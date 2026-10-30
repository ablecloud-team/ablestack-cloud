#!/usr/bin/env python3
# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements. See NOTICE. Apache License, Version 2.0.
# http://www.apache.org/licenses/LICENSE-2.0
"""Native read-only regression: signed bootstrap -> active CLI -> inheritance preflight.

The caller stages a verified image tree and private runtime/state/entrypoint roots.
Run only in a disposable SystemVM. This test never applies share configuration.
"""
import argparse
import json
import os
from pathlib import Path
import subprocess


def verify(image, runtime, env, share_name):
    image, runtime = Path(image), Path(runtime)
    helper = image / "usr/local/lib/ablestack-storage"
    ctl = image / "usr/local/bin/ablestack-storagectl"
    assert runtime.parent == image.parent and runtime.parent.parent == Path("/var/tmp")
    assert env["ABLESTACK_STORAGE_RUNTIME_ROOT"] == str(runtime)
    assert env["ABLESTACK_STORAGE_RUNTIME_ENTRYPOINT_ROOT"] == str(ctl.parent)
    desired = json.loads(Path("/etc/ablestack-storage/desired-state/smb-share-apply.json").read_text())
    share = next(row for row in desired["shares"] if row["name"] == share_name)
    policies = json.loads(Path("/etc/ablestack-storage/desired-state/directory-policy.json").read_text())["policies"]
    policy = policies[share["config"]["directoryPolicyUuid"]]
    query = {
        "protocol": "SMB", "revision": 0, "action": "UPDATE",
        "ports": [445], "resourceids": [share["uuid"]], "resourcecount": 1,
        "ackrequired": True, "drainrequired": False,
        "smbinheritance": {"inheritOwner": True, "directoryPolicyId": policy["uuid"]},
        "inheritancetarget": share, "inheritancepolicy": policy,
    }
    payload = runtime.parent / "inheritance-preflight.json"
    payload.write_text(json.dumps(query))
    env = dict(env, PYTHONDONTWRITEBYTECODE="1")
    def command(args, stdin=None):
        result = subprocess.run(args, input=stdin, capture_output=True, text=True, timeout=60, env=env)
        assert result.returncode == 0, (result.stdout[-1500:], result.stderr[-1500:])
        return json.loads(result.stdout)
    results = []
    pointer = integrity = version = None
    for attempt in range(3):
        boot = command(["python3", str(helper / "runtime_updater.py"), "bootstrap"], "{}")
        release = ctl.resolve().parent
        assert ctl.is_symlink() and release == (runtime / "current").resolve()
        assert not (release / "lib/ablestack-storage/runtime_updater.py").exists()
        assert (helper / "runtime_updater.py").is_file()
        capability = command([str(ctl), "configuration", "capability", "show"])
        assert capability["smbParentInheritanceVersion"] == 1
        assert capability["smbParentInheritanceProtectionVersion"] == 1
        assert capability["adDomainTransactionVersion"] == 1
        report = command([str(ctl), "configuration", "preflight", "show", str(payload)])
        assert report["status"] == "READY", report
        assert report["inheritancepreview"]["inheritowner"] is True
        assert report["inheritancepreview"]["existingobjectschange"] is False
        if attempt:
            assert boot["currentVersion"] == version
            assert os.readlink(runtime / "current") == pointer
            assert (runtime / "current/integrity.json").read_bytes() == integrity
        version = boot["currentVersion"]
        pointer = os.readlink(runtime / "current")
        integrity = (runtime / "current/integrity.json").read_bytes()
        results.append({"bootstrap": attempt + 1, "version": version,
                        "activatedCli": str(ctl.resolve()), "protection": 1, "adDomainTransactionVersion": 1,
                        "inheritOwnerTruePreflight": report["status"]})
    return {"success": True, "firstAndRepeatedBootstrapActiveCli": results,
            "releaseContainsUpdater": False, "configurationApplied": False}


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--image-root", required=True, type=Path)
    parser.add_argument("--runtime-root", required=True, type=Path)
    parser.add_argument("--share", required=True)
    args = parser.parse_args()
    print(json.dumps(verify(args.image_root, args.runtime_root, os.environ, args.share)))
