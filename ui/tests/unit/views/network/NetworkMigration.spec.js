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

import { shallowMount, flushPromises } from '@vue/test-utils'
import { reactive } from 'vue'
import MigrateNetwork from '@/views/network/MigrateNetwork'
import { postAPI } from '@/api'
import { migrationApis, readMigrationContext, migrationTargetDiagnostic, queryMigrationJob } from '@/utils/networkMigration'

jest.mock('@/api', () => ({ getAPI: jest.fn(), postAPI: jest.fn() }))
jest.mock('@/utils/networkMigration', () => {
  const actual = jest.requireActual('@/utils/networkMigration')
  return { ...actual, readMigrationContext: jest.fn(), migrationTargetDiagnostic: jest.fn(), queryMigrationJob: jest.fn() }
})
const logic = jest.requireActual('@/utils/networkMigration')
const copy = value => JSON.parse(JSON.stringify(value))
const providerServices = ['Dhcp', 'Dns', 'SourceNat'].map(name => ({ name, provider: [{ name: 'VirtualRouter' }] }))
const source = { id: 'source', name: 'Source', tags: 'a', state: 'Enabled', guestiptype: 'Isolated', traffictype: 'Guest', service: providerServices }
const offering = { ...copy(source), id: 'target', name: 'Target', tags: 'b' }
const physical = [{ id: 'a', name: 'A', tags: ['a'], state: 'Enabled' }, { id: 'b', name: 'B', tags: ['b'], state: 'Enabled' }]
const target = { offering, physical: physical[1] }
const network = { id: 'net', related: 'net', name: 'QA', type: 'Isolated', traffictype: 'Guest', networkofferingid: 'source', physicalnetworkid: 'a', state: 'Implemented', zoneid: 'zone', account: 'admin', domainid: 'domain', displaynetwork: true, cidr: '10.0.0.0/24', gateway: '10.0.0.1' }
const vm = { id: 'vm', name: 'QA VM', state: 'Running', nic: [{ id: 'nic', networkid: 'net', ipaddress: '10.0.0.5', macaddress: '02:00:00:00:00:05' }] }
const base = { network, offering: source, previous: null, checkpoint: false, physical, traffic: { a: [{ id: 'ta', traffictype: 'Guest' }], b: [{ id: 'tb', traffictype: 'Guest' }] }, vms: [vm], routers: [], publicIps: [], candidates: [target], jobs: [], job: null }
const job = { jobid: 'job', jobinstanceid: 'net', jobinstancetype: 'Network', cmd: 'org.apache.cloudstack.api.command.admin.network.MigrateNetworkCmd', created: '2026-11-05T12:00:00Z', jobstatus: 0 }
const completed = () => ({ ...copy(base), network: { ...copy(network), networkofferingid: 'target', physicalnetworkid: 'b' }, offering: copy(offering), candidates: [{ offering: copy(source), physical: physical[0] }] })
const checkpoint = () => ({ ...completed(), network: { ...completed().network, related: 'previous', state: 'Allocated' }, previous: { ...copy(network), id: 'previous', related: 'net', displaynetwork: false }, checkpoint: true, job: { ...job, jobstatus: 2 }, jobs: [{ ...job, jobstatus: 2 }] })
const getters = () => reactive({ userInfo: { id: 'operator', roletype: 'Admin' }, apis: Object.fromEntries(migrationApis.map(api => [api, {}])), project: {} })
let wrappers
beforeEach(() => {
  jest.clearAllMocks(); wrappers = []
  readMigrationContext.mockResolvedValue(copy(base))
  migrationTargetDiagnostic.mockResolvedValue({ status: 'ready', network: physical[1] })
  queryMigrationJob.mockResolvedValue(copy(job))
  postAPI.mockResolvedValue({ migratenetworkresponse: { jobid: 'job' } })
})
afterEach(() => wrappers.forEach(w => w.unmount()))
function mount () {
  const state = getters()
  const route = reactive({ path: '/guestnetwork/net' })
  const w = shallowMount(MigrateNetwork, { props: { resource: network }, global: { mocks: { $t: key => key, $store: { getters: state }, $route: route }, stubs: { RouterLink: true } } })
  wrappers.push(w); return { w, state, route }
}
async function ready (w) { await flushPromises(); await w.vm.selectTarget('target'); w.vm.acknowledged = true; await w.vm.$nextTick(); expect(w.vm.canStart).toBe(true) }

test.each(['User', 'DomainAdmin', 'ReadOnlyAdmin'])('%s does not see the action', role => {
  const g = getters(); g.userInfo.roletype = role
  expect(logic.migrationVisible(network, g)).toBe(false)
})
test.each(['Shared', 'L2'])('%s does not see the Isolated action', type => expect(logic.migrationVisible({ ...network, type }, getters())).toBe(false))
test('VPC, routing and missing API permissions are excluded', () => {
  expect(logic.migrationVisible({ ...network, vpcid: 'vpc' }, getters())).toBe(false)
  expect(logic.migrationVisible({ ...network, ip4routing: 'Static' }, getters())).toBe(false)
  const g = getters(); delete g.apis.listAsyncJobs
  expect(logic.migrationVisible(network, g)).toBe(false)
})
test('physical resolution uses offering tags, not updateNetwork candidates', () => {
  expect(logic.resolveMigrationPhysical(offering, physical, base.traffic).id).toBe('b')
  expect(logic.resolveMigrationPhysical({ ...offering, tags: 'missing' }, physical, base.traffic)).toBe(null)
  expect(logic.resolveMigrationPhysical(offering, [...physical, { ...physical[1], id: 'duplicate' }], { ...base.traffic, duplicate: base.traffic.b })).toBe(null)
})
test.each([
  { state: 'Disabled' }, { guestiptype: 'L2' }, { guestiptype: 'Shared' }, { forvpc: true }, { networkmode: 'ROUTED' },
  { service: [{ name: 'Dhcp', provider: [{ name: 'Netscaler' }] }] }, { specifyipranges: true }, { internetprotocol: 'IPv6' }, { service: [] }
])('unsupported offering %o is not selectable', fields => expect(logic.migrationOfferingCompatible(source, { ...offering, ...fields })).toBe(false))
test('new start and confirmed checkpoint use different resume values', () => {
  expect(logic.migrationParams(base, target)).toEqual({ networkid: 'net', networkofferingid: 'target', resume: false })
  expect(logic.migrationParams(checkpoint(), target)).toEqual({ networkid: 'net', networkofferingid: 'target', resume: true })
  expect(() => logic.migrationParams(checkpoint(), { ...target, offering: source })).toThrow('destination')
})
test('checkpoint requires reciprocal hidden previous network and matching ownership', () => {
  expect(logic.migrationCheckpoint(checkpoint().network, checkpoint().previous)).toBe(true)
  for (const previous of [null, { ...checkpoint().previous, related: 'other' }, { ...checkpoint().previous, displaynetwork: true }, { ...checkpoint().previous, account: 'other' }]) expect(() => logic.migrationCheckpoint(checkpoint().network, previous)).toThrow('checkpoint')
})
test.each(['Starting', 'Stopping', 'Migrating', 'Destroyed'])('VM %s blocks submission', state => expect(() => logic.migrationParams({ ...base, vms: [{ ...vm, state }] }, target)).toThrow('vm.state'))
test('transitional router state blocks submission', () => expect(() => logic.migrationParams({ ...base, routers: [{ id: 'r', state: 'Starting' }] }, target)).toThrow('router.state'))
test('pending migration blocks another submission', () => expect(() => logic.migrationParams({ ...base, jobs: [job] }, target)).toThrow('job.pending'))
test('job identity mismatches cannot become a successful result', async () => {
  for (const change of [{ jobinstanceid: 'other' }, { jobinstancetype: 'VirtualMachine' }, { jobid: 'other' }, { jobstatus: 9 }]) {
    await expect(logic.queryMigrationJob(jest.fn().mockResolvedValue({ queryasyncjobresultresponse: { ...job, ...change } }), 'net', 'job')).rejects.toThrow('job.scope')
  }
})
test('success requires destination offering, physical network and completed cleanup', () => {
  expect(logic.migrationSucceeded(completed(), target)).toBe(true)
  expect(logic.migrationSucceeded(checkpoint(), target)).toBe(false)
  expect(logic.migrationSucceeded(base, target)).toBe(false)
})

test('context reads paginated migration jobs and actual previous-network VM NICs', async () => {
  const ctx = checkpoint(); const requests = []
  ctx.network.projectid = 'project'; ctx.previous.projectid = 'project'
  const get = async (command, args) => {
    requests.push({ command, args })
    const response = (key, rows) => ({ [command.toLowerCase() + 'response']: { count: rows.length, [key]: rows } })
    if (command === 'listNetworks') return response('network', [args.id === 'net' ? ctx.network : ctx.previous])
    if (command === 'listNetworkOfferings') return response('networkoffering', args.id ? [offering] : [source, offering])
    if (command === 'listPhysicalNetworks') return response('physicalnetwork', physical)
    if (command === 'listTrafficTypes') return response('traffictype', base.traffic[args.physicalnetworkid])
    if (command === 'listAsyncJobs') {
      expect(args.projectid).toBeUndefined()
      return response('asyncjobs', [{ ...job, jobstatus: 2 }])
    }
    if (command === 'listVirtualMachines') return response('virtualmachine', args.networkid === 'previous' ? [{ ...vm, nic: [{ ...vm.nic[0], networkid: 'previous' }] }] : [])
    if (command === 'listRouters') return response('router', args.networkid === 'previous' ? [{ id: 'router', name: 'Old router', state: 'Running' }] : [])
    if (command === 'listPublicIpAddresses') return response('publicipaddress', [])
    throw Error(command)
  }
  const read = await logic.readMigrationContext(get, 'net', 'project')
  expect(read.checkpoint).toBe(true)
  expect(read.vms[0].nic[0].networkid).toBe('previous')
  expect(read.routers[0].migrationnetworkid).toBe('previous')
  expect(requests.some(r => r.command === 'listNetworkOfferings' && r.args.fornetworkid)).toBe(false)
  expect(requests.find(r => r.command === 'listAsyncJobs').args).toMatchObject({ resourceid: 'net', resourcetype: 'Network', keyword: 'MigrateNetworkCmd', listall: true })
  expect(requests.every(r => r.args.ignoreproject === true)).toBe(true)
  expect(requests.filter(r => ['listNetworks', 'listVirtualMachines', 'listPublicIpAddresses'].includes(r.command)).every(r => r.args.projectid === 'project')).toBe(true)
})

test('duplicate clicks submit exactly one start with resume=false', async () => {
  const { w } = mount(); await ready(w)
  let complete; postAPI.mockReturnValue(new Promise(resolve => { complete = resolve }))
  const first = w.vm.start(); const second = w.vm.start(); await flushPromises()
  expect(postAPI).toHaveBeenCalledTimes(1)
  expect(postAPI.mock.calls[0][1]).toEqual({ networkid: 'net', networkofferingid: 'target', resume: false, ignoreproject: true })
  complete({ migratenetworkresponse: { jobid: 'job' } }); await first; await second
  expect(w.vm.pending).toBe(true)
})
test('fresh VM/NIC change preserves selection but blocks POST', async () => {
  const { w } = mount(); await ready(w)
  readMigrationContext.mockResolvedValue({ ...copy(base), vms: [{ ...vm, nic: [{ ...vm.nic[0], ipaddress: '10.0.0.9' }] }] })
  await w.vm.start()
  expect(postAPI).not.toHaveBeenCalled(); expect(w.vm.targetId).toBe('target'); expect(w.vm.error).toContain('changed')
})
test('failed refresh preserves target and blocks submission', async () => {
  const { w } = mount(); await ready(w)
  readMigrationContext.mockRejectedValue(new Error('read failed')); await w.vm.load()
  expect(w.vm.targetId).toBe('target'); expect(w.vm.canStart).toBe(false); expect(postAPI).not.toHaveBeenCalled()
})
test('provider diagnosis failure blocks start', async () => {
  const { w } = mount(); await flushPromises()
  migrationTargetDiagnostic.mockRejectedValue(new Error('not ready'))
  await w.vm.selectTarget('target'); w.vm.acknowledged = true
  expect(w.vm.canStart).toBe(false); await w.vm.start(); expect(postAPI).not.toHaveBeenCalled()
})
test('receipt loss cannot trigger an automatic duplicate POST', async () => {
  const { w } = mount(); await ready(w); postAPI.mockResolvedValue({})
  await w.vm.start(); await w.vm.start()
  expect(w.vm.unknown).toBe(true); expect(postAPI).toHaveBeenCalledTimes(1)
})
test('server checkpoint fixes destination and resume=true after reload', async () => {
  readMigrationContext.mockResolvedValue(checkpoint())
  queryMigrationJob.mockResolvedValueOnce({ ...job, jobstatus: 2, jobresult: { errortext: 'capacity' } }).mockResolvedValue(job)
  const { w } = mount(); await flushPromises()
  expect(w.vm.context.checkpoint).toBe(true); expect(w.vm.target.offering.id).toBe('target')
  await w.vm.selectTarget('different'); w.vm.acknowledged = true; await w.vm.$nextTick()
  await w.vm.start()
  expect(postAPI.mock.calls[0][1]).toEqual({ networkid: 'net', networkofferingid: 'target', resume: true, ignoreproject: true })
})
test('pending job recovers from server on reload without POST', async () => {
  readMigrationContext.mockResolvedValue({ ...copy(base), job, jobs: [job] })
  const { w } = mount(); await flushPromises()
  expect(w.vm.pending).toBe(true); expect(w.vm.canStart).toBe(false); expect(postAPI).not.toHaveBeenCalled()
})
test('success reads actual destination and NIC data before marking verified', async () => {
  const { w } = mount(); await ready(w)
  readMigrationContext.mockResolvedValueOnce(copy(base)).mockResolvedValue(completed())
  queryMigrationJob.mockResolvedValue({ ...job, jobstatus: 1, jobresult: { network: completed().network } })
  await w.vm.start()
  expect(w.vm.verified).toBe(true); expect(w.vm.vmRows[0].location).toBe('B'); expect(w.emitted('refresh-data')).toHaveLength(1)
})
test('completed job reload uses server result rather than browser memory', async () => {
  const done = { ...job, jobstatus: 1, jobresult: { network: completed().network } }
  readMigrationContext.mockResolvedValue({ ...completed(), job: done, jobs: [done] }); queryMigrationJob.mockResolvedValue(done)
  const { w } = mount(); await flushPromises()
  expect(w.vm.verified).toBe(true); expect(postAPI).not.toHaveBeenCalled()
})
test('resource switch discards late data from the old network', async () => {
  let complete; readMigrationContext.mockReturnValueOnce(new Promise(resolve => { complete = resolve }))
  const { w } = mount()
  const next = { ...copy(base), network: { ...network, id: 'next', related: 'next' } }
  readMigrationContext.mockResolvedValue(next); await w.setProps({ resource: next.network }); await flushPromises()
  complete(copy(base)); await flushPromises()
  expect(w.vm.context.network.id).toBe('next'); expect(postAPI).not.toHaveBeenCalled()
})
test('role/API scope change during preflight cannot submit', async () => {
  const { w, state } = mount(); await ready(w)
  let complete; readMigrationContext.mockReturnValueOnce(new Promise(resolve => { complete = resolve }))
  const request = w.vm.start(); state.userInfo.roletype = 'User'; await w.vm.$nextTick()
  complete(copy(base)); await request; await flushPromises()
  expect(postAPI).not.toHaveBeenCalled(); expect(w.vm.canStart).toBe(false)
})

test('transient job identity read failure recovers without another POST', async () => {
  jest.useFakeTimers()
  try {
    const done = { ...job, jobstatus: 1, jobresult: { network: completed().network } }
    readMigrationContext.mockResolvedValueOnce({ ...copy(base), job, jobs: [job] }).mockResolvedValue({ ...completed(), job: done, jobs: [done] })
    queryMigrationJob.mockRejectedValueOnce(new Error('message.network.migration.job.scope')).mockResolvedValue(done)
    const { w } = mount(); await flushPromises()
    expect(w.vm.unknown).toBe(true); expect(w.vm.verified).toBe(false)
    jest.advanceTimersByTime(2000); await flushPromises()
    expect(w.vm.verified).toBe(true); expect(w.vm.unknown).toBe(false)
    expect(queryMigrationJob).toHaveBeenCalledTimes(2); expect(postAPI).not.toHaveBeenCalled()
  } finally { jest.useRealTimers() }
})

test('persistent job read failure stops after three automatic retries', async () => {
  jest.useFakeTimers()
  try {
    readMigrationContext.mockResolvedValue({ ...copy(base), job, jobs: [job] })
    queryMigrationJob.mockRejectedValue(new Error('unavailable'))
    const { w } = mount(); await flushPromises()
    for (let i = 0; i < 5; i++) { jest.advanceTimersByTime(2000); await flushPromises() }
    expect(queryMigrationJob).toHaveBeenCalledTimes(4)
    expect(w.vm.unknown).toBe(true); expect(w.vm.canStart).toBe(false); expect(postAPI).not.toHaveBeenCalled()
  } finally { jest.useRealTimers() }
})

test('route navigation closes the dialog and discards its pending network read', async () => {
  let finish
  readMigrationContext.mockReturnValueOnce(new Promise(resolve => { finish = resolve }))
  const { w, route } = mount()
  route.path = '/vm/vm'; await w.vm.$nextTick()
  finish(copy(base)); await flushPromises()
  expect(w.emitted('close-action')).toHaveLength(1)
  expect(w.vm.context).toBe(null); expect(postAPI).not.toHaveBeenCalled()
})

test('a VM resource cannot be mistaken for the previous network after navigation', async () => {
  const { w } = mount(); await flushPromises()
  await w.setProps({ resource: { id: 'vm', name: 'virtual machine' } }); await flushPromises()
  expect(readMigrationContext).toHaveBeenCalledTimes(1)
  expect(w.emitted('close-action')).toHaveLength(1); expect(postAPI).not.toHaveBeenCalled()
})
