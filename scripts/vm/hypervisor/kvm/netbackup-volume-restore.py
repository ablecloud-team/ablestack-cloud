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
import json
import os
from pathlib import Path
import re
import subprocess


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--plan-file", required=True)
    args = parser.parse_args()
    file = Path(args.plan_file)
    plan = json.loads(file.read_text())
    request = plan["request"]
    artifact = request["artifact"]
    values = [artifact["path"], request["destination"], artifact["sourceHost"], plan["destinationClient"], plan["server"]]
    if any(not value or "\n" in value or "\r" in value for value in values):
        raise RuntimeError("Invalid NetBackup restore selection")
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
    result = subprocess.run(["/usr/openv/netbackup/bin/bprestore", "-t", "0", "-C", artifact["sourceHost"],
                             "-D", plan["destinationClient"], "-S", plan["server"], "-s", date, "-e", date,
                             "-R", str(rename), "-f", str(selections), "-print_jobid"],
                            env=dict(os.environ, TZ="UTC"), capture_output=True, text=True, timeout=110)
    log = file.with_suffix(".log")
    log.write_text(result.stdout + result.stderr)
    os.chmod(log, 0o600)
    if result.returncode:
        raise RuntimeError("NetBackup bprestore failed; inspect " + str(log))
    match = re.search(r"(?:job\s*id\s*[:=]?\s*|^)(\d+)\s*$", result.stdout, re.IGNORECASE | re.MULTILINE)
    if not match:
        raise RuntimeError("NetBackup bprestore returned no job ID; inspect " + str(log))
    print("ABLESTACK_JOB_ID=" + match.group(1))


if __name__ == "__main__":
    main()
