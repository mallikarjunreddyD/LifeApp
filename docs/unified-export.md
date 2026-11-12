# Download My Data

Backup & Recovery contains Download My Data. Android exports a ZIP
containing a validated portable recovery ZIP with original LJPT bytes,
metadata Markdown, and available Journal, Planner, Notebook and monthly
workout PDFs. To restore, extract the recovery ZIP and open it using
Restore from Backup. The outer package is a data export.

The browser exports local To-do projections and available conflict
variants, plus local workout records. The browser package does not
contain online previews or Android recovery data.

Both exports are labeled device-local. They attempt available To-do sync
when online and record known pending changes. The manifest lists counts,
SHA-256 values, sizes and coverage. Android verifies the final file
written through the picker. The browser verifies the generated ZIP
before handing it to the browser's download manager. Keep both
unencrypted exports private. Android caps the outer package at 128 MiB
and reports omitted readable representations. Browser exports are
limited to 64 MiB. Monthly Workout PDF remains available from History.
