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

"""Read-only source checks before any checkpoint, freeze, snapshot or data copy."""
import argparse
import json
import os
import subprocess
import sys

UNSUPPORTED = (
    "GFS2_RAW_OFFLINE_FULL_REQUIRED: GFS2 RAW volumes require a protected offline "
    "Full backup with every connected VM stopped. The online/checkpoint path is rejected."
)


def inspect(args):
    try:
        result = subprocess.run(args, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                                text=True, timeout=30, check=False, env=dict(os.environ, LC_ALL="C"))
    except (OSError, subprocess.TimeoutExpired) as error:
        raise RuntimeError("BACKUP_SOURCE_UNKNOWN: Source inspection failed (%s)" % type(error).__name__) from None
    if result.returncode:
        raise RuntimeError("BACKUP_SOURCE_UNKNOWN: %s source inspection failed" % args[0])
    return result.stdout


def require_supported_sources(paths):
    for path in paths:
        # RBD RAW uses Ceph snapshots and must not enter the file-image check.
        if path.startswith("rbd:"):
            continue
        if not os.path.isabs(path):
            raise RuntimeError("BACKUP_SOURCE_UNKNOWN: File source must be an absolute path")
        filesystem = inspect(["findmnt", "--noheadings", "--output", "FSTYPE", "--target", path]).strip()
        if not filesystem or len(filesystem.splitlines()) != 1:
            raise RuntimeError("BACKUP_SOURCE_UNKNOWN: Cannot determine the source filesystem")
        if filesystem.lower() != "gfs2":
            continue
        try:
            # Read metadata without taking the source writer's exclusive image lock.
            info = json.loads(inspect(["qemu-img", "info", "--force-share", "--output=json", path]))
            image_format = info.get("format")
        except (ValueError, AttributeError):
            raise RuntimeError("BACKUP_SOURCE_UNKNOWN: Cannot determine the source image format") from None
        if image_format == "raw":
            raise RuntimeError(UNSUPPORTED)
        if image_format != "qcow2":
            raise RuntimeError("BACKUP_SOURCE_UNKNOWN: Unsupported GFS2 source image format")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--disk-paths", required=True, help="Comma-separated source paths")
    args = parser.parse_args()
    paths = [path for path in args.disk_paths.split(",") if path]
    if not paths:
        parser.error("At least one source disk is required")
    try:
        require_supported_sources(paths)
    except RuntimeError as error:
        print(str(error), file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
