# Architecture

Android is the primary editor platform. JVM desktop and iOS are prepared companion
hosts. All shared modules expose Android/JVM targets; iOS arm64 device and simulator
targets are enabled explicitly with `-PenableIos=true`.

Dependencies flow from apps to shared UI/data, from UI/data to domain, and from
domain to core. Core and domain contain no Android, UIKit, Compose or Room types.
Domain repository methods use suspend writes and Flow observations; implementations
will commit locally before publishing. There is no network layer, account system,
service locator, dependency injection framework or synchronization engine.

RecordMetadata uses Kotlin UUID and Instant, positive schema version and revision,
and monotonic update time. Revising preserves identity/creation time and guards
counter overflow. Schema version describes record format; revision tracks record
edits. Neither is the Room database version. Domain records intentionally contain
only metadata until their feature phase establishes content requirements.

Room has one metadata table, generated constructors per target and schema export.
UUID and Instant values map explicitly to canonical strings (including nanoseconds).
Table rows are persistence details and never enter UI/domain contracts. A later
phase must add database factories, feature tables, repository implementations and
migration tests before claiming persistence works. No destructive migration fallback
is permitted for personal journal data. The generated version 1 schema JSON is committed; future schema changes must
include reviewed schema exports.

Handwriting remains a platform boundary: Android stylus/palm rejection and eventual
Jetpack Ink integration belong to an Android adapter; UIKit input belongs to an iOS
adapter; shared UI must not depend on either SDK. No fake handwriting adapter or
stroke serialization format is introduced in Phase 0.

The light paper palette uses warm surfaces, dark ink and muted green accents.
System sans-serif body text and serif title typography avoid downloadable fonts.
The shell adapts using available width, has no fixed orientation, and names every
unfinished screen as a preview. Navigation is the only operational interaction.
No storage or network permissions are requested; Android automatic backup is disabled
until an explicit user-controlled backup design exists.
