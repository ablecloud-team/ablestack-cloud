// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements. See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership. The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License. You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied. See the License for the
// specific language governing permissions and limitations
// under the License.

import { reactive } from 'vue'
import { shallowMount, flushPromises } from '@vue/test-utils'
import RestartNetwork from '@/views/network/RestartNetwork'
import { getAPI, postAPI } from '@/api'
import { networkRestartReason, networkRestartParams, networkRestartCapabilities, networkRestartOptionReason } from '@/utils/networkRestart'

jest.mock('@/api', () => ({ getAPI: jest.fn(), postAPI: jest.fn() }))

const params = ['id', 'cleanup', 'makeredundant', 'livepatch']
const apis = { listAsyncJobs: {}, queryAsyncJobResult: {}, restartNetwork: { params: params.map(name => ({ name })) }, listNetworks: {}, listNetworkOfferings: {}, listRouters: {} }
const admin = { id: 'admin', roletype: 'Admin', account: 'admin', domainid: 'root', domainpath: 'ROOT' }
const user = { id: 'user', roletype: 'User', account: 'owner', domainid: 'root', domainpath: 'ROOT' }
const network = { id: 'a', name: 'Network A', type: 'Isolated', state: 'Implemented', account: 'owner', domainid: 'root', domainpath: 'ROOT', zoneid: 'zone', networkofferingid: 'offering' }
const offering = { id: 'offering', service: [{ name: 'Dns', provider: [{ name: 'VirtualRouter' }] }, { name: 'SourceNat', capability: [{ name: 'RedundantRouter', value: 'true' }] }] }
const config = { allowMakingRouterRedundant: true }
const data = { network, offering, routers: [{ state: 'Running' }] }

test.each([
  [{ type: 'L2' }, admin, {}, 'type'],
  [{ state: 'Allocated' }, admin, {}, 'state'],
  [{ state: 'Implementing' }, admin, {}, 'state'],
  [{ jobid: 'pending', jobstatus: 0 }, admin, {}, 'state'],
  [{}, user, {}, ''],
  [{ account: 'other' }, user, {}, 'permission'],
  [{ domainid: 'other' }, user, {}, 'permission'],
  [{ projectid: 'p', account: 'project' }, user, { id: 'p' }, ''],
  [{ projectid: 'p' }, user, { id: 'q' }, 'project'],
  [{ type: 'Shared', specifyvlan: true }, user, {}, 'shared.admin'],
  [{ type: 'Shared', specifyvlan: true }, admin, {}, ''],
  [{ domainid: 'child', domainpath: 'ROOT/A/child' }, { roletype: 'DomainAdmin', domainid: 'a', domainpath: 'ROOT/A' }, {}, ''],
  [{ domainid: 'peer', domainpath: 'ROOT/AB' }, { roletype: 'DomainAdmin', domainid: 'a', domainpath: 'ROOT/A' }, {}, 'permission']
])('single and bulk target policy: %p', (extra, actor, project, expected) => {
  expect(networkRestartReason({ ...network, ...extra }, { apis, userInfo: actor, project })).toBe(expected)
})
test('missing identity and missing API permission fail closed', () => {
  expect(networkRestartReason({}, { apis, userInfo: admin })).toBe('missing')
  expect(networkRestartReason(network, { apis: {}, userInfo: admin })).toBe('permission')
})
test('all supported false arguments are explicit; absent API capabilities are omitted', () => {
  expect(networkRestartParams('a', {})).toEqual({ id: 'a', cleanup: false, makeredundant: false, livepatch: false })
  expect(networkRestartParams('a', { livepatch: true }, ['cleanup', 'livepatch'])).toEqual({ id: 'a', cleanup: false, livepatch: true })
})
test('VPC, Shared, redundancy and provider capabilities constrain options', () => {
  expect(networkRestartCapabilities(data, params, config)).toEqual({ cleanup: true, makeredundant: true, livepatch: true })
  expect(networkRestartCapabilities({ ...data, network: { ...network, vpcid: 'vpc' } }, params, config)).toMatchObject({ cleanup: false, makeredundant: false })
  expect(networkRestartCapabilities({ ...data, network: { ...network, type: 'Shared' } }, params, config).makeredundant).toBe(false)
  expect(networkRestartCapabilities(data, params, { allowMakingRouterRedundant: 'false' }).makeredundant).toBe(false)
  expect(networkRestartCapabilities({ ...data, offering: { service: [] } }, params, config).livepatch).toBe(false)
})
test.each([
  [{ cleanup: true, livepatch: true }, data, 'livepatch'],
  [{ livepatch: true }, { ...data, routers: [] }, 'router.running'],
  [{ livepatch: true }, { ...data, routers: [{ state: 'Stopped' }] }, 'router.running'],
  [{}, { ...data, routers: [{ state: 'Starting' }] }, 'router.busy'],
  [{ cleanup: true }, { ...data, network: { ...network, vpcid: 'vpc' } }, 'cleanup']
])('option incompatibility: %p', (options, current, expected) => {
  expect(networkRestartOptionReason(current, options, params, config)).toBe(expected)
})

let wrappers, networks, poll, getters, failFinalRead
const reject = { response: { data: { restartnetworkresponse: { errorcode: 530, errortext: 'Definitive server refusal' } } } }
async function mount (targets = [network], actor = admin) {
  networks = Object.fromEntries(targets.map(n => [n.id, { ...n }]))
  getters = reactive({ apis, userInfo: { ...actor }, project: {} })
  const wrapper = shallowMount(RestartNetwork, { props: { resource: targets[0], currentAction: { networkRestartTargets: targets } }, global: { mocks: { $t: key => key, $store: { getters }, $route: { path: '/guestnetwork' }, $config: config, $pollJob: (...args) => poll(...args) } } })
  wrappers.push(wrapper); await flushPromises(); return wrapper
}
beforeEach(() => {
  wrappers = []; failFinalRead = false
  poll = jest.fn().mockResolvedValue({ jobstatus: 1 })
  getAPI.mockReset().mockImplementation((command, p) => {
    if (command === 'listAsyncJobs') return Promise.resolve({ listasyncjobsresponse: { count: 0, asyncjobs: [] } })
    if (command === 'queryAsyncJobResult') return poll({ jobId: p.jobid }).then(result => ({ queryasyncjobresultresponse: { ...result, jobid: p.jobid, jobinstanceid: Object.keys(networks).find(id => p.jobid.endsWith(id)), jobinstancetype: 'Network' } }))
    if (command === 'listNetworks') {
      if (failFinalRead && postAPI.mock.calls.length) return Promise.reject(new Error('Final read failed'))
      return Promise.resolve({ listnetworksresponse: { network: networks[p.id] ? [{ ...networks[p.id] }] : [] } })
    }
    if (command === 'listRouters') return Promise.resolve({ listroutersresponse: { router: [{ id: 'router', state: 'Running' }] } })
    return Promise.resolve({ listnetworkofferingsresponse: { networkoffering: [offering] } })
  })
  postAPI.mockReset().mockImplementation((command, p) => Promise.resolve({ restartnetworkresponse: { jobid: 'job-' + p.id } }))
})
afterEach(() => wrappers.forEach(w => w.unmount()))

test('a mixed L2 selection and a mixed unauthorized selection send no requests', async () => {
  const targets = [network, { ...network, id: 'b', type: 'L2' }]
  const w = await mount(targets); expect(w.vm.canStart).toBe(false); await w.vm.run(); expect(postAPI).not.toHaveBeenCalled()
  const w2 = await mount([network, { ...network, id: 'b', type: 'Shared', specifyvlan: true, account: 'other' }], user)
  expect(w2.vm.canStart).toBe(false); await w2.vm.run(); expect(postAPI).not.toHaveBeenCalled()
})
test('a state change during preflight blocks the whole original selection', async () => {
  const w = await mount([network, { ...network, id: 'b' }]); networks.b.state = 'Implementing'
  await w.vm.run(); expect(postAPI).not.toHaveBeenCalled(); expect(w.vm.started).toBe(false)
})
test('cleanup clears Live Patch and redundancy implies cleanup', async () => {
  const w = await mount(); w.vm.changeOption('livepatch', true); w.vm.changeOption('cleanup', true)
  expect(w.vm.options.livepatch).toBe(false); w.vm.changeOption('cleanup', false); w.vm.changeOption('makeredundant', true)
  expect(w.vm.options).toEqual({ cleanup: true, makeredundant: true, livepatch: false })
})
test('single and multi-target requests include all four arguments and wait for terminal results', async () => {
  const w = await mount([network, { ...network, id: 'b' }]); w.vm.changeOption('livepatch', true); await w.vm.run()
  expect(postAPI.mock.calls.map(c => c[1])).toEqual(['a', 'b'].map(id => ({ id, cleanup: false, makeredundant: false, livepatch: true, ignoreproject: true })))
  expect(w.vm.rows.map(r => r.status)).toEqual(['success', 'success']); expect(poll).toHaveBeenCalledTimes(2)
})
test.each(['server', 'job'])('partial %s failure retries only failed items', async kind => {
  const w = await mount([network, { ...network, id: 'b' }])
  if (kind === 'server') postAPI.mockImplementation((command, p) => p.id === 'b' ? Promise.reject(reject) : Promise.resolve({ restartnetworkresponse: { jobid: 'job-a' } }))
  else poll.mockImplementation(({ jobId }) => Promise.resolve(jobId === 'job-b' ? { jobstatus: 2, jobresult: { errortext: 'Job failure' } } : { jobstatus: 1 }))
  await w.vm.run(); expect(w.vm.rows.map(r => r.status)).toEqual(['success', 'failed'])
  postAPI.mockImplementation((command, p) => Promise.resolve({ restartnetworkresponse: { jobid: 'retry-' + p.id } })); poll.mockResolvedValue({ jobstatus: 1 })
  await w.vm.run(w.vm.failedIds); expect(postAPI.mock.calls.map(c => c[1].id)).toEqual(['a', 'b', 'b']); expect(w.vm.succeeded).toBe(2)
})
test('a state change between item executions fails that item without restarting a success again', async () => {
  const w = await mount([network, { ...network, id: 'b' }]); poll.mockImplementation(() => { networks.b.state = 'Implementing'; return Promise.resolve({ jobstatus: 1 }) })
  await w.vm.run(); expect(postAPI.mock.calls.map(c => c[1].id)).toEqual(['a']); expect(w.vm.failedIds).toEqual(['b'])
})
test('a successful job with a failed final lookup retains its job and rechecks without another POST', async () => {
  const w = await mount(); failFinalRead = true; await w.vm.run()
  expect(w.vm.rows[0]).toMatchObject({ status: 'unknown', jobId: 'job-a' }); expect(w.vm.failedIds).toEqual([])
  failFinalRead = false; await w.vm.recheck(w.vm.rows[0]); expect(w.vm.rows[0].status).toBe('success'); expect(postAPI).toHaveBeenCalledTimes(1)
})
test('lookup errors preserve the API response detail', async () => {
  const w = await mount()
  expect(w.vm.errorText({ response: { data: { listnetworksresponse: { errortext: 'Current network lookup failed' } } }, message: 'HTTP 500' })).toBe('Current network lookup failed')
})
test.each(['timeout', 'receipt'])('an unknown %s never offers automatic resubmission', async kind => {
  const w = await mount()
  if (kind === 'timeout') postAPI.mockRejectedValue(new Error('timeout'))
  else postAPI.mockResolvedValue({ restartnetworkresponse: {} })
  await w.vm.run(); expect(w.vm.rows[0].status).toBe('unknown'); expect(w.vm.failedIds).toEqual([]); await w.vm.run([]); expect(postAPI).toHaveBeenCalledTimes(1)
})
test('double submit and navigation/project change cannot send remaining writes', async () => {
  const w = await mount([network, { ...network, id: 'b' }]); let release
  postAPI.mockImplementation(() => new Promise(resolve => { release = resolve }))
  const running = w.vm.run(); await flushPromises(); await w.vm.run(); expect(postAPI).toHaveBeenCalledTimes(1)
  getters.project.id = 'other'; await flushPromises(); release({ restartnetworkresponse: { jobid: 'job-a' } }); await running
  expect(postAPI).toHaveBeenCalledTimes(1); expect(w.vm.scopeChanged).toBe(true)
})
