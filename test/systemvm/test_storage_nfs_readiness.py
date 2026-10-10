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


"""Exercise the embedded NFS probe budget and kernel-child safety without mounts."""
import ast,ipaddress,json,os,re,signal,subprocess,tempfile,time,unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import Mock,patch

SOURCE=Path(__file__).resolve().parents[2]/"systemvm/debian/usr/local/bin/ablestack-storagectl"


def probe_namespace(results):
    block=next(value for value in re.findall(r"<<'PY'\n(.*?)\nPY",SOURCE.read_text(),re.S) if "def probe_nfs_export_visibility(" in value)
    node=next(item for item in ast.parse(block).body if isinstance(item,ast.FunctionDef) and item.name=="probe_nfs_export_visibility")
    api=Mock();api.PIPE=subprocess.PIPE;api.TimeoutExpired=subprocess.TimeoutExpired;api.CompletedProcess=subprocess.CompletedProcess
    children=[]
    def create(arguments,**kwargs):
        result=results.pop(0);child=Mock(pid=777,returncode=None)
        child.poll.return_value=None if isinstance(result,Exception) else 0
        if isinstance(result,Exception):child.communicate.side_effect=result
        else:
            child.returncode=result.returncode;child.communicate.return_value=(result.stdout,result.stderr)
        children.append(child);return child
    api.Popen.side_effect=create
    values={name:getattr(os,name) for name in dir(os)};values['pidfd_open']=Mock(return_value=100);values['close']=Mock()
    system=SimpleNamespace(**values);signals=SimpleNamespace(SIGTERM=signal.SIGTERM,SIGKILL=signal.SIGKILL,pidfd_send_signal=Mock())
    ns={'ipaddress':ipaddress,'subprocess':api,'os':system,'signal':signals,'time':time,'tempfile':tempfile,'payload':{},'endpoint_key':lambda ip,port:str(port)}
    exec(compile(ast.Module(body=[node],type_ignores=[]),str(SOURCE),'exec'),ns)
    return ns,children


class NfsReadinessTest(unittest.TestCase):
    def endpoint_export(self):
        return {'listenIp':'0.0.0.0','port':2049,'listening':True},{'uuid':'export','pseudo':'/export','clients':[{'clients':'*'}]}
    def ip_result(self):return subprocess.CompletedProcess(['ip'],0,'2: eth0 inet 10.1.1.9/24 scope global eth0','')

    def test_restricted_acl_skips_local_mount_without_disabling_listener(self):
        ns,children=probe_namespace([self.ip_result()]);endpoint,export=self.endpoint_export();export['clients']=[{'clients':'10.9.0.0/24'}]
        failures=ns['probe_nfs_export_visibility']([endpoint],{('0.0.0.0',2049):[export]})
        self.assertEqual([],failures);self.assertTrue(endpoint['listening']);self.assertEqual('SKIPPED_ACL',endpoint['probeResults'][0]['status']);self.assertEqual(1,ns['subprocess'].Popen.call_count)

    def test_uninterruptible_mount_retains_writer_and_has_only_bounded_waits_and_no_success_claim(self):
        ns,children=probe_namespace([self.ip_result(),subprocess.TimeoutExpired(['mount'],15)])
        with tempfile.TemporaryDirectory() as root,patch.dict(os.environ,{'ABLESTACK_STORAGE_WRITER_LOCK_FD':'9','ABLESTACK_STORAGE_NFS_PROBE_JOURNAL':str(Path(root)/'journal.json')}):
            ns['nfs_probe_root']=root;endpoint,export=self.endpoint_export()
            failures=ns['probe_nfs_export_visibility']([endpoint],{('0.0.0.0',2049):[export]})
            self.assertEqual([endpoint],failures);result=endpoint['probeResults'][0]
            self.assertEqual('PROBE_PENDING',result['status']);self.assertTrue(result['probeChildActive']);self.assertTrue(result['cleanupPending']);self.assertFalse(endpoint['probeSuccess'])
            self.assertEqual('RECOVERY_REQUIRED',json.loads((Path(root)/'journal.json').read_text())['phase']);self.assertEqual(2,ns['subprocess'].Popen.call_count);self.assertEqual((9,),ns['subprocess'].Popen.call_args.kwargs['pass_fds'])
            self.assertTrue(all(0<call.kwargs['timeout']<=15 for call in children[1].communicate.call_args_list))
            self.assertEqual([signal.SIGTERM,signal.SIGKILL],[call.args[1] for call in ns['signal'].pidfd_send_signal.call_args_list])

    def test_mount_unmount_and_cleanup_share_the_same_total_deadline(self):
        ns,children=probe_namespace([self.ip_result(),subprocess.CompletedProcess(['mount'],0,'',''),subprocess.TimeoutExpired(['umount'],10)])
        ns['payload']['probeDeadlineSeconds']=1
        with tempfile.TemporaryDirectory() as root:
            ns['nfs_probe_root']=root;endpoint,export=self.endpoint_export();started=time.monotonic()
            ns['probe_nfs_export_visibility']([endpoint],{('0.0.0.0',2049):[export]})
            self.assertLess(time.monotonic()-started,1);self.assertTrue(endpoint['probeResults'][0]['cleanupPending'])
            self.assertTrue(all(call.kwargs['timeout']<=1 for child in children for call in child.communicate.call_args_list))

    def test_successful_probe_checks_both_mount_and_unmount_before_ready(self):
        done=subprocess.CompletedProcess([],0,'','');ns,children=probe_namespace([self.ip_result(),done,done,done])
        with tempfile.TemporaryDirectory() as root:
            ns['nfs_probe_root']=root;endpoint,export=self.endpoint_export()
            self.assertEqual([],ns['probe_nfs_export_visibility']([endpoint],{('0.0.0.0',2049):[export]}));self.assertTrue(endpoint['probeSuccess']);self.assertTrue(endpoint['probeResults'][0]['success'])
            self.assertEqual([],list(Path(root).iterdir()))

if __name__=='__main__':unittest.main()
