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

import { readConfigurationQuery, writeConfigurationQuery, listConfigurationParams } from '@/views/setting/configurationQuery'

const groups = [{ name: 'Management Server', subgroup: [{ name: 'API' }, { name: 'Agent' }] }]

test('restores independent API filters, page and page size from the URL', () => {
  const query = { filter: 'agent', name: 'agent.lb', category: 'Developer', page: '2', pagesize: '50' }
  const state = readConfigurationQuery(query, groups, 20)
  expect(listConfigurationParams(state)).toEqual({
    listAll: true, keyword: 'agent', name: 'agent.lb', category: 'Developer', page: 2, pagesize: 50
  })
  expect(writeConfigurationQuery(state, 20)).toMatchObject(query)
})

test('keeps a classified hierarchy together and remembers the list page size', () => {
  const state = readConfigurationQuery({ group: 'Management Server', subgroup: 'Agent', pagesize: '50', page: '9' }, groups, 20)
  expect(listConfigurationParams(state)).toEqual({ listAll: true, group: 'Management Server', subgroup: 'Agent', pagesize: -1 })
  expect(writeConfigurationQuery(state, 20)).toMatchObject({ group: 'Management Server', subgroup: 'Agent', page: undefined, pagesize: '50' })
})

test('normalizes malformed page inputs and missing or invalid group context', () => {
  const state = readConfigurationQuery({ group: 'unknown', page: '3e0a', pagesize: '-1', name: ['a', 'b'] }, groups, 20)
  expect(listConfigurationParams(state)).toEqual({ listAll: true, page: 1, pagesize: 20 })
  expect(readConfigurationQuery({ group: 'Management Server', subgroup: 'wrong' }, groups, 20).subgroup).toBe('API')
})
