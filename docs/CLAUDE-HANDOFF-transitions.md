# Home recovery and smoother transitions — 2026-10-21

## Observed on the target Fire TV
- Installed locally built debug APK; TMDB configuration copied into private files.
- After reboot, enabled_accessibility_services was null, accessibility_enabled was 0.
- GET_USAGE_STATS remained allow. Amazon launcher RUN_ANY_IN_BACKGROUND was ignore.
- User reports choppy app launches, return Home, and tab changes.
- No device trace or frame timing was available to this contributor. Do not call performance verified.
- Preserve the existing device configuration backup on the owner's PC.
- Never commit apps-private.json, TMDB credentials, device backups, or signing keys.

## Changes
- HomeServiceRecovery handles BOOT_COMPLETED and MY_PACKAGE_REPLACED and is called on activity resume.
- Recovery requires an explicit ADB WRITE_SECURE_SETTINGS grant AND the existing Home Button preference.
- It preserves other accessibility components, avoids duplicate abbreviated components, and does no polling.
- Recovery restores accessibility settings only; the existing service handles the actual Home redirect.
- This is best effort, not proof against Fire OS clearing settings again after the broadcast.
- Resume content refreshes and stale weather updates wait 600 ms; pending refresh is canceled on pause/destroy.
- App opening uses a 120 ms alpha transition instead of capturing/scaling a tile bitmap.
- Tab changes immediately hide the outgoing page, then fade in the incoming one. This reduces simultaneous page blending.
- onPause reuses the settled Home snapshot rather than issuing a GPU PixelCopy during the app handoff.
- Existing 120 ms Home cover fade is retained. No boot splash or persistent screenshot cache was added.
- Android CI compiles and runs the full Robolectric suite on pull requests. Hardware checks remain required.

## Build and update (Windows)
Use the same PC and signing key as the installed APK. A debug build from another machine may have a different signature; never uninstall to work around this without preserving data.
Download this branch or check it out, then:
```powershell
$env:JAVA_HOME = "C:\path\to\jdk-17"
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
.\gradlew.bat assembleDebug testDebugUnitTest
$adb = "$env:LOCALAPPDATA\AndroidTools\platform-tools\adb.exe"
$tv = "<tv-address>:5555"
& $adb connect $tv
& $adb -s $tv install -r -d ".\app\build\outputs\apk\debug\app-debug.apk"
& $adb -s $tv shell pm grant app.glasshome.launcher android.permission.WRITE_SECURE_SETTINGS
& $adb -s $tv shell am start -n app.glasshome.launcher/.HomeActivity
```
WRITE_SECURE_SETTINGS is a broad system-settings permission; this implementation only writes the accessibility service list and accessibility-enabled flag. It is not granted by installing the APK. If Fire OS rejects the grant, record the error and do not claim recovery is enabled.

To opt out: turn Home Screen settings > Home Button off. To remove the additional privilege:
```powershell
& $adb -s $tv shell pm revoke app.glasshome.launcher android.permission.WRITE_SECURE_SETTINGS
```
Revoking recovery permission does not itself disable a currently enabled Home redirect.

## Device verification
1. Confirm existing accessibility services remain present; Home Button off stays off after resume and reboot.
2. With Home Button on and recovery permission granted, reboot twice and inspect:
   settings get secure enabled_accessibility_services
   settings get secure accessibility_enabled
   dumpsys accessibility
3. Verify Home from Netflix, Prime, Settings, app library, repeated Home presses, long-press quick menu, wake from Sleep, and a voice-launched app. Never redirect over wanted Settings or quick-menu screens.
4. Test rapid tab reversals and held arrows, first/empty loads, offline pages, preserved focus, Reduce Motion.
5. Measure before/after with dumpsys gfxinfo app.glasshome.launcher reset, then identical navigation and dumpsys gfxinfo app.glasshome.launcher framestats. Use video for system/app handoff gaps; launcher frame timings alone do not measure Amazon's window.
6. Check fresh process startup separately from warm resume. Current snapshot exists only in memory; no guarantee of hiding the first Amazon frame after reboot.
7. Confirm deferred refreshes do not jump focus. Existing screenshots/navigation tests may need deliberate timing expectations reviewed, not weakened.
8. If Home recovery fails after boot, capture setting values, receiver/service logs, and package stopped state before adding any retry mechanism. Do not introduce continuous polling, disable Amazon launcher, or bypass platform restrictions.

## Remaining scope / acceptance
Physical Fire TV responsiveness, boot ordering, and OEM animation behavior must be verified. These changes reduce known transition work but do not establish a specific FPS or remove every Fire OS handoff. Persistent cached startup UI and broader image-cache lifetime tuning should be considered only after profiling.
