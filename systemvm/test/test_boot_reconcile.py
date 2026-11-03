# Licensed under the Apache License, Version 2.0. See NOTICE.
import copy
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import threading
import time
import unittest
import uuid

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "debian/usr/local/lib/ablestack-storage"))
import boot_reconcile as boot

INSTANCE = str(uuid.uuid4())
BOOT = str(uuid.uuid4())

class Runtime:
    def __init__(self, count=10, mode="V4_ONLY", ports=(2049,2050)):
        self.count, self.mode, self.ports = count, mode, ports
        self.applies = []
        self.calls = []
        self.launches = 0
        self.fail_once = True
        self.identity_value = "verified-pid-config-fs"
        self.live = self.peak = 0
        self.lock = threading.Lock()
    def network(self): pass
    def data(self, snapshot): pass
    def launch(self, directory, unit):
        self.launches += 1
        return {"returncode":0}
    def identity(self, plan): return self.identity_value
    def prepare(self, protocol, path, seconds):
        self.applies.append(protocol)
        return {"success":True,"runtimeEndpoints":[{"key":str(p)} for p in self.ports],
                "bootProbePlan":[{"host":"0.0.0.0","port":p,"exports":[
                    {"uuid":str(uuid.UUID(int=i+1)),"name":"e"+str(i),"pseudo":"/e"+str(i),
                     "protocolMode":self.mode,"probeHost":"10.10.13.240"}
                    for i in range(self.count)]} for p in self.ports]}
    def probe(self, task, private, seconds):
        with self.lock:
            self.live += 1
            self.peak = max(self.peak, self.live)
            self.calls.append(task["key"])
        time.sleep(.002)
        with self.lock: self.live -= 1
        if self.fail_once and task["exportName"] == "e0":
            return {"state":"WAITING","code":"NFS_PROBE_TIMEOUT","cleanup":"COMPLETE"}
        return {"state":"READY","code":None,"cleanup":"COMPLETE"}

class BootRecovery(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.paths = {p:self.root/(p+".json") for p in boot.DESIRED}
        for p,path in self.paths.items():
            key = {"NFS":"exports","SMB":"shares","ISCSI":"targets","NVMEOF":"subsystems"}[p]
            path.write_text(json.dumps({"instanceUuid":INSTANCE,"enabled":True,key:[{"uuid":str(uuid.uuid4()),"name":"kept"}]}))
        self.runtime = Runtime()
        self.engine = boot.BootReconciler(self.root/"journal",self.paths,self.runtime,BOOT)
    def tearDown(self): self.temp.cleanup()
    def request(self):
        value=self.engine.status()
        return {"instanceUuid":INSTANCE,"bootId":BOOT,"expectedDesiredHash":value["desiredHash"],"protocol":"NFS"}
    def test_partial_probe_resume_never_reapplies_completed_protocols_or_completed_exports(self):
        self.engine.start()
        first=self.engine.work()
        self.assertEqual("PAUSED",first["state"])
        self.assertTrue(first["canResume"])
        self.assertEqual(["NFS","SMB","ISCSI","NVMEOF"],self.runtime.applies)
        failure=copy.deepcopy(first["firstFailure"])
        before=list(self.runtime.calls)
        self.runtime.fail_once=False
        self.engine.start(self.request())
        done=self.engine.work()
        self.assertEqual("READY",done["state"])
        self.assertFalse(done["canResume"])
        self.assertEqual(failure,done["firstFailure"])
        self.assertEqual(4,len(self.runtime.applies))
        self.assertEqual(2,len(self.runtime.calls)-len(before))
        self.assertLessEqual(self.runtime.peak,4)
    def test_duplicate_and_lost_reply_retain_same_epoch(self):
        self.engine.start();self.engine.start(self.request())
        self.assertEqual(1,self.runtime.launches)
    def test_changed_desired_or_boot_cannot_resume_old_observation(self):
        self.engine.start();self.engine.work();request=self.request()
        saved=json.loads(self.paths["NFS"].read_text());saved["exports"][0]["name"]="new"
        self.paths["NFS"].write_text(json.dumps(saved))
        self.assertEqual("BLOCKED",self.engine.status()["state"])
        with self.assertRaisesRegex(boot.RecoveryError,"BOOT_OBSERVATION_CHANGED"):self.engine.start(request)
        other=boot.BootReconciler(self.root/"journal",self.paths,self.runtime,str(uuid.uuid4()))
        self.assertEqual("NOT_OBSERVED",other.status()["state"])
    def test_disabled_raw_and_legacy_apply_observation_do_not_change_desired_hash(self):
        before=self.engine.snapshot()["desiredHash"]
        saved=json.loads(self.paths["ISCSI"].read_text());saved["lastApply"]={"success":True}
        self.paths["ISCSI"].write_text(json.dumps(saved))
        self.assertEqual(before,self.engine.snapshot()["desiredHash"])
    def test_symlinked_journal_and_unsafe_saved_state_are_rejected(self):
        outside=self.root/"outside";outside.mkdir();self.engine.root.symlink_to(outside,target_is_directory=True)
        with self.assertRaisesRegex(boot.RecoveryError,"BOOT_JOURNAL_UNSAFE"):self.engine.start()
    def test_budget_and_exact_1_10_100_plan_keep_versions_and_ports(self):
        for count in (1,10,100):
            for mode,ports in (("V4_ONLY",(2049,2050)),("V3V4_DUAL",(2049,))):
                runtime=Runtime(count,mode,ports)
                plan=runtime.prepare("NFS",None,30)
                tasks,external=boot.probe_plan(plan)
                self.assertEqual(count*2,len(tasks))
                self.assertFalse(external)
                self.assertEqual(set(ports),{v["port"] for v in tasks})
                self.assertEqual({3,4} if mode=="V3V4_DUAL" else {4},{v["version"] for v in tasks})
                self.assertLessEqual(boot.workload_budget(len(tasks),len(ports)),900)
    def test_acl_exclusion_is_not_local_success_or_a_hidden_mount(self):
        plan=self.runtime.prepare("NFS",None,30)
        for group in plan["bootProbePlan"]:
            for row in group["exports"]:row["probeHost"]=None
        tasks,external=boot.probe_plan(plan)
        self.assertFalse(tasks)
        self.assertEqual(20,len(external))
        self.assertTrue(all(v["state"]=="EXTERNAL_CLIENT_REQUIRED" for v in external))
    def test_previous_signed_layout_is_accepted_but_partial_new_layout_is_not(self):
        import runtime_updater as updater
        current=set(updater.MANAGED_FILES)
        self.assertEqual(13,len(current))
        self.assertTrue(updater.complete_managed_names(current-{"lib/ablestack-storage/boot_reconcile.py"}))
        self.assertFalse(updater.complete_managed_names(current-{"lib/ablestack-storage/volume_identity.py"}))
        self.assertFalse(updater.complete_managed_names(current-{"lib/ablestack-storage/smb_client_access.py"}))
    def test_unresolved_configuration_is_protected_without_committing_or_rolling_back(self):
        directory=self.root/"transactions"/INSTANCE/str(uuid.uuid4());directory.mkdir(parents=True)
        state=directory/"status.json";state.write_text(json.dumps({"state":"VERIFIED"}))
        before=state.read_bytes()
        with self.assertRaisesRegex(boot.RecoveryError,"BOOT_CONFIGURATION_PROTECTED"):
            boot.Runtime().eligibility(INSTANCE,self.root/"transactions")
        self.assertEqual(before,state.read_bytes())
        state.write_text(json.dumps({"state":"COMMITTED"}))
        boot.Runtime().eligibility(INSTANCE,self.root/"transactions")
    def test_journal_from_another_boot_is_never_displayed_as_ready(self):
        self.engine.start();snapshot=self.engine.snapshot();directory=self.engine.locate(snapshot)
        value=json.loads((directory/"status.json").read_text());value["bootId"]=str(uuid.uuid4())
        (directory/"status.json").write_text(json.dumps(value))
        with self.assertRaisesRegex(boot.RecoveryError,"BOOT_JOURNAL_IDENTITY_CHANGED"):self.engine.status()
    def test_existing_iscsi_boot_rebuild_and_online_nfs_boundary_are_retained(self):
        class Recorded(boot.Runtime):
            def __init__(self): self.commands=[]
            def run(self,args,seconds=30):
                self.commands.append(args)
                return {"returncode":0,"stdout":'{"success":true}',"timedOut":False}
        runtime=Recorded()
        runtime.prepare("ISCSI",Path("/committed/iscsi.json"),20)
        self.assertEqual(["env","ABLESTACK_STORAGE_ISCSI_AUTHORITATIVE_REBUILD=1",boot.CLI,"iscsi","target","apply","/committed/iscsi.json"],runtime.commands[-1])
        runtime.prepare("NFS",Path("/committed/nfs.json"),20)
        self.assertEqual([boot.CLI,"nfs","export","boot-prepare","/committed/nfs.json"],runtime.commands[-1])
    def test_read_has_no_launch_apply_or_writing_side_effects(self):
        self.assertEqual("NOT_OBSERVED",self.engine.status()["state"])
        self.assertFalse(self.engine.root.exists())
        self.assertEqual([],self.runtime.applies)
    def test_real_timeout_kills_descendant_group(self):
        marker=self.root/"pid"
        script='import subprocess,time;from pathlib import Path;p=subprocess.Popen(["sleep","30"]);Path('+repr(str(marker))+').write_text(str(p.pid));time.sleep(30)'
        result=boot.bounded_command([sys.executable,"-c",script],.15)
        self.assertTrue(result["timedOut"])
        pid=int(marker.read_text());f=Path("/proc")/str(pid)/"stat"
        self.assertTrue(not f.exists() or f.read_text().rsplit(")",1)[1].split()[0] in ("Z","X"))
    def test_changed_runtime_identity_does_not_reuse_ready_probe(self):
        self.runtime.fail_once=False;self.engine.start();self.engine.work()
        self.runtime.identity_value="changed-managed-pid"
        observed=self.engine.status()
        self.assertEqual("INTERRUPTED",observed["state"]);self.assertTrue(observed["canResume"])
        self.engine.start(self.request());self.engine.work()
        self.assertEqual(5,len(self.runtime.applies))
    def test_full_100_export_work_is_bounded_and_non_nfs_prepared_once(self):
        self.runtime=Runtime(100);self.runtime.fail_once=False
        self.engine.runtime=self.runtime
        self.engine.start();done=self.engine.work()
        self.assertEqual("READY",done["state"])
        self.assertEqual(200,done["protocols"]["NFS"]["completed"])
        self.assertEqual(4,len(self.runtime.applies));self.assertLessEqual(self.runtime.peak,4)

    def nvme_observation(self):
        value = json.loads((ROOT / "test/fixtures/boot_nvme_apply_observation.json").read_text())
        for key in ("before", "after"):
            value[key]["instanceUuid"] = INSTANCE
        return value

    def test_nvme_observed_device_path_is_not_desired_and_snapshot_keeps_original_payload(self):
        observed = self.nvme_observation()
        self.paths["NVMEOF"].write_text(json.dumps(observed["before"]))
        before = self.engine.snapshot()
        expected = copy.deepcopy(before["states"])
        self.paths["NVMEOF"].write_text(json.dumps(observed["after"]))
        after = self.engine.snapshot()
        self.assertEqual(before["desiredHash"], after["desiredHash"])
        self.assertEqual(expected, before["states"])
        self.assertNotEqual(before["states"]["NVMEOF"], after["states"]["NVMEOF"])
        # The same-named key elsewhere is still desired; do not strip recursively.
        nfs = after["states"]["NFS"]
        nfs["runtime"] = {"backingPath": "/dev/requested"}
        self.paths["NFS"].write_text(json.dumps(nfs))
        self.assertNotEqual(after["desiredHash"], self.engine.snapshot()["desiredHash"])

    def test_nvme_desired_uuid_size_path_acl_and_backing_type_remain_protected(self):
        observed = self.nvme_observation()["after"]
        self.paths["NVMEOF"].write_text(json.dumps(observed))
        original = self.engine.snapshot()["desiredHash"]
        variants = [
            ("volumeUuid", lambda v: v["subsystems"][0]["namespaces"][0].update(volumeUuid=str(uuid.uuid4()))),
            ("namespaceUuid", lambda v: v["subsystems"][0]["namespaces"][0].update(uuid=str(uuid.uuid4()))),
            ("size", lambda v: v["subsystems"][0]["namespaces"][0].update(volumeSizeBytes=2147483648)),
            ("configuredPath", lambda v: v["subsystems"][0]["namespaces"][0].setdefault("config", {}).update(backingPath="/dev/requested")),
            ("ACL", lambda v: v["subsystems"][0]["hosts"][0].update(principal="nqn.other")),
            ("authentication", lambda v: v["subsystems"][0]["hosts"][0]["config"].update(dhChapEnabled=True)),
            ("backingType", lambda v: v["subsystems"][0]["namespaces"][0]["runtime"].update(backingType="file")),
        ]
        for name, mutate in variants:
            with self.subTest(name=name):
                candidate = copy.deepcopy(observed)
                mutate(candidate)
                self.paths["NVMEOF"].write_text(json.dumps(candidate))
                self.assertNotEqual(original, self.engine.snapshot()["desiredHash"])

    def test_worker_reloads_real_cli_saved_nvme_observation_after_all_protocol_applies(self):
        observed = self.nvme_observation()
        self.paths["NVMEOF"].write_text(json.dumps(observed["before"]))
        native_saved = observed["after"]
        test = self
        class ReloadSavedApply(Runtime):
            def prepare(self, protocol, path, seconds):
                value = super().prepare(protocol, path, seconds)
                if protocol == "NVMEOF":
                    test.paths[protocol].write_text(json.dumps(native_saved))
                return value
        runtime = ReloadSavedApply()
        runtime.fail_once = False
        self.engine.runtime = runtime
        self.engine.start()
        result = self.engine.work()
        self.assertEqual("READY", result["state"])
        self.assertEqual(["NFS", "SMB", "ISCSI", "NVMEOF"], runtime.applies)
        self.assertEqual(20, result["protocols"]["NFS"]["completed"])
        self.assertIsNone(result["firstFailure"])
        # A real ACL change in the same persisted result must still block.
        self.engine.root = self.root / "changed-journal"
        self.paths["NVMEOF"].write_text(json.dumps(observed["before"]))
        mutated = copy.deepcopy(native_saved)
        mutated["subsystems"][0]["hosts"][0]["principal"] = "nqn.other"
        native_saved = mutated
        self.engine.start()
        denied = self.engine.work()
        self.assertEqual("BLOCKED", denied["state"])
        self.assertEqual("BOOT_OBSERVATION_CHANGED", denied["firstFailure"]["code"])

if __name__=="__main__":unittest.main()
