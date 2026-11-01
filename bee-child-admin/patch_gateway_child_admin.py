# -*- coding: utf-8 -*-
"""Patch the existing verified BeeMonitorGateway with a protected admin bridge.

Does NOT alter the PTT path allowlist or any existing route.
The only privileged header is injected by the gateway after paired Bearer validation.
"""
from __future__ import annotations

import argparse
import ast
from pathlib import Path

START = '''        headers = {
            k: v for k, v in request.headers.items()
            if k.lower() not in HOP_HEADERS and (not authorized_monitor or k.lower() != "authorization")
        }
        query = request.url.query
'''
END = '''        headers = {
            k: v for k, v in request.headers.items()
            if k.lower() not in HOP_HEADERS
            and k.lower() != "x-bee-admin-secret"
            and (not authorized_monitor or k.lower() != "authorization")
        }
        # Only a successfully paired /monitor/* request can reach the child-admin
        # backend with the locally-held privileged key. A direct legacy tunnel
        # or a forged client header cannot access the new administration routes.
        if path.startswith("/child-admin/"):
            if not authorized_monitor:
                raise HTTPException(404, "route unavailable")
            try:
                admin_secret = (state_dir / "child_admin_secret.txt").read_text(encoding="ascii").strip()
            except OSError:
                raise HTTPException(503, "child admin credential unavailable") from None
            if len(admin_secret) < 48:
                raise HTTPException(503, "child admin credential invalid")
            headers["X-Bee-Admin-Secret"] = admin_secret
        query = request.url.query
'''


def patch(s: str) -> str:
    if END in s:
        print("GATEWAY_CHILD_ADMIN_BRIDGE_ALREADY_PATCHED")
        return s
    if s.count(START) != 1:
        raise ValueError("Expected gateway header builder not found exactly once")
    result = s.replace(START, END)
    ast.parse(result, filename="bee_monitor_gateway.py")
    print("GATEWAY_CHILD_ADMIN_BRIDGE_PATCHED")
    return result


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("target", type=Path)
    args = parser.parse_args()
    existing = args.target.read_text(encoding="utf-8")
    changed = patch(existing)
    if changed != existing:
        args.target.write_text(changed, encoding="utf-8")
    ast.parse(args.target.read_text(encoding="utf-8"))


if __name__ == "__main__":
    main()
