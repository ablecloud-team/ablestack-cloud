#!/usr/bin/env python3
# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements. See NOTICE. Apache License, Version 2.0.
# http://www.apache.org/licenses/LICENSE-2.0
"""Explicit SystemVM-only probe; real CLI, private mount namespace, no service mutations."""
import base64
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import uuid

def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()

def run(args, **kw):
    return subprocess.run(args, check=True, capture_output=True, text=True, timeout=40, **kw)

def inside(work):
    old_namespace = os.environ["SMB_PROBE_PARENT_MOUNT"]
    assert os.readlink("/proc/self/ns/mnt") != old_namespace
    mappings = [(work / "release", Path(os.environ["SMB_PROBE_ACTIVE_RELEASE"])),
                (work / "etc-storage", Path("/etc/ablestack-storage")),
                (work / "etc-samba", Path("/etc/samba")),
                (work / "var-storage", Path("/var/lib/ablestack-storage")),
                (work / "storagectl.log", Path("/var/log/ablestack-storagectl.log"))]
    for source, target in mappings:
        assert target.exists()
        run(["mount", "--bind", str(source), str(target)])
    cli = "/usr/local/bin/ablestack-storagectl"
    os.environ["ABLESTACK_STORAGECTL_CACHE"] = "0"
    capability = json.loads(run([cli, "configuration", "capability", "show"]).stdout)
    assert capability["smbListenerProfilesVersion"] == 1
    observations = {}
    for command in ("health", "inventory"):
        response = json.loads(run([cli, command]).stdout)
        observed = response["smbRuntime"]
        assert observed["version"] == 1 and observed["listeners"][0]["state"] == "READY", observed
        observations[command] = observed["listeners"][0]["state"]
    request = json.loads((work / "request.json").read_text())
    result = json.loads(run([cli, "configuration", "operation", "apply", str(work / "request.json")]).stdout)
    assert result["state"] == "ROLLED_BACK" and result["rollback"]["state"] == "SUCCEEDED", result
    private = Path("/var/lib/ablestack-storage/configuration-operations") / request["instanceUuid"] / request["operationid"]
    child = json.loads((private / "runner-result.json").read_text())
    assert child["result"]["code"] == "DUPLICATE_SMB_ENDPOINT", child
    assert not Path("/etc/ablestack-storage/smb-listeners.json").exists()
    print(json.dumps({"ok": True, "capability": capability["smbListenerProfilesVersion"],
                      "observations": observations, "childCode": child["result"]["code"],
                      "state": result["state"], "rollback": result["rollback"]["state"]}))

def outside(release, operation=None):
    assert os.geteuid() == 0
    cli = Path("/usr/local/bin/ablestack-storagectl")
    active = cli.resolve().parent
    fallback = Path("/usr/local/lib/ablestack-storage/configuration_transaction.py")
    desired = Path("/etc/ablestack-storage/desired-state/smb-share-apply.json")
    assert active != release and active != Path("/usr/local/bin") and fallback.is_file()
    assert digest(fallback) != digest(release / "lib/ablestack-storage/configuration_transaction.py")
    assert not Path("/etc/ablestack-storage/smb-listeners.json").exists(), "legacy fixture prerequisite"
    old = json.loads(desired.read_text())
    assert len(old["listeners"]) == 1
    protected = [Path(p) for p in ("/etc/passwd", "/etc/group", "/etc/shadow", "/etc/gshadow",
                 "/etc/samba/smb.conf", "/var/lib/samba/private/passdb.tdb")]
    protected += [p for p in active.rglob("*") if p.is_file()] + [fallback, desired]
    before = {str(p): digest(p) for p in protected if p.is_file()}
    pid = run(["systemctl", "show", "smbd", "-p", "MainPID", "--value"]).stdout.strip()
    assert int(pid) > 1
    real_legacy_verified = False
    if operation:
        # Read-only check of the preserved real failed journal, never resume it.
        spec = importlib.util.spec_from_file_location("probe_configuration", release / "lib/ablestack-storage/configuration_transaction.py")
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        module.RuntimeAdapter(operation).verify_restored()
        real_legacy_verified = True
    with tempfile.TemporaryDirectory(prefix="smb-entrypoint-probe-") as name:
        work = Path(name)
        shutil.copytree(release, work / "release")
        (work / "etc-storage/desired-state").mkdir(parents=True, mode=0o700)
        shutil.copy2(desired, work / "etc-storage/desired-state/smb-share-apply.json")
        (work / "etc-samba").mkdir()
        shutil.copy2("/etc/samba/smb.conf", work / "etc-samba/smb.conf")
        (work / "var-storage").mkdir()
        (work / "storagectl.log").touch()
        instance = old["instanceUuid"]
        before_value = dict(old, listenerOnly=True)
        duplicate = dict(old["listeners"][0], id=str(uuid.uuid4()))
        candidate = dict(before_value, listeners=[old["listeners"][0], duplicate])
        request = {"instanceUuid": instance, "operationid": str(uuid.uuid4()),
                   "vmUuid": Path("/sys/class/dmi/id/product_uuid").read_text().strip().lower(),
                   "baseRevision": 0, "targetRevision": 1,
                   "before": {"smb share apply": before_value}, "candidate": {"smb share apply": candidate},
                   "preflight": {"api": "enableStorageServiceProtocol", "protocol": "SMB", "revision": 0,
                                 "ports": [445], "drainrequired": False, "requestbytes": 8192}}
        (work / "request.json").write_text(json.dumps(request))
        env = {**os.environ, "SMB_PROBE_PARENT_MOUNT": os.readlink("/proc/self/ns/mnt"),
               "SMB_PROBE_ACTIVE_RELEASE": str(active)}
        observed = run(["unshare", "--mount", "--propagation", "private", "--",
                        sys.executable, __file__, "--inside", str(work)], env=env)
        result = json.loads(observed.stdout)
    assert before == {str(p): digest(p) for p in protected if p.is_file()}
    assert pid == run(["systemctl", "show", "smbd", "-p", "MainPID", "--value"]).stdout.strip()
    result.update(protectedFilesUnchanged=True, productionSmbPidUnchanged=True,
                  realFailedJournalVerifiedReadOnly=real_legacy_verified)
    print(json.dumps(result))

if __name__ == "__main__":
    if sys.argv[1] == "--inside":
        inside(Path(sys.argv[2]))
    else:
        outside(Path(sys.argv[1]).resolve(), Path(sys.argv[2]) if len(sys.argv) > 2 else None)
