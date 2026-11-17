# LED Presets (Android 9, ProDVX APPC-10X)

Simple app with preset buttons + brightness slider for the LED frame on /dev/ttyS1.

## Build the APK
Option A - Android Studio: open this folder, let it sync, Build > Build APK(s).
Option B - command line (JDK 17 + Android SDK, Gradle 8.7+):
    gradle wrapper --gradle-version 8.7
    ./gradlew assembleDebug
Option C - push to GitHub; the included workflow builds the APK (Actions > artifact).

Output: app/build/outputs/apk/debug/app-debug.apk  (debug-signed, installable)

## Install
    adb install -r app-debug.apk

## Notes
- Writing to /dev/ttyS1 needs root (switch in the app, default on) or a device where the
  port is already writable (then turn the switch off).
- Presets are defined in MainActivity.kt (`presets` list).
