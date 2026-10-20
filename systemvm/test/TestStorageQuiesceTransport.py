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

"""Run the packaged nested CLI with private SERVICE/ROOT state and a real writer FD."""
import fcntl
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
import uuid
from unittest.mock import patch

ROOT=Path(__file__).resolve().parents[2]
LIB=ROOT/"systemvm/debian/usr/local/lib/ablestack-storage"
sys.path.insert(0,str(LIB))
from config_generation import Generation
from native_render_runtime import NativeRenderedRuntime
from rendered_generation import NativeRuntimeFailure,native_runtime_diagnostic
from template_maintenance import Maintenance


class StorageQuiesceTransportTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        private=set()
        for line in Path("/proc/self/mountinfo").read_text().splitlines():
            fields=line.split()
            if " - tmpfs " in line:private.add(fields[4])
        if not {"/etc","/var","/run","/tmp"}<=private:
            raise unittest.SkipTest("Packaged transport tests require bwrap private /etc,/var,/run,/tmp")

    def setUp(self):
        self.temp=tempfile.TemporaryDirectory(prefix="storage-quiesce-transport-");self.addCleanup(self.temp.cleanup)
        self.root=Path(self.temp.name);self.cli=ROOT/"systemvm/debian/usr/local/bin/ablestack-storagectl"
        self.instance=str(uuid.uuid4());self.operation=str(uuid.uuid4())
        self.common={"instanceUuid":self.instance,"operationUuid":self.operation,"revision":2}
        self.service_scope={**self.common,"maintenanceUuid":self.operation}
        self.root_scope={**self.common,"templateUpgradeUuid":self.operation}
        self.config=self.root/"configuration";self.config.mkdir(mode=0o700)
        self.generation=Generation(self.root/"generation",self.config)
        source={"instanceUuid":self.instance,"operationUuid":str(uuid.uuid4()),"revision":1}
        for action in ("begin","verify","commit","finish"):self.generation.execute(action,source)
        self.maintenance=Maintenance(self.root/"maintenance",self.generation.status)
        self.lock=self.root/"writer.lock";self.lock.write_bytes(b"");self.lock.chmod(0o600)
        try:
            self.old_flags=fcntl.fcntl(9,fcntl.F_GETFD)
            self.saved_writer=fcntl.fcntl(9,fcntl.F_DUPFD_CLOEXEC,96)
        except OSError:self.saved_writer=None
        lock_fd=os.open(self.lock,os.O_RDWR|os.O_NOFOLLOW)
        if lock_fd!=9:os.dup2(lock_fd,9);os.close(lock_fd)
        fcntl.flock(9,fcntl.LOCK_EX|fcntl.LOCK_NB);self.addCleanup(self.restore_writer)
        binary=self.root/"bin";binary.mkdir(mode=0o700);self.calls=self.root/"systemctl.jsonl"
        systemctl=binary/"systemctl"
        systemctl.write_text("""#!/usr/bin/python3
import fcntl,json,os,sys
from pathlib import Path
lock=os.environ["ABLESTACK_STORAGE_WRITER_LOCK_FILE"]
named=os.stat(lock);inherited=True
try:held=os.fstat(9)
except OSError:
    inherited=False;probe=os.open(lock,os.O_RDONLY)
    try:
        try:fcntl.flock(probe,fcntl.LOCK_EX|fcntl.LOCK_NB)
        except BlockingIOError:pass
        else:raise AssertionError("ROOT inventory outlived its parent writer lock")
    finally:os.close(probe)
else:
    assert (named.st_dev,named.st_ino)==(held.st_dev,held.st_ino)
    assert "FLOCK" in Path("/proc/self/fdinfo/9").read_text()
    assert "WRITE" in Path("/proc/self/fdinfo/9").read_text()
assert sys.argv[1]=="list-units", "Only empty scoped inventory may be observed"
assert "--state=active" in sys.argv and "--type=service" in sys.argv
with open(os.environ["QUIESCE_TRANSPORT_CALLS"],"a") as handle:
    handle.write(json.dumps({"arguments":sys.argv[1:],"writerVerified":True,"writerFdInherited":inherited})+"\\n")
""")
        systemctl.chmod(0o755)
        self.environment=patch.dict(os.environ,{
            "PATH":str(binary)+":"+os.environ["PATH"],
            "ABLESTACK_STORAGE_GENERATION_DIR":str(self.generation.root),
            "ABLESTACK_STORAGE_CONFIGURATION_ROOT":str(self.config),
            "ABLESTACK_STORAGE_TEMPLATE_MAINTENANCE_DIR":str(self.maintenance.root),
            "ABLESTACK_STORAGE_RENDERED_GENERATIONS":str(self.root/"rendered"),
            "ABLESTACK_STORAGE_VOLUME_OPERATIONS":str(self.root/"volume-operations"),
            "ABLESTACK_STORAGE_WRITER_LOCK_FILE":str(self.lock),
            "ABLESTACK_STORAGE_WRITER_LOCK_FD":"9",
            "QUIESCE_TRANSPORT_CALLS":str(self.calls),
            "ABLESTACK_STORAGECTL_CACHE":"0"})
        self.environment.start();self.addCleanup(self.environment.stop)

    def restore_writer(self):
        if self.saved_writer is None:os.close(9)
        else:
            os.dup2(self.saved_writer,9);os.close(self.saved_writer)
            fcntl.fcntl(9,fcntl.F_SETFD,self.old_flags)

    def held_service(self):
        self.maintenance.enter(self.service_scope,"SERVICE")
        self.maintenance.write(self.maintenance.root/"service-maintenance.json",
                               {"scope":self.service_scope,"phase":"HELD","owners":[],"stoppedUnits":[]})

    def native(self,arguments,input_value=None,descriptors=(9,)):
        result=subprocess.run([str(self.cli),*arguments],input=input_value,pass_fds=descriptors,
                              capture_output=True,text=True,timeout=30)
        return result,json.loads(result.stdout)

    def invoke(self,request,transport):
        encoded=json.dumps(request,allow_nan=False)
        if transport=="file":
            path=self.root/"public-request.json";path.write_text(encoded);path.chmod(0o600)
            return self.native(("operation","quiesce",str(path)))
        if transport=="stdin":return self.native(("operation","quiesce","/dev/stdin"),encoded)
        self.assertEqual("memfd",transport)
        descriptor=os.memfd_create("synthetic-public-scope",os.MFD_CLOEXEC|os.MFD_ALLOW_SEALING)
        try:
            os.write(descriptor,encoded.encode());os.lseek(descriptor,0,os.SEEK_SET)
            fcntl.fcntl(descriptor,fcntl.F_ADD_SEALS,fcntl.F_SEAL_WRITE|fcntl.F_SEAL_GROW|fcntl.F_SEAL_SHRINK|fcntl.F_SEAL_SEAL)
            high=fcntl.fcntl(descriptor,fcntl.F_DUPFD_CLOEXEC,64)
            try:return self.native(("operation","quiesce","/proc/self/fd/"+str(high)),descriptors=(9,high))
            finally:os.close(high)
        finally:os.close(descriptor)

    def assert_quiesced(self,result,scope,kind):
        self.assertTrue(result["success"]);self.assertTrue(result["quiesced"])
        self.assertEqual(scope,result["scope"]);self.assertEqual(["SMB"],result["domainsQuiesced"])
        self.assertEqual([],result["stoppedUnits"])
        state=self.maintenance.status()
        self.assertTrue(state["bootHeld"]);self.assertEqual(kind,state["maintenanceKind"])
        self.assertEqual(scope,state["scope"])
        observations=[json.loads(line) for line in self.calls.read_text().splitlines()]
        self.assertTrue(observations);self.assertTrue(all(row["writerVerified"] for row in observations))
        self.assertTrue(all(row["arguments"][0]=="list-units" for row in observations))

    def test_native_runtime_service_sealed_memfd_reaches_both_packaged_clis(self):
        self.held_service()
        result=NativeRenderedRuntime(str(self.cli),None).command(("operation","quiesce"),
                                                               {**self.service_scope,"domains":["SMB"]})
        self.assert_quiesced(result,self.service_scope,"SERVICE")
        observations=[json.loads(line) for line in self.calls.read_text().splitlines()]
        self.assertEqual(2,len(observations))
        self.assertTrue(all(row["writerFdInherited"] for row in observations))
        self.assertTrue(all("ablestack-storage-smb@*.service" in row["arguments"] for row in observations))
        self.assertTrue(all("ablestack-storage-ganesha@*.service" not in row["arguments"] for row in observations))

    def test_service_regular_stdin_and_sealed_memfd_have_identical_scoped_results(self):
        self.held_service()
        for transport in ("file","stdin","memfd"):
            with self.subTest(transport=transport):
                child,result=self.invoke({**self.service_scope,"domains":["SMB"]},transport)
                self.assertEqual(0,child.returncode,child.stderr+child.stdout)
                self.assert_quiesced(result,self.service_scope,"SERVICE")

    def test_native_runtime_root_sealed_memfd_reaches_nested_maintenance_entry(self):
        result=NativeRenderedRuntime(str(self.cli),None).command(("operation","quiesce"),
                                                               {**self.root_scope,"domains":["SMB"]})
        self.assert_quiesced(result,self.root_scope,"ROOT")

    def test_root_regular_stdin_and_sealed_memfd_have_identical_scoped_results(self):
        for transport in ("file","stdin","memfd"):
            with self.subTest(transport=transport):
                child,result=self.invoke({**self.root_scope,"domains":["SMB"]},transport)
                self.assertEqual(0,child.returncode,child.stderr+child.stdout)
                self.assert_quiesced(result,self.root_scope,"ROOT")

    def test_unavailable_nested_maintenance_input_has_closed_path_free_diagnostic(self):
        sentinel="SYNTHETIC_INPUT_PATH_MUST_NOT_APPEAR"
        child,result=self.native(("operation","maintenance","service-quiesce",str(self.root/sentinel)))
        self.assertNotEqual(0,child.returncode)
        self.assertEqual({"success","errorCode","runtimeDiagnostic"},set(result))
        self.assertEqual({"schemaVersion":1,"protocol":"GENERATION","phase":"TRANSPORT",
                          "code":"NATIVE_CHILD_INPUT_UNAVAILABLE"},result["runtimeDiagnostic"])
        self.assertNotIn(sentinel,child.stdout+child.stderr)
        self.assertFalse(self.calls.exists());self.assertFalse(self.maintenance.marker.exists())

    def test_closed_memfd_is_classified_before_any_service_inventory(self):
        child,result=self.native(("operation","maintenance","service-quiesce","/proc/self/fd/64"))
        self.assertNotEqual(0,child.returncode)
        self.assertEqual("NATIVE_CHILD_INPUT_UNAVAILABLE",result["runtimeDiagnostic"]["code"])
        self.assertNotIn("/proc/self/fd",child.stdout+child.stderr);self.assertFalse(self.calls.exists())

    def test_native_parent_preserves_input_unavailable_instead_of_generic_child_failure(self):
        with self.assertRaises(NativeRuntimeFailure) as failure:
            NativeRenderedRuntime(str(self.cli),None).command(
                ("operation","maintenance","service-quiesce",str(self.root/"absent-public-scope")))
        self.assertEqual({"schemaVersion":1,"protocol":"GENERATION","phase":"TRANSPORT",
                          "code":"NATIVE_CHILD_INPUT_UNAVAILABLE"},native_runtime_diagnostic(failure.exception))
        self.assertFalse(self.calls.exists());self.assertFalse(self.maintenance.marker.exists())

    def test_invalid_json_and_foreign_scope_keep_existing_guards(self):
        path=self.root/"malformed-public-request.json";path.write_text("{")
        child,result=self.native(("operation","maintenance","service-quiesce",str(path)))
        self.assertNotEqual(0,child.returncode);self.assertNotIn("runtimeDiagnostic",result)
        self.held_service();before=self.maintenance.marker.read_bytes()
        foreign={**self.service_scope,"maintenanceUuid":str(uuid.uuid4()),"domains":["SMB"]}
        child,result=self.invoke(foreign,"memfd")
        self.assertNotEqual(0,child.returncode);self.assertNotIn("runtimeDiagnostic",result)
        self.assertEqual(before,self.maintenance.marker.read_bytes());self.assertFalse(self.calls.exists())


if __name__=="__main__":unittest.main()
