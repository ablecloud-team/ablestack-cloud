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

import copy
import importlib.util
import json
import pathlib
import subprocess
import unittest
from unittest.mock import patch

source = pathlib.Path(__file__).parents[2] / 'main/resources/script/managed-addon-placement.py'
spec = importlib.util.spec_from_file_location('placement', source)
placement = importlib.util.module_from_spec(spec)
spec.loader.exec_module(placement)

class ManagedAddonPlacementTest(unittest.TestCase):
    def manifest(self, component='HEADLAMP'):
        name, container, prefixes = placement.TARGETS[component]
        pod = {'containers': [{'name': container, 'image': prefixes[0] + 'a' * 64}],
               'nodeSelector': {'kubernetes.io/os': 'linux'}, 'securityContext': {'runAsNonRoot': True}}
        if component == 'CCM': pod['serviceAccountName'] = name
        return {'apiVersion': 'v1', 'kind': 'List', 'items': [
            {'apiVersion': 'apps/v1', 'kind': 'Deployment', 'metadata': {'name': name, 'namespace': 'kube-system'},
             'spec': {'template': {'spec': pod}}},
            {'apiVersion': 'v1', 'kind': 'Secret', 'metadata': {'name': 'fixture'}, 'data': {'fixture': 'PRIVATE_FIXTURE'}}]}
    def test_adds_only_two_control_tolerations_and_preserves_input(self):
        for component in placement.TARGETS:
            original = self.manifest(component); saved = copy.deepcopy(original)
            result = placement.normalize(original, component)
            self.assertEqual(original, saved)
            expected = copy.deepcopy(original)
            expected['items'][0]['spec']['template']['spec']['tolerations'] = [
                {'key': k, 'operator': 'Exists', 'effect': 'NoSchedule'} for k in placement.CONTROL_TAINTS]
            self.assertEqual(result, expected)
    def test_idempotent_preserves_existing_affinity_and_tolerations(self):
        m = self.manifest('CCM'); s = m['items'][0]['spec']['template']['spec']
        s['tolerations'] = [{'key': 'node.cloudprovider.kubernetes.io/uninitialized', 'value': 'true', 'effect': 'NoSchedule'}]
        s['affinity'] = {'nodeAffinity': {'preferredDuringSchedulingIgnoredDuringExecution': []}}
        n = placement.normalize(m, 'CCM')
        self.assertEqual(n, placement.normalize(n, 'CCM'))
        self.assertEqual(n['items'][0]['spec']['template']['spec']['tolerations'][0], s['tolerations'][0])
        self.assertEqual(n['items'][0]['spec']['template']['spec']['affinity'], s['affinity'])
    def test_existing_all_effects_exists_toleration_is_not_duplicated(self):
        m = self.manifest(); m['items'][0]['spec']['template']['spec']['tolerations'] = [{'operator': 'Exists'}]
        self.assertEqual(m, placement.normalize(m, 'HEADLAMP'))
    def test_rejects_missing_duplicate_wrong_namespace_and_api_version(self):
        for mutation in ('missing', 'duplicate', 'namespace', 'version'):
            m = self.manifest()
            if mutation == 'missing': m['items'].pop(0)
            elif mutation == 'duplicate': m['items'].append(copy.deepcopy(m['items'][0]))
            elif mutation == 'namespace': m['items'][0]['metadata']['namespace'] = 'user'
            else: m['items'][0]['apiVersion'] = 'extensions/v1beta1'
            with self.assertRaises(ValueError): placement.normalize(m, 'HEADLAMP')
    def test_rejects_unrelated_image_and_mutable_image(self):
        for image in ('private-fixture/unrelated@sha256:'+'a'*64, 'ghcr.io/headlamp-k8s/headlamp:latest'):
            m = self.manifest();m['items'][0]['spec']['template']['spec']['containers'][0]['image'] = image
            with self.assertRaises(ValueError): placement.normalize(m, 'HEADLAMP')
    def test_rejects_ccm_service_account_and_unknown_component(self):
        m = self.manifest('CCM');m['items'][0]['spec']['template']['spec']['serviceAccountName'] = 'default'
        with self.assertRaises(ValueError): placement.normalize(m, 'CCM')
        with self.assertRaises(ValueError): placement.normalize(m, 'USER')
    @patch.object(placement.subprocess, 'run')
    def test_cli_applies_stdin_without_changing_source_or_logging_secret(self, run):
        run.side_effect = [subprocess.CompletedProcess([],0,json.dumps(self.manifest()),''), subprocess.CompletedProcess([],0,'configured','')]
        with patch('sys.stdout') as out:
            self.assertEqual(placement.cli('/verified/headlamp.yaml','HEADLAMP'),0)
        dry, apply = run.call_args_list
        self.assertIn('--dry-run=client', dry.args[0]);self.assertEqual(apply.args[0][-3:], ['apply','-f','-'])
        self.assertEqual(json.loads(apply.kwargs['input']), placement.normalize(self.manifest(),'HEADLAMP'))
        self.assertNotIn('PRIVATE_FIXTURE', str(out.mock_calls))
        self.assertIn('--kubeconfig=/etc/kubernetes/admin.conf', apply.args[0])
    @patch.object(placement.subprocess, 'run')
    def test_cli_errors_are_redacted_and_preserve_retry_category(self, run):
        for raw, category in [('Unable to connect to the server: PRIVATE_FIXTURE','Unable to connect'), ('(Forbidden): PRIVATE_FIXTURE','(Forbidden)'), ('invalid PRIVATE_FIXTURE','(Invalid)')]:
            run.return_value = subprocess.CompletedProcess([],1,'',raw)
            with patch('sys.stderr') as err:
                self.assertEqual(placement.cli('/verified/headlamp.yaml','HEADLAMP'),1)
            text = str(err.mock_calls);self.assertIn(category,text);self.assertNotIn('PRIVATE_FIXTURE',text)
    @patch.object(placement.subprocess, 'run')
    def test_cli_identity_failure_never_applies(self, run):
        m = self.manifest();m['items'].pop(0);run.return_value = subprocess.CompletedProcess([],0,json.dumps(m),'')
        with patch('sys.stderr'): self.assertEqual(placement.cli('/verified/headlamp.yaml','HEADLAMP'),1)
        self.assertEqual(run.call_count,1)

    @patch.object(placement.subprocess, 'run')
    def test_existing_uses_uid_and_resource_version_cas_for_tolerations_only(self, run):
        d = self.manifest('CCM')['items'][0];d['metadata'].update(uid='managed-uid',resourceVersion='123')
        run.side_effect = [subprocess.CompletedProcess([],0,json.dumps(d),''),subprocess.CompletedProcess([],0,'patched','')]
        with patch('sys.stdout'): self.assertEqual(placement.cli('--existing','CCM'),0)
        dry, applied = run.call_args_list;args = applied.args[0]
        self.assertIn('--ignore-not-found', dry.args[0]);self.assertEqual(args[-3], '--type=json')
        changes = json.loads(args[-1]);self.assertEqual([x['path'] for x in changes], ['/metadata/uid','/metadata/resourceVersion','/spec/template/spec/tolerations'])
        self.assertEqual(changes[0]['value'],'managed-uid');self.assertEqual(changes[1]['value'],'123')
        self.assertIsNone(applied.kwargs['input'])
    @patch.object(placement.subprocess, 'run')
    def test_existing_missing_or_legacy_component_keeps_legacy_recovery(self, run):
        for output in ('', json.dumps({'spec': {'template': {'spec': {'containers': [{'image':'legacy/ccm:1'}]}}}})):
            run.reset_mock();run.return_value = subprocess.CompletedProcess([],0,output,'')
            with patch('sys.stdout'): self.assertEqual(placement.cli('--existing','CCM'),0)
            self.assertEqual(run.call_count,1)
    @patch.object(placement.subprocess, 'run')
    def test_existing_current_identity_without_cas_is_rejected(self, run):
        d = self.manifest()['items'][0];run.return_value = subprocess.CompletedProcess([],0,json.dumps(d),'')
        with patch('sys.stderr'): self.assertEqual(placement.cli('--existing','HEADLAMP'),1)
        self.assertEqual(run.call_count,1)
    @patch.object(placement.subprocess, 'run')
    def test_existing_already_normalized_is_read_only(self, run):
        d = placement.normalize(self.manifest()['items'][0], 'HEADLAMP');run.return_value = subprocess.CompletedProcess([],0,json.dumps(d),'')
        with patch('sys.stdout'): self.assertEqual(placement.cli('--existing','HEADLAMP'),0)
        self.assertEqual(run.call_count,1)

    def test_consecutive_kubectl_json_documents_preserve_all_resources(self):
        m = self.manifest('CCM');text = '\n'.join(json.dumps(x) for x in m['items'])
        self.assertEqual(placement.parse_document_stream(text), m)
        self.assertEqual(placement.parse_document_stream('  ' + json.dumps(m) + '\n'), m)
    def test_malformed_empty_or_non_object_stream_is_rejected(self):
        for text in (' ', '[]', '{} broken', '{} [1]'):
            with self.assertRaises(ValueError): placement.parse_document_stream(text)
    @patch.object(placement.subprocess, 'run')
    def test_cli_multi_document_provider_dry_run_is_applied_as_list(self, run):
        m = self.manifest('CCM');run.side_effect = [subprocess.CompletedProcess([],0,'\n'.join(json.dumps(x) for x in m['items']),''),subprocess.CompletedProcess([],0,'configured','')]
        with patch('sys.stdout'): self.assertEqual(placement.cli('/verified/provider.yaml','CCM'),0)
        self.assertEqual(json.loads(run.call_args_list[1].kwargs['input']), placement.normalize(m,'CCM'))

if __name__ == '__main__': unittest.main()
