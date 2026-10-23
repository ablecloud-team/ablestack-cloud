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
import { reactive } from 'vue'
import AttachVolume from '@/views/storage/AttachVolume.vue'
import { vmDisplayName, vmIdentity, vmLabel, vmMatchesSearch } from '@/utils/volumeAttachVm'
import { getAPI, postAPI } from '@/api'
jest.mock('@/api', () => ({ getAPI: jest.fn(), postAPI: jest.fn() }))
const resource = { id: 'volume', type: 'DATADISK', state: 'Ready', zoneid: 'zone', account: 'owner', domainid: 'domain', hypervisor: 'KVM' }
const vm = { id: 'uuid-1', name: 'old-hostname', displayname: '변경된 표시이름', instancename: 'i-2-50-VM', state: 'Stopped', zoneid: 'zone', account: 'owner', domainid: 'domain', volumemutationblockedreason: '' }
const duplicate = { ...vm, id: 'uuid-2', name: 'other-hostname', instancename: 'i-2-51-VM' }
const body = rows => ({ listvirtualmachinesresponse: { virtualmachine: rows, count: rows.length } })
const deferred = () => { let complete; const promise = new Promise(resolve => { complete = resolve }); return { promise, resolve: complete } }
let store, notify, validate, mounts
function mount (volume = resource) {
  const w = shallowMount(AttachVolume, {
    props: { resource: { ...volume } },
    global: {
      directives: { focus: {}, 'ctrl-enter': {} },
      mocks: { $t: s => s, $store: store, $getApiParams: () => ({ deviceid: { description: 'device' } }), $notifyError: notify, $pollJob: jest.fn() },
      stubs: { 'a-spin': { template: '<div><slot/></div>' }, 'a-form': { template: '<form><slot/></form>', methods: { validate: () => validate(), scrollToField: jest.fn() } }, 'a-form-item': { template: '<div><slot/></div>' }, 'a-select': { template: '<div><slot/></div>' }, 'a-button': { template: '<button><slot/></button>' } }
    }
  }); mounts.push(w); return w
}
beforeEach(() => {
  store = { getters: reactive({ userInfo: { id: 'caller' }, project: null }) }; notify = jest.fn(); validate = jest.fn().mockResolvedValue(); mounts = []
  getAPI.mockReset(); postAPI.mockReset()
  getAPI.mockImplementation(async (cmd, args) => body(args.id ? [vm] : args.state === 'Stopped' ? [vm, duplicate] : []))
  postAPI.mockResolvedValue({ attachvolumeresponse: { jobid: 'job' } })
})
afterEach(() => mounts.forEach(w => w.unmount()))

test('display name takes priority; fallback and duplicate identity contain UUID', () => {
  expect(vmDisplayName(vm)).toBe('변경된 표시이름'); expect(vmDisplayName({ name: 'fallback' })).toBe('fallback'); expect(vmDisplayName({ id: 'id' })).toBe('id')
  expect(vmIdentity(vm)).toBe('old-hostname · i-2-50-VM · uuid-1'); expect(vmLabel(vm)).not.toBe(vmLabel(duplicate))
})
test.each([' 변경된 표시이름 ', 'OLD-HOSTNAME', 'I-2-50-vm', 'UUID-1'])('searches display/name/instance/UUID: %s', query => {
  expect(vmMatchesSearch(vm, query)).toBe(true); expect(vmMatchesSearch(vm, 'missing')).toBe(false); expect(vmMatchesSearch(null, query)).toBe(false)
})
test('fetches all state pages with immutable owner filters; publishes only after both finish', async () => {
  const running = deferred()
  getAPI.mockImplementation((cmd, args) => args.state === 'Running' ? running.promise : Promise.resolve(args.page === 1 ? { listvirtualmachinesresponse: { virtualmachine: [vm], count: 2 } } : body([duplicate])))
  const w = mount(); await flushPromises()
  expect(w.vm.loading).toBe(true); expect(w.vm.virtualmachines).toEqual([])
  expect(getAPI.mock.calls.filter(c => c[1].state === 'Stopped').map(c => c[1].page)).toEqual([1, 2])
  expect(getAPI.mock.calls.every(c => c[1].account === 'owner' && c[1].domainid === 'domain' && c[1].zoneid === 'zone' && c[1].hypervisor === 'KVM' && c[1].pagesize === 100 && c[2].preserveOnFailure)).toBe(true)
  running.resolve(body([])); await flushPromises(); expect(w.vm.virtualmachines.map(v => v.id)).toEqual(['uuid-1', 'uuid-2']); expect(w.vm.loading).toBe(false)
})
test('project scope omits account/domain, and None hypervisor is omitted', async () => {
  mount({ ...resource, projectid: 'project', hypervisor: 'None' }); await flushPromises()
  getAPI.mock.calls.forEach(c => { expect(c[1].projectid).toBe('project'); expect(c[1].account).toBeUndefined(); expect(c[1].domainid).toBeUndefined(); expect(c[1].hypervisor).toBeUndefined() })
})
test('failed later page preserves previous rows/selection, blocks submit, retry replaces atomically', async () => {
  const w = mount(); await flushPromises(); w.vm.form.virtualmachineid = vm.id
  getAPI.mockImplementation(async (cmd, args) => {
    if (args.page === 2) throw new Error('503')
    return args.state === 'Stopped' ? { listvirtualmachinesresponse: { virtualmachine: [vm], count: 2 } } : body([])
  })
  await w.vm.fetchData(); expect(w.vm.lookupError).toBe('503'); expect(w.vm.virtualmachines).toHaveLength(2); expect(w.vm.form.virtualmachineid).toBe(vm.id)
  w.vm.handleSubmit(); await flushPromises(); expect(postAPI).not.toHaveBeenCalled()
  getAPI.mockImplementation(async (cmd, args) => body(args.state === 'Stopped' ? [vm] : [])); await w.findAll('button').find(b => b.text() === 'label.retry').trigger('click'); await flushPromises()
  expect(w.vm.lookupError).toBe(''); expect(w.vm.virtualmachines).toHaveLength(1); expect(w.vm.canSubmit).toBe(true)
})
test('late owner response is ignored and selection is cleared when scope changes', async () => {
  const old = deferred(); getAPI.mockReturnValue(old.promise); const w = mount()
  getAPI.mockImplementation(async () => body([duplicate]))
  await w.setProps({ resource: { ...resource, account: 'new' } }); await flushPromises()
  old.resolve(body([vm])); await flushPromises(); expect(w.vm.virtualmachines.map(v => v.id)).toEqual(['uuid-2']); expect(w.vm.form.virtualmachineid).toBeUndefined()
})
test('new lookup supersedes prior lookup and removed selection is cleared', async () => {
  const w = mount(); await flushPromises(); w.vm.form.virtualmachineid = vm.id
  const old = deferred(); getAPI.mockReturnValueOnce(old.promise)
  const first = w.vm.fetchData(); getAPI.mockResolvedValue(body([duplicate])); await w.vm.fetchData()
  old.resolve(body([vm])); await first; expect(w.vm.virtualmachines.map(v => v.id)).toEqual(['uuid-2']); expect(w.vm.form.virtualmachineid).toBeUndefined()
})
test.each([[], [{ ...vm, state: 'Destroyed' }], [{ ...vm, account: 'other' }], [{ ...vm, volumemutationblockedreason: 'backup' }]].map(rows => [rows]))('fresh missing/ineligible VM blocks attach: %j', async rows => {
  const w = mount(); await flushPromises(); w.vm.form.virtualmachineid = vm.id; getAPI.mockResolvedValue(body(rows))
  w.vm.handleSubmit(); await flushPromises(); expect(postAPI).not.toHaveBeenCalled(); expect(notify).toHaveBeenCalled(); expect(w.vm.loading).toBe(false)
})
test('same-name selection submits exact UUID after fresh validation; locks duplicate validation clicks', async () => {
  const w = mount(); await flushPromises(); w.vm.form.virtualmachineid = duplicate.id
  const wait = deferred(); validate.mockReturnValue(wait.promise); getAPI.mockResolvedValue(body([duplicate]))
  w.vm.handleSubmit(); w.vm.handleSubmit(); expect(validate).toHaveBeenCalledTimes(1)
  wait.resolve(); await flushPromises()
  expect(getAPI.mock.calls.at(-1)[1]).toMatchObject({ id: duplicate.id, account: 'owner', domainid: 'domain' })
  expect(postAPI).toHaveBeenCalledWith('attachVolume', { id: 'volume', virtualmachineid: duplicate.id, deviceid: undefined })
})
test('project switch during preflight never posts old selection', async () => {
  const w = mount(); await flushPromises(); w.vm.form.virtualmachineid = vm.id
  const wait = deferred(); getAPI.mockReturnValueOnce(wait.promise); w.vm.handleSubmit(); await flushPromises()
  store.getters.project = { id: 'new-project' }; await flushPromises(); wait.resolve(body([vm])); await flushPromises(); expect(postAPI).not.toHaveBeenCalled()
})
test('missing backup metadata disables candidate; empty results block submit', async () => {
  getAPI.mockImplementation(async () => body([{ ...vm, volumemutationblockedreason: undefined }]))
  const w = mount(); await flushPromises(); w.vm.form.virtualmachineid = vm.id; expect(w.vm.canSubmit).toBe(false)
  getAPI.mockResolvedValue(body([])); await w.vm.fetchData(); expect(w.vm.canSubmit).toBe(false)
})
