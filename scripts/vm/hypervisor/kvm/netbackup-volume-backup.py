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

"""Submit an exact staged artifact through a Host policy's User Backup schedule.

An intent is durable before bpbackup runs. Recovery never invokes bpbackup again:
Mold resolves the exact Job ID from its policy, Client, schedule and file list,
then confirms that job's exact Catalog image before acknowledging the artifact.
"""
import argparse
import fcntl
import hashlib
import json
import os
from pathlib import Path
import subprocess
import uuid

from thirdparty_volume_backup import atomic

BPBACKUP = Path("/usr/openv/netbackup/bin/bpbackup")
CONTROL_ROOT = Path("/usr/openv/netbackup/logs/user_ops/ablestack")
TRANSFER_SCHEDULE = "mold-transfer"


def request_id(plan):
    artifact = plan["artifact"]
    # Java UUID.nameUUIDFromBytes uses an MD5 digest with UUID version 3 bits.
    path_id = uuid.UUID(bytes=hashlib.md5(artifact["path"].encode("utf-8")).digest(), version=3)
    return "userbackup:%s:%s:%s" % (artifact["backupUuid"], path_id, plan["afterJobId"])


def validate(plan, directory):
    artifact = plan["artifact"]
    if str(uuid.UUID(artifact["backupUuid"])) != directory.name:
        raise RuntimeError("NetBackup request belongs to another source job")
    if plan["schedule"] != TRANSFER_SCHEDULE or not isinstance(plan["metadata"], bool):
        raise RuntimeError("Invalid NetBackup transfer schedule or artifact type")
    if not isinstance(plan.get("afterJobId"), str) or not plan["afterJobId"].isascii() or not plan["afterJobId"].isdigit():
        raise RuntimeError("Invalid NetBackup submission job boundary")
    for value in (artifact["path"], plan["policy"], plan["server"]):
        if not isinstance(value, str) or not value or any(c in value for c in "\n\r\0") or value.startswith("-"):
            raise RuntimeError("Invalid NetBackup backup selection")
    if not Path(artifact["path"]).is_absolute() or artifact["size"] < 0:
        raise RuntimeError("Invalid NetBackup artifact path or size")


def submit(file, plan, recover_only=False):
    validate(plan, file.parent)
    receipt = file.with_suffix(".receipt.json")
    intent = file.with_suffix(".intent.json")
    if receipt.exists() or intent.exists():
        saved = json.loads((receipt if receipt.exists() else intent).read_text())
        if saved["plan"] != plan or saved["requestId"] != request_id(plan):
            raise RuntimeError("NetBackup backup receipt belongs to another artifact")
        # An intent can hide an accepted request even if bpbackup timed out or the Agent restarted.
        # This marker is an attempt reference, never proof of a completed or accepted backup.
        return saved["requestId"]
    if recover_only:
        raise RuntimeError("NetBackup submission receipt is unavailable; the request will not be resubmitted")
    artifact = plan["artifact"]
    current = json.loads((file.parent / "volume-request.json").read_text())
    source = json.loads((file.parent / "volume-plan.json").read_text())
    requested = current.get("artifact", {})
    if (current.get("backupUuid") != artifact["backupUuid"] or current.get("metadata") != plan["metadata"]
            or any(requested.get(key) != artifact[key] for key in ("backupUuid", "path"))
            or requested.get("size", 0) != artifact["size"]
            or source["manifest"]["backupUuid"] != artifact["backupUuid"]):
        raise RuntimeError("Staged artifact differs from the pending Host request")
    root = Path(source["backupPath"]).resolve(strict=True)
    selection = Path(artifact["path"])
    if selection.is_symlink() or selection.resolve(strict=True) != selection:
        raise RuntimeError("NetBackup requires a real staged path without symlinks")
    if plan["metadata"]:
        if selection != root or not (root / "backup-manifest.json").is_file() or not (root / ".staging.complete").is_file():
            raise RuntimeError("NetBackup metadata is not ready")
    elif selection.parent != root or not selection.is_file() or selection.stat().st_size != artifact["size"]:
        raise RuntimeError("NetBackup staged volume is not ready or its size changed")
    if any((file.parent / name).exists() for name in ("volume-cancel", "staging-admission-cancel", "staging-admission-closed")):
        raise RuntimeError("NetBackup source pipeline is canceled or closed")
    if not BPBACKUP.is_file() or not os.access(BPBACKUP, os.X_OK):
        raise RuntimeError("NetBackup bpbackup is not installed or executable on the KVM Host")
    control = CONTROL_ROOT / artifact["backupUuid"]
    control.mkdir(parents=True, mode=0o700, exist_ok=True)
    selections = control / (file.stem + ".selections")
    progress = control / (file.stem + ".progress.log")
    # NetBackup's length-prefixed list format also supports paths containing spaces.
    selection_line = artifact["path"]
    if any(character.isspace() for character in selection_line):
        selection_line = "%d %s" % (len(selection_line), selection_line)
    atomic(selections, selection_line + "\n")
    os.chmod(selections, 0o600)
    progress.touch(mode=0o600)
    saved = {"plan": plan, "requestId": request_id(plan)}
    atomic(intent, saved)
    with file.with_suffix(".log").open("a") as output:
        os.chmod(output.name, 0o600)
        try:
            result = subprocess.run([str(BPBACKUP), "-p", plan["policy"], "-s", plan["schedule"],
                                     "-S", plan["server"], "-t", "0", "-f", str(selections),
                                     "-k", saved["requestId"], "-L", str(progress), "-en"],
                                    stdout=output, stderr=subprocess.STDOUT, timeout=110,
                                    env=dict(os.environ, LC_ALL="C", TZ="UTC"), check=False)
            # Even exit 0 proves submission only. The controller verifies the external job and image.
            saved["commandExit"] = result.returncode
            atomic(receipt, saved)
        except (subprocess.TimeoutExpired, OSError):
            # Preserve the intent and reconcile the exact job; never infer rejection from an ambiguous exit.
            pass
        output.flush()
        os.fsync(output.fileno())
    return saved["requestId"]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--plan-file", required=True)
    parser.add_argument("--recover-only", action="store_true")
    args = parser.parse_args()
    file = Path(args.plan_file)
    plan = json.loads(file.read_text())
    with file.with_suffix(".lock").open("a") as lock:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        rejected = file.with_suffix(".rejected.json")
        if rejected.exists():
            saved = json.loads(rejected.read_text())
            if saved.get("plan") != plan or saved.get("notSubmitted") is not True:
                raise RuntimeError("NetBackup non-submission receipt belongs to another artifact")
            print("ABLESTACK_NOT_SUBMITTED=" + json.dumps(saved))
            return
        try:
            print("ABLESTACK_REQUEST_ID=" + submit(file, plan, args.recover_only))
        except Exception as error:
            if not args.recover_only and not file.with_suffix(".intent.json").exists():
                saved = {"plan": plan, "notSubmitted": True, "failure": str(error)}
                atomic(rejected, saved)
                print("ABLESTACK_NOT_SUBMITTED=" + json.dumps(saved))
                return
            raise


if __name__ == "__main__":
    main()
