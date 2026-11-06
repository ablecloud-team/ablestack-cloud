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

"""An additive, recognizable NetBackup UI restore entry; never contains disk data."""
import json
import os
from pathlib import Path
import stat

NETBACKUP_ENTRY = "MOLD-RESTORE.json"
REQUIRED_METADATA = ("backup-manifest.json", "domain-config.xml", ".staging.complete")
MAX_METADATA_BYTES = 16 * 1024 * 1024


def read_metadata_file(path):
    fd = os.open(path, os.O_RDONLY | os.O_NOFOLLOW)
    try:
        before = os.fstat(fd)
        if (not stat.S_ISREG(before.st_mode) or before.st_nlink != 1
                or before.st_size <= 0 or before.st_size > MAX_METADATA_BYTES):
            raise ValueError("Restore metadata must be a bounded, individually owned regular file: " + str(path))
        with os.fdopen(fd, "r", encoding="utf-8", closefd=False) as stream:
            text = stream.read(MAX_METADATA_BYTES + 1)
        after = os.fstat(fd)
        if file_identity(before) != file_identity(after) or len(text.encode("utf-8")) > MAX_METADATA_BYTES:
            raise ValueError("Restore metadata changed while being read: " + str(path))
        return text
    finally:
        os.close(fd)


def file_identity(info):
    return [info.st_dev, info.st_ino, info.st_size, info.st_mtime_ns, info.st_ctime_ns]


def parse_netbackup_entry(text):
    entry = json.loads(text)
    if (not isinstance(entry, dict) or entry.get("version") != 1
            or entry.get("kind") != "MOLD_VM_RESTORE" or entry.get("provider") != "ablestack-netbackup"
            or not isinstance(entry.get("files"), dict) or set(entry["files"]) != set(REQUIRED_METADATA)
            or any(not isinstance(value, str) or not value for value in entry["files"].values())):
        raise ValueError("Invalid MOLD-RESTORE.json metadata entry")
    return entry["files"]


def write_netbackup_entry(root, writer):
    root = Path(root)
    files = {name: read_metadata_file(root / name) for name in REQUIRED_METADATA}
    manifest = json.loads(files["backup-manifest.json"])
    if manifest.get("provider") != "ablestack-netbackup" or manifest.get("complete") is not True:
        raise ValueError("NetBackup restore entry requires a completed volume manifest")
    entry = {"version": 1, "kind": "MOLD_VM_RESTORE", "provider": "ablestack-netbackup", "files": files}
    if len(json.dumps(entry).encode("utf-8")) > MAX_METADATA_BYTES:
        raise ValueError("NetBackup restore entry exceeds the metadata size limit")
    writer(root / NETBACKUP_ENTRY, entry)
