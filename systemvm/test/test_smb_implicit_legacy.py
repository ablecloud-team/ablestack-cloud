# Licensed under the Apache License, Version 2.0. See NOTICE.
import importlib.util
import base64
import json
import os
from pathlib import Path
import shutil
import socket
import subprocess
import tempfile
import time
import unittest
from unittest.mock import patch
import uuid

MODULE = Path(os.environ.get("CONFIGURATION_MODULE", Path(__file__).resolve().parents[1] /
              "debian/usr/local/lib/ablestack-storage/configuration_transaction.py"))
spec = importlib.util.spec_from_file_location("legacy_test_configuration", MODULE)
m = importlib.util.module_from_spec(spec); spec.loader.exec_module(m)

class ImplicitLegacyTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.controller = m.SmbListenerProfiles(self.root / "profiles", self.root / "base.conf")
        self.controller.base.write_text("[global]\n smb ports = 445\n")
        self.controller.base.chmod(0o600)
        self.identity = str(uuid.uuid4())
        self.before = {"instanceUuid": self.identity, "enabled": True, "listeners": []}
        m.atomic_json(self.controller.desired, self.before)
        self.process = {"pid": os.getpid(), "start": "start", "boot": "boot", "unitState": "enabled"}
        self.sockets = [["0.0.0.0", 445], ["::", 445]]
        self.process_mock = patch.object(self.controller, "legacy_process", return_value=self.process).start()
        self.socket_mock = patch.object(self.controller, "legacy_sockets", return_value=self.sockets).start()
        self.commands = patch.object(m, "checked", side_effect=lambda args, **kw:
            b"445\n" if "--parameter-name=smb ports" in args else b"" if "--parameter-name=interfaces" in args else b"No\n").start()
        self.addCleanup(patch.stopall)
    def tearDown(self):
        self.temp.cleanup()
    def snapshot(self):
        files = {}
        for path in (self.controller.base, self.controller.desired):
            st = path.stat()
            files[str(path)] = {"content": base64.b64encode(path.read_bytes()).decode(),
                                "uid": st.st_uid, "gid": st.st_gid, "mode": st.st_mode & 0o777}
        return {"files": files, "smbLegacyBoot": "boot", "smbListeners": {"observedEpoch": time.time() + 1}}
    def test_zero_listener_default_proof_does_not_fabricate_model_endpoints(self):
        proof = self.controller.implicit_legacy(self.before)
        self.assertEqual("IMPLICIT_LEGACY", proof["mode"])
        self.assertEqual(self.sockets, proof["sockets"])
        self.assertEqual([], self.before["listeners"])
        self.assertFalse(self.controller.manifest.exists())
    def test_foreign_instance_rejected(self):
        with self.assertRaisesRegex(m.Rejected, "SMB_LEGACY_IDENTITY_UNCONFIRMED"):
            self.controller.implicit_legacy(dict(self.before, instanceUuid=str(uuid.uuid4())))
    def test_unowned_writable_configuration_rejected(self):
        self.controller.base.chmod(0o666)
        with self.assertRaisesRegex(m.Rejected, "SMB_LEGACY_CONFIGURATION_UNCONFIRMED"):
            self.controller.implicit_legacy(self.before)
    def test_symlink_configuration_rejected(self):
        target=self.root/"target";self.controller.base.rename(target);self.controller.base.symlink_to(target)
        with self.assertRaisesRegex(m.Rejected, "SMB_LEGACY_CONFIGURATION_UNCONFIRMED"):
            self.controller.implicit_legacy(self.before)
    def test_changed_file_after_observation_rejected(self):
        proof=self.controller.implicit_legacy(self.before);self.controller.base.write_text("changed")
        with self.assertRaisesRegex(m.Rejected, "SMB_LEGACY_BEFORE_IMAGE_CHANGED"):
            self.controller.verify_implicit_legacy(proof)
    def test_different_pid_is_not_a_staged_ownership_receipt(self):
        proof=self.controller.implicit_legacy(self.before)
        self.process_mock.return_value=dict(self.process,pid=self.process["pid"]+1)
        with self.assertRaisesRegex(m.Rejected,"SMB_LEGACY_PROCESS_CHANGED"):
            self.controller.verify_implicit_legacy(proof)
    def test_ipv6_binding_is_part_of_restore_proof(self):
        proof=self.controller.implicit_legacy(self.before);self.socket_mock.return_value=[["0.0.0.0",445]]
        with self.assertRaisesRegex(m.Rejected,"SMB_LEGACY_BINDING_UNCONFIRMED"):
            self.controller.verify_implicit_legacy(proof)
    def test_unit_enable_state_is_part_of_restore_proof(self):
        proof=self.controller.implicit_legacy(self.before)
        self.process_mock.return_value=dict(self.process,unitState="disabled")
        with self.assertRaisesRegex(m.Rejected,"SMB_LEGACY_UNIT_STATE_CHANGED"):
            self.controller.verify_implicit_legacy(proof)
    def test_existing_old_journal_is_verified_without_start_or_stop(self):
        snap=self.snapshot()
        with patch.object(self.controller,"ctl") as ctl:
            self.controller.verify_legacy_restored(self.before,snap)
            ctl.assert_not_called()
    def test_old_journal_does_not_capture_changed_files_as_the_before_image(self):
        snap=self.snapshot();self.controller.base.write_text("tampered")
        with self.assertRaisesRegex(m.Rejected,"SMB_LEGACY_BEFORE_IMAGE_CHANGED"):
            self.controller.verify_legacy_restored(self.before,snap)
    def test_old_journal_rejects_newer_process_or_different_boot(self):
        snap=self.snapshot();snap["smbListeners"]["observedEpoch"]=1
        with self.assertRaisesRegex(m.Rejected,"SMB_LEGACY_PROCESS_CHANGED"):
            self.controller.verify_legacy_restored(self.before,snap)
        snap=self.snapshot();snap["smbLegacyBoot"]="another-boot"
        with self.assertRaisesRegex(m.Rejected,"SMB_LEGACY_PROCESS_CHANGED"):
            self.controller.verify_legacy_restored(self.before,snap)
    def test_structured_child_code_survives_rollback_without_raw_output(self):
        m.atomic_json(self.root/"original-runner-result.json",{"result":{"code":"SMB_LEGACY_IDENTITY_UNCONFIRMED","password":"must-not-copy","message":"raw command"}})
        value={"originalfailure":{"code":"PROTOCOL_APPLY_FAILED","phase":"APPLYING"}}
        m.retain_runner_failure_detail(value,self.root)
        self.assertEqual({"code":"SMB_LEGACY_IDENTITY_UNCONFIRMED","stage":"SMB_LISTENER"},value["originalfailure"]["detail"])
        m.atomic_json(self.root/"original-runner-result.json",{"result":{"code":"SMB_ANOTHER_FAILURE"}})
        m.retain_runner_failure_detail(value,self.root)
        self.assertEqual("SMB_LEGACY_IDENTITY_UNCONFIRMED",value["originalfailure"]["detail"]["code"])
        self.assertNotIn("password",json.dumps(value));self.assertNotIn("raw command",json.dumps(value))
    def test_unstructured_child_text_is_not_exposed(self):
        m.atomic_json(self.root/"original-runner-result.json",{"result":{"code":"SMB_SECRET\nraw"}})
        value={"originalfailure":{"code":"PROTOCOL_APPLY_FAILED","phase":"APPLYING"}}
        m.retain_runner_failure_detail(value,self.root)
        self.assertNotIn("detail",value["originalfailure"])
    def test_drain_observes_an_actual_established_socket(self):
        server=socket.socket();client=socket.socket()
        try:
            server.bind(("127.0.0.1",0));server.listen()
            client.connect(server.getsockname());accepted,_=server.accept()
            try:
                proof={"process":{"pid":os.getpid()},"sockets":[["127.0.0.1",server.getsockname()[1]]]}
                self.assertGreater(self.controller.implicit_connections(proof),0)
            finally:accepted.close()
        finally:client.close();server.close()

@unittest.skipUnless(os.environ.get("RUN_SYSTEMVM_SMB_LISTENER_TESTS")=="1","explicit isolated SystemVM execution")
class NativeImplicitLegacyTests(unittest.TestCase):
    def test_real_samba_handoff_rollback_drain_interruption_and_boot(self):
        with tempfile.TemporaryDirectory(prefix="legacy-driver-") as name:
            root=Path(name);shutil.copy2(MODULE,root/"configuration_transaction.py")
            shutil.copy2(Path(__file__).parent/"fixtures/smb_implicit_legacy_native_probe.py",root/"probe.py")
            run=subprocess.run(["python3","-B",str(root/"probe.py")],capture_output=True,text=True,timeout=180)
            self.assertEqual(0,run.returncode,run.stderr)
            result=json.loads((root/"legacy-native-result.json").read_text())
            self.assertTrue(result["ok"]);self.assertTrue(result["protectedUnchanged"])
            self.assertEqual(9,len(result["checks"]))

if __name__=="__main__": unittest.main()
