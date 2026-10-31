#!/usr/bin/env python3
# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements. See NOTICE. Apache License, Version 2.0.
# http://www.apache.org/licenses/LICENSE-2.0
"""Read-only pre-boot check of an assembled SystemVM's signed runtime payload."""
import argparse
import ast
import hashlib
import json
from pathlib import Path
import re
import stat
import subprocess
import sys


def verify(root):
    root = root.resolve(strict=True)

    def scoped(relative):
        path = root / relative
        if path.is_symlink() or not path.is_file() or not path.resolve().is_relative_to(root):
            raise ValueError("missing or unsafe image file: " + relative)
        return path

    module = ast.parse(scoped("usr/local/lib/ablestack-storage/runtime_updater.py").read_text())
    constants = {}
    for node in module.body:
        if isinstance(node, ast.Assign):
            for target in node.targets:
                if isinstance(target, ast.Name) and target.id in {
                    "MANAGED_FILES", "RUNTIME_ABI_VERSION", "DESIRED_STATE_SCHEMA_VERSION"
                }:
                    constants[target.id] = ast.literal_eval(node.value)
    contract = constants["MANAGED_FILES"]
    default = "etc/ablestack-storage/runtime-default/"
    manifest_path = scoped(default + "manifest.json")
    manifest = json.loads(manifest_path.read_text())
    if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._-]{0,127}", str(manifest.get("keyId", ""))):
        raise ValueError("invalid signing key identifier")
    key = scoped("etc/ablestack-storage/runtime-trusted-keys/" + manifest["keyId"] + ".pem")
    subprocess.run([
        "openssl", "pkeyutl", "-verify", "-pubin", "-inkey", str(key),
        "-sigfile", str(scoped(default + "manifest.sig")), "-rawin", "-in", str(manifest_path)
    ], check=True, capture_output=True)
    if (manifest.get("manifestFormat") != 2 or manifest.get("payloadLayout") != 2 or
            str(manifest.get("runtimeAbiVersion")) != str(constants["RUNTIME_ABI_VERSION"]) or
            str(manifest.get("desiredStateSchemaVersion")) != str(constants["DESIRED_STATE_SCHEMA_VERSION"])):
        raise ValueError("template manifest ABI/schema/layout differs")
    rows = manifest["files"]
    if len(rows) != len(contract) or {row["path"] for row in rows} != set(contract):
        raise ValueError("template manifest managed file set differs")
    verified = []
    for row in rows:
        name = row["path"]
        rel = Path(name)
        if rel.is_absolute() or ".." in rel.parts or str(rel) != name:
            raise ValueError("unsafe managed path")
        path = scoped("usr/local/" + ("bin/" + name if "/" not in name else name))
        mode = int(row["mode"], 8)
        actual = path.stat()
        if (mode != contract[name] or stat.S_IMODE(actual.st_mode) != mode or
                row["owner"] != "root" or row["group"] != "root" or
                actual.st_uid != 0 or actual.st_gid != 0 or actual.st_size != row["bytes"] or
                hashlib.sha256(path.read_bytes()).hexdigest() != row["sha256"]):
            raise ValueError("template payload metadata/hash differs: " + name)
        verified.append(name)
    profile = json.loads(scoped("usr/local/lib/ablestack-storage/runtime-baseline-profile.json").read_text())
    expected = {row["path"]: row for row in profile["files"]}
    if set(expected) != set(contract) or any(
            expected[row["path"]]["sha256"] != row["sha256"] or
            (expected[row["path"]]["mode"] if isinstance(expected[row["path"]]["mode"], int) else int(expected[row["path"]]["mode"], 8)) != int(row["mode"], 8) for row in rows):
        raise ValueError("template signed manifest and baseline profile differ")
    return {"success": True, "bundleVersion": manifest["bundleVersion"],
            "manifestSha256": hashlib.sha256(manifest_path.read_bytes()).hexdigest(),
            "signatureVerified": True, "managedFilesVerified": verified, "baselineProfileMatches": True}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, required=True)
    args = parser.parse_args()
    try:
        print(json.dumps(verify(args.root), sort_keys=True))
    except (ValueError, KeyError, OSError, subprocess.CalledProcessError) as error:
        print(json.dumps({"success": False, "error": str(error)}, sort_keys=True))
        sys.exit(1)
