#!/usr/bin/env python3

# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements.  See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.  The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at
#
#   http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied.  See the License for the
# specific language governing permissions and limitations
# under the License.

"""Write explicit registration details and source proof into the template."""
import argparse
import hashlib
import json
import os
import re
import xml.etree.ElementTree as ET
from pathlib import Path
import subprocess

ENTRYPOINTS = ("ablestack-storagectl", "ablestack-storage-boot-reconcile", "ablestack-storage-monitor")
p = argparse.ArgumentParser()
p.add_argument("--image-root", type=Path, required=True)
p.add_argument("--source-root", type=Path, required=True)
p.add_argument("--version", required=True)
p.add_argument("--runtime-version", required=True)
a = p.parse_args()
pom_path = a.source_root / "pom.xml"
declared_version = ET.parse(pom_path).getroot().findtext("{*}version") or ""
version_match = re.fullmatch(r"([0-9]+\.[0-9]+\.[0-9]+(?:\.[0-9]+)?)(?:-[A-Za-z][A-Za-z0-9_.-]*)?", declared_version)
if not version_match:
    raise SystemExit("Source POM product version is not a supported three/four-part platform version")
platform_version = version_match.group(1)
if platform_version.count(".") == 2:
    platform_version += ".0"
pom_sha256 = hashlib.sha256(pom_path.read_bytes()).hexdigest()
lock = json.loads((a.source_root / "tools/appliance/systemvmtemplate/storage-kernel-amd64.json").read_text())
commit = os.environ.get("ABLESTACK_STORAGE_RUNTIME_BUILD_COMMIT") or subprocess.check_output(
    ["git", "rev-parse", "HEAD"], cwd=a.source_root, text=True).strip()
files = []
for name in ENTRYPOINTS:
    path = a.image_root / "usr/local/bin" / name
    files.append({"path": name, "sha256": hashlib.sha256(path.read_bytes()).hexdigest()})
capabilities = {
    "storage.service.template": "true",
    "storage.service.template.version": a.version,
    "storage.service.platform.version": platform_version,
    "storage.service.template.maintenance.gate": "true",
    "storage.service.data.identity.inspect": "true",
    "storage.service.runtime.abi": "1",
    "storage.service.runtime.signed.readback": "true",
    "storage.service.desired.state.schema": "1",
    "storage.service.identity.capsule.schema": "1",
    "storage.service.configuration.generation.schema": "1",
    "storage.service.configuration.generation.adopt": "true",
    "storage.service.configuration.generation.align": "true",
    "storage.service.upgrade.min.manager.version": "4.23.0.0",
    "storage.service.upgrade.min.agent.version": "4.23.0.0",
    "storage.service.nvme.target.auth": "true",
    "storage.service.kernel.version": lock["kernelVersion"],
    "storage.service.source.commit": commit,
}
source_files = {"pom.xml": pom_sha256}
for base in ("systemvm/debian", "tools/appliance/systemvmtemplate", "tools/appliance/scripts", "tools/build"):
    for path in sorted((a.source_root / base).rglob("*")):
        relative = path.relative_to(a.source_root).as_posix()
        if path.is_file() and "__pycache__" not in path.parts and "runtime-trusted-keys" not in path.parts:
            source_files[relative] = hashlib.sha256(path.read_bytes()).hexdigest()
for name in ("tools/appliance/build.sh", "tools/appliance/shar_cloud_scripts.sh"):
    source_files[name] = hashlib.sha256((a.source_root / name).read_bytes()).hexdigest()
source_tree_sha = hashlib.sha256(json.dumps(source_files, sort_keys=True, separators=(",", ":")).encode()).hexdigest()
manifest = {"sourceTreeSha256": source_tree_sha, "sourceFiles": source_files, "manifestSchemaVersion": "1", "templateVersion": a.version,
            "runtimeBundleVersion": a.runtime_version, "buildCommit": commit,
            "platformVersion": platform_version, "productVersion": platform_version,
            "platformVersionSource": {"path": "pom.xml", "sha256": pom_sha256, "declaredVersion": declared_version},
            "architecture": "x86_64", "hypervisor": "KVM",
            "kernel": lock, "registrationDetails": capabilities, "runtimeFiles": files}
output = a.image_root / "etc/ablestack-storage/template-manifest.json"
output.parent.mkdir(parents=True, exist_ok=True)
output.write_text(json.dumps(manifest, indent=2, sort_keys=True) + "\n", encoding="utf-8")
output.chmod(0o644)
