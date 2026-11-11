"""Write a deterministic, synthetic Phase 7E archive fixture to stdout."""
import hashlib
import json
import struct
import sys
import zipfile

kind = sys.argv[1]
if kind not in ("planner", "notebook", "habits"):
    raise SystemExit("Expected planner, notebook or habits")
timestamp = "2026-11-09T12:00:00Z"


alternative = len(sys.argv) == 3 and sys.argv[2] == "alternative"


def identity(number):
    return f"73000000-0000-4000-8000-{number + (100 if alternative else 0):012d}"


def metadata(number):
    return dict(id=identity(number), createdAt=timestamp, updatedAt=timestamp, schemaVersion=1, revision=1)


data = dict(format="LifeAppRecords", formatVersion=1, dataSchemaVersion=6, metadata=[], journals=[],
            planners=[], habits=[], notebooks=[], notebookPages=[], todos=[], strokes=[])
counts = dict(metadata=0, journalEntries=0, journalPages=0, planners=0, habitLedgers=0, habitDefinitions=0,
              habitCompletions=0, notebooks=0, notebookPages=0, strokes=1, todos=0)
if kind == "planner":
    data["planners"].append(dict(metadata=metadata(1),
        layout=dict(month="2026-11", version=1, rows=6, width=1400, headerHeight=70, cellHeight=120)))
    counts["planners"] = 1
    surface = identity(1)
elif kind == "notebook":
    data["notebooks"].append(dict(metadata=metadata(11), name="Test notebook", description=None,
                                  defaultTemplate="BLANK", archived=False))
    data["notebookPages"].append(dict(metadata=metadata(12), notebookId=identity(11), order=0,
                                     title="Field notes", paper="BLANK", width=1000, height=1000, archived=False))
    counts["notebooks"] = counts["notebookPages"] = 1
    surface = identity(12)
else:
    data["habits"].append(dict(metadata=metadata(21), month="2026-11",
        habits=[dict(metadata=metadata(22), ledgerId=identity(21), name="Reading", order=0, category=None,
                     colorArgb=None, target=None, archived=False)], completions=[]))
    counts["habitLedgers"] = counts["habitDefinitions"] = 1
    counts["strokes"] = 0
files = {}
if kind != "habits":
    stroke = metadata(3 if kind == "planner" else 13)
    path = f"ink/{stroke['id']}.ljpt"
    data["strokes"].append(dict(metadata=stroke, surfaceId=surface, order=0, brush="PEN", colorArgb=0xff000000,
                                width=10, pointFormatVersion=1, pointsFile=path))
    line_y = 250 if alternative else 220
    points = struct.pack("<4sIIfffqfffq", b"LJPT", 1, 2, 100, line_y, -1, 0, 150, line_y, -1, 1)
    files[path] = points
files["data.json"] = json.dumps(data).encode()
manifest = dict(format="LifeAppArchive", backupFormatVersion=1, kind="backup", appVersion="test", createdAt=timestamp,
                dataSchemaVersion=6, sections=["metadata", "journal", "planner", "habits", "notebooks", "ink", "todos"],
                counts=counts, files=[dict(path=name, size=len(content), sha256=hashlib.sha256(content).hexdigest())
                                      for name, content in files.items()])
with zipfile.ZipFile(sys.stdout.buffer, "w", zipfile.ZIP_DEFLATED) as archive:
    for name, content in {**files, "manifest.json": json.dumps(manifest).encode()}.items():
        archive.writestr(zipfile.ZipInfo(name), content, compress_type=zipfile.ZIP_DEFLATED)
