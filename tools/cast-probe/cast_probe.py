#!/usr/bin/env python3
"""Stand-in for the TV. Verifies the MikuCast wire format and captures PCM.

Exists because the real client cannot be installed until someone accepts the adb
prompt on the television, and "it compiles" is not evidence that a bit-perfect
audio path works. This speaks the protocol from MikuCastProtocol.kt directly and
writes the received PCM to a WAV so the bytes can be compared against the source
file.

Usage: cast_probe.py HOST [SECONDS] [OUT.pcm] [PORT]
"""
import os
import sys

# Resolve miku_wire from this script's own directory. Python normally prepends
# that directory to sys.path, but not under -I / -P, and these were being run
# with -I. Without this the import fails with ModuleNotFoundError on a command
# line that otherwise looks correct.
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import json
import socket
import time
import wave

import miku_wire as w

if len(sys.argv) < 2:
    sys.exit(__doc__)

HOST = sys.argv[1]
SECONDS = float(sys.argv[2]) if len(sys.argv) > 2 else 12.0
OUT = sys.argv[3] if len(sys.argv) > 3 else "cast.pcm"
PORT = int(sys.argv[4]) if len(sys.argv) > 4 else 8796

# Derive the WAV path by stripping whatever extension OUT has, rather than
# replacing a literal ".pcm". The old form was a no-op on any other extension,
# so the WAV writer reopened and truncated the raw capture that had just been
# written to the same path, destroying it while reporting both files as written.
WAV_OUT = os.path.splitext(OUT)[0] + ".wav"
if os.path.abspath(WAV_OUT) == os.path.abspath(OUT):
    WAV_OUT = OUT + ".wav"

s = socket.create_connection((HOST, PORT), timeout=10)
s.settimeout(5)
print(f"connected to {HOST}:{PORT}")

buf = b""


def need(n):
    global buf
    while len(buf) < n:
        chunk = s.recv(65536)
        if not chunk:
            raise EOFError("peer closed")
        buf += chunk
    out, buf = buf[:n], buf[n:]
    return out


def as_json(payload, what):
    """Decode a text frame without letting a bad one end the capture.

    A truncated, empty or non-UTF-8 FORMAT/META payload used to raise
    JSONDecodeError or UnicodeDecodeError straight out of the read loop, past an
    except clause that only caught EOFError and socket.timeout. That aborted the
    script before the reporting block, so the frame counts were never printed and
    every captured PCM byte was discarded unwritten. A malformed frame is a
    finding about the server, which is the entire point of this tool, so it gets
    reported and the capture continues.
    """
    try:
        return json.loads(payload.decode())
    except (UnicodeDecodeError, json.JSONDecodeError) as e:
        print(f"!! malformed {what} payload ({len(payload)} bytes): {type(e).__name__}: {e}")
        print(f"   raw: {payload[:120]!r}")
        return None


counts, fmt, pcm_bytes, malformed = {}, None, bytearray(), 0
t0 = time.time()
try:
    while time.time() - t0 < SECONDS:
        typ, length = w.parse_header(need(w.HEADER_SIZE))
        payload = need(length) if length else b""
        counts[typ] = counts.get(typ, 0) + 1
        if typ == w.FORMAT:
            got = as_json(payload, "FORMAT")
            if got is None:
                malformed += 1
            else:
                fmt = got
                print("FORMAT:", fmt)
        elif typ == w.META:
            got = as_json(payload, "META")
            if got is None:
                malformed += 1
            else:
                print("META:", got)
        elif typ == w.SKIP:
            print("SKIP (TV fell behind):", payload.decode(errors="replace"))
        elif typ == w.PCM:
            pcm_bytes += payload
except (EOFError, socket.timeout) as e:
    print("stream ended:", type(e).__name__, e)
except ValueError as e:
    # Bad magic: the stream is desynced and nothing after this is trustworthy,
    # but what was already captured still is, so fall through and report it.
    print("!! framing lost:", e)
finally:
    s.close()

elapsed = time.time() - t0
print("\n--- frames ---")
for t, n in sorted(counts.items()):
    print(f"  {w.NAMES.get(t, t):8s} {n}")
if malformed:
    print(f"  malformed text frames: {malformed}")
print(f"PCM bytes: {len(pcm_bytes)} in {elapsed:.1f}s = {len(pcm_bytes)/elapsed/1e6:.2f} MB/s")

if fmt and pcm_bytes:
    rate = fmt.get("sampleRate", 0)
    bps = fmt.get("bytesPerSample", 0)
    ch = fmt.get("channelCount", 0)
    expect = rate * bps * ch
    print(f"declared {rate} Hz x {bps*8}-bit x {ch}ch = {expect/1e6:.3f} MB/s expected")
    if expect:
        print(f"ratio received/expected = {(len(pcm_bytes)/elapsed)/expect:.3f}  (1.0 = real time, no loss)")
    with open(OUT, "wb") as f:
        f.write(bytes(pcm_bytes))
    print("raw PCM ->", OUT)
    if bps in (2, 3, 4):
        with wave.open(WAV_OUT, "wb") as wv:
            wv.setnchannels(ch)
            wv.setsampwidth(bps)
            wv.setframerate(rate)
            wv.writeframes(bytes(pcm_bytes))
        print("wav ->", WAV_OUT)
elif pcm_bytes:
    # No usable FORMAT frame, so the WAV header cannot be written honestly.
    # Keep the raw bytes regardless: they are the capture.
    with open(OUT, "wb") as f:
        f.write(bytes(pcm_bytes))
    print("raw PCM ->", OUT, "(no usable FORMAT frame, so no WAV)")
