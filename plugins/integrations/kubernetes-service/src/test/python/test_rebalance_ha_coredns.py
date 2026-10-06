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

import importlib.util,unittest,copy
from pathlib import Path
path=Path(__file__).resolve().parents[2]/'main/resources/script/rebalance-ha-coredns.py'
s=importlib.util.spec_from_file_location('dns',path);m=importlib.util.module_from_spec(s);s.loader.exec_module(m)
class Tests(unittest.TestCase):
 def deployment(self):return {'metadata':{'uid':'deployment-1','resourceVersion':'10'},'spec':{'replicas':2,'template':{'metadata':{'labels':{'k8s-app':'kube-dns'},'annotations':{'user':'keep'}},'spec':{'affinity':{'nodeAffinity':{'requiredDuringSchedulingIgnoredDuringExecution':{'nodeSelectorTerms':[{'matchExpressions':[{'key':'disk','operator':'In','values':['ssd']}]}]}},'podAntiAffinity':{'preferredDuringSchedulingIgnoredDuringExecution':[{'weight':10,'podAffinityTerm':{'topologyKey':'topology.kubernetes.io/zone'}}]}}}}}}
 def test_preserves_custom_policy_and_adds_identity_preconditions(self):
  d=self.deployment();old=copy.deepcopy(d);p=m.rebalance_patch(d);self.assertEqual(d,old);self.assertEqual(p[0]['value'],'deployment-1');self.assertEqual(p[1]['value'],'10');a=p[2]['value'];self.assertEqual(a['nodeAffinity'],d['spec']['template']['spec']['affinity']['nodeAffinity']);self.assertEqual(a['podAntiAffinity']['preferredDuringSchedulingIgnoredDuringExecution'],d['spec']['template']['spec']['affinity']['podAntiAffinity']['preferredDuringSchedulingIgnoredDuringExecution']);self.assertEqual(p[3]['value']['user'],'keep')
 def test_retry_does_not_duplicate_required_term(self):
  d=self.deployment();d['spec']['template']['spec']['affinity']['podAntiAffinity']['requiredDuringSchedulingIgnoredDuringExecution']=[copy.deepcopy(m.DNS_TERM)];p=m.rebalance_patch(d);self.assertEqual(len(p[2]['value']['podAntiAffinity']['requiredDuringSchedulingIgnoredDuringExecution']),1)
 def test_single_replica_fails_before_patch(self):
  d=self.deployment();d['spec']['replicas']=1
  with self.assertRaises(ValueError):m.rebalance_patch(d)
 def test_missing_identity_and_wrong_selector_fail(self):
  for key in ['uid','resourceVersion']:
   d=self.deployment();d['metadata'].pop(key)
   with self.assertRaises(ValueError):m.rebalance_patch(d)
  d=self.deployment();d['spec']['template']['metadata']['labels']['k8s-app']='foreign'
  with self.assertRaises(ValueError):m.rebalance_patch(d)
 def test_foreign_terminating_and_notready_pods_cannot_satisfy_gate(self):
  d=self.deployment();rs={'items':[{'metadata':{'uid':'owned-rs','ownerReferences':[{'uid':'deployment-1','kind':'Deployment','controller':True}]}},{'metadata':{'uid':'foreign-rs','ownerReferences':[{'uid':'deployment-2','kind':'Deployment','controller':True}]}}]}
  def pod(rs,node,ready='True',terminating=False):
   p={'metadata':{'ownerReferences':[{'uid':rs,'kind':'ReplicaSet','controller':True}]},'spec':{'nodeName':node},'status':{'conditions':[{'type':'Ready','status':ready}]}}
   if terminating:p['metadata']['deletionTimestamp']='now'
   return p
  p={'items':[pod('owned-rs','node-a'),pod('owned-rs','node-b'),pod('foreign-rs','node-c'),pod('owned-rs','node-d','False'),pod('owned-rs','node-e',terminating=True)]};self.assertEqual(m.owned_ready_nodes(d,rs,p),['node-a','node-b'])
 def test_worker_preference_preserves_required_policy(self):
  d=self.deployment(); original=copy.deepcopy(d['spec']['template']['spec']['affinity']['nodeAffinity']);a=m.rebalance_patch(d,True)[2]['value'];self.assertEqual(a['nodeAffinity']['requiredDuringSchedulingIgnoredDuringExecution'],original['requiredDuringSchedulingIgnoredDuringExecution']);self.assertEqual(a['nodeAffinity']['preferredDuringSchedulingIgnoredDuringExecution'][0]['weight'],100);d['spec']['template']['spec']['affinity']=a;self.assertEqual(len(m.rebalance_patch(d,True)[2]['value']['nodeAffinity']['preferredDuringSchedulingIgnoredDuringExecution']),1)
 def test_previous_revision_cannot_satisfy_gate(self):
  d=self.deployment();d['metadata']['annotations']={'deployment.kubernetes.io/revision':'2'};rs={'items':[{'metadata':{'uid':'old-rs','annotations':{'deployment.kubernetes.io/revision':'1'},'ownerReferences':[{'uid':'deployment-1','kind':'Deployment','controller':True}]}}]};pods={'items':[{'metadata':{'ownerReferences':[{'uid':'old-rs','kind':'ReplicaSet','controller':True}]},'spec':{'nodeName':'node-a'},'status':{'conditions':[{'type':'Ready','status':'True'}]}}]};self.assertEqual(m.owned_ready_nodes(d,rs,pods),[])
if __name__ == '__main__':
 unittest.main()

