# Miku Music — Changelog

Native Kotlin/Compose player for the HiBy M500 MIKU (`com.miku.player`).

## 0.9.17 — 2026-08-15 — tape materials + OS fn-lock

### Tape mode — de-slopped, themed
- Rebuilt as a real cassette: gold **metal shell** with fine engraved ribbing + directional gradient,
  a **recessed beveled window** showing the album art with **projectM behind it** (now more visible),
  and reels with genuine depth — drop shadow, metallic radial-gradient hub, glossy tape pack, and the
  correct Compact-Cassette spindle (18-hole flange ring + 6-tooth splined drive + centre hole).
- Correct tape transfer (top supply empties, bottom take-up fills, chronological to the track).
- Tap cycles themes: GOLD / MEMOREX / CHROME. Corner screw bosses + head mechanism.

### Pocket lock — OS-level (fn = Both)
- Discovered the M500's fn-lock is driven by the global setting `fn_settings` (modes: `key_lock`,
  `touch_lock`, `Both`, `none`) + `fn_status`. Set it to **`Both`** so the fn button locks the
  touchscreen AND the physical buttons together (system-wide). Removed the app-level touch-lock
  overlay (a press-and-hold is useless when a pocket holds it by itself). Power-button lock still
  needs a framework/keylayout patch (recorded).

## 0.9.14 — 2026-08-15 — visualizer overhaul, tape rebuild, taste groundwork

### Visualizer
- **130 real, descriptively-named presets** (from the named projectM library) replace the generic
  `preset_NNN` pack — so the on-screen name is the actual preset — plus **10 original Miku-themed
  `.milk` presets** (teal/pink, twin-tail tunnels, 39-pulse, spectrum, etc.).
- **Runs every preset on the slow GPU** (no presets dropped for weight): mesh lowered to 24×18 and
  the render buffer capped at 800px (upscaled), holding ~60fps.
- **Fullscreen was silent → fixed**: a single shared `AudioCapture` (one Visualizer for the session)
  instead of two instances fighting over the audio session.
- **No more preset-jump entering fullscreen**: the enum "setPreset" no longer advances the playlist,
  and the playlist position is saved/restored across the per-surface projectM re-create.
- **Fullscreen controls reworked**: the viz stays full-bleed; tap reveals a compact translucent glass
  card (title/artist/preset, embossed scrubber, transport + preset-cycle) with a **pin** to keep it up.
- Live preset name reads fresh (mutex-guarded playlist query).

### Now Playing
- Header reads **MIKU MUSIC PLAYER**; album art no longer clipped (Fit); **blurred album-art +
  Miku background**; the old "Location & Format" card is now an **artistic art + data panel**
  (art, album/year, format/bitrate/quality-tier chips).
- **Embossed, color-shifting scrubber** — recessed groove, raised glossy fill that drifts teal→pink
  with progress and dims when paused, travelling shimmer, raised knob. Feels like a physical control.

### Tape mode — rebuilt to match a real deck
- Vertical cassette filling the screen (the "sideways" tape): **album art as the tinted background**,
  two stacked reels with metallic 6-hole hubs that turn while playing (top empties, bottom fills),
  centre teeth + tape strands, vertical serif title clear of the reels, teal artist·album, MIKU mark,
  times by each reel, corner screws.

### Likes / library / QoL
- **Artist likes** (third tier alongside song + album likes), rainbow heart in artist rows + detail.
- **Albums list chronologically** (by year); tracks stay in track-number order.
- **Animations work with the device's animations disabled** (`animator_duration_scale = 0`): rainbow
  hearts, equalizer bars, and scrubber shimmer are now driven by manual time loops — hearts were
  stuck red before; verified shifting teal→magenta on-device.
- **Playing-track indicator**: the currently-playing row shows an animated equalizer over its art +
  a teal title.
- **Exact resume after an app update**: restores the real last track + position and only auto-resumes
  if it was playing (a full reinstall still kills the process — audio can't literally survive it).
- **Portrait-locked** (no rotation; also ends the rotation queue-clobber).
- Fixed a launch crash: the MediaSessionService is now started from a foreground context, not onCreate.

## 0.9.7 — 2026-08-15

### Now-playing "stops updating / stuck on an old track"
- Now-playing now re-syncs to the player's **actual** current item (`currentMediaItem`) on any
  item/timeline change, instead of trusting the transition event's passed item (which could go
  stale and leave the bar frozen on a previously-played track).
- Position poll loops (mini-bar + full screen) are wrapped so a transient player error can't kill
  the `while` loop and freeze the UI.
- Removed another dead per-composition `loadLikedTracks` disk read in the mini-bar.

## 0.9.6 — 2026-08-15 — UI/QoL round + real tape mode

### Performance (interface felt laggy)
- **`TrackRow` read SharedPreferences from disk on every row composition** (a dead, unused `liked`
  var) — removed. This was the main scroll jank.
- Metric `AnnotatedString`s are now built once per track (`remember`), not every recomposition.
- `tracks.artists()` / `tracks.albums()` were regrouping the whole library on every recomposition;
  now memoized per library change. Lazy lists all carry stable keys.

### Play history + track metrics (were nowhere to be found)
- New play history + per-track play count + last-played timestamp (`PlayerPreferences`), recorded
  on every track that starts.
- Home gets a **Recently played** shelf (horizontal art cards).
- **Long-press any track → a metrics sheet**: art, title/artist/album/year, format, bitrate,
  duration, size, play count, last-played (relative), and full file path.

### Tape mode — rebuilt
- Replaced the placeholder cassette with a realistic Maxell/Sony-style deck: smoke shell with
  gloss, cream label + teal/pink header stripes + "A" badge, a real tape window with two wound
  reels (concentric tape texture) that fill left→right with progress and spin while playing,
  the exposed tape strand, bottom head-access + capstan cutouts, and 5 slotted screws.

## 0.9.4 — 2026-08-15 — code-vet pass

Full multi-agent review of the codebase; fixed the real bugs found (crashes/leaks/lifecycle/correctness).

### Crashes / threading (visualizer)
- **Off-thread projectM call** — `AndroidView`'s factory/update lambdas run on the UI thread and
  reached `setPreset → projectm_playlist_play_next` (projectM is GL-thread-only) on every
  recomposition → latent SIGSEGV. `setPreset` now hops to the GL thread via `queueEvent` and
  skips no-op repeats; the switch is covered by the same skip-render guard as gestures.
- **Cross-thread use-after-free** — during a fullscreen transition two GL threads can briefly
  coexist; the old thread's render could race the new thread's destroy+recreate of the global
  projectM handle. Added a native `std::mutex` serializing all handle access.

### Leaks
- `MediaMetadataRetriever` was released only on success → native-context leak on every art-less
  track; now released in `finally`.
- `Haptics` cached a `Vibrator` from a possibly-Activity Context (process-lifetime leak); now
  uses `applicationContext`.
- `rescan` Toast/Handler ran through the Activity context; now app-context.

### Lifecycle / correctness
- **Queue clobber on Activity recreation** — restore-on-launch keyed only on `currentTrack == null`
  (fresh Compose state), so a rotation/config-change rebuilt the queue with the whole library and
  seeked backwards. Now it rebuilds only when the player is empty; otherwise it reflects the
  current item. `isPlaying`/`currentTrack` are seeded from ongoing playback.
- MediaStore query moved off the main thread (`Dispatchers.IO`); `rescan`'s pre-count too.
- **Scan "found N new" under-reporting** — replaced the 3.5s guessed delay with a real
  MediaScanner completion callback (report once when the last path finishes; 20s fallback).
- Play/pause glyph now updates instantly via a `Player.Listener` instead of the 300ms poll;
  removed a dead duplicate `liked` state in the now-playing screen.
- `PlayerHolder.ensure/ensureSession/release` are now `@Synchronized`.

### Library grouping
- Artists group case-insensitively ("Gwar" == "GWAR"), displaying the most common casing.
- Albums group by `albumId` (distinct albums sharing a title stay separate) instead of by name.

### Reviewed & deferred (not bugs / by-design)
- Collab-splitter still trims "Earth, Wind & Fire" → "Earth" (known feat-stripping tradeoff).
- Album art cache keyed per-track not per-album (works; duplicates art across an album's tracks).

## 0.9.3 — 2026-08-15

### Fix
- **Play/pause "looping" on the physical keys** — once we added the MediaSession, it became the
  system media-button owner AND `onKeyDown` was also toggling, so each hardware press acted twice
  (play→pause→play). `onKeyDown` now only fires the haptic and passes the event through; the
  session performs the single transport action. Verified: one press = one state change.

## 0.9.2 — 2026-08-15

### Lockscreen / notification media control
- Added a Media3 `MediaSession` + `PlaybackService` (foreground `mediaPlayback`). Our playback
  now owns the lockscreen + notification media control — title, artist, and album art come from
  each track's metadata — instead of leaving the lockscreen to Spotify. Verified: our session
  becomes the system "media button session" and posts the foreground media notification.
- MediaItems now carry full `MediaMetadata` (title/artist/album + album-art URI).
- Player + session live in a process-wide `PlayerHolder` so playback and the lockscreen control
  survive the Activity; released on task-removal when idle.
- Physical media keys now route to us system-wide (via the session), not just while foregrounded.

### Known / next
- Cross-app interception (re-skinning *other* apps' media, e.g. Spotify, in Miku style) needs a
  `NotificationListenerService` + manual notification-access grant — separate follow-up.

## 0.9.1 — 2026-08-15

### Fixes
- **Double-tap visualizer crash fixed** — after a preset switch projectM briefly has no
  active preset; rendering that frame null-deref'd (SIGSEGV). Now skips ~5 render frames
  after a switch so the new preset loads first. Survives sustained double-tap spam.
- **Play/pause icon** now reflects real state (polls `player.isPlaying`); was stuck on ▶.

### Feedback & controls
- Every button is now a `HapticIconButton`: vibrator tick + springy scale-down on press.
- Physical transport keys (M500 side buttons / headset: next/prev/play/pause/play-pause)
  handled in `onKeyDown`, each with a haptic tick.

### Branding
- Title bar: teal gradient, Miku launcher icon as the app glyph, faded Miku watermark
  burned into the trailing edge.

### Known / next
- App-side MediaSession so lockscreen/widget show *our* now-playing (lockscreen still shows
  Spotify's session) and physical keys work while backgrounded.
- Hi-res video player that can push the amps/DAC (low priority).

## 0.9.0 — 2026-08-15

First tracked version. Everything below is on-device and working.

### Playback & library
- Media3/ExoPlayer playback; resume last track/position on launch.
- MediaStore library with manual rescan (MediaScanner across all volumes, "found N new" toast).
- Tab nav: Home / Songs / Artists / Albums / Library, with Artist → Album → Track drill-down.
- Album tiles show year + album-level format/bitrate; artist-name normalization (feat./collab stripping).
- Working scrub bar (position poll + drag-to-seek); shuffle & repeat wired to ExoPlayer.

### Visualizer (real libprojectM 4.2.0)
- Real libprojectM cross-compiled under the NDK (arm64), audio-reactive via the Android Visualizer API.
- 120 bundled Milkdrop `.milk` presets, auto-cycling.
- Fullscreen: tap = show/hide controls, double-tap = next preset; real preset-name toast.
- Crash-hardened: C++ exception guards at the JNI boundary, `-fexceptions`, per-GL-context
  projectM re-create (fixed the fullscreen crash), thread-safe action queue (fixed touch crash),
  removed the lock gesture that could freeze the app.

### Likes, UI
- Shared `LikeStore` so song hearts sync across rows, mini-bar, and now-playing.
- Album likes (distinct from song likes).
- Dynamic per-tier metric colors; live animated track count.
- Redesigned floating mini-bar (progress + like-heart + transport).
- Tape mode (cassette view, spinning reels) — placeholder; to be replaced with a HiBy-Tape-derived deck.

### Known / next
- projectM 60fps pass across all presets.
- Scrub bar + app made more dynamic (color/shape/size/interaction).
- Tape mode rebuilt from HiBy Tape source.
- Queue menus, online art/lyrics enrichment, achievements, DAC profiles, MediaSession for widget/lockscreen.
