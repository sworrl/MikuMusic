# cast-probe — a stand-in for the TV

Speaks the MikuCast wire format (see
`miku-player-kotlin/app/src/main/java/com/miku/player/cast/MikuCastProtocol.kt`)
so the cast path can be verified without a television. Python standard library
only.

It exists because "the TV app compiles" is not evidence that a bit-perfect
audio path works, and because the TV was unreachable: installing the companion
needs someone to accept an adb prompt on the set. Running these found a bug
that would have shipped — every metadata frame was being thrown away by a
`NetworkOnMainThreadException` swallowed inside a bare `runCatching`, so a
connected TV would have played perfect audio under a permanently blank title.

## Reaching the device

Over USB, which also sidesteps Wi-Fi power save (the DAP was not pingable on
its own LAN address while the socket was open):

```
adb -d forward tcp:18796 tcp:8796
```

The cast server lives in `PlaybackService`, which is only started when playback
begins — so **start a track on the M500 first**, or nothing will be listening.
Note that `monkey -c LAUNCHER` picks `MikuFMRadioActivity`, not the player;
use `am start -n com.miku.player/.MainActivity`.

## Capture and verify the stream

```
python3 cast_probe.py HOST [SECONDS] [OUT.pcm] [PORT]
python3 cast_probe.py 127.0.0.1 15 ./cast.pcm 18796
```

Prints the FORMAT frame, counts frames by type, and reports received bytes
against what the declared format implies. A **ratio of 1.000 means real time
with no loss**; below that means dropped audio, and `SKIP` frames mean the
reader fell behind. Writes the raw PCM and a WAV.

To prove bit-perfection, decode the source independently and look for the
capture inside it:

```
ffmpeg -v error -i track.flac -f s16le -acodec pcm_s16le -ar 44100 -ac 2 track.raw -y
python3 - <<'PY'
ref = open('track.raw','rb').read(); cap = open('cast.pcm','rb').read()
probe = cap[len(cap)//3: len(cap)//3 + 65536]
i = ref.find(probe); off = i - len(cap)//3
print('offset', off, 'identical:', ref[off:off+len(cap)] == cap)
PY
```

Measured on 2026-10-08: 2,654,976 bytes over 15.0 s, ratio 1.000, and every
byte identical to the ffmpeg decode starting 12.90 s into the track.

## Exercise the control channel

```
python3 cast_control.py HOST PORT COMMAND [POSITION_MS]
python3 cast_control.py 127.0.0.1 18796 playpause
python3 cast_control.py 127.0.0.1 18796 seek 45000
```

Confirm the player obeyed:

```
adb -d shell "dumpsys media_session | grep -m1 -oE 'state=(PLAYING|PAUSED)\([0-9]\)'"
```

Commands the bridge accepts: `playpause`, `play`, `pause`, `next`, `prev`,
`seek`. `seek` requires a position in milliseconds and the script refuses
without one, because a seek frame missing `positionMs` asks the player to jump
to zero and tests nothing.

`miku_wire.py` holds the magic, the frame-type numbers and the header
pack/parse, mirroring `MikuCastProtocol.kt`. Both scripts import it, so a change
to the wire format is one edit rather than two that can drift apart. They add
their own directory to `sys.path` explicitly, since `python3 -I` does not.

## Watching the device side

Filter by tag; the main log buffer rolls over quickly under backup traffic and
a plain `logcat -d | grep` will miss the lines entirely.

```
adb -d logcat -s MikuCastBridge:V MikuCastServer:V MikuCastTap:V PlaybackService:V
```

`MikuCastTap: tap enabled, local output muted` on connect and
`tap disabled, local output restored` on disconnect is the gain mute working.
