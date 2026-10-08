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

"""Behavioral normal CLI cleanup model; no DATA deletion or replay bypass."""
from pathlib import Path
import ast
import copy
import fcntl
import json
import os
import sys
import tempfile
import unittest
import uuid
from unittest.mock import patch
LIB=Path(__file__).resolve().parents[2]/"systemvm/debian/usr/local/lib/ablestack-storage"
sys.path.insert(0,str(LIB))
from nvme_cleanup import NvmeManagedCleanup
from nvme_credentials import NvmeCredentialStore


class StorageNvmeCleanupTest(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup);self.root=Path(self.temp.name)
        self.base=self.root/"nvmet";self.base.mkdir(mode=0o700)
        for name in ("subsystems","ports","hosts"):(self.base/name).mkdir(mode=0o700)
        self.state_dir=self.root/"state";self.state_dir.mkdir(mode=0o700);self.state=self.state_dir/"nvmeof-subsystems.json"
        self.instance=str(uuid.uuid4());self.nqn="nqn.2026-10.local.storage:owned";self.foreign="nqn.2026-10.local.storage:unrecorded"
        self.host="nqn.2026-10.example:client";self.removed=[];self.observations=0
        self.idle={"status":"ok","sessionSchemaVersion":2,"observedNvmeofTcpCount":0,"warnings":[],"sessions":[]}
        self.payload={"instanceUuid":self.instance,"enabled":True,"subsystems":[{"uuid":str(uuid.uuid4()),"targetName":self.nqn,
                      "namespaces":[{"uuid":str(uuid.uuid4()),"lunOrNamespace":1}],"hosts":[{"uuid":str(uuid.uuid4()),"principal":self.host}]}]}
        self.empty={"instanceUuid":self.instance,"enabled":False,"subsystems":[]}
        self.state.write_text(json.dumps(self.payload));self.state.chmod(0o600)
        self.make_subsystem(self.nqn,self.host);self.make_port("1",self.nqn)
        self.raw=self.root/"RAW-sentinel";self.raw.write_bytes(b"UNCHANGED DATA SENTINEL");self.before=self.raw.read_bytes()
        lock=self.root/"writer.lock";self.lock=os.open(lock,os.O_RDWR|os.O_CREAT|os.O_EXCL,0o600)
        try:self.previous_fd9=os.dup(9)
        except OSError:self.previous_fd9=None
        os.dup2(self.lock,9);fcntl.flock(9,fcntl.LOCK_EX|fcntl.LOCK_NB)
        self.env=patch.dict(os.environ,{"ABLESTACK_STORAGE_WRITER_LOCK_FD":"9","ABLESTACK_STORAGE_WRITER_LOCK_FILE":str(lock)})
        self.env.start();self.addCleanup(self.release_writer)
        cleanup=self.cleanup(self.payload);cleanup.created_port("1",("127.0.0.1",4420));cleanup.created_host(self.host)

    def release_writer(self):
        self.env.stop();os.close(self.lock)
        if self.previous_fd9 is None:os.close(9)
        else:os.dup2(self.previous_fd9,9);os.close(self.previous_fd9)

    def make_subsystem(self,nqn,host=None):
        target=self.base/"subsystems"/nqn;target.mkdir(mode=0o700)
        (target/"namespaces").mkdir(mode=0o700);(target/"allowed_hosts").mkdir(mode=0o700)
        namespace=target/"namespaces/1";namespace.mkdir(mode=0o700);(namespace/"enable").write_text("1");(namespace/"device_path").write_text("/dev/fixture-data")
        (target/"attr_allow_any_host").write_text("0")
        if host:
            directory=self.base/"hosts"/host
            if not directory.exists():directory.mkdir(mode=0o700);(directory/"dhchap_key").write_text("")
            (target/"allowed_hosts"/host).symlink_to(directory,target_is_directory=True)

    def make_port(self,number,nqn):
        port=self.base/"ports"/number;port.mkdir(mode=0o700);(port/"subsystems").mkdir(mode=0o700)
        for key,value in {"addr_trtype":"tcp","addr_adrfam":"ipv4","addr_traddr":"127.0.0.1","addr_trsvcid":"4420"}.items():(port/key).write_text(value)
        (port/"subsystems"/nqn).symlink_to(self.base/"subsystems"/nqn,target_is_directory=True)

    def rmdir_configfs_model(self,path):
        path=Path(path);self.removed.append(str(path.relative_to(self.base)))
        # Real configfs removes its fixed attributes/default groups on rmdir.
        # This model removes only the known metadata leaves, never DATA.
        for child in list(path.iterdir()):
            if child.is_symlink():raise AssertionError("shared reference reached rmdir")
            if child.is_dir():child.rmdir()
            elif child.name in ("enable","device_path","attr_allow_any_host","addr_trtype","addr_adrfam","addr_traddr","addr_trsvcid","dhchap_key"):child.unlink()
            else:raise AssertionError("unmanaged child reached rmdir")
        path.rmdir()

    def sessions(self):
        self.observations+=1;return copy.deepcopy(self.idle)

    def cleanup(self,payload=None):
        return NvmeManagedCleanup(self.base,self.state,payload or self.empty,self.sessions,rmdir=self.rmdir_configfs_model)

    def test_normal_disabled_apply_removes_only_prior_owned_namespace_target_and_created_host_port(self):
        result=self.cleanup().finish({})
        self.assertEqual(set(),result);self.assertFalse((self.base/"subsystems"/self.nqn).exists())
        self.assertFalse((self.base/"ports/1").exists());self.assertFalse((self.base/"hosts"/self.host).exists())
        self.assertGreater(self.observations,0);self.assertEqual(self.before,self.raw.read_bytes())

    def test_shared_foreign_target_host_and_port_links_remain_exact_and_private_slot_is_preserved(self):
        self.make_subsystem(self.foreign,self.host)
        (self.base/"ports/1/subsystems"/self.foreign).symlink_to(self.base/"subsystems"/self.foreign,target_is_directory=True)
        path=self.base/"subsystems"/self.foreign/"namespaces/1/enable";before=path.read_bytes()
        result=self.cleanup().finish({})
        self.assertEqual({self.host},result);self.assertEqual(before,path.read_bytes())
        self.assertTrue((self.base/"ports/1/subsystems"/self.foreign).is_symlink());self.assertTrue((self.base/"hosts"/self.host).exists())
        self.assertNotIn("ports/1",self.removed);self.assertNotIn("hosts/"+self.host,self.removed);self.assertEqual(self.before,self.raw.read_bytes())

    def test_unregistered_empty_foreign_port_and_host_objects_are_preserved(self):
        self.make_port("2",self.foreign);(self.base/"ports/2/subsystems"/self.foreign).unlink()
        foreign_host=self.base/"hosts"/"nqn.2026-10.example:unowned";foreign_host.mkdir();(foreign_host/"dhchap_key").write_text("")
        self.cleanup().finish({})
        self.assertTrue((self.base/"ports/2").exists());self.assertTrue(foreign_host.exists())

    def test_busy_unknown_or_boolean_session_count_refuses_before_unlink_or_namespace_disable(self):
        for change in ({"observedNvmeofTcpCount":1},{"status":"unknown"},{"warnings":["unavailable"]},{"observedNvmeofTcpCount":False},{"sessions":[{"protocol":"NVME_OF"}]}):
            with self.subTest(change=tuple(change)):
                self.idle.update(change)
                with self.assertRaises(ValueError):self.cleanup().finish({})
                self.assertEqual([],self.removed);self.assertEqual("1",(self.base/"subsystems"/self.nqn/"namespaces/1/enable").read_text())
                self.assertTrue((self.base/"ports/1/subsystems"/self.nqn).is_symlink())
                self.idle={"status":"ok","sessionSchemaVersion":2,"observedNvmeofTcpCount":0,"warnings":[],"sessions":[]}

    def test_existing_foreign_target_unrecorded_namespace_symlink_or_foreign_instance_refuses_before_effects(self):
        self.make_subsystem(self.foreign)
        request={**self.payload,"subsystems":[{**self.payload["subsystems"][0],"targetName":self.foreign}]}
        with self.assertRaises(ValueError):self.cleanup(request)
        extra=self.base/"subsystems"/self.nqn/"namespaces/2";extra.mkdir()
        with self.assertRaises(ValueError):self.cleanup()
        extra.rmdir()
        with self.assertRaises(ValueError):self.cleanup({**self.empty,"instanceUuid":str(uuid.uuid4())})
        with self.assertRaises(ValueError):self.cleanup({**self.payload,"subsystems":[{**self.payload["subsystems"][0],"namespaces":[{"lunOrNamespace":False}]}]})
        self.assertEqual([],self.removed);self.assertEqual("1",(self.base/"subsystems"/self.nqn/"namespaces/1/enable").read_text())

    def test_replaced_canonical_or_owned_receipt_identity_refuses_before_cleanup_effects(self):
        cleanup=self.cleanup();self.state.write_text(json.dumps({**self.payload,"foreignMutation":True}))
        with self.assertRaises(ValueError):cleanup.finish({})
        self.assertEqual([],self.removed)
        self.state.write_text(json.dumps(self.payload))
        book=self.state_dir/"nvme-runtime-ownership.json";value=json.loads(book.read_text());value["ports"]["1"]["inode"]+=1;book.write_text(json.dumps(value))
        with self.assertRaises(ValueError):self.cleanup()
        self.assertEqual([],self.removed)

    def test_actual_writer_descriptor_requires_own_exclusive_flock_before_any_cleanup_effect(self):
        fcntl.flock(9,fcntl.LOCK_UN)
        with self.assertRaisesRegex(ValueError,"exclusive lock"):self.cleanup()
        self.assertEqual([],self.removed);self.assertEqual("1",(self.base/"subsystems"/self.nqn/"namespaces/1/enable").read_text())

    def test_exact_signed_normal_cli_tail_cleans_without_replay_env_and_keeps_shared_vault_slot(self):
        self.make_subsystem(self.foreign,self.host)
        (self.base/"ports/1/subsystems"/self.foreign).symlink_to(self.base/"subsystems"/self.foreign,target_is_directory=True)
        cleanup=self.cleanup()
        secrets=self.state_dir/"secrets";secrets.mkdir(mode=0o700)
        store=NvmeCredentialStore(secrets/"nvmeof-acl-secrets.json")
        previous={"schemaVersion":1,"instanceUuid":self.instance,"hosts":{self.host:{"dhChapKey":"SYNTHETIC_PRIVATE_SLOT"}}}
        store.persist(previous)
        cli=LIB.parent.parent/"bin/ablestack-storagectl";block=cli.read_text().split("apply_nvmeof_subsystems() {",1)[1].split("identity_capsule_command() {",1)[0]
        source=block.split("<<'PY'\n",1)[1].split("\nPY",1)[0];tree=ast.parse(source)
        index=next(i for i,node in enumerate(tree.body) if isinstance(node,ast.Assign) and any(isinstance(target,ast.Name) and target.id=="preserved_shared_hosts" for target in node.targets))
        printed=[];namespace={"cleanup":cleanup,"desired_port_ids":{},"previous_credentials":previous,
                             "credential_store":store,"credential_record":{"schemaVersion":1,"instanceUuid":self.instance,"hosts":{}},
                             "payload":self.empty,"redact_sensitive":lambda value:value,"state_dir":str(self.state_dir),"applied":0,"skipped":0,
                             "os":os,"json":json,"print":lambda value:printed.append(json.loads(value))}
        with patch.dict(os.environ,{},clear=False):
            previous_mode=os.environ.pop("ABLESTACK_STORAGE_RENDERED_REPLAY",None)
            try:exec(compile(ast.Module(body=tree.body[index:],type_ignores=[]),str(cli),"exec"),namespace)
            finally:
                if previous_mode is not None:os.environ["ABLESTACK_STORAGE_RENDERED_REPLAY"]=previous_mode
        self.assertTrue(printed[0]["success"]);self.assertEqual(previous,store.read())
        self.assertFalse((self.base/"subsystems"/self.nqn).exists());self.assertTrue((self.base/"subsystems"/self.foreign).exists())
        self.assertEqual([],json.loads(self.state.read_text())["subsystems"]);self.assertEqual(self.before,self.raw.read_bytes())

    def test_signed_inline_cleanup_body_is_exact_and_has_no_generic_foreign_link_sweep(self):
        cli=LIB.parent.parent/"bin/ablestack-storagectl";source=cli.read_text()
        actual=source.split("# BEGIN EMBEDDED NVME CLEANUP\n",1)[1].split("# END EMBEDDED NVME CLEANUP",1)[0]
        self.assertEqual((LIB/"nvme_cleanup.py").read_text().rstrip(),actual.rstrip())
        block=source.split("apply_nvmeof_subsystems() {",1)[1].split("identity_capsule_command() {",1)[0]
        self.assertNotIn("remove_stale_managed_port_links",block);self.assertNotIn("unlink_all_subsystems",block)
        self.assertNotIn('if os.environ.get("ABLESTACK_STORAGE_RENDERED_REPLAY") and',block)


if __name__=="__main__":unittest.main()
