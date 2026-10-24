# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements. See NOTICE. Apache License, Version 2.0.
# http://www.apache.org/licenses/LICENSE-2.0
import copy
import importlib.util
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import time
import unittest
from unittest.mock import patch
import uuid

ROOT = Path(__file__).resolve().parents[1] / "debian/usr/local/lib/ablestack-storage"
sys.path.insert(0, str(ROOT))
import configuration_preflight as preflight
import configuration_transaction as transaction

class AdmissionTests(unittest.TestCase):
    def setUp(self):
        self.intent = {"protocol": "NFS", "instanceUuid": str(uuid.uuid4()), "revision": 2,
                       "action": "UPDATE", "resourceids": ["export"], "ackrequired": True}
        self.sample = {"memoryavailablebytes": 1024*preflight.MIB, "filesystems": [
            {"freebytes": 1024*preflight.MIB, "freeinodes": 1000, "readonly": False}],
            "busyvolumes": [], "connections": 1, "load1": .1, "cpucount": 2, "snapshotbytes": 13321, "observedat": time.time()}

    def report(self, **patches):
        return preflight.evaluate(self.intent, {**self.sample, **patches})

    def test_real_units_and_sparse_virtual_size_do_not_become_memory_or_root_requirements(self):
        baseline = self.report()
        self.assertEqual("READY", baseline["status"])
        self.intent["virtualSizeBytes"] = 10*1024**4
        self.assertEqual(baseline, self.report())

    def test_process_cgroup_limit_is_used_instead_of_only_host_memory(self):
        with tempfile.TemporaryDirectory() as temporary:
            base=Path(temporary);group=base/"v2/slice/agent";group.mkdir(parents=True)
            (base/"meminfo").write_text("MemAvailable: 1048576 kB\n")
            (base/"groups").write_text("0::/slice/agent\n")
            (group/"memory.max").write_text(str(256*preflight.MIB))
            (group/"memory.current").write_text(str(200*preflight.MIB))
            value=preflight.available_memory(base/"meminfo",base/"groups",base/"v2",base/"v1")
            self.assertEqual(56*preflight.MIB,value)

    def test_resource_failures_and_unknown_cannot_pass(self):
        for values, code in [
            ({"memoryavailablebytes": 1}, "MEMORY_LOW"),
            ({"filesystems": [{"freebytes":1,"freeinodes":1000,"readonly":False}]}, "SPACE_LOW"),
            ({"filesystems": [{"freebytes":10**9,"freeinodes":1000,"readonly":True}]}, "FILESYSTEM_READONLY"),
            ({"busyvolumes": ["data"]}, "VOLUME_PREPARATION_ACTIVE"),
        ]:
            with self.subTest(code=code):
                report=self.report(**values)
                self.assertEqual("BLOCKED",report["status"])
                self.assertTrue(any(x["code"]==code and x["blocking"] for x in report["checks"]))
        with patch.object(preflight,"sample",side_effect=OSError("unavailable")):
            self.assertEqual("UNKNOWN",preflight.inspect(self.intent)["status"])

    def test_drain_and_load_are_distinct_and_fingerprint_ignores_sample_drift(self):
        self.intent["drainrequired"]=True
        self.assertEqual("BLOCKED",self.report()["status"])
        self.intent["drainrequired"]=False
        high=self.report(load1=20)
        self.assertEqual("READY",high["status"])
        self.assertTrue(any(c["code"]=="LOAD_HIGH" for c in high["checks"]))
        self.assertEqual(self.report()["fingerprint"],self.report(memoryavailablebytes=2*1024**3)["fingerprint"])
        first=self.report()["fingerprint"];self.intent["revision"]=3
        self.assertNotEqual(first,self.report()["fingerprint"])

    def test_gate_rejects_before_snapshot_apply_or_restore(self):
        with tempfile.TemporaryDirectory() as directory:
            counts=[]
            class Gated(transaction.RuntimeAdapter):
                def snapshot(self,p):counts.append("snapshot")
                def apply(self,p):counts.append("apply")
                def restore(self):counts.append("restore")
            payload={"instanceUuid":str(uuid.uuid4()),"operationid":str(uuid.uuid4()),"baseRevision":0,"targetRevision":1,
                     "before":{},"candidate":{"nfs export apply":{}},"preflight":self.intent}
            with patch.object(preflight,"sample",return_value={**self.sample,"memoryavailablebytes":1}):
                result=transaction.Transaction(Path(directory),Gated).execute("apply",payload)
            self.assertEqual("ROLLED_BACK",result["state"])
            self.assertEqual("NOT_NEEDED",result["rollback"]["state"])
            self.assertEqual("MEMORY_LOW",result["originalfailure"]["code"])
            self.assertEqual([],counts)

    def test_bounded_child_drains_output_without_persisting_raw_secrets(self):
        with tempfile.TemporaryDirectory() as directory:
            private=Path(directory)
            transaction.atomic_json(private/"request.json",{"executionProfile":{"softdeadlineseconds":300}})
            original=subprocess.Popen
            program="import os,json; [os.write(1,b'x'*8192+b'\\n') for _ in range(1152)]; print(json.dumps({'success':True,'password':'private-sentinel'}))"
            with patch.object(transaction.subprocess,"Popen",side_effect=lambda *a,**kw: original([sys.executable,"-c",program],**kw)):
                transaction.run_child(private,"nfs export apply",0)
            result=json.loads((private/"runner-result.json").read_text())
            self.assertTrue(result["outputTruncated"])
            self.assertEqual({"success":True},result["result"])
            self.assertNotIn("private-sentinel",(private/"runner-result.json").read_text())
            progress=json.loads((private/"runner-progress.json").read_text())
            self.assertFalse(progress["cancellationSupported"])
            self.assertEqual(300,round(progress["softDeadlineAt"]-progress["startedAt"]))

    def test_getter_observes_live_worker_overdue_without_any_writes(self):
        with tempfile.TemporaryDirectory() as directory:
            payload={"instanceUuid":str(uuid.uuid4()),"operationid":str(uuid.uuid4())}
            tx=transaction.Transaction(Path(directory))
            _,private,status=tx.paths(payload);private.mkdir(parents=True)
            identity={"pid":__import__("os").getpid(),"boot":Path("/proc/sys/kernel/random/boot_id").read_text().strip(),
                      "start":Path("/proc/self/stat").read_text().split()[21]}
            transaction.atomic_json(status,{"operationid":payload["operationid"],"state":"RUNNING","phase":"APPLYING"})
            transaction.atomic_json(private/"runner-progress.json",{"child":identity,"heartbeatAt":time.time(),"softDeadlineAt":time.time()-1})
            before={p.name:p.read_bytes() for p in private.iterdir()}
            view=tx.show(payload)
            self.assertTrue(view["workerActive"]);self.assertTrue(view["execution"]["overdue"])
            self.assertFalse(view["cancellationSupported"])
            self.assertEqual(before,{p.name:p.read_bytes() for p in private.iterdir()})

if __name__=="__main__":
    unittest.main(verbosity=2)
