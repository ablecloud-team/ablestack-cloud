# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements.  See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.  The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at
#
#   http: #www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied.  See the License for the
# specific language governing permissions and limitations
# under the License.


import ast
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
import uuid
ROOT=Path(__file__).resolve().parents[2];LIB=ROOT/"systemvm/debian/usr/local/lib/ablestack-storage";sys.path.insert(0,str(LIB))
import config_generation as module
CLI=ROOT/"systemvm/debian/usr/local/bin/ablestack-storagectl"


class StorageGenerationBootIdentityTest(unittest.TestCase):
    def test_actual_cli_status_reads_live_boot_without_changing_generation_or_any_file(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);config=root/"config";config.mkdir(mode=0o700);generation=root/"generation";generation.mkdir(mode=0o700)
            selected=module.Generation(generation,config);digest=selected.digest()
            current={"instanceUuid":str(uuid.uuid4()),"operationUuid":str(uuid.uuid4()),"revision":5,"configurationSha256":digest}
            path=generation/"current.json";path.write_text(json.dumps(current));path.chmod(0o600)
            before={str(p):p.read_bytes() for p in root.rglob("*") if p.is_file()}
            env=dict(os.environ,ABLESTACK_STORAGE_GENERATION_DIR=str(generation),ABLESTACK_STORAGE_CONFIGURATION_ROOT=str(config),ABLESTACK_STORAGE_LOG_FILE="/dev/null")
            replies=[]
            for _ in range(2):
                result=subprocess.run(["bash",str(CLI),"operation","generation","status"],capture_output=True,text=True,timeout=15,env=env)
                self.assertEqual(0,result.returncode,result.stderr);replies.append(json.loads(result.stdout))
            boot=str(uuid.UUID(Path("/proc/sys/kernel/random/boot_id").read_text().strip()))
            for reply in replies:
                self.assertEqual(boot,reply["bootId"]);self.assertEqual(current,reply["generation"]);self.assertEqual(digest,reply["configurationSha256"])
                self.assertEqual("IN_SYNC",reply["generationStatus"]);self.assertIsNone(reply["pendingOperationUuid"]);self.assertNotIn("bootId",reply["generation"])
            self.assertEqual(replies[0],replies[1]);self.assertEqual(before,{str(p):p.read_bytes() for p in root.rglob("*") if p.is_file()})
    def test_inline_status_method_matches_reviewed_module_ast_exactly(self):
        block=CLI.read_text().split("<<'PYGENERATION'\n",1)[1].split("\nPYGENERATION",1)[0]
        def status(text):
            cls=next(n for n in ast.parse(text).body if isinstance(n,ast.ClassDef) and n.name=="Generation")
            return next(n for n in cls.body if isinstance(n,ast.FunctionDef) and n.name=="status")
        self.assertEqual(ast.dump(status((LIB/"config_generation.py").read_text())),ast.dump(status(block)))


if __name__=="__main__":unittest.main()
