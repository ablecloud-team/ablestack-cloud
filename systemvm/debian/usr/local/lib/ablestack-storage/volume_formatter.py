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

"""Public formatter identity and completion proof. Never signals, formats or mounts."""
import hashlib
import math
import os
from pathlib import Path
import re
import stat
import uuid


def volume_formatter_boot():
    return str(uuid.UUID(Path("/proc/sys/kernel/random/boot_id").read_text().strip()))


def volume_format_owner(request):
    owner = {key: request[key] for key in ("instanceUuid", "managerOperationUuid")}
    for value in owner.values():
        if not isinstance(value, str) or str(uuid.UUID(value)) != value:
            raise ValueError("Format owner UUID is invalid")
    revision = request.get("managerRevision", request.get("revision"))
    if type(revision) is not int or revision < 1 or ("revision" in request and request["revision"] != revision):
        raise ValueError("Format owner revision is invalid")
    return {**owner, "revision": revision}


def volume_original_owner(value):
    if not isinstance(value, dict) or set(value) != {"instanceUuid", "managerOperationUuid", "revision"}:
        raise ValueError("Original format owner is unavailable")
    return volume_format_owner(value)


def volume_formatter_process(pid):
    root = Path("/proc") / str(pid)
    def identity():
        info = root.stat()
        with (root / "stat").open("rb", buffering=0) as stream:
            raw = stream.read(4097)
        if len(raw) > 4096:
            raise ValueError("Formatter process metadata exceeds its bound")
        fields = raw[raw.rindex(b")") + 2:].split()
        if len(fields) < 20 or info.st_uid != os.geteuid():
            raise ValueError("Formatter process owner is unavailable")
        return {"pid": pid, "uid": info.st_uid, "startTicks": int(fields[19]),
                "state": fields[0].decode("ascii")}
    before = identity()
    with (root / "cmdline").open("rb", buffering=0) as stream:
        raw = stream.read(8193)
    if len(raw) > 8192:
        raise ValueError("Formatter argv exceeds its bound")
    argv = raw.rstrip(b"\0").split(b"\0") if raw else []
    after = identity()
    if any(before[key] != after[key] for key in ("pid", "uid", "startTicks")):
        raise ValueError("Formatter PID identity changed")
    return {**after, "argv": argv}


def volume_capture_formatter(pid, command, device, boot_id, process_reader=volume_formatter_process):
    # Capture before communicate()/wait(): even an already exited own child is
    # still a zombie with an exact start tick until this parent reaps it.
    observed = process_reader(pid)
    if type(observed["startTicks"]) is not int or observed["startTicks"] <= 0:
        raise ValueError("Formatter start ticks are unavailable")
    expected = [os.fsencode(value) for value in command]
    if observed["state"] not in ("Z", "X", "x") and observed["argv"] != expected:
        raise ValueError("Formatter argv differs from its owned child")
    return {"bootId": str(uuid.UUID(boot_id)), "pid": pid, "startTicks": observed["startTicks"],
            "argvSha256": hashlib.sha256(b"\0".join(expected)).hexdigest(),
            "devicePath": device, "formatter": Path(command[0]).name}


def volume_observe_formatter(record, boot_id=None, process_reader=volume_formatter_process):
    try:
        current_boot = str(uuid.UUID(boot_id)) if boot_id is not None else volume_formatter_boot()
    except (OSError, ValueError, UnicodeError, TypeError):
        return {"active": None, "state": None, "observationComplete": False, "bootId": None, "reason": "BOOT_UNAVAILABLE"}
    def answer(active, complete, reason, state=None):
        return {"active": active, "state": state, "observationComplete": complete,
                "bootId": current_boot, "reason": reason}
    if not record.get("formatStarted"):
        return answer(False, True, "NOT_STARTED")
    identity = record.get("formatterIdentity")
    recorded_boot = record.get("formatterBootId")
    if recorded_boot is not None:
        try:
            if str(uuid.UUID(recorded_boot)) != recorded_boot:
                raise ValueError()
        except (ValueError, TypeError, AttributeError):
            return answer(None, False, "INVALID_FORMATTER_BOOT")
        if recorded_boot != current_boot:
            # A process cannot survive a guest BOOT change. Never inspect an old
            # numeric PID that may now belong to an unrelated process.
            return answer(False, True, "PREVIOUS_BOOT")
    if not isinstance(identity, dict):
        # Ordinary historical completed mounts remain readable. An old weak
        # receipt is never upgraded into new explicit-resume authority.
        if record.get("phase") == "COMPLETE" and record.get("originalFormatOwner") is None and (record.get("formatterSuccessReceipt") or {}).get("schemaVersion") != 2:
            return answer(False, True, "LEGACY_COMPLETE")
        return answer(None, False, "MISSING_PINNED_IDENTITY")
    if (set(identity) != {"bootId", "pid", "startTicks", "argvSha256", "devicePath", "formatter"}
            or identity.get("bootId") != recorded_boot or identity.get("pid") != record.get("formatterPid")
            or identity.get("startTicks") != record.get("formatterStartTicks")
            or type(record.get("formatterPid")) is not int or type(record.get("formatterStartTicks")) is not int
            or type(identity.get("pid")) is not int or identity["pid"] <= 0
            or type(identity.get("startTicks")) is not int or identity["startTicks"] <= 0
            or not isinstance(identity.get("argvSha256"), str)
            or re.fullmatch("[a-f0-9]{64}", identity["argvSha256"]) is None
            or identity.get("devicePath") != record.get("devicePath")
            or identity.get("formatter") != record.get("formatter")):
        return answer(None, False, "INVALID_PINNED_IDENTITY")
    try:
        observed = process_reader(identity["pid"])
    except (FileNotFoundError, ProcessLookupError):
        return answer(False, True, "EXITED")
    except (OSError, ValueError, UnicodeError, IndexError):
        return answer(None, False, "PROCESS_READ_UNAVAILABLE")
    if observed.get("startTicks") != identity["startTicks"]:
        return answer(False, True, "PID_REUSED")
    if observed.get("state") in ("Z", "X", "x"):
        return answer(False, True, "EXITED", observed["state"])
    if hashlib.sha256(b"\0".join(observed["argv"])).hexdigest() != identity["argvSha256"]:
        return answer(None, False, "PROCESS_ARGV_CHANGED")
    return answer(True, True, "EXACT_PROCESS", observed["state"])


def volume_verify_format_receipt(record, existing_type, observed_uuid, serial, size):
    receipt = record.get("formatterSuccessReceipt")
    if not isinstance(receipt, dict) or type(receipt.get("schemaVersion")) is not int or receipt["schemaVersion"] != 2:
        raise ValueError("Explicit resume requires the strong formatter completion receipt")
    owner = volume_original_owner(record.get("originalFormatOwner"))
    if volume_original_owner(receipt.get("originalFormatOwner")) != owner:
        raise ValueError("Formatter receipt original owner differs")
    boot = record.get("formatterBootId")
    if not isinstance(boot, str) or str(uuid.UUID(boot)) != boot or receipt.get("formatterBootId") != boot:
        raise ValueError("Formatter receipt original BOOT differs")
    for key, recorded in (("formatterStartTicks", record.get("formatterStartTicks")), ("formatterPid", record.get("formatterPid"))):
        if type(recorded) is not int or recorded <= 0 or type(receipt.get(key)) is not int or receipt[key] != recorded:
            raise ValueError("Formatter receipt original process differs")
    normalized = lambda value: "".join(char for char in str(value or "").lower() if char.isalnum())
    token = str(uuid.UUID(record["volumeUuid"])).replace("-", "")
    live_serial = normalized(serial)
    if (live_serial not in (token, token[:20]) or normalized(receipt.get("serial")) != live_serial
            or normalized(record.get("serial")) != live_serial
            or type(size) is not int or size <= 0 or type(receipt.get("sizeBytes")) is not int
            or receipt["sizeBytes"] != size or type(record.get("volumeSizeBytes")) is not int or record.get("volumeSizeBytes") != size):
        raise ValueError("Formatter receipt live volume identity or geometry differs")
    if (record.get("terminationPending") or receipt.get("formatterExitCode") != 0 or type(receipt.get("formatterExitCode")) is not int
            or record.get("formatterExitCode") != 0 or type(record.get("formatterExitCode")) is not int
            or receipt.get("volumeUuid") != record.get("volumeUuid") or existing_type not in ("xfs", "ext4")
            or receipt.get("filesystemUuid") != observed_uuid or record.get("filesystemUuid") != observed_uuid
            or not isinstance(observed_uuid, str) or str(uuid.UUID(observed_uuid)) != observed_uuid
            or receipt.get("filesystem") != existing_type or record.get("filesystem") != existing_type):
        raise ValueError("Filesystem headers alone cannot prove successful format completion")
    policy = receipt.get("formatDiscardPolicy")
    expected_options = ["-f"] if existing_type == "xfs" else ["-F"]
    if policy == "SKIP_DISCARD":
        expected_options += ["-K"] if existing_type == "xfs" else ["-E", "nodiscard,lazy_itable_init=1,lazy_journal_init=1"]
    elif policy != "DEFAULT":
        raise ValueError("Formatter receipt discard policy is unknown")
    if receipt.get("formatterOptions") != expected_options or record.get("formatterOptions") != expected_options or record.get("formatDiscardPolicy") != policy:
        raise ValueError("Formatter receipt options differ from the reviewed invocation")
    verified = receipt.get("verifiedEpoch")
    if type(verified) not in (int, float) or not math.isfinite(verified) or verified <= 0:
        raise ValueError("Formatter receipt verification timestamp is invalid")
    return receipt
