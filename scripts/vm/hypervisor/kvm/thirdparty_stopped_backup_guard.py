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

"""Stopped source ownership shared with the KVM Agent's VM start fence."""
import contextlib
import fcntl
import json
import os
from pathlib import Path
import re

ROOT = Path("/var/lib/ablestack/backup/backup-start/source-guards")


def identity(source):
    plan = source.get("backupStartPlan")
    if not plan or not plan.get("protectStoppedSource"):
        return None
    vm = source.get("vmName")
    if plan.get("nasSource") is not None:
        nas = plan["nasSource"]
        if (plan.get("version") != 1 or plan.get("manifest") is not None
                or vm != nas.get("vmName") or not vm or not re.fullmatch(r"[A-Za-z0-9_-][A-Za-z0-9_.-]*", vm)
                or not re.fullmatch(r"[A-Za-z0-9-]+", plan.get("jobId", ""))
                or plan.get("backupPath") != source.get("backupPath")
                or source.get("checkpointName") != nas.get("checkpointName")
                or source.get("backupType") != nas.get("backupType") or nas.get("backupType") not in ("FULL", "INCREMENTAL")
                or source.get("rbd") != (nas.get("engine") == "RBD_DIFF")
                or nas.get("engine") not in ("QCOW2", "RBD_DIFF")
                or not source.get("diskPaths") or len(source["diskPaths"]) != nas.get("volumeCount")
                or not re.fullmatch(r"[a-f0-9]{64}", nas.get("requestDigest", ""))):
            raise RuntimeError("NAS source protection differs from the backup plan")
        return plan
    if (not vm or not re.fullmatch(r"[A-Za-z0-9_.-]+", vm) or vm in (".", "..")
            or plan.get("manifest") != source.get("manifest")
            or vm != plan["manifest"].get("vmName")
            or not re.fullmatch(r"[A-Za-z0-9-]+", plan.get("jobId", ""))
            or plan["jobId"] != plan["manifest"].get("backupUuid")):
        raise RuntimeError("Stopped source protection differs from the backup plan")
    return plan


@contextlib.contextmanager
def locked(plan):
    directory = ROOT / (plan["nasSource"]["vmName"] if plan.get("nasSource") else plan["manifest"]["vmName"])
    # Only Agent preparation creates ownership. A missing marker must fail closed.
    with (directory / "start.lock").open("a") as lock:
        fcntl.lockf(lock, fcntl.LOCK_EX)
        yield directory / (plan["jobId"] + ".json")


def read(file, plan):
    proof = json.loads(file.read_text())
    if proof.get("version") != 1 or proof.get("plan") != plan:
        raise RuntimeError("Stopped source ownership is unconfirmed")
    rbd = (plan["nasSource"]["engine"] == "RBD_DIFF" if plan.get("nasSource")
           else all(volume["engine"] == "RBD_DIFF" for volume in plan["manifest"]["volumes"]))
    released = proof.get("released") is True
    if ((released and proof.get("phase") not in ({"SOURCE_CLEANED", "UNSTARTED_FENCED", "SNAPSHOTS_COMPLETE"}
                                                if rbd else {"SOURCE_CLEANED", "UNSTARTED_FENCED"}))
            or (not released and proof.get("phase") != "PROTECTED")):
        raise RuntimeError("Stopped source release phase is unconfirmed")
    return proof


def release_locked(file, plan, phase):
    proof = read(file, plan)
    if proof.get("released"):
        return
    proof.update(released=True, phase=phase)
    temporary = file.with_name(file.name + ".tmp")
    with temporary.open("w") as stream:
        json.dump(proof, stream)
        stream.flush()
        os.fsync(stream.fileno())
    os.replace(temporary, file)
    fd = os.open(file.parent, os.O_RDONLY)
    try:
        os.fsync(fd)
    finally:
        os.close(fd)


@contextlib.contextmanager
def prepare(source, is_stopped):
    plan = identity(source)
    if plan is None:
        yield
        return
    with locked(plan) as file:
        proof = read(file, plan)
        if proof.get("released") or not is_stopped():
            raise RuntimeError("Stopped VM backup source is active or its protection was already released")
        yield
        if source["rbd"]:
            # The caller completes every volume's snapshot before leaving this scope.
            release_locked(file, plan, "SNAPSHOTS_COMPLETE")


def cleaned(source):
    plan = identity(source)
    if plan is not None:
        with locked(plan) as file:
            release_locked(file, plan, "SOURCE_CLEANED")
