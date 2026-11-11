"""HTTP assertions for the isolated Compose stack, with no credential output."""
import hashlib
import json
import struct
import uuid
import os
from pathlib import Path
import sys
from urllib.error import HTTPError
from urllib.request import Request, urlopen

base = f"http://127.0.0.1:{os.environ['LIFEAPP_LOOPBACK_PORT']}"
origin = os.environ["LIFEAPP_HTTPS_ORIGIN"]


def call(path, *, method="GET", expected=200, body=None, headers=None):
    request = Request(base + path, method=method, headers=headers or {})
    if isinstance(body, bytes):
        request.data = body
        request.add_header("Content-Type", "application/octet-stream")
    elif body is not None:
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
points = struct.pack("<4sIIfffq", b"LJPT", 1, 1, 0.0, 2.0, -1.0, 0)
stroke_id = "71000000-0000-4000-8000-000000000003"
if sys.argv[1] == "hold":
    scope = session["scope"]
    digest = hashlib.sha256(points).hexdigest()
    headers = {
        **auth,
        "X-LifeApp-Dataset": scope["dataset"],
        "X-LifeApp-Epoch": scope["epoch"],
        "X-Content-SHA256": digest,
    }
    upload = str(uuid.uuid4())
    call(f"/v1/ink/staged/{upload}", method="PUT", expected=204, body=points, headers=headers)

    def metadata(identity):
        time = "2026-11-09T12:00:00Z"
        return {"id": identity, "createdAt": time, "updatedAt": time, "schemaVersion": 1, "revision": 1}

    entry_id = "71000000-0000-4000-8000-000000000001"
    page_id = "71000000-0000-4000-8000-000000000002"
    call("/v1/journal/ink/commit", method="POST", expected=204, headers=auth, body={
        "scope": scope, "uploadId": upload, "sha256": digest,
        "entry": {"metadata": metadata(entry_id), "date": "2026-11-09", "pages": [{
            "metadata": metadata(page_id), "entryId": entry_id, "order": 0,
            "width": 1000.0, "height": 1414.0, "paper": "RULED", "archived": False,
        }]},
        "stroke": {"metadata": metadata(stroke_id), "surfaceId": page_id, "order": 0,
                   "brush": "PEN", "colorArgb": 4278190080, "width": 3.0, "pointFormatVersion": 1},
    })
    # This second upload remains staged. Recovery should keep only the committed bytes.
    call(f"/v1/ink/staged/{uuid.uuid4()}", method="PUT", expected=204, body=points, headers=headers)
    Path(sys.argv[2]).write_text(session["accessToken"], encoding="utf-8")
    sys.exit(0)
if held := os.environ.get("LIFEAPP_SMOKE_HELD_TOKEN"):
    token = Path(held).read_text(encoding="utf-8")
    call("/v1/session", headers={"Authorization": f"Bearer {token}"}, expected=401)
    restored_ink, ink_headers = call(f"/v1/journal/ink/{stroke_id}", headers=auth)
    assert restored_ink == points and ink_headers["X-Content-SHA256"] == hashlib.sha256(points).hexdigest()
    backup = json.loads(call("/v1/backups/status", headers=auth)[0])
    assert backup["state"] == "failed" and backup["lastVerifiedAt"] is None
    workout = json.loads(call("/v1/workouts/70000000-0000-4000-8000-000000000002", headers=auth)[0])
    restored_set = workout["entries"][0]["sets"][0]
    assert restored_set["metadata"]["id"] == "70000000-0000-4000-8000-000000000004"
    assert restored_set["weight"] == {"value": 22.5, "unit": "LB"} and restored_set["repetitions"] == 8
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
