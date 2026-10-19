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

"""Opt-in real NFS CLI/Ganesha lifecycle in an isolated Bookworm container.

No kernel_apply fixture is used. Only systemctl is a fixed process lifecycle
adapter supplied by the test launcher. The native CLI, DBus, listener checks,
bind mounts, NFS kernel client and immutable generation ownership are real.
Never enable this on an operator VM or the build host.
"""
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import time
import unittest
import uuid

ENABLED = os.environ.get("ABLESTACK_REAL_NFS_LIFECYCLE") == "1"
CLI = Path("/usr/local/bin/ablestack-storagectl")
STORE = Path("/var/lib/ablestack-storage/rendered-generations")
ALIAS_RECEIPT = Path("/etc/ablestack-storage/nfs-export-aliases.json")
LIB = Path("/usr/local/lib/ablestack-storage")
sys.path.insert(0, str(LIB))


def command(arguments, expected=0, env=None, timeout=45):
    result = subprocess.run(arguments, capture_output=True, text=True,
                            env=env, timeout=timeout)
    if result.returncode != expected:
        raise AssertionError({"command": arguments, "exitCode": result.returncode,
                              "stdout": result.stdout[-16000:],
                              "stderr": result.stderr[-4000:]})
    return result


def protected(path, value):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    path.write_text(json.dumps(value, sort_keys=True) + "\n")
    path.chmod(0o600)


def manifest(scope, files, previous=None):
    value = {"scope": scope,
             "files": {name: hashlib.sha256(raw.encode()).hexdigest()
                       for name, raw in files.items()},
             "previousRenderedSha256": previous}
    digest = hashlib.sha256(json.dumps(value, sort_keys=True,
                                     separators=(",", ":")).encode()).hexdigest()
    return {**value, "manifestSha256": digest}


def write_generation(scope, files, previous=None):
    value = manifest(scope, files, previous)
    path = STORE / "generations" / scope["operationUuid"]
    path.mkdir(parents=True, mode=0o700)
    for name, raw in files.items():
        file = path / name
        file.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
        file.write_text(raw)
        file.chmod(0o600)
    protected(path / "generation.json", value)
    return path, value


@unittest.skipUnless(ENABLED, "requires an explicit isolated real NFS lifecycle container")
class StorageNfsCliLifecycleTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        if os.geteuid() != 0 or not Path("/run/issue909-nfs-cli-isolated").is_file():
            raise RuntimeError("real NFS lifecycle must run only in its disposable namespace")
        cls.pin = os.environ["ABLESTACK_EXPECTED_CLI_SHA256"]
        if hashlib.sha256(CLI.read_bytes()).hexdigest() != cls.pin:
            raise RuntimeError("real NFS lifecycle CLI pin changed")
        cls.proof = Path(os.environ["ABLESTACK_LIFECYCLE_PROOF"])
        cls.proof.mkdir(parents=True, exist_ok=True)
        cls.results = []
        STORE.mkdir(parents=True, exist_ok=True, mode=0o700)
        (STORE / "generations").mkdir(mode=0o700)

    def run_lifecycle(self, kind, busy=False):
        from native_renderers import render_nfs_candidate
        from nfs_readiness import NfsEndpointReadiness

        label = kind.lower() + ("-busy" if busy else "")
        proof = self.proof / label
        proof.mkdir()
        volume = Path("/srv/ablestack-storage/volumes") / label
        backing = proof / "data"
        backing.mkdir()
        (backing / "retained.txt").write_text("immutable test data for " + label + "\n")
        volume.mkdir(parents=True, mode=0o750)
        command(["mount", "--bind", str(backing), str(volume)])
        alias = Path("/export") / ("lifecycle-" + label)
        export_uuid = str(uuid.uuid5(uuid.NAMESPACE_URL, "export-" + label))
        instance_uuid = str(uuid.uuid5(uuid.NAMESPACE_URL, "instance-" + label))
        target_uuid = str(uuid.uuid5(uuid.NAMESPACE_URL, "target-" + label))
        baseline_uuid = str(uuid.uuid5(uuid.NAMESPACE_URL, "baseline-" + label))
        scope = {"instanceUuid": instance_uuid, "operationUuid": target_uuid, "revision": 2}
        maintenance_scope = {**scope, ("templateUpgradeUuid" if kind == "ROOT" else "maintenanceUuid"):
                             str(uuid.uuid5(uuid.NAMESPACE_URL, "upgrade-" + label)) if kind == "ROOT" else target_uuid}
        disabled = {"enabled": False, "exports": [], "listenIp": "192.0.2.1",
                    "port": 2049, "protocolMode": "V4_ONLY", "idMappingMode": "NUMERIC"}
        payload = {**disabled, "enabled": True, "exports": [
            {"uuid": export_uuid, "name": alias.name, "path": str(alias), "state": "Ready",
             "config": {"backingPath": str(volume), "volumeMountPath": str(volume),
                        "createDirectory": False, "rootSquash": False},
             "acls": [{"principal": "192.0.2.1/32", "permission": "READ_WRITE",
                       "state": "Ready", "config": {"rootSquash": False}}]}]}
        baseline_scope = {**scope, "operationUuid": baseline_uuid, "revision": 1}
        baseline, baseline_manifest = write_generation(baseline_scope,
                                                      render_nfs_candidate(disabled, CLI))
        target, target_manifest = write_generation(scope, render_nfs_candidate(payload, CLI),
                                                   baseline_manifest["manifestSha256"])
        journal = {"scope": scope, "phase": "ACTIVATING",
                   "targetSha256": target_manifest["manifestSha256"],
                   "previousSha256": baseline_manifest["manifestSha256"],
                   "previousOperationUuid": baseline_uuid}
        protected(STORE / "activation.json", journal)
        pointer = STORE / "current"
        pointer.unlink(missing_ok=True)
        pointer.symlink_to("generations/" + target_uuid)
        protected("/var/lib/ablestack-storage/template-maintenance.json",
                  {"kind": kind, "scope": maintenance_scope, "bootHeld": True})
        protected("/run/ablestack-storage/rendered-authorization/writer.json",
                  {"scope": scope, "maintenanceScope": maintenance_scope})
        env = {**os.environ, "ABLESTACK_STORAGE_RENDERED_REPLAY": str(target),
               "ABLESTACK_STORAGE_RENDERED_FROM": str(baseline),
               "ABLESTACK_STORAGE_RENDERED_BUDGET": "120"}
        payload_path = proof / "enabled.json"
        protected(payload_path, payload)
        metadata_before = tuple(getattr(backing.stat(), key)
                                for key in ("st_uid", "st_gid", "st_mode"))
        data_before = hashlib.sha256((backing / "retained.txt").read_bytes()).hexdigest()
        applied = command([str(CLI), "nfs", "export", "apply", str(payload_path)], env=env, timeout=150)
        (proof / "apply-response.json").write_text(applied.stdout)
        response = json.loads(applied.stdout)
        self.assertIs(response["success"], True, response)
        self.assertIs(response["detailVerified"], True)
        endpoint = response["runtimeEndpoints"][0]
        pid = endpoint["pid"]
        self.assertTrue(NfsEndpointReadiness.listener_owned(endpoint))
        self.assertTrue(os.path.samefile(alias, volume))
        self.assertEqual(export_uuid, next(iter(json.loads(ALIAS_RECEIPT.read_text()))))
        client = proof / "client"
        client.mkdir()
        command(["mount", "-t", "nfs", "-o", "vers=4.1,port=2049,proto=tcp,noac",
                 "192.0.2.1:/" + alias.name, str(client)], timeout=25)
        self.assertEqual((client / "retained.txt").read_bytes(), (backing / "retained.txt").read_bytes())
        (client / "client-created.txt").write_text("written through native-managed NFS\n")
        self.assertEqual((backing / "client-created.txt").read_text(),
                         "written through native-managed NFS\n")
        command(["umount", str(client)])
        receipt_before = ALIAS_RECEIPT.read_bytes()
        busy_child = subprocess.Popen(["sleep", "120"], cwd=alias) if busy else None
        try:
            pointer.unlink()
            pointer.symlink_to("generations/" + baseline_uuid)
            journal["phase"] = "ROLLING_BACK"
            protected(STORE / "activation.json", journal)
            configs = list(Path("/etc/ganesha/ablestack-storage").glob("*.conf"))
            self.assertEqual(1, len(configs))
            self.assertTrue(configs[0].is_symlink())
            self.assertFalse(configs[0].exists(), "test must exercise a dangling current configuration")
            protected(proof / "disabled.json", disabled)
            inverse_env = {**env, "ABLESTACK_STORAGE_RENDERED_REPLAY": str(baseline),
                           "ABLESTACK_STORAGE_RENDERED_FROM": str(target)}
            inverse = command([str(CLI), "nfs", "export", "apply", str(proof / "disabled.json")],
                              expected=1 if busy else 0, env=inverse_env, timeout=150)
            (proof / "rollback-response.json").write_text(inverse.stdout)
            result = json.loads(inverse.stdout)
            self.assertFalse(NfsEndpointReadiness.listener_owned(endpoint) if Path("/proc/" + str(pid) + "/fd").exists() else False,
                             "owned listener survives inverse")
            self.assertFalse(Path("/proc/" + str(pid) + "/cmdline").read_bytes()
                             if Path("/proc/" + str(pid) + "/cmdline").exists() else b"")
            record = {"maintenanceKind": kind, "busyUnmount": busy, "cliSha256": self.pin,
                      "actualNativeCli": True, "actualGaneshaDbusAndKernelNfs": True,
                      "applyVerified": response["success"], "clientReadWrite": True,
                      "danglingCurrentExercised": True, "ownedDaemonStopped": True,
                      "rollbackSuccess": result["success"]}
            if busy:
                self.assertFalse(result["success"])
                self.assertEqual("NFS_ALIAS_UNMOUNT_FAILED", result["lastFailure"]["code"])
                self.assertEqual(receipt_before, ALIAS_RECEIPT.read_bytes())
                self.assertTrue(os.path.samefile(alias, volume))
                self.assertIn("ablestack-storage:" + export_uuid + ":alias", Path("/etc/fstab").read_text())
                record.update(failureCode=result["lastFailure"]["code"], aliasReceiptPreserved=True,
                              busyMountRetained=True, fstabReceiptPreserved=True)
                # Retry the exact approved inverse after releasing only our
                # owned blocker. The stopped daemon/config must stay absent;
                # scope pins and the preserved alias receipt are unchanged.
                self.assertFalse(os.path.lexists(configs[0]))
                pins = {path: path.read_bytes() for path in (
                    STORE / "activation.json",
                    Path("/var/lib/ablestack-storage/template-maintenance.json"),
                    Path("/run/ablestack-storage/rendered-authorization/writer.json"))}
                busy_child.terminate()
                busy_child.wait(timeout=5)
                busy_child = None
                self.assertEqual(receipt_before, ALIAS_RECEIPT.read_bytes())
                resumed = command([str(CLI), "nfs", "export", "apply", str(proof / "disabled.json")],
                                  env=inverse_env, timeout=150)
                (proof / "rollback-resumed-response.json").write_text(resumed.stdout)
                resumed_result = json.loads(resumed.stdout)
                self.assertTrue(resumed_result["success"], resumed_result)
                self.assertEqual({}, json.loads(ALIAS_RECEIPT.read_text()))
                self.assertFalse(os.path.samefile(alias, volume))
                self.assertNotIn("ablestack-storage:" + export_uuid + ":alias", Path("/etc/fstab").read_text())
                self.assertFalse(os.path.lexists(configs[0]))
                self.assertEqual("0", command(["systemctl", "show", "-p", "MainPID", "--value",
                                              "ablestack-storage-ganesha@" + endpoint["key"] + ".service"]).stdout.strip())
                for path, before in pins.items():
                    self.assertEqual(before, path.read_bytes())
                record.update(inverseResumedAfterOwnedBlockerRelease=True,
                              originalScopePinsUnchanged=True, stoppedUnitAndConfigStillAbsent=True,
                              resumedRollbackSuccess=True, resumedAliasUnmountVerified=True,
                              resumedAliasReceiptCleared=True, resumedFstabReceiptCleared=True)
            else:
                self.assertTrue(result["success"], result)
                self.assertEqual({}, json.loads(ALIAS_RECEIPT.read_text()))
                self.assertFalse(os.path.samefile(alias, volume))
                self.assertNotIn("ablestack-storage:" + export_uuid + ":alias", Path("/etc/fstab").read_text())
                record.update(aliasUnmountVerified=True, aliasReceiptCleared=True)
            self.assertEqual(data_before, hashlib.sha256((backing / "retained.txt").read_bytes()).hexdigest())
            self.assertEqual(metadata_before, tuple(getattr(backing.stat(), key)
                                                  for key in ("st_uid", "st_gid", "st_mode")))
            record.update(originalDataAndPosixPreserved=True)
            self.results.append(record)
            (proof / "result-public.json").write_text(json.dumps(record, indent=2, sort_keys=True) + "\n")
        finally:
            if busy_child:
                busy_child.terminate()
                busy_child.wait(timeout=5)
                command(["umount", str(alias)])
                protected(ALIAS_RECEIPT, {})
            command(["umount", str(volume)])
            try:
                while os.waitpid(-1, os.WNOHANG)[0]:
                    pass
            except ChildProcessError:
                pass

    def test_root_empty_baseline_inverse(self):
        self.run_lifecycle("ROOT")

    def test_service_empty_baseline_inverse(self):
        self.run_lifecycle("SERVICE")

    def test_service_busy_unmount_preserves_receipt(self):
        self.run_lifecycle("SERVICE", busy=True)

    @classmethod
    def tearDownClass(cls):
        (cls.proof / "result-public.json").write_text(json.dumps({
            "success": len(cls.results) == 3, "cases": cls.results,
            "scope": "native NFS CLI lifecycle with seeded protected generation/maintenance records; full RenderedDriver not executed",
            "systemctlOnlyShim": True, "liveClusterConnections": 0
        }, sort_keys=True, indent=2) + "\n")


if __name__ == "__main__":
    unittest.main()
