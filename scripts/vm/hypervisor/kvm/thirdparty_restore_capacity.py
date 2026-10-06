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

from thirdparty_volume_backup import atomic


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--plan-file", required=True)
    parser.add_argument("--action", choices=("reserve", "release"), required=True)
    args = parser.parse_args()
    plan = json.loads(Path(args.plan_file).read_text())
    root = Path(plan["stageRoot"]).resolve(strict=True)
    destination = Path(plan["destination"])
    if not destination.is_absolute() or not destination.resolve().is_relative_to(root):
        raise RuntimeError("Restore destination is outside configured staging")
    directory = root / ".volume-reservations"
    directory.mkdir(mode=0o700, exist_ok=True)
    reservation = directory / (plan["jobId"] + ".json")
    if reservation.parent != directory or reservation.name != plan["jobId"] + ".json":
        raise RuntimeError("Invalid restore job ID")
    with (directory / "capacity.lock").open("a") as lock:
        fcntl.flock(lock, fcntl.LOCK_EX)
        if args.action == "release":
            reservation.unlink(missing_ok=True)
            return
        if reservation.exists():
            raise RuntimeError("Restore staging reservation already exists")
        selected = [v for v in plan["manifest"]["volumes"] if v["uuid"] in plan["volumeUuids"]]
        largest = max(v["provisionedBytes"] for v in selected)
        required = largest + (largest * plan["bufferPercent"] + 99) // 100
        scratch = plan.get("primaryScratchBytes", 0)
        reserved = sum(r["bytes"] + r.get("primaryScratchBytes", 0)
                       for r in (json.loads(p.read_text()) for p in directory.glob("*.json")))
        stat = os.statvfs(root)
        if required + scratch > stat.f_bavail * stat.f_frsize - reserved:
            raise RuntimeError("Insufficient effective staging capacity including backup and restore reservations")
        atomic(reservation, {"jobId": plan["jobId"], "bytes": required,
                             "primaryScratchBytes": scratch, "host": plan["hostName"], "operation": "RESTORE"})


if __name__ == "__main__":
    main()
