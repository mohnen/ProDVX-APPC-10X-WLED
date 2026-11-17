# ProDVX-APPC-10X-WLED

Turns a ProDVX APPC-10X panel (Android 9) into a **WLED-compatible device** on the
network, driving the 52-LED frame over `/dev/ttyS1`. WLED apps, Home Assistant,
xLights, Hyperion etc. can find and control it like a normal WLED strip.

## WLED interface

- **HTTP JSON API** — `/json/state`, `/json/info`, `/json/effects`, `/json/palettes`
  (GET + POST), plus `/json` and `?v=` combined info. CORS enabled.
- **Legacy XML API** — `/win` with `T`, `A`, `R`, `G`, `B`, `FX`, `SX`, `IX`, `FP` params.
- **WebSocket** — `/ws`, pushes `{state, info}` on connect and on every change,
  accepts the same JSON commands.
- **UDP realtime** — port `21324`, protocols WARLS (1), DRGB (2), DNRGB (4).
- **mDNS** — announces `wled-prodvx` as `_wled._tcp` and `_http._tcp`.
- **Web UI** — `http://<panel-ip>/` has basic on/off, brightness, color, effect,
  speed controls (this is not the full WLED UI).

The server listens on `:8080`; when root is available the app adds an
`iptables` redirect so port `:80` works too (what WLED clients expect).
The app runs as a foreground service and autostarts on boot.

### Effects

A subset of real WLED effect IDs is implemented: Solid (0), Blink (1),
Breathe (2), Wipe (3), Rainbow (9), Scan (10), Dual Scan (11), Fade (12),
Running (15), Twinkle (17), Sparkle (20), Strobe (23), Chase (28),
Fireworks (42), Rain (43), Fire Flicker (45), Gradient (46), Fire 2012 (66),
Meteor (76). Unimplemented IDs are reported as `RSVD` and render as Solid.
Palettes: Default, * Random Cycle, * Color 1, Party, Rainbow, Sunset, Fire,
Icefire.

Single segment only (52 LEDs, `start`/`stop` honored).

### Local UI

The app itself still shows preset buttons, a brightness slider, the server
on/off switch and the root toggle. Presets act like a manual pattern and are
cleared as soon as an API client sets colors/effects.

## Build the APK

Option A - Android Studio: open this folder, let it sync, Build > Build APK(s).
Option B - command line (JDK 17 + Android SDK, Gradle 8.7+):

    gradle wrapper --gradle-version 8.7
    ./gradlew assembleDebug

Option C - push to GitHub; the included workflow builds the APK (Actions > artifact).

Output: `app/build/outputs/apk/debug/app-debug.apk` (debug-signed, installable)

## Install

    adb install -r app-debug.apk

## Notes

- Writing to `/dev/ttyS1` needs root (switch in the app, default on) or a device
  where the port is already writable (then turn the switch off).
- The LED protocol is `55 AA` + 52 x `[L,B,R,G]` + `88` @ 115200 8N1; frames are
  pushed through a persistent `cat > /dev/ttyS1` pipe for ~30fps effects.
- Global brightness maps onto the per-LED brightness nibble (1..15); finer
  dimming is done by scaling RGB on top.
