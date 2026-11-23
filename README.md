<p align="center">
  <img src="docs/logo.png" alt="Hush logo" width="128" height="128">
</p>

<h1 align="center">Hush</h1>

A calm Android app that adds a short, timed pause before you open chosen apps.

### [Download the latest signed APK](https://github.com/DanielCohen002/hush/releases/latest)

Package name `com.djcohen.hush`. Needs Android 7.0 or newer.

> **Hush is a fork of [Pause](https://github.com/dtsykunov/pause-app) by dtsykunov** (MIT licensed;
> the original licence is kept in `LICENSE`). Thanks to the original author for the foundation.

What Hush adds on top of Pause:

- a landscape layout, and a pause screen you can still pull the notification shade over
- apps of your choosing offered "instead" on the pause screen
- breathing styles: classic, box, 4-7-8, ripples, still
- three colour themes (Ember by default, Seafoam, and the original Lavender), light and dark
- an optional puzzle while you wait: mini sudoku, word search or mini crossword, from bundled packs
  (`tools/gen_puzzles.py` builds them) so nothing is generated on the phone and nothing repeats

When you launch a selected app, the screen is covered by a panel with a countdown; only after it
finishes can you choose **Open anyway** or **Cancel**.

It uses an Accessibility Service to detect app launches and an accessibility overlay to draw the pause
screen, so it needs no "draw over other apps" permission. The only network use is two
buttons: **Check for updates**, which asks GitHub for the latest release, and **Download new puzzles**
(optional, puzzle data only, no code). Both run only when you tap them; there is no background
networking.

**Skip the Settings trip (optional).** Android won't let an app switch on its own accessibility
service unless it has `WRITE_SECURE_SETTINGS`. Grant it once from a computer with the phone
connected (USB debugging on), and Hush turns itself on whenever you open it:

    adb shell pm grant com.djcohen.hush android.permission.WRITE_SECURE_SETTINGS

Swiping down from the top edge of the pause screen opens the notification shade.

> **Check your phone first.** Recent Android versions include this natively: Digital Wellbeing's app
> timers and Focus mode, and Android 17's Pause Point (a built-in delay before selected apps, on Pixel
> and Samsung). If your phone has those, prefer them. Hush is for older devices, custom ROMs, and
> phones that ship without Google's Digital Wellbeing.

## Build

```sh
nix develop        # provides JDK 21 + Android SDK + Gradle
./gradlew assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

## Install

With the phone connected over USB (USB debugging enabled):

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Set up on the phone

1. Open **Hush**.
2. Tap **Enable accessibility access** and turn on "Hush monitor" in the system settings.
   (On some phones this lives under *Settings → Accessibility → Installed apps*.)
3. Back in the app, set the **pause length** and choose the apps you want to gate.

## How it works

- `AppMonitorService`: accessibility service; reacts to foreground-app changes and triggers the
  overlay for paused apps. A configurable per-app allow-window (default 15 min) after "Open anyway"
  avoids re-prompting that app during its session. Optionally, the end of that window becomes a
  check-in instead of a passive gate: the pause reappears if you're still in the app, and you
  choose whether to continue or leave.
- `InterventionOverlay`: the full-screen cover. It covers the screen immediately, eases in a pulsing
  circle and countdown, then reveals the choice buttons; it slides back down on dismiss.
- `Prefs`: stores the paused-app set, pause length, the rolling 24h list of open attempts, and the
  per-app stats.
- `MainActivity` / `AppsActivity` / `StatsActivity`: settings (accessibility status, pause length,
  message, allow-window and the check-in switch), the searchable app picker, and per-app stats.

Note: an accessibility-based gate is best-effort, not a hard block. It's designed to make impulsive
opens deliberate, not to be tamper-proof.

## License

MIT. See [LICENSE](LICENSE).
</content>
</invoke>
