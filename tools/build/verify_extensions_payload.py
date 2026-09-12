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

"""Validate extension files in an extracted management RPM, without executing them."""

import argparse
import os
import stat
from pathlib import Path


REQUIRED = ("HyperV/hyperv.py", "MaaS/maas.py", "Proxmox/proxmox.sh")


def verify_payload(source_root, payload_root):
    source_root, payload_root = Path(source_root), Path(payload_root)
    installed = payload_root / "etc/cloudstack/extensions"
    link = payload_root / "usr/share/cloudstack-management/extensions"
    errors = []
    if not link.is_symlink() or os.readlink(link) != "/etc/cloudstack/extensions":
        errors.append("Missing or incorrect management extensions symlink")
    for relative in REQUIRED:
        source = source_root / "extensions" / relative
        target = installed / relative
        if not source.is_file() or not target.is_file() or target.is_symlink():
            errors.append("Missing extension file: " + relative)
            continue
        if source.read_bytes() != target.read_bytes():
            errors.append("Extension differs from source: " + relative)
        if stat.S_IMODE(target.stat().st_mode) != 0o755:
            errors.append("Extension must have mode 0755: " + relative)
    for directory in [installed] + [installed / Path(p).parent for p in REQUIRED]:
        if not directory.is_dir() or stat.S_IMODE(directory.stat().st_mode) != 0o755:
            errors.append("Extension directory must have mode 0755: " + str(directory))
    properties = payload_root / "etc/cloudstack/management/server.properties"
    mode = None
    if properties.is_file():
        for line in properties.read_text().splitlines():
            if line.strip().startswith("extensions.deployment.mode="):
                mode = line.split("=", 1)[1].strip()
    if mode != "production":
        errors.append("Packaged extensions.deployment.mode must be production")
    return errors


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source_root", type=Path)
    parser.add_argument("payload_root", type=Path)
    args = parser.parse_args()
    findings = verify_payload(args.source_root, args.payload_root)
    if findings:
        parser.exit(1, "\n".join(findings) + "\n")
    print("Verified management RPM extension files, modes, symlink and deployment mode")
