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

"""Check capability claims against the mounted image, without executing it."""
import argparse
import hashlib
import json
from pathlib import Path

p = argparse.ArgumentParser()
p.add_argument("image_root", type=Path)
p.add_argument("--output", type=Path)
a = p.parse_args()
root = a.image_root
manifest = json.loads((root / "etc/ablestack-storage/template-manifest.json").read_text())
cap = manifest["registrationDetails"]
for key, expected in {
    "storage.service.template": "true", "storage.service.runtime.abi": "1",
    "storage.service.runtime.signed.readback": "true",
    "storage.service.desired.state.schema": "1", "storage.service.identity.capsule.schema": "1",
    "storage.service.configuration.generation.schema": "1",
    "storage.service.configuration.generation.adopt": "true",
    "storage.service.configuration.generation.align": "true",
    "storage.service.nvme.target.auth": "true",
}.items():
    if cap.get(key) != expected:
        raise SystemExit("Unsupported template capability: " + key)
for key in ("templateVersion", "runtimeBundleVersion", "buildCommit"):
    if not manifest.get(key):
        raise SystemExit("Missing template provenance: " + key)
lock = manifest["kernel"]
version = lock["kernelVersion"]
config = (root / ("boot/config-" + version)).read_text().splitlines()
for key, value in lock["requiredConfig"].items():
    if key + "=" + value not in config:
        raise SystemExit("Missing kernel capability: " + key)
if not (root / ("boot/vmlinuz-" + version)).is_file() or not (root / ("boot/initrd.img-" + version)).is_file():
    raise SystemExit("Pinned kernel boot artifacts are missing")
modules = root / "lib/modules" / version
for name in ("nvmet", "nvmet-tcp", "nvme-auth"):
    if not list(modules.rglob(name + ".ko*")):
        raise SystemExit("Missing pinned kernel module: " + name)
for item in manifest["runtimeFiles"]:
    source = root / "usr/local/bin" / item["path"]
    if hashlib.sha256(source.read_bytes()).hexdigest() != item["sha256"]:
        raise SystemExit("Runtime source differs from template manifest: " + item["path"])
cli = (root / "usr/local/bin/ablestack-storagectl").read_text()
for marker in ('identity_capsule_command()', 'action in ("adopt", "align")', 'configurationSha256'):
    if marker not in cli:
        raise SystemExit("Template runtime does not implement required ROOT recovery capability")
for module in ("runtime_updater.py", "volume_identity.py", "session_auth.py", "identity_capsule.py", "config_generation.py"):
    if not (root / "usr/local/lib/ablestack-storage" / module).is_file():
        raise SystemExit("Missing Storage Service support module: " + module)
updater = (root / "usr/local/lib/ablestack-storage/runtime_updater.py").read_text()
if "def readback(self, request):" not in updater or "updaterSha256" not in updater:
    raise SystemExit("Template runtime updater lacks signed installed-file readback")
if a.output:
    a.output.parent.mkdir(parents=True, exist_ok=True)
    a.output.write_text(json.dumps(manifest, indent=2, sort_keys=True) + "\n", encoding="utf-8")
print("Storage Service template capability validation passed: " + version)
