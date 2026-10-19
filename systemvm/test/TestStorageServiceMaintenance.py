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

import ast,copy,fcntl,json,os,stat,sys,tempfile,types,unittest,uuid
from unittest.mock import patch
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
    def completed_formatter_files(self):
        directory=Path(os.environ["ABLESTACK_STORAGE_VOLUME_OPERATIONS"]);directory.mkdir(mode=0o700)
        records={};locks=[]
        for _ in range(2):
            volume=str(uuid.uuid4());path=directory/(volume+".json")
            record={"volumeUuid":volume,"phase":"COMPLETE","formatStarted":False,"terminationPending":False}
            path.write_text(json.dumps(record));path.chmod(0o600);records[volume]=record
            lock=directory/(volume+".lock");lock.write_bytes(b"");lock.chmod(0o644);locks.append(lock)
        original=self.service.command
        def command(args,payload=None):
            if args==("volume","operation","status"):
                return {"success":True,"status":records[payload["volumeUuid"]]["phase"],"formatterActive":False,
                        "formatterObservationComplete":True,"terminationPending":False}
            return original(args,payload)
        self.service.command=command
        return directory,locks
    def test_completed_formatter_mutexes_are_preserved_and_released(self):
        directory,locks=self.completed_formatter_files()
        before={p.name:(p.stat().st_ino,p.stat().st_mode,p.read_bytes())for p in directory.iterdir()}
        self.service.enter(self.scope)
        self.assertTrue(self.maintenance.status()["bootHeld"])
        self.assertEqual(before,{p.name:(p.stat().st_ino,p.stat().st_mode,p.read_bytes())for p in directory.iterdir()})
        for path in locks:
            descriptor=os.open(path,os.O_RDONLY|os.O_NOFOLLOW)
            try:fcntl.flock(descriptor,fcntl.LOCK_EX|fcntl.LOCK_NB)
            finally:os.close(descriptor)
    def test_owner_private_mutex_mode_is_also_supported(self):
        directory,locks=self.completed_formatter_files()
        for lock in locks:lock.chmod(0o600)
        self.assertEqual(self.scope,self.service.fence(self.scope))
    def test_held_formatter_mutex_blocks_before_marker_without_deleting_it(self):
        directory,locks=self.completed_formatter_files();fd=os.open(locks[0],os.O_RDWR)
        try:
            fcntl.flock(fd,fcntl.LOCK_EX|fcntl.LOCK_NB)
            with self.assertRaisesRegex(ValueError,"Active formatter lock"):self.service.enter(self.scope)
            self.assertFalse(self.maintenance.marker.exists());self.assertFalse(self.calls);self.assertTrue(locks[0].exists())
        finally:os.close(fd)
    def test_unknown_formatter_entry_and_orphan_mutex_fail_closed(self):
        directory,locks=self.completed_formatter_files()
        unknown=directory/"unknown.tmp";unknown.write_bytes(b"")
        with self.assertRaisesRegex(ValueError,"Unknown formatter journal entry"):self.service.fence(self.scope)
        unknown.unlink();locks[0].with_suffix(".json").unlink()
        with self.assertRaisesRegex(ValueError,"lacks its exact journal"):self.service.fence(self.scope)
        self.assertTrue(locks[0].exists());self.assertFalse(self.maintenance.marker.exists())
    def test_noncanonical_mutex_uuid_is_not_an_alias(self):
        directory,locks=self.completed_formatter_files();bad=directory/"NOT-A-UUID.lock";bad.write_bytes(b"")
        with self.assertRaises(ValueError):self.service.fence(self.scope)
        self.assertTrue(bad.exists())
    def test_mutex_symlink_hardlink_and_writable_modes_are_rejected(self):
        directory,locks=self.completed_formatter_files();path=locks[0];saved=path.read_bytes()
        path.unlink();path.symlink_to(locks[1])
        with self.assertRaisesRegex(ValueError,"entry is unprotected"):self.service.fence(self.scope)
        path.unlink();path.write_bytes(saved);path.chmod(0o644)
        alias=Path(self.temp.name)/"hardlink-alias";os.link(path,alias)
        with self.assertRaisesRegex(ValueError,"entry is unprotected"):self.service.fence(self.scope)
        alias.unlink()
        for mode in(0o666,0o744,0o4644):
            with self.subTest(mode=mode):
                path.chmod(mode)
                with self.assertRaisesRegex(ValueError,"entry is unprotected"):self.service.fence(self.scope)
        self.assertFalse(self.maintenance.marker.exists())
    def test_mutex_foreign_owner_is_rejected_without_real_chown(self):
        directory,locks=self.completed_formatter_files();original=Path.lstat
        def owner(path):
            info=original(path)
            if path==locks[0]:
                row={k:getattr(info,k)for k in dir(info)if k.startswith("st_")};row["st_uid"]+=1
                return types.SimpleNamespace(**row)
            return info
        with patch.object(Path,"lstat",owner):
            with self.assertRaisesRegex(ValueError,"entry is unprotected"):self.service.fence(self.scope)
        self.assertFalse(self.maintenance.marker.exists())
    def test_unknown_active_or_termination_pending_formatter_status_is_rejected(self):
        directory,locks=self.completed_formatter_files();original=self.service.command
        for change in({"formatterActive":True},{"formatterActive":None},{"formatterObservationComplete":False},
                       {"terminationPending":True},{"status":"RECONCILE_REQUIRED"},{"success":False}):
            def command(args,payload=None):
                value=original(args,payload)
                return {**value,**change}if args==("volume","operation","status")else value
            self.service.command=command
            with self.subTest(change=change):
                with self.assertRaisesRegex(ValueError,"Unresolved formatter"):self.service.fence(self.scope)
        self.assertFalse(self.maintenance.marker.exists());self.assertTrue(all(p.exists()for p in locks))
    def test_mutex_inode_replacement_during_status_observation_is_rejected(self):
        directory,locks=self.completed_formatter_files();original=self.service.command
        def command(args,payload=None):
            value=original(args,payload)
            if args==("volume","operation","status")and locks[0].exists():
                locks[0].unlink();locks[0].write_bytes(b"");locks[0].chmod(0o644)
            return value
        self.service.command=command
        with self.assertRaisesRegex(ValueError,"identity changed during observation"):self.service.fence(self.scope)
        self.assertFalse(self.maintenance.marker.exists())
    def test_whole_os_model_has_persistent_mutexes_and_real_status_projection(self):
        import volume_formatter
        repository=Path(__file__).resolve().parents[2]
        fixture=repository/"server/src/test/resources/storage-config/rendered-local-os-fixture.py"
        tree=ast.parse(fixture.read_text());cls=next(n for n in tree.body if isinstance(n,ast.ClassDef)and n.name=="RenderedGuestFixture")
        selected=[n for n in cls.body if isinstance(n,ast.FunctionDef)and n.name in("initialize_formatter_journals","formatter_status")]
        dummy=ast.ClassDef(name="MountedOsModel",bases=[],keywords=[],body=selected,decorator_list=[])
        namespace={"Path":Path,"json":json,"os":os,"time":__import__("time"),"require":lambda v,c:None if v else (_ for _ in()).throw(ValueError(c))}
        exec(compile(ast.fix_missing_locations(ast.Module(body=[dummy],type_ignores=[])),"actual_whole_os_journal_model","exec"),namespace)
        model=namespace["MountedOsModel"]();model.case=Path(self.temp.name);model.effects=[]
        model.path_guard=types.SimpleNamespace(path=lambda p:self.assertTrue(p.resolve().is_relative_to(model.case.resolve())))
        model.initialize_formatter_journals()
        self.assertFalse((model.case/"volume-operations").exists())
        witness=model.case/"witness-os-fixture.json";witness.write_text("{}")
        with self.assertRaises(ValueError):model.initialize_formatter_journals()
        self.assertFalse((model.case/"volume-operations").exists())
        volume=str(uuid.uuid4());fs=str(uuid.uuid4());boot=str(uuid.uuid4())
        facts={"bootId":boot,"dataVolumes":[{"volumeUuid":volume,"sizeBytes":20<<30,"filesystem":"ext4",
                "filesystemUuid":fs,"mounts":[{"target":"/srv/ablestack-storage/volumes/"+volume,"uuid":fs}]}]}
        (model.case/"witness-os-fixture.json").write_text(json.dumps(facts))
        model.initialize_formatter_journals()
        cli=(repository/"systemvm/debian/usr/local/bin/ablestack-storagectl").read_text()
        block=cli.split("volume_operation_status() {",1)[1].split("<<'PYVOLUME'\n",1)[1].split("\nPYVOLUME",1)[0]
        functions=[n for n in ast.parse(block).body if isinstance(n,ast.FunctionDef)and n.name in("read_volume_operation","project_volume_operation")]
        model.volume_status_namespace={"json":json,"os":os,"stat":stat};exec(compile(ast.Module(body=functions,type_ignores=[]),"real_volume_status","exec"),model.volume_status_namespace)
        model.volume_formatter_module=volume_formatter
        observed=model.formatter_status({"volumeUuid":volume})
        self.assertEqual("COMPLETE",observed["status"]);self.assertFalse(observed["formatterActive"])
        self.assertTrue(observed["formatterObservationComplete"])
        self.assertEqual({volume+".json",volume+".lock"},{p.name for p in(model.case/"volume-operations").iterdir()})
        with patch.dict(os.environ,{"ABLESTACK_STORAGE_VOLUME_OPERATIONS":str(model.case/"volume-operations")}):
            self.service.command=lambda args,payload=None:model.formatter_status(payload)
            self.assertEqual(self.scope,self.service.fence(self.scope))
        self.assertTrue((model.case/"volume-operations"/(volume+".lock")).exists())
        journal=model.case/"volume-operations"/(volume+".json");original=journal.read_bytes()
        changed=copy.deepcopy(facts);newuuid=str(uuid.uuid4())
        changed["dataVolumes"][0]["filesystemUuid"]=newuuid;changed["dataVolumes"][0]["mounts"][0]["uuid"]=newuuid
        witness.write_text(json.dumps(changed));model.initialize_formatter_journals()
        self.assertEqual(original,journal.read_bytes())
        with self.assertRaisesRegex(ValueError,"OS_BINDING_CHANGED"):model.formatter_status({"volumeUuid":volume})
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


class StorageServiceDomainInventoryTest(unittest.TestCase):
    """Exercise production inventory/proof against private /proc and config paths."""
    def setUp(self):
        import fnmatch
        self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup)
        self.root=Path(self.temp.name);self.calls=[];self.stops=[];self.show_counts={}
        self.nfs="ablestack-storage-ganesha@10.10.13.227_2049.service"
        self.smb="ablestack-storage-smb@"+"a"*24+".service"
        self.active={self.nfs,self.smb};self.change_before_second_show=None
        self.operation=str(uuid.uuid4());self.instance=str(uuid.uuid4())
        self.scope={"instanceUuid":self.instance,"maintenanceUuid":self.operation,"operationUuid":self.operation,"revision":2}
        self.generation={"generation":{"instanceUuid":self.instance,"operationUuid":self.operation,"revision":1,
                           "configurationSha256":"a"*64},"configurationSha256":"a"*64,
                         "generationStatus":"PENDING","pendingOperationUuid":self.operation}
        self.maintenance=Maintenance(self.root/"state",lambda:self.generation)
        self.maintenance.enter(self.scope,"SERVICE")
        self.maintenance.write(self.root/"state/service-maintenance.json",{"scope":self.scope,"phase":"HELD"})
        self.private_file("/etc/ablestack-storage/smb-endpoint-listeners/"+"a"*24+".json",
                          json.dumps({"listenIp":"10.10.13.227","port":445}),0o600)
        configuration=self.mapped("/etc/ganesha/ablestack-storage/10.10.13.227_2049.conf")
        configuration.parent.mkdir(parents=True,mode=0o700);configuration.symlink_to(self.root/"missing-current/nfs.conf")
        self.process(self.nfs,51001,"/opt/ablestack-ganesha/5.5.3/bin/ganesha.nfsd",
                     ["ganesha.nfsd","-f","/etc/ganesha/ablestack-storage/10.10.13.227_2049.conf"])
        self.process(self.smb,51002,"/usr/sbin/smbd",["smbd","--configfile=/etc/samba/smb.conf"])
        self.pid_by_unit={self.nfs:51001,self.smb:51002}
        def run(arguments,**kwargs):
            self.calls.append(arguments)
            if arguments[:2]==["systemctl","list-units"]:
                patterns=arguments[6:]
                return "".join(unit+" loaded active running\n" for unit in sorted(self.active)
                               if any(fnmatch.fnmatchcase(unit,pattern) for pattern in patterns))
            if arguments[:2]==["systemctl","show"]:
                unit=arguments[2];self.show_counts[unit]=self.show_counts.get(unit,0)+1
                if self.show_counts[unit]==2 and self.change_before_second_show:self.change_before_second_show(unit)
                return "MainPID="+str(self.pid_by_unit[unit])+"\nActiveState=active\nSubState=running\n"
            if arguments[:2]==["systemctl","stop"]:
                self.stops.append(arguments[2]);self.active.remove(arguments[2]);return ""
            if arguments[0]=="ss":return ""
            raise AssertionError("Unexpected fixture command: "+repr(arguments))
        self.service=ServiceMaintenance(self.maintenance,"unused",run)
        self.patch_path=patch("service_maintenance.Path",self.mapped);self.patch_path.start();self.addCleanup(self.patch_path.stop)
        self.patch_environment=patch.dict(os.environ,{"ABLESTACK_STORAGE_VOLUME_OPERATIONS":str(self.root/"absent-volumes")})
        self.patch_environment.start();self.addCleanup(self.patch_environment.stop)

    def mapped(self,value):
        path=Path(value)
        return self.root/str(path).lstrip("/") if str(path).startswith(("/etc/","/proc/","/run/")) or str(path)=="/proc" else path

    def private_file(self,name,content,mode=0o600):
        path=self.mapped(name);path.parent.mkdir(parents=True,exist_ok=True,mode=0o700)
        path.write_text(content);path.chmod(mode);return path

    def process(self,unit,pid,executable,arguments,start_ticks="12345"):
        proc=self.mapped("/proc")/str(pid);proc.mkdir(parents=True,exist_ok=True,mode=0o700)
        (proc/"exe").symlink_to(executable)
        (proc/"cmdline").write_bytes(("\0".join(arguments)+"\0").encode())
        (proc/"cgroup").write_text("0::/system.slice/"+unit+"\n")
        (proc/"stat").write_text(str(pid)+" (private-fixture) S "+"0 "*18+start_ticks+"\n")

    def quiesce(self,domains):
        return self.service.quiesce({**self.scope,"domains":domains})

    def test_smb_inverse_uses_real_proof_without_opening_dangling_nfs_configuration(self):
        self.assertTrue(self.mapped("/etc/ganesha/ablestack-storage/10.10.13.227_2049.conf").is_symlink())
        result=self.quiesce(["SMB"])
        self.assertEqual([self.smb],result["stoppedUnits"]);self.assertTrue(result["quiesced"])
        self.assertEqual({self.nfs},self.active);self.assertNotIn(self.nfs,self.show_counts)
        for call in self.calls:
            if call[:2]==["systemctl","list-units"]:
                self.assertNotIn("ablestack-storage-ganesha@*.service",call)
                self.assertIn("smbd.service",call)
        self.assertTrue(self.maintenance.status()["bootHeld"])

    def test_selected_nfs_and_both_domains_still_validate_dangling_configuration(self):
        for domains in (["NFS"],["NFS","SMB"]):
            with self.subTest(domains=domains):
                with self.assertRaises(FileNotFoundError):self.quiesce(domains)
                self.assertFalse(self.stops)
        self.assertNotIn(self.smb,self.show_counts)

    def test_full_enter_still_validates_every_owned_acceptor_before_effects(self):
        self.maintenance.marker.unlink();self.service.journal.unlink()
        with self.assertRaises(FileNotFoundError):self.service.enter(self.scope)
        self.assertFalse(self.stops);self.assertFalse(self.maintenance.marker.exists())

    def test_selected_foreign_smb_is_rejected_before_any_owned_stop(self):
        foreign="ablestack-storage-smb@"+"b"*24+".service";self.active.add(foreign);self.pid_by_unit[foreign]=51003
        self.process(foreign,51003,"/usr/sbin/sshd",["sshd","--configfile=/etc/samba/smb.conf"])
        self.private_file("/etc/ablestack-storage/smb-endpoint-listeners/"+"b"*24+".json",
                          json.dumps({"listenIp":"10.10.13.227","port":1445}))
        with self.assertRaisesRegex(ValueError,"executable is outside"):self.quiesce(["SMB"])
        self.assertFalse(self.stops);self.assertNotIn(self.nfs,self.show_counts)

    def test_selected_legacy_acceptors_remain_rejected(self):
        for legacy in ("smbd.service","nmbd.service","winbind.service"):
            with self.subTest(unit=legacy):
                self.active.add(legacy)
                try:
                    with self.assertRaisesRegex(ValueError,"foreign or legacy"):self.quiesce(["SMB"])
                    self.assertFalse(self.stops)
                finally:self.active.remove(legacy)

    def test_unselected_legacy_nfs_does_not_block_smb_inverse(self):
        self.active.add("nfs-server.service")
        self.assertTrue(self.quiesce(["SMB"])["quiesced"])
        self.assertEqual({self.nfs,"nfs-server.service"},self.active)

    def test_selected_pid_start_identity_is_rechecked_before_stop(self):
        def change(unit):
            self.private_file("/proc/"+str(self.pid_by_unit[unit])+"/stat",
                              str(self.pid_by_unit[unit])+" (private-fixture) S "+"0 "*18+"changed\n")
        self.change_before_second_show=change
        with self.assertRaisesRegex(ValueError,"changed before inverse"):self.quiesce(["SMB"])
        self.assertFalse(self.stops)

    def test_selected_cgroup_and_protected_endpoint_checks_remain_strict(self):
        cgroup=self.private_file("/proc/51002/cgroup","0::/system.slice/foreign.service\n")
        with self.assertRaisesRegex(ValueError,"outside its owned unit"):self.quiesce(["SMB"])
        cgroup.write_text("0::/system.slice/"+self.smb+"\n")
        self.mapped("/etc/ablestack-storage/smb-endpoint-listeners/"+"a"*24+".json").chmod(0o644)
        with self.assertRaisesRegex(ValueError,"record is not protected"):self.quiesce(["SMB"])
        self.assertFalse(self.stops)

    def test_scoped_inventory_rejects_cross_domain_systemctl_readback(self):
        original=self.service.run
        def run(arguments,**kwargs):
            if arguments[:2]==["systemctl","list-units"]:return self.nfs+" loaded active running\n"
            return original(arguments,**kwargs)
        self.service.run=run
        with self.assertRaisesRegex(ValueError,"escaped its requested domains"):self.quiesce(["SMB"])
        self.assertFalse(self.stops);self.assertFalse(self.show_counts)


class StorageSmbControllerOwnershipTest(unittest.TestCase):
    """The packaged endpoint runner owns a distinct, pidfile-pinned smbd."""
    def setUp(self):
        import hashlib
        self.fixture=StorageServiceDomainInventoryTest("test_smb_inverse_uses_real_proof_without_opening_dangling_nfs_configuration")
        self.fixture.setUp();self.addCleanup(self.fixture.doCleanups);f=self.fixture
        self.key=hashlib.sha256(b"10.10.13.227:445").hexdigest()[:24]
        self.unit="ablestack-storage-smb@"+self.key+".service"
        f.active.remove(f.smb);f.active.add(self.unit)
        self.controller=51010;self.daemon=51011;f.pid_by_unit[self.unit]=self.controller
        f.service.cli="/usr/local/bin/ablestack-storagectl"
        f.private_file("/etc/ablestack-storage/smb-endpoint-listeners/"+self.key+".json",
                       json.dumps({"listenIp":"10.10.13.227","port":445}),0o600)
        self.controller_args=["/bin/bash",f.service.cli,"smb","endpoint-run",self.key]
        self.daemon_args=["/usr/sbin/smbd","--foreground","--no-process-group","-s","/etc/samba/smb.conf",
                          "--option=smb ports=445","--option=pid directory=/run/ablestack-storage/smb/"+self.key,
                          "--option=interfaces=10.10.13.227","--option=bind interfaces only=yes"]
        f.process(self.unit,self.controller,"/usr/bin/bash",self.controller_args,"201")
        f.process(self.unit,self.daemon,"/usr/sbin/smbd",self.daemon_args,"202")
        self.group="0::/system.slice/system-ablestack\\x2dstorage\\x2dsmb.slice/"+self.unit+"\n"
        for pid in (self.controller,self.daemon):f.private_file("/proc/"+str(pid)+"/cgroup",self.group)
        self.stat(self.daemon,self.controller,"202")
        directory=f.mapped("/run/ablestack-storage/smb/"+self.key);directory.mkdir(parents=True,mode=0o750)
        self.pidfile=f.private_file("/run/ablestack-storage/smb/"+self.key+"/smbd.pid",str(self.daemon)+"\n",0o644)
        from root_source_identity_checkpoint import RootSourceIdentityCheckpoint
        from service_identity_source import ServicePublicIdentity
        runtime=types.SimpleNamespace(cli=f.service.cli)
        def run(arguments,**kwargs):return types.SimpleNamespace(returncode=0,stdout=f.service.run(arguments))
        parameters=(runtime,f.mapped("/etc/ablestack-storage"),run,f.mapped("/proc"))
        self.identities=[RootSourceIdentityCheckpoint(*parameters),ServicePublicIdentity(*parameters)]
    def stat(self,pid,parent,ticks):
        return self.fixture.private_file("/proc/"+str(pid)+"/stat",
               str(pid)+" (private-controller-fixture) S "+str(parent)+" "+"0 "*17+ticks+"\n")
    def args(self,pid,arguments):
        (self.fixture.mapped("/proc")/str(pid)/"cmdline").write_bytes(("\0".join(arguments)+"\0").encode())
    def reject(self,pattern=None):
        f=self.fixture
        readers=[lambda:f.service.prove_unit(self.unit),*[lambda identity=identity:identity.owner(self.unit) for identity in self.identities]]
        for reader in readers:
            if pattern:
                with self.assertRaisesRegex(ValueError,pattern):reader()
            else:
                with self.assertRaises((ValueError,OSError)):reader()
        self.assertFalse(f.stops)

    def test_actual_acceptor_owner_is_identical_for_service_root_and_service_identity(self):
        owner=self.fixture.service.prove_unit(self.unit)
        self.assertEqual(self.daemon,owner["pid"]);self.assertEqual("202",owner["startTicks"])
        self.assertEqual({"unit","pid","startTicks","configurationPath","listenerEndpoints"},set(owner))
        for identity in self.identities:self.assertEqual(owner,identity.owner(self.unit))
        self.assertFalse(self.fixture.stops)
        result=self.fixture.quiesce(["SMB"])
        self.assertEqual([self.unit],result["stoppedUnits"]);self.assertEqual({self.fixture.nfs},self.fixture.active)

    def test_controller_requires_exact_packaged_cli_and_five_arguments(self):
        variants=[["/bin/bash","-c","smbd"],["/bin/bash","/tmp/ablestack-storagectl","smb","endpoint-run",self.key],
                  [*self.controller_args,"extra"],[*self.controller_args[:-1],"b"*24]]
        for arguments in variants:
            with self.subTest(arguments=arguments):
                self.args(self.controller,arguments);self.reject("exact packaged endpoint runner")
        self.args(self.controller,self.controller_args)
        self.fixture.service.cli="/tmp/ablestack-storagectl"
        with self.assertRaisesRegex(ValueError,"exact packaged endpoint runner"):self.fixture.service.prove_unit(self.unit)
        self.assertFalse(self.fixture.stops)

    def test_acceptor_exact_parent_executable_and_cgroup_are_required(self):
        f=self.fixture
        self.stat(self.daemon,1,"202");self.reject("exact packaged controller")
        self.stat(self.daemon,self.controller,"202")
        executable=f.mapped("/proc")/str(self.daemon)/"exe"
        executable.unlink();executable.symlink_to("/usr/sbin/sshd");self.reject("exact packaged controller")
        executable.unlink();executable.symlink_to("/usr/sbin/smbd")
        f.private_file("/proc/"+str(self.daemon)+"/cgroup",self.group.rstrip()+"/foreign-child\n")
        self.reject("exact packaged controller")

    def test_acceptor_configuration_port_pid_directory_and_interface_options_are_exact(self):
        variants=[]
        for index,value in ((4,"/tmp/smb.conf"),(5,"--option=smb ports=1445"),
                            (6,"--option=pid directory=/tmp"),(7,"--option=interfaces=10.10.13.228"),
                            (8,"--option=bind interfaces only=no")):
            changed=list(self.daemon_args);changed[index]=value;variants.append(changed)
        variants.append([*self.daemon_args,"--option=interfaces=0.0.0.0"])
        for arguments in variants:
            with self.subTest(arguments=arguments):
                self.args(self.daemon,arguments);self.reject("exact packaged controller")
        self.args(self.daemon,self.daemon_args)

    def test_pid_receipt_and_its_directory_remain_protected(self):
        self.pidfile.chmod(0o666);self.reject("PID receipt is not protected")
        self.pidfile.chmod(0o644);self.pidfile.parent.chmod(0o777);self.reject("PID directory is not protected")
        self.pidfile.parent.chmod(0o750);self.pidfile.write_text(str(self.controller)+"\n");self.reject("exact packaged controller")
        self.pidfile.unlink();self.pidfile.symlink_to(self.fixture.root/"foreign.pid");self.reject()

    def test_controller_and_acceptor_ticks_cannot_change_during_proof(self):
        import service_maintenance
        original=service_maintenance.service_process_observation
        for victim in (self.controller,self.daemon):
            with self.subTest(pid=victim):
                def observe(process):
                    result=original(process)
                    if process.name==str(victim):
                        self.stat(victim,self.controller if victim==self.daemon else 0,str(int(result["startTicks"])+1))
                    return result
                with patch("service_maintenance.service_process_observation",side_effect=observe):
                    self.reject("identity changed during proof")
                self.stat(self.controller,0,"201");self.stat(self.daemon,self.controller,"202")

    def test_protected_endpoint_key_must_match_controller_and_acceptor(self):
        self.fixture.private_file("/etc/ablestack-storage/smb-endpoint-listeners/"+self.key+".json",
                                  json.dumps({"listenIp":"10.10.13.228","port":445}),0o600)
        self.reject("record differs from its unit")

    def test_direct_main_smbd_retains_the_same_existing_owner_shape(self):
        f=self.fixture
        direct=f.service.prove_unit(f.smb)
        for identity in self.identities:self.assertEqual(direct,identity.owner(f.smb))
        self.assertEqual(51002,direct["pid"]);self.assertEqual("12345",direct["startTicks"])

if __name__=="__main__":unittest.main()
