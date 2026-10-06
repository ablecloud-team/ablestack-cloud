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

"""Identify a mounted capacity domain without using Host-local device numbers.

GFS2/local filesystems use their on-disk UUID. NFS uses a durable marker at
the export root, shared by every client of that export (including DNS aliases).
Different NFS exports are separate domains; overlapping exports are unsupported.
"""
import argparse
import fcntl
import json
import os
import subprocess
import uuid
from pathlib import Path


def filesystem_capacity(path, prepare_identity=False):
    path = Path(path).resolve(strict=True)
    output = subprocess.run(["findmnt", "--json", "--target", str(path), "--output", "TARGET,FSTYPE,UUID,FSROOT"],
                            check=True, capture_output=True, text=True, timeout=30)
    mounts = json.loads(output.stdout)["filesystems"]
    if len(mounts) != 1:
        raise RuntimeError("Cannot identify the mounted capacity domain")
    mount = mounts[0]
    if mount.get("uuid"):
        key = "fs:" + str(uuid.UUID(mount["uuid"]))
    elif mount.get("fstype") in ("nfs", "nfs4"):
        # Requiring the export root prevents a bind-mounted child directory from
        # creating a second accounting namespace for the same mounted export.
        if mount.get("fsroot") not in (None, "/"):
            raise RuntimeError("NFS staging/primary must mount the export root")
        directory = Path(mount["target"]) / ".ablestack-capacity"
        if directory.is_symlink():
            raise RuntimeError("Capacity identity directory is a symlink")
        if prepare_identity:
            directory.mkdir(mode=0o700, exist_ok=True)
        identity = directory / "filesystem.id"
        with (directory / "identity.lock").open("a" if prepare_identity else "r") as lock:
            fcntl.flock(lock, fcntl.LOCK_EX)
            if not identity.exists():
                if not prepare_identity:
                    raise RuntimeError("Saved NFS capacity identity is unavailable")
                with identity.open("x") as file:
                    file.write(str(uuid.uuid4()))
                    file.flush()
                    os.fsync(file.fileno())
                fd = os.open(directory, os.O_RDONLY | os.O_DIRECTORY)
                try:
                    os.fsync(fd)
                finally:
                    os.close(fd)
            if identity.is_symlink():
                raise RuntimeError("Capacity identity file is a symlink")
            key = "nfs:" + str(uuid.UUID(identity.read_text().strip()))
    else:
        raise RuntimeError("Mounted filesystem UUID is unavailable; capacity identity is unconfirmed")
    stat = os.statvfs(path)
    return {"storageKey": key, "availableBytes": stat.f_bavail * stat.f_frsize}


def backup_capacity(saved):
    """Requery the exact primary filesystems recorded before backup admission."""
    stage = filesystem_capacity(saved["stageRoot"])
    if stage["storageKey"] != saved["stagingStorageKey"]:
        raise RuntimeError("Backup staging capacity domain changed")
    claims = {}
    for target in saved["targets"]:
        current = filesystem_capacity(target["parent"])
        if current["storageKey"] != target["storageKey"]:
            raise RuntimeError("Backup primary capacity domain changed")
        claim = claims.setdefault(current["storageKey"], dict(current, volumeBytes=0))
        claim["availableBytes"] = min(claim["availableBytes"], current["availableBytes"])
        claim["volumeBytes"] += target["volumeBytes"]
    for claim in claims.values():
        size = claim["volumeBytes"]
        claim["requiredBytes"] = size + max(10 * 1024**3, size // 5)
    return {"version": 1, "jobId": saved["jobId"], "stagingStorageKey": saved["stagingStorageKey"],
            "primaryClaims": list(claims.values())}


def prepare_backup_capacity(job_id, stage_root, paths, sizes):
    if not paths or len(paths) != len(sizes) or any(size <= 0 for size in sizes):
        raise RuntimeError("Backup primary capacity sources differ from selected volumes")
    stage = filesystem_capacity(stage_root, True)
    saved = {"jobId": job_id, "stageRoot": str(stage_root), "stagingStorageKey": stage["storageKey"], "targets": []}
    for path, size in zip(paths, sizes):
        parent = Path(path).resolve(strict=True).parent
        current = filesystem_capacity(parent, True)
        saved["targets"].append({"parent": str(parent), "storageKey": current["storageKey"], "volumeBytes": size})
    return saved


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    selection = parser.add_mutually_exclusive_group(required=True)
    selection.add_argument("--path")
    selection.add_argument("--backup-plan-file")
    parser.add_argument("--prepare-identity", action="store_true")
    args = parser.parse_args()
    print(json.dumps(backup_capacity(json.loads(Path(args.backup_plan_file).read_text()))
                     if args.backup_plan_file else filesystem_capacity(args.path, args.prepare_identity)))
