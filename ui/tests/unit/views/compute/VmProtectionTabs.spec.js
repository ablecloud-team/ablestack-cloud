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

import { shallowMount, flushPromises } from '@vue/test-utils'
import tabs, { isFtMachineCompatible } from '@/utils/vmProtectionTabs'
import { getAPI } from '@/api'
import { getDrVmProtectionView } from '@/api/dr'
jest.mock('@/api', () => ({ getAPI: jest.fn() }))
jest.mock('@/api/dr', () => ({ getDrVmProtectionView: jest.fn() }))
const empty = id => ({ virtualmachineid: id, configured: false, association: [] })
const related = (id, role = 'SOURCE') => ({ virtualmachineid: id, configured: true, association: [{ planid: 'plan', relationshiprole: role }] })
const machine = value => ({ 'kvm.guest.os.machine.type': value })
const deferred = () => { const d = {}; d.promise = new Promise((resolve, reject) => { d.resolve = resolve; d.reject = reject }); return d }
function mount (resource = { id: 'a', details: machine('q35') }, tab = 'details', apis = { getFtctlProtection: {}, getDrVmProtectionView: {} }) {
  window.history.replaceState({}, '', '/#/vm/' + resource.id + '?tab=' + tab + '&keep=1')
  return shallowMount({
    mixins: [tabs],
    props: ['resource'],
    data: () => ({ currentTab: tab }),
    methods: { resolveCurrentTabFromRoute () { return this.currentTab } },
    template: '<div><span v-if="showFtTab">FT</span><span v-if="showDrTab">DR</span><p>{{ visibleCurrentTab }}</p></div>'
  }, { props: { resource }, global: { mocks: { $route: { path: '/vm/' + resource.id, query: { tab } }, $router: { replace: jest.fn() }, $store: { getters: { apis } } } } })
}
beforeEach(() => {
  jest.clearAllMocks()
  getDrVmProtectionView.mockImplementation(id => Promise.resolve(empty(id)))
  getAPI.mockImplementation((name, p) => Promise.resolve({ listvirtualmachinesresponse: { virtualmachine: [{ id: p.id, details: {} }] } }))
})
test.each(['q35', 'pc-q35-9.2', 'pc', '', undefined, 'virt'])('unsupported type %s never displays FT', async value => {
  const w = mount({ id: 'a', details: machine(value) }, 'ftctl')
  await flushPromises()
  expect(w.vm.showFtTab).toBe(false)
  expect(w.vm.currentTab).toBe('details')
  expect(w.vm.$router.replace).toHaveBeenCalledWith({ path: '/vm/a', query: { tab: 'details', keep: '1' } })
  w.unmount()
})
test('machine normalization matches server, and q35 prefix is not accepted', () => {
  expect(isFtMachineCompatible(machine(' PC-i440FX-9.2 '))).toBe(true)
  expect(isFtMachineCompatible(machine('pc-q35-9.2'))).toBe(false)
})
test('compatible machine can display FT independently of an empty DR relation', async () => {
  const w = mount({ id: 'a', details: machine('pc-i440fx-9.2') }, 'ftctl')
  await flushPromises()
  expect(w.vm.showFtTab).toBe(true)
  expect(w.vm.showDrTab).toBe(false)
  expect(getAPI).not.toHaveBeenCalled()
  w.unmount()
})
test.each(['SOURCE', 'RECOVERY_TARGET', 'TEST_TARGET'])('q35 with %s DR relationship displays only DR', async role => {
  getDrVmProtectionView.mockResolvedValue(related('a', role))
  const w = mount(undefined, 'drplans')
  await flushPromises()
  expect(w.vm.showFtTab).toBe(false)
  expect(w.vm.showDrTab).toBe(true)
  expect(w.vm.visibleCurrentTab).toBe('drplans')
  expect(getDrVmProtectionView).toHaveBeenCalledTimes(1)
  w.unmount()
})
test('missing VM details are queried, without mounting FT or rewriting pending URL', async () => {
  const d = deferred(); getAPI.mockReturnValue(d.promise)
  const w = mount({ id: 'a' }, 'ftctl')
  expect(w.vm.showFtTab).toBe(false)
  expect(w.vm.visibleCurrentTab).toBe('details')
  expect(w.vm.$router.replace).not.toHaveBeenCalled()
  d.resolve({ listvirtualmachinesresponse: { virtualmachine: [{ id: 'a', details: machine('pc-i440fx-9.2') }] } })
  await flushPromises()
  expect(w.vm.visibleCurrentTab).toBe('ftctl')
  w.unmount()
})
test('lookup failure is not an empty relationship and preserves a confirmed same-VM view', async () => {
  getDrVmProtectionView.mockResolvedValue(related('a'))
  const w = mount(undefined, 'drplans'); await flushPromises()
  getDrVmProtectionView.mockRejectedValue(new Error('offline'))
  await w.vm.refreshProtectionTabs()
  expect(w.vm.showDrTab).toBe(true)
  expect(w.vm.drEligibility).toBe('error')
  expect(w.vm.$router.replace).not.toHaveBeenCalled()
  w.unmount()
})
test('initial failure preserves URL, retry to confirmed empty normalizes it', async () => {
  getDrVmProtectionView.mockRejectedValue(new Error('offline'))
  const w = mount(undefined, 'drplans'); await flushPromises()
  expect(w.vm.showDrTab).toBe(false)
  expect(w.vm.protectionLookupFailed).toBe(true)
  expect(w.vm.$router.replace).not.toHaveBeenCalled()
  getDrVmProtectionView.mockResolvedValue(empty('a'))
  await w.vm.refreshProtectionTabs()
  expect(w.vm.currentTab).toBe('details')
  w.unmount()
})
test('VM switch discards stale relationship and stale machine responses', async () => {
  const ft = deferred(); const dr = deferred()
  getAPI.mockReturnValueOnce(ft.promise); getDrVmProtectionView.mockReturnValueOnce(dr.promise)
  const w = mount({ id: 'a' }); await w.setProps({ resource: { id: 'b', details: machine('q35') } }); await flushPromises()
  ft.resolve({ listvirtualmachinesresponse: { virtualmachine: [{ id: 'a', details: machine('pc-i440fx-9.2') }] } })
  dr.resolve(related('a')); await flushPromises()
  expect(w.vm.protectionVmId).toBe('b')
  expect(w.vm.showFtTab).toBe(false)
  expect(w.vm.showDrTab).toBe(false)
  expect(w.vm.drView.virtualmachineid).toBe('b')
  w.unmount()
})
test('permission denial issues no requests and normalizes direct access', async () => {
  const w = mount(undefined, 'drplans', {})
  await flushPromises()
  expect(getAPI).not.toHaveBeenCalled()
  expect(getDrVmProtectionView).not.toHaveBeenCalled()
  expect(w.vm.currentTab).toBe('details')
  w.unmount()
})
test('wrong VM and malformed responses are errors, not authoritative absence', async () => {
  getDrVmProtectionView.mockResolvedValue(related('other'))
  const w = mount(undefined, 'drplans'); await flushPromises()
  expect(w.vm.drEligibility).toBe('error')
  expect(w.vm.$router.replace).not.toHaveBeenCalled()
  getDrVmProtectionView.mockResolvedValue({ virtualmachineid: 'a', configured: true })
  await w.vm.refreshProtectionTabs()
  expect(w.vm.drEligibility).toBe('error')
  w.unmount()
})
test('conflicting relationships survive a stale projection', async () => {
  const view = related('a')
  view.relationconflict = true; view.projectionstate = 'UNKNOWN'
  view.association.push({ planid: 'p2', relationshiprole: 'RECOVERY_TARGET' })
  getDrVmProtectionView.mockResolvedValue(view)
  const w = mount(); await flushPromises()
  expect(w.vm.showDrTab).toBe(true)
  expect(w.vm.drView.association).toHaveLength(2)
  w.unmount()
})
test('unmounted and superseded requests cannot apply their results', async () => {
  const old = deferred(); getDrVmProtectionView.mockReturnValueOnce(old.promise)
  const w = mount(); await w.vm.refreshProtectionTabs()
  old.resolve(related('a')); await flushPromises()
  expect(w.vm.showDrTab).toBe(false)
  const pending = deferred(); getDrVmProtectionView.mockReturnValueOnce(pending.promise)
  const refresh = w.vm.refreshProtectionTabs(); w.unmount()
  pending.resolve(related('a')); await refresh
  expect(w.vm.drView.configured).toBe(false)
})

