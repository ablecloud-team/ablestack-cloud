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

"""Gate C LOCAL test transport: real Native generation engine, never a status mock."""
from pathlib import Path
import argparse
import fcntl
import hashlib
import importlib.util
import json
import os
import re
import stat
import sys
import time

MODULE_SHA256 = "01f3d84d5e655be77039b1d3f6423aa27e07ebce395a0d636e58c8f7b6c155ab"
ACTIONS = frozenset(("status", "begin", "verify", "commit", "finish", "restore", "rollback", "frozen", "frozen-initial", "clone-initial-inspect", "render-import", "render-status", "render-stage", "render-activate", "render-finalize", "render-rollback", "render-maintenance-resume-source", "service-enter", "service-status", "service-release", "observe", "inventory", "verify-health", "health", "writer-idle", "identity-capabilities", "export-identity", "export-local-source", "local-source-status"))
MAX_INPUT = 65536

def require(value, code):
    if not value:
        raise ValueError(code)

def sha(raw):
    return hashlib.sha256(raw).hexdigest()

def check_case(path):
    require(path.is_absolute() and path.resolve(strict=True)==path, "ABSOLUTE_REAL_CASE_DIRECTORY_REQUIRED")
    info=path.lstat()
    require(stat.S_ISDIR(info.st_mode)and not path.is_symlink()and info.st_uid==os.geteuid()and not info.st_mode&0o077,"PRIVATE_TEST_CASE_DIRECTORY_REQUIRED")
    return path


def fingerprint(path):
    if not os.path.lexists(path):
        return {"exists": False}
    before = path.lstat()
    require(stat.S_ISREG(before.st_mode) and before.st_uid == os.geteuid()
            and not before.st_mode & 0o022 and before.st_size <= 8 * 1024 * 1024, "UNPROTECTED_GENERATION_FILE")
    fd = os.open(path, os.O_RDONLY | os.O_NOFOLLOW | os.O_CLOEXEC)
    try:
        opened = os.fstat(fd)
        identity = lambda s: (s.st_dev, s.st_ino, s.st_size, s.st_mode, s.st_mtime_ns)
        require(identity(before) == identity(opened), "GENERATION_FILE_CHANGED")
        raw = os.read(fd, 8 * 1024 * 1024 + 1)
        require(len(raw) == opened.st_size and identity(before) == identity(path.lstat()), "GENERATION_FILE_CHANGED")
        return {"exists": True, "sha256": sha(raw), "bytes": len(raw)}
    finally:
        os.close(fd)

def observation(generation):
    return {"current": fingerprint(generation.current), "pending": fingerprint(generation.pending),
            "configurationSha256": generation.digest()}

def append_trace(path, record):
    raw = (json.dumps(record, sort_keys=True, separators=(",", ":")) + "\n").encode()
    require(len(raw) < 65536, "TRACE_ROW_TOO_LARGE")
    fd = os.open(path, os.O_WRONLY | os.O_APPEND | os.O_CREAT | os.O_NOFOLLOW | os.O_CLOEXEC, 0o600)
    try:
        info = os.fstat(fd)
        require(stat.S_ISREG(info.st_mode) and info.st_uid == os.geteuid()
                and stat.S_IMODE(info.st_mode) == 0o600, "UNPROTECTED_TRACE")
        fcntl.flock(fd, fcntl.LOCK_EX)
        require(os.write(fd, raw) == len(raw), "SHORT_TRACE_WRITE")
        os.fsync(fd)
    finally:
        os.close(fd)

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--case-dir", type=Path, required=True)
    parser.add_argument("--repository-root", type=Path, required=True)
    args = parser.parse_args()
    case = check_case(args.case_dir)
    repository=args.repository_root.resolve(strict=True)
    actual_module=repository/"systemvm/debian/usr/local/lib/ablestack-storage/config_generation.py"
    require(sha(actual_module.read_bytes()) == MODULE_SHA256, "ACTUAL_NATIVE_MODULE_CHANGED")
    raw = sys.stdin.buffer.read(MAX_INPUT + 1)
    require(0 < len(raw) <= MAX_INPUT, "BOUNDED_JSON_STDIN_REQUIRED")
    wire = json.loads(raw)
    require(type(wire) is dict and set(wire) == {"operation", "request"}, "EXACT_TRANSPORT_WIRE_REQUIRED")
    aliases={"operation observe":"observe","inventory":"inventory","operation verify":"verify-health","health":"health","operation writer-idle":"writer-idle","operation maintenance service-enter":"service-enter","operation maintenance service-status":"service-status","operation maintenance service-release":"service-release","identity capsule capabilities":"identity-capabilities","identity capsule export":"export-identity","identity capsule export-local-source":"export-local-source","smb identity local-source-status":"local-source-status"}
    literal=aliases.get(wire["operation"])
    match = re.fullmatch(r"operation generation ([a-z-]+)", wire["operation"]) if type(wire["operation"]) is str else None
    if literal:match=re.fullmatch(r"operation generation ([a-z-]+)","operation generation "+literal)
    require(match is not None and match.group(1) in ACTIONS, "UNSUPPORTED_TEST_TRANSPORT_OPERATION")
    action = match.group(1)
    require(wire["request"] is None or type(wire["request"]) is dict, "NATIVE_REQUEST_OBJECT_REQUIRED")
    # Only the normal canonical module is imported. No replacement Generation subclass or monkey patch.
    spec = importlib.util.spec_from_file_location("gate_c_real_config_generation", actual_module)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    generation = module.Generation(state=case / "native-generations", configuration=case / "configuration")
    trace = case / "native-transport-trace.jsonl"
    if not trace.exists():
        require(not os.path.lexists(generation.current) and not os.path.lexists(generation.pending), "MANUAL_INITIAL_GENERATION_SEED_PROHIBITED")
        if generation.root.exists():
            require(not any(generation.root.iterdir()), "NONEMPTY_INITIAL_GENERATION_DIRECTORY")
    before = observation(generation)
    started = time.monotonic()
    record = {"kind": "REAL_NATIVE_GENERATION_CALL", "action": action, "operation": wire["operation"],
              "moduleSha256": MODULE_SHA256, "requestSha256": sha(raw), "requestKeys": sorted((wire["request"] or {}).keys()),
              "before": before, "startedEpoch": time.time(), "manualGenerationSeed": False,
              "generationStatusMock": False, "realModuleExecute": True, "shellWriterLockCoverageClaimed": False,
              "osKernelFixtureUsedByThisTransport": False, "runtimeProtocolVerificationClaimed": False}
    result = None
    code = 0
    try:
        if action=="identity-capabilities":
            path=Path(__file__).with_name("identity-capability-readonly.py")
            spec=importlib.util.spec_from_file_location("real_identity_capability",path)
            helper=importlib.util.module_from_spec(spec);spec.loader.exec_module(helper)
            result=helper.capability(repository,wire["request"])
        elif action.startswith("render-") or action in ("export-identity","export-local-source","local-source-status","service-enter","service-status","service-release","observe","inventory","verify-health","health","writer-idle"):
            guard_name="case_filesystem_guard"
            guard_path=Path(__file__).with_name("case_filesystem_guard.py").resolve(strict=True)
            loaded=sys.modules.get(guard_name)
            if loaded is not None:
                require(Path(getattr(loaded,"__file__","")).resolve(strict=True)==guard_path,"EXACT_SIBLING_CASE_GUARD_REQUIRED")
            else:
                guard_spec=importlib.util.spec_from_file_location(guard_name,guard_path)
                guard=importlib.util.module_from_spec(guard_spec);sys.modules[guard_name]=guard
                try:guard_spec.loader.exec_module(guard)
                except BaseException:
                    sys.modules.pop(guard_name,None);raise
            path=Path(__file__).with_name("rendered-local-os-fixture.py")
            spec=importlib.util.spec_from_file_location("real_rendered_os_fixture",path)
            helper=importlib.util.module_from_spec(spec);spec.loader.exec_module(helper)
            result=helper.RenderedGuestFixture(repository,case,generation).execute("verify" if action=="verify-health" else action,wire["request"])
            record["actualNativeRenderedProducer"]=True
            record["osKernelFixtureUsedByThisTransport"]=True
        elif action == "clone-initial-inspect":
            helper_path=Path(__file__).with_name("witness-os-fixture-portable.py")
            helper_spec=importlib.util.spec_from_file_location("gate_c_portable_os_fixture",helper_path)
            helper=importlib.util.module_from_spec(helper_spec);helper_spec.loader.exec_module(helper)
            result=helper.inspect_fixture(generation,wire["request"],case/"witness-os-fixture.json",repository)
            witness_path=repository/"systemvm/debian/usr/local/lib/ablestack-storage/clone_initial_witness.py"
            record["actualWitnessModuleSha256"]=sha(witness_path.read_bytes())
            record["actualNativeWitnessProducer"]=True
            record["osKernelFixtureUsedByThisTransport"]=True
            record["runtimeObservationSource"]="EXPLICIT_OS_KERNEL_FIXTURE_NOT_REAL_VM"
        else:
            result = generation.execute(action, wire["request"])
        require(type(result) is dict, "NATIVE_ENGINE_RESULT_SCHEMA")
        record["resultPublic"] = {k: result[k] for k in ("success", "generationSupported", "generationStatus",
                                  "runtimeRevision", "pendingOperationUuid", "phase", "configurationSha256") if k in result}
        record["nativeExecuteSucceeded"] = True
    except (ValueError, KeyError, OSError, TypeError) as error:
        record["nativeExecuteSucceeded"] = False
        record["nativeErrorClass"] = type(error).__name__
        result = {"success": False, "errorCode": "CONFIG_GENERATION_REJECTED", "reason": type(error).__name__}
        code = 1
    finally:
        record["after"] = observation(generation)
        record["durationSeconds"] = round(time.monotonic() - started, 6)
        record["completedEpoch"] = time.time()
        append_trace(trace, record)
    # Existing Java Native transport receives this real result without an additional envelope.
    print(json.dumps(result, sort_keys=True))
    return code

if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (ValueError, KeyError, OSError, TypeError, json.JSONDecodeError) as error:
        print(json.dumps({"success": False, "errorCode": "LOCAL_GENERATION_TRANSPORT_REJECTED",
                          "errorClass": type(error).__name__}), flush=True)
        raise SystemExit(2) from None
