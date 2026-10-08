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

import importlib.util
import json
from pathlib import Path
import tempfile
import threading
import unittest
import uuid

SOURCE=Path(__file__).resolve().parents[2]/'systemvm/debian/usr/local/lib/ablestack-storage/rendered_generation.py'
spec=importlib.util.spec_from_file_location('rendered',SOURCE);module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)

class StorageRenderedGenerationTest(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup)
        self.store=module.RenderedGeneration(Path(self.temp.name)/'rendered')
        self.instance=str(uuid.uuid4());self.calls=[];self.live={}
        self.scope={'instanceUuid':self.instance,'operationUuid':str(uuid.uuid4()),'revision':1,'expectedCurrentRenderedSha256':None}
        self.files=self.rendered_files('old')
        self.manifest=self.store.stage(self.scope,self.files,self.validate)
        self.store.activate(self.scope,self.replay,self.verify)
        self.store.finalize(self.scope,self.generation,self.verify)
        self.calls.clear()

    def rendered_files(self,label):
        desired={name:None for name in module.DESIRED_PATHS}
        desired['desired-state/smb-share-apply.json']={'enabled':True,'shares':[{'name':label}]}
        return {'nfs/manifest.json':json.dumps({'exports':[]}), 'smb/smb.conf':'[global]\nsecurity = user\n# '+label+'\n',
                'smb/manifest.json':json.dumps({'shares':[label]}), 'block/iscsi-plan.json':json.dumps({'targets':[]}),
                'block/nvmeof-plan.json':json.dumps({'subsystems':[]}), 'desired-state.json':json.dumps(desired),
                'network-bindings.json':json.dumps({'bindings':[]}), 'posix-plan.json':json.dumps({'policies':[]}), 'file-volumes.json':json.dumps({'schemaVersion':1,'volumes':[]}),'prerequisites.json':json.dumps({'directories':{}})}

    def validate(self,path,manifest):
        self.assertTrue((path/'smb/smb.conf').is_file())
        return {domain:True for domain in module.DOMAINS}

    def replay(self,path,domain,rollback):
        self.calls.append((domain,rollback))
        self.live[domain]=self.store.inspect(path)['domainSha256'][domain]

    def verify(self,path):
        manifest=self.store.inspect(path)
        return {domain:self.live.get(domain)==manifest['domainSha256'][domain] for domain in module.DOMAINS}

    def generation(self):
        manifest=self.store.inspect(self.store.pointer())
        return {'generationStatus':'IN_SYNC','pendingOperationUuid':None,'configurationSha256':manifest['configurationSha256'],
                'generation':{**manifest['scope'],'configurationSha256':manifest['configurationSha256']}}

    def next_scope(self):
        return {**self.scope,'operationUuid':str(uuid.uuid4()),'revision':2,'expectedCurrentRenderedSha256':self.manifest['manifestSha256']}

    def test_validation_failure_never_changes_current_files_or_runtime(self):
        request=self.next_scope();old=self.store.pointer()
        with self.assertRaises(ValueError):self.store.stage(request,self.rendered_files('new'),lambda path,manifest:{'SMB':False})
        self.assertEqual(old,self.store.pointer());self.assertFalse(self.calls)
        self.assertFalse((self.store.generations/request['operationUuid']).exists())

    def test_atomic_publication_retains_previous_files_and_replays_only_changed_domains(self):
        request=self.next_scope();old=self.store.pointer();manifest=self.store.stage(request,self.rendered_files('new'),self.validate)
        self.assertEqual(old,self.store.pointer())
        active=self.store.activate(request,self.replay,self.verify)
        self.assertTrue(active['bootHeld']);self.assertEqual([('SMB',False)],self.calls)
        self.assertEqual(manifest,self.store.inspect(self.store.pointer()))
        self.assertEqual(self.files['smb/smb.conf'].encode(),(old/'smb/smb.conf').read_bytes())
        final=self.store.finalize(request,self.generation,self.verify)
        self.assertFalse(final['bootHeld']);self.assertTrue(old.exists())

    def test_kernel_replay_failure_restores_previous_pointer_and_requires_verified_runtime(self):
        request=self.next_scope();self.store.stage(request,self.rendered_files('new'),self.validate);old=self.store.pointer()
        def fail(path,domain,rollback):
            self.replay(path,domain,rollback)
            if not rollback:raise RuntimeError('injected ordered runtime failure')
        with self.assertRaises(RuntimeError):self.store.activate(request,fail,self.verify)
        self.assertEqual(old,self.store.pointer());self.assertEqual('ROLLED_BACK',self.store.status()['activation']['phase'])
        self.assertFalse(self.store.status()['bootHeld']);self.assertEqual([('SMB',False),('SMB',True)],self.calls)

    def test_failed_previous_runtime_readback_remains_recovery_required(self):
        request=self.next_scope();self.store.stage(request,self.rendered_files('new'),self.validate)
        def failure(path,domain,rollback):raise RuntimeError('injected failure')
        with self.assertRaises(RuntimeError):self.store.activate(request,failure,lambda path:{domain:False for domain in module.DOMAINS})
        self.assertEqual('RECOVERY_REQUIRED',self.store.status()['activation']['phase']);self.assertTrue(self.store.status()['bootHeld'])

    def test_process_death_after_pointer_swap_is_detected_and_resumes_same_pinned_generation(self):
        request=self.next_scope();target=self.store.stage(request,self.rendered_files('new'),self.validate)
        def interrupted(path,domain,rollback):raise KeyboardInterrupt('simulated process death')
        with self.assertRaises(KeyboardInterrupt):self.store.activate(request,interrupted,self.verify)
        recovered=module.RenderedGeneration(self.store.root)
        self.assertEqual('ACTIVATING',recovered.status()['activation']['phase']);self.assertTrue(recovered.status()['bootHeld'])
        self.assertEqual(target,recovered.inspect(recovered.pointer()))
        recovered.activate(request,self.replay,self.verify)
        self.assertEqual('VERIFIED',recovered.status()['activation']['phase'])

    def test_unexpected_paths_plain_credentials_tamper_and_foreign_scope_are_rejected(self):
        request=self.next_scope()
        for files in ({**self.rendered_files('new'),'../../outside':b'unsafe'},
                      {**self.rendered_files('new'),'block/iscsi-plan.json':json.dumps({'password':'SYNTHETIC'})}):
            with self.assertRaises(ValueError):self.store.stage(request,files,self.validate)
        self.store.stage(request,self.rendered_files('new'),self.validate)
        path=self.store.generations/request['operationUuid'];(path/'smb/smb.conf').write_text('tampered')
        with self.assertRaises(ValueError):self.store.activate(request,self.replay,self.verify)
        with self.assertRaises(ValueError):self.store.rollback({**request,'operationUuid':str(uuid.uuid4())},self.replay,self.verify)
        self.assertFalse(self.calls)

    def test_finalization_requires_no_pending_exact_native_generation_and_fresh_all_protocols(self):
        request=self.next_scope();self.store.stage(request,self.rendered_files('new'),self.validate);self.store.activate(request,self.replay,self.verify)
        for generation in (lambda:{**self.generation(),'pendingOperationUuid':request['operationUuid']},
                           lambda:{**self.generation(),'configurationSha256':'0'*64}):
            with self.assertRaises(ValueError):self.store.finalize(request,generation,self.verify)
        with self.assertRaises(ValueError):self.store.finalize(request,self.generation,lambda path:{'SMB':True})
        self.assertTrue(self.store.status()['bootHeld'])

    def test_pinned_readers_observe_a_whole_old_or_new_directory_during_pointer_switches(self):
        request=self.next_scope();self.store.stage(request,self.rendered_files('new'),self.validate)
        old=self.store.pointer();new=self.store.generations/request['operationUuid'];failures=[]
        def reader():
            try:
                for _ in range(50):self.store.inspect(self.store.pointer())
            except Exception as error:failures.append(type(error).__name__)
        thread=threading.Thread(target=reader);thread.start()
        for _ in range(20):self.store.publish_pointer(new);self.store.publish_pointer(old)
        thread.join();self.assertEqual([],failures)

    def test_stage_retry_after_pointer_activation_is_same_manifest_and_does_not_touch_runtime(self):
        request=self.next_scope();files=self.rendered_files('new')
        target=self.store.stage(request,files,self.validate)
        self.store.activate(request,self.replay,self.verify)
        before=list(self.calls)
        self.assertEqual(target,self.store.stage(request,files,self.validate))
        self.assertEqual(before,self.calls)
        with self.assertRaises(ValueError):self.store.stage(request,self.rendered_files('foreign'),self.validate)

    def test_verified_activation_retry_only_rechecks_and_does_not_rebuild_live_kernel_domains(self):
        request=self.next_scope();self.store.stage(request,self.rendered_files('new'),self.validate)
        self.store.activate(request,self.replay,self.verify);self.calls.clear()
        self.store.activate(request,self.replay,self.verify)
        self.assertEqual([],self.calls)

    def test_real_domain_failure_reverses_each_affected_domain_and_only_then_restores_desired(self):
        request=self.next_scope();files=self.rendered_files('new')
        for name in ('nfs/manifest.json','block/iscsi-plan.json','block/nvmeof-plan.json'):
            files[name]=json.dumps({'changed':True})
        self.store.stage(request,files,self.validate);normalization=[]
        def fail(path,domain,rollback):
            self.replay(path,domain,rollback)
            if domain=='NVMEOF' and not rollback:raise RuntimeError('fault after all four domain effects')
        with self.assertRaises(RuntimeError):self.store.activate(request,fail,self.verify,lambda path:normalization.append(self.verify(path)))
        self.assertEqual([(domain,False) for domain in module.DOMAINS]+[(domain,True) for domain in reversed(module.DOMAINS)],self.calls)
        self.assertEqual([{domain:True for domain in module.DOMAINS}],normalization)
        self.assertEqual('ROLLED_BACK',self.store.status()['activation']['phase'])

    def test_prerequisite_failure_restores_source_pointer_before_its_inverse_and_all_runtime_checks(self):
        request=self.next_scope();self.store.stage(request,self.rendered_files('new'),self.validate);old=self.store.pointer();events=[]
        def prerequisite(path,rollback):
            events.append((rollback,self.store.pointer()==path))
            if not rollback:raise RuntimeError('permission/network prerequisite injected failure')
        with self.assertRaises(RuntimeError):self.store.activate(request,self.replay,self.verify,prerequisites=prerequisite)
        self.assertEqual(old,self.store.pointer());self.assertEqual([(False,True),(True,True)],events)
        self.assertEqual('ROLLED_BACK',self.store.status()['activation']['phase'])

    def test_early_domain_fault_rolls_back_only_durably_started_effects(self):
        request=self.next_scope();files=self.rendered_files('new')
        for name in ('nfs/manifest.json','block/iscsi-plan.json','block/nvmeof-plan.json'):files[name]=json.dumps({'changed':True})
        self.store.stage(request,files,self.validate)
        def fail(path,domain,rollback):
            journal=self.store.read_journal()
            if not rollback:self.assertIn(domain,journal['startedDomains'])
            self.replay(path,domain,rollback)
            if domain=='NFS' and not rollback:raise RuntimeError('first-domain failure')
        with self.assertRaises(RuntimeError):self.store.activate(request,fail,self.verify)
        self.assertEqual([('NFS',False),('NFS',True)],self.calls)
        self.assertEqual(['NFS'],self.store.read_journal()['startedDomains'])
        self.assertEqual([],self.store.read_journal()['appliedDomains'])

    def test_restart_rollback_uses_pre_effect_receipt_even_without_successful_apply_ack(self):
        request=self.next_scope();self.store.stage(request,self.rendered_files('new'),self.validate)
        def die(path,domain,rollback):raise KeyboardInterrupt('response lost after effect')
        with self.assertRaises(KeyboardInterrupt):self.store.activate(request,die,self.verify)
        recovered=module.RenderedGeneration(self.store.root)
        self.assertEqual(['SMB'],recovered.read_journal()['startedDomains'])
        recovered.rollback(request,self.replay,self.verify)
        self.assertEqual([('SMB',True)],self.calls)
        self.assertFalse(recovered.status()['bootHeld'])

if __name__=='__main__':unittest.main()
