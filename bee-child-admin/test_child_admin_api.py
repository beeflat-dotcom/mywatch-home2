# -*- coding: utf-8 -*-
"""Dry-run tests against synthetic child JSON only. Never use live user data."""
from __future__ import annotations

import copy
import hashlib
import json
import os
import tempfile
import threading
import uuid
from datetime import datetime, timedelta, timezone
from pathlib import Path

from fastapi import FastAPI
from fastapi.testclient import TestClient

from bee_child_admin_api import (
    ChildAdminStore, ChildAdminConflict, ChildAdminInvalid, install_child_admin_api,
)

JST = timezone(timedelta(hours=9))


def dump(path, content):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(content, ensure_ascii=False, indent=2), encoding="utf-8")


def init(root):
    f = root / "利用希望JSON"
    f.mkdir(parents=True)
    first = {
        "child_id": "child_" + "a" * 32,
        "name": "仮　児童", "child_name": "仮　児童",
        "short_name": "仮", "line_group": "", "no": 1, "shop": "今",
        "active": True, "active_basis": "imported",
    }
    for m in (10, 11, 12):
        records = {"child_" + "a"*32 + "|2026-11-05": {
            "child_id": first["child_id"], "child_name": first["name"],
            "date": "2026-11-05", "no": 1, "shop": "今",
            "use_type": "デイ", "pickup": True, "dropoff": False,
            "start_time": "14:00", "end_time": "17:00", "lunch": False,
        }} if m == 11 else {}
        dump(f / f"2026-{m:02d}.json", {
            "schema_version": 1, "year": 2026, "month": m, "revision": 1,
            "children": [copy.deepcopy(first)], "records": records,
            "events": {}, "projections": {}, "request_history": [],
            "updated_at": "before",
        })
    dump(f / "運用.json", {"schema_version":1, "mode":"json_primary", "master_month":"2026-10"})
    dump(root / "児童基本情報.json", {
        "schema_version":1, "revision":1, "updated_at":"before",
        "children": {first["child_id"]: {"burden_rate":0.1, "usage_request_no":1}},
        "recent_actions":{},
    })
    dump(root / "児童利用状態.json", {
        "schema_version":1, "children":{first["child_id"]:True},
        "child_names": {first["child_id"]:first["name"]},
    })
    return first["child_id"]


def execute():
    with tempfile.TemporaryDirectory(prefix="bee_child_admin_sandbox_") as td:
        root=Path(td)/"LIVE_ROOT"
        root.mkdir()
        old_id=init(root)
        october=root/"利用希望JSON"/"2026-10.json"
        october_before=october.read_bytes()
        clock=lambda:datetime(2026,11,1,19,30,tzinfo=JST)
        class FakeTransport:
            _lock=threading.RLock()
        store=ChildAdminStore(root,transport=FakeTransport(),clock=clock,trigger_export=False)
        opts=store.options()
        assert opts["next_usage_request_no"]==2,opts
        assert store.get(old_id)["child"]["usage_request_no"]==1
        create={
            "client_action_id":str(uuid.uuid4()),"surname":"仮",
            "child_given_name":"新児","guardian_given_name":"保護",
            "shop":"福","active":True,"burden_rate":0.0,"source":"chatgpt",
            "address":"テスト住所1","phone":"000-0000-0000","postal_code":"000-0000"
        }
        result=store.apply(create,"create")
        cid=result["child_id"]
        assert cid!=old_id and result["usage_request_no"]==2
        assert result["child"]["shop"]=="福成"
        assert result["child"]["guardian_given_name"]=="保護"
        assert result["address_book_refresh"]=="scheduled"
        after=store.get(cid)
        assert after["child"]["address"]=="テスト住所1"
        assert october.read_bytes()==october_before
        for m in (11,12):
            d=json.loads((root/"利用希望JSON"/f"2026-{m:02d}.json").read_text(encoding="utf-8"))
            assert len(d["children"])==2
        assert json.loads((root/"利用希望JSON"/"運用.json").read_text(encoding="utf-8"))["master_month"]=="2026-11"
        assert store.options()["next_usage_request_no"]==3
        assert store.apply(create,"create")["status"]=="duplicate"
        try:
            store.apply({**create,"surname":"別"},"create")
            raise AssertionError("Reused idempotency key should be rejected")
        except ChildAdminConflict:
            pass
        try:
            store.apply({**create,"client_action_id":str(uuid.uuid4())},"create")
            raise AssertionError("Same child name should be rejected")
        except ChildAdminConflict:
            pass
        update={
            "client_action_id":str(uuid.uuid4()),"child_id":cid,
            "surname":"別","child_given_name":"新児",
            "guardian_given_name":"","shop":"今","active":False,
            "burden_rate":0.1,"source":"android","phone":"111-1111-1111",
        }
        modified=store.apply(update,"update")
        assert modified["child"]["active"] is False
        assert modified["child"]["shop"]=="今"
        assert modified["child"]["guardian_given_name"]=="保護"
        assert modified["child"]["phone"]=="111-1111-1111"
        assert modified["child"]["name"]=="別　新児"
        assert october.read_bytes()==october_before
        state=json.loads((root/"児童利用状態.json").read_text(encoding="utf-8"))
        assert state["children"][cid] is False
        old_record=json.loads((root/"利用希望JSON"/"2026-11.json").read_text(encoding="utf-8"))["records"]
        record=next(iter(old_record.values()))
        assert record["shop"]=="今" and record["pickup"] is True and record["start_time"]=="14:00"
        assert store.apply(update,"update")["status"]=="duplicate"
        bad={**update,"client_action_id":str(uuid.uuid4()),"burden_rate":0.2}
        try:
            store.apply(bad,"update")
            raise AssertionError("Invalid burden rate should fail")
        except ChildAdminInvalid:
            pass
        backups=list((root/"児童登録変更バックアップ").iterdir())
        assert len(backups)==2,len(backups)
        for archive in backups:
            assert (archive/"transaction.json").is_file()
            assert json.loads((archive/"transaction.json").read_text(encoding="utf-8"))["state"]=="committed"

    with tempfile.TemporaryDirectory(prefix="bee_child_admin_auth_") as td:
        root=Path(td)
        init(root)
        local=root/"localappdata"/"BeeMonitorGateway"
        local.mkdir(parents=True)
        secret="A"*64
        (local/"child_admin_secret.txt").write_text(secret,encoding="ascii")
        prior=os.environ.get("LOCALAPPDATA")
        os.environ["LOCALAPPDATA"]=str(root/"localappdata")
        try:
            api=FastAPI()
            install_child_admin_api(api,root)
            cli=TestClient(api)
            assert cli.get("/child-admin/options").status_code==403
            assert cli.get("/child-admin/options",headers={"X-Bee-Admin-Secret":"wrong"}).status_code==403
            r=cli.get("/child-admin/options",headers={"X-Bee-Admin-Secret":secret})
            assert r.status_code==200,r.text
            assert r.json()["next_usage_request_no"]==2
            created=cli.post("/child-admin/create",headers={"X-Bee-Admin-Secret":secret},json={
                "client_action_id":str(uuid.uuid4()),"surname":"試験",
                "child_given_name":"利用","guardian_given_name":"親",
                "shop":"今","burden_rate":0.1,
            })
            assert created.status_code==200,created.text
            assert created.json()["child"]["name"]=="試験　利用"
            assert cli.post("/child-admin/create",json={"ignored":True}).status_code==403
        finally:
            if prior is None:os.environ.pop("LOCALAPPDATA",None)
            else:os.environ["LOCALAPPDATA"]=prior

    print("DRY_RUN_SUCCESS: month pointers, duplicates, idempotency, basic info, stable IDs, historical attendance, backups, auth")


if __name__=="__main__":
    execute()
