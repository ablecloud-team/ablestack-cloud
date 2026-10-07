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

"""Share the backup pipeline's flock admission lock; never expire live reservations by age."""
import argparse
import fcntl
import json
import os
from pathlib import Path

from thirdparty_volume_backup import atomic, staging_identity, admission_closed_reason, close_admission, remove_reservation
from thirdparty_primary_capacity import filesystem_capacity


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--plan-file", required=True)
    parser.add_argument("--action", choices=("prepare", "reserve", "release", "cancel"), required=True)
    args = parser.parse_args()
    file = Path(args.plan_file)
    job = file.parent
    plan = json.loads(file.read_text())
    if job.name != plan["jobId"]:
        raise RuntimeError("Restore capacity control belongs to another job")
    root = Path(plan["stageRoot"]).resolve(strict=True)
    destination = Path(plan["destination"])
    if not destination.is_absolute() or not destination.resolve().is_relative_to(root):
        raise RuntimeError("Restore destination is outside configured staging")
    directory = root / ".volume-reservations"
    directory.mkdir(mode=0o700, exist_ok=True)
    reservation = directory / (plan["jobId"] + ".json")
    if reservation.parent != directory or reservation.name != plan["jobId"] + ".json":
        raise RuntimeError("Invalid restore job ID")
    if args.action == "cancel":
        from thirdparty_staging_admission import control
        control(job / "staging-admission-plan.json", "cancel", "Staging queue timeout expired")
        return
    with (directory / "capacity.lock").open("a") as lock:
        fcntl.flock(lock, fcntl.LOCK_EX)
        if args.action == "release":
            # Admission uses this same lock. Persist closure before removing the
            # reservation so a delayed grant cannot recreate a completed job's claim.
            close_admission(job, "Restore staging cleanup completed\n")
            remove_reservation(reservation)
            return
        if admission_closed_reason(job) is not None:
            raise RuntimeError("Restore staging admission is closed; use a new restore job")
        if reservation.exists():
            raise RuntimeError("Restore staging reservation already exists")
        plan["stageFilesystemId"] = staging_identity(directory)
        atomic(file, plan)
        selected = [v for v in plan["manifest"]["volumes"] if v["uuid"] in plan["volumeUuids"]]
        largest = max(v["provisionedBytes"] for v in selected)
        required = largest + (largest * plan["bufferPercent"] + 99) // 100
        scratch = plan.get("primaryScratchBytes", 0)
        record = {"jobId": plan["jobId"], "bytes": required,
                  "primaryScratchBytes": scratch, "host": plan["hostName"], "operation": "RESTORE"}
        if args.action == "prepare":
            storage_key = filesystem_capacity(root, True)["storageKey"]
            if plan.get("primaryCapacityVersion") == 1 and storage_key != plan["stagingStorageKey"]:
                raise RuntimeError("Staging capacity domain changed while preparing restore")
            atomic(job / "staging-admission-plan.json", {
                "jobId": plan["jobId"], "stageRoot": str(root), "stageFilesystemId": plan["stageFilesystemId"],
                "admissionProtocolVersion": 1,
                "reservation": record})
            atomic(job / "staging-admission-request.json", {
                "admission": True, "jobId": plan["jobId"], "operation": "RESTORE", "requiredBytes": required + scratch,
                "capacityVersion": plan.get("primaryCapacityVersion", 0), "stagingStorageKey": storage_key})
            return
        reserved = sum(r["bytes"] + r.get("primaryScratchBytes", 0)
                       for r in (json.loads(p.read_text()) for p in directory.glob("*.json")))
        stat = os.statvfs(root)
        if required + scratch > stat.f_bavail * stat.f_frsize - reserved:
            raise RuntimeError("Insufficient effective staging capacity including backup and restore reservations")
        atomic(reservation, record)


if __name__ == "__main__":
    main()
