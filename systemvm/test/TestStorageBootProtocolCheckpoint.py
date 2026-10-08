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

import json,os,subprocess,tempfile,unittest
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]
BOOT=ROOT/'systemvm/debian/usr/local/bin/ablestack-storage-boot-reconcile'

class StorageBootProtocolCheckpointTest(unittest.TestCase):
    def test_nfs_cold_retry_does_not_reapply_successful_smb_and_receipts_are_bound_to_boot_and_file(self):
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder);nfs=root/'nfs.json';smb=root/'smb.json'
            for file in (nfs,smb):file.write_text('{}');file.chmod(0o600)
            stub=root/'storagectl';calls=root/'calls'
            stub.write_text('#!/bin/bash\necho "$1 $2 $3" >> "$CALLS"\nif [[ "$1 $2 $3" == "nfs export apply" ]]; then\n count=$(grep -c "nfs export apply" "$CALLS")\n (( count > 1 )) || exit 1\nfi\nexit 0\n');stub.chmod(0o700)
            source=BOOT.read_text();body=source[source.index('boot_protocol_checkpoint() {'):];a=body.index('boot_protocol_ready() {');b=body.index('\nattempt=1',a);body=body[:a]+'boot_protocol_ready() { return 0; }\n'+body[b:]
            prefix='set -euo pipefail\nBOOT_DEADLINE=$(python3 -c "import time;print(time.monotonic()+10)")\nboot_bounded_exec() { "$@"; }\nSTORAGECTL='+str(stub)+'\nMONITOR=/nonexistent\nNFS_PAYLOAD='+str(nfs)+'\nSMB_PAYLOAD='+str(smb)+'\nPOSIX_POLICIES=/nonexistent\nISCSI_PAYLOAD=/nonexistent\nNVMEOF_PAYLOAD=/nonexistent\nMAX_ATTEMPTS=3\nSLEEP_SECONDS=0\nLOG_FILE='+str(root/'log')+'\nlog() { :; }\nensure_ganesha_runtime_dirs() { :; }\nmount() { :; }\n'
            environment=dict(os.environ,CALLS=str(calls),ABLESTACK_STORAGE_BOOT_CHECKPOINT_DIR=str(root/'checkpoints'))
            result=subprocess.run(['bash'],input=prefix+body,text=True,capture_output=True,env=environment,timeout=10)
            self.assertEqual(0,result.returncode,result.stderr)
            entries=calls.read_text().splitlines();self.assertEqual(2,entries.count('nfs export apply'));self.assertEqual(1,entries.count('smb share apply'))
            receipt=json.loads((root/'checkpoints/smb.json').read_text());self.assertEqual('VERIFIED',receipt['phase']);self.assertTrue(receipt['bootId']);self.assertEqual(64,len(receipt['desiredSha256']))
            helper=source[source.index('boot_protocol_checkpoint() {'):source.index('attempt=1')]
            smb.write_text('{"changed":true}')
            checked=subprocess.run(['bash'],input=helper+'\nboot_protocol_checkpoint SMB '+str(smb)+' check\n',text=True,capture_output=True,env=environment,timeout=5)
            self.assertNotEqual(0,checked.returncode)

    def test_fresh_smb_health_after_apply_ack_requires_owned_listener_and_tcp(self):
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder);desired=root/'desired.json';desired.write_text(json.dumps({'enabled':True,'shares':[{'state':'Ready'}]}))
            health=root/'health.json';source=BOOT.read_text();function=source[source.index('boot_protocol_ready() {'):source.index('\nattempt=1')]
            prefix='STORAGECTL=/unused\nboot_bounded_exec() { cat "$HEALTH_FILE"; }\n'
            environment=dict(os.environ,HEALTH_FILE=str(health))
            for owned,tcp,expected in ((False,True,1),(True,False,1),(True,True,0)):
                health.write_text(json.dumps({'success':True,'smbRuntime':{'available':True,'runtimeEndpoints':[{'listenerOwned':owned,'tcpReady':tcp}]}}))
                result=subprocess.run(['bash'],input=prefix+function+'\nboot_protocol_ready SMB '+str(desired)+'\n',text=True,capture_output=True,env=environment,timeout=5)
                self.assertEqual(expected,result.returncode,result.stderr)

if __name__=='__main__':unittest.main()
