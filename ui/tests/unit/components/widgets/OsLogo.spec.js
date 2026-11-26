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

import OsLogo from '@/components/widgets/OsLogo.vue'
import { getAPI } from '@/api'

jest.mock('@/api', () => ({ getAPI: jest.fn() }))

const read = (store, id, useCache = true) => OsLogo.methods.fetchOsTypeName.call({ $store: store, useCache }, id)
const response = description => ({ listostypesresponse: { ostype: [{ description }] } })

beforeEach(() => { getAPI.mockReset() })

test('shares a single request between VM rows with the same OS type', async () => {
  const store = { state: { user: { discoveryGeneration: 1 } } }
  getAPI.mockResolvedValue(response('Ubuntu'))
  const results = await Promise.all([read(store, 'ubuntu'), read(store, 'ubuntu'), read(store, 'ubuntu')])
  expect(results).toEqual(['Ubuntu', 'Ubuntu', 'Ubuntu'])
  expect(getAPI).toHaveBeenCalledTimes(1)
  expect(getAPI).toHaveBeenCalledWith('listOsTypes', { id: 'ubuntu' })

  await expect(read(store, 'ubuntu')).resolves.toBe('Ubuntu')
  expect(getAPI).toHaveBeenCalledTimes(1)
})

test('a new login does not reuse the previous OS type lookup', async () => {
  const store = { state: { user: { discoveryGeneration: 1 } } }
  getAPI.mockResolvedValueOnce(response('Old name')).mockResolvedValueOnce(response('New name'))
  const old = read(store, 'custom')
  store.state.user.discoveryGeneration++
  const current = read(store, 'custom')
  await expect(old).resolves.toBe('Old name')
  await expect(current).resolves.toBe('New name')
  await expect(read(store, 'custom')).resolves.toBe('New name')
  expect(getAPI).toHaveBeenCalledTimes(2)
})

test('removes failed requests so the next row can retry', async () => {
  const store = { state: { user: { discoveryGeneration: 1 } } }
  getAPI.mockRejectedValueOnce(new Error('Unavailable')).mockResolvedValueOnce(response('Debian'))
  await expect(read(store, 'debian')).rejects.toThrow('Unavailable')
  await expect(read(store, 'debian')).resolves.toBe('Debian')
  expect(getAPI).toHaveBeenCalledTimes(2)
})
