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

import { api, getAPI, postAPI } from '@/api'
import { axios } from '@/utils/request'

jest.mock('@/utils/request', () => ({ axios: jest.fn(() => Promise.resolve({})), sourceToken: {} }))
jest.mock('@/vue-app', () => ({ vueProps: { $localStorage: { get: () => 'session' } } }))

beforeEach(() => jest.clearAllMocks())

test('optional metadata is local and its timeout is bounded', async () => {
  await getAPI('listConfigurations', { name: 'favicon.state.interval' }, { optionalDiscovery: true })
  const config = axios.mock.calls[0][0]
  expect(config.optionalDiscovery).toBe(true)
  expect(config.timeout).toBe(15000)
  expect(config.params).toEqual({ command: 'listConfigurations', response: 'json', sessionkey: 'session', name: 'favicon.state.interval' })
})

test('ordinary API requests retain their existing request configuration', async () => {
  await getAPI('listVirtualMachines')
  const config = axios.mock.calls[0][0]
  expect(config.optionalDiscovery).toBeUndefined()
  expect(config.timeout).toBeUndefined()
})

test('Diplo four-argument API retains method, query and form body', async () => {
  await api('updateVirtualMachine', { id: 'vm1' }, 'POST', { displayname: 'new name' })
  const config = axios.mock.calls[0][0]
  expect(config.method).toBe('POST')
  expect(config.params.id).toBe('vm1')
  expect(config.data.get('displayname')).toBe('new name')
  expect(config.optionalDiscovery).toBeUndefined()
  expect(config.timeout).toBeUndefined()
})

test('postAPI preserves the existing Diplo query/form contract', async () => {
  await postAPI('updateVirtualMachine', { id: 'vm1' })
  const config = axios.mock.calls[0][0]
  expect(config.method).toBe('POST')
  expect(config.params.command).toBe('updateVirtualMachine')
  expect(config.data.get('id')).toBe('vm1')
})
