/*
Licensed to the Apache Software Foundation (ASF) under one
or more contributor license agreements.  See the NOTICE file
distributed with this work for additional information
regarding copyright ownership.  The ASF licenses this file
to you under the Apache License, Version 2.0 (the
"License"); you may not use this file except in compliance
with the License.  You may obtain a copy of the License at

  http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing,
software distributed under the License is distributed on an
"AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
KIND, either express or implied.  See the License for the
specific language governing permissions and limitations
under the License.
*/

import { getAPI, postAPI } from '@/api'
import DrCheckpointManager from '@/components/dr/DrCheckpointManager.vue'
jest.mock('@/api', () => ({ getAPI: jest.fn(), postAPI: jest.fn() }))

describe('checkpoint cleanup selection', () => {
  test('only explicitly eligible, non-deleted sets can be selected', () => {
    const vm = { selected: [] }
    const selection = DrCheckpointManager.computed.rowSelection.call(vm)
    expect(selection.getCheckboxProps({ eligible: true, state: 'COMMITTED' }).disabled).toBe(false)
    expect(selection.getCheckboxProps({ eligible: false, state: 'COMMITTED' }).disabled).toBe(true)
    expect(selection.getCheckboxProps({ eligible: true, state: 'DELETED' }).disabled).toBe(true)
    expect(selection.getCheckboxProps({ state: 'COMMITTED' }).disabled).toBe(true)
  })
})

test('preview uses GET and parses the actual API response', async () => {
  getAPI.mockResolvedValue({ managedrcheckpointsresponse: { checkpointmanagement: { details: JSON.stringify({ sets: [{ sequence: 1 }], policy: { keepCount: 3, keepDays: 2 } }) } } })
  const vm = { planId: 'plan', keepCount: 5, keepDays: 0, selected: [], sets: [] }
  await DrCheckpointManager.methods.fetch.call(vm, false, true)
  expect(getAPI).toHaveBeenCalledWith('manageDrCheckpoints', { planuuid: 'plan', savepolicy: false })
  expect(vm.sets).toEqual([{ sequence: 1 }])
  expect(vm.keepCount).toBe(3)
  expect(vm.error).toBe('')
})

test('policy save uses POST and never sends a deletion selection', async () => {
  postAPI.mockResolvedValue({ managedrcheckpointsresponse: { checkpointmanagement: { details: '{"sets":[]}' } } })
  const vm = { planId: 'plan', keepCount: 4, keepDays: 7 }
  await DrCheckpointManager.methods.fetch.call(vm, true)
  expect(postAPI).toHaveBeenCalledWith('manageDrCheckpoints', { planuuid: 'plan', keepcount: 4, keepdays: 7, savepolicy: true })
})

test('inventory errors remain visible and do not enable deletion', async () => {
  getAPI.mockRejectedValue(new Error('target unavailable'))
  const vm = { planId: 'plan', keepCount: 5, keepDays: 0, selected: ['old'] }
  await DrCheckpointManager.methods.fetch.call(vm)
  expect(vm.error).toBe('target unavailable')
  expect(vm.selected).toEqual([])
})

test('Cloud command error response displays the actual reason', async () => {
  getAPI.mockRejectedValue({ response: { data: { managedrcheckpointsresponse: { errortext: 'DR_CHECKPOINT_IN_USE' } } } })
  const vm = { planId: 'plan', keepCount: 5, keepDays: 0 }
  await DrCheckpointManager.methods.fetch.call(vm)
  expect(vm.error).toBe('DR_CHECKPOINT_IN_USE')
})
