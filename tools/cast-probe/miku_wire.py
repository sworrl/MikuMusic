"""MikuCast wire format, shared by the probe and the control sender.

Mirrors `MikuCastProtocol.kt`. It lives in its own module because the two
scripts previously each defined MAGIC and the frame-type numbers for
themselves, which meant a change to the Kotlin side had to be mirrored by hand
in two places and would silently send or parse the wrong frame type in whichever
one got missed.
"""
import struct

MAGIC = b"MIKU"
HEADER_SIZE = 9

FORMAT = 1
PCM = 2
META = 3
CONTROL = 4
PING = 5
SKIP = 6

NAMES = {FORMAT: "FORMAT", PCM: "PCM", META: "META",
         CONTROL: "CONTROL", PING: "PING", SKIP: "SKIP"}


def frame(frame_type: int, payload: bytes = b"") -> bytes:
    """One complete frame: magic, type, big-endian length, payload."""
    return MAGIC + bytes([frame_type]) + struct.pack(">I", len(payload)) + payload


def parse_header(hdr: bytes):
    """(frame_type, payload_length) from a 9-byte header. Raises on bad magic."""
    if len(hdr) != HEADER_SIZE:
        raise ValueError(f"header must be {HEADER_SIZE} bytes, got {len(hdr)}")
    if hdr[:4] != MAGIC:
        raise ValueError(f"bad magic {hdr[:4]!r}, expected {MAGIC!r}")
    return hdr[4], struct.unpack(">I", hdr[5:9])[0]
