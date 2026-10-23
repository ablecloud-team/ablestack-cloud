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
import { shallowMount, flushPromises } from '@vue/test-utils'
import Compute from '@/views/offering/UpdateComputeOffering.vue'
import Access from '@/views/offering/UpdateStorageOfferingAccess.vue'
import Assign from '@/views/storage/AssignVolume.vue'
import Clone from '@/views/offering/CloneComputeOffering.vue'
import { getAPI, postAPI } from '@/api'
import { computeEditParams, accessParams, assignmentParams, assignmentReason, canManageStorageOffering } from '@/utils/storageManagement'
import { readAllocationList } from '@/utils/volumeAllocation'
jest.mock('@/api', () => ({ getAPI: jest.fn(), postAPI: jest.fn() }))
const deferred = () => { let release; const promise = new Promise(resolve => { release = resolve }); return { promise, resolve: release } }
const compute = { id: 'o', name: 'Original', displaytext: 'Keep', state: 'Inactive', domainid: 'd1', zoneid: 'z1', purgeresources: true, diskofferingcomputeonly: true, serviceofferingdetails: { 'External:note': 'keep', 'purge.db.entities': 'true', 'allocation.policy': 'metadata' } }
const volume = { id: 'v', name: 'Volume', type: 'DATADISK', state: 'Ready', account: 'old', domainid: 'd1', size: 1073741824, path: 'keep', storageid: 'pool', volumemutationblockedreason: '' }
const owner = { id: 'account', name: 'New owner', domainid: 'd1', state: 'enabled', accounttype: 0 }
const project = { id: 'project', name: 'Project', domainid: 'd1', state: 'Active' }
const fields = ['name', 'displaytext', 'hosttags', 'storagetags', 'purgeresources', 'externaldetails', 'cleanupexternaldetails', 'domainid', 'zoneid', 'accountid', 'projectid']
let store, record, domainRows, accounts, snapshotCount
const mounts = []
function mount (component, route = 'computeoffering') {
  const w = shallowMount(component, {
    props: { resource: { id: component === Assign ? 'v' : 'o', name: 'Stale title' } },
    global: {
      directives: { focus: {}, 'ctrl-enter': {} },
      mocks: { $t: key => key, $store: store, $route: { meta: { name: route } }, $message: { success: jest.fn() }, $router: { push: jest.fn() } },
      stubs: { 'a-spin': { template: '<div><slot/></div>' }, 'a-form': { template: '<form><slot/></form>', methods: { validate: () => Promise.resolve() } }, 'a-form-item': { template: '<div><slot name="label"/><slot/></div>' } }
    }
  }); mounts.push(w); return w
}
beforeEach(() => {
  record = { ...compute }; domainRows = [{ id: 'd1', name: 'A' }, { id: 'd2', name: 'B' }]; accounts = [owner]; snapshotCount = 0
  store = { state: { user: { token: 'session' } }, getters: { userInfo: { id: 'user', accountid: 'caller', roletype: 'Admin' }, project: { id: 'source-project' }, apis: Object.fromEntries(['updateServiceOffering', 'updateDiskOffering', 'assignVolume'].map(name => [name, { params: fields.map(name => ({ name })) }])) } }
  getAPI.mockReset(); postAPI.mockReset(); postAPI.mockResolvedValue({ assignvolumeresponse: { id: 'v' } })
  getAPI.mockImplementation(async (command) => {
    const bodies = { listServiceOfferings: { serviceoffering: [record] }, listDiskOfferings: { diskoffering: [record] }, listVolumes: { volume: [volume] }, listDomains: { domain: domainRows, count: domainRows.length }, listZones: { zone: [{ id: 'z1', name: 'Zone' }], count: 1 }, listAccounts: { account: accounts, count: accounts.length }, listProjects: { project: [project], count: 1 }, listSnapshots: { count: snapshotCount } }
    return { [command.toLowerCase() + 'response']: bodies[command] }
  })
})
afterEach(() => { mounts.splice(0).forEach(w => w.unmount()) })

test('compute name-only edit omits purge, external values, tags and scope', async () => {
  const w = mount(Compute); await flushPromises(); w.vm.form.name = 'New name'; await w.vm.submit()
  expect(postAPI).toHaveBeenCalledWith('updateServiceOffering', { id: 'o', name: 'New name' }, { preserveOnFailure: true })
  expect(w.emitted('refresh-data')).toHaveLength(1)
})
test('compute cleanup false is explicit, while external cleanup requires an explicit acknowledgement', async () => {
  const w = mount(Compute); await flushPromises(); w.vm.form.purgeresources = false; w.vm.form.externalMode = 'clear'
  await w.vm.submit(); expect(postAPI).not.toHaveBeenCalled(); expect(w.vm.form.purgeresources).toBe(false)
  w.vm.form.confirmClear = true; await w.vm.submit()
  expect(postAPI).toHaveBeenCalledWith('updateServiceOffering', { id: 'o', purgeresources: false, cleanupexternaldetails: true }, { preserveOnFailure: true })
})
test('external edit and clone include only real external keys, without double prefix or internal details', async () => {
  const w = mount(Compute); await flushPromises(); w.vm.form.externalMode = 'edit'; w.vm.form.external.note = 'new'; await w.vm.submit()
  expect(postAPI.mock.calls[0][1]).toEqual({ id: 'o', 'externaldetails[0].note': 'new' })
  const context = { form: {}, $store: store }
  Clone.methods.populateFormFromResource.call(context, compute)
  expect(context.form.externaldetails).toEqual({ note: 'keep' })
})
test('domain admin without tag permission cannot send changed tags', async () => {
  store.getters.userInfo.roletype = 'DomainAdmin'
  const w = mount(Compute); await flushPromises(); w.vm.form.name = 'DA name'; w.vm.form.hosttags = 'hidden'
  await w.vm.submit(); expect(postAPI.mock.calls[0][1]).toEqual({ id: 'o', name: 'DA name' }); expect(w.vm.tagFields).toEqual([])
})
test('read error blocks submit; retry loads fresh Inactive source', async () => {
  getAPI.mockRejectedValueOnce(new Error('503'))
  const w = mount(Compute); await flushPromises(); await w.vm.submit(); expect(postAPI).not.toHaveBeenCalled(); expect(w.vm.readError).toBe('503')
  await w.vm.load(); expect(w.vm.loaded).toBe(true)
  expect(getAPI).toHaveBeenCalledWith('listServiceOfferings', { id: 'o', listall: true, state: 'all', details: 'all' }, { preserveOnFailure: true })
})
test('server conflict retains input without overwriting a newer record', async () => {
  const w = mount(Compute); await flushPromises(); w.vm.form.name = 'My edit'; record = { ...record, purgeresources: false }
  await w.vm.submit(); expect(postAPI).not.toHaveBeenCalled(); expect(w.vm.form.name).toBe('My edit'); expect(w.vm.submitError).toBe('message.storage.management.changed')
})
test('lost submission response retains input, locks duplicates, and requires explicit reload', async () => {
  const w = mount(Compute); await flushPromises(); w.vm.form.name = 'My edit'; postAPI.mockRejectedValueOnce(new Error('Network Error'))
  await w.vm.submit(); await w.vm.submit(); expect(postAPI).toHaveBeenCalledTimes(1); expect(w.vm.unknownResult).toBe(true); expect(w.vm.form.name).toBe('My edit')
  await w.vm.load(); expect(w.vm.unknownResult).toBe(false); expect(w.vm.form.name).toBe('Original')
})
test('duplicate submits are locked during preflight and an old resource response cannot submit', async () => {
  const w = mount(Compute); await flushPromises(); w.vm.form.name = 'My edit'; const wait = deferred(); getAPI.mockReturnValueOnce(wait.promise)
  const pending = w.vm.submit(); await flushPromises(); await w.vm.submit(); await w.setProps({ resource: { id: 'other', name: 'Other' } }); await flushPromises()
  wait.resolve({ listserviceofferingsresponse: { serviceoffering: [compute] } }); await pending
  expect(postAPI).not.toHaveBeenCalled(); expect(w.vm.saving).toBe(false)
})
test.each(['computeoffering', 'diskoffering'])('%s access read uses all states and unchanged UUID selections send no update', async route => {
  const w = mount(Access, route); await flushPromises(); expect(w.vm.form).toEqual({ ispublic: false, domainid: ['d1'], zoneid: ['z1'] })
  await w.vm.submit(); expect(postAPI).not.toHaveBeenCalled(); expect(w.emitted('close-action')).toHaveLength(1)
})
test('domain access change sends only domains; all-zone clear sends all without changing domains', async () => {
  const w = mount(Access); await flushPromises(); w.vm.form.domainid = ['d2']; await w.vm.submit()
  expect(postAPI.mock.calls[0][1]).toEqual({ id: 'o', domainid: 'd2' })
  postAPI.mockClear(); w.vm.form.domainid = ['d1']; w.vm.form.zoneid = []; await w.vm.submit()
  expect(postAPI.mock.calls[0][1]).toEqual({ id: 'o', zoneid: 'all' })
})
test('domain administrator keeps zones and outside-scope domains and cannot make public', async () => {
  store.getters.userInfo.roletype = 'DomainAdmin'; domainRows = [{ id: 'd1' }, { id: 'child' }]; record = { ...record, domainid: 'd1,outside' }
  const w = mount(Access); await flushPromises(); expect(w.vm.retainedDomains).toEqual(['outside']); expect(w.vm.form.zoneid).toEqual(['z1'])
  w.vm.form.domainid = ['child']; await w.vm.submit(); expect(postAPI.mock.calls[0][1]).toEqual({ id: 'o', domainid: 'child' })
  expect(getAPI.mock.calls.some(([name]) => name === 'listZones')).toBe(false)
  postAPI.mockClear(); w.vm.form.ispublic = true; await w.vm.submit(); expect(postAPI).not.toHaveBeenCalled()
})
test('complete domain list uses UUIDs beyond first page; repeated partial data fails safely', async () => {
  getAPI.mockReset(); getAPI.mockResolvedValueOnce({ listdomainsresponse: { domain: [{ id: 'A' }], count: 2 } }).mockResolvedValueOnce({ listdomainsresponse: { domain: [{ id: 'B' }], count: 2 } })
  expect((await readAllocationList(getAPI, 'listDomains', 'domain')).map(x => x.id)).toEqual(['A', 'B'])
  expect(getAPI.mock.calls[1][1]).toMatchObject({ page: 2, pagesize: 100 })
  getAPI.mockReset(); getAPI.mockResolvedValue({ listdomainsresponse: { domain: [{ id: 'A' }], count: 2 } })
  await expect(readAllocationList(getAPI, 'listDomains', 'domain')).rejects.toThrow('Incomplete')
})
test.each(['User', 'ResourceAdmin'])('unauthorized %s cannot load or submit management dialogs', async role => {
  store.getters.userInfo.roletype = role
  const w = mount(Compute); await flushPromises(); await w.vm.submit(); expect(w.vm.loaded).toBe(false); expect(getAPI).not.toHaveBeenCalled(); expect(postAPI).not.toHaveBeenCalled()
})
test('assignment state guard rejects attached, root, unknown mutation status, and normal users', () => {
  expect(assignmentReason(volume, 'Admin')).toBe('')
  for (const v of [{ ...volume, virtualmachineid: 'vm' }, { ...volume, type: 'ROOT' }, { ...volume, volumemutationblockedreason: undefined }, { ...volume, clonefastflattenstatus: 'running' }]) expect(assignmentReason(v, 'Admin')).not.toBe('')
  expect(assignmentReason(volume, 'User')).not.toBe('')
})
test.each(['account', 'project'])('assignment sends one target UUID and suppresses implicit source project for %s', async type => {
  const w = mount(Assign, 'volume'); await flushPromises(); w.vm.form = { type, target: type === 'account' ? owner.id : project.id, confirm: true }; await w.vm.submit()
  expect(postAPI).toHaveBeenCalledWith('assignVolume', { volumeid: 'v', ignoreproject: true, [type === 'account' ? 'accountid' : 'projectid']: type === 'account' ? owner.id : project.id }, { preserveOnFailure: true })
  expect(w.vm.$router.push).toHaveBeenCalledWith({ path: '/volume' })
})
test('assignment blocks snapshots and revoked targets, retaining selected input', async () => {
  const w = mount(Assign, 'volume'); await flushPromises(); w.vm.form.target = owner.id; w.vm.form.confirm = true; snapshotCount = 1
  await w.vm.submit(); expect(postAPI).not.toHaveBeenCalled(); expect(w.vm.form.target).toBe(owner.id)
  snapshotCount = 0; accounts = []; await w.vm.submit(); expect(postAPI).not.toHaveBeenCalled()
})
test('assignment cannot use a disabled account, same owner, same project, or ambiguous target type', () => {
  expect(assignmentParams(volume, 'account', owner)).toEqual({ volumeid: 'v', ignoreproject: true, accountid: owner.id })
  expect(() => assignmentParams(volume, 'account', { ...owner, state: 'disabled' })).toThrow()
  expect(() => assignmentParams(volume, 'account', { ...owner, name: volume.account })).toThrow()
  expect(() => assignmentParams({ ...volume, projectid: project.id }, 'project', project)).toThrow()
  expect(() => assignmentParams(volume, 'other', owner)).toThrow()
})
test('unsupported fields and unconfirmed external removal are never transmitted', () => {
  expect(computeEditParams(compute, { name: 'Original', purgeresources: false, externalMode: 'preserve' }, { name: {} }, false)).toEqual({ id: 'o' })
  expect(() => accessParams(compute, { ispublic: false, domainid: ['injected'], zoneid: [] }, { admin: true, managedDomainIds: ['d1'], supported: { domainid: {} } })).toThrow()
})
test('public offering management stays root-only while domain-admin can manage private scopes', () => {
  expect(canManageStorageOffering({}, 'DomainAdmin')).toBe(false)
  expect(canManageStorageOffering(compute, 'DomainAdmin')).toBe(true)
  expect(canManageStorageOffering(compute, 'User')).toBe(false)
  expect(canManageStorageOffering({}, 'Admin')).toBe(true)
})
