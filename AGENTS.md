# LibrePods Agent Notes

## Goal

Preserve a clean, repeatable debugging loop for Android LibrePods issues without mixing unrelated layers.

## Iteration Procedure

1. Reproduce in one known setup.
2. Capture logs for that exact run.
3. Patch only the failing layer.
4. Rebuild the APK.
5. Update the installed app with `adb install -r`.
6. Retest the same scenario.
7. Rebuild Magisk artifacts only if the change actually affects them.

For app-code changes, prefer:

```bash
cd android
./gradlew :app:assembleDebug
cd ..
adb install -r android/app/build/outputs/apk/debug/app-debug.apk
```

Only rebuild Magisk artifacts when changing:

- `root-module/`
- `local-tools/librepods-privapp-module/`
- module packaging scripts

## Logging

When logs are needed from the user's device, instruct the user to run:

```bash
./local-tools/collect-librepods-logs.sh
```

That script:

- creates timestamped logs in `local-tools/logs/`
- records basic device/build context
- streams full `logcat`
- should be stopped with `Ctrl+C`

Prefer broad log capture first and filter after reading the log.
