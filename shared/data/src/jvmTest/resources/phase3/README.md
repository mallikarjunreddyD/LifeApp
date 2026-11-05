# Frozen Phase 3 compatibility fixtures

schema4-backup.zip and schema4-records.json were generated once using the unchanged
Phase 3 codec on merged main2c82a17, before Phase3.1 modified any encoder or model.
They contain synthetic BackupFixture records only: two journal dates/four pages,
two planners, two habit ledgers with checked/unchecked/archived history, two notebooks,
four notebook templates and20 strokes. The archive has backup format1/data schema4;
journal page objects have no archived field. These are intentional test resources,
not build artifacts, credentials or personal backups. Do not regenerate them using
a newer writer: they prove older exported files remain readable/restorable.
