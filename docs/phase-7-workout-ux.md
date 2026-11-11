# Phase 7A workout logging

Android and the PWA show a finish summary with active minutes, optional
average heart rate, calendar date, notes and optional calories. Finishing
keeps incomplete exercises and sets in the saved workout. The existing
history editor can change summary values later. A blank duration uses elapsed
time when finishing. A manually entered duration remains independent of UTC
start and end times. Heart rate can be blank and accepts 20–250 BPM.

Workout edits still commit locally through the existing Room or IndexedDB
repository. The session and its children keep their existing IDs. Android
exports use portable schema 8 when the new fields are present and continue
to read older archives.

This increment covers workout summaries. Six-day templates, inline set
editing, richer exercise measurements and device checks are still part of
Phase 7A. Workout synchronization starts in Phase 7B.
