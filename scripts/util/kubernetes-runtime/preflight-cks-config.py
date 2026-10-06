#!/usr/bin/env python3
# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements. See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership. The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License. You may obtain a copy of the License at
# http://www.apache.org/licenses/LICENSE-2.0
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied. See the License for the
# specific language governing permissions and limitations
# under the License.
import argparse
import hashlib
import json
from pathlib import Path
import sys
import zipfile

REQUIRED = ("k8s-control-node.yml", "k8s-control-node-add.yml", "k8s-node.yml", "etcd-node.yml")


def inspect(candidate, installed):
    installed = Path(installed)
    results = []
    with zipfile.ZipFile(candidate) as archive:
        names = archive.namelist()
        for filename in REQUIRED:
            entry = "conf/" + filename
            if names.count(entry) != 1:
                raise ValueError("Candidate must have exactly one " + entry)
            expected = hashlib.sha256(archive.read(entry)).hexdigest()
            path = installed / filename
            actual = hashlib.sha256(path.read_bytes()).hexdigest() if path.is_file() else None
            results.append({"file": filename, "candidateSha256": expected,
                            "installedSha256": actual, "status": "MATCH" if expected == actual else "MISSING" if actual is None else "DRIFT"})
    return {"status": "PASS" if all(row["status"] == "MATCH" for row in results) else "FAIL", "files": results}


def main():
    parser = argparse.ArgumentParser(description="Read-only check of packaged Kubernetes templates against active external CKS configuration")
    parser.add_argument("--candidate-jar", required=True)
    parser.add_argument("--installed-conf", default="/usr/share/cloudstack-management/cks/conf")
    args = parser.parse_args()
    try:
        result = inspect(args.candidate_jar, args.installed_conf)
    except (OSError, ValueError, zipfile.BadZipFile) as error:
        print(json.dumps({"status": "ERROR", "error": str(error)}))
        return 2
    print(json.dumps(result, indent=2))
    return 0 if result["status"] == "PASS" else 1


if __name__ == "__main__":
    sys.exit(main())
