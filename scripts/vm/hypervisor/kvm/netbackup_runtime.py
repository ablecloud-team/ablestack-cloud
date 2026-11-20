#!/usr/bin/env python3
# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements. See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership. The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License. You may obtain a copy of the License at
#
#   http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied. See the License for the
# specific language governing permissions and limitations
# under the License.

"""Refresh generated NetBackup executables without reconfiguring credentials or policies."""
import argparse
import fcntl
import os
from pathlib import Path
import shlex
import stat
import subprocess
import tempfile

SOURCE = Path(__file__).resolve().parent
CONFIG = Path(os.environ.get("NETBACKUP_WATCHER_CONFIG", os.environ.get("WATCHER_CONFIG_PATH", "/etc/ablestack/netbackup/restore-watcher.conf")))
SERVICE = Path(os.environ.get("WATCHER_SERVICE_PATH", "/etc/systemd/system/netbackup-host-restore-watcher.service"))
WATCHER = Path(os.environ.get("WATCHER_OUTPUT_PATH", "/usr/local/sbin/netbackup-host-restore-watcher"))
NOTIFY = Path(os.environ.get("RESTORE_NOTIFY_OUTPUT_PATH", "/usr/local/sbin/netbackup-host-restore-notify"))


def atomic(path, data, mode):
    if path.is_symlink() or (path.exists() and not path.is_file()):
        raise RuntimeError("Refusing redirected runtime file: " + str(path))
    if path.exists() and path.read_bytes() == data and stat.S_IMODE(path.stat().st_mode) == mode:
        return False
    path.parent.mkdir(parents=True, exist_ok=True)
    fd, temporary = tempfile.mkstemp(prefix=".netbackup-runtime-", dir=path.parent)
    try:
        with os.fdopen(fd, "wb") as output:
            os.fchmod(output.fileno(), mode)
            output.write(data)
            output.flush()
            os.fsync(output.fileno())
        os.replace(temporary, path)
        directory = os.open(path.parent, os.O_RDONLY | os.O_DIRECTORY)
        try:
            os.fsync(directory)
        finally:
            os.close(directory)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)
    return True


def refresh():
    if not CONFIG.is_file():
        return False  # A package install must not configure or enable a new integration.
    lock_path = Path("/var/lib/ablestack/netbackup/restore-watcher.lock")
    for line in CONFIG.read_text().splitlines():
        key, separator, value = line.partition("=")
        if separator and key.strip() == "LOCK_FILE":
            values = shlex.split(value.strip())
            if len(values) != 1 or not Path(values[0]).is_absolute():
                raise RuntimeError("Unconfirmed NetBackup watcher lock path")
            lock_path = Path(values[0])
    files = [(SOURCE / "netbackup-host-restore-watcher.py", WATCHER, 0o755),
             (SOURCE / "netbackup-host-restore-notify", NOTIFY, 0o755),
             (SOURCE / "thirdparty_restore_metadata.py", WATCHER.parent / "thirdparty_restore_metadata.py", 0o644)]
    prepared = []
    for source, target, mode in files:
        if source.is_symlink() or not source.is_file():
            raise RuntimeError("Packaged NetBackup runtime is incomplete: " + str(source))
        data = source.read_bytes()
        if source.suffix == ".py":
            compile(data, str(source), "exec")
        if target.is_symlink() or (target.exists() and not target.is_file()):
            raise RuntimeError("Refusing redirected runtime file: " + str(target))
        prepared.append((target, data, mode))
    lock_path.parent.mkdir(parents=True, mode=0o700, exist_ok=True)
    with lock_path.open("a") as lock:
        # The timer retries after a busy watcher finishes; never terminate a callback.
        fcntl.flock(lock.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
        changed = False
        for target, data, mode in prepared:
            changed = atomic(target, data, mode) or changed
        return changed


def install():
    if not CONFIG.is_file() or not SERVICE.is_file():
        return False
    command = "/usr/bin/python3 " + shlex.quote(str(SOURCE / "netbackup_runtime.py")) + " --refresh"
    # Existing configured hosts receive an upgrade hook; newly generated units already contain it.
    if "ExecStartPre=" + command not in SERVICE.read_text():
        dropin = SERVICE.with_name(SERVICE.name + ".d") / "20-mold-runtime-refresh.conf"
        if atomic(dropin, ("[Service]\nExecStartPre=" + command + "\n").encode(), 0o644):
            subprocess.run(["systemctl", "daemon-reload"], check=True, timeout=30)
    try:
        return refresh()
    except BlockingIOError:
        print("NetBackup runtime refresh deferred until the next watcher timer execution")
        return False


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    group = parser.add_mutually_exclusive_group(required=True)
    group.add_argument("--install", action="store_true")
    group.add_argument("--refresh", action="store_true")
    args = parser.parse_args()
    if args.install:
        install()
    else:
        refresh()


if __name__ == "__main__":
    main()
