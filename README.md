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

### Discovery

WLED clients (the app, Home Assistant) find devices via mDNS and then verify
them over **HTTP on port 80** — the port is not negotiable. At startup the app:

1. tries to bind `:80` directly (works if the kernel allows unprivileged low ports),
2. falls back to `:8080` plus an `iptables` NAT redirect `:80 -> :8080` via `su`,
3. if `iptables`/NAT is unavailable, spawns a tiny TCP relay as root via
   `app_process` (`RootProxy`), which binds `:80` and forwards to `127.0.0.1:8080`,
4. self-tests `http://<ip>:80` and shows the result in the app status line.

If the status line says "`:80` not reachable, discovery limited", the device can
still be used by IP (`http://<ip>:8080`) but won't appear in discovery lists.
The app runs as a foreground service and autostarts on boot.

### Effects

20 effects are implemented with contiguous ids 0-19 (only implemented
effects are listed, so no `RSVD` entries): Solid, Blink, Breathe, Wipe,
Rainbow, Scan, Dual Scan, Fade, Running, Twinkle, Sparkle, Strobe, Chase,
Fireworks, Rain, Fire Flicker, Gradient, Fire 2012, Meteor, Palette.
13 palettes: Default, * Random Cycle, * Color 1, Rainbow, Party, Sunset,
Fire, Icefire, Forest, Ocean, Pastel, Lava, Cloud. With a palette != Default,
Solid renders it as a static gradient and palette-aware effects color their
primary element from it.

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

The API service autostarts on boot (`BOOT_COMPLETED` / `QUICKBOOT_POWERON`)
and right after app updates (`MY_PACKAGE_REPLACED`). Android requires one
launch after a *fresh* install before background components may run; to start
the API without opening the UI:

    adb shell am startservice -n net.mohnen.prodvxled/.WledService
    # alternatives: broadcast to the autostart receiver, or launch once
    adb shell am broadcast -a net.mohnen.prodvxled.START_API -n net.mohnen.prodvxled/.BootReceiver -f 0x20
    adb shell am start -n net.mohnen.prodvxled/.MainActivity

## Notes

- Writing to `/dev/ttyS1` needs root (switch in the app, default on) or a device
  where the port is already writable (then turn the switch off).
- The LED protocol is `55 AA` + 52 x `[L,B,R,G]` + `88` @ 115200 8N1; frames are
  pushed through a persistent `cat > /dev/ttyS1` pipe for ~30fps effects.
- Global brightness maps onto the per-LED brightness nibble (1..15); finer
  dimming is done by scaling RGB on top.
