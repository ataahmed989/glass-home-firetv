# Glass Home for Fire TV

A tvOS-style home screen for an Amazon Fire TV Stick 4K Plus (Fire OS 8, Android 11), with
no ads and no sponsored rows.

![Movies](docs/screenshots/movies-1-hero.png)

## What it has

- **Home**: the focused app's presentation over a constant background (five included), a glass
  dock of 5:3 app tiles six to a row, and the widgets on the right. Tiles are layered icons:
  on focus the layers drift apart, a soft light moves across them and the edge catches a glare.
- **Tabs** at the top: Home, Movies, TV Shows and Sports. The remote's ☰ (Menu) button jumps to
  them from anywhere; pressing Up repeatedly ends there too.
- **Movies and TV Shows** (TMDB): a hero carousel with title logos, Continue Watching, Top 10,
  new arrivals, "Popular on …" for each streaming app installed, In Theaters and Coming Soon,
  Top Rated. Select opens a details page with where it streams and a button to open that app.
- **Sports** (ESPN): Live Now, My Teams, and a row per league (NFL, college football, NBA,
  NHL, MLB, MLS, Premier League), with live scores, start times and the channel or app.
- **Continue Watching**: built only from what has actually played on this TV, read from the
  streaming apps' media sessions. Fire TV does not share Amazon's or the apps' own watch
  history with other launchers, so it starts empty and fills as you watch.
- **Widgets**: a Smart Stack (one card that turns between weather, your next game, Continue
  Watching and the date) or Glances (four small squares), chosen in Home Screen settings.
- **Control Center** from the status capsule: Sleep, Sleep Timer, Wi-Fi, Bluetooth, and
  shortcuts to screen mirroring, accessibility, display settings and Home Screen settings.

More screens: [pages](docs/screenshots/sheet-pages.png),
[widgets](docs/screenshots/sheet-widgets.png),
[Control Center and settings](docs/screenshots/sheet-control.png).

## Status

Built and tested off the device: Robolectric renders every screen at 1920x1080 and drives it
with remote key events (navigation, held keys, Back, the Menu button). It has **not been
installed on the TV yet**. Still to check there: that the Home button stays Amazon's, which
streaming apps report their titles for Continue Watching, that the Sleep permission works on
Fire OS, and which Fire TV settings screens the Control Center shortcuts open.

## Build and test

Needs JDK 17 and the Android SDK (platform 35) in `ANDROID_HOME`.

```sh
cd launcher
./gradlew assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest    # renders into launcher/screenshots/
```

The tests replay TMDB and ESPN responses recorded on 20 October 2026
(`app/src/test/fixtures`), so they run offline and show the same games and titles every time.
To record anything missing: `./gradlew testDebugUnitTest -PnetRecord=true -PtmdbTokenFile=<file>`.

## Install on the TV

With ADB debugging on and the TV reachable at `<tv-address>`:

```sh
adb connect <tv-address>:5555
adb install -r app-debug.apk

# Continue Watching: lets the launcher see what the streaming apps play (Fire TV has no screen for this)
adb shell cmd notification allow_listener app.glasshome.launcher/app.glasshome.launcher.MediaWatcher

# Control Center Sleep and Sleep Timer: allows turning the screen off, nothing else.
# Home Screen settings > Allow Sleep gives it back, so the launcher can always be uninstalled.
adb shell dpm set-active-admin app.glasshome.launcher/.SleepAdmin
```

**TMDB token** (for Movies, TV Shows and featured titles): copy
`launcher/app/src/main/assets/apps.json`, put the token in `tmdb_api_key`, and push it to
`/sdcard/Android/data/app.glasshome.launcher/files/apps.json`. It stays on the TV; never commit it.
The same file sets the app order, hidden apps and `favorite_teams` (e.g. `"nfl:CIN"`).

## Remote

| Key | Does |
| --- | --- |
| Arrows | Move focus. Held, they step at a steady rate. |
| Select | Opens the focused app, title, game or widget (once per press). |
| ☰ Menu | To the tab bar, closing anything open; again to go back down. |
| Back | Closes what is open, then steps back toward Home. |
| Home | Left to Fire TV. |

## Debloat

`firetv/disabled-packages.txt` lists the 19 Amazon packages that were turned off with
`pm disable-user --user 0` (telemetry, ads, ambient and shopping). Nothing was uninstalled, and
Fire OS updates, voice, casting and mirroring were kept. `firetv/restore.sh` turns them all
back on.

## Data and credits

- Film and TV data and artwork: [TMDB](https://www.themoviedb.org/). This product uses the TMDB
  API but is not endorsed or certified by TMDB. Streaming availability: JustWatch, via TMDB.
- Scores and schedules: ESPN's public scoreboard feed, which is unofficial and may change.
- Weather: [Open-Meteo](https://open-meteo.com/).
- Font: [Inter](https://rsms.me/inter/), SIL Open Font License 1.1 (`assets/fonts/Inter-OFL.txt`).
  No Apple fonts, symbols or templates are used.
- App logos are trademarks of their owners, and the backgrounds and several icons were supplied
  for personal use. Keep this repository private.
