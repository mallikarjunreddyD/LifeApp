# Phase 7F workout logbook

Open **Workouts → Add workout**, choose the workout date and optionally
select a saved routine. Its ordered exercises and planned set rows become
editable copies. Add, copy or remove sets, and reorder or remove exercises
for this workout. A blank external weight is valid for bodyweight work.
Choose KG or LB per weighted set. Exercise remarks belong to this session.
Enter optional minutes, average BPM, calories and overall remarks, then
choose **Save workout**. A manually logged workout needs no timer timestamps.

The editor keeps valid Android draft values in Room. The PWA keeps its draft
in IndexedDB. An edit draft records the revision it opened. The repository
rejects a save if the session was changed or deleted in the meantime.

History offers card and spreadsheet views plus date, month and routine
filters. Both views show the routine name captured at save time. A routine
rename or edit changes the template used for future workouts. Use **Save
changes to routine** to explicitly copy the current exercise order and set
counts to the template. The exercise library and routine manager are
collapsed until opened. Exercise archival preserves historical references.

**Delete workout** confirms the selected date and routine. The local
repository marks that UUID deleted in one transaction, retaining its
exercises and sets. Repeat deletion is idempotent. Restore the same session
from **Recently deleted**. Deleted sessions are omitted from history and
monthly reports. Android saves the PDF through the existing document
workflow. The PWA offers print or save to PDF, in detailed or compact
landscape format.

## Compatibility

Room and IndexedDB store workout payloads as JSON. Their existing physical
schemas can store the additive fields. Older records default to the legacy
behavior. Historical equipment, timestamps, set durations and rest values
remain stored. Portable data schema 9 preserves the new values, and older
archive schemas remain readable.

Workout writes and deletion are device-local. The authenticated PostgreSQL
workout routes remain read-only. The OpenAPI record shapes describe the
additive fields for future transport. A future workout protocol must carry
tombstones through durable operations and reject stale edits without
resurrecting deleted sessions.
