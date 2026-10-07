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

"""Reject staging roots, primary directories, symlink ancestors and nested mounts before cleanup."""
import argparse
from pathlib import Path
import re

LEGACY_ROOTS = {
    "ablestack-commvault": Path("/tmp/mold/backup"),
    "ablestack-netbackup": Path("/tmp/mold/netbackup"),
    "ablestack-veeam": Path("/tmp/mold/veeam"),
}
TIMESTAMP = r"[0-9]{4}(\.[0-9]{2}){5}\.[0-9]{3}"


def validate_job_directory(provider, path, provider_root=None):
    path = Path(path)
    legacy = LEGACY_ROOTS.get(provider)
    if legacy is None or not path.is_absolute() or any(part in (".", "..") for part in path.parts):
        raise RuntimeError("Invalid staging cleanup provider or path: %s" % path)
    if path.is_relative_to(legacy):
        root = legacy
    else:
        root = Path(provider_root) if provider_root is not None else path.parent.parent
    if (not root.is_absolute() or any(part in (".", "..") for part in root.parts)
            or (root != legacy and root.name != provider) or not path.is_relative_to(root)):
        raise RuntimeError("Cleanup must target a provider's individual job directory: %s" % path)
    relative = path.relative_to(root).parts
    if (len(relative) != 2 or not re.fullmatch(r"[A-Za-z0-9_.-]+", relative[0])
            or relative[0] in (".", "..")
            or not re.fullmatch(r"[A-Za-z0-9_.-]+" if relative[0] == "restore" else TIMESTAMP, relative[1])
            or relative[1] in (".", "..")):
        raise RuntimeError("Invalid staging job directory: %s" % path)
    for current in (path, *path.parents):
        if current.is_symlink():
            raise RuntimeError("Staging cleanup cannot traverse a symbolic link: %s" % current)
    mounts = Path("/proc/self/mountinfo").read_text().splitlines()
    if not mounts:
        raise RuntimeError("Unable to inspect staging cleanup mounts")
    for line in mounts:
        fields = line.split(" ")
        if len(fields) < 10 or not fields[4].startswith("/"):
            raise RuntimeError("Unable to inspect staging cleanup mounts")
        mount = re.sub(r"\\(040|011|012|134)", lambda match: chr(int(match[1], 8)), fields[4])
        if Path(mount).is_relative_to(path):
            raise RuntimeError("Staging cleanup cannot delete a mount point or its parent: %s" % mount)


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--provider", required=True)
    parser.add_argument("--path", required=True)
    args = parser.parse_args()
    try:
        validate_job_directory(args.provider, args.path)
    except (OSError, RuntimeError) as error:
        parser.exit(1, "Unsafe staging cleanup: %s\n" % error)
