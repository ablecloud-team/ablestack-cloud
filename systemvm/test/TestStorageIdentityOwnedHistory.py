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
import os
from pathlib import Path
import sys
import unittest
from types import SimpleNamespace
from unittest.mock import patch
ROOT=Path(__file__).resolve().parents[2];LIB=ROOT/"systemvm/debian/usr/local/lib/ablestack-storage";sys.path.insert(0,str(LIB))
import identity_capsule as module

class StorageIdentityOwnedHistoryTest(unittest.TestCase):
    def setUp(self):
        self.user="history:x:12000:12001::/nonexistent:/usr/sbin/nologin";self.group="history:x:12001:"
        self.files={"/etc/ablestack-storage/smb-local-account-provenance.json":json.dumps({"schemaVersion":1,"accounts":{"/etc/passwd":[self.user],"/etc/group":[self.group]}}).encode(),
                    "/etc/passwd":("root:x:0:0:root:/root:/bin/bash\n"+self.user+"\nforeign:x:13000:13001::/x:/bin/false\n").encode(),
                    "/etc/group":("root:x:0:\n"+self.group+"\nforeign:x:13001:\n").encode(),
                    "/etc/shadow":b"history:!:1:0:99999:7:::\nforeign:!:1:0:99999:7:::\n",
                    "/etc/gshadow":b"history:!::\nforeign:!::\n"}
    def capture(self):
        def read(path,*args):return self.files[path],SimpleNamespace(st_mode=0o600,st_gid=0)
        with patch.object(module,"regular_file",side_effect=read),patch.object(module.os.path,"exists",side_effect=lambda path:path in self.files),patch.object(module,"live_identity_database_holders",return_value=[]):
            return module.collect([])
    def test_removed_acl_owned_history_is_encrypted_without_unmanaged_or_nss_names(self):
        result=self.capture()
        self.assertEqual([self.user],result["accounts"]["/etc/passwd"]);self.assertEqual([self.group],result["accounts"]["/etc/group"])
        self.assertEqual(["history"],[row.split(":",1)[0] for row in result["accounts"]["/etc/shadow"]])
        self.assertNotIn("foreign",json.dumps(result["accounts"]))
    def test_changed_or_missing_owned_historical_uid_gid_shell_fails_before_capsule_publication(self):
        self.files["/etc/passwd"]=self.files["/etc/passwd"].replace(self.user.encode(),self.user.replace(":12000:",":12002:").encode())
        with self.assertRaises(ValueError):self.capture()
        self.files["/etc/passwd"]=b"root:x:0:0:root:/root:/bin/bash\n"
        with self.assertRaises(ValueError):self.capture()
if __name__=="__main__":unittest.main()
