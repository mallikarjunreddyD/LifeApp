# Monthly workout reports

Open **Workouts → Export monthly PDF** and select the month and layout.
Android writes a PDF through the system file picker and verifies the saved
file. The PWA builds a print view from its offline records. Use **Print or
save PDF** to open the browser print interface and select its PDF save or share
action. Both report layouts use A4. Compact mode uses landscape orientation.

Reports use the saved local workout date and include completed sessions only.
Average duration and average heart rate use only sessions with that
measurement. The summary displays the count used for each average. Recorded
zeroes are preserved and missing measurements are shown as unavailable.
Exercise order and entered KG or LB values are preserved.

The report reflects the workouts available on the current device. Workout
sync and the six-day workout type data are still pending, so the report omits
per-type counts and unsupported exercise measurement breakdowns. Physical
Xiaomi Pad 7 PDF saving and iPhone Safari print and share remain to be tested.
The PWA uses the operating system print workflow for PDF creation.
