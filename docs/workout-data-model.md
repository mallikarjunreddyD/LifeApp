# Shared workout models

The common domain defines reusable exercise definitions, planned routines,
recorded sessions, exercise entries and sets. Definitions can be renamed or
archived while retaining their UUID. An archived definition remains a valid
reference for recorded history. A routine stores an ordered exercise template.
Actual measured values belong only to a session.

Sessions carry the user's calendar date independently of UTC start and end
instants. Active duration is stored in integer seconds and may be shorter than
elapsed time. Weight uses an explicit KG or LB unit with a finite nonnegative
value. A completed set requires positive repetitions or a positive measured
duration. Optional rest uses seconds. Calories are optional user-supplied kcal.
No estimate is calculated. Notes and titles are bounded.

Entry positions are contiguous from zero. Set numbers are contiguous from one.
Parent IDs and record IDs are checked together, and exercise references can be
validated against a repository's definitions. Cross-platform API fixtures use
calendar date strings, UTC timestamps, explicit units and decimal string revisions.

Room persistence, workout backup fields, editing and the mutation API are deferred.
Phase 6 should decide pause tracking, routine editing and history semantics before
committing the workout database schema.
