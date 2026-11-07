<p align="center">
  <img src="icons/unutma-icon-valknut-1024.png" width="128" height="128" alt="Unutma app icon: three interlocked purple triangles">
</p>

<h1 align="center">Unutma</h1>

<p align="center">Notes, checklists, routines and reminders for Android. <i>Unutma</i> means "don't forget".</p>

## What it does

- **Notes and checklists** with bold/italic, headings, numbered lists, pictures, voice notes, links between notes and quick maths.
- **Reminders** (with "repeat until done"), countdowns, and notes that expire.
- **Routines**: checklists that untick themselves on a schedule, with streaks, week squares and a month view.
- **Google Calendar**: see, add, change and delete events next to your notes.
- **Widgets** for checklists, routines, notes, countdowns and your calendar.
- **Locked notes** are encrypted with your phone's screen lock.
- **Back up and restore** everything in one .zip, and **import from Google Keep**.

Everything stays on your phone; nothing is sent anywhere.

See [CHANGELOG.md](CHANGELOG.md) for what changed in each version.

## Building

Open the project in Android Studio, or from the project folder:

```bash
./gradlew assembleDebug        # test build: installs as "Unutma (debug)", beside the real app
./gradlew testDebugUnitTest    # unit tests
```

Release builds are signed in Android Studio (Build › Generate Signed App Bundle or APK) with the same key every time.

The app icon is in [`icons/`](icons): `unutma-icon-valknut-purple.svg` and a 1024 × 1024 PNG.
