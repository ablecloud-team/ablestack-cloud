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

from pathlib import Path
import tempfile,os,subprocess,json
source=Path(__file__).resolve().parents[2]/'main/resources/script/remove-node-from-cluster'
assert not Path('/opt/bin/kubectl').exists(), 'Fake client must not shadow an installed /opt/bin client'
fake=r"""#!/usr/bin/python3
import os,sys,json
args=sys.argv[1:]
with open(os.environ['TRACE'],'a') as f:f.write(json.dumps(args)+'\n')
scenario=os.environ['CASE']
if args[:2]==['get','node']:
 if scenario=='api-error':sys.exit(1)
 if '--ignore-not-found' in args:
  if scenario!='notfound':print('node/worker1')
 else:print('true' if scenario=='pdb-operator-cordon' else 'false')
elif args[0]=='drain':
 assert '--force' not in args and '--delete-local-data' not in args and '--delete-emptydir-data' not in args
 if scenario.startswith(('pdb','unmanaged','emptydir')):sys.exit(1)
elif args[0]=='reset' and scenario=='reset-error':sys.exit(1)
elif args[0]=='delete' and scenario=='delete-error':sys.exit(1)
"""
rows=[]
with tempfile.TemporaryDirectory(prefix='rt1311-script-') as td:
 root=Path(td)
 for name in ['kubectl','kubeadm','rm']:(root/name).write_text(fake);(root/name).chmod(0o700)
 for case,nodeType,op,expected in [('success','control','remove',0),('pdb','control','remove',1),('pdb-operator-cordon','control','remove',1),('unmanaged','control','remove',1),('emptydir','control','remove',1),('api-error','control','remove',1),('notfound','control','remove',0),('reset-error','worker','remove',1),('reset-ok','worker','remove',0),('delete-error','control','delete',1),('delete-ok','control','delete',0)]:
  trace=root/(case+'.jsonl');env=dict(os.environ,PATH=str(root)+':'+os.environ['PATH'],TRACE=str(trace),CASE=case)
  r=subprocess.run(['bash',str(source),'worker1',nodeType,op],env=env,capture_output=True,text=True,timeout=5);assert r.returncode==expected,(case,r.returncode,r.stderr)
  calls=[json.loads(x) for x in trace.read_text().splitlines()];uncordon=sum(x[0]=='uncordon' for x in calls)
  assert uncordon==(1 if case in ['pdb','unmanaged','emptydir'] else 0),(case,calls)
  if case in ['api-error','notfound']:assert not any(x[0] in ['drain','delete','reset'] for x in calls)
  if case=='reset-error':assert not any(x==['-f','/home/cloud/success'] for x in calls),calls
  if case=='reset-ok':assert calls[-1]==['-f','/home/cloud/success'],calls
  rows.append({'case':case,'exitCode':r.returncode,'uncordonCalls':uncordon,'commands':calls})
print(json.dumps({'status':'PASS','cases':rows},indent=2))
