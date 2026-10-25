#!/usr/bin/env python3
"""Non-privileged catalogue validation using the native signed-bundle contract."""
import hashlib
import json
import os
from pathlib import Path
import sys
from runtime_updater import RuntimeUpdater, RuntimeUpgradeError, sha256_file

def verify(root):
    if os.geteuid() == 0:
        raise RuntimeUpgradeError("PRIVILEGED_WORKER", "catalogue verification must be unprivileged")
    metadata = json.loads((root / "metadata.json").read_text())
    for name, field in [("bundle.tar.gz", "sha256"), ("manifest.json", "manifestsha256")]:
        if sha256_file(root / name) != metadata[field]:
            raise RuntimeUpgradeError("REGISTERED_HASH_MISMATCH", "registered hash differs")
    if metadata.get("artifactsize") is not None and (root / "bundle.tar.gz").stat().st_size != metadata["artifactsize"]:
        raise RuntimeUpgradeError("REGISTERED_SIZE_MISMATCH", "registered size differs")
    updater = RuntimeUpdater()
    updater.trusted_keys = root
    manifest = updater.load_manifest(root)
    updater.verify_signature(root, manifest)
    for field, key in [("bundleVersion", "version"), ("runtimeAbiVersion", "runtimeabiversion"),
                       ("desiredStateSchemaVersion", "desiredstateschemaversion"),
                       ("serviceImpact", "serviceimpact"), ("keyId", "signingkeyid")]:
        if str(manifest[field]) != str(metadata[key]):
            raise RuntimeUpgradeError("REGISTERED_METADATA_MISMATCH", "registered declaration differs: " + key)
    result = updater.inspect_archive(root, manifest)
    compatible = (str(manifest["runtimeAbiVersion"]) == "1" and
                  str(manifest["desiredStateSchemaVersion"]) == "1" and manifest["serviceImpact"] == "NONE")
    result.update(success=True, compatibility="COMPATIBLE" if compatible else "INCOMPATIBLE",
                  profileId="storage-runtime-abi1-schema1-layout2-control3", signatureVerified=True,
                  workerUid=os.geteuid(), payloadExecuted=False, archiveFilesExtracted=False)
    return result

if __name__ == "__main__":
    try:
        print(json.dumps(verify(Path(sys.argv[1])), sort_keys=True))
    except (RuntimeUpgradeError, OSError, ValueError) as error:
        print(json.dumps(dict(success=False, errorCode=getattr(error, "code", "VALIDATION_UNKNOWN"),
                              message=str(error)), sort_keys=True))
        sys.exit(1)
