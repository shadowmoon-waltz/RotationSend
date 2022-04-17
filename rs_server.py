#!/usr/bin/env python3

# Copyright 2022 shadowmoon_waltz
#
# This file is part of RotationSend.
#
# RotationSend is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
#
# RotationSend is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for more details.
#
# You should have received a copy of the GNU General Public License along with Foobar. If not, see <https://www.gnu.org/licenses/>.

HOST = "127.0.0.1"
PORT = 14111

import socket
import os
import struct

# https://bugs.python.org/issue41437
# https://github.com/codypiersall/pynng/issues/49
# https://stackoverflow.com/questions/905189/why-does-sys-exit-not-exit-when-called-inside-a-thread-in-python
import ctypes

# for *nix, you may need a signal handler and a call to os.kill(os.getpid(), signal.SIGINT)
kernel32 = ctypes.WinDLL('kernel32', use_last_error=True)
@ctypes.WINFUNCTYPE(ctypes.c_uint, ctypes.c_uint)
def ctrl_handler(ctrl_event):
    if ctrl_event == 0: # control c event
        os._exit(1)
    return False
kernel32.SetConsoleCtrlHandler(ctrl_handler, True)

sock=socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
sock.bind((HOST, PORT))
print("rs_server started on host {} and port {}".format(HOST, PORT))
while True:
    data, addr = sock.recvfrom(24)
    (when, roll, pitch, azimuth) = struct.unpack("<qxxxxfff", data)
    print(f"received message: when {when}, roll {roll}, pitch {pitch}, azimuth {azimuth}")
