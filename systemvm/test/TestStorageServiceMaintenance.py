#!/usr/bin/env python3

# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements.  See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.  The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at
#
#   http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied.  See the License for the
# specific language governing permissions and limitations
# under the License.

import copy,json,os,sys,tempfile,unittest,uuid
from pathlib import Path
LIB=Path(__file__).resolve().parents[2]/"systemvm/debian/usr/local/lib/ablestack-storage"
sys.path.insert(0,str(LIB))
from template_maintenance import Maintenance,scope
from service_maintenance import ServiceMaintenance

class StorageServiceMaintenanceTest(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup)
        self.operation=str(uuid.uuid4());self.instance=str(uuid.uuid4())
        self.scope={"instanceUuid":self.instance,"maintenanceUuid":self.operation,"operationUuid":self.operation,"revision":2}
        self.verified={"instanceUuid":self.instance,"operationUuid":self.operation,"revision":2,"configurationSha256":"a"*64}
        self.actual={"generation":self.verified,"configurationSha256":"a"*64,"generationStatus":"IN_SYNC","pendingOperationUuid":None}
        self.maintenance=Maintenance(Path(self.temp.name)/"state",lambda:self.actual)
        self.active=[{"unit":"ablestack-storage-ganesha@test.service","pid":1,"startTicks":"1","configurationPath":"fixture"}]
        self.calls=[];self.fail=False;self.verify=True
        def run(args,**kwargs):
            self.calls.append(args)
            if args[1]=="stop":
                if self.fail:raise ValueError("Injected stop failure")
                self.active=[]
            return ""
        def command(args,payload=None):
            if args==("operation","generation","render-status"):return {"success":True,"bootHeld":False,"current":{"scope":{key:self.verified[key] for key in ("instanceUuid","operationUuid","revision")},"configurationSha256":self.verified["configurationSha256"],"manifestSha256":"c"*64},"activation":None}
            return {"success":True,"scope":self.scope,"runtimeVerified":self.verify}
        self.service=ServiceMaintenance(self.maintenance,"fixture",run,command,lambda:copy.deepcopy(self.active))
        self.old=os.environ.get("ABLESTACK_STORAGE_VOLUME_OPERATIONS");os.environ["ABLESTACK_STORAGE_VOLUME_OPERATIONS"]=str(Path(self.temp.name)/"volumes")
        self.addCleanup(self.resetenv)
    def resetenv(self):
        if self.old is None:os.environ.pop("ABLESTACK_STORAGE_VOLUME_OPERATIONS",None)
        else:os.environ["ABLESTACK_STORAGE_VOLUME_OPERATIONS"]=self.old
    def test_typed_durable_hold_survives_restart_and_same_scope_replay(self):
        result=self.service.enter(self.scope)
        self.assertEqual("HELD",result["phase"]);self.assertEqual("SERVICE",result["maintenanceKind"])
        self.assertFalse(result["drainSupported"]);self.assertNotIn("drainComplete",result)
        self.assertTrue(self.maintenance.status()["bootHeld"]);self.assertEqual(0o600,self.maintenance.marker.stat().st_mode&0o777)
        self.assertEqual(result,self.service.enter(self.scope));self.assertEqual(1,len(self.calls))
        reopened=Maintenance(self.maintenance.root,lambda:self.actual)
        self.assertEqual("SERVICE",reopened.status()["maintenanceKind"])
    def test_foreign_root_or_mixed_scope_cannot_replace_service_marker(self):
        self.service.enter(self.scope)
        for request in ({**self.scope,"maintenanceUuid":str(uuid.uuid4())},{**self.scope,"templateUpgradeUuid":str(uuid.uuid4())}):
            with self.assertRaises(ValueError):self.service.enter(request)
        with self.assertRaises(ValueError):self.maintenance.enter({"instanceUuid":self.instance,"templateUpgradeUuid":str(uuid.uuid4()),"operationUuid":self.operation,"revision":2})
        self.assertEqual(self.scope,self.maintenance.status()["scope"])
    def test_foreign_generation_pending_formatter_or_unprotected_directory_blocks_before_marker(self):
        self.actual["pendingOperationUuid"]=str(uuid.uuid4())
        with self.assertRaises(ValueError):self.service.enter(self.scope)
        self.actual["pendingOperationUuid"]=None
        directory=Path(os.environ["ABLESTACK_STORAGE_VOLUME_OPERATIONS"]);directory.mkdir(mode=0o700)
        (directory/(str(uuid.uuid4())+".json")).write_text("{}")
        with self.assertRaises(ValueError):self.service.enter(self.scope)
        self.assertFalse(self.maintenance.marker.exists());self.assertFalse(self.calls)
    def test_source_native_or_rendered_digest_drift_is_rejected_before_marker_and_stop(self):
        self.actual["configurationSha256"]="b"*64
        with self.assertRaisesRegex(ValueError,"digest drifted"):self.service.enter(self.scope)
        self.actual["configurationSha256"]="a"*64
        self.service.command=lambda args,payload=None:{"success":True,"bootHeld":False,"current":{"configurationSha256":"b"*64,"scope":self.scope}}
        with self.assertRaisesRegex(ValueError,"rendered baseline"):self.service.enter(self.scope)
        self.assertFalse(self.maintenance.marker.exists());self.assertFalse(self.calls)

    def test_partial_stop_failure_persists_recovery_and_boot_hold(self):
        self.fail=True
        with self.assertRaises(ValueError):self.service.enter(self.scope)
        self.assertEqual("RECOVERY_REQUIRED",self.service.status(self.scope)["phase"])
        self.assertTrue(self.maintenance.status()["bootHeld"])
        self.fail=False;self.service.enter(self.scope)
        self.assertEqual("HELD",self.service.status(self.scope)["phase"])
    def test_release_requires_fresh_all_four_readback_then_exact_generation_and_replays(self):
        self.service.enter(self.scope);request={**self.scope,"verifiedGeneration":self.verified}
        self.verify=False
        with self.assertRaises(ValueError):self.service.release(request)
        self.assertTrue(self.maintenance.status()["bootHeld"])
        self.verify=True;self.actual["configurationSha256"]="b"*64
        with self.assertRaises(ValueError):self.service.release(request)
        self.assertTrue(self.maintenance.status()["bootHeld"])
        self.actual["configurationSha256"]="a"*64
        result=self.service.release(request);self.assertTrue(result["released"]);self.assertFalse(result["bootHeld"])
        self.assertTrue(self.service.release(request)["released"])
    def test_actual_signed_cli_scopes_service_hold_and_blocks_boot_readonly(self):
        import subprocess
        root=Path(self.temp.name)
        cli=Path(__file__).resolve().parents[2]/"systemvm/debian/usr/local/bin/ablestack-storagectl"
        binaries=root/"bin";binaries.mkdir();systemctl=binaries/"systemctl"
        systemctl.write_text("#!/bin/sh\nexit 0\n");systemctl.chmod(0o755)
        env=dict(os.environ,PATH=str(binaries)+":"+os.environ["PATH"],ABLESTACK_STORAGE_GENERATION_DIR=str(root/"generation"),
                 ABLESTACK_STORAGE_CONFIGURATION_ROOT=str(root/"config"),ABLESTACK_STORAGE_TEMPLATE_MAINTENANCE_DIR=str(root/"native-maintenance"),
                 ABLESTACK_STORAGE_RENDERED_GENERATIONS=str(root/"render"),ABLESTACK_STORAGE_WRITER_LOCK_FILE=str(root/"writer"))
        (root/"config").mkdir(mode=0o700)
        def native(args,request=None,success=True):
            result=subprocess.run([str(cli),*args,*(["/dev/stdin"] if request is not None else [])],env=env,input=json.dumps(request) if request is not None else None,capture_output=True,text=True,timeout=15)
            self.assertEqual(success,result.returncode==0,result.stderr+result.stdout)
            return json.loads(result.stdout)
        for action in ("begin","verify","commit","finish"):native(("operation","generation",action),self.scope)
        from rendered_generation import RenderedGeneration,DESIRED_PATHS,REQUIRED,DOMAINS
        generation_status=native(("operation","generation","status"))
        store=RenderedGeneration(root/"render");files={name:"{}" for name in REQUIRED};files["desired-state.json"]=json.dumps(generation_status["configurationDesiredState"])
        baseline=store.stage({key:generation_status["generation"][key] for key in ("instanceUuid","operationUuid","revision")},files,lambda *args:{domain:True for domain in DOMAINS})
        store.publish_pointer(store.generations/baseline["scope"]["operationUuid"])
        entered=native(("operation","maintenance","service-enter"),self.scope)
        self.assertEqual(self.scope,entered["scope"]);self.assertEqual("SERVICE",entered["maintenanceKind"])
        status=native(("operation","maintenance","status"));self.assertTrue(status["bootHeld"])
        denied=native(("operation","generation","render-boot-gate","smbd.service"),success=False)
        self.assertIn("held",denied["reason"])
        generation=native(("operation","generation","status"))["generation"]
        native(("operation","maintenance","service-release"),{**self.scope,"verifiedGeneration":generation},success=False)
        self.assertTrue(native(("operation","maintenance","status"))["bootHeld"])

    def test_orphan_native_listener_blocks_completion_without_fake_drain_claim(self):
        owners=[{"listenerEndpoints":[{"listenIp":"10.10.13.240","port":2049}]}]
        self.service.run=lambda args,**kwargs:"LISTEN 0 128 10.10.13.240:2049 0.0.0.0:* users:((owned,pid=1))\n"
        with self.assertRaisesRegex(ValueError,"remains exposed"):self.service.listeners_clear(owners)
        self.service.run=lambda args,**kwargs:"LISTEN 0 128 10.10.13.242:2049 0.0.0.0:* users:((foreign,pid=2))\n"
        self.service.listeners_clear(owners)

    def test_foreign_legacy_acceptors_are_rejected_before_signal(self):
        for unit in ("smbd.service","unrelated.service","ablestack-storage-smb@foreign.service","nfs-kernel-server.service"):
            with self.assertRaises(ValueError):self.service.prove_unit(unit)
        self.assertFalse(self.calls)

if __name__=="__main__":unittest.main()
