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

import { shallowMount } from '@vue/test-utils'
import { getAPI } from '@/api'
import store from '@/store'
import AutoAlertBanner from '@/components/header/AutoAlertBanner.vue'

jest.mock('@/api', () => ({ getAPI: jest.fn() }))
jest.mock('@/store', () => ({ state: { user: { discoveryGeneration: 0 } }, getters: { apis: {}, userInfo: { id: 'user1' } } }))
const flush = async () => { for (let i = 0; i < 30; i++) await Promise.resolve() }
let wrapper
beforeEach(() => {
  jest.useFakeTimers()
  getAPI.mockReset()
  store.state.user.discoveryGeneration = 0
  store.getters.apis = {}
})
afterEach(() => {
  if (wrapper) wrapper.unmount()
  wrapper = null
  jest.clearAllTimers()
  jest.useRealTimers()
})

test('unauthorized banner does not request or poll Wall or inventory APIs', async () => {
  wrapper = shallowMount(AutoAlertBanner, { global: { mocks: { $t: key => key } } })
  await flush()
  jest.advanceTimersByTime(120000)
  await flush()
  expect(getAPI).not.toHaveBeenCalled()
})

test('authorized banner catches a 432 and remains mounted', async () => {
  store.getters.apis = { listWallAlertRules: {} }
  getAPI.mockRejectedValue(new Error('432 forbidden'))
  wrapper = shallowMount(AutoAlertBanner, { global: { mocks: { $t: key => key } } })
  await flush()
  expect(wrapper.exists()).toBe(true)
  expect(getAPI.mock.calls.map(call => call[0])).toEqual(['listWallAlertRules'])
})

test('unmount during a pending request cannot resurrect polling', async () => {
  store.getters.apis = { listWallAlertRules: {}, listHostsMetrics: {}, listVirtualMachines: {} }
  let finish
  getAPI.mockImplementation(() => new Promise(resolve => { finish = resolve }))
  wrapper = shallowMount(AutoAlertBanner, { global: { mocks: { $t: key => key } } })
  await flush()
  wrapper.unmount()
  wrapper = null
  store.state.user.discoveryGeneration++
  finish({ listwallalertrulesresponse: { rule: [] } })
  await flush()
  jest.advanceTimersByTime(120000)
  await flush()
  expect(getAPI).toHaveBeenCalledTimes(1)
})
