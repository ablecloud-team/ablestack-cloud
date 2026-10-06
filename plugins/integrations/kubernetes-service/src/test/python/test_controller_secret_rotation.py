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

import hashlib
import json
import os
import pathlib
import subprocess
import tempfile
import unittest

SCRIPT = pathlib.Path(__file__).resolve().parents[2] / "main/resources/script/deploy-cloudstack-secret"
ANNOTATION = "mold.ablecloud.io/cloud-config-sha256"

class RotationTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = pathlib.Path(self.temp.name)
        self.state = self.root / "state.json"
        self.config = "[Global]\napi-url = http://fixture.invalid/api\napi-key = fixture-api\nsecret-key = fixture-secret\n\n\n"
        self.digest = hashlib.sha256(self.config.encode()).hexdigest()
        self.state.write_text(json.dumps({"items": [self.workload("Deployment", "provider"), self.workload("DaemonSet", "csi")]}))
        self.mock = self.root / "kubectl"
        self.mock.write_text("""#!/usr/bin/env python3
import json,os,pathlib,sys
args=sys.argv[1:];root=pathlib.Path(os.environ['MOCK_ROOT']);state=root/'state.json'
if 'create' in args: print('fixture secret')
elif 'apply' in args: sys.stdin.read()
elif 'get' in args: print(state.read_text())
elif 'patch' in args:
 if os.environ.get('FAIL_PATCH')=='1': sys.exit(17)
 data=json.loads(state.read_text());patch=json.loads(args[args.index('-p')+1]);name=args[args.index('patch')+2]
 for item in data['items']:
  if item['metadata']['name']==name:item['spec']['template'].setdefault('metadata',{}).setdefault('annotations',{}).update(patch['spec']['template']['metadata']['annotations'])
 state.write_text(json.dumps(data))
 with (root/'patches').open('a') as out:out.write(name+'\\n')
else: sys.exit(19)
""")
        self.mock.chmod(0o700)
        self.env = {**os.environ, "MOLD_KUBECTL": str(self.mock), "MOCK_ROOT": str(self.root)}

    def tearDown(self):
        self.temp.cleanup()

    def workload(self, kind, name, digest=None, secret="cloudstack-secret"):
        return {"kind": kind, "metadata": {"name": name}, "spec": {"template": {"metadata": {"annotations": {} if digest is None else {ANNOTATION: digest}}, "spec": {"volumes": [{"secret": {"secretName": secret}}]}}}}

    def run_rotation(self, fail=False):
        env = {**self.env, **({"FAIL_PATCH": "1"} if fail else {})}
        return subprocess.run(["bash", str(SCRIPT), "-u", "http://fixture.invalid/api", "-k", "fixture-api", "-s", "fixture-secret"], capture_output=True, text=True, env=env)

    def test_changed_credentials_roll_all_consumers_without_printing_values(self):
        result=self.run_rotation();self.assertEqual(result.returncode,0,result.stderr)
        self.assertEqual((self.root/'patches').read_text().count('\n'),2)
        self.assertNotIn('fixture-secret',result.stdout+result.stderr)
        self.assertNotIn('fixture-api',result.stdout+result.stderr)
        for item in json.loads(self.state.read_text())['items']:
            self.assertEqual(item['spec']['template']['metadata']['annotations'][ANNOTATION],self.digest)

    def test_same_configuration_does_not_restart_again(self):
        self.assertEqual(self.run_rotation().returncode,0)
        before=(self.root/'patches').read_text()
        self.assertEqual(self.run_rotation().returncode,0)
        self.assertEqual((self.root/'patches').read_text(),before)

    def test_partial_rotation_retries_only_unreconciled_consumers(self):
        self.state.write_text(json.dumps({"items":[self.workload('Deployment','provider',self.digest),self.workload('DaemonSet','csi')]}))
        self.assertEqual(self.run_rotation().returncode,0)
        self.assertEqual((self.root/'patches').read_text(),'csi\n')

    def test_foreign_secret_consumers_are_untouched(self):
        self.state.write_text(json.dumps({"items":[self.workload('Deployment','foreign',secret='foreign-secret')]}))
        self.assertEqual(self.run_rotation().returncode,0)
        self.assertFalse((self.root/'patches').exists())

    def test_failed_patch_cannot_report_reconciliation_success(self):
        result=self.run_rotation(True);self.assertNotEqual(result.returncode,0)
        self.assertNotIn('MOLD_CONTROLLER_CONFIG_RECONCILED',result.stdout)
        self.assertEqual(self.run_rotation().returncode,0)

if __name__ == '__main__':
    unittest.main()
