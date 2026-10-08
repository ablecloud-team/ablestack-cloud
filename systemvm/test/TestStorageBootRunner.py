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
import json,tempfile
import os,signal,subprocess,sys,time,unittest
from unittest.mock import Mock,patch
LIB=Path(__file__).resolve().parents[2]/"systemvm/debian/usr/local/lib/ablestack-storage"
sys.path.insert(0,str(LIB))
import boot_runner as module


class StorageBootRunnerTest(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup)
        self.journal=Path(self.temp.name)/'state'/'child.json'
        patcher=patch.dict(os.environ,{'ABLESTACK_STORAGE_BOOT_CHILD_JOURNAL':str(self.journal)});patcher.start();self.addCleanup(patcher.stop)

    def test_actual_slow_child_is_terminated_with_pidfd_and_total_wait_is_bounded(self):
        began=time.monotonic();result=module.BootRunner(began+.1).run([sys.executable,'-c','import time;time.sleep(5)'])
        self.assertFalse(result['success']);self.assertTrue(result['recoveryRequired']);self.assertTrue(result['deadlineExceeded']);self.assertFalse(result['childActive'])
        self.assertLess(time.monotonic()-began,.5)

    def test_uninterruptible_child_uses_only_remaining_bounded_waits_and_reports_recovery(self):
        child=Mock(pid=123);child.poll.return_value=None;child.wait.side_effect=subprocess.TimeoutExpired(['mount'],1)
        descriptor=os.open('/dev/null',os.O_RDONLY)
        with patch.object(module.subprocess,'Popen',return_value=child),patch.object(module.os,'pidfd_open',return_value=descriptor),patch.object(module.signal,'pidfd_send_signal') as send:
            def assert_checkpoint(*args):
                self.assertEqual('TIMED_OUT_PENDING_RECONCILE',json.loads(self.journal.read_text())['phase'])
            send.side_effect=assert_checkpoint
            result=module.BootRunner(time.monotonic()+1).run(['mount','-a'])
        self.assertTrue(result['childActive']);self.assertTrue(result['recoveryRequired']);self.assertEqual([signal.SIGTERM,signal.SIGKILL],[call.args[1] for call in send.call_args_list])
        self.assertTrue(all(0<call.kwargs['timeout']<=1 for call in child.wait.call_args_list))
        with self.assertRaises(OSError):os.fstat(descriptor)

    def test_expired_budget_or_missing_pidfd_support_starts_no_command(self):
        with patch.object(module.subprocess,'Popen') as launch:
            with self.assertRaises(TimeoutError):module.BootRunner(time.monotonic()-1).run(['mount','-a'])
            launch.assert_not_called()
        with patch.object(module.os,'pidfd_open',None),patch.object(module.subprocess,'Popen') as launch:
            with self.assertRaises(ValueError):module.BootRunner(time.monotonic()+10).run(['mount','-a'])
            launch.assert_not_called()

    def test_actual_signed_shell_wrapper_preserves_fixed_python_heredoc_input(self):
        source=(LIB.parents[1]/'bin/ablestack-storage-boot-reconcile').read_text()
        function=source[source.index('boot_bounded_exec() {'):source.index('\nlog() {')]
        script='BOOT_DEADLINE=$(python3 -c "import time;print(time.monotonic()+5)")\n'+function+'\nboot_bounded_exec python3 - <<\'PUBLIC_PROBE\'\nimport sys\nprint("BOOT_PUBLIC_PROBE_PASS")\nPUBLIC_PROBE\n'
        result=subprocess.run(['bash'],input=script,text=True,capture_output=True,timeout=6)
        self.assertEqual(0,result.returncode,result.stderr);self.assertEqual('BOOT_PUBLIC_PROBE_PASS',result.stdout.strip())

    def test_actual_writer_probe_reports_exact_boot_child_busy_without_native_lock_or_metadata_write(self):
        cli=LIB.parents[1]/'bin/ablestack-storagectl'
        self.journal.parent.mkdir(mode=0o700)
        ticks=Path('/proc/self/stat').read_text().rpartition(')')[2].split()[19]
        record={'phase':'RECOVERY_REQUIRED','childActive':True,'childPid':os.getpid(),'startTicks':ticks,'bootId':Path('/proc/sys/kernel/random/boot_id').read_text().strip()}
        self.journal.write_text(json.dumps(record));self.journal.chmod(0o600);before=self.journal.read_bytes()
        environment=dict(os.environ,ABLESTACK_STORAGE_WRITER_LOCK_FILE=str(Path(self.temp.name)/'never-lock'))
        result=subprocess.run([str(cli),'operation','writer-idle'],capture_output=True,text=True,env=environment,timeout=5)
        self.assertEqual(0,result.returncode,result.stderr);value=json.loads(result.stdout);self.assertFalse(value['writerIdle']);self.assertTrue(value['bootChildActive'])
        self.assertEqual(before,self.journal.read_bytes());self.assertFalse((Path(self.temp.name)/'never-lock').exists())
        record['startTicks']='unrelated-reused-pid';self.journal.write_text(json.dumps(record))
        result=subprocess.run([str(cli),'operation','writer-idle'],capture_output=True,text=True,env=environment,timeout=5)
        self.assertTrue(json.loads(result.stdout)['writerIdle'])

if __name__=='__main__':unittest.main()
