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
from pathlib import Path
import base64,hashlib,os,subprocess,sys,tempfile,unittest
from unittest.mock import patch
LIB=Path(__file__).resolve().parents[2]/"systemvm/debian/usr/local/lib/ablestack-storage"
sys.path.insert(0,str(LIB))
import identity_capsule

class StorageIdentitySourceFenceTest(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup);self.root=Path(self.temp.name)
        self.tdb=self.root/"passdb.tdb";self.tdb.write_bytes(b"SYNTHETIC_PRIVATE_DB_ONLY");self.tdb.chmod(0o600)
        self.accounts=set()
        for name in ("passwd","group","shadow","gshadow"):
            path=self.root/name;path.touch(mode=0o600);self.accounts.add(str(path))
        self.files=patch.object(identity_capsule,"FILES",{str(self.tdb)});self.files.start();self.addCleanup(self.files.stop)
        self.adfiles=patch.object(identity_capsule,"AD_FILES",set());self.adfiles.start();self.addCleanup(self.adfiles.stop)
        self.accountfiles=patch.object(identity_capsule,"ACCOUNT_FILES",self.accounts);self.accountfiles.start();self.addCleanup(self.accountfiles.stop)
        self.livefiles=patch.object(identity_capsule,"LIVE_TDB_FILES",{str(self.tdb)});self.livefiles.start();self.addCleanup(self.livefiles.stop)
    def test_real_open_samba_identity_holder_blocks_before_raw_tdb_or_account_read(self):
        holder=subprocess.Popen([sys.executable,"-c","import sys; from pathlib import Path; Path('/proc/self/comm').write_text('smbd'); f=open(sys.argv[1],'rb'); print('READY',flush=True); sys.stdin.read(1)",str(self.tdb)],stdin=subprocess.PIPE,stdout=subprocess.PIPE,text=True)
        try:
            self.assertEqual("READY",holder.stdout.readline().strip())
            with patch("identity_capsule.regular_file",side_effect=AssertionError("raw private bytes were read")):
                with self.assertRaisesRegex(ValueError,"SOURCE_QUIESCE_REQUIRED"):identity_capsule.collect(["testuser"])
            self.assertEqual(5,len(list(self.root.iterdir())))
        finally:holder.communicate(input="x",timeout=3)
    def test_stopped_source_exports_heap_bytes_without_any_tdbbackup_subprocess_or_extra_file(self):
        with patch("identity_capsule.subprocess.run",side_effect=AssertionError("raw backup subprocess was invoked")):
            payload=identity_capsule.collect(["testuser"])
        self.assertEqual(b"SYNTHETIC_PRIVATE_DB_ONLY",base64.b64decode(payload["files"][str(self.tdb)]["data"]))
        self.assertEqual(hashlib.sha256(self.tdb.read_bytes()).hexdigest(),payload["files"][str(self.tdb)]["sha256"])
        self.assertEqual(5,len(list(self.root.iterdir())));self.assertNotIn("tdbbackup",str(payload))
    def test_regular_private_source_changing_while_read_is_rejected(self):
        original=os.fstat;calls=0
        def drift(descriptor):
            nonlocal calls
            calls+=1
            if calls==2:
                with self.tdb.open("ab") as changed:changed.write(b"CHANGED")
            return original(descriptor)
        with patch("identity_capsule.os.fstat",side_effect=drift):
            with self.assertRaisesRegex(ValueError,"changed while reading"):identity_capsule.regular_file(self.tdb)

if __name__=="__main__":unittest.main()
