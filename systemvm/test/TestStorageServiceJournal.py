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
import configparser
import contextlib
import copy
import fcntl
import json
import os
from pathlib import Path
import stat
import subprocess
import sys
import tempfile
import unittest
import uuid
from unittest.mock import patch

ROOT=Path(__file__).resolve().parents[2]
sys.path.insert(0,str(ROOT/"systemvm/debian/usr/local/lib/ablestack-storage"))
import rendered_driver as driver_module
from config_generation import Generation,atomic_json
from rendered_driver import RenderedDriver
from rendered_generation import RenderedGeneration
from service_maintenance import ServiceMaintenance
from template_maintenance import Maintenance


class StorageServiceJournalTest(unittest.TestCase):
    """Real generation/rendered/SERVICE engines; only OS observations are fixtures."""
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup)
        self.root=Path(self.temp.name);self.common=self.root/"shared-state";self.common.mkdir(mode=0o755)
        self.common.chmod(0o755) # Actual SystemVM common state is public; its journal is private.
        self.config=self.root/"config";self.config.mkdir(mode=0o700)
        self.environment=patch.dict(os.environ,{
            "ABLESTACK_STORAGE_TEMPLATE_MAINTENANCE_DIR":str(self.common),
            "ABLESTACK_STORAGE_VOLUME_OPERATIONS":str(self.root/"volume-operations"),
            "ABLESTACK_STORAGE_RESERVATION_DIR":str(self.root/"resource-reservation"),
            "ABLESTACK_STORAGE_CONFIGURATION_ROOT":str(self.config)})
        self.environment.start();self.addCleanup(self.environment.stop)
        self.gen=Generation(self.root/"generation",self.config)
        self.assertEqual("UNVERIFIED",self.gen.status()["generationStatus"])
        self.source_scope={"instanceUuid":str(uuid.uuid4()),"operationUuid":str(uuid.uuid4()),"revision":1}
        atomic_json(self.config/"sharedfs-network.json",{"ipAddress":"192.0.2.80","cidr":"192.0.2.0/24"})
        for action in("begin","verify","commit","finish"):self.gen.execute(action,self.source_scope)
        self.original=self.gen.status()
        self.scope={**self.source_scope,"operationUuid":str(uuid.uuid4()),"revision":2}
        self.service_scope={**self.scope,"maintenanceUuid":self.scope["operationUuid"]}
        self.store=RenderedGeneration(self.root/"rendered")
        self.driver=RenderedDriver(ROOT/"systemvm/debian/usr/local/bin/ablestack-storagectl",self.store)
        self.keeper=Maintenance(self.common,self.gen.status)
        self.service=ServiceMaintenance(self.keeper,self.driver.runtime.cli,run=self.unexpected_os_write,command=self.command,units=lambda:[])
        self.driver.runtime.command=self.command
        self.driver.runtime.iscsi_root=self.root/"kernel/iscsi";self.driver.runtime.iscsi_root.mkdir(parents=True)
        self.driver.runtime.nvme_root=self.root/"kernel/nvmet";self.driver.runtime.nvme_root.mkdir(parents=True)
        self.exposed_nfs=False;self.commands=[];self.boot_guard_installs=[]
        self.driver.install_boot_guards=lambda:self.boot_guard_installs.append("fixed OS boot guards")
        request={**self.source_scope,"previousGeneration":self.original["generation"],
            "configurationDesiredState":self.original["configurationDesiredState"],
            "protocolDesiredState":{domain:None for domain in("NFS","SMB","ISCSI","NVMEOF")},
            "credentialRefs":{},"fileVolumeBindings":[],"expectedBindings":[]}
        # Run the real render/import/stage/validator. Only the external testparm process is an OS fixture.
        with patch.object(driver_module.subprocess,"run",self.parser_process):
            imported=self.driver.execute("render-import",request)
        self.assertTrue(imported["activation"]["baselineImported"]);self.assertEqual("COMPLETE",imported["activation"]["phase"])
        self.activation=copy.deepcopy(imported["activation"]);self.manifest=copy.deepcopy(imported["current"])
        self.gen.execute("begin",self.scope)
        self.assertEqual("HELD",self.service.enter(self.service_scope)["phase"])
        self.assertEqual([],self.service.status(self.service_scope)["stoppedUnits"])
        self.gen.execute("rollback",self.scope)
        self.assertEqual(self.original["generation"],self.gen.status()["generation"])
        self.journal=self.common/"service-maintenance.json"

    def unexpected_os_write(self,*args,**kwargs):
        raise AssertionError("A stopped/empty SOURCE must not run a daemon/configuration command")

    def parser_process(self,args,**kwargs):
        self.assertEqual(["testparm","-s"],args[:2])
        path=Path(args[2]);self.assertTrue(path.is_relative_to(self.root))
        parsed=configparser.ConfigParser(interpolation=None);parsed.read_string(path.read_text())
        self.assertEqual("user",parsed["global"]["security"]);self.assertTrue(parsed["global"]["smb ports"].strip())
        return subprocess.CompletedProcess(args,0,"","")

    def command(self,args,payload=None):
        self.commands.append(tuple(args))
        if args==("operation","generation","status"):return self.gen.status()
        if args==("operation","maintenance","status"):return self.keeper.status()
        if args==("operation","generation","render-status"):return self.store.status()
        if args==("operation","generation","render-maintenance-verify"):
            return self.driver.execute("render-maintenance-verify",payload)
        if args==("network","endpoints","inspect"):
            self.assertEqual([],payload["expectedBindings"])
            return {"success":True,"sideEffects":False,"bindingsValidated":True}
        if args==("operation","verify"):
            self.assertEqual([],list(self.driver.runtime.iscsi_root.iterdir()))
            self.assertEqual([],list(self.driver.runtime.nvme_root.iterdir()))
            return {"success":True,"status":"ok","nfsGanesha":{"active":0,"endpoints":[]},
                "listenPorts":{"nfs":[2049]if self.exposed_nfs else[]},"smbRuntime":{"runtimeEndpoints":[]}}
        raise AssertionError(("Unexpected OS/native request",args))

    @contextlib.contextmanager
    def writer(self):
        path=self.root/"desired-writer.lock";fd=os.open(path,os.O_RDWR|os.O_CREAT,0o600)
        try:previous=os.dup(9)
        except OSError:previous=None
        try:
            if fd!=9:os.dup2(fd,9)
            fcntl.flock(9,fcntl.LOCK_EX|fcntl.LOCK_NB)
            with patch.dict(os.environ,{"ABLESTACK_STORAGE_WRITER_LOCK_FD":"9"}):
                yield
        finally:
            if previous is not None:os.dup2(previous,9);os.close(previous)
            elif fd!=9:os.close(9)
            os.close(fd)

    def resume(self):
        real_path=Path;authorization=self.root/"run/rendered-authorization"
        def os_path(value):return authorization if str(value)=="/run/ablestack-storage/rendered-authorization"else real_path(value)
        with self.writer(),patch.object(driver_module,"Path",os_path):
            return self.driver.execute("render-maintenance-resume-source",self.service_scope)

    def test_parent_0755_empty_imported_source_resumes_verifies_and_releases_same_writer(self):
        before={p.name:p.read_bytes()for p in self.config.iterdir()}
        result=self.resume()
        self.assertTrue(result["sourceUnchangedVerified"]);self.assertEqual([],result["resumedDomains"])
        self.assertFalse(result["generationAdvanced"]);self.assertEqual(self.activation,self.store.status()["activation"])
        verified=self.driver.execute("render-maintenance-verify",{**self.service_scope,"verifiedGeneration":self.original["generation"]})
        self.assertTrue(verified["sourceUnchangedVerified"]);self.assertFalse(verified["activationOccurred"])
        released=self.service.release({**self.service_scope,"verifiedGeneration":self.original["generation"]})
        self.assertTrue(released["released"]);self.assertFalse(released["bootHeld"])
        self.assertEqual("RELEASED",self.keeper.read(self.journal)["phase"])
        self.assertEqual("VERIFIED",self.keeper.read(self.journal)["sourceResumePhase"])
        self.assertEqual(self.original["generation"],self.gen.status()["generation"]);self.assertIsNone(self.gen.status()["pendingOperationUuid"])
        self.assertEqual(self.manifest,self.store.status()["current"]);self.assertEqual(before,{p.name:p.read_bytes()for p in self.config.iterdir()})
        self.assertEqual(0o755,stat.S_IMODE(self.common.stat().st_mode));self.assertEqual(0o600,stat.S_IMODE(self.journal.stat().st_mode))
        self.assertEqual([self.source_scope["operationUuid"]],sorted(p.name for p in self.store.generations.iterdir()))
        self.assertFalse(any(args[-1:]in[("apply",),("reconcile",)]for args in self.commands))

    def test_group_writable_parent_is_rejected_without_checkpoint_changes(self):
        before=self.journal.read_bytes();self.common.chmod(0o775)
        with self.assertRaisesRegex(ValueError,"maintenance read directory"):
            self.driver.service_source(self.service_scope)
        self.assertEqual(before,self.journal.read_bytes())

    def test_foreign_scope_and_unprotected_or_symlink_journal_are_rejected(self):
        before=self.journal.read_bytes();value=self.keeper.read(self.journal)
        self.keeper.write(self.journal,{**value,"scope":{**self.service_scope,"operationUuid":str(uuid.uuid4())}})
        with self.assertRaises(ValueError):self.driver.service_source(self.service_scope)
        self.journal.write_bytes(before);self.journal.chmod(0o644)
        with self.assertRaisesRegex(ValueError,"record is not protected"):self.driver.service_source(self.service_scope)
        self.journal.chmod(0o600);target=self.common/"foreign.json";target.write_bytes(before);target.chmod(0o600)
        self.journal.unlink();self.journal.symlink_to(target)
        with self.assertRaisesRegex(ValueError,"record is not protected"):self.driver.service_source(self.service_scope)
        self.assertEqual(before,target.read_bytes())

    def test_failed_all_four_readback_keeps_hold_and_writes_recovery_under_parent_0755(self):
        self.exposed_nfs=True
        with self.assertRaisesRegex(ValueError,"all-four readback"):self.resume()
        record=self.keeper.read(self.journal)
        self.assertEqual("RECOVERY_REQUIRED",record["sourceResumePhase"])
        self.assertEqual([],record["sourceResumeDomains"]);self.assertTrue(self.keeper.status()["bootHeld"])
        self.assertFalse(self.keeper.receipt.exists());self.assertEqual(self.activation,self.store.status()["activation"])

    def test_rendered_private_directory_keeps_0700_requirement(self):
        self.store.root.chmod(0o755)
        with self.assertRaisesRegex(ValueError,"Rendered generation directory is not protected"):self.store.status()
