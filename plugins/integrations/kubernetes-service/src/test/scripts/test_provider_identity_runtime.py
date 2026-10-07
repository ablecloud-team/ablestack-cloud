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

"""Execute consumer shell snippets against containerd 1.x/2.x fixtures."""
import pathlib, re, subprocess, tempfile, unittest
BASE=pathlib.Path(__file__).resolve().parents[2]/'main/resources'
class ExternalProviderFlags(unittest.TestCase):
 def test_payload_gates_external_provider_in_every_node_template(self):
  for name in ['k8s-control-node.yml','k8s-control-node-add.yml','k8s-node.yml']:
   source=(BASE/'conf'/name).read_text()
   block=re.search(r'(?m)^        KUBELET_EXTRA_ARGS="--cgroup-driver=systemd"\n.*?^        printf .*? > /etc/default/kubelet', source, re.S).group()
   for payload in [None, '', 'apiVersion: apps/v1\n']:
    with self.subTest(name=name,payload=payload),tempfile.TemporaryDirectory() as temporary:
     root=pathlib.Path(temporary)
     if payload is not None:(root/'provider.yaml').write_text(payload)
     output=root/'kubelet';script='BINARIES_DIR="'+str(root)+'"\n'+block.replace('/etc/default/kubelet',str(output))
     subprocess.run(['bash','-e','-c',script],check=True)
     flags=output.read_text()
     self.assertEqual(payload is not None and bool(payload),'--cloud-provider=external' in flags)
     self.assertIn('--cgroup-driver=systemd',flags)
 def test_upgrade_preserves_unrelated_flags_and_handles_quoted_values(self):
  source=(BASE/'script/upgrade-kubernetes.sh').read_text()
  start=source.index('  # New Mold payloads use the external CCM')
  block=source[start:source.index('  systemctl daemon-reload',start)]
  for value in ['--cgroup-driver=systemd --node-ip=10.1.2.3', '"--cgroup-driver=systemd --node-ip=10.1.2.3"', "'--cgroup-driver=systemd --node-ip=10.1.2.3'", '""']:
   with self.subTest(value=value),tempfile.TemporaryDirectory() as temporary:
    root=pathlib.Path(temporary);(root/'provider.yaml').write_text('nonempty')
    flags=root/'kubelet';flags.write_text('KUBELET_EXTRA_ARGS='+value+'\nOTHER_SETTING=preserved\n')
    script='BINARIES_DIR="'+str(root)+'"\n'+block.replace('/etc/default/kubelet',str(flags))
    subprocess.run(['bash','-e','-c',script],check=True)
    result=flags.read_text();self.assertIn('--cloud-provider=external',result);self.assertIn('OTHER_SETTING=preserved',result)
    if 'node-ip' in value:self.assertIn('--node-ip=10.1.2.3',result)
    # The same migration is idempotent.
    subprocess.run(['bash','-e','-c',script],check=True);self.assertEqual(result,flags.read_text())
 def test_upgrade_rejects_conflicting_provider_without_rewriting_flags(self):
  source=(BASE/'script/upgrade-kubernetes.sh').read_text();start=source.index('  # New Mold payloads use the external CCM');block=source[start:source.index('  systemctl daemon-reload',start)]
  with tempfile.TemporaryDirectory() as temporary:
   root=pathlib.Path(temporary);(root/'provider.yaml').write_text('nonempty');flags=root/'kubelet';original='KUBELET_EXTRA_ARGS=--cloud-provider=other\n';flags.write_text(original)
   result=subprocess.run(['bash','-e','-c','BINARIES_DIR="'+str(root)+'"\n'+block.replace('/etc/default/kubelet',str(flags))],capture_output=True,text=True)
   self.assertNotEqual(0,result.returncode);self.assertEqual(original,flags.read_text())
if __name__=='__main__':unittest.main()
