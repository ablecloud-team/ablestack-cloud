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

import os
import sys
import unittest


LIB_DIR = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "debian", "usr", "local", "lib", "ablestack-storage"))
sys.path.insert(0, LIB_DIR)

from volume_identity import VolumeIdentityError, flatten_mounts, resolve_volume_device  # noqa: E402


class StorageVolumeIdentityTest(unittest.TestCase):
    def devices(self):
        return [
            {
                "name": "sda", "path": "/dev/sda", "type": "disk", "size": 10 * 1024 ** 3,
                "serial": "root-disk", "children": [
                    {"name": "sda1", "path": "/dev/sda1", "type": "part", "fstype": "xfs", "uuid": "root-fs", "mountpoint": "/"},
                ],
            },
            {
                "name": "sdb", "path": "/dev/sdb", "type": "disk", "size": 50 * 1024 ** 3,
                "serial": "different-volume", "fstype": "xfs", "uuid": "wrong-fs", "mountpoint": "/srv/other",
            },
            {
                "name": "sde", "path": "/dev/sde", "type": "disk", "size": 50 * 1024 ** 3,
                "serial": "814767c9-81fc-4f95-9287-15997ed76921", "fstype": "xfs",
                "uuid": "5a467333-6cd0-4dd9-bacf-f9b6f0b08bf7",
                "mountpoint": "/srv/ablestack-storage/volumes/814767c9-81fc-4f95-9287-15997ed76921",
            },
        ]

    def test_volume_uuid_wins_after_guest_device_renumbering(self):
        result = resolve_volume_device(
            self.devices(),
            volume_uuid="814767c9-81fc-4f95-9287-15997ed76921",
            mount_source="/dev/sde",
        )
        self.assertEqual("/dev/sde", result["devicePath"])
        self.assertEqual("VOLUME_SERIAL", result["matchedBy"])

    def test_filesystem_uuid_is_stable_fallback(self):
        devices = self.devices()
        devices[2]["serial"] = ""
        result = resolve_volume_device(
            devices,
            filesystem_uuid="5a467333-6cd0-4dd9-bacf-f9b6f0b08bf7",
            mount_source="/dev/sde",
        )
        self.assertEqual("/dev/sde", result["devicePath"])
        self.assertEqual("FILESYSTEM_UUID", result["matchedBy"])

    def test_mounted_source_with_wrong_identity_is_rejected(self):
        with self.assertRaisesRegex(VolumeIdentityError, "does not match"):
            resolve_volume_device(
                self.devices(),
                volume_uuid="814767c9-81fc-4f95-9287-15997ed76921",
                mount_source="/dev/sdb",
            )

    def test_ambiguous_volume_identity_is_rejected(self):
        devices = self.devices()
        devices[2]["mountpoint"] = None
        duplicate = dict(devices[2])
        duplicate.update({"name": "sdf", "path": "/dev/sdf", "mountpoint": None})
        devices.append(duplicate)
        with self.assertRaisesRegex(VolumeIdentityError, "Ambiguous"):
            resolve_volume_device(devices, volume_uuid="814767c9-81fc-4f95-9287-15997ed76921")

    def test_size_only_fallback_requires_explicit_opt_in(self):
        devices = self.devices()
        devices.append({"name": "sdg", "path": "/dev/sdg", "type": "disk", "size": 25 * 1024 ** 3})
        with self.assertRaises(VolumeIdentityError):
            resolve_volume_device(devices, expected_size=25 * 1024 ** 3)
        result = resolve_volume_device(devices, expected_size=25 * 1024 ** 3, allow_blank_size_fallback=True)
        self.assertEqual("/dev/sdg", result["devicePath"])
        self.assertEqual("UNIQUE_BLANK_SIZE", result["matchedBy"])

    def test_findmnt_children_are_flattened_without_bind_alias_duplication(self):
        mounts = flatten_mounts([
            {
                "source": "/dev/sda6", "target": "/", "fstype": "xfs", "children": [
                    {
                        "source": "/dev/sde",
                        "target": "/srv/ablestack-storage/volumes/volume-1",
                        "fstype": "xfs",
                        "children": [
                            {"source": "/dev/sde[/nfs01]", "target": "/export/nfs01", "fstype": "xfs"},
                        ],
                    },
                ],
            },
        ])
        self.assertEqual(3, len(mounts))
        canonical = [item for item in mounts if item["target"].startswith("/srv/ablestack-storage/volumes/")]
        self.assertEqual(1, len(canonical))
        self.assertEqual("/dev/sde", canonical[0]["source"])


if __name__ == "__main__":
    unittest.main()
