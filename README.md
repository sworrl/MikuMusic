<h1 align="center">Miku Music</h1>

<p align="center"><em>A bit-perfect music player for the HiBy Digital M500 x Hatsune Miku. Real DACs, real numbers, no invented readings.</em></p>

<p align="center">
  <img alt="License" src="https://img.shields.io/badge/license-GPL--3.0--or--later-blue">
  <img alt="Platform" src="https://img.shields.io/badge/platform-Android%2014-3ddc84">
  <img alt="Device" src="https://img.shields.io/badge/device-HiBy%20M500%20(khaje)-39C5BB">
  <img alt="DAC" src="https://img.shields.io/badge/DAC-dual%20CS43198-FF5FA2">
  <img alt="Output" src="https://img.shields.io/badge/output-DIRECT%20PCM%2C%20verified-success">
  <img alt="Visualizer" src="https://img.shields.io/badge/visualizer-libprojectM%204.2.0-B388FF">
</p>

Miku Music is the music player for [MikuOS](https://github.com/sworrl/MikuOS), a platform-signed
Android 14 replacement for the HiBy Digital M500 x Hatsune Miku DAP. It plays local files straight
to the device's dual Cirrus Logic CS43198 DACs with no resampling and no mixer in the path, draws
its visuals with real libprojectM, and does not display a number it did not measure.

It is a normal Android app and it builds with Gradle. It expects the M500's hardware, so most of it
runs anywhere and the parts that talk to the DAC do not.

**Why it exists.** The M500 is very good hardware running software that hides it. Stock firmware
routes music through Android's mixer, so a 44.1kHz file gets resampled to whatever the mixer happens
to be running at before it reaches a pair of DACs chosen specifically for not needing that. The
hardware is capable of exact playback. Nothing in the stock stack lets you have it.

**The other reason.** Audio apps lie constantly. They show a bit depth they got from a filename, a
sample rate that is the mixer's and not the file's, a "BPM" invented from the title, an EQ curve that
is not connected to anything. Every reading in this app comes from the file or the hardware or it is
not shown at all. There is a dash where other players guess.

---

## Contents

- [What is verified](#what-is-verified)
- [The audio chain](#the-audio-chain)
- [Features](#features)
- [The visualizer](#the-visualizer)
- [Tape mode](#tape-mode)
- [Scrobbling](#scrobbling)
- [Build from source](#build-from-source)
- [Install](#install)
- [Repo structure](#repo-structure)
- [The device](#the-device)
- [Known limitations](#known-limitations)
- [Contributing](#contributing)
- [Legal](#legal)

---

## What is verified

This table is the line between what has been confirmed on the hardware and what has not. It is the
first section on purpose.

Legend: ✅ confirmed on a real M500 · ⚠️ implemented, not proven on hardware · ❌ tried, does not work.

| Thing | State | How it was checked |
|---|---|---|
| DIRECT PCM output at 44.1 / 48 / 192kHz | ✅ | `dumpsys audio` shows the `direct_pcm` profile, flags NONE, sample rate matching the FILE on every track change |
| 24-bit packed output | ✅ | Confirmed live at native 48k/24 |
| No resample on the playback path | ✅ | Output sample rate tracks the file, not the mixer |
| Bluetooth codec forced to the best the sink offers | ⚠️ | Enforcement code runs and logs; the LDAC push path is unproven because no LDAC sink has been connected to it |
| DAC register visibility (filter, DRE, gain, high-power) | ✅ | Read and written through `vendor.audio.hiby.*`, verified against the stock app's behavior |
| Per-track sample rate and bit depth | ✅ | Read from the file with `MediaMetadataRetriever`, never from the extension |
| libprojectM 4.2.0 rendering | ✅ | Real native library, version read at runtime from `projectm_get_version_string()` |
| The 80 Miku presets | ⚠️ | Generated and confirmed unpacking into the preset library on device; not yet watched rendering one by one |
| CUE sheet splitting | ✅ | Disc images with a sibling `.cue` split into real tracks |
| Cue-less disc image splitting (MusicBrainz durations) | ⚠️ | Implemented, roughly 489 images in the test library still unsplit |
| FM tuner | ❌ | SELinux denies `platform_app` direct access to `/dev/radio0`. Two real paths forward are documented in MikuOS, neither is shipped |

---

## The audio chain

The whole point of the project, and the part worth reading the code for.

**What goes wrong by default.** Android's `AudioTrack.Builder.build()` adds `FLAG_DEEP_BUFFER` when
the requested buffer is around 100ms or larger. Qualcomm's AudioPolicyManager only routes to the
`direct_pcm` profile when the flags are NONE. So asking for a comfortable buffer silently opts you
out of direct output, and every track goes through the mixer at whatever rate the mixer is running.

**What this app does.** `MikuDirectAudioSink` is a Media3 `AudioSink` that requests a buffer
deliberately under the deep-buffer promotion threshold, so the track is built with no flags and the
policy manager routes it to the DAC directly. There is a fallback that retries at the platform
minimum if AudioTrack refuses the small buffer, because a silent failure here means silence.

**The bug that hid it for months.** The sink computed its small buffer and then clamped it with
`max(getAudioTrackMinBufferSize(), threshold)`. On this device the platform minimum is about double
the threshold, so the `max` always won, the buffer was always over the line, and every track was
deep-buffered onto a 192kHz mixer while the app cheerfully reported bit-perfect. Fixed 2026-09-17.
If you are writing a direct-output sink for any Android device, that is the trap.

**Integer PCM passthrough.** 16, 24 and 32-bit integer PCM reach the DAC in their own format.
No float conversion, no dither, no volume attenuation applied in software on the DIRECT path, which
is also why volume behaves differently there than through the mixer.

---

## Features

| Area | What it does |
|---|---|
| **Playback** | Media3 + MediaSession, gapless, FLAC/ALAC/WAV/AIFF/DSF/DFF/MP3/AAC/OGG/Opus, replay from where you stopped |
| **Library** | Songs, albums, artists, genres, folders, playlists. Per-track true sample rate and bit depth read from the file |
| **Now Playing** | Full-bleed art, wavy scrubber, dynamic palette taken from the album, a track-facts strip that only shows facts it has |
| **Visualizer** | Real libprojectM 4.2.0, 80 Miku presets plus whatever else you drop in, swipe to change, fullscreen |
| **Tape mode** | A spec-exact Compact Cassette deck. Real IEC 60094-7 millimeter geometry, wound-pack physics, wow and flutter, 18 shell themes |
| **Likes** | Track, album and artist hearts. An album like covers every track on it unless you explicitly refuse one |
| **Taste engine** | Affinity model and a co-occurrence graph over your own listening. Miku Radio station mode, optional smart shuffle |
| **Listening stats** | A local play database and a Miku Rewind recap. Yours, on device |
| **Scrobbling** | Last.fm API 2.0 with an offline queue. Browser sign-in, so the app never handles your password |
| **Booklet viewer** | Sleeve, booklet and PDF scans from the album folder, rendered as the physical object |
| **Artist photos** | Exact-match Wikidata/Wikipedia lookups with a local `artist.jpg` override and a reject button |
| **Alarm clock** | `setAlarmClock` with a full-screen intent over the lockscreen, fade-in, snooze on the side button |
| **CUE and disc images** | Whole-disc rips split into real tracks from a sibling cue sheet |
| **BLE remote** | A GATT peripheral, off by default, six-digit pairing, with a Web Bluetooth companion page |
| **Hardware settings** | Digital filter, DRE, gain, high-power mode, straight to `vendor.audio.hiby.*` |

---

## The visualizer

It is real [libprojectM](https://github.com/projectM-visualizer/projectm) 4.2.0, vendored and built
for arm64, not a shader that looks a bit like Milkdrop. The version shown on screen is read out of
the loaded library at runtime, not written into our source, so it cannot drift from the truth.

`app/src/main/assets/miku_presets/` holds 80 presets written for this project, in ten families of
eight: Negi Rain, Twintail Flow, Hatsune Bloom, Vocaloid Circuit, Miku Vortex, Leek Spin, Crystal
Chorus, Neon Stage, Tidal Teal, Star Aria. Families differ in motion and render math. Variants
inside a family change symmetry, speed, wave mode, decay and what the audio drives, not just the
hue. They are classic version-1 `.milk` presets with no HLSL blocks, which is the part of the format
projectM's GLES path handles without argument. `tools/gen_miku_presets.py` in the MikuOS repo
regenerates them.

The rest of the library is projectM's own "cream of the crop" pack, which is not redistributed here
because it is not ours. Drop `presets_pack.zip` into `app/src/main/assets/` before building, or
point the app at any folder of `.milk` files.

---

## Tape mode

A cassette deck drawn from the actual Philips/IEC spec, not from a photo of one.

Shell 101.6 x 63.5mm, hub centers 42.5mm apart, 8.5mm splined spindle holes, an 11.4 to 24.5mm
wound pack radius. The reels turn at the rate the tape is actually moving, so the supply reel
empties and the take-up reel fills at the correct changing angular velocity. There is wow at about
0.7Hz from pack eccentricity and flutter at about 12Hz from guide friction, scaled by the grade of
the stock, because a good metal tape on a good transport barely wanders and a cheap ferric one
audibly does.

Eighteen shells, each a real design convention rather than a color scheme: hub styles, notch layouts,
window variants, wear cues. Three of them are earned in the BPM game.

Volume in tape mode is the deck's own fader, permanently on the shell with a twelve-segment LED
ladder, because the app's normal volume modal has no business covering the cassette.

---

## Scrobbling

Last.fm Audioscrobbler 2.0, dependency-free, with an offline queue so nothing is lost without a
network.

Sign-in uses the desktop token flow: the app asks for a request token, opens `last.fm` in your own
browser where you are already signed in, and exchanges the approved token for a session key. The app
never sees your account password. `auth.getMobileSession` exists in the code and nothing in the UI
reaches it.

The session key never expires and is a bearer credential for scrobbling on your account, so it lives
in `EncryptedSharedPreferences` behind a hardware-backed master key, same as the API shared secret.

A build ships with an API key in `local.properties`. If you would rather scrobble through your own
Last.fm API account, Settings has fields for it and user credentials win over the build's.

---

## Build from source

```bash
git clone https://github.com/sworrl/MikuMusic
cd MikuMusic

# Minimum local.properties
cat > local.properties <<'EOF'
sdk.dir=/path/to/Android/Sdk
EOF

./gradlew :app:assembleDebug
```

Optional `local.properties` keys, all blank-safe. Anything absent fails soft and the feature says so
in Settings rather than crashing or pretending:

| Key | What it turns on |
|---|---|
| `lastfm.api.key` / `lastfm.api.secret` | Scrobbling without the user bringing their own API account |
| `miku.windy.key` | Windy layers in the weather tile |
| `miku.entitlement.url` / `miku.entitlement.hmac` | The entitlement client, which is inert without both |
| `miku.ingest.host` | The library ingest server |

Release builds are signed with the Falcon Technix platform key so the OS trusts them as platform
apps. That key is not in this repo. Without it `assembleRelease` produces an unsigned APK, which is
fine for anything except replacing a system app.

**Native.** libprojectM is a git submodule built by CMake. It is symlinked in, and Gradle cannot see
through the symlink to know it changed, so after updating it you MUST `rm -rf app/.cxx` or the build
silently ships the previous `.so`.

**The JIT ceiling.** ART refuses to compile a method over 16384 dex instructions and interprets it
forever instead. Compose composables hit this easily. `tools/scan_jit_limit.sh` in the MikuOS repo
scans an APK and exits non-zero if anything is over. Run it before shipping.

---

## Install

On a MikuOS device the apps come with the ROM. To install a build over the top:

```bash
adb install -r -d MikuMusic-v2.0.287.apk
```

The debug and release builds must be signed with the same key or the install is refused, and
uninstalling takes your likes and history with it. Back up `/data/data/com.miku.player` first.

---

## Repo structure

| Path | What it is |
|---|---|
| `app/` | Miku Music itself, `com.miku.player` |
| `app/src/main/java/androidx/media3/exoplayer/audio/` | `MikuDirectAudioSink`, the bit-perfect sink |
| `app/src/main/cpp/` | The projectM JNI bridge |
| `app/src/main/assets/miku_presets/` | The 80 presets |
| `mikuos-launcher/` | The MikuOS home screen, `com.miku.launcher` |
| `mikuos-systemui/` | Navigation gestures, shade, idle dim, `com.miku.systemui` |
| `mikuos-settings/` | The MikuOS settings app |
| `hardware-settings/` | DAC and hardware controls, `com.m500.hardware` |
| `fmradio/` | FM tuner UI, shipped as `com.caf.fmradio` so vendor SELinux rules apply |

The launcher and system UI live here rather than in MikuOS because they are Gradle modules of the
same build and share the theme and motion code with the player.

---

## The device

HiBy Digital M500 x Hatsune Miku edition, product `khaje`. Android 14 base, 3.2 inch 720x1280
portrait panel at 270dpi. Dual Cirrus Logic CS43198 in the "MIKU DAC" configuration. 3.5mm single
ended and 4.4mm balanced out, plus USB DAC. Snapdragon 665, four little cores that matter more than
you would expect. Physical power, volume wheel, play/pause, next, previous and Fn on the right edge.

There is no ambient light sensor. MikuOS measures room brightness with the camera's auto-exposure
instead, which is documented over in that repo.

---

## Known limitations

- **One device.** Everything here is verified on an M500 and nothing else. The bit-perfect path in
  particular depends on Qualcomm's `direct_pcm` profile being present and on this vendor's policy.
- **FM does not work.** SELinux denies direct `/dev/radio0` access to a `platform_app`. The UI is
  there, the tuner is not.
- **LDAC push is unproven.** The enforcement code runs, no LDAC sink has been connected to verify it.
- **Roughly 489 cue-less disc images** in the test library are still one file per disc.
- **Volume behaves differently on the DIRECT path**, because no software attenuation is applied
  there. That is correct and it is also surprising the first time.
- **The preset pack is not included.** See [The visualizer](#the-visualizer).

---

## Contributing

Issues and pull requests are welcome, with one house rule that is not negotiable:

**Never display a value you did not measure.** No placeholder percentages, no bit depth inferred
from a file extension, no BPM guessed from a title, no "probably" rendered as a number. If the data
is not there, show a dash and say why. Several passes of this codebase have been spent removing
exactly that kind of thing and it is not going back in.

Beyond that: match the surrounding code, comment the WHY rather than the what, and if you fix a
non-obvious platform behavior, write down what the platform actually does so the next person does
not have to rediscover it.

---

## Legal

GPL-3.0-or-later. See [LICENSE](LICENSE).

Hatsune Miku and the associated character designs are the property of Crypton Future Media. This is
an unaffiliated hobby project for a device Crypton licensed, and it ships no Crypton artwork. HiBy
Digital's firmware, applications and assets are theirs and none of them are redistributed here.
libprojectM is LGPL-2.1 and is used as a library. Media3 and the AndroidX libraries are Apache-2.0.

Flashing a replacement OS onto a device can brick it. This one is used daily on the author's own
M500, which is a statement about one device and not a warranty about yours.
