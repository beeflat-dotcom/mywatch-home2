# -*- coding: utf-8 -*-
"""Synthetic-only regression suite for retirement of present and archived children."""
from __future__ import annotations

import copy
import json
import os
import tempfile
import threading
import uuid
from datetime import datetime, timedelta, timezone
from pathlib import Path

from fastapi import FastAPI
from fastapi.testclient import TestClient

from bee_child_admin_api import ChildAdminStore
from bee_child_retire_api import ChildRetirementStore, install_child_retire_api

JST = timezone(timedelta(hours=9))
CID = "child_" + "a" * 32


def write(path: Path, obj: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(obj, ensure_ascii=False, indent=2), encoding="utf-8")


def setup(root: Path) -> None:
    f = root / "利用希望JSON"
    child = {
        "child_id": CID,
        "name": "森河　愛花", "child_name": "森河　愛花",
        "short_name": "森河", "no": 31, "shop": "福成",
        "active": False, "active_basis": "old_import",
        "line_group": "PLACEHOLDER",
    }
    for month in (10, 11, 12):
        records = {"test": {"child_id": CID, "date": "2026-11-05",
                            "pickup": True, "shop": "福成", "start_time": "13:30",
                            "child_name": child["name"]}} if month == 11 else {}
        write(f / f"2026-{month:02d}.json", {
            "schema_version": 1, "revision": 20, "year": 2026, "month": month,
            "children": [copy.deepcopy(child)], "records": records,
            "events": {}, "primary_basis": "python_json",
        })
    write(f / "運用.json", {
        "schema_version": 1, "mode": "json_primary", "master_month": "2026-10",
    })
    write(root / "児童基本情報.json", {
        "schema_version": 1, "revision": 1, "children": {
            CID: {"guardian_given_name": "お母", "burden_rate": 0.1,
                  "usage_request_no": 31},
        }, "recent_actions": {},
    })
    write(root / "児童利用状態.json", {
        "schema_version": 1,
        "children": {CID: False},
        "child_names": {CID: "森河　愛花"},
    })


def run() -> None:
    with tempfile.TemporaryDirectory(prefix="bee_retirement_synthetic_") as td:
        root = Path(td) / "client"
        root.mkdir()
        setup(root)
        archive_root = Path(td) / "退所者用"
        for label in ("50宇高孝志郎", "67森河健吾"):
            (archive_root / label).mkdir(parents=True)
        class FakeTransport:
            _lock = threading.RLock()
        now=lambda: datetime(2026,11,1,21,0,0,tzinfo=JST)
        admin=ChildAdminStore(root,transport=FakeTransport(),clock=now,trigger_export=False)
        retirement=ChildRetirementStore(root,admin,archive_root=archive_root)
        initial=(root/"利用希望JSON/2026-10.json").read_bytes()
        before=json.loads((root/"利用希望JSON/2026-11.json").read_text(encoding="utf-8"))
        current_req={"client_action_id":str(uuid.uuid4()),"child_id":CID,
                     "full_name":"森河　愛花","source":"chatgpt"}
        updated=retirement.retire(current_req)
        assert updated["mode"]=="current_master" and updated["previously_inactive"]
        assert updated["effective_date"] is None
        assert updated["paperwork_status"]=="未確認"
        assert updated["child_id"]==CID
        assert (root/"利用希望JSON/2026-10.json").read_bytes()==initial
        after=json.loads((root/"利用希望JSON/2026-11.json").read_text(encoding="utf-8"))
        assert after["records"]==before["records"]
        assert after["children"][0]["active"] is False
        assert after["children"][0]["active_basis"]=="child_admin_retired"
        basic=json.loads((root/"児童基本情報.json").read_text(encoding="utf-8"))
        assert basic["children"][CID]["retirement_status"]=="retired"
        assert basic["children"][CID]["retirement_effective_date"] is None
        assert json.loads((root/"利用希望JSON/運用.json").read_text(encoding="utf-8"))["master_month"]=="2026-11"
        assert retirement.retire(current_req)["status"]=="duplicate"
        n=admin.options()["next_usage_request_no"]
        assert n==32
        for name in ("宇高　孝志郎", "森河　健吾"):
            req={"client_action_id":str(uuid.uuid4()),"full_name":name,
                 "source":"chatgpt","note":"退所指示（退所日未指定）"}
            result=retirement.retire(req)
            assert result["mode"]=="archived_only" and result["child_id"]==""
            assert retirement.retire(req)["status"]=="duplicate"
            other=retirement.retire({**req,"client_action_id":str(uuid.uuid4())})
            assert other["status"]=="already_retired"
        assert admin.options()["next_usage_request_no"]==n
        after2=json.loads((root/"利用希望JSON/2026-11.json").read_text(encoding="utf-8"))
        assert after2==after, "Archived-only retirement must not rewrite current master"
        assert (root/"利用希望JSON/2026-10.json").read_bytes()==initial
        registry=retirement.list_retired()
        assert registry["retired_count"]==3
        assert {e["full_name"] for e in registry["entries"]}=={
            "森河　愛花","宇高　孝志郎","森河　健吾"}
        assert len(list((root/"児童登録変更バックアップ").iterdir()))==3
        for arc in (root/"児童登録変更バックアップ").iterdir():
            assert json.loads((arc/"transaction.json").read_text(encoding="utf-8"))["state"]=="committed"
        assert len(json.loads((root/"児童登録変更履歴.json").read_text(encoding="utf-8"))["history"])==3
        assert "森河　愛花" in str(registry)

    with tempfile.TemporaryDirectory(prefix="bee_retirement_auth_") as td:
        root=Path(td)/"client"
        setup(root)
        local=Path(td)/"local"
        creds=local/"BeeMonitorGateway"
        creds.mkdir(parents=True)
        (creds/"child_admin_secret.txt").write_text("d"*64,encoding="ascii")
        old=os.environ.get("LOCALAPPDATA")
        os.environ["LOCALAPPDATA"]=str(local)
        try:
            admin=ChildAdminStore(root,trigger_export=False)
            store=ChildRetirementStore(root,admin,archive_root=Path(td)/"archives")
            app=FastAPI()
            install_child_retire_api(app,store)
            client=TestClient(app)
            assert client.get("/child-admin/retired").status_code==403
            assert client.post("/child-admin/retire",json={}).status_code==403
            headers={"X-Bee-Admin-Secret":"d"*64}
            assert client.get("/child-admin/retired",headers=headers).json()["retired_count"]==0
            bad=client.post("/child-admin/retire",headers=headers,json={
                "client_action_id":str(uuid.uuid4()),"full_name":"森河 愛花",
                "child_id":CID,"effective_date":"2026-99-99",
            })
            assert bad.status_code==400,bad.text
            req=client.post("/child-admin/retire",headers=headers,json={
                "client_action_id":str(uuid.uuid4()),"full_name":"森河 愛花",
                "child_id":CID,"source":"android",
            })
            assert req.status_code==200,req.text
            assert req.json()["retirement_status"]=="retired"
            assert client.get("/child-admin/retired",headers=headers).json()["retired_count"]==1
        finally:
            if old is None:os.environ.pop("LOCALAPPDATA",None)
            else:os.environ["LOCALAPPDATA"]=old
    print("RETIREMENT_TEST_OK: registered + archived, zero historical changes, duplicate prevention, audit and auth")


if __name__=="__main__":
    run()
