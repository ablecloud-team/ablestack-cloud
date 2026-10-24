# SPDX-License-Identifier: Apache-2.0
"""Initial DATA preparation safety contracts; never formats a real device."""
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

LIB = Path(__file__).resolve().parents[1] / "debian/usr/local/lib/ablestack-storage"
sys.path.insert(0, str(LIB))
import volume_preparation as vp

V = "11111111-2222-4333-8444-555555555555"
R = "22222222-2222-4333-8444-555555555555"
O = "33333333-2222-4333-8444-555555555555"
M = "44444444-2222-4333-8444-555555555555"
P = dict(operationId=O, instanceUuid=M, vmUuid=M, rootVolumeUuid=R, volumeUuid=V,
         volumeName="new-data", volumeSizeBytes=20*1024**3, filesystem="xfs", formatTimeoutSeconds=300)


def observation(fs="", mounted=False):
    return dict(devicePath="/dev/sdb", filesystem=fs, filesystemUuid="fs-uuid" if fs else "",
                mountPath="/srv/ablestack-storage/volumes/"+V, mounted=mounted)


class StoreTest(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory()
        self.store=vp.Store(V, Path(self.tmp.name)/"private")
    def tearDown(self): self.tmp.cleanup()
    def initial(self, **kw):
        s=dict(version=1, operationId=O, volumeUuid=V, payload=P.copy(),
               status="RUNNING",phase="QUEUED",unit="unit",bootId="boot",
               formatEverStarted=False,formatCount=0)
        s.update(kw)
        return self.store.update({},s)

    def test_missing_status_does_not_create_files(self):
        v=vp.snapshot(P,self.store)
        self.assertFalse(v["canResume"]);self.assertFalse(self.store.root.exists())

    def test_atomic_private_roundtrip_preserves_prior_fields(self):
        self.initial()
        self.store.update({"phase":"FORMATTING","formatEverStarted":True})
        self.store.update({"heartbeatAt":12})
        s=self.store.read();self.assertTrue(s["formatEverStarted"]);self.assertEqual(s["phase"],"FORMATTING")
        self.assertEqual(self.store.path.stat().st_mode & 0o777,0o600)

    def test_corrupt_record_fails_closed(self):
        self.store.ensure();self.store.path.write_text("{");self.store.path.chmod(0o600)
        with self.assertRaises(vp.PreparationError): self.store.read()

    def test_symlink_journal_never_followed(self):
        self.store.ensure()
        target=Path(self.tmp.name)/"source";target.write_text("unchanged")
        self.store.path.symlink_to(target)
        with self.assertRaises((vp.PreparationError,OSError)):self.store.read()
        self.assertEqual(target.read_text(),"unchanged")

    def test_other_operation_rejected(self):
        self.initial()
        with self.assertRaises(vp.PreparationError):vp.validate_record(self.store.read(),dict(P,operationId=R))

    def test_alive_worker_can_only_be_observed(self):
        self.initial(formatEverStarted=True)
        with patch.object(vp,"alive",return_value=True),patch.object(vp,"inspect",return_value=observation()):
            v=vp.snapshot(P,self.store)
        self.assertEqual(v["status"],"RUNNING");self.assertFalse(v["canResume"])

    def test_started_then_empty_is_not_reformattable(self):
        self.initial(formatEverStarted=True,phase="FORMATTING")
        with patch.object(vp,"alive",return_value=False),patch.object(vp,"inspect",return_value=observation()):
            v=vp.snapshot(P,self.store)
        self.assertEqual(v["status"],"RECOVERY_REQUIRED");self.assertFalse(v["canResume"])

    def test_supported_interrupted_result_resumes_verification_only(self):
        self.initial(formatEverStarted=True,phase="FORMATTING",formatCount=1)
        with patch.object(vp,"alive",return_value=False),patch.object(vp,"inspect",return_value=observation("xfs")):
            v=vp.snapshot(P,self.store)
        self.assertTrue(v["canResume"]);self.assertEqual(v["formatCount"],1)

    def test_ready_reboot_keeps_uuid_and_count(self):
        self.initial(formatEverStarted=True,phase="PREPARED",filesystemUuid="fs-uuid",formatCount=1)
        with patch.object(vp,"alive",return_value=False),patch.object(vp,"inspect",return_value=observation("xfs",True)):
            v=vp.snapshot(P,self.store)
        self.assertEqual(v["status"],"PREPARED");self.assertTrue(v["canResume"]);self.assertEqual(v["formatCount"],1)

    def test_changed_filesystem_uuid_blocks(self):
        self.initial(formatEverStarted=True,filesystemUuid="other")
        with patch.object(vp,"alive",return_value=False),patch.object(vp,"inspect",return_value=observation("xfs",True)):
            v=vp.snapshot(P,self.store)
        self.assertFalse(v["canResume"]);self.assertEqual(v["errorCode"],"FILESYSTEM_IDENTITY_CHANGED")

    def test_failed_health_is_not_retried_by_status(self):
        self.initial(formatEverStarted=True,errorCode="FILESYSTEM_INVALID")
        with patch.object(vp,"alive",return_value=False),patch.object(vp,"inspect",return_value=observation("xfs")):
            self.assertFalse(vp.snapshot(P,self.store)["canResume"])

    def test_start_replay_does_not_launch_again(self):
        self.initial(formatEverStarted=True)
        with patch.object(vp,"alive",return_value=False),patch.object(vp,"inspect",return_value=observation("xfs")),patch.object(vp,"run") as run:
            self.assertEqual(vp.launch(P,store=self.store)["status"],"OBSERVATION_PENDING")
            run.assert_not_called()

    def test_first_format_intent_is_durable_before_command(self):
        self.initial()
        c=object.__new__(vp.PreparationContext);c.store=self.store;c.payload=P
        with patch.object(vp,"inspect",return_value=observation()):
            c.before_format("/dev/sdb","xfs")
            with self.assertRaises(vp.PreparationError):c.before_format("/dev/sdb","xfs")
        self.assertTrue(self.store.read()["formatEverStarted"]);self.assertEqual(self.store.read()["formatCount"],1)

    def test_existing_signature_cannot_be_initialized(self):
        self.initial()
        c=object.__new__(vp.PreparationContext);c.store=self.store;c.payload=P
        with patch.object(vp,"inspect",return_value=observation("ext4")):
            with self.assertRaises(vp.PreparationError):c.before_format("/dev/sdb","xfs")
        self.assertEqual(self.store.read()["formatCount"],0)

    def test_parallel_worker_lock_excludes_second_starter(self):
        self.initial()
        with self.store.lock("job"):
            with self.assertRaises(BlockingIOError):
                with self.store.lock("job",blocking=False):pass

    def test_probe_timeout_kills_and_reaps_only_own_child(self):
        c=object.__new__(vp.PreparationContext);c.store=self.store;c.payload=P;self.initial()
        with self.assertRaises(vp.PreparationError):c.run(["/bin/sleep","5"],timeout=.03)

    def test_readonly_health_uses_no_repair_flags(self):
        c=object.__new__(vp.PreparationContext);c.store=self.store;c.payload=P
        with patch.object(c,"run",return_value=subprocess.CompletedProcess([],0,"","")) as run:
            c.health("/dev/sdb","xfs");self.assertEqual(run.call_args.args[0],["xfs_repair","-n","/dev/sdb"])
            c.health("/dev/sdb","ext4");self.assertEqual(run.call_args.args[0],["e2fsck","-f","-n","/dev/sdb"])


class IdentityTest(unittest.TestCase):
    def devices(self,serial=None,fs=""):
        return [dict(name="sda",path="/dev/sda",type="disk",size=5*1024**3,serial=R.replace("-","")[:20],fstype="ext4",mountpoint="/"),
                dict(name="sdb",path="/dev/sdb",type="disk",size=P["volumeSizeBytes"],serial=serial or V.replace("-","")[:20],fstype=fs)]
    def fake(self,devices,fs="",signatures=None,mount=None):
        def run(args,timeout):
            if args[0]=="lsblk":return subprocess.CompletedProcess(args,0,json.dumps({"blockdevices":devices}),"")
            if args[0]=="findmnt":return subprocess.CompletedProcess(args,0 if mount else 1,mount or "","")
            if args[0]=="blkid":return subprocess.CompletedProcess(args,0 if fs else 2,"TYPE="+fs+"\nUUID=fs-uuid\n" if fs else "","")
            if args[0]=="wipefs":return subprocess.CompletedProcess(args,0,json.dumps({"signatures":signatures or []}),"")
            raise AssertionError(args)
        return run
    def test_exact_serial_blank_allowed(self):
        d=self.devices()
        with patch.object(vp,"run",side_effect=self.fake(d)),patch("volume_identity.glob.glob",return_value=[]):
            self.assertFalse(vp.inspect(P)["filesystem"])
    def test_wrong_serial_same_size_never_falls_back(self):
        d=self.devices("another-volume")
        with patch.object(vp,"run",side_effect=self.fake(d)),patch("volume_identity.glob.glob",return_value=[]):
            with self.assertRaises(vp.VolumeIdentityError):vp.inspect(P)
    def test_wrong_root_blocks(self):
        d=self.devices();d[0]["serial"]="other-root"
        with patch.object(vp,"run",side_effect=self.fake(d)):
            with self.assertRaises(vp.PreparationError):vp.inspect(P)
    def test_partial_unknown_signature_blocks(self):
        d=self.devices()
        with patch.object(vp,"run",side_effect=self.fake(d,signatures=[{"type":"unknown"}])),patch("volume_identity.glob.glob",return_value=[]):
            with self.assertRaises(vp.PreparationError):vp.inspect(P)
    def test_foreign_mount_blocks(self):
        d=self.devices();d[1]["mountpoint"]="/other"
        with patch.object(vp,"run",side_effect=self.fake(d)),patch("volume_identity.glob.glob",return_value=[]):
            with self.assertRaises((vp.PreparationError,vp.VolumeIdentityError)):vp.inspect(P)
    def test_size_mismatch_blocks(self):
        d=self.devices();d[1]["size"]+=1024
        with patch.object(vp,"run",side_effect=self.fake(d)),patch("volume_identity.glob.glob",return_value=[]):
            with self.assertRaises(vp.PreparationError):vp.inspect(P)



class WorkerTest(StoreTest):
    def test_worker_reboot_resume_calls_only_mount_existing_and_preserves_format_count(self):
        self.initial(formatEverStarted=True,formatCount=1,filesystemUuid="fs-uuid")
        captured=[]
        def helper(args,timeout=20):
            self.assertEqual(args[:4],[vp.HELPER,"volume","attach","inspect"])
            payload=json.loads(Path(args[4]).read_text());captured.append(payload)
            return subprocess.CompletedProcess(args,0,json.dumps(dict(volumeUuid=V,persistentMount=True,filesystemUuid="fs-uuid",filesystem="xfs",volumeMountPath=observation("xfs")["mountPath"])),"")
        with patch.object(vp,"Store",return_value=self.store),patch.object(vp,"inspect",return_value=observation("xfs")),patch.object(vp,"alive",return_value=True),patch.object(vp.PreparationContext,"health") as health,patch.object(vp,"run",side_effect=helper):
            vp.worker(V,O)
        self.assertEqual(captured[0]["importMode"],"MOUNT_EXISTING")
        self.assertEqual(self.store.read()["status"],"PREPARED")
        self.assertEqual(self.store.read()["formatCount"],1);health.assert_called_once_with("/dev/sdb","xfs")
        self.assertFalse(list(self.store.root.glob("*.input")))
    def test_worker_partial_after_prior_format_never_calls_helper(self):
        self.initial(formatEverStarted=True,formatCount=1)
        with patch.object(vp,"Store",return_value=self.store),patch.object(vp,"inspect",return_value=observation()),patch.object(vp,"run") as execute:
            vp.worker(V,O)
        self.assertEqual(self.store.read()["errorCode"],"FORMAT_RESULT_UNKNOWN")
        self.assertEqual(self.store.read()["formatCount"],1);execute.assert_not_called()
    def test_worker_failed_helper_keeps_original_journal_and_data_intent(self):
        self.initial()
        with patch.object(vp,"Store",return_value=self.store),patch.object(vp,"inspect",return_value=observation()),patch.object(vp,"run",return_value=subprocess.CompletedProcess([],1,"","fixture failed")):
            vp.worker(V,O)
        s=self.store.read();self.assertEqual(s["errorCode"],"PREPARATION_FAILED")
        self.assertEqual(s["payload"],P);self.assertFalse(s["formatEverStarted"])
    def test_worker_changed_filesystem_uuid_blocks_before_mount(self):
        self.initial(formatEverStarted=True,formatCount=1,filesystemUuid="different")
        with patch.object(vp,"Store",return_value=self.store),patch.object(vp,"inspect",return_value=observation("xfs")),patch.object(vp,"run") as execute:
            vp.worker(V,O)
        self.assertEqual(self.store.read()["errorCode"],"IDENTITY_CHANGED");execute.assert_not_called()
    def test_first_launch_records_original_intent_before_start(self):
        def launch(args,timeout=20):
            self.assertEqual(args[0],"systemd-run")
            self.assertEqual(self.store.read()["payload"],P)
            self.assertFalse(self.store.read()["formatEverStarted"])
            return subprocess.CompletedProcess(args,0,"","")
        with patch.object(vp,"inspect",return_value=observation()),patch.object(vp,"alive",return_value=False),patch.object(vp,"run",side_effect=launch):
            result=vp.launch(P,store=self.store)
        self.assertEqual(result["status"],"RUNNING")
        self.assertEqual(result["formatCount"],0)



class ObservationPathTest(unittest.TestCase):
    """Real 10TiB failure record; execute snapshot/identity/main, not a mocked inspect."""
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.record = json.loads((Path(__file__).parent / "fixtures/preparation_active_10t.json").read_text())
        self.payload = self.record["payload"]
        self.store = vp.Store(self.payload["volumeUuid"], Path(self.tmp.name) / "private")
        self.store.update({}, self.record)
        self.calls = []
        self.active = True
        self.fs = ""
        self.fs_uuid = "prepared-uuid"
        self.mount = None
        self.devices = [
            dict(name="sda", path="/dev/sda", type="disk", size=5242880000,
                 serial=self.payload["rootVolumeUuid"].replace("-", "")[:20], mountpoint="/"),
            dict(name="sdb", path="/dev/sdb", type="disk", size=10*1024**4,
                 serial=self.payload["volumeUuid"].replace("-", "")[:20])]
    def tearDown(self):
        self.tmp.cleanup()
    def command(self, args, timeout=20):
        self.calls.append(args)
        cmd = args[0]
        if cmd == "systemctl":
            return subprocess.CompletedProcess(args, 0 if self.active else 3,
                                               "active\n" if self.active else "inactive\n", "")
        if cmd == "lsblk":
            return subprocess.CompletedProcess(args, 0, json.dumps({"blockdevices": self.devices}), "")
        if cmd == "findmnt":
            return subprocess.CompletedProcess(args, 0 if self.mount else 1, self.mount or "", "")
        if cmd in ("blkid", "wipefs"):
            if not self.fs:
                # Exact observed failure: a read probe waits behind mkfs BLKDISCARD.
                raise subprocess.TimeoutExpired(args, 8)
            return subprocess.CompletedProcess(args, 0, "TYPE="+self.fs+"\nUUID="+self.fs_uuid+"\n", "")
        raise AssertionError("Unexpected command: "+str(args))
    def execute(self, cli=False):
        from contextlib import redirect_stdout
        import io
        before = self.store.path.read_bytes()
        with patch.object(vp, "run", side_effect=self.command), \
             patch.object(vp, "boot_id", return_value=self.record["bootId"]), \
             patch.object(vp, "ticks", return_value=self.record["workerStartTicks"]), \
             patch("volume_identity.glob.glob", return_value=[]):
            if cli:
                payload = Path(self.tmp.name) / "request.json"
                payload.write_text(json.dumps(self.payload))
                output = io.StringIO()
                with patch.object(vp, "Store", return_value=self.store), \
                     patch.object(sys, "argv", ["volume_preparation.py", "status", str(payload)]), \
                     redirect_stdout(output):
                    vp.main()
                result = json.loads(output.getvalue())
            else:
                result = vp.snapshot(self.payload, self.store)
        self.assertEqual(self.store.path.read_bytes(), before)
        self.assertFalse(any(args[0] in ("systemd-run", "mkfs.xfs", "mkfs.ext4", vp.HELPER) for args in self.calls))
        return result
    def test_active_10t_mkfs_never_probes_filesystem_and_preserves_progress(self):
        result = self.execute()
        self.assertEqual(result["status"], "RUNNING")
        self.assertEqual(result["phase"], "FORMATTING")
        for key in ("formatCount", "formatStartedAt", "formatDeadlineAt", "heartbeatAt", "operationId"):
            self.assertEqual(result[key], self.record[key])
        self.assertEqual(result["formatCount"], 1)
        self.assertFalse(result["canResume"])
        self.assertTrue(result["observationAvailable"])
        self.assertNotIn("payload", result)
        self.assertNotIn("workerPid", result)
        self.assertFalse(any(args[0] in ("blkid", "wipefs") for args in self.calls))
        columns = next(args[-1] for args in self.calls if args[0] == "lsblk").split(",")
        self.assertNotIn("FSTYPE", columns)
        self.assertNotIn("UUID", columns)
    def test_active_cli_serialization_keeps_running_phase_and_count(self):
        result = self.execute(cli=True)
        self.assertEqual((result["status"], result["phase"], result["formatCount"]),
                         ("RUNNING", "FORMATTING", 1))
        self.assertFalse(result["canResume"])
    def test_active_identity_mismatches_still_fail_closed_before_signature_probe(self):
        changes = [("root", lambda: self.devices[0].update(serial="wrong-root")),
                   ("data", lambda: self.devices[1].update(serial="wrong-data")),
                   ("size", lambda: self.devices[1].update(size=10*1024**4+512)),
                   ("foreign-mount", lambda: self.devices[1].update(mountpoint="/foreign")),
                   ("partitions", lambda: self.devices[1].update(children=[dict(name="sdb1", type="part", path="/dev/sdb1", size=1)]))]
        original = json.loads(json.dumps(self.devices))
        for name, change in changes:
            with self.subTest(name=name):
                self.devices = json.loads(json.dumps(original)); self.calls.clear(); change()
                result = self.execute(cli=True)
                self.assertEqual(result["status"], "RECOVERY_REQUIRED")
                self.assertFalse(result["canResume"])
                self.assertFalse(any(args[0] in ("blkid", "wipefs") for args in self.calls))
    def test_inactive_signature_timeout_cli_is_unavailable_not_formatter_failure(self):
        self.active = False
        result = self.execute(cli=True)
        self.assertEqual(result["status"], "OBSERVATION_PENDING")
        self.assertEqual(result["errorCode"], "OBSERVATION_TIMEOUT")
        self.assertEqual(result["phase"], "FORMATTING")
        self.assertEqual(result["formatCount"], 1)
        self.assertEqual(result["heartbeatAt"], self.record["heartbeatAt"])
        self.assertFalse(result["observationAvailable"]); self.assertFalse(result["canResume"])
        self.assertTrue(any(args[0] == "blkid" for args in self.calls))
    def test_active_identity_probe_timeout_preserves_journal_but_never_confirms_running(self):
        original = self.command
        def timeout(args, seconds=20):
            if args[0] == "lsblk":
                self.calls.append(args); raise subprocess.TimeoutExpired(args, 8)
            return original(args, seconds)
        self.command = timeout
        result = self.execute(cli=True)
        self.assertEqual(result["status"], "OBSERVATION_PENDING")
        self.assertEqual(result["formatCount"], 1)
        self.assertFalse(result["observationAvailable"]); self.assertFalse(result["canResume"])
    def test_worker_exit_during_lightweight_observation_requires_full_signature_verification(self):
        original = self.command
        def exiting(args, timeout=20):
            result = original(args, timeout)
            if args[0] == "findmnt" and self.active:
                self.active = False; self.fs = "xfs"; self.mount = "/dev/sdb"
                self.store.update({"phase": "PREPARED", "filesystemUuid": self.fs_uuid})
            return result
        self.command = exiting
        # The worker legitimately updates its own journal in this race fixture.
        with patch.object(vp, "run", side_effect=self.command), \
             patch.object(vp, "boot_id", return_value=self.record["bootId"]), \
             patch.object(vp, "ticks", return_value=self.record["workerStartTicks"]):
            result = vp.snapshot(self.payload, self.store)
        self.assertEqual(result["status"], "PREPARED")
        self.assertEqual(result["formatCount"], 1)
        self.assertTrue(any(args[0] == "blkid" for args in self.calls))
    def test_inactive_partial_signature_never_becomes_resumable(self):
        self.active = False
        original = self.command
        def partial(args, timeout=20):
            if args[0] == "blkid":
                self.calls.append(args); return subprocess.CompletedProcess(args, 2, "", "")
            if args[0] == "wipefs":
                self.calls.append(args)
                return subprocess.CompletedProcess(args, 0, '{"signatures":[{"type":"unknown"}]}', "")
            return original(args, timeout)
        self.command = partial
        result = self.execute(cli=True)
        self.assertEqual(result["status"], "RECOVERY_REQUIRED")
        self.assertFalse(result["canResume"])
    def test_inactive_changed_filesystem_uuid_remains_blocked(self):
        self.active = False; self.fs = "xfs"; self.mount = "/dev/sdb"
        self.store.update({"filesystemUuid": "original-uuid", "phase": "PREPARED"})
        result = self.execute(cli=True)
        self.assertEqual(result["errorCode"], "FILESYSTEM_IDENTITY_CHANGED")
        self.assertFalse(result["canResume"])
    def test_active_journal_payload_mismatch_does_not_query_or_launch(self):
        self.store.update({"payload": dict(self.payload, rootVolumeUuid="00000000-0000-4000-8000-000000000001")})
        result = self.execute(cli=True)
        self.assertEqual(result["errorCode"], "IDENTITY_CHANGED")
        self.assertFalse(result["canResume"]); self.assertFalse(self.calls)


if __name__=="__main__":unittest.main()
