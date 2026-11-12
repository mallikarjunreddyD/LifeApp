# Phase 9A structured synchronization

The first implementation brings To-do and Expenses under Settings →
Synchronization. Browser binding is atomic in IndexedDB. Android takes one
verified recovery snapshot for both reviews. Android commits each existing
binding independently, so a retry can finish a module whose inventory changed.
Existing accounts, scopes, queues and stored records carry forward.

Habits has its own web tab. The web client asks for habit versions only.
Android automatic transfer and queued archive retry are restricted to Habits.
Existing journal, planner and notebook copies remain in Android storage, backups
and server history. Legacy server endpoints still support older clients.

The habit transport still uses reviewed month versions and requires connectivity
for PWA edits. Workout records still live in each client's local store. A new
structured protocol for workouts and habits and full four-module binding remain
open. This branch is a foundation for Phase 9A.
