# Stylus and device validation

Automated synthetic MotionEvents are not equivalent to Xiaomi stylus hardware.
No physical Xiaomi Pad 7 checks have been performed in this environment. Every
checkbox below remains pending until a tester records device/stylus model, Android
and HyperOS versions, APK/commit, orientation, observations and result.

## Automated tests

Common/JVM tests cover calendar boundaries (including leap years), stable IDs and
validation, coordinate round trips, spatial hit testing, exact eraser undo/redo,
page/date draft navigation, serialized autosave, failed-save retention/retry,
point serialization, duplicate-date prevention, atomic rollback, file-database
reopening and migration from schema 1 with existing data.

Android instrumentation tests synthesize stylus/finger/eraser events against the
real JournalCanvas and test pressure, highlighter properties, cancellation,
whole-stroke erase commit and logical transforms. Compose instrumentation tests
exercise native stylus-to-autosave wiring, date buttons, tool selection, page navigation, system display rotation and Activity
recreation with retained vectors. CI runs these on an API 36 Pixel Tablet emulator;
consult docs/validation.md and the PR checks for actual execution results.

Run with a booted development emulator or test device:

```sh
./gradlew :apps:android:connectedDebugAndroidTest
```

Use a disposable test installation: UI tests create journal content on dates in
2200. They do not delete existing journals. Test outputs are under
`apps/android/build/reports/androidTests/connected/debug`.

## Xiaomi Pad 7 checklist

- [ ] Install the debug APK and launch; today's local calendar date opens.
- [ ] Write several sentences and measure visible pen latency and dropped samples.
- [ ] Write small letters/curves and dots; compare the wet stroke to restored ink.
- [ ] Rest the palm before, during and after stylus contact; inspect for stray marks
      and unintended panning. Test cancel signals and hover if exposed by this stylus.
- [ ] Vary pressure slowly; confirm supported pressure changes thickness naturally.
      Record constant/no-pressure devices as such; verify full-width fallback.
- [ ] Write at all edges, near system gesture areas, and just outside the paper.
- [ ] Switch black/blue/red/green and widths; confirm restored tool properties.
- [ ] Use highlighter, including overlaps; verify transparency on reload.
- [ ] Switch pen/eraser and test a physical eraser tool if available. A finger must
      never draw or erase. Erasing removes whole intersecting strokes only.
- [ ] Undo/redo additions and erasures; confirm exact restored position and order.
- [ ] Pan with one finger and pinch 1–4×; write again at multiple zoom levels.
- [ ] Rotate portrait → landscape → portrait while viewing and after writing;
      date/page and vector geometry must survive without distortion.
- [ ] Navigate previous/next day across month/year boundaries and leap day;
      use the picker and Today. Viewing a blank date must not create stored content.
- [ ] Add several pages, write on each, revisit earlier pages and keep editing.
- [ ] Wait for “All changes saved locally”, close/reopen, and verify editable ink
      on multiple dates/pages, including older entries.
- [ ] After Saved, force-stop and reopen; verify every saved stroke is editable.
      Separately test leaving while Saving and note the documented pending-work limit.
- [ ] Background during a stroke and during saving; completed ink must queue for save.
- [ ] Test low-storage/failing-write conditions: error appears, unsaved ink stays
      visible, Retry drains queued changes in order, and Saved appears only after success.
- [ ] Fill several pages with thousands of strokes; record input/render latency,
      reload duration and eraser response. No performance target is claimed yet.
- [ ] Disable all network connections, repeat writing/reopening, and check no account
      or online service is required.

## Limits and expected behavior

Finger/palm input cannot create ink. Finger panning is ignored during an active or
hovering stylus; MotionEvent ACTION_CANCEL and FLAG_CANCELED discard uncommitted
pen/eraser gestures. Hardware palm rejection and latency depend on the actual
Xiaomi firmware/stylus and are not guaranteed. Finger gestures before the stylus is
present can pan the page; no unsupported hardware-palm classification is invented.

The app commits each completed stroke, not every movement point. Unsaved work is
retained in application memory for retries and Activity changes. Android process
termination or power loss before a commit can lose pending/in-progress work;
only a successful saved status confirms durability. Undo/redo history is in memory
and is not restored after process restart, while saved vector content remains editable.
