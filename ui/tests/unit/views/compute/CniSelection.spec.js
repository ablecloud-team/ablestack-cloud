// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements. See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership. The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License. You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied. See the License for the
// specific language governing permissions and limitations
// under the License.

import { shallowMount } from '@vue/test-utils'
import { nextTick, reactive } from 'vue'
import Create from '@/views/compute/CreateKubernetesCluster.vue'
import Picker from '@/views/compute/wizard/UserDataSelection.vue'
import { getAPI, postAPI } from '@/api'
jest.mock('@/api', () => ({ getAPI: jest.fn(), postAPI: jest.fn() }))
jest.mock('@/store', () => ({ getters: { apis: {}, userInfo: {}, project: {} } }))
const flush = async () => { for (let i = 0; i < 12; i++) { await Promise.resolve(); await nextTick() } }
const response = (rows = [], count = rows.length) => ({ listcniconfigurationresponse: { count, cniconfig: rows } })
const first = { id: 'cni-a', name: 'CNI A', account: 'owner', domainid: 'domain', params: 'A,B' }
const second = { id: 'cni-b', name: 'CNI B', account: 'owner', domainid: 'domain' }
const table = { name: 'ATable', props: ['pagination', 'dataSource', 'rowSelection'], template: '<div />' }
const form = { template: '<form><slot /></form>', methods: { validate: () => Promise.resolve(), validateFields: () => Promise.resolve(), scrollToField: () => {} } }
let wrappers
function mount (role = 'Admin', project = {}) {
  const getters = reactive({ apis: { listCniConfiguration: {} }, capabilities: {}, features: {}, userInfo: { roletype: role, account: 'owner', domainid: 'domain' }, project })
  const w = shallowMount({
    ...Create,
    created () { this.emptyEntry = { id: null, name: '' }; this.initForm() }
  }, {
    attachTo: document.body,
    data: () => ({ owner: { account: 'owner', domainid: 'domain' }, wizardStep: 3, selectedZone: { id: 'zone' } }),
    global: {
      renderStubDefaultSlot: true,
      mocks: { $store: { getters }, $t: key => key, $getApiParams: () => new Proxy({}, { get: () => ({ description: 'parameter' }) }), $notifyError: jest.fn(), $pollJob: jest.fn() },
      stubs: { UserDataSelection: false, CniVariableTools: { props: ['resource', 'values', 'scope', 'networkId', 'asNumber', 'disabled'], template: '<div />' }, 'a-table': table, 'a-form': form }
    }
  })
  w.vm.form.advancedmode = true
  wrappers.push(w)
  return { w, getters, picker: () => w.findComponent(Picker) }
}
beforeEach(() => { wrappers = []; jest.clearAllMocks(); getAPI.mockResolvedValue(response([first, second])); postAPI.mockResolvedValue({ createkubernetesclusterresponse: { jobid: 'job' } }) })
afterEach(() => wrappers.forEach(w => w.unmount()))

test.each(['Admin', 'DomainAdmin', 'User'])('real Kubernetes consumer uses CNI API and permission for %s without listUserData', async role => {
  const { w, picker } = mount(role); await flush()
  expect(picker().vm.ready).toBe(true)
  expect(getAPI.mock.calls.every(([command, args]) => command === 'listCniConfiguration' && args.account === 'owner' && args.domainid === 'domain' && args.ignoreproject === true)).toBe(true)
  picker().vm.selectItem(first); await flush()
  expect(w.vm.form.cniconfigurationid).toBe(first.id)
  expect(w.vm.cniConfigParams.map(p => p.key)).toEqual(['A', 'B'])
  w.vm.cniConfigValues.A = 'old'; picker().vm.selectItem(second); await flush()
  expect(w.vm.cniConfigParams).toEqual([]); expect(Object.keys(w.vm.cniConfigValues)).toEqual([])
  picker().vm.selectItem(); await flush()
  expect(w.vm.form.cniconfigurationid).toBeUndefined()
})

test('project scope is explicit and cannot inherit the global project in account mode', async () => {
  const { w, picker } = mount(); w.vm.owner = { projectid: 'project' }; await flush()
  expect(getAPI.mock.calls.at(-1)[1]).toMatchObject({ projectid: 'project', ignoreproject: true })
  expect(getAPI.mock.calls.at(-1)[1].account).toBeUndefined()
  picker().vm.selectItem(first); await flush(); w.vm.cniConfigValues.A = 'old'
  w.vm.owner = { account: 'other', domainid: 'domain' }; await flush()
  expect(w.vm.form.cniconfigurationid).toBeUndefined(); expect(Object.keys(w.vm.cniConfigValues)).toEqual([])
})

test('non-admin project changes and advanced mode reset all consumer state', async () => {
  const { w, getters, picker } = mount('User', { id: 'p1' }); await flush()
  expect(picker().vm.owner).toEqual({ projectid: 'p1' })
  picker().vm.selectItem(first); await flush(); getters.project = { id: 'p2' }; await flush()
  expect(w.vm.form.cniconfigurationid).toBeUndefined()
  expect(getAPI.mock.calls.at(-1)[1].projectid).toBe('p2')
  picker().vm.selectItem(first); await flush(); w.vm.form.advancedmode = false; await flush()
  expect(w.vm.form.cniconfigurationid).toBeUndefined(); expect(w.vm.selectedCni).toBeNull()
})

test('late owner response cannot overwrite a newer owner, and pages use CNI response keys', async () => {
  let old
  getAPI.mockImplementationOnce(() => new Promise(resolve => { old = resolve })).mockResolvedValue(response([second], 11))
  const { w, picker } = mount(); await flush()
  w.vm.owner = { account: 'other', domainid: 'domain' }; await flush()
  old(response([first])); await flush()
  expect(picker().vm.items).toEqual([second])
  picker().vm.handleTableChange({ current: 2, pageSize: 10 }); await flush()
  expect(getAPI.mock.calls.at(-1)[1]).toMatchObject({ page: 2, pagesize: 10, account: 'other' })
})

test('failure and missing selected row block submission until retry; empty success supports no custom CNI', async () => {
  const { w, picker } = mount(); await flush(); picker().vm.selectItem(first); await flush()
  getAPI.mockRejectedValueOnce(new Error('network')); await picker().vm.load(picker().vm.query); await flush()
  expect(w.vm.cniSelectionBlocked).toBe(true)
  await Create.methods.handleSubmit.call({ wizardStep: 4, cniSelectionBlocked: w.vm.cniSelectionBlocked }); expect(postAPI).not.toHaveBeenCalled()
  getAPI.mockResolvedValue(response([first])); await picker().vm.load(picker().vm.query); await flush()
  expect(w.vm.cniSelectionBlocked).toBe(false)
  getAPI.mockResolvedValue(response()); await picker().vm.load(picker().vm.query); await flush()
  expect(w.vm.cniSelectionBlocked).toBe(true)
  picker().vm.selectItem(); await flush()
  expect(w.vm.cniSelectionBlocked).toBe(false)
})

test('CNI-only permission is required; revocation invalidates a selected configuration', async () => {
  const { w, getters, picker } = mount(); await flush(); picker().vm.selectItem(first); await flush()
  getters.apis = { listUserData: {} }; await flush()
  expect(picker().vm.allowed).toBe(false); expect(w.vm.form.cniconfigurationid).toBeUndefined()
  const calls = getAPI.mock.calls.length
  await picker().vm.load(picker().vm.query); expect(getAPI).toHaveBeenCalledTimes(calls)
})

function submission (w) {
  for (const { key } of w.vm.cniConfigParams) if (key !== 'AS_NUMBER' && w.vm.cniConfigValues[key] === undefined) w.vm.cniConfigValues[key] = 'test-' + key
  // Keep the consumer selection/ownership state; submit independently of the
  // Ant Design form/render lifecycle, which is stubbed in these unit tests.
  return {
    ...Create.methods,
    get cniSelectionBlocked () { return w.vm.cniSelectionBlocked },
    get cniScopeKey () { return w.vm.cniScopeKey },
    get form () { return { ...w.vm.form, name: 'test', zoneid: 0, kubernetesversionid: 0, serviceofferingid: 0, hypervisor: null } },
    get owner () { return w.vm.owner },
    get cniConfigParams () { return w.vm.cniConfigParams },
    get cniMissingValues () { return w.vm.cniMissingValues },
    get cniConfigValues () { return w.vm.cniConfigValues },
    wizardStep: 4,
    zones: [{ id: 'zone' }],
    kubernetesVersions: [{ id: 'version' }],
    serviceOfferings: [{ id: 'offering' }],
    formRef: { value: { validate: () => Promise.resolve() } },
    handleRemoveFields: values => ({ ...values }),
    $notifyError: jest.fn(),
    $pollJob: jest.fn(),
    $t: key => key,
    $emit: jest.fn()
  }
}
test('create request contains only selected CNI and declared values, and no CNI fields after clearing', async () => {
  const { w, picker } = mount(); await flush(); picker().vm.selectItem(first); await flush()
  const vm = submission(w); Object.assign(w.vm.cniConfigValues, { A: 'current', leaked: 'old' })
  await vm.handleSubmit()
  expect(postAPI).toHaveBeenLastCalledWith('createKubernetesCluster', expect.objectContaining({ cniconfigurationid: first.id, 'cniconfigdetails[0].A': 'current' }))
  expect(JSON.stringify(postAPI.mock.calls.at(-1)[1])).not.toContain('leaked')
  picker().vm.selectItem(); await flush(); await vm.handleSubmit()
  expect(postAPI.mock.calls.at(-1)[1].cniconfigurationid).toBeUndefined()
  expect(Object.keys(postAPI.mock.calls.at(-1)[1]).some(key => key.startsWith('cniconfigdetails'))).toBe(false)
})

test('owner change while async form validation is pending cannot submit a stale CNI', async () => {
  const { w, picker } = mount(); await flush(); picker().vm.selectItem(first); await flush(); const vm = submission(w)
  let finish
  vm.formRef.value.validate = () => new Promise(resolve => { finish = resolve })
  const submitting = vm.handleSubmit()
  w.vm.owner = { account: 'new', domainid: 'domain' }; await flush(); finish(); await submitting
  expect(postAPI).not.toHaveBeenCalled(); expect(vm.loading).toBe(false)
})
