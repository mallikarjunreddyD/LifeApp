"""HTTP assertions for the isolated Compose stack, with no credential output."""
import json
import os
from pathlib import Path
import sys
from urllib.error import HTTPError
from urllib.request import Request, urlopen

base = f"http://127.0.0.1:{os.environ['LIFEAPP_LOOPBACK_PORT']}"
origin = os.environ["LIFEAPP_HTTPS_ORIGIN"]


def call(path, *, method="GET", expected=200, body=None, headers=None):
    request = Request(base + path, method=method, headers=headers or {})
    if body is not None:
        request.data = json.dumps(body).encode()
        request.add_header("Content-Type", "application/json")
    try:
        response = urlopen(request, timeout=10)
    except HTTPError as error:
        response = error
    with response:
        assert response.code == expected, f"{method} {path}: expected {expected}, got {response.code}"
        return response.read(), response.headers


assert json.loads(call("/ready")[0]) == {"status": "ready"}
assert b"LifeApp" in call("/")[0]
assert json.loads(call("/manifest.webmanifest")[0])["display"] == "standalone"
assert b"lifeapp-shell-" in call("/sw.js")[0]
call("/v1/todos", expected=401)
credentials = {"username": "compose-smoke-owner", "password": "disposable compose smoke password"}
operation = json.loads(Path("contracts/fixtures/todo-operation.json").read_text())
session = json.loads(call("/v1/session", method="POST", body={**credentials, "client": "android"})[0])
auth = {"Authorization": f"Bearer {session['accessToken']}"}
if sys.argv[1] == "hold":
    Path(sys.argv[2]).write_text(session["accessToken"], encoding="utf-8")
    sys.exit(0)
if held := os.environ.get("LIFEAPP_SMOKE_HELD_TOKEN"):
    token = Path(held).read_text(encoding="utf-8")
    call("/v1/session", headers={"Authorization": f"Bearer {token}"}, expected=401)
    backup = json.loads(call("/v1/backups/status", headers=auth)[0])
    assert backup["state"] == "verified" and backup["lastVerifiedAt"]
if old_epoch := os.environ.get("LIFEAPP_OLD_EPOCH"):
    call(f"/v1/sync/pull?epoch={old_epoch}&after=0", headers=auth, expected=409)
if sys.argv[1] == "seed":
    call("/v1/sync/push", method="POST", body={"scope": session["scope"], "operation": operation}, headers=auth)
assert any(item["metadata"]["id"] == operation["recordId"] for item in json.loads(call("/v1/todos", headers=auth)[0])["items"])
call("/v1/session", method="DELETE", headers=auth, expected=204)
call("/v1/todos", headers=auth, expected=401)

if sys.argv[1] == "seed":
    call("/v1/session", method="POST", expected=403, body={**credentials, "client": "browser"})
    body, headers = call("/v1/session", method="POST", body={**credentials, "client": "browser"},
                         headers={"Origin": origin})
    session = json.loads(body)
    cookie = headers["Set-Cookie"]
    assert all(flag in cookie.lower() for flag in ("secure", "httponly", "samesite=strict"))
    assert "accessToken" not in session
    auth = {"Cookie": cookie.split(";", 1)[0], "Origin": origin}
    call("/v1/session", headers=auth)
    call("/v1/session", method="DELETE", headers=auth, expected=403)
    call("/v1/session", method="DELETE", headers={**auth, "X-CSRF-Token": session["csrfToken"]}, expected=204)
    call("/v1/session", headers=auth, expected=401)
