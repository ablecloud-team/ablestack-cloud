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

"""Fenced NAS workers keep the existing repository format and share VM start ownership."""
import argparse
import json
import os
from pathlib import Path
import signal
import subprocess

import thirdparty_stopped_backup_guard as guard
from thirdparty_volume_backup import (Backup, atomic, claim_backup_start, confirm_dummy_terminated,
                                     guest_is_off_or_absent, run)


def load(file):
    source = json.loads(file.read_text())
    plan = guard.identity(source)
    if (plan is None or not plan.get("nasSource") or file.parent.name != plan["jobId"]
            or not source["diskPaths"] or len(set(source["diskPaths"])) != len(source["diskPaths"])):
        raise RuntimeError("NAS source runtime identity is unconfirmed")
    if not source["rbd"] and source.get("dummyName") != "DUMMY-NAS-" + plan["jobId"]:
        raise RuntimeError("NAS dummy identity differs from its source owner")
    return source


def started(file, plan):
    receipt = json.loads((guard.ROOT.parent / (plan["jobId"] + ".json")).read_text())
    if receipt.get("version") != 1 or receipt.get("plan") != plan or receipt.get("state") != "STARTED":
        raise RuntimeError("NAS worker is unstarted or permanently fenced")
    record = json.loads((file.parent / "volume-source-start.json").read_text())
    if record != {"version": 1, "backupUuid": plan["jobId"], "state": "STARTED"}:
        raise RuntimeError("NAS source IO ownership is unconfirmed")


def begin(file, source):
    plan = guard.identity(source)
    claim_backup_start(file)
    receipt = file.parent / "volume-source-start.json"
    if receipt.exists():
        raise RuntimeError("NAS source was already initialized")
    record = {"version": 1, "backupUuid": plan["jobId"], "state": "NOT_STARTED"}
    atomic(receipt, record)
    with guard.locked(plan) as ownership:
        proof = guard.read(ownership, plan)
        if proof.get("released") or not guest_is_off_or_absent(source["vmName"]):
            raise RuntimeError("NAS stopped source is active or its protection was released")
        record["state"] = "STARTED"
        atomic(receipt, record)
    # The durable PROTECTED record continues to exclude starts after this lock is released.


def rbd_source(uri):
    if uri.startswith("rbd/"):
        return ["rbd"], uri
    return Backup.rbd_command(uri)


def snapshots(args, image):
    return json.loads(run(args + ["snap", "ls", "--format", "json", image], 30))


def snapshots_complete(file, source):
    plan = guard.identity(source)
    if not source["rbd"]:
        raise RuntimeError("QCOW2 cannot release ownership using RBD snapshot completion")
    started(file, plan)
    with guard.locked(plan) as ownership:
        proof = guard.read(ownership, plan)
        if proof.get("released"):
            raise RuntimeError("NAS snapshot preparation was already released")
        if not guest_is_off_or_absent(source["vmName"]):
            raise RuntimeError("NAS VM state changed before all snapshots were completed")
        for uri in source["diskPaths"]:
            args, image = rbd_source(uri)
            if not any(item.get("name") == source["checkpointName"] for item in snapshots(args, image)):
                raise RuntimeError("A required NAS RBD snapshot is absent; VM start protection is retained")
        guard.release_locked(ownership, plan, "SNAPSHOTS_COMPLETE")


def no_source_controls(file, source):
    targets = {str(file), source.get("dummyName", "")}
    if source["rbd"]:
        targets.update(rbd_source(uri)[1] + "@" + source["checkpointName"] for uri in source["diskPaths"])
    targets.discard("")
    for process in Path("/proc").iterdir():
        if not process.name.isdigit():
            continue
        try:
            args = process.joinpath("cmdline").read_bytes().decode().split("\0")
        except (FileNotFoundError, ProcessLookupError):
            continue
        if args and Path(args[0]).name in {"virsh", "rbd", "qemu-img", "qemu-nbd"} and targets.intersection(args):
            raise RuntimeError("A NAS source control process remains active; VM start protection is retained")


def cleanup(file, source):
    plan = guard.identity(source)
    with guard.locked(plan) as ownership:
        proof = guard.read(ownership, plan)
        if proof.get("released"):
            return
        no_source_controls(file, source)
        receipt = json.loads((file.parent / "volume-source-start.json").read_text())
        if (receipt.get("version") != 1 or receipt.get("backupUuid") != plan["jobId"]
                or receipt.get("state") not in ("STARTED", "NOT_STARTED")):
            raise RuntimeError("NAS source cleanup lacks its exact start record")
        if source["rbd"] and receipt["state"] == "STARTED":
            for uri in source["diskPaths"]:
                args, image = rbd_source(uri)
                if any(item.get("name") == source["checkpointName"] for item in snapshots(args, image)):
                    run(args + ["snap", "rm", image + "@" + source["checkpointName"]], 30)
                if any(item.get("name") == source["checkpointName"] for item in snapshots(args, image)):
                    raise RuntimeError("Partial NAS snapshot cleanup is unconfirmed")
        elif not source["rbd"]:
            dummy = source["dummyName"]
            if not guest_is_off_or_absent(dummy):
                run(["virsh", "-c", "qemu:///system", "destroy", dummy], 30)
            confirm_dummy_terminated(dummy)
        guard.release_locked(ownership, plan, "SOURCE_CLEANED")


def run_backup(file, source):
    if not source["rbd"] or not source.get("script"):
        raise RuntimeError("Detached NAS source execution requires its RBD script")
    begin(file, source)
    env = dict(os.environ, ABLESTACK_NAS_SOURCE_PLAN=str(file.resolve()))
    process = subprocess.Popen(source["script"], env=env)

    def interrupt(signum, frame):
        process.terminate()
        raise InterruptedError("NAS RBD worker interrupted; source cleanup must be reconciled")

    previous_term = signal.signal(signal.SIGTERM, interrupt)
    previous_int = signal.signal(signal.SIGINT, interrupt)
    try:
        result = process.wait(timeout=max(300, source.get("timeout", 43200)) + 120)
    except BaseException:
        process.terminate()
        raise
    finally:
        signal.signal(signal.SIGTERM, previous_term)
        signal.signal(signal.SIGINT, previous_int)
    if result != 0:
        return result
    plan = guard.identity(source)
    with guard.locked(plan) as ownership:
        proof = guard.read(ownership, plan)
        if not proof.get("released") or proof.get("phase") != "SNAPSHOTS_COMPLETE":
            raise RuntimeError("NAS snapshot completion is unconfirmed")
    return 0


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--plan-file", required=True, type=Path)
    parser.add_argument("--action", required=True, choices=("begin", "run", "snapshots-complete", "cleaned", "cleanup"))
    options = parser.parse_args()
    try:
        source = load(options.plan_file)
        if options.action == "run":
            return run_backup(options.plan_file, source)
        if options.action == "begin":
            begin(options.plan_file, source)
        elif options.action == "snapshots-complete":
            snapshots_complete(options.plan_file, source)
        else:
            cleanup(options.plan_file, source)
        return 0
    except Exception:
        # Runtime paths may contain repository and Ceph credentials.
        print("NAS source ownership or cleanup is unconfirmed; VM start protection is retained", flush=True)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
