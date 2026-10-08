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

import json,os,subprocess,fcntl
from pathlib import Path
import sys,tempfile,unittest,uuid

LIB=Path(__file__).resolve().parents[2]/"systemvm/debian/usr/local/lib/ablestack-storage"
sys.path.insert(0,str(LIB))
from resource_reservation import ResourceReservation


class StorageResourceReservationTest(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup);self.root=Path(self.temp.name)
        self.gen=self.root/'generation';self.gen.mkdir();self.instance=str(uuid.uuid4())
        (self.gen/'current.json').write_text(json.dumps({'instanceUuid':self.instance,'revision':9}));(self.gen/'current.json').chmod(0o600)
        self.now=1000;self.observed={'memoryAvailableBytes':1024,'stagingFreeBytes':2048,'loadPerCpu':.5,'logicalReservationOnly':True}
        self.store=ResourceReservation(self.root/'reservation',self.gen,lambda:self.observed,lambda:self.now,'boot1')
        self.scope={'instanceUuid':self.instance,'operationUuid':str(uuid.uuid4()),'revision':10,'leaseDurationSeconds':90}
        self.requirements={'minimumMemoryAvailableBytes':512,'stagingRequiredBytes':1024,'maxLoadPerCpu':2,'requireSessionDrain':False}
        self.request={**self.scope,'requirements':self.requirements}

    def test_status_never_creates_files_and_does_not_claim_ram_allocation(self):
        result=self.store.status();self.assertTrue(result['reservationSupported']);self.assertFalse(result['reservationAcquired']);self.assertTrue(result['logicalReservationOnly']);self.assertFalse((self.root/'reservation').exists())

    def test_acquire_renew_release_are_scoped_and_keep_a_logical_expiry(self):
        result=self.store.execute('acquire',self.request);self.assertTrue(result['reservationAcquired']);self.assertEqual(91000,result['leaseExpiresAt'])
        self.now=2000;self.assertEqual(92000,self.store.execute('renew',self.scope)['leaseExpiresAt'])
        released=self.store.execute('release',self.scope);self.assertFalse(released['reservationAcquired']);self.assertEqual(self.instance,released['scope']['instanceUuid'])
        self.assertFalse(self.store.record.exists())

    def test_active_foreign_scope_is_never_overwritten_and_only_expired_acquire_can_take_over(self):
        self.store.execute('acquire',self.request);foreign={**self.request,'operationUuid':str(uuid.uuid4())}
        for action in ('acquire','renew','release'):
            with self.assertRaises(ValueError):self.store.execute(action,foreign)
        self.now=92000
        for action in ('renew','release'):
            with self.assertRaises(ValueError):self.store.execute(action,foreign)
        self.assertEqual(foreign['operationUuid'],self.store.execute('acquire',foreign)['scope']['operationUuid'])

    def test_reboot_invalidates_lease_and_renew_cannot_resurrect_an_expired_record(self):
        self.store.execute('acquire',self.request);self.store.boot_id='boot2'
        self.assertTrue(self.store.status()['expired']);self.assertFalse(self.store.status()['reservationAcquired'])
        with self.assertRaises(ValueError):self.store.execute('renew',self.scope)

    def test_fresh_headroom_failures_and_unimplemented_drain_never_acquire(self):
        for changes,code in (({'memoryAvailableBytes':1},'MEMORY_HEADROOM'),({'stagingFreeBytes':1},'STAGING_HEADROOM'),({'loadPerCpu':5},'LOAD_HEADROOM')):
            previous=self.observed;self.observed={**previous,**changes}
            result=self.store.execute('acquire',self.request);self.assertFalse(result['success']);self.assertIn(code,result['blockers']);self.assertFalse(self.store.record.exists());self.observed=previous
        result=self.store.execute('acquire',{**self.request,'requirements':{**self.requirements,'requireSessionDrain':True}})
        self.assertIn('DRAIN_NOT_IMPLEMENTED',result['blockers']);self.assertFalse(result['drainSupported']);self.assertFalse(self.store.record.exists())

    def test_foreign_instance_pending_or_unprotected_record_fail_closed(self):
        with self.assertRaises(ValueError):self.store.execute('acquire',{**self.request,'instanceUuid':str(uuid.uuid4())})
        path=self.gen/'pending.json';path.write_text(json.dumps({**self.scope,'operationUuid':str(uuid.uuid4())}));path.chmod(0o600)
        with self.assertRaises(ValueError):self.store.execute('acquire',self.request)
        path.unlink();self.store.execute('acquire',self.request);self.store.record.chmod(0o666)
        with self.assertRaises(ValueError):self.store.status()

    def test_fresh_root_baseline_requires_exact_protected_boot_held_marker_and_renews_under_same_scope(self):
        (self.gen/"current.json").unlink()
        marker=self.root/"maintenance"/"template-maintenance.json";marker.parent.mkdir(mode=0o700)
        self.store.maintenance=marker
        root_scope={key:self.scope[key] for key in ("instanceUuid","operationUuid","revision")}
        root_scope["templateUpgradeUuid"]=str(uuid.uuid4())
        request={**self.request,"templateUpgradeUuid":root_scope["templateUpgradeUuid"],"maintenanceScope":root_scope}
        with self.assertRaises(ValueError):self.store.execute("acquire",request)
        self.assertFalse(self.store.root.exists())
        marker.write_text(json.dumps({"scope":root_scope}));marker.chmod(0o600)
        result=self.store.execute("acquire",request);self.assertTrue(result["reservationAcquired"])
        self.assertTrue(self.store.execute("renew",request)["reservationAcquired"])
        before=self.store.record.read_bytes()
        foreign={**root_scope,"templateUpgradeUuid":str(uuid.uuid4())};marker.write_text(json.dumps({"scope":foreign}))
        with self.assertRaises(ValueError):self.store.execute("renew",request)
        self.assertEqual(before,self.store.record.read_bytes())
        marker.write_text(json.dumps({"scope":root_scope}))
        self.assertFalse(self.store.execute("release",request)["reservationAcquired"])

    def test_root_baseline_caller_proof_foreign_or_unprotected_marker_cannot_bypass_generation(self):
        (self.gen/"current.json").unlink()
        with self.assertRaises(ValueError):self.store.execute("acquire",self.request)
        self.assertFalse(self.store.root.exists())
        marker=self.root/"marker.json";self.store.maintenance=marker
        expected={key:self.scope[key] for key in ("instanceUuid","operationUuid","revision")}
        expected["templateUpgradeUuid"]=str(uuid.uuid4())
        request={**self.request,"maintenanceScope":expected,"templateUpgradeUuid":expected["templateUpgradeUuid"]}
        for replacement in ({**expected,"instanceUuid":str(uuid.uuid4())},{**expected,"operationUuid":str(uuid.uuid4())},{**expected,"revision":True}):
            marker.write_text(json.dumps({"scope":replacement}));marker.chmod(0o600)
            with self.assertRaises(ValueError):self.store.execute("acquire",request)
        marker.write_text(json.dumps({"scope":expected}));marker.chmod(0o644)
        with self.assertRaises(ValueError):self.store.execute("acquire",request)
        self.assertFalse(self.store.root.exists())

    def test_root_marker_presence_means_boot_held_and_inactive_or_false_marker_is_rejected(self):
        (self.gen/"current.json").unlink()
        marker=self.root/"template-maintenance.json";self.store.maintenance=marker
        expected={key:self.scope[key] for key in ("instanceUuid","operationUuid","revision")}
        expected["templateUpgradeUuid"]=str(uuid.uuid4())
        request={**self.request,"maintenanceScope":expected,"templateUpgradeUuid":expected["templateUpgradeUuid"]}
        for value in ({"scope":expected,"bootHeld":False},{"scope":expected,"phase":"INACTIVE"}):
            marker.write_text(json.dumps(value));marker.chmod(0o600)
            with self.assertRaises(ValueError):self.store.execute("acquire",request)
            self.assertFalse(self.store.root.exists())

    def test_actual_cli_root_baseline_acquire_renew_release_is_pinned_to_the_marker(self):
        (self.gen/"current.json").unlink()
        marker=self.root/"maintenance"/"template-maintenance.json";marker.parent.mkdir(mode=0o700)
        expected={key:self.scope[key] for key in ("instanceUuid","operationUuid","revision")}
        expected["templateUpgradeUuid"]=str(uuid.uuid4());marker.write_text(json.dumps({"scope":expected}));marker.chmod(0o600)
        request={**self.request,"templateUpgradeUuid":expected["templateUpgradeUuid"],"maintenanceScope":expected,
                 "requirements":{**self.requirements,"minimumMemoryAvailableBytes":0,"stagingRequiredBytes":0,"maxLoadPerCpu":100}}
        payload=self.root/"root-request.json";payload.write_text(json.dumps(request))
        cli=Path(__file__).resolve().parents[2]/"systemvm/debian/usr/local/bin/ablestack-storagectl"
        environment=dict(os.environ,ABLESTACK_STORAGE_RESERVATION_DIR=str(self.root/"root-reservation"),
                         ABLESTACK_STORAGE_GENERATION_DIR=str(self.gen),ABLESTACK_STORAGE_TEMPLATE_MAINTENANCE_DIR=str(marker.parent))
        for action in ("acquire","renew","release"):
            result=subprocess.run([str(cli),"operation","reservation",action,str(payload)],capture_output=True,text=True,env=environment,timeout=10)
            self.assertEqual(0,result.returncode,result.stdout+result.stderr)
            self.assertEqual(expected["instanceUuid"],json.loads(result.stdout)["scope"]["instanceUuid"])
            self.assertEqual(action!="release",json.loads(result.stdout)["reservationAcquired"])

    def test_actual_cli_status_and_format_capabilities_create_no_writer_or_payload_files(self):
        cli=Path(__file__).resolve().parents[2]/"systemvm/debian/usr/local/bin/ablestack-storagectl"
        environment=dict(os.environ,ABLESTACK_STORAGE_RESERVATION_DIR=str(self.root/'never-created'),ABLESTACK_STORAGE_WRITER_LOCK_FILE=str(self.root/'never-writer'/'lock'))
        result=subprocess.run([str(cli),'operation','reservation','status'],capture_output=True,text=True,env=environment,timeout=10)
        self.assertEqual(0,result.returncode,result.stderr);self.assertFalse(json.loads(result.stdout)['reservationAcquired'])
        result=subprocess.run([str(cli),'volume','operation','capabilities'],capture_output=True,text=True,env=environment,timeout=10)
        self.assertEqual(0,result.returncode,result.stderr);cap=json.loads(result.stdout)
        self.assertIn('SKIP_DISCARD',cap['formatDiscardPolicies']);self.assertTrue(cap['sparseFormatRequired']);self.assertTrue(cap['formatterSuccessReceiptSupported'])
        self.assertFalse((self.root/'never-created').exists());self.assertFalse((self.root/'never-writer').exists())

    def test_real_acquire_renew_release_use_their_own_lock_while_native_writer_descriptor_is_held(self):
        cli=Path(__file__).resolve().parents[2]/"systemvm/debian/usr/local/bin/ablestack-storagectl"
        native=self.root/'native.lock';native.touch(mode=0o600)
        descriptor=os.open(native,os.O_RDWR);fcntl.flock(descriptor,fcntl.LOCK_EX|fcntl.LOCK_NB)
        try:
            environment=dict(os.environ,ABLESTACK_STORAGE_RESERVATION_DIR=str(self.root/'live-reservation'),ABLESTACK_STORAGE_GENERATION_DIR=str(self.gen),ABLESTACK_STORAGE_WRITER_LOCK_FILE=str(native))
            request={**self.scope,'requirements':{**self.requirements,'minimumMemoryAvailableBytes':0,'stagingRequiredBytes':0,'maxLoadPerCpu':100}}
            payload=self.root/'request.json';payload.write_text(json.dumps(request))
            for action in ('acquire','renew','release'):
                result=subprocess.run([str(cli),'operation','reservation',action,str(payload)],capture_output=True,text=True,env=environment,timeout=10)
                self.assertEqual(0,result.returncode,result.stderr+result.stdout)
                value=json.loads(result.stdout);self.assertTrue(value['success']);self.assertTrue(value['logicalReservationOnly'])
                self.assertEqual(action!='release',value['reservationAcquired'])
            self.assertFalse((self.root/'live-reservation/lease.json').exists())
        finally:os.close(descriptor)

    def test_status_uses_fresh_resource_observation_without_rewriting_lease_or_creating_directory(self):
        first=self.store.status();self.assertEqual(1,first['observed']['generatedEpoch']);self.assertFalse(self.store.root.exists())
        self.store.execute('acquire',self.request);before=self.store.record.read_bytes();self.now=2000;self.observed={**self.observed,'memoryAvailableBytes':99}
        current=self.store.status();self.assertEqual(99,current['observed']['memoryAvailableBytes']);self.assertEqual(2,current['observed']['generatedEpoch'])
        self.assertEqual(before,self.store.record.read_bytes());self.assertTrue(current['reservationAcquired'])

if __name__=='__main__':unittest.main()
