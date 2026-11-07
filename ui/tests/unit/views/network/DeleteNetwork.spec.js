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
import DeleteNetwork from '@/views/network/DeleteNetwork'
import { postAPI } from '@/api'
import { readNetworkDeletion, networkJob, networkCollection } from '@/utils/networkLifecycle'

jest.mock('@/api', () => ({ getAPI: jest.fn(), postAPI: jest.fn() }))
jest.mock('@/utils/networkLifecycle', () => ({ ...jest.requireActual('@/utils/networkLifecycle'), readNetworkDeletion: jest.fn(), networkJob: jest.fn(), networkCollection: jest.fn() }))
const logic = jest.requireActual('@/utils/networkLifecycle')
const copy = value => JSON.parse(JSON.stringify(value))
const apis = Object.fromEntries(['deleteNetwork', 'listNetworks', 'listAsyncJobs', 'queryAsyncJobResult', 'listVirtualMachines', 'listRouters', 'listPublicIpAddresses'].map(api => [api, {}]))
const network = { id: 'a', name: '검토 네트워크', type: 'Isolated', state: 'Implemented', account: 'owner', domainid: 'root', domainpath: 'ROOT', networkofferingid: 'offering', related: 'a' }
const context = id => ({ network: { ...network, id, related: id }, vms: [], routers: [], ips: [], jobs: [] })
const job = (id, status = 1) => ({ jobid: 'job-' + id, jobinstanceid: id, jobinstancetype: 'Network', jobstatus: status })
let wrappers
beforeEach(() => {
  jest.clearAllMocks(); wrappers = []
  readNetworkDeletion.mockImplementation(async (get, row) => context(row.id))
  postAPI.mockImplementation(async (api, args) => ({ deletenetworkresponse: { jobid: 'job-' + args.id } }))
  networkJob.mockImplementation(async (get, id) => job(id))
  networkCollection.mockResolvedValue([])
})
afterEach(() => { wrappers.forEach(w => w.unmount()); jest.useRealTimers() })
function mount (ids = ['a']) {
  const getters = reactive({ apis: copy(apis), userInfo: { id: 'admin', roletype: 'Admin', account: 'admin', domainid: 'root' }, project: {} })
  const route = reactive({ path: '/guestnetwork' })
  const w = shallowMount(DeleteNetwork, { props: { resource: network, currentAction: { networkDeletionTargets: ids.map(id => ({ ...network, id, related: id })) } }, global: { mocks: { $t: key => key, $route: route, $store: { getters, state: { user: { token: 'test' } } } }, stubs: { RouterLink: true } } })
  wrappers.push(w); return { w, getters, route }
}
async function ready (w) { await flushPromises(); w.vm.acknowledged = true; await w.vm.$nextTick() }

test.each(['Isolated', 'Shared', 'L2'])('%s normal deletion is supported without forced arguments', async type => {
  readNetworkDeletion.mockImplementation(async (get, row) => ({ ...context(row.id), network: { ...context(row.id).network, type } }))
  const { w } = mount(); await ready(w); await w.vm.run()
  expect(postAPI).toHaveBeenCalledWith('deleteNetwork', { id: 'a', ignoreproject: true }, { preserveOnFailure: true })
  expect(w.vm.removed).toEqual(['a'])
})

test('partial batch retains blocked VM/NIC and retries only that item after remediation', async () => {
  let attached = true
  readNetworkDeletion.mockImplementation(async (get, row) => ({ ...context(row.id), vms: row.id === 'b' && attached ? [{ id: 'vm', name: 'VM', nic: [{ networkid: 'b' }] }] : [] }))
  const { w } = mount(['a', 'b']); await ready(w); await w.vm.run()
  expect(w.vm.removed).toEqual(['a']); expect(w.vm.retryIds).toEqual(['b'])
  expect(postAPI.mock.calls.map(call => call[1].id)).toEqual(['a'])
  attached = false; await w.vm.run(['a', 'b'])
  expect(postAPI.mock.calls.map(call => call[1].id)).toEqual(['a', 'b'])
  expect(w.vm.removed).toEqual(['a', 'b'])
})

test('batch submits the second item only after the first completed job is verified', async () => {
  let release
  networkJob.mockImplementationOnce(() => new Promise(resolve => { release = resolve })).mockImplementation(async (get, id) => job(id))
  const { w } = mount(['a', 'b']); await ready(w)
  const running = w.vm.run(); await flushPromises()
  expect(postAPI.mock.calls.map(call => call[1].id)).toEqual(['a'])
  release(job('a')); await running
  expect(postAPI.mock.calls.map(call => call[1].id)).toEqual(['a', 'b'])
})

test('double click does not duplicate submissions', async () => {
  const { w } = mount(); await ready(w)
  await Promise.all([w.vm.run(), w.vm.run()])
  expect(postAPI).toHaveBeenCalledTimes(1)
})

test('a real job failure preserves server details, code and job id', async () => {
  networkJob.mockResolvedValueOnce({ ...job('a', 2), jobresult: { errorcode: 530, errortext: 'NIC remains in network' } })
  const { w } = mount(); await ready(w); await w.vm.run()
  expect(w.vm.rows[0]).toMatchObject({ status: 'failed', code: 530, error: 'NIC remains in network', jobId: 'job-a' })
  await w.vm.run(['a']); expect(postAPI).toHaveBeenCalledTimes(2); expect(w.vm.removed).toEqual(['a'])
})

test('an ambiguous POST stops the batch and cannot become a write retry', async () => {
  postAPI.mockRejectedValue(new Error('Connection lost'))
  const { w } = mount(['a', 'b']); await ready(w); await w.vm.run()
  expect(w.vm.rows.map(r => r.status)).toEqual(['unknown', 'blocked'])
  await w.vm.run(['a', 'b']); expect(postAPI).toHaveBeenCalledTimes(1)
})

test('reopening discovers a pending delete and performs only reads', async () => {
  readNetworkDeletion.mockImplementation(async (get, row) => ({ ...context(row.id), jobs: [{ ...job(row.id, 0), cmd: 'org.apache.cloudstack.api.command.user.network.DeleteNetworkCmd' }] }))
  const { w } = mount(); await flushPromises()
  expect(w.vm.removed).toEqual(['a']); expect(postAPI).not.toHaveBeenCalled()
})

test('reopening restores the last failed job details before an explicit retry', async () => {
  readNetworkDeletion.mockImplementation(async (get, row) => ({ ...context(row.id), lastDeletion: job(row.id, 2) }))
  networkJob.mockResolvedValue({ ...job('a', 2), jobresult: { errorcode: 530, errortext: 'Router root cleanup failed' } })
  const { w } = mount(); await ready(w)
  expect(w.vm.rows[0]).toMatchObject({ status: 'failed', jobId: 'job-a', code: 530, error: 'Router root cleanup failed' })
  expect(postAPI).not.toHaveBeenCalled(); expect(w.vm.canStart).toBe(true)
  networkJob.mockResolvedValue(job('a')); await w.vm.run()
  expect(postAPI).toHaveBeenCalledTimes(1); expect(w.vm.removed).toEqual(['a'])
})

test('restored terminal success is verified by reads and is never resubmitted', async () => {
  readNetworkDeletion.mockImplementation(async (get, row) => ({ ...context(row.id), lastDeletion: job(row.id, 1) }))
  const { w } = mount(); await flushPromises()
  expect(w.vm.removed).toEqual(['a']); expect(postAPI).not.toHaveBeenCalled()
})

test('a read failure keeps the last dependency snapshot and cannot be treated as an empty network', async () => {
  const { w } = mount(); await ready(w)
  const before = copy(w.vm.rows[0].network)
  readNetworkDeletion.mockRejectedValue(new Error('Read failed')); await w.vm.refresh()
  expect(w.vm.rows[0].network).toEqual(before); expect(w.vm.rows[0].error).toBe('Read failed')
  expect(w.vm.canStart).toBe(false)
})

test('an offering change after confirmation blocks the write', async () => {
  const { w } = mount(); await ready(w)
  readNetworkDeletion.mockResolvedValue({ ...context('a'), network: { ...network, networkofferingid: 'other' } })
  await w.vm.run(); expect(postAPI).not.toHaveBeenCalled(); expect(w.vm.rows[0].error).toContain('changed')
})

test.each(['project', 'role', 'page'])('%s change invalidates late reads and closes the old dialog', async field => {
  let release
  readNetworkDeletion.mockImplementation(() => new Promise(resolve => { release = resolve }))
  const { w, getters, route } = mount(); await flushPromises()
  if (field === 'project') getters.project = { id: 'other' }
  if (field === 'role') getters.userInfo.roletype = 'ReadOnlyAdmin'
  if (field === 'page') route.path = '/vm/vm'
  await flushPromises(); release(context('a')); await flushPromises()
  expect(w.vm.scopeChanged).toBe(true); expect(w.vm.rows[0].loaded).toBe(false)
  expect(w.emitted('close-action')).toHaveLength(1); expect(postAPI).not.toHaveBeenCalled()
})

test('route change while a POST is in flight cannot submit later batch items', async () => {
  let release
  postAPI.mockImplementation(() => new Promise(resolve => { release = resolve }))
  const { w, route } = mount(['a', 'b']); await ready(w)
  const request = w.vm.run(); await flushPromises(); route.path = '/vm/vm'; await flushPromises()
  release({ deletenetworkresponse: { jobid: 'job-a' } }); await request
  expect(postAPI).toHaveBeenCalledTimes(1); expect(networkJob).not.toHaveBeenCalled()
})

test('three transient result read failures preserve the job and stop further writes', async () => {
  jest.useFakeTimers()
  networkJob.mockRejectedValue(new Error('Read timeout'))
  const { w } = mount(['a', 'b']); await ready(w)
  const pending = w.vm.run(); await flushPromises()
  for (let i = 0; i < 3; i++) { jest.advanceTimersByTime(2000); await flushPromises() }
  await pending
  expect(w.vm.rows[0]).toMatchObject({ status: 'unknown', jobId: 'job-a' })
  expect(postAPI).toHaveBeenCalledTimes(1)
  networkJob.mockResolvedValue(job('a')); await w.vm.recheck(w.vm.rows[0])
  expect(postAPI).toHaveBeenCalledTimes(1); expect(w.vm.removed).toEqual(['a'])
})

test.each([{ jobid: 'other' }, { jobinstanceid: 'other' }, { jobinstancetype: 'VirtualMachine' }, { jobstatus: 9 }])('wrong job identity is rejected: %o', async extra => {
  const get = jest.fn().mockResolvedValue({ queryasyncjobresultresponse: { ...job('a'), ...extra } })
  await expect(logic.networkJob(get, 'a', 'job-a')).rejects.toThrow('scope')
})

test('failed and malformed collection responses are not empty successful reads', async () => {
  for (const response of [{}, { listnetworksresponse: { errorcode: 530 } }, { listnetworksresponse: { count: 2, network: [] } }]) {
    await expect(logic.networkCollection(jest.fn().mockResolvedValue(response), 'listNetworks', 'network', {})).rejects.toThrow()
  }
})

test('each project target is read in its own scope; Root job queries do not inherit a selected project', async () => {
  const calls = []
  const get = async (api, args) => {
    calls.push({ api, args })
    return { [api.toLowerCase() + 'response']: { count: api === 'listNetworks' ? 1 : 0, [api === 'listNetworks' ? 'network' : api === 'listAsyncJobs' ? 'asyncjobs' : 'virtualmachine']: api === 'listNetworks' ? [{ ...network, projectid: 'actual' }] : [] } }
  }
  await logic.readNetworkDeletion(get, { ...network, projectid: 'actual' }, { apis: { listVirtualMachines: {}, listAsyncJobs: {} } })
  expect(calls.find(c => c.api === 'listVirtualMachines').args.projectid).toBe('actual')
  expect(calls.find(c => c.api === 'listAsyncJobs').args.projectid).toBeUndefined()
})

test.each([
  [{ roletype: 'User', account: 'other', domainid: 'root' }, {}, 'permission'],
  [{ roletype: 'User', account: 'owner', domainid: 'root' }, {}, ''],
  [{ roletype: 'DomainAdmin', domainpath: 'ROOT/A' }, { domainpath: 'ROOT/AB' }, 'permission'],
  [{ roletype: 'Admin' }, { related: 'previous' }, 'checkpoint'],
  [{ roletype: 'Admin' }, { state: 'Implementing' }, 'busy']
])('deletion scope and state guards: %o', (user, extra, expected) => expect(logic.networkDeleteReason({ ...network, ...extra }, { apis, userInfo: user })).toBe(expected))
