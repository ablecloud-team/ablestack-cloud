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

import VmIsoTab from '@/views/compute/VmIsoTab.vue'
import { getAPI } from '@/api'
jest.mock('@/api', () => ({ getAPI: jest.fn(), postAPI: jest.fn() }))
const vm = { id: 'vm', zoneid: 'zone', isos: [{ id: 'iso', deviceseq: 4, bootable: false }] }
function context () {
  return { resource: { id: 'vm' }, vm: {}, selected: ['iso', 'removed'], mediaById: {}, listRequestToken: () => ({}), isListRequestCurrent: () => true }
}
beforeEach(() => getAPI.mockReset())
it('reads actual secondary media capability from listIsos and prunes detached selections', async () => {
  getAPI.mockResolvedValueOnce({ listvirtualmachinesresponse: { virtualmachine: [vm] } }).mockResolvedValueOnce({ listisosresponse: { iso: [{ id: 'iso', bootable: true }] } })
  const ctx = context(); await VmIsoTab.methods.fetchData.call(ctx)
  expect(VmIsoTab.computed.rows.call(ctx)[0].bootable).toBe(true)
  expect(ctx.selected).toEqual(['iso'])
  expect(ctx.loading).toBe(false)
})
it('does not mislabel media when ISO metadata is inaccessible', async () => {
  getAPI.mockResolvedValueOnce({ listvirtualmachinesresponse: { virtualmachine: [vm] } }).mockRejectedValueOnce(new Error('permission'))
  const ctx = context(); await VmIsoTab.methods.fetchData.call(ctx)
  expect(VmIsoTab.computed.rows.call(ctx)[0].bootable).toBeUndefined()
  expect(ctx.listRefreshFailed).toBeUndefined()
})
it('ignores delayed data after the VM or security context changed', async () => {
  getAPI.mockResolvedValueOnce({ listvirtualmachinesresponse: { virtualmachine: [vm] } })
  const ctx = context(); ctx.isListRequestCurrent = () => false
  await VmIsoTab.methods.fetchData.call(ctx)
  expect(ctx.vm).toEqual({})
  expect(getAPI).toHaveBeenCalledTimes(1)
})
it('keeps a refresh failure distinct from an empty attachment list', async () => {
  getAPI.mockRejectedValue(new Error('network'))
  const ctx = context(); ctx.vm = vm
  await VmIsoTab.methods.fetchData.call(ctx)
  expect(ctx.vm).toBe(vm)
  expect(ctx.listRefreshFailed).toBe(true)
})
