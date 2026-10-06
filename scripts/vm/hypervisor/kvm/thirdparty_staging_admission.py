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

"""Host capacity admission. The controller holds its DB scheduling lock while admitting.

A lost Agent response is recovered using the same job ID. Cancellation and admission
use the same filesystem lock, so a canceled waiter cannot subsequently acquire space.
"""
import argparse
import fcntl
import json
import os
import uuid
from pathlib import Path

from thirdparty_volume_backup import atomic, staging_identity


def control(plan_file, action, reason="", token=""):
    job = Path(plan_file).parent
    plan = json.loads(Path(plan_file).read_text())
    job_id = plan["jobId"]
    if job.name != job_id or not job_id or any(c not in "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789_.-" for c in job_id):
        raise RuntimeError("Invalid staging admission job ID")
    root = Path(plan["stageRoot"]).resolve(strict=True)
    directory = root / ".volume-reservations"
    if action == "inspect":
        # Inspection never creates a reservation, namespace, filesystem ID or grant.
        with (directory / "capacity.lock").open("r") as lock:
            fcntl.flock(lock, fcntl.LOCK_SH)
            identity = str(uuid.UUID((directory / "filesystem.id").read_text().strip()))
            if identity != plan["stageFilesystemId"]:
                raise RuntimeError("Staging filesystem changed; reservation identity is unconfirmed")
            reservation = directory / (job_id + ".json")
            if not reservation.exists():
                return {"state": "NONE", "reservedBytes": 0}
            record = json.loads(reservation.read_text())
            if record != plan["reservation"]:
                raise RuntimeError("Reservation differs from the saved admission request")
            return {"state": "RESERVED", "reservedBytes": record["bytes"] + record.get("primaryScratchBytes", 0)}
    directory.mkdir(mode=0o700, exist_ok=True)
    reservation = directory / (job_id + ".json")
    granted = job / "staging-admission-granted"
    canceled = job / "staging-admission-cancel"
    fences = job / "staging-admission-fences.json"
    with (directory / "capacity.lock").open("a") as lock:
        fcntl.flock(lock, fcntl.LOCK_EX)
        identity = staging_identity(directory)
        if identity != plan["stageFilesystemId"]:
            raise RuntimeError("Staging filesystem changed while the job was waiting")
        if action == "cancel":
            if reservation.exists() or granted.exists():
                return {"state": "ADMITTED", "reason": "The job has already acquired staging capacity"}
            if not canceled.exists():
                atomic(canceled, reason or "Staging queue canceled by operator")
            return {"state": "CANCELED", "reason": canceled.read_text()}
        if canceled.exists():
            return {"state": "CANCELED", "reason": canceled.read_text()}
        if reservation.exists():
            record = json.loads(reservation.read_text())
            if record != plan["reservation"]:
                raise RuntimeError("Staging reservation differs from the saved admission request")
            atomic(granted, "admitted\n")
            return {"state": "ADMITTED"}
        if granted.exists():
            # The engine may already have released its reservation. Never reacquire it.
            return {"state": "ADMITTED"}
        if action == "status":
            if token:
                # Fence a possibly delayed grant BEFORE proving WAITING to the
                # controller. A timeout alone cannot release its DB claims.
                blocked = json.loads(fences.read_text()) if fences.exists() else []
                if token not in blocked:
                    blocked.append(token)
                    atomic(fences, blocked)
            return {"state": "WAITING", "fencedToken": token}
        if plan.get("admissionProtocolVersion") == 1 and not token:
            raise RuntimeError("Admission requires a controller reservation token")
        if token and fences.exists() and token in json.loads(fences.read_text()):
            return {"state": "WAITING", "reason": "STALE_ADMISSION_TOKEN", "effectiveAvailableBytes": 0, "fencedToken": token}
        record = plan["reservation"]
        required = record["bytes"] + record.get("primaryScratchBytes", 0)
        reserved = sum(r["bytes"] + r.get("primaryScratchBytes", 0)
                       for r in (json.loads(p.read_text()) for p in directory.glob("*.json")))
        stat = os.statvfs(root)
        available = stat.f_bavail * stat.f_frsize - reserved
        if required > available:
            if token:
                blocked = json.loads(fences.read_text()) if fences.exists() else []
                if token not in blocked:
                    blocked.append(token)
                    atomic(fences, blocked)
            return {"state": "WAITING", "reason": "STAGING_CAPACITY", "requiredBytes": required,
                    "effectiveAvailableBytes": max(0, available), "fencedToken": token}
        atomic(reservation, record)
        atomic(granted, "admitted\n")
        return {"state": "ADMITTED"}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--plan-file", required=True)
    parser.add_argument("--action", choices=("admit", "cancel", "status", "inspect"), required=True)
    parser.add_argument("--reason", default="")
    parser.add_argument("--token", default="")
    parser.add_argument("--result-file")
    args = parser.parse_args()
    result = control(args.plan_file, args.action, args.reason, args.token)
    output = Path(args.result_file) if args.result_file else Path(args.plan_file).parent / (
        "staging-admission-inspection.json" if args.action == "inspect" else "staging-admission-result.json")
    if output.parent != Path(args.plan_file).parent:
        raise RuntimeError("Admission result must remain in the job control directory")
    atomic(output, result)


if __name__ == "__main__":
    main()
