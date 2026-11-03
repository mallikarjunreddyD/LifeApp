# My Journal development rules

## Scope and boundaries

Work only on the requested phase. Android on Xiaomi Pad 7 is primary. Keep all
features offline-first, with no mandatory registration. Do not introduce backend,
cloud, synchronization or account dependencies during Phase 0. Never present
placeholder editing, saving or settings as working functionality.

Apps own launchers and platform adapters. shared/ui owns Compose and design tokens;
shared/data owns Room, conversions and future local repository implementations;
shared/domain owns platform-independent records and repository contracts;
shared/core owns identity and metadata invariants. Dependencies point inward toward
core. No platform, Compose or Room imports in core/domain. No database access in UI.
Keep handwriting APIs in platform adapters; defer stroke formats until handwriting
requirements are tested on hardware. Avoid speculative frameworks and abstractions.

## Conventions

Use Kotlin official style, four spaces, explicit names and immutable data where
possible. Use Kotlin UUIDs and UTC Instants for identity/timestamps. Preserve record
creation times and increment revisions on edits. Distinguish record schema versions
from Room database versions. Use suspend functions for writes and Flow for observable
local data. Surface storage failures; never silently drop user content. Do not use
destructive Room migrations. Export/review generated Room schemas on schema changes.
Use catalog versions, stable official dependencies and Gradle Kotlin DSL only.
Keep Apple targets opt-in; never require Xcode for Android development on Windows.

## Commands and testing

Use JDK 21 and Android SDK platform 37 / Build Tools 36.0.0. Windows commands use
`gradlew.bat`; POSIX commands use `./gradlew`.

```sh
./gradlew :shared:core:jvmTest :shared:domain:jvmTest :shared:data:jvmTest :shared:ui:jvmTest
./gradlew :apps:android:assembleDebug :apps:android:lintDebug :apps:desktop:classes
./gradlew :apps:desktop:run
# macOS + Xcode only:
./gradlew -PenableIos=true :shared:ui:linkDebugFrameworkIosSimulatorArm64
```

Test meaningful invariants, mapping round-trips, failure paths and later persistence
and migrations. Do not add tests that merely mirror UI constants. Validate adaptive
layouts, large text, orientation and stylus behavior on real devices as relevant.
Run applicable checks, fix failures, and report exact blockers if tools or network
are missing. Never call an unexecuted test passing. Keep docs/validation.md accurate.
