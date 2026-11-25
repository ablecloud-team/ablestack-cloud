// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

import { configurationText } from '@/views/setting/configurationLocale'

const record = { name: 'api.agent.limit', displaytext: 'API limit', description: 'Limit in seconds' }
const catalog = {
  'api.agent.limit': {
    displaytext: [{ enSource: 'API limit', ko: 'API 한도' }],
    description: [
      { enSource: 'Limit in seconds', ko: '제한 시간(초)' },
      { enSource: 'Limit in milliseconds', ko: '제한 시간(밀리초)' }
    ]
  }
}

test('uses the API original in English and the literal setting key in Korean', () => {
  expect(configurationText(record, 'displaytext', 'ko_KR', catalog)).toBe('API 한도')
  expect(configurationText(record, 'description', 'ko_KR', catalog)).toBe('제한 시간(초)')
  expect(configurationText(record, 'description', 'en', catalog)).toBe('Limit in seconds')
})

test('selects a reviewed source variant independently for each field', () => {
  const variant = { ...record, description: ' Limit in milliseconds\r\n' }
  expect(configurationText(variant, 'description', 'ko_KR', catalog)).toBe('제한 시간(밀리초)')
  expect(configurationText(variant, 'displaytext', 'ko_KR', catalog)).toBe('API 한도')
})

test('falls back to current metadata when text or the catalog is unavailable', () => {
  const changed = { ...record, description: 'New API description', displaytext: '' }
  expect(configurationText(changed, 'description', 'ko_KR', catalog)).toBe('New API description')
  expect(configurationText(changed, 'displaytext', 'ko_KR', catalog)).toBe(record.name)
  expect(configurationText(record, 'description', 'ko_KR', null)).toBe(record.description)
  expect(configurationText({ ...record, name: 'unknown.key' }, 'description', 'ko_KR', catalog)).toBe(record.description)
})
