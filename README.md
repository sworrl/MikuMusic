# Miku Music

**An Open Source Music Player** — a native Kotlin / Jetpack Compose music player built for the
HiBy Digital M500 (x Hatsune Miku edition) DAP, and any Android 8+ device.

Miku Music is a from-scratch rewrite of the ideas in the HiBy Music mod, tuned for the M500's
dual CS43198 DAC hardware and its portrait 3.2" screen, with a heavy dose of personality.

## Features

- **Full local library player** — MediaStore-backed, recursive scan with a manual rescan button,
  verbose per-track metrics (bitrate, format, size, duration) colored by quality tier
- **Media3 / ExoPlayer playback** with a MediaSession: lockscreen + notification media controls,
  hardware media keys, background playback via a foreground `MediaSessionService`
- **Real libprojectM 4.2 visualizer** — native GLES3 build with 120 bundled Milkdrop `.milk`
  presets, audio-reactive, gesture-driven fullscreen mode (tap = controls, double-tap = next
  preset, long-press = lock, swipe = prev/next), usable as a Now Playing background
- **Tape Mode** — a Compact Cassette drawn to the real IEC 60094-7 mechanical spec in millimetre
  coordinates (101.6 × 63.5 mm shell, 42.5 mm hub spacing, corner guide rollers, head/pinch/capstan
  openings), with working tape physics: area-conserving pack radii, a continuous tape strand, and
  reels that spin while playing. Tap cycles through branded layout themes amalgamated from real
  artist and blank-tape designs (studio-grade, '84 major label, neon inversion, pastel merch)
- **Likes with rainbow hearts** (tracks *and* albums), playlists, play history, per-track play
  counts, "recently played" home rows
- **Home / Songs / Artists / Albums / Library** tabs, artist/album detail views, album art
  everywhere with a two-level (memory + disk) art cache
- **Queue context menus** — play next, add to queue, front, or a random upcoming slot
- **HiBy hardware aware** — reads/writes the M500's `vendor.audio.hiby.*` DAC settings
  (filter, DRE, gain) when platform-signed
- **Per-listen location logging** (opt-in) for "last played in <city>" fun facts

## Building

Requirements: JDK 17, Android SDK (compileSdk 35), Gradle 8.x wrapper (do **not** use a system
Gradle 4.x), NDK + CMake for the native visualizer.

```sh
JAVA_HOME=~/.local/toolchains/jdk17 ./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The projectM sources are expected at `app/src/main/cpp/vendor/projectm` (libprojectM 4.2.0 with
its submodules; a symlink to a local checkout works). ABI is limited to `arm64-v8a`.

### Device notes (HiBy M500)

- Buttons (power, volume wheel, play/pause, next/prev) are on the **right edge**; Tape Mode is
  drawn to be viewed sideways with that rail on top.
- Writing `vendor.audio.hiby.*` global settings needs `WRITE_SECURE_SETTINGS` — platform-sign the
  APK with the device keys for that; a debug build plays fine but can't switch DAC modes.

## License

Open source — have fun with it! But if you're from HiBy and use this code, please attribute me and MAYBE send over some free samples of new gear! 😉
