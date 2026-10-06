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

"""Submit one exact Standard image selection with bprestore's documented -R path remapping."""
import argparse
import datetime
import fcntl
import json
import os
from pathlib import Path
import re
import subprocess
import uuid

from thirdparty_volume_backup import atomic


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
                raise RuntimeError("NetBackup non-submission receipt belongs to another request")
            print("ABLESTACK_NOT_SUBMITTED=" + json.dumps(saved))
            return
        try:
            restore(file, plan, args.recover_only)
        except Exception as error:
            # No durable intent means bprestore was never invoked. After an intent
            # exists, even a timeout/nonzero exit can hide an accepted restore job.
            if not args.recover_only and not file.with_suffix(".intent.json").exists() and not file.with_suffix(".receipt.json").exists():
                saved = {"plan": plan, "notSubmitted": True, "failure": str(error)}
                atomic(rejected, saved)
                print("ABLESTACK_NOT_SUBMITTED=" + json.dumps(saved))
                return
            raise


def restore(file, plan, recover_only):
    request = plan["request"]
    if not re.fullmatch(r"[A-Za-z0-9_.-]+", request["jobId"]) or request["sequence"] < 0:
        raise RuntimeError("Invalid NetBackup restore job identity")
    uuid.UUID(request["jobId"][-36:])
    artifact = request["artifact"]
    values = [artifact["path"], request["destination"], artifact["sourceHost"], plan["destinationClient"], plan["server"]]
    if any(not value or "\n" in value or "\r" in value for value in values):
        raise RuntimeError("Invalid NetBackup restore selection")
    receipt = file.with_suffix(".receipt.json")
    intent = file.with_suffix(".intent.json")
    log = file.with_suffix(".log")
    if receipt.exists():
        saved = json.loads(receipt.read_text())
        if saved["plan"] != plan or not re.fullmatch(r"[0-9]+", saved["jobId"]):
            raise RuntimeError("NetBackup restore receipt belongs to another request")
        print("ABLESTACK_JOB_ID=" + saved["jobId"])
        return
    if intent.exists():
        if json.loads(intent.read_text()) != plan:
            raise RuntimeError("NetBackup restore intent belongs to another request")
        # stdout is durable even when the wrapper loses its response. Never invoke bprestore twice.
        if log.exists():
            job_id = read_job_id(log.read_text())
            if job_id:
                atomic(receipt, {"plan": plan, "jobId": job_id})
                print("ABLESTACK_JOB_ID=" + job_id)
                return
        raise RuntimeError("NetBackup submission outcome is unknown; original request will not be resubmitted")
    if recover_only:
        raise RuntimeError("NetBackup restore receipt is not available; waiting for submission reconciliation")
    executable = Path("/usr/openv/netbackup/bin/bprestore")
    if not executable.is_file() or not os.access(executable, os.X_OK):
        raise RuntimeError("NetBackup bprestore is not installed or executable on the Restore Worker Host")
    stamp = artifact.get("backupTime")
    if not stamp:
        # NetBackup's Standard catalog image ID ends in its exact backup start epoch.
        stamp = artifact["externalId"].rsplit("_", 1)[1]
    when = (datetime.datetime.fromtimestamp(int(stamp), datetime.timezone.utc) if str(stamp).isdigit()
            else datetime.datetime.fromisoformat(stamp.replace("Z", "+00:00")).astimezone(datetime.timezone.utc))
    date = when.strftime("%m/%d/%Y %H:%M:%S")
    selections = file.with_suffix(".selections")
    rename = file.with_suffix(".rename")
    # Keep control files in NetBackup's allowed user_ops tree; no shell expansion of paths or client names.
    control = Path("/usr/openv/netbackup/logs/user_ops") / "ablestack" / request["jobId"]
    control.mkdir(parents=True, mode=0o700, exist_ok=True)
    selections = control / selections.name
    rename = control / rename.name
    selections.write_text(artifact["path"] + "\n")
    rename.write_text("change %s to %s\n" % (artifact["path"], request["destination"]))
    os.chmod(selections, 0o600)
    os.chmod(rename, 0o600)
    atomic(intent, plan)
    errors = file.with_suffix(".stderr.log")
    with log.open("w") as output, errors.open("w") as error:
        os.chmod(log, 0o600)
        os.chmod(errors, 0o600)
        subprocess.run(["/usr/openv/netbackup/bin/bprestore", "-t", "0", "-C", artifact["sourceHost"],
                             "-D", plan["destinationClient"], "-S", plan["server"], "-s", date, "-e", date,
                             "-R", str(rename), "-f", str(selections), "-print_jobid"],
                            env=dict(os.environ, TZ="UTC", LC_ALL="C"), stdout=output, stderr=error, timeout=110)
        output.flush()
        os.fsync(output.fileno())
    job_id = read_job_id(log.read_text())
    if not job_id:
        raise RuntimeError("NetBackup bprestore returned no job ID; inspect " + str(log))
    atomic(receipt, {"plan": plan, "jobId": job_id})
    print("ABLESTACK_JOB_ID=" + job_id)


def read_job_id(output):
    if output.strip().isdigit():
        return output.strip()
    matches = set(re.findall(r"\bjob\s*id\s*[:=]?\s*(\d+)\s*\.?\s*$", output, re.IGNORECASE | re.MULTILINE))
    return next(iter(matches)) if len(matches) == 1 else None


if __name__ == "__main__":
    main()
