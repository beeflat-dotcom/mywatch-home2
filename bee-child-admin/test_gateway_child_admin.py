# -*- coding: utf-8 -*-
"""Strict isolated gateway regression test for child admin privilege injection."""
from __future__ import annotations

import asyncio
import tempfile
from pathlib import Path

import httpx

from bee_monitor_gateway import make_app, issue_code


async def verify():
    with tempfile.TemporaryDirectory(prefix="bee_admin_gateway_test_") as td:
        state_dir=Path(td)
        shared_secret="b"*64
        (state_dir/"child_admin_secret.txt").write_text(shared_secret, encoding="ascii")
        requests=[]

        async def backend(req):
            requests.append((req.url.path,req.headers.get("x-bee-admin-secret",""),
                             req.headers.get("authorization","")))
            return httpx.Response(200,json={"ok":True,"path":req.url.path})

        app=make_app(state_dir,transport=httpx.MockTransport(backend))
        async with httpx.AsyncClient(
            transport=httpx.ASGITransport(app=app),base_url="http://gateway"
        ) as client:
            for path in ("/monitor/child-admin/options","/child-admin/options"):
                r=await client.get(path,headers={"X-Bee-Admin-Secret":"FORGED"})
                assert r.status_code in (401,404), (path,r.status_code)
            assert not requests
            code=issue_code(state_dir)
            resp=await client.post("/monitor/pair",json={
                "code":code,"device_name":"test_admin_android"
            })
            assert resp.status_code==200,resp.text
            token=resp.json()["token"]
            auth={"Authorization":"Bearer "+token}
            r=await client.get("/monitor/child-admin/options",headers={
                **auth,"X-Bee-Admin-Secret":"FORGED",
            })
            assert r.status_code==200,r.text
            assert requests[-1]==("/child-admin/options",shared_secret,""),requests[-1]
            r=await client.post("/monitor/child-admin/create",headers=auth,json={
                "client_action_id":"11111111-1111-4111-8111-111111111111",
                "surname":"テスト","child_given_name":"仮",
            })
            assert r.status_code==200
            assert requests[-1][0]=="/child-admin/create"
            assert requests[-1][1]==shared_secret
            r=await client.get("/monitor/child-master",headers={
                **auth, "X-Bee-Admin-Secret":"FORGED",
            })
            assert r.status_code==200
            assert requests[-1]==("/child-master","",""),requests[-1]
            r=await client.get("/ptt/health",headers={"X-Bee-Admin-Secret":"FORGED"})
            assert r.status_code==200
            assert requests[-1]==("/ptt/health","",""),requests[-1]
    print("GATEWAY_AUTHENTICATED_CHILD_ADMIN_PROXY_TEST_SUCCESS")


if __name__=="__main__":
    asyncio.run(verify())
