#!/usr/bin/env python3
# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements. See the NOTICE file distributed with
# this work for additional information regarding copyright ownership.
# The ASF licenses this file to You under the Apache License, Version 2.0
# (the "License"); you may not use this file except in compliance with
# the License. You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
"""Restore only a validated, persisted SharedFS STATIC network during bootstrap."""
import ipaddress
import json
import os
from pathlib import Path
import re
import stat
import subprocess
import sys

STATE = Path("/etc/ablestack-storage/sharedfs-network.json")
LINKS = Path("/sys/class/net")
HELPER = Path("/usr/local/sbin/ablestack-sharedfs-network")

def load_static_state(path=STATE, links=LINKS):
    if not path.exists() and not path.is_symlink():
        return None
    fd = os.open(path, os.O_RDONLY | os.O_NOFOLLOW)
    with os.fdopen(fd) as source:
        info = os.fstat(source.fileno())
        if not stat.S_ISREG(info.st_mode) or info.st_uid != 0 or info.st_mode & 0o077 or info.st_size > 8192:
            raise ValueError("Unsafe SharedFS STATIC state")
        state = json.load(source)
    if not isinstance(state, dict):
        raise ValueError("Invalid SharedFS STATIC state")
    network = ipaddress.ip_network(state["cidr"], strict=False)
    address = ipaddress.ip_address(state["ipAddress"])
    if network.version != 4 or address.version != 4 or address not in network or address in (network.network_address, network.broadcast_address):
        raise ValueError("Invalid SharedFS STATIC address")
    if state.get("gateway"):
        gateway = ipaddress.ip_address(state["gateway"])
        if gateway.version != 4 or gateway not in network:
            raise ValueError("Invalid SharedFS STATIC gateway")
    for key in ("dns1", "dns2"):
        if state.get(key) and ipaddress.ip_address(state[key]).version != 4:
            raise ValueError("Invalid SharedFS STATIC DNS")
    mac = str(state["macAddress"]).lower()
    if not re.fullmatch(r"(?:[0-9a-f]{2}:){5}[0-9a-f]{2}", mac):
        raise ValueError("Invalid SharedFS STATIC MAC")
    matched = [p for p in links.iterdir() if p.name != "lo" and (p / "address").read_text().strip().lower() == mac]
    if len(matched) != 1:
        raise ValueError("SharedFS STATIC state does not identify exactly one NIC")
    return state

def restore(path=STATE, links=LINKS, helper=HELPER):
    state = load_static_state(path, links)
    if state is None:
        return "DHCP"
    info = helper.lstat()
    if not stat.S_ISREG(info.st_mode) or info.st_uid != 0 or info.st_mode & 0o022:
        raise ValueError("Unsafe SharedFS STATIC helper")
    # Reuse the existing formatter-independent network implementation.
    result = subprocess.run([str(helper), str(path)], capture_output=True, text=True, timeout=20)
    if result.returncode:
        raise RuntimeError("SharedFS STATIC restore failed: " + result.stderr[-1024:])
    return "STATIC"

if __name__ == "__main__":
    try:
        print(restore())
    except (OSError, ValueError, KeyError, RuntimeError, subprocess.TimeoutExpired) as error:
        print(str(error), file=sys.stderr)
        sys.exit(1)
