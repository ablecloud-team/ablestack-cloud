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

import json
"""Pure AST/in-memory regression: never import a Native fixture or execute a worker."""
import ast
import copy
import hashlib
import io
import json
from pathlib import Path
import re
import stat
import subprocess
import types
import unittest
from unittest.mock import Mock
import xml.etree.ElementTree as ET

ROOT=Path(__file__).resolve().parents[2]
SOURCE=ROOT/"systemvm/debian/usr/local/lib/ablestack-storage/native_render_validation.py"
CLI=ROOT/"systemvm/debian/usr/local/bin/ablestack-storagectl"


class StorageNativeRenderValidationTest(unittest.TestCase):
    def setUp(self):
        self.source=SOURCE.read_text();self.tree=ast.parse(self.source)
        allowed={"NFS_VALIDATION_ERROR_STAGES","NativeRenderValidationError","native_validation_diagnostic","validate_nfs_candidate","GANESHA_NAMESPACE_WORKER"}
        nodes=[n for n in self.tree.body if isinstance(n,(ast.ClassDef,ast.FunctionDef))and n.name in allowed
            or isinstance(n,ast.Assign)and any(isinstance(t,ast.Name)and t.id in allowed for t in n.targets)]
        self.ns={"json":json,"Path":None,"subprocess":None}
        exec(compile(ast.Module(body=nodes,type_ignores=[]),"pure_validation_definitions","exec"),self.ns)
        self.worker=ast.parse(self.ns["GANESHA_NAMESPACE_WORKER"])

    def runtime_fixture(self):
        files={};dirs={"/etc/ablestack-storage","/opt","/opt/ablestack-ganesha","/opt/ablestack-ganesha/5.5.3"}
        modes={};opened={};reads=[];prefix="/opt/ablestack-ganesha/5.5.3"
        names=("bin/ganesha.nfsd","lib/x86_64-linux-gnu/libganesha_nfsd.so.5.5.3",
            "lib/x86_64-linux-gnu/libntirpc.so.5.0","lib/x86_64-linux-gnu/ganesha/libfsalvfs.so")
        manifest={"schemaVersion":1,"version":"5.5.3","prefix":prefix,"files":{}}
        for name in names:
            path=prefix+"/"+name;files[path]=("ELF fixture "+name).encode();dirs.add(str(Path(path).parent))
            manifest["files"][name]=hashlib.sha256(files[path]).hexdigest()
        manifest_path="/etc/ablestack-storage/ganesha-build-manifest.json"
        files[manifest_path]=json.dumps(manifest).encode()
        class MemoryPath:
            def __init__(self,value):self.value=str(value)
            def __str__(self):return self.value
            def __truediv__(self,name):return MemoryPath(self.value+"/"+str(name))
            @property
            def parent(self):return MemoryPath(str(Path(self.value).parent))
            def exists(self):return self.value in files or self.value in dirs
            def is_symlink(self):return False
            def lstat(self):
                if not self.exists():raise FileNotFoundError
                mode=(stat.S_IFDIR|0o755)if self.value in dirs else(stat.S_IFREG|0o644)
                return types.SimpleNamespace(st_dev=1,st_ino=hash(self.value),st_uid=0,st_gid=0,
                    st_mode=modes.get(self.value,mode),st_size=len(files.get(self.value,b"")),st_mtime_ns=1,st_ctime_ns=1)
        def open_file(path,flags):
            key=len(opened)+1;opened[key]=path;reads.append(str(path));return key
        fake_os=types.SimpleNamespace(O_RDONLY=0,O_NOFOLLOW=1,open=open_file,
            fstat=lambda fd:opened[fd].lstat(),fdopen=lambda fd,*args,**kwargs:io.BytesIO(files[str(opened[fd])]),close=lambda fd:None)
        keep={"ERROR_STAGES","RUNTIME_PREFIX","RUNTIME_FILES","WorkerFailure","require","protected_file","selected_runtime"}
        nodes=[n for n in self.worker.body if isinstance(n,(ast.ClassDef,ast.FunctionDef))and n.name in keep
            or isinstance(n,ast.Assign)and any(isinstance(t,ast.Name)and t.id in keep for t in n.targets)]
        ns={"Path":MemoryPath,"json":json,"hashlib":hashlib,"re":re,"stat":stat,"os":fake_os}
        exec(compile(ast.Module(body=nodes,type_ignores=[]),"memory_runtime_selection","exec"),ns)
        return ns,files,dirs,modes,reads,manifest_path

    def test_selected_private_runtime_attests_binary_fsal_and_both_private_libraries(self):
        ns,files,dirs,modes,reads,manifest_path=self.runtime_fixture()
        binary,prefix=ns["selected_runtime"]()
        self.assertEqual("/opt/ablestack-ganesha/5.5.3/bin/ganesha.nfsd",binary)
        self.assertEqual("/opt/ablestack-ganesha/5.5.3",prefix)
        self.assertEqual({manifest_path,*[prefix+"/"+x for x in ns["RUNTIME_FILES"]]},set(reads))

    def test_missing_private_dependency_never_falls_back_to_another_binary(self):
        ns,files,dirs,modes,reads,manifest_path=self.runtime_fixture()
        del files["/opt/ablestack-ganesha/5.5.3/lib/x86_64-linux-gnu/libntirpc.so.5.0"]
        with self.assertRaises(FileNotFoundError):ns["selected_runtime"]()
        self.assertFalse(any(x.startswith("/usr/")for x in reads))

    def test_changed_runtime_manifest_file_hash_or_writable_prefix_are_rejected(self):
        for kind in("path","hash","owner_write"):
            with self.subTest(kind=kind):
                ns,files,dirs,modes,reads,manifest_path=self.runtime_fixture()
                if kind=="path":
                    value=json.loads(files[manifest_path]);value["prefix"]="/opt/foreign"
                    files[manifest_path]=json.dumps(value).encode()
                elif kind=="hash":files["/opt/ablestack-ganesha/5.5.3/bin/ganesha.nfsd"]=b"changed"
                else:modes["/opt/ablestack-ganesha/5.5.3"]=stat.S_IFDIR|0o777
                with self.assertRaises(ns["WorkerFailure"])as raised:ns["selected_runtime"]()
                self.assertEqual("NFS_VALIDATOR_RUNTIME_UNTRUSTED",raised.exception.code)

    def test_runtime_selection_absence_is_a_closed_runtime_failure(self):
        ns,files,dirs,modes,reads,manifest_path=self.runtime_fixture();files.clear()
        with self.assertRaises(ns["WorkerFailure"])as raised:ns["selected_runtime"]()
        self.assertEqual("NFS_VALIDATOR_RUNTIME_UNAVAILABLE",raised.exception.code)

    def test_private_prefix_is_bound_readonly_before_chroot_and_selected_binary_is_reused(self):
        run=next(n for n in self.worker.body if isinstance(n,ast.FunctionDef)and n.name=="run")
        text=ast.get_source_segment(self.ns["GANESHA_NAMESPACE_WORKER"],run)
        self.assertLess(text.index("binary,prefix=selected_runtime()"),text.index("os.chroot("))
        self.assertLess(text.index("command(['mount','--bind',prefix,str(target)])"),text.index("os.chroot("))
        self.assertIn("command(['mount','-o','remount,bind,ro',str(target)])",text)
        self.assertIn("server=start([binary,'-F','-f',str(path)",text)
        self.assertNotIn("binary='/opt",text);self.assertNotIn("'/opt',",text)

    def test_private_dbus_rpc_and_v4_state_exist_before_unchanged_candidate_startup(self):
        run=next(n for n in self.worker.body if isinstance(n,ast.FunctionDef)and n.name=="run")
        values={n.value for n in ast.walk(run)if isinstance(n,ast.Constant)and isinstance(n.value,str)}
        for path in("run/dbus","run/rpcbind","var/lib/nfs/ganesha","var/lib/nfs/ganesha/v4recov",
            "var/lib/nfs/ganesha/v4old","var/lib/rpcbind","/usr/bin/dbus-daemon","/usr/sbin/rpcbind"):
            self.assertIn(path,values)
        xml=next(n.args[0].value for n in ast.walk(run)if isinstance(n,ast.Call)and isinstance(n.func,ast.Attribute)
            and n.func.attr=="write_text"and n.args and isinstance(n.args[0],ast.Constant))
        bus=ET.fromstring(xml);self.assertEqual("system",bus.findtext("type"))
        self.assertEqual("unix:path=/run/dbus/system_bus_socket",bus.findtext("listen"))
        self.assertEqual("EXTERNAL",bus.findtext("auth"));self.assertEqual({"user":"root"},bus.find("policy").attrib)
        text=ast.get_source_segment(self.ns["GANESHA_NAMESPACE_WORKER"],run)
        self.assertLess(text.index("bus=start("),text.index("server=start("))
        self.assertLess(text.index("rpc=start("),text.index("server=start("))
        self.assertIn("work=end-min(8,budget/4)",text)
        self.assertIn("deadline=min(work,time.monotonic()+8)",text)
        self.assertIn("for child in reversed(children)",text)
        self.assertNotIn("'diagnostic':tail",text)
        self.assertNotIn("Enable_DBUS",text)

    def parent(self,stdout,returncode=1,timeout=False):
        process=Mock()
        if timeout:process.side_effect=subprocess.TimeoutExpired("unshare",1,stderr="do not disclose")
        else:process.return_value=types.SimpleNamespace(stdout=stdout,returncode=returncode,stderr="private path and log")
        class Candidate:
            def __init__(self,value):pass
            def __truediv__(self,value):return self
            def __str__(self):return "/owned/candidate"
            def read_text(self):return '{"endpoints":[{}]}'
        self.ns.update(Path=Candidate,subprocess=types.SimpleNamespace(run=process,TimeoutExpired=subprocess.TimeoutExpired))
        return process

    def test_only_exact_success_attests_validation_and_worker_timeout_is_bounded(self):
        proc=self.parent('{"success":true,"nfsParserVerified":true}',0)
        self.assertTrue(self.ns["validate_nfs_candidate"]("/ignored",9))
        args=proc.call_args[0][0];self.assertEqual("7.0",args[-1]);self.assertEqual(9,proc.call_args[1]["timeout"])
        self.assertIn("--kill-child=SIGKILL",args)
        self.parent("",timeout=True)
        with self.assertRaises(self.ns["NativeRenderValidationError"])as caught:self.ns["validate_nfs_candidate"]("/ignored",9)
        self.assertEqual("NFS_VALIDATOR_WORKER_TIMEOUT",caught.exception.diagnostic["runtimeValidationCode"])
        self.assertEqual("NFS_VALIDATION_FAILED",str(caught.exception))

    def test_worker_failure_preserves_only_fixed_code_stage_protocol(self):
        diagnostic={"protocol":"NFS","stage":"RUNTIME_SELECTION","runtimeValidationCode":"NFS_VALIDATOR_RUNTIME_UNAVAILABLE"}
        self.parent(json.dumps({"success":False,"nfsParserVerified":False,"diagnostic":diagnostic}))
        with self.assertRaises(self.ns["NativeRenderValidationError"])as caught:self.ns["validate_nfs_candidate"]("/ignored")
        self.assertEqual(diagnostic,self.ns["native_validation_diagnostic"](caught.exception))
        self.assertNotIn("private",str(caught.exception))

    def test_unknown_extra_wrong_type_or_mismatched_pair_is_not_a_public_diagnostic(self):
        valid={"protocol":"NFS","stage":"RUNTIME_SELECTION","runtimeValidationCode":"NFS_VALIDATOR_RUNTIME_UNAVAILABLE"}
        for delta in({"path":"/private"},{"protocol":"SMB"},{"stage":"PARSER_STARTUP"},{"stage":True},{"runtimeValidationCode":[]},{"runtimeValidationCode":"UNKNOWN"}):
            with self.subTest(delta=delta):
                error=types.SimpleNamespace(diagnostic={**valid,**delta})
                self.assertIsNone(self.ns["native_validation_diagnostic"](error))
        for raw in("not json","{}",'{"success":true,"nfsParserVerified":false}',"x"*(64*1024+1)):
            self.parent(raw)
            with self.assertRaises(self.ns["NativeRenderValidationError"])as caught:self.ns["validate_nfs_candidate"]("/ignored")
            self.assertEqual("NFS_VALIDATOR_WORKER_FAILED",caught.exception.diagnostic["runtimeValidationCode"])

    def test_worker_and_parent_contract_pairs_are_identical(self):
        actual=next(n.value for n in self.worker.body if isinstance(n,ast.Assign)and any(isinstance(t,ast.Name)and t.id=="ERROR_STAGES"for t in n.targets))
        self.assertEqual(self.ns["NFS_VALIDATION_ERROR_STAGES"],ast.literal_eval(actual))

    def test_embedded_canonical_validation_body_and_catch_are_exact_without_executing_cli(self):
        self.assertEqual(1,CLI.read_text().count(self.source))
        lines=CLI.read_text().splitlines()
        opening='  python3 - "$action" "$payload" "$0" <<'+repr("PYRENDEREDGENERATION")
        first=lines.index(opening)+1;last=lines.index("PYRENDEREDGENERATION",first)
        tree=ast.parse("\n".join(lines[first:last])+"\n")
        handler=tree.body[-1].handlers[0];outputs=[]
        error=self.ns["NativeRenderValidationError"]("NFS_VALIDATOR_RUNTIME_UNAVAILABLE")
        scope={"error":error,"json":json,"native_validation_diagnostic":self.ns["native_validation_diagnostic"],"print":outputs.append,"SystemExit":SystemExit}
        with self.assertRaises(SystemExit):
            exec(compile(ast.Module(body=handler.body,type_ignores=[]),"pure_rpc_error_catch","exec"),scope)
        result=json.loads(outputs[0])
        self.assertEqual("RENDERED_GENERATION_REJECTED",result["errorCode"])
        self.assertEqual(error.diagnostic,result["diagnostic"]);self.assertEqual("NFS_VALIDATION_FAILED",result["reason"])
