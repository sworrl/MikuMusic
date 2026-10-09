#!/usr/bin/env python3
"""Exercise the TV -> M500 control channel and confirm the player obeys.

Usage: cast_control.py HOST PORT COMMAND [POSITION_MS]

COMMAND is one of playpause, play, pause, next, prev, seek. POSITION_MS is
required for seek and ignored otherwise: the bridge reads positionMs off the
frame, and a seek sent without it asks the player to jump to 0 rather than
testing anything useful.
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

import miku_wire as w

if len(sys.argv) < 4:
    sys.exit(__doc__)

HOST = sys.argv[1]
PORT = int(sys.argv[2])
CMD = sys.argv[3]

cmd = {"cmd": CMD}
if CMD == "seek":
    if len(sys.argv) < 5:
        sys.exit("seek needs a position: cast_control.py HOST PORT seek POSITION_MS")
    cmd["positionMs"] = int(sys.argv[4])
elif len(sys.argv) >= 5:
    print(f"note: '{CMD}' takes no position argument; ignoring {sys.argv[4]!r}")

s = socket.create_connection((HOST, PORT), timeout=10)
s.settimeout(3)
time.sleep(1.5)                      # let FORMAT/PCM start so we are a real client

body = json.dumps(cmd).encode()
s.sendall(w.frame(w.CONTROL, body))
print(f"sent CONTROL {cmd} ({len(body)} byte payload)")

# Drain briefly so the socket is not closed under the player's reader.
end = time.time() + 4
try:
    while time.time() < end:
        if not s.recv(65536):
            break
except socket.timeout:
    pass
finally:
    s.close()
