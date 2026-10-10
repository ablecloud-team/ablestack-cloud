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

import { getAPI, postAPI } from '@/api'
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

test('snapshot reads preserve the session on transient failure without sending the flag', async () => {
  await getAPI('listVMSnapshot', { page: 1 })
  const config = axios.mock.calls[0][0]
  expect(config.preserveOnFailure).toBe(true)
  expect(config.params.preserveOnFailure).toBeUndefined()
})

test.each(['volumeid', 'snapshotid'])('source creation retains session ownership on a lost %s submission response', async sourceKey => {
  await postAPI('deployVirtualMachine', { [sourceKey]: 'source', name: 'test', serviceofferingid: 'offering' })
  const config = axios.mock.calls[0][0]
  expect(config.preserveOnFailure).toBe(true)
  expect(config.data.get(sourceKey)).toBe('source')
  expect(config.data.has('preserveOnFailure')).toBe(false)
})

test('legacy volume submission also preserves its uncertain outcome while template deployment is unchanged', async () => {
  await postAPI('deployVirtualMachineForVolume', { volumeid: 'volume' })
  expect(axios.mock.calls[0][0].preserveOnFailure).toBe(true)
  await postAPI('deployVirtualMachine', { templateid: 'template' })
  expect(axios.mock.calls[1][0].preserveOnFailure).toBeUndefined()
})

test('an explicit start-retry transport option is local and does not weaken ordinary requests', async () => {
  await postAPI('startVirtualMachine', { id: 'vm' }, { preserveOnFailure: true })
  expect(axios.mock.calls[0][0].preserveOnFailure).toBe(true)
  expect(axios.mock.calls[0][0].data.has('preserveOnFailure')).toBe(false)
  await postAPI('startVirtualMachine', { id: 'vm' })
  expect(axios.mock.calls[1][0].preserveOnFailure).toBeUndefined()
})
