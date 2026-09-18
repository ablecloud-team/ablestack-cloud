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

import { shallowMount } from '@vue/test-utils'
import VmNicsTab from '@/views/compute/VmNicsTab.vue'
import { getAPI } from '@/api'
jest.mock('@/api', () => ({ getAPI: jest.fn(), postAPI: jest.fn() }))
jest.mock('@/views/network/CreateNetwork.vue', () => ({ render: () => null }))
const vm = { id: 'vm', zoneid: 'zone', account: 'admin', domainid: 'domain', state: 'Running', hypervisor: 'KVM' }
const nic = { id: 'nic', networkid: 'l2', type: 'L2', isdefault: true, enabled: true, linkstate: true }
const flush = async () => { for (let i = 0; i < 40; i++) await Promise.resolve() }
const responses = (api, params) => {
  if (api === 'listVirtualMachines') return { listvirtualmachinesresponse: { virtualmachine: [{ ...vm, id: params.id, nic: [nic] }] } }
  if (api === 'listNics') return { listnicsresponse: { nic: [{ ...nic, linkstate: false }] } }
  if (api === 'listZones') return { listzonesresponse: { zone: [{ networktype: 'Advanced' }] } }
  if (api === 'listVMSnapshot') {
    if (params.pagesize && !params.page) throw new Error('page is required with pagesize')
    return { listvmsnapshotresponse: {} }
  }
  if (api === 'listNetworks') return { listnetworksresponse: { network: [{ id: 'l2', type: 'L2', state: 'Setup' }] } }
  throw new Error('Unexpected API: ' + api)
}
function mount () {
  return shallowMount(VmNicsTab, {
    props: { resource: vm },
    global: { mocks: { $store: { getters: { apis: { listZones: {}, listVMSnapshot: {}, createNetwork: {}, addNicToVirtualMachine: {} }, project: {}, userInfo: { id: 'admin' } }, state: { user: { token: 'token' } } }, $route: { path: '/vm/vm', fullPath: '/vm/vm?tab=nics' }, $t: x => x, $te: () => true } }
  })
}
beforeEach(() => { jest.useFakeTimers(); jest.clearAllMocks(); getAPI.mockImplementation(async (api, params) => responses(api, params)) })
afterEach(() => jest.useRealTimers())
test('initial load completes and snapshot API uses the existing singular command', async () => {
  const wrapper = mount(); await flush()
  expect(wrapper.vm.loading).toBe(false)
  expect(wrapper.vm.rows).toHaveLength(1)
  expect(wrapper.vm.rows[0].linkstate).toBe(true)
  expect(wrapper.vm.listLastUpdated).not.toBeNull()
  expect(wrapper.vm.reason('addNicToVirtualMachine')).toBe('')
  expect(getAPI).toHaveBeenCalledWith('listVMSnapshot', expect.objectContaining({ virtualmachineid: 'vm' }))
  wrapper.unmount()
})
test('failed refresh preserves data and blocks writes; recovery clears the warning', async () => {
  const wrapper = mount(); await flush()
  getAPI.mockRejectedValue(new Error('offline')); await wrapper.vm.fetchData(); await flush()
  expect(wrapper.vm.rows).toHaveLength(1)
  expect(wrapper.vm.reason('addNicToVirtualMachine')).toBe('message.list.refresh.stale')
  getAPI.mockImplementation(async (api, params) => responses(api, params)); await wrapper.vm.fetchData(); await flush()
  expect(wrapper.vm.listRefreshFailed).toBe(false)
  wrapper.unmount()
})
test('candidate pagination keeps VM ownership and excludes attached networks', async () => {
  const wrapper = mount(); await flush()
  getAPI.mockImplementation(async (api, params) => params.canusefordeploy ? { listnetworksresponse: { count: 3, network: params.page === 1 ? [{ id: 'l2', state: 'Setup' }, { id: 'first', state: 'Allocated' }] : [{ id: 'second', state: 'Implemented' }] } } : responses(api, params))
  await wrapper.vm.openAttach()
  expect(wrapper.vm.candidates.map(n => n.id)).toEqual(['first', 'second'])
  expect(getAPI).toHaveBeenCalledWith('listNetworks', expect.objectContaining({ page: 2, account: 'admin', domainid: 'domain', zoneid: 'zone', canusefordeploy: true }))
  wrapper.unmount()
})
test('refresh leaves the open creation form resource stable and VM switch closes it', async () => {
  const wrapper = mount(); await flush(); wrapper.vm.openCreate()
  const original = wrapper.vm.formVm
  await wrapper.vm.fetchData(); await flush()
  expect(wrapper.vm.formVm).toBe(original)
  await wrapper.setProps({ resource: { ...vm, id: 'next' } }); await flush()
  expect(wrapper.vm.form).toBe(''); expect(wrapper.vm.loading).toBe(false)
  wrapper.unmount()
})

test('flat locale message keys are translated even when the existence lookup cannot resolve them', () => {
  const { createI18n } = require('vue-i18n')
  const i18n = createI18n({ locale: 'ko', messages: { ko: { 'message.vmnic.reconcile': 'NIC state has not been reflected yet' } } })
  const context = { $t: i18n.global.t.bind(i18n.global) }
  expect(VmNicsTab.methods.translateError.call(context, 'message.vmnic.reconcile')).toBe('NIC state has not been reflected yet')
  expect(VmNicsTab.methods.translateError.call(context, 'Server rejected request')).toBe('Server rejected request')
})
