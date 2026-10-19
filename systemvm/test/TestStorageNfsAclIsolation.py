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

"""#918 actual fixed producers with modelled DBus and zero remote effects."""
import ast, copy, hashlib, importlib.util, json, re, os, subprocess, sys, tempfile, time, unittest, uuid
from pathlib import Path
from unittest.mock import Mock, patch
ROOT=Path(__file__).resolve().parents[2]
LIB=ROOT/"systemvm/debian/usr/local/lib/ablestack-storage"
sys.path.insert(0,str(LIB))
from ganesha_dbus import GaneshaDbus
from nfs_readiness import NfsProbeRunner,NfsEndpointReadiness,NfsProbeObservation
from config_generation import redact as canonical_nfs_redact
dbus_spec=importlib.util.spec_from_file_location("issue918_fixed_ganesha_bus",ROOT/"systemvm/test/TestStorageGaneshaDbus.py")
dbus_fixture=importlib.util.module_from_spec(dbus_spec);dbus_spec.loader.exec_module(dbus_fixture)
EndpointBus,multiport_configuration=dbus_fixture.EndpointBus,dbus_fixture.multiport_configuration
spec=importlib.util.spec_from_file_location("fixed_nfs_probe_fixture",ROOT/"test/systemvm/test_storage_nfs_readiness.py")
fixture=importlib.util.module_from_spec(spec);spec.loader.exec_module(fixture)


def nfs_alias_request_loop(payload, created_directory=None):
    """Run the real request loop and alias helper, with no host FS or service effects."""
    from types import SimpleNamespace
    from native_renderers import fixed_render_helpers, NFS_HELPERS
    cli=ROOT/"systemvm/debian/usr/local/bin/ablestack-storagectl"
    program=next(value for value in re.findall(r"<<'PY'\n(.*?)\nPY",cli.read_text(),re.S)
                 if "def start_ganesha_endpoints("in value)
    tree=ast.parse(program)
    functions=[node for node in tree.body if isinstance(node,ast.FunctionDef)
               and node.name in ("ensure_export_alias","safe_uuid")]
    request_loop=next(node for node in tree.body if isinstance(node,ast.For)
                      and isinstance(node.target,ast.Name)and node.target.id=="export"
                      and any(isinstance(call,ast.Call)and isinstance(call.func,ast.Name)
                              and call.func.id=="ensure_export_alias"for call in ast.walk(node)))
    ns=fixed_render_helpers(cli,"def write_ganesha_configs(",NFS_HELPERS)
    path=SimpleNamespace(join=os.path.join,isdir=lambda p:True,exists=lambda p:True,
                         samefile=lambda a,b:True)
    fake_os=SimpleNamespace(path=path,makedirs=Mock())
    ns.update({"copy":copy,"payload":payload,"os":fake_os,"re":re,
               "enabled":True,"managed_files":set(),"next_alias_state":{},"quota_state":{},
               "endpoint_exports":{},"default_listen_ip":"0.0.0.0","default_port":2049,
               "service_protocol_mode":"V4_ONLY","is_mountpoint":lambda p:True,
               "is_path_on_mount":lambda a,b:True,
               "ensure_directory_below_volume":Mock(return_value=created_directory),
               "verify_share_mount_boundary":Mock(),"apply_posix_permissions":Mock(),
               "fstab_escape":lambda value:value,"update_fstab":Mock(),"daemon_reload":Mock(),
               "run":Mock(side_effect=AssertionError("NFS alias model executed an OS command"))})
    exec(compile(ast.Module(body=functions,type_ignores=[]),str(cli),"exec"),ns)
    with tempfile.TemporaryDirectory()as folder:
        ns["exports_dir"]=folder
        exec(compile(ast.Module(body=[request_loop],type_ignores=[]),str(cli),"exec"),ns)
    return ns


class NfsAclIsolationTest(unittest.TestCase):
    def endpoint(self,port=2049):
        return {"listenIp":"0.0.0.0","port":port,"pid":123,"startTicks":"456",
                "configurationSha256":"a"*64,"configurationTarget":"/immutable/source.conf",
                "processRunning":True,"listening":True,"listenerOwned":True,
                "exportInventoryReady":True}
    def export(self,index=1,principal="*"):
        return {"uuid":str(uuid.uuid5(uuid.NAMESPACE_OID,"issue918-"+str(index))),
                "name":"share"+str(index),"path":"/export/share"+str(index),"pseudo":"/share"+str(index),
                "protocolMode":"V4_ONLY","clients":[{"clients":principal}],
                "appliedClients":[GaneshaDbus.client_identifier(principal)]}
    def ip(self,text=None,code=0):
        return subprocess.CompletedProcess(["ip"],code,text if text is not None else
                "2: eth0 inet 10.1.1.9/24 scope global eth0","")
    def run_cli(self,observations,exports,endpoint=None):
        ns,_=fixture.probe_namespace(observations);endpoint=endpoint or self.endpoint()
        endpoint["exportBindings"]=[{"path":row["path"],"pseudo":row["pseudo"],"exportId":1000+index,
                                      "clients":[{"client":principal} for principal in row["appliedClients"]]}
                                     for index,row in enumerate(exports)]
        with tempfile.TemporaryDirectory() as folder:
            ns["nfs_probe_root"]=folder
            failures=ns["probe_nfs_export_visibility"]([endpoint],{("0.0.0.0",2049):exports})
        return ns,endpoint,failures

    def test_restricted_slash32_and_multiple_cidr_skip_without_mutating_clients(self):
        for principal in ("192.0.2.11/32","192.0.2.0/24,198.51.100.0/24"):
            export=self.export(principal="192.0.2.11/32")
            export["clients"]=[{"clients":principal}];before=copy.deepcopy(export["clients"])
            ns,endpoint,failures=self.run_cli([self.ip()],[export])
            row=endpoint["probeResults"][0]
            self.assertEqual([],failures);self.assertIs(True,row["success"])
            self.assertIs(True,row["probeSkipped"]);self.assertEqual("ACL_RESTRICTED_NO_LOCAL_SOURCE",row["skipReason"])
            self.assertEqual([],row["probes"]);self.assertIsNone(endpoint["exportsVisible"])
            self.assertEqual(before,export["clients"]);self.assertTrue(endpoint["listening"])
            self.assertEqual(1,ns["subprocess"].Popen.call_count)

    def test_local_address_failure_empty_malformed_and_invalid_acl_are_not_skip(self):
        for observation in (self.ip(code=1),self.ip(""),self.ip("2: eth0 inet invalid scope global")):
            ns,endpoint,failures=self.run_cli([observation],[self.export(principal="192.0.2.11/32")])
            self.assertEqual([endpoint],failures);row=endpoint["probeResults"][0]
            self.assertIsNot(True,row["success"]);self.assertFalse(row["probeSkipped"])
            self.assertEqual("LOCAL_ADDRESS_OBSERVATION_FAILED",endpoint["lastFailure"]["code"])
            self.assertEqual(1,ns["subprocess"].Popen.call_count)
        invalid=self.export();invalid["clients"]=[{"clients":"invalid"}]
        ns,endpoint,failures=self.run_cli([self.ip()],[invalid])
        self.assertEqual([endpoint],failures);self.assertFalse(endpoint["probeResults"][0]["probeSkipped"])
        self.assertEqual("CLIENT_ACL_INVALID",endpoint["lastFailure"]["code"])

    def test_skip_requires_every_exact_runtime_health_evidence(self):
        for field in ("processRunning","listening","listenerOwned","exportInventoryReady","pid","startTicks","configurationSha256"):
            endpoint=self.endpoint();endpoint.pop(field)
            ns,endpoint,failures=self.run_cli([self.ip()],[self.export(principal="192.0.2.11")],endpoint)
            self.assertEqual([endpoint],failures);self.assertFalse(endpoint["probeResults"][0]["probeSkipped"])
            self.assertEqual("PROBE_SKIP_RUNTIME_UNVERIFIED",endpoint["lastFailure"]["code"])

    def test_wildcard_local_cidr_and_multiple_cidr_keep_real_mount_and_cleanup(self):
        for principal in ("*","10.1.1.0/24","192.0.2.0/24,10.1.1.9/32"):
            export=self.export();export["clients"]=[{"clients":principal}]
            done=subprocess.CompletedProcess([],0,"","")
            ns,endpoint,failures=self.run_cli([self.ip(),done,done],[export])
            self.assertEqual([],failures);row=endpoint["probeResults"][0]
            self.assertIs(True,row["success"]);self.assertFalse(row["probeSkipped"])
            self.assertIsNone(row["skipReason"]);self.assertEqual(1,len(row["probes"]))
            self.assertTrue(row["probes"][0]["cleanupComplete"])
            self.assertEqual(["ip","mount","umount"],[call.args[0][0] for call in ns["subprocess"].Popen.call_args_list])

    def test_100_verified_dynamic_exports_have_zero_ip_mount_or_unmount_children(self):
        endpoint=self.endpoint();endpoint["activationStrategy"]="DBUS_DYNAMIC_UPDATE_VERIFIED"
        exports=[self.export(index) for index in range(100)]
        runner=NfsProbeRunner({},tempfile.gettempdir(),time.monotonic()+30,None)
        runner.cache_enabled=False;runner.run_command=Mock(side_effect=AssertionError("dynamic update spawned a probe"))
        self.assertEqual([],runner.run([endpoint],{("0.0.0.0",2049):exports}))
        runner.run_command.assert_not_called()
        self.assertEqual(0,runner.total);self.assertEqual(100,endpoint["skippedDynamicCount"])
        self.assertEqual(0,endpoint["skippedAclCount"]);self.assertEqual(0,endpoint["totalProbeCount"])
        self.assertTrue(all(row["success"] is True and row["probeSkipped"] is True and
                            row["skipReason"]=="DBUS_DYNAMIC_UPDATE_VERIFIED" and not row["probes"]
                            for row in endpoint["probeResults"]))

    def test_boot_dynamic_hint_does_not_bypass_strict_boot_mount_probe(self):
        endpoint=self.endpoint();endpoint["activationStrategy"]="DBUS_DYNAMIC_UPDATE_VERIFIED"
        runner=NfsProbeRunner({},tempfile.gettempdir(),time.monotonic()+30,lambda *a:("10.1.1.9",None))
        runner.cache_enabled=True;runner.run_command=Mock(return_value=self.ip())
        def probe(endpoint,export,spec,result):
            result["probes"].append({"success":True,"cleanupComplete":True});runner.completed+=1
        runner.probe=Mock(side_effect=probe)
        with tempfile.TemporaryDirectory() as folder:
            os.chmod(folder,0o700);runner.cache_root=Path(folder)
            self.assertEqual([],runner.run([endpoint],{("0.0.0.0",2049):[self.export()]}))
        runner.probe.assert_called_once();self.assertEqual(0,endpoint["skippedDynamicCount"])

    def test_clean_export_denial_retains_other_export_and_owned_endpoint(self):
        denied=subprocess.CompletedProcess([],32,"","mount.nfs: access denied")
        done=subprocess.CompletedProcess([],0,"","")
        ns,endpoint,failures=self.run_cli([self.ip(),denied,done,done],[self.export(1),self.export(2)])
        self.assertEqual([endpoint],failures);first,second=endpoint["probeResults"]
        self.assertFalse(first["success"]);self.assertEqual("PROBE_PERMISSION_DENIED",first["failure"]["code"])
        self.assertTrue(second["success"]);self.assertTrue(second["probes"][0]["cleanupComplete"])
        self.assertEqual("PROBE_PERMISSION_DENIED",endpoint["lastFailure"]["code"])
        self.assertTrue(endpoint["listening"]);self.assertTrue(endpoint["processRunning"])

    def test_genuine_endpoint_cleanup_only_stops_failed_owned_keys(self):
        cli=ROOT/"systemvm/debian/usr/local/bin/ablestack-storagectl"
        program=next(value for value in re.findall(r"<<'PY'\n(.*?)\nPY",cli.read_text(),re.S)
                     if "def stop_ganesha_endpoints(" in value)
        tree=ast.parse(program)
        node=next(node for node in tree.body if isinstance(node,ast.FunctionDef) and node.name=="stop_ganesha_endpoints")
        with tempfile.TemporaryDirectory() as folder:
            runtime=Path(folder)
            for key,pid in (("healthy",123),("failed",456),("other",789)):
                (runtime/(key+".pid")).write_text(str(pid))
            process=Mock(PIPE=-1,DEVNULL=-3)
            process.run.return_value=Mock(stdout="\n".join("ablestack-storage-ganesha@"+key+".service loaded active" for key in ("healthy","failed","other")),returncode=0)
            terminate=Mock()
            ns={"os":os,"re":re,"shutil":Mock(),"subprocess":process,"ganesha_remaining":lambda limit:limit,
                "ganesha_run_dir":folder,"terminate_pid":terminate}
            with patch("os.path.isdir",return_value=True):
                exec(compile(ast.Module(body=[node],type_ignores=[]),str(cli),"exec"),ns)
                ns["stop_ganesha_endpoints"](only_keys={"failed"})
            self.assertEqual([456],[call.args[0] for call in terminate.call_args_list])
            self.assertTrue((runtime/"healthy.pid").exists());self.assertTrue((runtime/"other.pid").exists())
            self.assertFalse((runtime/"failed.pid").exists())
            stopped=[call.args[0][-1] for call in process.run.call_args_list if call.args[0][1]=="stop"]
            self.assertEqual(["ablestack-storage-ganesha@failed.service"],stopped)
        function=next(node for node in tree.body if isinstance(node,ast.FunctionDef) and node.name=="start_ganesha_endpoints")
        cleanup=next(node for node in ast.walk(function) if isinstance(node,ast.Call) and isinstance(node.func,ast.Name)
                     and node.func.id=="stop_ganesha_endpoints" and any(keyword.arg=="only_keys" for keyword in node.keywords))
        self.assertEqual(1,len(cleanup.keywords))

    def test_late_rpc_failure_compensates_before_failure_response_and_never_marks_target_verified(self):
        cli=ROOT/"systemvm/debian/usr/local/bin/ablestack-storagectl"
        program=next(value for value in re.findall(r"<<'PY'\n(.*?)\nPY",cli.read_text(),re.S)if"def start_ganesha_endpoints("in value)
        nodes=ast.parse(program).body
        begin=next(index for index,node in enumerate(nodes)if isinstance(node,ast.Assign)and any(isinstance(target,ast.Name)and target.id=="runtime_endpoints"for target in node.targets))
        from types import SimpleNamespace
        import io
        from contextlib import redirect_stdout
        from nfs_readiness import NfsReadinessError
        endpoint={"listenIp":"0.0.0.0","port":2049,"listening":True,"exportInventoryReady":True,
                  "completedProbeCount":0,"totalProbeCount":0,"probeResults":[]}
        legacy=Mock(active=True);legacy.prepare.return_value=legacy;legacy.rollback.return_value=True
        ns={"payload":{"exports":[],"operationScope":{}},"enabled":True,"configs":[],"quota_state":{},"endpoint_exports":{},
            "nfs_operation_deadline":time.monotonic()+30,"nfs_control_command":Mock(),"pending_nfs":Mock(),
            "NfsLegacyDynamicApply":Mock(return_value=legacy),"GaneshaDbusPolicy":Mock(),
            "BootCoordinator":SimpleNamespace(attach_existing=lambda:None),"NfsReadinessError":NfsReadinessError,
            "write_ganesha_configs":Mock(return_value=[("owned","/owned.conf","0.0.0.0",2049,"V3V4_DUAL")]),
            "start_ganesha_endpoints":Mock(return_value=[endpoint]),"cleanup_stale_export_aliases":Mock(),"probe_nfs_export_visibility":Mock(return_value=[]),
            "default_port":2049,"rpcbind_active":Mock(return_value=False),"subprocess":subprocess,"json":json}
        output=io.StringIO()
        with redirect_stdout(output):
            with self.assertRaises(SystemExit)as stopped:exec(compile(ast.Module(body=nodes[begin:],type_ignores=[]),str(cli),"exec"),ns)
        self.assertEqual(1,stopped.exception.code);result=json.loads(output.getvalue())
        self.assertFalse(result["success"]);self.assertTrue(result["sourceRollbackVerified"])
        self.assertEqual("RPC_REGISTRATION_NOT_READY",result["lastFailure"]["code"])
        legacy.verified.assert_not_called();legacy.rollback.assert_called_once();legacy.discard.assert_called_once()


class GaneshaDynamicIsolationTest(unittest.TestCase):
    def setUp(self):
        self.content=multiport_configuration()[2049];self.bus=EndpointBus()
        self.name=self.bus.register(self.content,123);self.adapter=GaneshaDbus(self.bus)
    def target(self,count=1):
        return self.content.replace("Clients = 192.0.2.0/24;","Clients = 198.51.100.11/32;",count)
    def test_100_export_policy_change_updates_only_one_and_readbacks_every_export(self):
        target=self.target();before=copy.deepcopy(self.bus.endpoints[self.name]["exports"])
        with tempfile.TemporaryDirectory() as folder:
            path=Path(folder)/"target.conf";path.write_text(target)
            observed=self.adapter.transition(self.name,123,path,self.content,target)
        updated=[args for _,method,args in self.bus.calls if method=="UpdateExport"]
        self.assertEqual(1,len(updated));self.assertEqual(1,observed["exportsUpdated"])
        self.assertFalse(observed["restartPerformed"]);self.assertEqual(100,observed["exportsVerified"])
        self.assertEqual(200,len([1 for _,method,_ in self.bus.calls if method=="DisplayExport"]))
        after=self.bus.endpoints[self.name]["exports"]
        self.assertEqual(1,sum(before[key]!=after[key] for key in before))
    def test_add_and_remove_client_policy_blocks_keep_the_existing_export_id(self):
        matched=re.search(r"(?m)^[ \t]*CLIENT\s*\{[^{}]*\}[ \t]*\n?",self.content,re.S)
        extra=matched[0].replace("192.0.2.0/24","198.51.100.11/32")
        target=self.content[:matched.end()]+extra+self.content[matched.end():]
        old=self.adapter.configuration(self.content);new=self.adapter.configuration(target)
        self.assertEqual(old["exports"][0]["structureSha256"],new["exports"][0]["structureSha256"])
        with tempfile.TemporaryDirectory() as folder:
            destination=Path(folder)/"target.conf";destination.write_text(target)
            added=self.adapter.transition(self.name,123,destination,self.content,target)
            self.assertEqual(1,added["exportsUpdated"])
            destination.write_text(self.content)
            removed=self.adapter.transition(self.name,123,destination,target,self.content)
            self.assertEqual(1,removed["exportsUpdated"])
        self.assertEqual(old["exports"],list(self.bus.endpoints[self.name]["exports"].values()))

    def test_noop_verifies_full_exact_inventory_and_has_zero_update_calls(self):
        result=self.adapter.transition(self.name,123,"/never-opened",self.content,self.content)
        self.assertEqual(0,result["exportsUpdated"])
        self.assertFalse(any(method=="UpdateExport" for _,method,_ in self.bus.calls))
    def test_export_add_remove_core_and_immutable_export_change_reject_before_update(self):
        old=self.adapter.configuration(self.content)["exports"]
        for target in (old[:-1],[{**old[0],"exportId":65534},*old[1:]]):
            with self.assertRaises(ValueError):self.adapter.update(self.name,123,"/never-opened",old,target)
        with self.assertRaises(ValueError):self.adapter.transition(self.name,123,"/never-opened",self.content,self.content.replace("NFS_Port = 2049;","NFS_Port = 2050;",1))
        with self.assertRaises(ValueError):self.adapter.transition(self.name,123,"/never-opened",self.content,self.content.replace('Pseudo = "/local-001";','Pseudo = "/foreign";',1))
        self.assertFalse(any(method=="UpdateExport" for _,method,_ in self.bus.calls))
    def test_partial_second_update_error_compensates_exact_source_and_preserves_original_error(self):
        target=self.target(2);original=copy.deepcopy(self.bus.endpoints[self.name]["exports"])
        normal=self.adapter.call;calls=[0];injected=RuntimeError("injected exact second TARGET update")
        with tempfile.TemporaryDirectory() as folder:
            source=Path(folder)/"source.conf";source.write_text(self.content)
            dest=Path(folder)/"target.conf";dest.write_text(target)
            def fail_second(manager,method,*args):
                if method=="UpdateExport" and str(args[0])==str(dest):
                    calls[0]+=1
                    if calls[0]==2:raise injected
                return normal(manager,method,*args)
            self.adapter.call=fail_second
            with self.assertRaisesRegex(RuntimeError,"SOURCE_ROLLBACK_VERIFIED") as error:
                self.adapter.transition(self.name,123,dest,self.content,target,source)
            self.assertIs(injected,error.exception.__cause__)
            self.assertEqual(original,self.bus.endpoints[self.name]["exports"])
            self.assertEqual(2,len([1 for _,method,args in self.bus.calls if method=="UpdateExport" and str(args[0])==str(source)]))
            self.assertEqual(123,self.bus.endpoints[self.name]["pid"])
    def test_failed_source_compensation_never_claims_rollback_verified(self):
        normal=self.adapter.call;target=self.target(2)
        with tempfile.TemporaryDirectory() as folder:
            source=Path(folder)/"source.conf";source.write_text(self.content)
            dest=Path(folder)/"target.conf";dest.write_text(target);count=[0]
            def fail(manager,method,*args):
                if method=="UpdateExport":
                    count[0]+=1
                    if count[0]>1:raise RuntimeError("DBus unavailable")
                return normal(manager,method,*args)
            self.adapter.call=fail
            with self.assertRaisesRegex(RuntimeError,"SOURCE_ROLLBACK_UNVERIFIED"):
                self.adapter.transition(self.name,123,dest,self.content,target,source)
    def test_pinned_outer_source_rollback_accepts_already_compensated_exact_source_only(self):
        cli=ROOT/"systemvm/debian/usr/local/bin/ablestack-storagectl"
        program=next(value for value in re.findall(r"<<'PY'\n(.*?)\nPY",cli.read_text(),re.S)
                     if "def rendered_ganesha_dynamic_candidates(" in value)
        node=next(node for node in ast.parse(program).body if isinstance(node,ast.FunctionDef)
                  and node.name=="rendered_ganesha_dynamic_candidates")
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder);target=root/"target";(target/"nfs").mkdir(parents=True)
            target_file=target/"nfs"/"owned.conf";target_file.write_text(self.target(2))
            (target/"nfs"/"manifest.json").write_text(json.dumps({"endpoints":[{"legacyUnitKey":"owned","configurationPath":"nfs/owned.conf"}]}))
            current=root/"current.conf";current.write_text(self.content)
            class Process:
                def read_bytes(self):return b"/usr/bin/ganesha.nfsd\0-f\0"+str(current).encode()+b"\0"
            def paths(value):return Process() if str(value).startswith("/proc/") else Path(value)
            ns={"rendered_context":(root,{"_rollbackReplay":False}),"rendered_from_context":(target,{}),
                "json":json,"Path":paths,"time":time,"os":os,"ganesha_conf_dir":str(root),
                "GaneshaDbus":Mock(return_value=self.adapter),"root_maintenance_nfs_boundary":lambda:False,
                "subprocess":Mock(PIPE=-1,DEVNULL=-3),"stop_owned_rendered_ganesha":Mock(side_effect=AssertionError("restart fallback"))}
            ns["subprocess"].run.return_value=Mock(returncode=0,stdout="123")
            exec(compile(ast.Module(body=[node],type_ignores=[]),str(cli),"exec"),ns)
            configs=[("owned",str(current),"0.0.0.0",2049,"V4_ONLY")]
            with self.assertRaises(ValueError):ns["rendered_ganesha_dynamic_candidates"](configs)
            ns["rendered_context"]=(root,{"_rollbackReplay":True})
            result=ns["rendered_ganesha_dynamic_candidates"](configs)["owned"]
            adapter,pid,before,after,name,source=result
            self.assertEqual(before,after);self.assertIsNone(source)
            observed=adapter.transition(name,pid,current,before,after,source)
            self.assertEqual(0,observed["exportsUpdated"])
            self.assertFalse(any(method=="UpdateExport" for _,method,_ in self.bus.calls))

    def test_bad_acknowledgement_rejects_even_if_bus_mutated_target(self):
        normal=self.adapter.call;target=self.target()
        with tempfile.TemporaryDirectory() as folder:
            dest=Path(folder)/"target.conf";dest.write_text(target)
            def bad_ack(manager,method,*args):
                result=normal(manager,method,*args)
                return "0 exports updated" if method=="UpdateExport" else result
            self.adapter.call=bad_ack
            with self.assertRaisesRegex(ValueError,"acknowledge exactly one"):
                self.adapter.transition(self.name,123,dest,self.content,target)


class NormalNfsDynamicWriterTest(unittest.TestCase):
    def setUp(self):
        import fcntl
        from config_generation import Generation,atomic_json
        from pending_nfs_authorization import PendingNfsAuthorization
        from rendered_generation import RenderedGeneration
        from template_maintenance import Maintenance
        from native_renderers import render_nfs_candidate
        from nfs_dynamic_apply import NfsLegacyDynamicApply
        self.atomic=atomic_json;self.kind=NfsLegacyDynamicApply
        self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup)
        self.root=Path(self.temp.name);os.chmod(self.root,0o700)
        self.saved_environment=dict(os.environ);self.addCleanup(self.restore_environment)
        try:self.old9=os.dup(9)
        except OSError:self.old9=None
        self.addCleanup(self.restore9)
        lock=self.root/"writer.lock";descriptor=os.open(lock,os.O_RDWR|os.O_CREAT,0o600)
        if descriptor!=9:os.dup2(descriptor,9);os.close(descriptor)
        fcntl.flock(9,fcntl.LOCK_EX|fcntl.LOCK_NB)
        os.environ["ABLESTACK_STORAGE_WRITER_LOCK_FD"]="9";os.environ["ABLESTACK_STORAGE_WRITER_LOCK_FILE"]=str(lock)
        self.instance=str(uuid.uuid4());self.source_scope={"instanceUuid":self.instance,"operationUuid":str(uuid.uuid4()),"revision":1}
        self.scope={"instanceUuid":self.instance,"operationUuid":str(uuid.uuid4()),"revision":2}
        self.source={"instanceUuid":self.instance,"enabled":True,"protocolMode":"V4_ONLY","idMappingMode":"NUMERIC",
                     "listenIp":"0.0.0.0","port":2049,"exports":[]}
        for index in (1,2):
            name="normal-"+str(index)
            self.source["exports"].append({"uuid":str(uuid.uuid5(uuid.NAMESPACE_OID,name)),"name":name,"path":"/export/"+name,
                "exportPath":"/"+name,"state":"Ready","config":{"volumeMountPath":"/srv/ablestack-storage/volumes/model","backingPath":"/srv/ablestack-storage/volumes/model/"+name,
                "relativeSharePath":name,"anonUid":65534,"anonGid":65534},
                "acls":[{"principal":"192.0.2.0/24","permission":"READ_WRITE","state":"Ready"}]})
        self.g=Generation(self.root/"generation",self.root/"configuration")
        self.atomic(self.g.config/self.kind.NFS,self.source)
        self.g.execute("begin",self.source_scope);self.g.execute("verify",self.source_scope)
        self.g.execute("commit",self.source_scope);self.g.execute("finish",self.source_scope)
        self.g.execute("begin",self.scope)
        self.configuration=self.root/"ganesha";self.configuration.mkdir(mode=0o700)
        files=render_nfs_candidate(self.source,ROOT/"systemvm/debian/usr/local/bin/ablestack-storagectl")
        self.endpoints=json.loads(files["nfs/manifest.json"])["endpoints"]
        self.bus=EndpointBus()
        for endpoint in self.endpoints:
            content=files[endpoint["configurationPath"]]
            (self.configuration/(endpoint["legacyUnitKey"]+".conf")).write_text(content)
            self.bus.register(content,123)
        self.auth=PendingNfsAuthorization(self.g,RenderedGeneration(self.root/"rendered"),
                                          Maintenance(self.root/"maintenance"),self.root/"authorization",self.configuration)
        self.payload=copy.deepcopy(self.source);self.payload["operationScope"]=self.scope
        for row in self.payload["exports"]:row["acls"][0]["principal"]="198.51.100.11/32"
        self.writer=self.new_writer()
    def restore_environment(self):
        os.environ.clear();os.environ.update(self.saved_environment)
    def restore9(self):
        os.close(9)
        if self.old9 is not None:os.dup2(self.old9,9);os.close(self.old9)
    def new_writer(self):
        writer=self.kind(self.auth,time.monotonic()+30,Mock(return_value=subprocess.CompletedProcess([],0,"123","")),
                         ROOT/"systemvm/debian/usr/local/bin/ablestack-storagectl",self.configuration,self.root/"snapshots",
                         lambda **kw:GaneshaDbus(self.bus,**kw))
        def snapshot(path,pid,rendered=False):
            data=Path(path).read_bytes()
            return {"bootId":writer.records.boot_id,"pid":pid,"startTicks":"456",
                    "configurationSha256":hashlib.sha256(data).hexdigest(),"configurationTarget":str(path)},data.decode()
        writer.readiness.snapshot=Mock(side_effect=snapshot)
        writer.readiness.listener_owned=Mock(return_value=True)
        return writer
    def publish_target(self,writer=None,payload=None):
        writer=writer or self.writer;payload=payload or self.payload
        writer.prepare(payload)
        configs=writer.publish()
        return writer.candidates(configs)
    def apply_candidates(self,candidates):
        for adapter,pid,before,after,name,source in candidates.values():
            key=next(key for key,row in self.writer.record["endpoints"].items()if row["busName"]==name)
            adapter.transition(name,pid,self.configuration/(key+".conf"),before,after,source)
    def test_actual_inline_normal_alias_loop_cannot_shadow_dynamic_uuid_module(self):
        cli=ROOT/"systemvm/debian/usr/local/bin/ablestack-storagectl"
        source=cli.read_text()
        inline=source.split("# BEGIN EMBEDDED NFS LEGACY DYNAMIC\n",1)[1].split("# END EMBEDDED NFS LEGACY DYNAMIC",1)[0]
        program=next(value for value in re.findall(r"<<'PY'\n(.*?)\nPY",source,re.S)if"def start_ganesha_endpoints("in value)
        cleanup=next(node for node in ast.parse(program).body if isinstance(node,ast.FunctionDef)
                     and node.name=="cleanup_stale_export_aliases")
        ns={"GaneshaDbus":GaneshaDbus,"NfsEndpointReadiness":NfsEndpointReadiness,"NfsProbeRunner":NfsProbeRunner,
            "pending_nfs_redact":canonical_nfs_redact}
        exec(compile(inline,str(cli),"exec"),ns)
        alias=str(uuid.uuid4())
        ns.update(alias_state={alias:{}},next_alias_state={alias:{}},save_alias_state=lambda value:None)
        original_uuid=ns.get("uuid")
        exec(compile(ast.Module(body=[cleanup],type_ignores=[]),str(cli),"exec"),ns)
        ns["cleanup_stale_export_aliases"]()
        self.assertIs(original_uuid,ns.get("uuid"))
        original_kind=self.kind
        try:
            self.kind=ns["NfsLegacyDynamicApply"]
            writer=self.new_writer()
            writer.prepare(self.payload)
            self.assertTrue(writer.active)
            self.assertEqual(self.source_scope, {key:writer.record["sourceGeneration"][key]for key in self.source_scope})
            self.assertTrue(writer.candidates(writer.publish()))
        finally:self.kind=original_kind

    def test_real_alias_request_loop_keeps_source_payload_exact_and_existing_directory_unmodified(self):
        source_payload=copy.deepcopy(self.source);source_payload["operationScope"]=self.scope
        original=copy.deepcopy(source_payload)
        ns=nfs_alias_request_loop(source_payload)
        self.assertEqual(original,source_payload)
        self.assertTrue(all("_createdBackingDirectory"not in row for row in source_payload["exports"]))
        self.assertEqual([None,None],[call.args[2]for call in ns["apply_posix_permissions"].call_args_list])
        ns["run"].assert_not_called()
        self.writer.prepare(source_payload)
        self.assertTrue(self.writer.source_intent)
        self.assertEqual(self.writer.before,self.writer.record["targetDesiredSha256"])

    def test_real_alias_request_loop_keeps_new_directory_receipt_local_for_posix_initialization(self):
        receipt={"device":101,"inode":202}
        source_payload=copy.deepcopy(self.source);source_payload["operationScope"]=self.scope
        original=copy.deepcopy(source_payload)
        ns=nfs_alias_request_loop(source_payload,receipt)
        self.assertEqual(original,source_payload)
        self.assertEqual([receipt,receipt],[call.args[2]for call in ns["apply_posix_permissions"].call_args_list])
        self.assertEqual(2,ns["update_fstab"].call_count)
        self.assertEqual(2,ns["daemon_reload"].call_count)
        ns["run"].assert_not_called()

    def test_observation_metadata_uses_exact_canonical_producer_for_source_intent_and_target_digest(self):
        source_payload=copy.deepcopy(self.source);source_payload["operationScope"]=self.scope
        source_payload["exports"][0]["config"]["lastInspection"]={"observedAt":"2026-10-17T17:21:31.748165Z"}
        # The fixed producer retains the empty inspection object after removing its timestamp.
        canonical=canonical_nfs_redact({key:value for key,value in source_payload.items()if key!="operationScope"})
        self.atomic(self.g.config/self.kind.NFS,canonical)
        source_artifact=self.g.root/(self.source_scope["operationUuid"]+".json")
        source_record=json.loads(source_artifact.read_text())
        desired={**source_record["desired"],self.kind.NFS:canonical}
        checksum=self.writer.checksum(desired)
        source_record.update(desired=desired,configurationSha256=checksum)
        self.atomic(source_artifact,source_record)
        pointer={**json.loads(self.g.current.read_text()),"configurationSha256":checksum}
        self.atomic(self.g.current,pointer)
        pending=json.loads(self.g.pending.read_text());pending.update(previous=pointer,beforeSha256=checksum)
        self.atomic(self.g.pending,pending)
        nfs_alias_request_loop(source_payload)
        self.writer.prepare(source_payload)
        self.assertTrue(self.writer.source_intent)
        self.assertEqual(checksum,self.writer.record["targetDesiredSha256"])
        self.assertIn("observedAt",source_payload["exports"][0]["config"]["lastInspection"])
        self.assertNotIn("_createdBackingDirectory",source_payload["exports"][0])

    def test_canonical_source_comparison_still_rejects_semantic_or_unknown_underscore_fields(self):
        candidates=self.publish_target();self.apply_candidates(candidates);self.writer.verified()
        for change in (lambda row:row["config"].update(readOnly=True),lambda row:row.update(_foreignField=True)):
            source_payload=copy.deepcopy(self.source);source_payload["operationScope"]=self.scope
            change(source_payload["exports"][0])
            nfs_alias_request_loop(source_payload)
            with self.assertRaisesRegex(ValueError,"exact SOURCE writer"):
                self.new_writer().prepare(source_payload)

    def test_source_replay_after_actual_alias_loop_recovers_owned_target_checkpoint(self):
        original=copy.deepcopy(self.bus.endpoints)
        candidates=self.publish_target();self.apply_candidates(candidates);self.writer.verified()
        self.atomic(self.g.config/self.kind.NFS,{key:value for key,value in self.payload.items()if key!="operationScope"})
        recovery_payload=copy.deepcopy(self.source);recovery_payload["operationScope"]=self.scope
        nfs_alias_request_loop(recovery_payload)
        recovery=self.new_writer();recovery.prepare(recovery_payload)
        self.assertTrue(recovery.source_intent)
        self.assertEqual(original,self.bus.endpoints)
        self.assertEqual(self.g.status()["generation"]["operationUuid"],self.source_scope["operationUuid"])
        self.assertEqual(recovery.record["beforeSha256"],recovery.record["targetDesiredSha256"])

    def test_normal_pending_api_writer_reaches_dynamic_without_rendered_optin(self):
        candidates=self.publish_target()
        self.assertTrue(self.writer.active);self.assertEqual(1,len(candidates))
        self.apply_candidates(candidates);self.writer.verified()
        self.assertEqual("TARGET_VERIFIED",self.writer.record["phase"])
        self.assertEqual(2,len([1 for _,method,_ in self.bus.calls if method=="UpdateExport"]))
        self.assertEqual(self.source_scope["operationUuid"],self.g.status()["generation"]["operationUuid"])
        self.assertEqual(self.scope["operationUuid"],self.g.status()["pendingOperationUuid"])
    def test_normal_wire_calls_actual_startup_with_dynamic_candidates_and_zero_restart(self):
        candidates=self.publish_target()
        cli=ROOT/"systemvm/debian/usr/local/bin/ablestack-storagectl"
        program=next(value for value in re.findall(r"<<'PY'\n(.*?)\nPY",cli.read_text(),re.S)if"def start_ganesha_endpoints("in value)
        node=next(node for node in ast.parse(program).body if isinstance(node,ast.FunctionDef)and node.name=="start_ganesha_endpoints")
        import contextlib
        from types import SimpleNamespace
        readiness=self.writer.readiness;readiness.adapter_type=GaneshaDbus;readiness.reuse=Mock(return_value=None);readiness.remember=Mock()
        readiness.wait=Mock(side_effect=lambda endpoint,*args:endpoint.update(exportInventoryReady=True,listenerOwned=True))
        network=Mock();network.create_connection.return_value=contextlib.nullcontext()
        commands=Mock();commands.which.side_effect=lambda name:"/usr/bin/ganesha.nfsd"if name=="ganesha.nfsd"else None
        ns={"Path":lambda path:SimpleNamespace(exists=lambda:False),"legacy_dynamic":self.writer,
            "rendered_ganesha_dynamic_candidates":Mock(side_effect=AssertionError("normal API borrowed rendered optin")),
            "stop_default_ganesha_service":Mock(),"stop_ganesha_endpoints":Mock(),
            "ensure_ganesha_runtime_dirs":Mock(),"ensure_rpcbind_started":lambda:True,"ganesha_run_dir":str(self.root),
            "NfsEndpointReadiness":lambda *args:readiness,"GaneshaDbus":GaneshaDbus,
            "nfs_control_command":Mock(),"nfs_operation_deadline":time.monotonic()+30,"ganesha_remaining":lambda limit=10:limit,
            "os":os,"shutil":commands,"subprocess":Mock(DEVNULL=-3,PIPE=-1),"time":time,"socket":network,
            "managed_ganesha_process":lambda pid:True,"process_exists":lambda pid:True,
            "tail_file":lambda *a:"Historical prior UpdateExport: Could not parse; errors found block EXPORT",
            "NfsReadinessError":__import__("nfs_readiness").NfsReadinessError}
        self.writer.candidates=Mock(return_value=candidates)
        exec(compile(ast.Module(body=[node],type_ignores=[]),str(cli),"exec"),ns)
        key=next(iter(candidates));row=self.writer.record["endpoints"][key]["identity"]
        result=ns["start_ganesha_endpoints"]([(key,str(self.configuration/(key+".conf")),row["listenIp"],row["port"],row["protocolMode"])])
        self.assertEqual("DBUS_DYNAMIC_UPDATE_VERIFIED",result[0]["activationStrategy"])
        self.assertFalse(result[0]["restartPerformed"])
        self.assertFalse(any(call.args[0][1]=="restart"for call in ns["subprocess"].run.call_args_list))
    def test_normal_fixed_serializer_uses_atomic_dynamic_publisher_without_unlink(self):
        self.writer.prepare(self.payload)
        cli=ROOT/"systemvm/debian/usr/local/bin/ablestack-storagectl"
        program=next(value for value in re.findall(r"<<'PY'\n(.*?)\nPY",cli.read_text(),re.S)if"def write_ganesha_configs("in value)
        node=next(node for node in ast.parse(program).body if isinstance(node,ast.FunctionDef)and node.name=="write_ganesha_configs")
        ns={"legacy_dynamic":self.writer}
        exec(compile(ast.Module(body=[node],type_ignores=[]),str(cli),"exec"),ns)
        with patch("nfs_dynamic_apply.os.unlink",side_effect=AssertionError("dynamic publisher unlinked a live config")):
            configs=ns["write_ganesha_configs"]({},2049)
        self.assertEqual(1,len(configs))
        self.assertTrue(self.writer.candidates(configs))
    def test_atomic_target_replace_failure_preserves_complete_source_and_can_compensate(self):
        self.writer.prepare(self.payload)
        before={path:path.read_bytes()for path in self.configuration.iterdir()}
        original=os.replace
        def fail_target(source,destination):
            if Path(destination).parent==self.configuration:raise OSError("atomic target publication failed")
            return original(source,destination)
        with patch("nfs_dynamic_apply.os.replace",side_effect=fail_target):
            with self.assertRaises(OSError):self.writer.publish()
        self.assertEqual(before,{path:path.read_bytes()for path in self.configuration.iterdir()})
        self.assertTrue(self.writer.rollback())
        self.assertFalse(any(method=="UpdateExport"for _,method,_ in self.bus.calls))

    def test_normal_partial_dbus_failure_restores_source_runtime_and_config_without_restart(self):
        original=copy.deepcopy(self.bus.endpoints);candidates=self.publish_target();normal=self.writer.adapter.call;counter=[0]
        def fail_second(manager,method,*args):
            if method=="UpdateExport"and str(args[0]).startswith(str(self.configuration)):
                counter[0]+=1
                if counter[0]==2:raise RuntimeError("exact TARGET second update failed")
            return normal(manager,method,*args)
        self.writer.adapter.call=fail_second
        with self.assertRaisesRegex(RuntimeError,"SOURCE_ROLLBACK_VERIFIED"):self.apply_candidates(candidates)
        self.assertTrue(self.writer.rollback());self.assertEqual(original,self.bus.endpoints)
        for key,row in self.writer.record["endpoints"].items():
            self.assertEqual(row["sourceSha256"],hashlib.sha256((self.configuration/(key+".conf")).read_bytes()).hexdigest())
        self.assertEqual("ROLLED_BACK",self.writer.record["phase"])
    def test_foreign_source_or_missing_actual_fd9_never_falls_back_to_target_restart(self):
        before={path:path.read_bytes()for path in self.configuration.iterdir()}
        os.environ["ABLESTACK_STORAGE_WRITER_LOCK_FD"]="8"
        with self.assertRaises(ValueError):self.writer.prepare(self.payload)
        os.environ["ABLESTACK_STORAGE_WRITER_LOCK_FD"]="9"
        path=next(iter(before));path.write_text(path.read_text().replace("192.0.2.0/24","203.0.113.0/24",1))
        with self.assertRaisesRegex(ValueError,"actual config differs"):self.writer.prepare(self.payload)
        self.assertFalse(any(method=="UpdateExport"for _,method,_ in self.bus.calls))
    def test_same_normal_scope_recovers_target_verified_snapshot_before_source_reapply(self):
        original=copy.deepcopy(self.bus.endpoints);candidates=self.publish_target();self.apply_candidates(candidates);self.writer.verified()
        clean_target={key:value for key,value in self.payload.items()if key!="operationScope"}
        self.atomic(self.g.config/self.kind.NFS,clean_target)
        self.assertNotEqual(self.g.digest(),self.writer.before)
        recovery=self.new_writer();source_payload={**self.source,"operationScope":self.scope}
        recovery.prepare(source_payload)
        self.assertEqual(original,self.bus.endpoints)
        for key,row in recovery.record["endpoints"].items():
            self.assertEqual(row["sourceSha256"],hashlib.sha256((self.configuration/(key+".conf")).read_bytes()).hexdigest())
        target,endpoints=recovery.render(self.source)
        configs=[(row["legacyUnitKey"],str(self.configuration/(row["legacyUnitKey"]+".conf")),row["listenIp"],row["port"],row["mode"])for row in endpoints]
        current=recovery.candidates(configs)
        updates=len([1 for _,method,_ in self.bus.calls if method=="UpdateExport"])
        for adapter,pid,before,after,name,source in current.values():
            key=next(key for key,row in recovery.record["endpoints"].items()if row["busName"]==name)
            adapter.transition(name,pid,self.configuration/(key+".conf"),before,after,source)
        self.assertEqual(updates,len([1 for _,method,_ in self.bus.calls if method=="UpdateExport"]))
    def test_publishing_checkpoint_recovers_source_without_prior_target_dbus_effect(self):
        original=copy.deepcopy(self.bus.endpoints)
        self.writer.prepare(self.payload);self.writer.publish()
        self.assertEqual("PUBLISHING",self.writer.record["phase"])
        recovery=self.new_writer();recovery.prepare({**self.source,"operationScope":self.scope})
        self.assertTrue(recovery.active);self.assertEqual(original,self.bus.endpoints)
        self.assertFalse(any(method=="UpdateExport"for _,method,_ in self.bus.calls))
        for key,row in recovery.record["endpoints"].items():
            self.assertEqual(row["sourceSha256"],hashlib.sha256((self.configuration/(key+".conf")).read_bytes()).hexdigest())
    def test_pre_activation_partial_snapshot_write_failure_removes_only_its_known_inodes(self):
        original=copy.deepcopy(self.bus.endpoints)
        before={path:(path.read_bytes(),path.stat().st_ino,path.stat().st_mtime_ns)for path in self.configuration.iterdir()}
        original_open=os.open
        def fail_snapshot(path,*args,**kwargs):
            if Path(path).name.startswith("target-"):raise OSError("injected pre-activation ENOSPC")
            return original_open(path,*args,**kwargs)
        with patch("nfs_dynamic_apply.os.open",side_effect=fail_snapshot):
            with self.assertRaises(OSError):self.writer.prepare(self.payload)
        self.assertFalse(self.writer.path.exists());self.assertEqual(original,self.bus.endpoints)
        self.assertEqual(before,{path:(path.read_bytes(),path.stat().st_ino,path.stat().st_mtime_ns)for path in self.configuration.iterdir()})
        recovery=self.new_writer();recovery.prepare({**self.source,"operationScope":self.scope})
        configs=recovery.publish();self.assertTrue(recovery.candidates(configs))
        self.assertFalse(any(method=="UpdateExport"for _,method,_ in self.bus.calls))
    def test_pre_activation_unknown_private_file_is_preserved_without_source_or_target_effect(self):
        original=copy.deepcopy(self.bus.endpoints);original_open=os.open
        def fail_snapshot(path,*args,**kwargs):
            if Path(path).name.startswith("target-"):
                (Path(path).parent/"foreign.txt").write_text("preserve")
                raise OSError("unknown private file present")
            return original_open(path,*args,**kwargs)
        with patch("nfs_dynamic_apply.os.open",side_effect=fail_snapshot):
            with self.assertRaises(OSError):self.writer.prepare(self.payload)
        self.assertTrue((self.writer.path/"foreign.txt").exists())
        self.assertEqual(original,self.bus.endpoints)
        self.assertFalse(any(method=="UpdateExport"for _,method,_ in self.bus.calls))

    def test_next_verified_generation_retires_only_its_committed_predecessor_snapshot(self):
        candidates=self.publish_target();self.apply_candidates(candidates);self.writer.verified()
        previous_path=self.writer.path
        self.atomic(self.g.config/self.kind.NFS,{key:value for key,value in self.payload.items()if key!="operationScope"})
        self.g.execute("verify",self.scope);self.g.execute("commit",self.scope);self.g.execute("finish",self.scope)
        self.scope={"instanceUuid":self.instance,"operationUuid":str(uuid.uuid4()),"revision":3}
        self.g.execute("begin",self.scope)
        next_payload=copy.deepcopy(self.payload);next_payload["operationScope"]=self.scope
        for row in next_payload["exports"]:row["acls"][0]["principal"]="203.0.113.11/32"
        next_writer=self.new_writer();next_writer.prepare(next_payload)
        self.assertFalse(previous_path.exists());self.assertTrue(next_writer.active)
        self.assertTrue(next_writer.path.exists())

    def test_dynamic_effects_reject_false_rechecked_writer_authorization(self):
        self.publish_target()
        self.auth.validate_payload=Mock(return_value=False)
        config=self.writer.config_root
        before={path:path.read_bytes()for path in config.iterdir()}
        with self.assertRaisesRegex(ValueError,"authorization is absent"):
            self.writer.candidates([(row["legacyUnitKey"],str(config/(row["legacyUnitKey"]+".conf")),row["listenIp"],row["port"],row["mode"])for row in self.endpoints])
        with self.assertRaisesRegex(ValueError,"authorization is absent"):self.writer.rollback()
        self.assertEqual(before,{path:path.read_bytes()for path in config.iterdir()})
        self.assertFalse(any(method=="UpdateExport"for _,method,_ in self.bus.calls))

    def test_normal_structural_addition_uses_original_strict_path_without_dynamic_mutation(self):
        target=copy.deepcopy(self.payload);new=copy.deepcopy(target["exports"][0])
        new["uuid"]=str(uuid.uuid4());new["name"]="new";new["path"]="/export/new";new["exportPath"]="/new"
        new["config"]["backingPath"]=new["config"]["volumeMountPath"]+"/new";new["config"]["relativeSharePath"]="new";target["exports"].append(new)
        self.writer.prepare(target)
        self.assertFalse(self.writer.active);self.assertFalse(any(method=="UpdateExport"for _,method,_ in self.bus.calls))



class ProbeObservationTest(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup)
        self.root=Path(self.temp.name);os.chmod(self.root,0o700)
        self.content=multiport_configuration()[20490];self.bus=EndpointBus()
        self.name=self.bus.register(self.content,123)
        self.adapter=lambda **kwargs:GaneshaDbus(self.bus,**kwargs)
        self.proof=NfsProbeObservation(time.monotonic()+10,self.adapter,self.root/"proof",self.root/"config")
        self.config=self.root/"config"/"owned.conf";self.config.parent.mkdir(mode=0o700);self.config.write_text(self.content)
        self.expected=GaneshaDbus.configuration(self.content)["exports"]
        row=self.expected[0]
        self.identity={"bootId":self.proof.records.boot_id,"pid":123,"startTicks":"456",
                       "configurationSha256":"a"*64,"configurationTarget":str(self.config)}
        self.endpoint={**self.identity,"listenIp":"0.0.0.0","port":20490,
                       "processRunning":True,"listening":True,"exportInventoryReady":True,"listenerOwned":True,
                       "activationStrategy":"DBUS_DYNAMIC_UPDATE_VERIFIED","probeSuccess":True,
                       "probeResults":[{"exportUuid":str(uuid.uuid4()),"exportName":"owned",
                           "targetPath":row["path"],"pseudoPath":row["pseudo"],"protocolMode":"V4_ONLY",
                           "success":True,"probeSkipped":True,"skipReason":"DBUS_DYNAMIC_UPDATE_VERIFIED",
                           "status":"SKIPPED_DYNAMIC","probes":[],
                           "clients":[client["client"] for client in row["clients"]]}]}
        self.proof.record([self.endpoint])
    def project(self,endpoint=None):
        with patch.object(NfsEndpointReadiness,"snapshot",return_value=(self.identity,self.content)),patch.object(NfsEndpointReadiness,"listener_owned",return_value=True):
            return self.proof.project(endpoint or copy.deepcopy(self.endpoint),self.config)
    def test_exact_fresh_current_readback_projects_typed_skip_and_clients(self):
        row=self.project()
        self.assertIs(True,row["probeObservationVerified"]);self.assertTrue(row["listenerOwned"])
        self.assertEqual(self.endpoint["probeResults"],row["probeResults"])
        self.assertEqual("DBUS_DYNAMIC_UPDATE_VERIFIED",row["probeSkipReason"])
    def test_stale_boot_pid_ticks_config_and_current_path_never_project(self):
        original=copy.deepcopy(self.identity)
        for key,value in (("bootId","other"),("pid",456),("startTicks","other"),("configurationSha256","b"*64),("configurationTarget","/other/current")):
            self.identity={**original,key:value}
            with self.assertRaises(ValueError):self.project()
        self.identity=original
    def test_changed_effective_runtime_clients_never_use_stale_apply_receipt(self):
        row=self.bus.endpoints[self.name]["exports"][self.expected[0]["exportId"]]
        row["clients"][0]["client"]="198.51.100.99/32"
        with self.assertRaises(ValueError):self.project()
    def test_missing_owned_listener_or_changed_identity_never_project(self):
        with patch.object(NfsEndpointReadiness,"snapshot",return_value=(self.identity,self.content)),patch.object(NfsEndpointReadiness,"listener_owned",return_value=False):
            with self.assertRaises(ValueError):self.proof.project(copy.deepcopy(self.endpoint),self.config)
        with patch.object(NfsEndpointReadiness,"snapshot",side_effect=[(self.identity,self.content),({**self.identity,"startTicks":"changed"},self.content)]),patch.object(NfsEndpointReadiness,"listener_owned",return_value=True):
            with self.assertRaises(ValueError):self.proof.project(copy.deepcopy(self.endpoint),self.config)
    def test_observation_symlink_and_size_bounds_are_rejected(self):
        path=self.proof.root/"latest.json";path.unlink();path.symlink_to(self.config)
        with self.assertRaises((ValueError,OSError)):self.project()
        with self.assertRaises(ValueError):self.proof.record([self.endpoint]*257)
    def test_exact_proc_inode_listener_ownership_rejects_foreign_pid_port_and_ip(self):
        proc=self.root/"proc";(proc/"123"/"fd").mkdir(parents=True);(proc/"net").mkdir()
        (proc/"123"/"fd"/"9").symlink_to("socket:[12345]")
        header="sl local_address rem_address st tx_queue rx_queue tr tm retr uid timeout inode"
        row="0: 00000000:0801 00000000:0000 0A 0:0 0:0 0 0 0 12345"
        (proc/"net"/"tcp").write_text(header+"\n"+row+"\n");(proc/"net"/"tcp6").write_text(header+"\n")
        self.assertTrue(NfsEndpointReadiness.listener_owned({"pid":123,"listenIp":"0.0.0.0","port":2049},proc))
        for change in ({"port":2050},{"listenIp":"192.0.2.1"}):
            self.assertFalse(NfsEndpointReadiness.listener_owned({"pid":123,"listenIp":"0.0.0.0","port":2049,**change},proc))
        (proc/"123"/"fd"/"9").unlink();(proc/"123"/"fd"/"9").symlink_to("socket:[99999]")
        self.assertFalse(NfsEndpointReadiness.listener_owned({"pid":123,"listenIp":"0.0.0.0","port":2049},proc))


if __name__=="__main__":unittest.main()
