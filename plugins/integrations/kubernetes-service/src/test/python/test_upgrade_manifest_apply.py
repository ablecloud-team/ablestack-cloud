# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements. See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership. The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License. You may obtain a copy of the License at
#
# http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied. See the License for the
# specific language governing permissions and limitations
# under the License.
import pathlib
import subprocess
import tempfile
import unittest

class UpgradeManifestApplyTest(unittest.TestCase):
    def execute(self, error, failures):
        source = (pathlib.Path(__file__).parents[2] / 'main/resources/script/upgrade-kubernetes.sh').read_text()
        start = source.index('apply_upgrade_manifest() {')
        function = source[start:source.index('\n}\n', start) + 3]
        with tempfile.TemporaryDirectory() as d:
            p = pathlib.Path(d)
            cli = p / 'kubectl'
            cli.write_text('#!/bin/bash\nn=$(cat "'+str(p/'count')+'" 2>/dev/null || echo 0); n=$((n+1)); echo "$n" > "'+str(p/'count')+'"\nprintf "%s\\n" "$@" >> "'+str(p/'args')+'"\nif (( n <= '+str(failures)+' )); then printf "%s\\n" '+__import__('shlex').quote(error)+' >&2; exit 1; fi\necho configured\n')
            cli.chmod(0o700)
            function = function.replace('/opt/bin/kubectl', str(cli))
            shell = 'UPGRADE_STATUS_FILE='+str(p/'status')+'\nmark_upgrade_stage() { :; }\nwait_for_upgrade_api() { echo READY_GATE >> '+str(p/'gate')+'; }\nsleep() { :; }\n'+function+'\napply_upgrade_manifest CNI_APPLY /offline/network.yaml'
            r = subprocess.run(['bash', '-c', shell],capture_output=True,text=True,timeout=5)
            return r, int((p/'count').read_text()), (p/'args').read_text(), (p/'status').read_text() if (p/'status').exists() else '', (p/'gate').read_text() if (p/'gate').exists() else ''
    def test_transient_retry_binds_admin_config_and_limits_requests(self):
        r,count,args,status,gate=self.execute('Unable to connect to the server: EOF',2)
        self.assertEqual(r.returncode,0);self.assertEqual(count,3);self.assertEqual(gate.count('READY_GATE'),2)
        self.assertEqual(args.count('--kubeconfig=/etc/kubernetes/admin.conf'),3);self.assertEqual(args.count('--request-timeout=20s'),3)
        self.assertIn('reason=API_TRANSIENT',status);self.assertNotIn('Unable to connect',status)
    def test_invalid_manifest_is_not_retried_or_exposed(self):
        r,count,args,status,gate=self.execute('Error from server (Invalid): sensitive fixture',9)
        self.assertNotEqual(r.returncode,0);self.assertEqual(count,1);self.assertEqual(gate,'');self.assertIn('reason=API_VALIDATION',status);self.assertNotIn('sensitive',status+r.stderr)
    def test_authorization_failure_is_not_retried(self):
        r,count,args,status,gate=self.execute('Error from server (Forbidden): sensitive fixture',9)
        self.assertNotEqual(r.returncode,0);self.assertEqual(count,1);self.assertEqual(gate,'');self.assertIn('reason=API_AUTHORIZATION',status)
    def test_persistent_transport_failure_stops_after_three_attempts(self):
        r,count,args,status,gate=self.execute('Unable to connect to the server: connection reset by peer',9)
        self.assertNotEqual(r.returncode,0);self.assertEqual(count,3);self.assertEqual(gate.count('READY_GATE'),2)
if __name__=='__main__':unittest.main()
