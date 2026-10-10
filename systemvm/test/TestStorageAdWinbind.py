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

import json,os,sys,tempfile,unittest,uuid,subprocess,fcntl
from pathlib import Path
from unittest.mock import patch
LIB=Path(__file__).resolve().parents[2]/"systemvm/debian/usr/local/lib/ablestack-storage"
sys.path.insert(0,str(LIB))
from ad_winbind import AdWinbind,AD_WINBIND_UNIT,AD_WINBIND_UNIT_CONTENT

class StorageAdWinbindTest(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup);self.base=Path(self.temp.name)
        self.scope={"instanceUuid":str(uuid.uuid4()),"operationUuid":str(uuid.uuid4()),"revision":2}
        self.marker={**self.scope,"templateUpgradeUuid":str(uuid.uuid4())};self.held=True;self.calls=[]
        def run(args,**kwargs):
            self.calls.append(args)
            output=json.dumps({"bootHeld":self.held,"scope":self.marker,"maintenanceKind":"ROOT"}) if args[1:]==["operation","maintenance","status"] else ""
            return subprocess.CompletedProcess(args,0,output,"")
        self.daemon=AdWinbind("fixture",self.base/"config",self.base/"auth",self.base/"units",run)
    def test_held_daemon_gate_requires_actual_same_process_inode_start_boot_and_still_locked_fd9(self):
        path=self.base/"writer";descriptor=os.open(path,os.O_RDWR|os.O_CREAT|os.O_EXCL,0o600);saved=None
        try:
            try:saved=os.dup(9)
            except OSError:pass
            os.dup2(descriptor,9);fcntl.flock(9,fcntl.LOCK_EX|fcntl.LOCK_NB)
            with patch.dict(os.environ,{"ABLESTACK_STORAGE_WRITER_LOCK_FD":"9","ABLESTACK_STORAGE_WRITER_LOCK_FILE":str(path)}):
                self.daemon.authorize(self.scope)
                self.assertTrue(self.daemon.gate()["daemonStartAuthorized"])
                fcntl.flock(9,fcntl.LOCK_UN)
                with self.assertRaisesRegex(ValueError,"exclusive lock"):self.daemon.gate()
        finally:
            os.close(descriptor)
            if saved is None:os.close(9)
            else:os.dup2(saved,9);os.close(saved)
    def test_open_named_fd_without_own_flock_cannot_publish_daemon_start_authorization(self):
        path=self.base/"writer";descriptor=os.open(path,os.O_RDWR|os.O_CREAT|os.O_EXCL,0o600);saved=None
        try:
            try:saved=os.dup(9)
            except OSError:pass
            os.dup2(descriptor,9)
            with patch.dict(os.environ,{"ABLESTACK_STORAGE_WRITER_LOCK_FD":"9","ABLESTACK_STORAGE_WRITER_LOCK_FILE":str(path)}):
                with self.assertRaisesRegex(ValueError,"exclusive lock"):self.daemon.authorize(self.scope)
                self.assertFalse(self.daemon.authorization.exists())
        finally:
            if descriptor!=9:os.close(descriptor)
            if saved is None:os.close(9)
            else:os.dup2(saved,9);os.close(saved)
    def test_foreign_maintenance_scope_fails_before_any_unit_effect(self):
        self.marker["operationUuid"]=str(uuid.uuid4())
        with self.assertRaises(ValueError):self.daemon.start(self.scope)
        self.assertFalse(self.daemon.unit_directory.exists())
        self.assertTrue(all(args[0]=="fixture" for args in self.calls))
    def test_unit_install_is_exact_and_refuses_foreign_definition_instead_of_overwriting(self):
        self.daemon.install();unit=self.daemon.unit_directory/AD_WINBIND_UNIT
        self.assertEqual(AD_WINBIND_UNIT_CONTENT,unit.read_text());self.assertEqual(0o644,unit.stat().st_mode&0o777)
        unit.write_text("[Service]\nExecStart=/tmp/foreign\n")
        with self.assertRaises(ValueError):self.daemon.install()
        self.assertIn("/tmp/foreign",unit.read_text())

if __name__=="__main__":unittest.main()
