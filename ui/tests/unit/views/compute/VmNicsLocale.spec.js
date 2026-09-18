// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements. See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership. The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License. You may obtain a copy of the License at
// http://www.apache.org/licenses/LICENSE-2.0
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied. See the License for the
// specific language governing permissions and limitations
// under the License.

import fs from 'fs'
import path from 'path'

const files = ['compute/VmNicsTab.vue', 'network/CreateNetwork.vue', 'network/CreateIsolatedNetworkForm.vue', 'network/CreateL2NetworkForm.vue', 'network/CreateSharedNetworkForm.vue']
const source = files.map(file => fs.readFileSync(path.join(__dirname, '../../../../src/views', file), 'utf8')).join('\n')
const keys = [...new Set([...source.matchAll(/'((?:label|message|state)\.[a-zA-Z0-9_.-]+)'/g)].map(match => match[1]).filter(key => !key.endsWith('.')))]
for (const language of ['en', 'ko_KR']) {
  test(`NIC dialogs have no untranslated literal keys in ${language}`, () => {
    const locale = JSON.parse(fs.readFileSync(path.join(__dirname, '../../../../public/locales', language + '.json'), 'utf8'))
    expect(keys.filter(key => !locale[key] || locale[key] === key)).toEqual([])
  })
}
