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
import stat
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
        detail = " ".join(result.stderr.split())[:512]
        raise RuntimeError("BACKUP_SOURCE_UNKNOWN: %s source inspection failed (exit=%s)%s" %
                           (args[0], result.returncode, ": " + detail if detail else ""))
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


def require_accessible_sources(paths):
    """Probe existing sources without snapshots, image writer locks or mutations.

    The caller bounds the whole probe with timeout, including kernel I/O waits.
    RAW sources are allowed here: offline Full protection is enforced at dispatch.
    """
    for path in paths:
        if not os.path.isabs(path):
            raise RuntimeError("BACKUP_SOURCE_UNAVAILABLE: File source must be an absolute path")
        try:
            filesystem = inspect(["findmnt", "--noheadings", "--output", "FSTYPE", "--target", path]).strip()
            if not filesystem or len(filesystem.splitlines()) != 1:
                raise RuntimeError("BACKUP_SOURCE_UNAVAILABLE: Cannot determine the source filesystem")
            mode = os.stat(path).st_mode
            if not (stat.S_ISREG(mode) or stat.S_ISBLK(mode)):
                raise RuntimeError("BACKUP_SOURCE_UNAVAILABLE: Source is not a disk file or block device")
            with open(path, "rb", buffering=0) as disk:
                if not disk.read(1):
                    raise RuntimeError("BACKUP_SOURCE_UNAVAILABLE: Source disk is empty")
        except OSError as error:
            raise RuntimeError("BACKUP_SOURCE_UNAVAILABLE: %s: %s" % (path, error.strerror)) from None


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--disk-paths", help="Comma-separated source paths")
    mode.add_argument("--health-paths", nargs="+", help="Read-only accessibility check before backup registration")
    args = parser.parse_args()
    paths = args.health_paths or [path for path in args.disk_paths.split(",") if path]
    if not paths:
        parser.error("At least one source disk is required")
    try:
        if args.health_paths:
            require_accessible_sources(paths)
        else:
            require_supported_sources(paths)
    except RuntimeError as error:
        print(str(error), file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
