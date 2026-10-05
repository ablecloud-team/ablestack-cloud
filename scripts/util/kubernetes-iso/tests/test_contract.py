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
import copy
import hashlib
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

HERE=Path(__file__).resolve().parents[1]
def module(name):
 spec=importlib.util.spec_from_file_location(name,HERE/(name+'.py'));m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m);return m
build=module('build');validate=module('validate')

class RecipeGates(unittest.TestCase):
 def setUp(self):self.recipe=json.loads((HERE/'recipes/kubernetes-1.34.2-amd64.json').read_text())
 def test_supported_catalog(self):
  for path in (HERE/'recipes').glob('*.json'):build.check_recipe(json.loads(path.read_text()))
 def test_cross_minor_autoscaler_is_rejected(self):
  self.recipe['kubernetes_version']='1.35.2'
  with self.assertRaisesRegex(ValueError,'minor'):build.check_recipe(self.recipe)
 def test_mutable_manifest_is_rejected(self):
  self.recipe['files'][0]['url']='https://raw.githubusercontent.com/test/test/main/file'
  with self.assertRaisesRegex(ValueError,'mutable'):build.check_recipe(self.recipe)
 def test_missing_provider_payload_is_rejected(self):
  self.recipe['files']=[f for f in self.recipe['files'] if f['path']!='provider.yaml']
  with self.assertRaisesRegex(ValueError,'mandatory'):build.check_recipe(self.recipe)
 def test_sha1_component_is_rejected(self):
  self.recipe['components']['autoscaler']['api_signature']='HMAC-SHA1'
  with self.assertRaisesRegex(ValueError,'unapproved'):build.check_recipe(self.recipe)
 def test_missing_digest_is_rejected(self):
  self.recipe['images'][0]['reference']='example.com/controller:latest'
  with self.assertRaisesRegex(ValueError,'digest'):build.check_recipe(self.recipe)
 def test_payload_path_escape_is_rejected(self):
  for name in ['../outside','/absolute','dir/../escape','a\\b']:
   with self.assertRaises(ValueError):build.safe_path(name)
 def test_corrupt_download_cache_fails_closed(self):
  with tempfile.TemporaryDirectory() as temporary:
   root=Path(temporary);file=self.recipe['files'][0]
   (root/file['sha256']).write_bytes(b'corrupted prior download')
   with self.assertRaisesRegex(ValueError,'cached source checksum'):build.download(file,root/'output',root)
   self.assertFalse((root/'output').exists())

class IndependentReaderGates(unittest.TestCase):
 def test_unlisted_file_is_rejected_before_any_binary_exec(self):
  with tempfile.TemporaryDirectory() as temporary:
   root=Path(temporary)
   recipe={'kubernetes_version':'1.34.2','components':{}}
   manifest={'recipe_sha256':'expected','kubernetes_version':'1.34.2','architecture':'amd64','components':{}}
   (root/'manifest.json').write_text(json.dumps(manifest))
   (root/'provider.yaml').write_text('tampered')
   (root/'SHA256SUMS').write_text(validate.hash_file(root/'manifest.json')+'  manifest.json\n')
   with self.assertRaisesRegex(ValueError,'unlisted or missing'):validate.validate_payload(root,recipe,'expected')
 def test_arm64_binary_cannot_be_claimed_as_amd64(self):
  elf=bytearray(64);elf[:6]=b'\x7fELF\x02\x01';elf[18:20]=b'\xb7\x00'
  with self.assertRaisesRegex(ValueError,'amd64 ELF'):validate.elf_amd64(elf,'arm64-controller')
 def test_mutable_runtime_image_is_rejected(self):
  with self.assertRaisesRegex(ValueError,'mutable'):validate.manifest_images({'image':'apache/ccm:latest','imagePullPolicy':'IfNotPresent'})

if __name__=='__main__':unittest.main()
