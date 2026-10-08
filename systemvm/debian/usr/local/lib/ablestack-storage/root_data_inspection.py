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

"""Fresh, strictly identified DATA inventory for same-VM ROOT changes."""
import os
from pathlib import Path
import uuid
import json
import subprocess
from volume_identity import (VolumeIdentityError, flatten_devices, flatten_mounts,
                             resolve_volume_device, root_disk_names)

def inspect_signatures(device):
    try:
        observed = subprocess.run(["wipefs", "--no-act", "--json", device], capture_output=True, text=True, timeout=8)
        value = json.loads(observed.stdout) if observed.returncode == 0 else None
        if not isinstance(value, dict) or not isinstance(value.get("signatures"), list):
            return {"available": False, "signatures": []}
        return {"available": True, "signatures": [{key: item.get(key) for key in ("offset", "type", "uuid", "label")} for item in value["signatures"]]}
    except (ValueError, OSError, subprocess.TimeoutExpired):
        return {"available": False, "signatures": []}


def inspect_data(request, blockdevices, filesystems, signature_observer=inspect_signatures):
    for key in ("instanceUuid", "operationUuid", "templateUpgradeUuid"):
        str(uuid.UUID(request[key]))
    volumes = request.get("volumes")
    if not isinstance(volumes, list) or len(volumes) > 512:
        raise ValueError("ROOT DATA manifest request is invalid")
    devices = flatten_devices(blockdevices)
    mounts = flatten_mounts(filesystems)
    seen_ids = set()
    seen_paths = set()
    result = []
    for entry in volumes:
        volume = str(uuid.UUID(entry["volumeUuid"]))
        kind = entry["kind"]
        size = entry["sizeBytes"]
        if volume in seen_ids or kind not in ("FILE_DATA", "BLOCK_RAW", "UNUSED") or isinstance(size, bool) or not isinstance(size, int) or size <= 0:
            raise ValueError("ROOT DATA request has an ambiguous or invalid volume")
        seen_ids.add(volume)
        managed = "/srv/ablestack-storage/volumes/" + volume
        sources = {mount.get("source") for mount in mounts if mount.get("target") == managed}
        if len(sources) > 1:
            raise ValueError("ROOT DATA backing mount source is ambiguous")
        mount_source = next(iter(sources)) if sources else None
        mapped = resolve_volume_device(blockdevices, volume_uuid=volume, filesystem_uuid=entry.get("filesystemUuid"),
                                       mount_source=mount_source, expected_size=size, allow_blank_size_fallback=False)
        if mapped["matchedBy"] != "VOLUME_SERIAL":
            raise ValueError("ROOT DATA requires an exact stable volume serial mapping")
        disk_path = mapped["diskPath"]
        disk = next((item for item in devices if item.get("path") == disk_path), None)
        if not disk or disk.get("name") in root_disk_names(devices) or int(disk.get("size") or 0) != size:
            raise ValueError("ROOT DATA disk identity or exact size differs")
        if disk_path in seen_paths:
            raise ValueError("Two ROOT DATA records resolve to the same disk")
        seen_paths.add(disk_path)
        selected = next((item for item in devices if item.get("path") == mapped["devicePath"]), disk)
        if kind == "FILE_DATA":
            candidates = [item for item in devices if item.get("fstype") and
                          (item.get("path") == disk_path or (item.get("_parentDisk") or {}).get("path") == disk_path)]
            declared = entry.get("filesystemUuid")
            if declared:
                candidates = [item for item in candidates if item.get("uuid") == declared]
            if len(candidates) != 1 or not candidates[0].get("uuid"):
                raise ValueError("ROOT FILE DATA filesystem identity is unavailable or ambiguous")
            selected = candidates[0]
        related = []
        for mount in mounts:
            source = str(mount.get("source") or "").split("[", 1)[0]
            if source == selected.get("path") or (selected.get("uuid") and mount.get("uuid") == selected["uuid"]):
                related.append({key: mount[key] for key in ("source", "target", "fstype", "options", "uuid") if key in mount})
        signatures = signature_observer(disk_path)
        partitioned = bool(disk.get("children")) or any(item.get("path") != disk_path and item.get("_parentDisk", {}).get("path") == disk_path for item in devices)
        blank = bool(signatures.get("available") is True and not signatures.get("signatures") and not partitioned
                     and not disk.get("fstype") and not disk.get("uuid") and not disk.get("pttype") and not related)
        result.append({"volumeUuid": volume, "kind": kind, "mappingStatus": "EXACT", "matchedBy": mapped["matchedBy"],
                       "serial": disk.get("serial"), "wwn": disk.get("wwn"), "sizeBytes": int(disk["size"]),
                       "observedDevicePath": selected.get("path"), "filesystemUuid": selected.get("uuid"),
                       "filesystem": selected.get("fstype"), "mounts": related, "blank": blank,
                       "signatureObservationAvailable": signatures.get("available") is True, "signatures": signatures.get("signatures") or [], "partitioned": partitioned})
    return result
