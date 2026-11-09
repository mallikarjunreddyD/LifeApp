# Android To-do and reminders

To-do supports title and calendar deadline editing, completion, reopening and
confirmed deletion. Room retains the original identity and creation time. All
writes increment the revision and commit with the local outbox. A failed write can
be retried or dismissed. Pending tasks group by overdue, due today and upcoming
using the device's current calendar date. Completed tasks are shown separately.

Reminders default to disabled with local times 09:00 and 19:00. In Settings,
enable reminders, adjust both times and grant notifications on Android 13 and
later. Permission denial leaves To-do available. The app uses two individually
keyed, one-shot inexact RTC alarms with `setAndAllowWhileIdle`. The next local
occurrence is recomputed after each delivery. The app replaces alarms after boot,
clock/time-zone changes, updates and settings edits. Matching reminder times use
one alarm. The stored slot trigger rejects stale or duplicate dispatches. Doze,
battery optimization, force-stop and Xiaomi background restrictions can delay
or prevent alarms. Opening the app recreates the schedule after a force-stop.

The receiver queries current pending tasks from Room. It displays counts only,
including overdue counts, and skips notification delivery when no tasks are
pending. A private notification opens To-do. The receiver requires no network.
The shared preferences model and next-occurrence calculation use calendar
time and a supplied clock instant and time zone. Android scheduling and
permissions are platform adapters.

Phase 5 handoff requires a native iOS To-do view with shared repository editing,
iOS notification permission and calendar triggers via UNUserNotificationCenter,
iOS cloud synchronization and an authenticated cross-device acceptance run. A
repeating iOS local notification cannot query up-to-the-second synchronized state
while the app is suspended. Use generic reminder text or refresh notifications
opportunistically. Reminder preferences and deliveries stay per device, so each
enabled device may notify separately. Windows editing is outside this phase.

- [Android alarm scheduling](https://developer.android.com/develop/background-work/services/alarms)
- [Android notification permissions](https://developer.android.com/develop/ui/views/notifications/notification-permission)
