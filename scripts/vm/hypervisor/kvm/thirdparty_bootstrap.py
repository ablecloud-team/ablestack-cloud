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

"""Publish and retire only the small, owned payload of an external parent backup."""

import argparse
import json
import os
from pathlib import Path
import re
import stat
import tempfile


def read_regular(path):
    with os.fdopen(os.open(path, os.O_RDONLY | os.O_NOFOLLOW), "r") as stream:
        info = os.fstat(stream.fileno())
        if not stat.S_ISREG(info.st_mode) or not 0 < info.st_size <= 4096:
            raise ValueError("Bootstrap must be a small regular file: " + str(path))
        return stream.read(4097)


def payload_root(value):
    root = Path(value)
    if not root.is_absolute() or root == Path("/") or root.resolve() != root:
        raise ValueError("Bootstrap directory must be absolute and cannot redirect through symlinks")
    return root


def backup_token(value):
    if not value or not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_.-]{0,127}", value):
        raise ValueError("Invalid bootstrap backup identifier")
    return value


def publish(root, source, expected=None):
    root = payload_root(root)
    source = Path(source)
    if source.name != ".volume-bootstrap" or source.resolve() != source:
        raise ValueError("Source must be the original volume bootstrap marker")
    backup = backup_token(read_regular(source).strip())
    if expected is not None and backup != expected:
        raise ValueError("Bootstrap belongs to a different backup")
    value = {"version": 1, "backupUuid": backup, "sourceMarker": str(source)}
    root.mkdir(parents=True, exist_ok=True)
    target = root / (backup + ".json")
    if target.exists() or target.is_symlink():
        if json.loads(read_regular(target)) != value:
            raise ValueError("Parent payload already belongs to another backup")
        return str(target)
    descriptor, temporary = tempfile.mkstemp(prefix=".bootstrap-", dir=str(root))
    try:
        with os.fdopen(descriptor, "w") as stream:
            json.dump(value, stream, sort_keys=True)
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, target)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)
    return str(target)


def retire(root, backup):
    root = payload_root(root)
    target = root / (backup_token(backup) + ".json")
    if not target.exists() and not target.is_symlink():
        return
    value = json.loads(read_regular(target))
    if value.get("version") != 1 or value.get("backupUuid") != backup or not value.get("sourceMarker"):
        raise ValueError("Parent payload ownership is unconfirmed")
    target.unlink()
    # The selected directory remains available to the next parent Job.


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=("publish", "retire"))
    parser.add_argument("--payload-root", required=True)
    parser.add_argument("--source-marker")
    parser.add_argument("--backup-uuid")
    args = parser.parse_args()
    if args.action == "publish":
        if not args.source_marker:
            parser.error("publish requires --source-marker")
        print(publish(args.payload_root, args.source_marker, args.backup_uuid))
    else:
        if not args.backup_uuid:
            parser.error("retire requires --backup-uuid")
        retire(args.payload_root, args.backup_uuid)


if __name__ == "__main__":
    main()
