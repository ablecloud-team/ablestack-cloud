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

import hashlib
import ast
import io
import re
from contextlib import redirect_stdout
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import threading
import fcntl
import time
import unittest
from unittest.mock import Mock, patch

LIB=Path(__file__).resolve().parents[2]/"systemvm/debian/usr/local/lib/ablestack-storage"
sys.path.insert(0,str(LIB))
import nfs_readiness as nfs
from nfs_readiness import NfsProbeRunner,NfsEndpointReadiness,NfsReadinessError
from ganesha_dbus import GaneshaDbus


class StorageNfsReadinessTest(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup)
        self.root=Path(self.temp.name)
        self.calls=[]
        self.runner=NfsProbeRunner({},self.root,time.monotonic()+30,lambda host,*args:(host,None))
        self.runner.cache_root=self.root/"cache"
        self.runner.cache_enabled=False
        self.runner.run_command=self.command

    def command(self,args,maximum,context=None):
        self.calls.append(args)
        return subprocess.CompletedProcess(args,0,"2: eth0 inet 10.1.1.9/24 scope global eth0" if args[0]=="ip" else "","")

    def endpoint(self,index=0):
        return {"listenIp":"127.0.0."+str(index+1),"port":2049+index,"protocolMode":"V4_ONLY",
                "pid":os.getpid(),"startTicks":"123","configurationSha256":"a"*64,"exportInventoryReady":True}

    def export(self,index=0,dual=False):
        return {"uuid":"00000000-0000-4000-8000-"+str(index).zfill(12),"id":1000+index,
                "name":"export"+str(index),"path":"/export/share"+str(index),"pseudo":"/share"+str(index),
                "protocolMode":"V3V4_DUAL" if dual else "V4_ONLY"}

    def run_exports(self,count=1,dual=False,endpoint=None):
        endpoint=endpoint or self.endpoint()
        exports=[self.export(index,dual) for index in range(count)]
        result=self.runner.run([endpoint],{(endpoint["listenIp"],endpoint["port"]):exports})
        return result,endpoint,exports

    def test_1_10_100_exports_check_every_mode_without_parallelism_or_mount_leaks(self):
        for count in (1,10,100):
            for dual in (False,True):
                self.calls.clear();self.runner.completed=0;self.runner.total=0
                failures,endpoint,_=self.run_exports(count,dual)
                self.assertEqual([],failures)
                expected=count*(2 if dual else 1)
                self.assertEqual(expected,len([call for call in self.calls if call[0]=="mount"]))
                self.assertEqual(expected,endpoint["completedProbeCount"])
                self.assertEqual(1,self.runner.concurrency)
                self.assertEqual([],list(self.root.iterdir()))

    def test_representative_endpoint_modes_precede_remaining_exports(self):
        a,b=self.endpoint(),self.endpoint(1)
        exports=[self.export(0,True),self.export(1,True)]
        self.runner.run([a,b],{(a["listenIp"],a["port"]):exports,(b["listenIp"],b["port"]):exports})
        mounts=[call for call in self.calls if call[0]=="mount"]
        self.assertEqual(["127.0.0.1:/share0","127.0.0.1:/share0","127.0.0.2:/share0","127.0.0.2:/share0"],
                         [call[-2] for call in mounts[:4]])
        self.assertTrue(all(call[-2].endswith("/share1") for call in mounts[4:]))

    def test_success_cache_reused_only_same_boot_config_export_mode_and_owned_process(self):
        self.runner.cache_enabled=True
        failures,ep,exports=self.run_exports(2,True);self.assertFalse(failures)
        self.calls.clear();self.runner.completed=0;self.runner.total=0;self.runner.success_keys=[]
        self.assertFalse(self.runner.run([ep],{(ep["listenIp"],ep["port"]):exports}))
        self.assertFalse(any(call[0]=="mount" for call in self.calls))
        self.assertEqual(4,ep["completedProbeCount"])
        for field,value in (("configurationSha256","b"*64),("startTicks","456"),("pid",os.getpid()+1)):
            self.calls.clear();self.runner.completed=0;self.runner.total=0;self.runner.success_keys=[]
            changed={**ep,field:value}
            self.runner.run([changed],{(ep["listenIp"],ep["port"]):exports})
            self.assertEqual(4,len([call for call in self.calls if call[0]=="mount"]))
        self.runner.boot_id="00000000-0000-4000-8000-000000000099"
        self.calls.clear();self.runner.run([ep],{(ep["listenIp"],ep["port"]):exports})
        self.assertTrue(any(call[0]=="mount" for call in self.calls))

    def test_specific_export_timeout_retries_only_missing_proof(self):
        self.runner.cache_enabled=True
        failed=[False]
        def command(args,maximum,context=None):
            if args[0]=="mount" and args[-2].endswith("/share1") and not failed[0]:
                failed[0]=True
                error=subprocess.TimeoutExpired(args,15);error.childActive=False
                self.calls.append(args);raise error
            return self.command(args,maximum,context)
        self.runner.run_command=command
        failures,ep,exports=self.run_exports(3)
        self.assertTrue(failures);self.assertFalse(ep["probeSuccess"])
        self.calls.clear();self.runner.completed=0;self.runner.total=0;self.runner.success_keys=[]
        self.assertFalse(self.runner.run([ep],{(ep["listenIp"],ep["port"]):exports}))
        self.assertEqual(["127.0.0.1:/share1"],[call[-2] for call in self.calls if call[0]=="mount"])

    def test_cache_symlink_and_foreign_summary_never_establish_completion(self):
        self.runner.cache_enabled=True
        _,ep,_=self.run_exports()
        self.runner.verified_summary([ep])
        changed={**ep,"pid":ep["pid"]+1}
        with self.assertRaises(ValueError):self.runner.verified_summary([changed])
        summary=self.runner.cache_root/"summary.json"
        summary.unlink();summary.symlink_to(self.root/"missing")
        with self.assertRaises(ValueError):self.runner.verified_summary([ep])

    def test_pending_child_prevents_any_later_mount_and_remains_recovery_required(self):
        def command(args,maximum,context=None):
            if args[0]=="mount":
                self.calls.append(args)
                error=subprocess.TimeoutExpired(args,15);error.childActive=True;error.childPid=999
                raise error
            return self.command(args,maximum,context)
        self.runner.run_command=command
        failures,ep,_=self.run_exports(3)
        self.assertTrue(failures);self.assertEqual(1,len([call for call in self.calls if call[0]=="mount"]))
        self.assertTrue(ep["probeResults"][0]["cleanupPending"])
        self.assertEqual(0,ep["completedProbeCount"])

    def test_real_pidfd_child_timeout_is_bounded_and_child_reaped(self):
        runner=NfsProbeRunner({},self.root,time.monotonic()+.3,None)
        start=time.monotonic()
        with self.assertRaises(subprocess.TimeoutExpired) as caught:
            runner.run_command([sys.executable,"-c","import time;time.sleep(60)"],.05)
        self.assertFalse(caught.exception.childActive)
        self.assertLess(time.monotonic()-start,1)
        self.assertFalse(Path("/proc/"+str(caught.exception.childPid)).exists())

    def test_unknown_inventory_wait_never_claims_runtime_ready(self):
        endpoint=self.endpoint();endpoint.pop("exportInventoryReady")
        readiness=NfsEndpointReadiness(time.monotonic()+.01,Mock(side_effect=ValueError("not ready")),self.command)
        readiness.snapshot=lambda *args:({"pid":os.getpid(),"startTicks":"123","configurationSha256":"a"*64},"")
        with self.assertRaises(NfsReadinessError):readiness.wait(endpoint,"/unused")
        self.assertNotIn("exportInventoryReady",endpoint)

    def test_cold_dbus_registration_delay_then_exact_inventory_no_mutation_methods(self):
        endpoint=self.endpoint();endpoint.pop("exportInventoryReady")
        content='NFS_Core_Param {\n Protocols = 4;\n}\nEXPORT {\n Export_Id = 1000;\n Path = "/export/share";\n Pseudo = "/share";\n FSAL {\n Name = VFS;\n }\n}\n'
        adapter=Mock()
        adapter.configuration.side_effect=GaneshaDbus.configuration
        adapter.owned_endpoint_manager.side_effect=[ValueError("cold"),("owned","manager")]
        readiness=NfsEndpointReadiness(time.monotonic()+1,lambda **kwargs:adapter,self.command)
        readiness.snapshot=lambda *args:({"pid":os.getpid(),"startTicks":"123","configurationSha256":"a"*64},content)
        readiness.wait(endpoint,"/unused")
        self.assertTrue(endpoint["exportInventoryReady"])
        adapter.verify.assert_called_once()
        self.assertFalse(adapter.transition.called)

    def test_unknown_or_foreign_endpoint_process_is_not_a_reusable_boot_owner(self):
        config=self.root/"known.conf";config.write_text("known");config.chmod(0o600)
        readiness=NfsEndpointReadiness(time.monotonic()+1,Mock(),self.command,config_root=self.root)
        with self.assertRaises(ValueError):readiness.snapshot(config,os.getpid())
        config.unlink();config.symlink_to(self.root/"foreign")
        with self.assertRaises(ValueError):readiness.snapshot(config,os.getpid(),False)

    def test_planner_matches_dual_collapse_and_actual_selected_ip_port_expansion(self):
        payload={"exports":[{"uuid":str(index),"config":{"endpointMode":"SELECTED","listenIps":["10.1.1.1","10.1.1.2"],
                  "listenerGroupPorts":[2049,2050,2051]}} for index in range(100)]}
        self.assertEqual({"probeTasks":600,"endpointModeGroups":6,"concurrency":1},nfs.nfs_boot_workload(payload))
        payload["protocolMode"]="V3V4_DUAL"
        self.assertEqual({"probeTasks":200,"endpointModeGroups":2,"concurrency":1},nfs.nfs_boot_workload(payload))
        payload["enabled"]=False
        self.assertEqual(0,nfs.nfs_boot_workload(payload)["probeTasks"])

    def test_mount_directory_symlink_or_unprotected_parent_rejected_before_mount(self):
        unsafe=self.root/"unsafe";unsafe.mkdir(mode=0o777);unsafe.chmod(0o777)
        self.runner.root=str(unsafe)
        failures,endpoint,_=self.run_exports()
        self.assertTrue(failures);self.assertFalse(endpoint["probeSuccess"])
        self.assertFalse(any(call[0]=="mount" for call in self.calls))

    def test_exact_live_endpoint_snapshot_pins_configuration_and_process_start(self):
        config=self.root/"known.conf";config.write_text("known");config.chmod(0o600)
        child=subprocess.Popen(["ganesha.nfsd","-c","import time;time.sleep(20)","-f",str(config)],executable=sys.executable)
        try:
            readiness=NfsEndpointReadiness(time.monotonic()+1,Mock(),self.command,config_root=self.root)
            deadline=time.monotonic()+1
            while time.monotonic()<deadline:
                try:
                    binding,data=readiness.snapshot(config,child.pid);break
                except ValueError:time.sleep(.01)
            else:self.fail("Owned test process did not enter its exact argv")
            self.assertEqual(hashlib.sha256(b"known").hexdigest(),binding["configurationSha256"])
            self.assertEqual(child.pid,binding["pid"]);self.assertEqual("known",data)
            config.write_text("changed")
            changed,_=readiness.snapshot(config,child.pid)
            self.assertNotEqual(binding["configurationSha256"],changed["configurationSha256"])
        finally:
            child.terminate();child.wait(timeout=2)

    def test_legacy_boot_detail_verifier_requires_exact_payload_and_does_not_apply_or_mount(self):
        path=self.root/"saved.json";data=json.dumps({"enabled":False,"exports":[]}).encode()
        path.write_bytes(data);path.chmod(0o600)
        desired="a"*64
        record={"bootId":self.runner.boot_id,"desiredHash":desired,
                "_internal":{"payloadDigests":{"NFS":hashlib.sha256(data).hexdigest()}}}
        cache=self.root/"legacy-cache"
        class Reader(NfsProbeRunner):
            def __init__(inner,*args,**kwargs):
                super().__init__(*args,**kwargs);inner.cache_root=cache
        with patch.dict(os.environ,{"ABLESTACK_STORAGE_BOOT_DESIRED_HASH":desired}):
            reader=Reader({},self.root,time.monotonic()+2,None)
            reader.write(cache/"summary.json",{"bootId":reader.boot_id,"desiredHash":desired,"endpoints":[],
                "successKeys":[],"success":True,"completedProbeCount":0,"totalProbeCount":0,"skippedAclCount":0,"detailVerificationKind":"NOT_REQUIRED"})
            before={str(item.relative_to(self.root)):hashlib.sha256(item.read_bytes()).hexdigest() for item in self.root.rglob("*") if item.is_file()}
            command=Mock(side_effect=AssertionError("No probe/apply expected"))
            with patch.object(nfs,"NfsProbeRunner",Reader):
                result=nfs.verify_nfs_boot_payload(path,record,Mock(),command,time.monotonic()+2)
                self.assertTrue(result["detailVerified"]);command.assert_not_called()
                foreign=json.loads(json.dumps(record));foreign["_internal"]["payloadDigests"]["NFS"]="b"*64
                with self.assertRaises(ValueError):nfs.verify_nfs_boot_payload(path,foreign,Mock(),command,time.monotonic()+2)
                path.chmod(0o666)
                with self.assertRaises(ValueError):nfs.verify_nfs_boot_payload(path,record,Mock(),command,time.monotonic()+2)
                path.chmod(0o600)
            after={str(item.relative_to(self.root)):hashlib.sha256(item.read_bytes()).hexdigest() for item in self.root.rglob("*") if item.is_file()}
            self.assertEqual(before,after)

    def test_embedded_readonly_verifier_rejects_unbound_call_without_remote_or_resource_write(self):
        cli=LIB.parents[3]/"bin/ablestack-storagectl"
        cli=Path(__file__).resolve().parents[2]/"systemvm/debian/usr/local/bin/ablestack-storagectl"
        environment=dict(os.environ)
        environment.pop("ABLESTACK_STORAGE_BOOT_COORDINATOR",None)
        environment["ABLESTACK_STORAGE_WRITER_LOCK_FILE"]=str(self.root/"must-not-create.lock")
        result=subprocess.run([str(cli),"nfs","export","boot-verify",str(self.root/"unread-payload")],
            capture_output=True,text=True,env=environment,timeout=5)
        self.assertEqual(1,result.returncode);self.assertEqual("",result.stderr)
        value=json.loads(result.stdout);self.assertFalse(value["success"]);self.assertFalse(value["detailVerified"])
        self.assertEqual("NFS_BOOT_DETAIL_UNVERIFIED",value["lastFailure"]["code"])
        self.assertEqual([],list(self.root.iterdir()))

    def test_real_injected_probe_child_keeps_fd9_until_bounded_pidfd_cleanup(self):
        lock=self.root/"writer.lock";ready=self.root/"child.ready"
        descriptor=os.open(lock,os.O_RDWR|os.O_CREAT,0o600)
        saved=None
        try:
            try:saved=os.dup(9)
            except OSError:pass
            if descriptor!=9:os.dup2(descriptor,9)
            fcntl.flock(9,fcntl.LOCK_EX|fcntl.LOCK_NB)
            errors=[]
            def run():
                try:
                    runner=NfsProbeRunner({},self.root,time.monotonic()+4,None)
                    runner.run_command([sys.executable,"-c","import os,sys,time;os.fstat(9);open(sys.argv[1],'w').write('ready');time.sleep(60)",str(ready)],1)
                except subprocess.TimeoutExpired as failure:errors.append(failure)
            with patch.dict(os.environ,{"ABLESTACK_STORAGE_WRITER_LOCK_FD":"9","ABLESTACK_STORAGE_NFS_PROBE_JOURNAL":str(self.root/"child"/"current.json")}):
                worker=threading.Thread(target=run);worker.start()
                limit=time.monotonic()+1
                while not ready.exists() and time.monotonic()<limit:time.sleep(.01)
                self.assertTrue(ready.exists(),"Injected probe must attest FD9 before checking ownership")
                os.close(9)
                if descriptor!=9:os.close(descriptor);descriptor=-1
                probe=os.open(lock,os.O_RDONLY)
                try:
                    with self.assertRaises(BlockingIOError):fcntl.flock(probe,fcntl.LOCK_EX|fcntl.LOCK_NB)
                    worker.join(timeout=3);self.assertFalse(worker.is_alive())
                    self.assertEqual(1,len(errors));self.assertFalse(errors[0].childActive)
                    fcntl.flock(probe,fcntl.LOCK_EX|fcntl.LOCK_NB)
                finally:os.close(probe)
        finally:
            if descriptor>=0:
                try:os.close(descriptor)
                except OSError:pass
            if saved is not None:os.dup2(saved,9);os.close(saved)

    def test_known_boot_endpoint_reuse_preserves_pid_and_invalidates_configuration_change(self):
        config=self.root/"known.conf";config.write_text("known");config.chmod(0o600)
        child=subprocess.Popen(["ganesha.nfsd","-c","import time;time.sleep(20)","-f",str(config)],executable=sys.executable)
        command=Mock(return_value=subprocess.CompletedProcess([],0,str(child.pid),""))
        try:
            readiness=NfsEndpointReadiness(time.monotonic()+2,Mock(),command,config_root=self.root)
            readiness.records.cache_enabled=True;readiness.records.cache_root=self.root/"endpoints"
            deadline=time.monotonic()+1
            while time.monotonic()<deadline:
                try:identity=readiness.remember("known",config,child.pid);break
                except ValueError:time.sleep(.01)
            else:self.fail("Owned process argv not observed")
            self.assertEqual(identity,readiness.reuse("known",config))
            self.assertEqual(child.pid,readiness.reuse("known",config)["pid"])
            self.assertTrue(all(call.args[0][1]=="show" for call in command.call_args_list))
            config.write_text("changed")
            self.assertIsNone(readiness.reuse("known",config))
        finally:
            child.terminate();child.wait(timeout=2)

    def test_permission_failure_is_structured_nonretryable_and_never_exposes_stderr(self):
        secret="SYNTHETIC_PRIVATE_BODY_MUST_NOT_LEAK"
        original=self.runner.run_command
        def command(args,maximum,context=None):
            if args[0]=="mount":return subprocess.CompletedProcess(args,32,"","access denied "+secret)
            return original(args,maximum,context)
        self.runner.run_command=command
        failures,endpoint,_=self.run_exports()
        self.assertTrue(failures)
        failure=endpoint["probeResults"][0]["probes"][0]["failure"]
        self.assertEqual("PROBE_PERMISSION_DENIED",failure["code"]);self.assertFalse(failure["retryable"])
        self.assertNotIn(secret,json.dumps(endpoint))
        self.assertEqual([],list(self.root.iterdir()))

    def test_child_gone_journal_observation_error_is_structured_and_keeps_cleanup_unknown(self):
        child=Mock(pid=77777777,returncode=0)
        child.poll.return_value=0;child.communicate.return_value=("","")
        original_close=os.close
        self.runner.run_command=NfsProbeRunner.run_command.__get__(self.runner)
        self.runner.write=Mock(side_effect=OSError("SYNTHETIC_PRIVATE_JOURNAL_ERROR"))
        self.runner.mounted=Mock(return_value=True)
        with patch.dict(os.environ,{"ABLESTACK_STORAGE_WRITER_LOCK_FD":"9"}), \
             patch("nfs_readiness.subprocess.Popen",return_value=child), \
             patch("nfs_readiness.os.pidfd_open",return_value=999999), \
             patch("nfs_readiness.os.close",side_effect=lambda fd:None if fd==999999 else original_close(fd)), \
             patch("nfs_readiness.signal.pidfd_send_signal") as signals:
            with self.assertRaises(NfsReadinessError) as caught:
                self.runner.run_command(["mount","fixed"],15)
            self.assertFalse(caught.exception.public["cleanup"]["complete"])
            self.assertFalse(caught.exception.public["cleanup"]["childActive"])
            self.assertNotIn("SYNTHETIC",json.dumps(caught.exception.public));signals.assert_not_called()

    def test_cleanup_observation_and_type_errors_never_allow_a_second_mount(self):
        for error in (OSError("private"),ValueError("private"),TypeError("private")):
            self.calls.clear();self.runner.blocked=False;self.runner.completed=0;self.runner.total=0
            self.runner.mounted=Mock(side_effect=error)
            failures,endpoint,_=self.run_exports(3)
            self.assertTrue(failures);self.assertFalse(endpoint["probeSuccess"])
            self.assertEqual(1,len([call for call in self.calls if call[0]=="mount"]))
            failure=endpoint["lastFailure"]
            self.assertFalse(failure["cleanup"]["complete"])
            self.assertNotIn("private",json.dumps(endpoint))

    def test_exited_mount_child_journal_error_and_mounted_residue_preserve_the_exact_failure(self):
        child=Mock(pid=77777777,returncode=0);child.poll.return_value=0;child.communicate.return_value=("","")
        original_close=os.close;owned=NfsProbeRunner.run_command.__get__(self.runner)
        commands=[]
        def command(args,maximum,context=None):
            if args[0]=="ip":return self.command(args,maximum,context)
            commands.append(args)
            return owned(args,maximum,context)
        self.runner.run_command=command
        self.runner.write=Mock(side_effect=TypeError("SYNTHETIC_PRIVATE_BAD_JSON"))
        self.runner.mounted=Mock(return_value=True)
        with patch.dict(os.environ,{"ABLESTACK_STORAGE_WRITER_LOCK_FD":"9"}), \
             patch("nfs_readiness.subprocess.Popen",return_value=child), \
             patch("nfs_readiness.os.pidfd_open",return_value=999999), \
             patch("nfs_readiness.os.close",side_effect=lambda fd:None if fd==999999 else original_close(fd)), \
             patch("nfs_readiness.signal.pidfd_send_signal") as signals:
            failures,endpoint,_=self.run_exports(3)
        self.assertTrue(failures);self.assertEqual(1,len([args for args in commands if args[0]=="mount"]))
        failure=endpoint["lastFailure"]
        self.assertEqual("PROBE_CHILD_UNOBSERVABLE",failure["code"])
        self.assertEqual(self.export()["uuid"],failure["exportUuid"])
        self.assertFalse(failure["cleanup"]["complete"]);self.assertTrue(failure["cleanup"]["mountPresent"])
        self.assertFalse(failure["cleanup"]["childActive"]);signals.assert_not_called()
        self.assertNotIn("SYNTHETIC",json.dumps(endpoint))

    def test_acl_skip_metadata_is_explicit_and_never_counted_as_a_mount_success(self):
        self.runner.cache_enabled=True
        self.runner.select_host=lambda *args:(None,"local ACL does not allow this client")
        failures,endpoint,_=self.run_exports()
        self.assertFalse(failures);self.assertEqual(0,endpoint["completedProbeCount"])
        self.assertEqual(0,len([args for args in self.calls if args[0]=="mount"]))
        report=self.runner.verified_summary([endpoint])
        self.assertEqual(1,report["skippedAclCount"])
        self.assertEqual("EXACT_INVENTORY_AND_ELIGIBLE_MOUNTS",report["detailVerificationKind"])
        value=json.loads((self.runner.cache_root/"summary.json").read_text())
        value.pop("detailVerificationKind")
        (self.runner.cache_root/"summary.json").write_text(json.dumps(value))
        with self.assertRaises(ValueError):self.runner.verified_summary([endpoint])

    def test_pidfd_signal_error_keeps_the_owned_child_pending_without_pid_fallback(self):
        child=Mock(pid=77777777,returncode=None);child.poll.return_value=None
        child.communicate.side_effect=subprocess.TimeoutExpired(["mount"],15)
        original_close=os.close
        runner=NfsProbeRunner({},self.root,time.monotonic()+2,None)
        runner.write=Mock()
        with patch.dict(os.environ,{"ABLESTACK_STORAGE_WRITER_LOCK_FD":"9"}), \
             patch("nfs_readiness.subprocess.Popen",return_value=child), \
             patch("nfs_readiness.os.pidfd_open",return_value=999999), \
             patch("nfs_readiness.os.close",side_effect=lambda fd:None if fd==999999 else original_close(fd)), \
             patch("nfs_readiness.signal.pidfd_send_signal",side_effect=PermissionError("private")) as signals:
            with self.assertRaises(subprocess.TimeoutExpired) as caught:runner.run_command(["mount","fixed"],15)
        self.assertTrue(caught.exception.childActive);self.assertEqual(2,signals.call_count)
        self.assertEqual("RECOVERY_REQUIRED",runner.write.call_args.args[1]["phase"])

    def test_actual_ctl_unknown_observation_result_never_invents_cleanup_complete(self):
        from types import SimpleNamespace
        cli=Path(__file__).resolve().parents[2]/"systemvm/debian/usr/local/bin/ablestack-storagectl"
        body=next(body for body in re.findall(r"<<'PY'\n(.*?)\nPY",cli.read_text(),re.S)
                  if "def probe_nfs_export_visibility(" in body)
        nodes=ast.parse(body).body
        begin=next(index for index,node in enumerate(nodes) if isinstance(node,ast.Assign)
                   and any(isinstance(target,ast.Name) and target.id=="runtime_endpoints" for target in node.targets))
        endpoint={"listenIp":"127.0.0.1","port":2049,"listening":True,"exportInventoryReady":True}
        namespace={"BootCoordinator":SimpleNamespace(attach_existing=lambda:None),"NfsReadinessError":NfsReadinessError,
            "subprocess":subprocess,"json":json,"payload":{"exports":[]},"enabled":True,"configs":[("known",)],
            "quota_state":{},"endpoint_exports":{},"needs_rpcbind":False,"default_port":2049,"start_ganesha_endpoints":lambda *args:[endpoint],
            "probe_nfs_export_visibility":Mock(side_effect=TypeError("SYNTHETIC_RAW_BODY_MUST_NOT_LEAK"))}
        output=io.StringIO()
        with redirect_stdout(output):
            with self.assertRaises(SystemExit) as caught:exec(compile(ast.Module(body=nodes[begin:],type_ignores=[]),str(cli),"exec"),namespace)
        self.assertEqual(1,caught.exception.code)
        value=json.loads(output.getvalue())
        self.assertFalse(value["success"]);self.assertFalse(value["lastFailure"]["cleanup"]["complete"])
        self.assertFalse(value["lastFailure"]["retryable"]);self.assertNotIn("SYNTHETIC",output.getvalue())


if __name__=="__main__":unittest.main()
