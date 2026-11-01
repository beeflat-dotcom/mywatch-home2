# -*- coding: utf-8 -*-
"""Child master administration for Local AI Monitor.

Data direction: formal JSON -> address-book XLSX (never the reverse).
An authenticated NEC / paired-gateway caller can create or update a child.
The data-plane uses the existing UsageStore monthly JSON and basic profile JSON.
It never changes the date, store, pickup or attendance conditions of a visit.
"""
from __future__ import annotations

import copy
import hashlib
import hmac
import json
import os
import re
import secrets
import shutil
import subprocess
import time
import uuid
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Any

from fastapi import Body, Depends, FastAPI, HTTPException, Request

from bee_line_outbox import FileLease

VERSION = "2026-11-01-CHILD-ADMIN-01"
JST = timezone(timedelta(hours=9))
CHILD_ID_RE = re.compile(r"^child_[0-9a-f]{32}$")
ALLOWED = {
    "client_action_id", "child_id", "surname", "child_given_name",
    "guardian_given_name", "shop", "active", "burden_rate", "source",
    "expected_revision", "address", "phone", "school_name", "recipient_number",
    "school_address", "day_service_days", "daytime_days",
    "postal_code", "building_name", "birth_date", "clear_fields",
}
DETAILS = {
    "address": 300, "phone": 50, "school_name": 100, "recipient_number": 40,
    "school_address": 300, "postal_code": 16, "building_name": 100,
    "birth_date": 40,
}
DAY_FIELDS = {"day_service_days", "daytime_days"}
CLEARABLE = {"guardian_given_name", *DETAILS, *DAY_FIELDS}


class ChildAdminInvalid(ValueError):
    pass


class ChildAdminConflict(RuntimeError):
    pass


class ChildAdminUnavailable(RuntimeError):
    pass


def _stamp() -> str:
    return datetime.now(JST).isoformat(timespec="seconds")


def _read(path: Path) -> dict:
    try:
        d = json.loads(path.read_text(encoding="utf-8-sig"))
    except (OSError, ValueError, TypeError) as exc:
        raise ChildAdminUnavailable(f"正式JSONを読めません: {path.name}") from exc
    if not isinstance(d, dict):
        raise ChildAdminUnavailable(f"正式JSON形式が不正: {path.name}")
    return d


def _write_atomic(path: Path, d: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_name("." + path.name + "." + secrets.token_hex(6) + ".tmp")
    try:
        with tmp.open("x", encoding="utf-8", newline="\n") as handle:
            json.dump(d, handle, ensure_ascii=False, indent=2)
            handle.write("\n")
            handle.flush()
            os.fsync(handle.fileno())
        for n in range(8):
            try:
                os.replace(tmp, path)
                break
            except PermissionError:
                if n == 7:
                    raise
                time.sleep(0.1 * (n + 1))
    finally:
        tmp.unlink(missing_ok=True)


def _digest(d: Any) -> str:
    return hashlib.sha256(json.dumps(d, ensure_ascii=False, sort_keys=True,
           separators=(",", ":")).encode("utf-8")).hexdigest()


def _norm(s: str) -> str:
    return re.sub(r"\s+", "", s or "")


def _name_component(v: Any, label: str, *, required: bool) -> str:
    if not isinstance(v, str):
        raise ChildAdminInvalid(f"{label}は文字列で指定してください")
    v = v.strip()
    if not v and not required:
        return ""
    if not v or len(v) > 35 or re.search(r"\s", v) or any(ord(c) < 32 for c in v):
        raise ChildAdminInvalid(f"{label}は空白なしの名前だけを指定してください（35文字以内）")
    return v


def _rate(v: Any) -> float:
    if isinstance(v, bool) or not isinstance(v, (int, float)) or float(v) not in (0.0, 0.1):
        raise ChildAdminInvalid("burden_rateは0か0.1で指定してください")
    return float(v)


def _shop(v: Any) -> str:
    if v not in ("今", "福"):
        raise ChildAdminInvalid("店舗は「今」または「福」で指定してください")
    return "福成" if v == "福" else "今"


def _validate_payload(body: dict, kind: str) -> dict:
    if not isinstance(body, dict) or set(body) - ALLOWED:
        raise ChildAdminInvalid("利用者情報の入力項目が不正です")
    try:
        action_id = str(uuid.UUID(body["client_action_id"]))
    except (KeyError, ValueError, TypeError, AttributeError):
        raise ChildAdminInvalid("client_action_idにはUUIDを指定してください") from None
    if body.get("source", "android") not in ("android", "chatgpt", "nec"):
        raise ChildAdminInvalid("sourceはandroid/chatgpt/necにしてください")
    result = copy.deepcopy(body)
    result["client_action_id"] = action_id
    result["source"] = body.get("source", "android")

    if kind == "create":
        for key in ("surname", "child_given_name", "guardian_given_name"):
            if key not in body:
                raise ChildAdminInvalid(f"{key}は必須です")
        result["surname"] = _name_component(body["surname"], "苗字", required=True)
        result["child_given_name"] = _name_component(body["child_given_name"], "児童名", required=True)
        result["guardian_given_name"] = _name_component(body["guardian_given_name"], "保護者名", required=True)
        result["shop"] = _shop(body.get("shop"))
        result["burden_rate"] = _rate(body.get("burden_rate"))
        if body.get("child_id"):
            raise ChildAdminInvalid("新規登録時にchild_idは指定できません")
        if body.get("expected_revision") is not None:
            raise ChildAdminInvalid("新規登録にexpected_revisionは指定できません")
    else:
        cid = body.get("child_id")
        if not isinstance(cid, str) or not CHILD_ID_RE.fullmatch(cid):
            raise ChildAdminInvalid("既存の正式child_idを指定してください")
        for key in ("surname", "child_given_name"):
            if key not in body:
                raise ChildAdminInvalid(f"{key}は必須です")
            result[key] = _name_component(body[key], "苗字" if key == "surname" else "児童名", required=True)
        if "guardian_given_name" in body and body["guardian_given_name"]:
            result["guardian_given_name"] = _name_component(body["guardian_given_name"], "保護者名", required=True)
        if "shop" in body:
            result["shop"] = _shop(body["shop"])
        if "burden_rate" in body and body["burden_rate"] is not None:
            result["burden_rate"] = _rate(body["burden_rate"])
        if "expected_revision" in body:
            rev = body["expected_revision"]
            if type(rev) is not int or rev < 0:
                raise ChildAdminInvalid("expected_revisionは0以上の整数です")

    if "active" in body and type(body["active"]) is not bool:
        raise ChildAdminInvalid("activeはtrue/falseだけを指定してください")
    if kind == "create":
        result.setdefault("active", True)

    for key, max_chars in DETAILS.items():
        if key in body:
            v = body[key]
            if not isinstance(v, str) or len(v) > max_chars or any(ord(c) < 32 for c in v):
                raise ChildAdminInvalid(f"{key}が不正です")
            result[key] = v.strip()
    for key in DAY_FIELDS:
        if key in body:
            v = body[key]
            if v is not None and (type(v) is not int or not 0 <= v <= 31):
                raise ChildAdminInvalid(f"{key}は0～31の整数で指定してください")
    if "clear_fields" in body:
        fields = body["clear_fields"]
        if kind == "create" or not isinstance(fields, list) or len(fields) > 12 or any(
            not isinstance(s, str) or s not in CLEARABLE for s in fields
        ):
            raise ChildAdminInvalid("clear_fieldsが不正です")
        result["clear_fields"] = fields
    return result


class ChildAdminStore:
    def __init__(self, root: Path, *, transport=None, clock=None, trigger_export: bool = True):
        self.root = Path(root)
        self.folder = self.root / "利用希望JSON"
        self.basic_path = self.root / "児童基本情報.json"
        self.status_path = self.root / "児童利用状態.json"
        self.config_path = self.folder / "運用.json"
        self.audit_path = self.root / "児童登録変更履歴.json"
        self.archive_dir = self.root / "児童登録変更バックアップ"
        self.lock = self.folder / "更新.lock"
        self.transport = transport
        self.clock = clock or (lambda: datetime.now(JST))
        self.trigger_export = trigger_export

    def _month_and_paths(self) -> tuple[str, list[Path]]:
        month = self.clock().strftime("%Y-%m")
        current = self.folder / (month + ".json")
        if not current.is_file():
            raise ChildAdminUnavailable(f"{month}の正式利用希望JSONがありません")
        paths = sorted(p for p in self.folder.glob("????-??.json")
                       if p.stem >= month and re.fullmatch(r"\d{4}-\d{2}", p.stem))
        if not paths or current not in paths:
            raise ChildAdminUnavailable("正式な当月・将来月の利用者JSONを特定できません")
        return month, paths

    def _load(self):
        month, paths = self._month_and_paths()
        docs = {p: _read(p) for p in paths}
        active = docs[paths[0]]
        if active.get("schema_version") != 1 or not isinstance(active.get("children"), list):
            raise ChildAdminUnavailable("当月利用者JSONの形式が不正です")
        if any(x.get("schema_version") != 1 or not isinstance(x.get("children"), list) for x in docs.values()):
            raise ChildAdminUnavailable("将来月の利用者JSONの形式が不正です")
        basic = _read(self.basic_path)
        state = _read(self.status_path)
        config = _read(self.config_path)
        if (basic.get("schema_version") != 1 or not isinstance(basic.get("children"), dict)
            or state.get("schema_version") != 1 or not isinstance(state.get("children"), dict)
            or config.get("schema_version") != 1 or config.get("mode") != "json_primary"):
            raise ChildAdminUnavailable("既存マスタの形式が一致しません")
        ids = [c.get("child_id") for c in active["children"]]
        nums = [c.get("no") for c in active["children"]]
        if len(ids) != len(set(ids)) or any(not CHILD_ID_RE.fullmatch(str(i)) for i in ids):
            raise ChildAdminUnavailable("正式児童IDが不正または重複しています")
        if any(type(n) is not int or n <= 0 for n in nums) or len(nums) != len(set(nums)):
            raise ChildAdminUnavailable("正式利用希望番号が不正または重複しています")
        return month, docs, basic, state, config

    @staticmethod
    def _lookup(doc: dict, child_id: str) -> dict:
        rows = [c for c in doc["children"] if c["child_id"] == child_id]
        if len(rows) != 1:
            raise ChildAdminInvalid("指定された児童IDが正式マスタ内で一意に見つかりません")
        return rows[0]

    @staticmethod
    def _candidate(child: dict, basics: dict, state: dict) -> dict:
        c = dict(child)
        b = basics.get("children", {}).get(c["child_id"], {})
        if not isinstance(b, dict):
            b = {}
        fullname = str(c.get("name") or "").strip()
        parts = re.split(r"\s+", fullname, maxsplit=1)
        c["surname"] = str(b.get("surname") or c.get("surname") or (parts[0] if parts else ""))
        c["child_given_name"] = str(
            b.get("child_given_name") or c.get("child_given_name") or (parts[1] if len(parts) > 1 else "")
        )
        c["guardian_given_name"] = str(b.get("guardian_given_name") or "")
        c["active"] = bool(state.get("children", {}).get(c["child_id"], c.get("active", False)))
        c["burden_rate"] = b.get("burden_rate")
        c["usage_request_no"] = b.get("usage_request_no", c.get("no"))
        for key in (set(DETAILS) | DAY_FIELDS):
            c[key] = b.get(key, "")
        return c

    def options(self) -> dict:
        _, docs, basic, _, _ = self._load()
        all_nums = [c["no"] for d in docs.values() for c in d["children"]]
        all_nums.extend(v.get("usage_request_no") for v in basic["children"].values()
                        if isinstance(v, dict) and type(v.get("usage_request_no")) is int)
        return {
            "ok": True, "version": VERSION, "shops": ["今", "福"],
            "burden_rates": [0.0, 0.1], "next_usage_request_no": max(all_nums or [0]) + 1,
        }

    def get(self, child_id: str) -> dict:
        if not CHILD_ID_RE.fullmatch(child_id or ""):
            raise ChildAdminInvalid("child_idの形式が不正です")
        _, docs, basic, state, _ = self._load()
        child = self._lookup(next(iter(docs.values())), child_id)
        c = self._candidate(child, basic, state)
        return {"ok": True, "version": VERSION, "status": "found", "child_id": child_id,
                "usage_request_no": c["usage_request_no"], "child": c,
                "revision": next(iter(docs.values()))["revision"]}

    @staticmethod
    def _short_name(surname: str, given: str, used: set[str]) -> str:
        for alias in (surname, surname + given[:1], surname + given[:2], surname + given):
            if _norm(alias) not in used:
                return alias
        raise ChildAdminConflict("略称が重複しています。別の略称が必要です")

    @staticmethod
    def _transaction(changes: dict[Path, dict], archive: Path) -> None:
        """Backup, stage and atomically replace each file. Roll back on raised errors."""
        archive.mkdir(parents=True, exist_ok=False)
        staged = {}
        snapshots = {}
        applied = []
        manifest = {"created_at": _stamp(), "state": "preparing", "paths": []}
        try:
            for idx, (path, data) in enumerate(changes.items()):
                if not path.parent.is_dir():
                    raise ChildAdminUnavailable("正式保存先ディレクトリがありません")
                filename = f"{idx:02d}_{path.name}"
                old_file = archive / filename
                if path.exists():
                    shutil.copy2(path, old_file)
                    snapshots[path] = old_file
                else:
                    snapshots[path] = None
                temp = path.with_name("." + path.name + "." + secrets.token_hex(6) + ".tmp")
                _write_atomic(temp, data)  # writes atomically to a temporary target
                staged[path] = temp
                manifest["paths"].append({"name": path.name, "original": str(path),
                                           "backup": str(old_file) if old_file.exists() else ""})
            _write_atomic(archive / "transaction.json", {**manifest, "state": "prepared"})
            for path, temp in staged.items():
                for attempt in range(8):
                    try:
                        os.replace(temp, path)
                        applied.append(path)
                        break
                    except PermissionError:
                        if attempt == 7:
                            raise
                        time.sleep((attempt+1)*0.10)
            _write_atomic(archive / "transaction.json", {**manifest, "state": "committed"})
        except Exception:
            restore_errors = []
            for path in reversed(applied):
                try:
                    if snapshots[path]:
                        original = snapshots[path]
                        restore_temp = path.with_name("." + path.name + "." + secrets.token_hex(6) + ".restore")
                        shutil.copy2(original, restore_temp)
                        os.replace(restore_temp, path)
                    else:
                        path.unlink(missing_ok=True)
                except Exception as exc:
                    restore_errors.append(path.name + ":" + type(exc).__name__)
            _write_atomic(archive / "transaction.json",
                          {**manifest, "state": "restore_failed" if restore_errors else "rolled_back",
                           "restore_errors": restore_errors})
            if restore_errors:
                raise ChildAdminUnavailable("保存時の復元に失敗しました。バックアップを確認してください")
            raise
        finally:
            for temp in staged.values():
                temp.unlink(missing_ok=True)

    def _notify_export(self) -> str:
        if not self.trigger_export or os.name != "nt":
            return "scheduled"
        try:
            result = subprocess.run(
                ["schtasks.exe", "/Run", "/TN", "Bee-SimpleAddressBook-AutoExport"],
                capture_output=True, timeout=8, check=False,
                creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0),
            )
            return "triggered" if result.returncode == 0 else "scheduled"
        except (OSError, subprocess.TimeoutExpired):
            return "scheduled"

    def apply(self, body: dict, kind: str) -> dict:
        if kind not in ("create", "update"):
            raise ChildAdminInvalid("操作が不正です")
        values = _validate_payload(body, kind)
        action_id = values["client_action_id"]
        checksum = _digest({"operation": kind, "body": values})
        # The same file lock is used by the formal usage JSON writer.
        with FileLease(self.lock, 15):
            transport_lock = self.transport._lock if self.transport else None
            if transport_lock:
                transport_lock.acquire()
            try:
                month, docs, basic, state, config = self._load()
                audit = _read(self.audit_path) if self.audit_path.is_file() else {
                    "schema_version": 1, "revision": 0, "updated_at": "",
                    "actions": {}, "history": [],
                }
                if (audit.get("schema_version") != 1 or not isinstance(audit.get("actions"), dict)):
                    raise ChildAdminUnavailable("利用者変更履歴の形式が不正です")
                prev = audit["actions"].get(action_id)
                if prev is not None:
                    if prev["checksum"] != checksum:
                        raise ChildAdminConflict("同じclient_action_idが別の内容で使われています")
                    out = dict(prev["result"])
                    out["status"] = "duplicate"
                    return out

                current_path = self.folder / (month + ".json")
                original = docs[current_path]
                all_names = {_norm(c["name"]): c["child_id"] for c in original["children"]}
                basic_children = basic["children"]
                if kind == "create":
                    fullname = values["surname"] + "　" + values["child_given_name"]
                    if _norm(fullname) in all_names:
                        raise ChildAdminConflict("同姓同名の児童が既に登録されています。既存IDを確認してください")
                    all_nums = [c["no"] for d in docs.values() for c in d["children"]]
                    all_nums.extend(v.get("usage_request_no") for v in basic_children.values()
                                    if isinstance(v, dict) and type(v.get("usage_request_no")) is int)
                    next_no = max(all_nums or [0]) + 1
                    cid = "child_" + uuid.uuid4().hex
                    used = {_norm(c.get("short_name", "")) for c in original["children"]}
                    short_name = self._short_name(values["surname"], values["child_given_name"], used)
                    row = {
                        "child_id": cid, "name": fullname, "child_name": fullname,
                        "short_name": short_name, "line_group": "", "no": next_no,
                        "shop": values["shop"], "active": values["active"],
                        "active_basis": "child_admin_explicit",
                        "surname": values["surname"], "child_given_name": values["child_given_name"],
                    }
                    before = {}
                    old_info = {}
                else:
                    cid = values["child_id"]
                    if cid not in all_names.values():
                        raise ChildAdminInvalid("登録済み児童IDが見つかりません")
                    orig_row = self._lookup(original, cid)
                    old_info = copy.deepcopy(basic_children.get(cid, {}))
                    before = self._candidate(orig_row, basic, state)
                    expected = values.get("expected_revision")
                    if expected is not None and expected != original["revision"]:
                        raise ChildAdminConflict("確認後に児童マスタの版が変わりました。再読み込みしてください")
                    fullname = values["surname"] + "　" + values["child_given_name"]
                    existing_name_id = all_names.get(_norm(fullname))
                    if existing_name_id is not None and existing_name_id != cid:
                        raise ChildAdminConflict("同姓同名の別児童が登録されています")
                    next_no = orig_row["no"]
                    row = dict(orig_row)
                    prior_parts = re.split(r"\s+", orig_row["name"].strip(), maxsplit=1)
                    prior_surname = prior_parts[0]
                    if values["surname"] != prior_surname and _norm(row["short_name"]) == _norm(prior_surname):
                        used = {_norm(c.get("short_name", "")) for c in original["children"] if c["child_id"] != cid}
                        row["short_name"] = self._short_name(values["surname"], values["child_given_name"], used)
                    row["name"] = row["child_name"] = fullname
                    row["surname"] = values["surname"]
                    row["child_given_name"] = values["child_given_name"]
                    if "shop" in values:
                        row["shop"] = values["shop"]
                    row["active"] = values.get("active", before["active"])
                    row["active_basis"] = "child_admin_explicit"

                info = dict(old_info)
                info.update({
                    "surname": values["surname"],
                    "child_given_name": values["child_given_name"],
                    "usage_request_no": next_no,
                    "updated_at": _stamp(),
                    "updated_by": values["source"],
                })
                if kind == "create":
                    info["guardian_given_name"] = values["guardian_given_name"]
                    info["burden_rate"] = values["burden_rate"]
                elif values.get("guardian_given_name"):
                    info["guardian_given_name"] = values["guardian_given_name"]
                if "burden_rate" in values:
                    info["burden_rate"] = values["burden_rate"]
                for key in (set(DETAILS) | DAY_FIELDS):
                    if key in values:
                        info[key] = values[key]
                for key in values.get("clear_fields", []):
                    info[key] = None if key in DAY_FIELDS else ""
                basic_children[cid] = info
                basic["revision"] = int(basic["revision"]) + 1
                basic["updated_at"] = _stamp()

                for path, doc in docs.items():
                    if kind == "create":
                        if any(c["child_id"] == cid or _norm(c["name"]) == _norm(fullname) for c in doc["children"]):
                            raise ChildAdminConflict("将来月の利用者一覧に重複があります")
                        doc["children"].append(dict(row))
                        doc["children"].sort(key=lambda c: (c["no"], c["child_id"]))
                    else:
                        target = self._lookup(doc, cid)
                        target.update({
                            "name": row["name"], "child_name": row["child_name"],
                            "short_name": row["short_name"], "shop": row["shop"],
                            "active": row["active"], "active_basis": row["active_basis"],
                            "surname": row["surname"], "child_given_name": row["child_given_name"],
                        })
                        # Name is a label; keep every visit date, store, transport and attendance unchanged.
                        for rec in doc["records"].values():
                            if rec["child_id"] == cid:
                                rec["child_name"] = fullname
                    doc["revision"] = int(doc["revision"]) + 1
                    doc["updated_at"] = _stamp()

                state["children"][cid] = bool(row["active"])
                if isinstance(state.get("child_names"), dict):
                    state["child_names"][cid] = fullname
                state["updated_by"] = "child_admin:" + values["source"]
                state["instructed_at"] = _stamp()
                state["source_master"] = month + ".json"
                state["source_revision"] = docs[current_path]["revision"]
                config["master_month"] = month

                candidate = self._candidate(row, basic, state)
                result = {
                    "ok": True, "status": "completed", "version": VERSION,
                    "child_id": cid, "usage_request_no": next_no, "child": candidate,
                    "revision": docs[current_path]["revision"],
                }
                audit_entry = {
                    "client_action_id": action_id, "kind": kind, "source": values["source"],
                    "child_id": cid, "at": _stamp(),
                    "previous": {k: before.get(k) for k in (
                        "name", "shop", "active", "short_name", "guardian_given_name", "burden_rate"
                    )} if before else None,
                    "new": {k: candidate.get(k) for k in (
                        "name", "shop", "active", "short_name", "guardian_given_name", "burden_rate"
                    )},
                }
                audit["revision"] = int(audit.get("revision", 0)) + 1
                audit["updated_at"] = _stamp()
                audit["actions"][action_id] = {"checksum": checksum, "result": result}
                audit.setdefault("history", []).append(audit_entry)

                changes = {**docs, self.basic_path: basic, self.status_path: state,
                           self.config_path: config, self.audit_path: audit}
                archive = self.archive_dir / (self.clock().strftime("%Y%m%d_%H%M%S_%f") +
                                             "_" + action_id.replace("-", ""))
                self._transaction(changes, archive)
            finally:
                if transport_lock:
                    transport_lock.release()

        result["address_book_refresh"] = self._notify_export()
        return result


def install_child_admin_api(app: FastAPI, root: Path, transport=None) -> ChildAdminStore:
    store = ChildAdminStore(root, transport=transport)

    def require_admin(request: Request) -> None:
        local = os.getenv("LOCALAPPDATA")
        if not local:
            raise HTTPException(503, "NEC child admin credential is unavailable")
        key_path = Path(local) / "BeeMonitorGateway" / "child_admin_secret.txt"
        try:
            expected = key_path.read_text(encoding="ascii").strip()
        except OSError:
            raise HTTPException(503, "NEC child admin credential is unavailable") from None
        supplied = request.headers.get("x-bee-admin-secret", "")
        if len(expected) < 48 or not hmac.compare_digest(supplied, expected):
            raise HTTPException(403, "管理認証が必要です")

    def checked(f, *args, **kwargs):
        try:
            return f(*args, **kwargs)
        except ChildAdminInvalid as exc:
            raise HTTPException(400, str(exc)) from exc
        except ChildAdminConflict as exc:
            raise HTTPException(409, str(exc)) from exc
        except ChildAdminUnavailable as exc:
            raise HTTPException(503, str(exc)) from exc
        except TimeoutError:
            raise HTTPException(503, "マスタの更新中です。再試行してください") from None

    @app.get("/child-admin/options", dependencies=[Depends(require_admin)])
    def child_admin_options():
        return checked(store.options)

    @app.get("/child-admin/{child_id}", dependencies=[Depends(require_admin)])
    def child_admin_get(child_id: str):
        return checked(store.get, child_id)

    @app.post("/child-admin/create", dependencies=[Depends(require_admin)])
    def child_admin_create(body: dict = Body(...)):
        return checked(store.apply, body, "create")

    @app.post("/child-admin/update", dependencies=[Depends(require_admin)])
    def child_admin_update(body: dict = Body(...)):
        return checked(store.apply, body, "update")

    return store
