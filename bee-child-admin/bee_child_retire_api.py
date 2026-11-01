# -*- coding: utf-8 -*-
"""Retirement administration for registered or already-archived children.

A retirement records an administrative status, never deletes visit history.
Effective retirement date is intentionally unknown unless explicitly supplied.
Existing children retain their stable child_id and usage request number.
Previously archived children are logged without being resurrected in the master.
"""
from __future__ import annotations

import copy
import hmac
import os
import re
import uuid
from datetime import date
from pathlib import Path
from typing import Any

from fastapi import Body, Depends, FastAPI, HTTPException, Request

from bee_child_admin_api import (
    ChildAdminStore, ChildAdminInvalid, ChildAdminConflict, ChildAdminUnavailable,
    _digest, _read, _stamp,
)
from bee_line_outbox import FileLease

VERSION = "2026-11-01-CHILD-RETIRE-01"
ALLOWED = {"client_action_id", "child_id", "full_name", "source", "effective_date", "note"}
SOURCES = {"chatgpt", "android", "nec"}


def norm(name: str) -> str:
    return re.sub(r"\s+", "", name or "")


def parse_payload(body: Any) -> dict:
    if not isinstance(body, dict) or set(body) - ALLOWED:
        raise ChildAdminInvalid("退所入力項目に誤りがあります")
    try:
        action_id = str(uuid.UUID(body["client_action_id"]))
    except (KeyError, ValueError, TypeError, AttributeError):
        raise ChildAdminInvalid("client_action_idはUUIDで指定してください") from None
    full_name = body.get("full_name")
    if not isinstance(full_name, str):
        raise ChildAdminInvalid("full_name（児童の正式氏名）が必要です")
    parts = re.split(r"[\s\u3000]+", full_name.strip())
    if len(parts) != 2 or any(not p or len(p) > 35 for p in parts):
        raise ChildAdminInvalid("full_nameは「苗字　児童名」の2要素で指定してください")
    if any(any(ord(ch) < 32 for ch in p) for p in parts):
        raise ChildAdminInvalid("児童名に制御文字を含められません")
    name = parts[0] + "\u3000" + parts[1]
    source = body.get("source", "chatgpt")
    if source not in SOURCES:
        raise ChildAdminInvalid("sourceに誤りがあります")
    child_id = body.get("child_id", "")
    if child_id and (not isinstance(child_id, str) or not re.fullmatch(r"child_[0-9a-f]{32}", child_id)):
        raise ChildAdminInvalid("child_idに誤りがあります")
    effective = body.get("effective_date")
    if effective is not None:
        if not isinstance(effective, str) or not re.fullmatch(r"\d{4}-\d{2}-\d{2}", effective):
            raise ChildAdminInvalid("effective_dateはYYYY-MM-DDまたはnullです")
        try:
            date.fromisoformat(effective)
        except ValueError as exc:
            raise ChildAdminInvalid("退所日が不正です") from exc
    note = body.get("note", "")
    if not isinstance(note, str) or len(note) > 250 or any(ord(c) < 32 for c in note):
        raise ChildAdminInvalid("noteの書式が不正です")
    return {
        "client_action_id": action_id,
        "full_name": name,
        "child_id": child_id,
        "source": source,
        "effective_date": effective,
        "note": note,
    }


class ChildRetirementStore:
    def __init__(self, root: Path, admin: ChildAdminStore,
                 *, archive_root: Path | None = None):
        self.root = Path(root)
        self.admin = admin
        self.file = self.root / "児童退所記録.json"
        self.archive_root = archive_root or Path.home() / "Dropbox" / "BU" / "受給者証" / "退所者用"

    def _archived_dir(self, full_name: str) -> Path:
        if not self.archive_root.is_dir():
            raise ChildAdminUnavailable("退所者用の受給者証フォルダが見つかりません")
        matches = [
            p for p in self.archive_root.iterdir()
            if p.is_dir() and norm(re.sub(r"^\d+", "", p.name)) == norm(full_name)
        ]
        if len(matches) != 1:
            raise ChildAdminInvalid("現行名簿にない児童は、退所者用フォルダの正式氏名と1件一致する必要があります")
        return matches[0]

    def list_retired(self) -> dict:
        ledger = _read(self.file) if self.file.is_file() else {"entries": {}}
        if not isinstance(ledger.get("entries"), dict):
            raise ChildAdminUnavailable("退所記録の形式が不正です")
        entries = []
        for key, item in sorted(ledger["entries"].items()):
            if isinstance(item, dict):
                entries.append({
                    "full_name": item.get("full_name", ""),
                    "child_id": item.get("child_id", ""),
                    "mode": item.get("mode", ""),
                    "retirement_status": item.get("retirement_status", ""),
                    "effective_date": item.get("effective_date"),
                    "recorded_at": item.get("recorded_at"),
                    "paperwork_status": item.get("paperwork_status", "未確認"),
                })
        return {"ok": True, "version": VERSION, "retired_count": len(entries), "entries": entries}

    def retire(self, body: dict) -> dict:
        inp = parse_payload(body)
        checksum = _digest(inp)
        key = norm(inp["full_name"])
        with FileLease(self.admin.lock, 15):
            tlock = self.admin.transport._lock if self.admin.transport else None
            if tlock:
                tlock.acquire()
            try:
                month, docs, basics, state, config = self.admin._load()
                now = _stamp()
                ledger = _read(self.file) if self.file.is_file() else {
                    "schema_version": 1, "revision": 0,
                    "updated_at": "", "entries": {}, "actions": {}, "history": [],
                }
                if (ledger.get("schema_version") != 1 or
                    not isinstance(ledger.get("entries"), dict) or
                    not isinstance(ledger.get("actions"), dict) or
                    not isinstance(ledger.get("history"), list)):
                    raise ChildAdminUnavailable("退所台帳の形式が不正です")
                previous = ledger["actions"].get(inp["client_action_id"])
                if previous is not None:
                    if previous.get("checksum") != checksum:
                        raise ChildAdminConflict("同じclient_action_idが別の退所内容に使用されています")
                    result = dict(previous["result"])
                    result["status"] = "duplicate"
                    return result

                if key in ledger["entries"]:
                    existing = ledger["entries"][key]
                    if existing.get("retirement_status") == "retired":
                        return {
                            "ok": True, "status": "already_retired",
                            "version": VERSION,
                            "full_name": inp["full_name"],
                            "child_id": existing.get("child_id", ""),
                            "mode": existing.get("mode", ""),
                            "effective_date": existing.get("effective_date"),
                            "recorded_at": existing.get("recorded_at"),
                        }

                present = [
                    c for c in next(iter(docs.values()))["children"]
                    if norm(c.get("name", "")) == key
                ]
                if len(present) > 1:
                    raise ChildAdminConflict("現行名簿に同姓同名の重複があります")
                candidate = present[0] if present else None
                if inp["child_id"]:
                    if not candidate or candidate["child_id"] != inp["child_id"]:
                        raise ChildAdminConflict("児童IDと正式氏名が一致しません")
                if candidate is None:
                    if inp["child_id"]:
                        raise ChildAdminInvalid("指定IDは現行名簿にありません")
                    folder = self._archived_dir(inp["full_name"])
                    mode = "archived_only"
                    cid = ""
                    reference = str(folder.relative_to(Path.home() / "Dropbox"))
                    changes = {}
                    already_inactive = True
                else:
                    cid = candidate["child_id"]
                    mode = "current_master"
                    reference = ""
                    already_inactive = not bool(state["children"].get(cid, candidate.get("active", False)))
                    for p, doc in docs.items():
                        matches = [c for c in doc["children"] if c.get("child_id") == cid]
                        if len(matches) != 1:
                            raise ChildAdminConflict("当月・将来月の利用者マスタで児童IDが一致しません")
                        row = matches[0]
                        if norm(row.get("name","")) != key:
                            raise ChildAdminConflict("当月・将来月で氏名が一致しません")
                        row["active"] = False
                        row["active_basis"] = "child_admin_retired"
                        doc["revision"] = int(doc.get("revision", 0)) + 1
                        doc["updated_at"] = now
                    basics["children"].setdefault(cid, {})
                    info = basics["children"][cid]
                    if not isinstance(info, dict):
                        raise ChildAdminUnavailable("児童基本情報の形式が不正です")
                    info["retired"] = True
                    info["retirement_status"] = "retired"
                    info["retirement_recorded_at"] = now
                    info["retirement_effective_date"] = inp["effective_date"]
                    info["retirement_source"] = inp["source"]
                    info["updated_at"] = now
                    info["updated_by"] = inp["source"]
                    basics["revision"] = int(basics.get("revision", 0)) + 1
                    basics["updated_at"] = now
                    state["children"][cid] = False
                    state["updated_by"] = "child_admin:retire"
                    state["instructed_at"] = now
                    state["source_master"] = month + ".json"
                    state["source_revision"] = docs[self.admin.folder / (month + ".json")]["revision"]
                    config["master_month"] = month
                    changes = {
                        **docs, self.admin.basic_path: basics,
                        self.admin.status_path: state,
                        self.admin.config_path: config,
                    }
                row = {
                    "full_name": inp["full_name"],
                    "child_id": cid, "mode": mode,
                    "retirement_status": "retired",
                    "recorded_at": now,
                    "effective_date": inp["effective_date"],
                    "paperwork_status": "未確認",
                    "source": inp["source"],
                    "archived_reference": reference,
                    "note": inp["note"],
                }
                ledger["entries"][key] = row
                result = {
                    "ok": True, "status": "completed", "version": VERSION,
                    "full_name": inp["full_name"], "child_id": cid,
                    "mode": mode, "retirement_status": "retired",
                    "effective_date": inp["effective_date"],
                    "recorded_at": now, "previously_inactive": already_inactive,
                    "paperwork_status": "未確認",
                }
                ledger["revision"] = int(ledger.get("revision", 0)) + 1
                ledger["updated_at"] = now
                ledger["actions"][inp["client_action_id"]] = {
                    "checksum": checksum, "result": result,
                }
                ledger["history"].append({
                    "client_action_id": inp["client_action_id"],
                    "full_name": inp["full_name"], "mode": mode,
                    "at": now, "source": inp["source"],
                })
                changes[self.file] = ledger

                audit = _read(self.admin.audit_path) if self.admin.audit_path.is_file() else {
                    "schema_version": 1, "revision": 0,
                    "updated_at": "", "actions": {}, "history": [],
                }
                if audit.get("schema_version") != 1 or not isinstance(audit.get("actions"), dict):
                    raise ChildAdminUnavailable("変更履歴JSONの形式が不正です")
                audit["revision"] = int(audit.get("revision", 0)) + 1
                audit["updated_at"] = now
                audit["actions"][inp["client_action_id"]] = {
                    "checksum": checksum, "result": result,
                }
                audit.setdefault("history", []).append({
                    "client_action_id": inp["client_action_id"],
                    "kind": "retire", "child_id": cid,
                    "full_name": inp["full_name"], "at": now,
                    "source": inp["source"],
                    "mode": mode,
                })
                changes[self.admin.audit_path] = audit
                archive = self.admin.archive_dir / (
                    self.admin.clock().strftime("%Y%m%d_%H%M%S_%f") + "_" +
                    inp["client_action_id"].replace("-", "")
                )
                self.admin._transaction(changes, archive)
            finally:
                if tlock:
                    tlock.release()
        result["address_book_refresh"] = self.admin._notify_export()
        return result


def install_child_retire_api(app: FastAPI, store: ChildRetirementStore) -> ChildRetirementStore:
    def require_admin(request: Request) -> None:
        local = os.getenv("LOCALAPPDATA")
        if not local:
            raise HTTPException(503, "管理認証キーがありません")
        p = Path(local) / "BeeMonitorGateway" / "child_admin_secret.txt"
        try:
            expected = p.read_text(encoding="ascii").strip()
        except OSError:
            raise HTTPException(503, "管理認証キーを読めません") from None
        supplied = request.headers.get("x-bee-admin-secret", "")
        if len(expected) < 48 or not hmac.compare_digest(expected, supplied):
            raise HTTPException(403, "管理認証が必要です")

    @app.get("/child-admin/retired", dependencies=[Depends(require_admin)])
    def retirement_registry():
        return store.list_retired()

    @app.post("/child-admin/retire", dependencies=[Depends(require_admin)])
    def retirement_record(body: dict = Body(...)):
        try:
            return store.retire(body)
        except ChildAdminInvalid as exc:
            raise HTTPException(400, str(exc)) from exc
        except ChildAdminConflict as exc:
            raise HTTPException(409, str(exc)) from exc
        except (ChildAdminUnavailable, TimeoutError) as exc:
            raise HTTPException(503, str(exc)) from exc

    return store
