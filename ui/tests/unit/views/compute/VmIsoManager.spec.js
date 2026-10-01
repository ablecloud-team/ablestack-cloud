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
import VmIsoTab from '@/views/compute/VmIsoTab.vue'
import VmIsoManager from '@/views/compute/VmIsoManager.vue'
import { getAPI } from '@/api'
jest.mock('@/api', () => ({ getAPI: jest.fn(), postAPI: jest.fn() }))
beforeEach(() => getAPI.mockReset())

it('reopens active operations rather than submitting another action', async () => {
  const ctx = { busy: true, currentAction: { api: 'attachIso' }, openAttach: jest.fn() }
  await VmIsoManager.methods.openAction.call(ctx)
  expect(ctx.form).toBe('progress')
  expect(ctx.openAttach).not.toHaveBeenCalled()
})
it('starts menu detach without implicitly selecting connected media', async () => {
  const ctx = { currentAction: { api: 'detachIso' }, openDetach: jest.fn(), fetchData: jest.fn() }
  await VmIsoManager.methods.openAction.call(ctx)
  expect(ctx.openDetach).toHaveBeenCalledWith([])
  expect(ctx.fetchData).toHaveBeenCalled()
})
it('shares explicit tab selection and excludes media detached during refresh', () => {
  const ctx = { rows: [{ id: 'a' }, { id: 'b' }] }
  VmIsoManager.methods.openDetach.call(ctx, [{ id: 'b' }, { id: 'removed' }])
  expect(ctx.form).toBe('detach')
  expect(VmIsoManager.computed.targets.call(ctx)).toEqual([{ id: 'b' }])
})
it('closes the menu owner without cancelling background operations', () => {
  const ctx = { dialogOnly: true, $emit: jest.fn() }
  VmIsoManager.methods.closeDialog.call(ctx)
  expect(ctx.$emit).toHaveBeenCalledWith('close-action')
})
it('paginates candidates and refreshes VM capacity before presenting selection', async () => {
  const ctx = {
    scopeKey: 'scope',
    vm: { zoneid: 'zone' },
    rows: [{ id: 'attached' }],
    candidateRequest: 0,
    $nextTick: async () => {},
    fetchData: jest.fn(),
    $t: k => k
  }
  getAPI.mockResolvedValueOnce({ listisosresponse: { iso: [{ id: 'a' }], count: 2 } })
    .mockResolvedValueOnce({ listisosresponse: { iso: [{ id: 'b' }], count: 2 } })
    .mockResolvedValueOnce({ listisosresponse: { iso: [{ id: 'a' }, { id: 'attached' }], count: 2 } })
    .mockResolvedValueOnce({ listisosresponse: {} })
  await VmIsoManager.methods.openAttach.call(ctx)
  expect(ctx.fetchData).toHaveBeenCalled()
  expect(getAPI.mock.calls[1][1].page).toBe(2)
  expect(ctx.candidates.map(i => i.id)).toEqual(['a', 'b'])
})

it('renders the same single modal with a toolbar only in the tab entrypoint', () => {
  const options = {
    props: { resource: { id: 'vm', state: 'Running', hypervisor: 'KVM' } },
    global: { mocks: { $t: k => k, $store: { getters: { apis: {} }, state: { user: { token: 'token' } } }, $route: { path: '/vm/vm' } } }
  }
  const tab = shallowMount(VmIsoTab, options)
  const menu = shallowMount(VmIsoManager, { ...options, props: { ...options.props, dialogOnly: true, currentAction: { api: 'detachIso' } } })
  expect(tab.find('.iso-toolbar').exists()).toBe(true)
  expect(menu.find('.iso-toolbar').exists()).toBe(false)
  expect(tab.findAll('a-modal-stub').length).toBe(1)
  expect(menu.findAll('a-modal-stub').length).toBe(1)
  tab.unmount()
  menu.unmount()
})
