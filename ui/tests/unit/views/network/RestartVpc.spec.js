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
import { reactive } from 'vue'
import { shallowMount, flushPromises } from '@vue/test-utils'
import RestartVpc from '@/views/network/RestartVpc'
import { getAPI, postAPI } from '@/api'
import { vpcRestartApis } from '@/utils/vpcRestart'
jest.mock('@/api', () => ({ getAPI: jest.fn(), postAPI: jest.fn() }))
const actor = { id: 'u', roleid: 'role', roletype: 'Admin', account: 'admin', domainid: 'd' }
const a = { id: 'a', name: 'VPC A', account: 'admin', domainid: 'd', zoneid: 'z', state: 'Enabled', vpcofferingid: 'o', redundantvpcrouter: false, restartrequired: false }
const b = { ...a, id: 'b', name: 'VPC B' }
const offering = { id: 'o', state: 'Enabled', service: [{ name: 'SourceNat', provider: [{ name: 'VpcVirtualRouter' }] }] }
const redundant = { ...offering, id: 'r', name: 'Redundant VPC offering', isdefault: true }
let wrappers, vpcs, getters, route, jobs, routerState, finalFailure, queried, pending, query, onRead
function body (name, rows, field) { return { [name.toLowerCase() + 'response']: { [field]: rows, count: rows.length } } }
async function mount (targets = [a, b]) {
  const apis = Object.fromEntries(vpcRestartApis.map(x => [x, {}])); apis.restartVPC.params = ['id', 'cleanup', 'makeredundant', 'livepatch'].map(name => ({ name })); apis.listNetworks = {}; apis.listRouters = {}
  getters = reactive({ apis, userInfo: { ...actor }, project: {} }); route = reactive({ fullPath: '/vpc' })
  const w = shallowMount(RestartVpc, { props: { resource: targets[0], currentAction: { vpcRestartTargets: targets } }, global: { stubs: { MoldDialog: { template: '<div><slot /></div>' }, MoldDialogActions: { template: '<div><slot /></div>' } }, mocks: { $t: x => x, $route: route, $store: { getters, state: { user: { token: 'secret-token-not-persisted' } } } } } })
  wrappers.push(w); await flushPromises(); return w
}
beforeEach(() => {
  sessionStorage.clear(); wrappers = []; vpcs = { a: { ...a }, b: { ...b } }; jobs = {}; routerState = 'Running'; finalFailure = false; queried = false; pending = []; onRead = () => {}
  query = jest.fn().mockImplementation(id => ({ jobid: id, cmd: 'org.apache.cloudstack.api.command.user.vpc.RestartVPCCmd', userid: 'u', jobinstancetype: 'Vpc', jobstatus: 1, jobresult: { success: true } }))
  getAPI.mockReset().mockImplementation(async (cmd, p) => {
    onRead(cmd, p)
    if (cmd === 'queryAsyncJobResult') { queried = true; return { queryasyncjobresultresponse: await query(p.jobid) } }
    if (cmd === 'listVPCs') { if (finalFailure && queried) throw new Error('Final lookup failed'); return body(cmd, vpcs[p.id] ? [{ ...vpcs[p.id] }] : [], 'vpc') }
    if (cmd === 'listVPCOfferings') return body(cmd, [offering, redundant], 'vpcoffering')
    if (cmd === 'listNetworks') return body(cmd, [{ id: 'net-' + p.vpcid, vpcid: p.vpcid }], 'network')
    if (cmd === 'listRouters') return body(cmd, [{ id: 'router-' + p.vpcid, vpcid: p.vpcid, state: routerState }], 'router')
    if (cmd === 'listAsyncJobs') return body(cmd, pending, 'asyncjobs')
    throw new Error(cmd)
  })
  postAPI.mockReset().mockImplementation(async (cmd, p) => { jobs[p.customjobid] = p.id; return { restartvpcresponse: { jobid: p.customjobid } } })
})
afterEach(() => wrappers.forEach(w => w.unmount()))

test('bulk true and false flags are explicit and wait for each final job', async () => {
  const w = await mount(); w.vm.changeOption('livepatch', true); await w.vm.run()
  expect(postAPI.mock.calls.map(c => [c[1].id, c[1].cleanup, c[1].makeredundant, c[1].livepatch])).toEqual([['a', false, false, true], ['b', false, false, true]])
  expect(new Set(postAPI.mock.calls.map(c => c[1].customjobid)).size).toBe(2)
  expect(w.vm.rows.map(r => r.status)).toEqual(['success', 'success']); expect(w.vm.succeeded).toBe(2)
})
test('single uses the same contract and default false values', async () => {
  const w = await mount([a]); await w.vm.run(); expect(postAPI.mock.calls[0][1]).toMatchObject({ id: 'a', cleanup: false, makeredundant: false, livepatch: false }); expect(w.vm.succeeded).toBe(1)
})
test('cleanup clears patch, conversion forces cleanup, disabling cleanup clears conversion', async () => {
  const w = await mount(); w.vm.changeOption('livepatch', true); w.vm.changeOption('makeredundant', true)
  expect(w.vm.options).toEqual({ cleanup: true, makeredundant: true, livepatch: false }); w.vm.changeOption('cleanup', false)
  expect(w.vm.options).toEqual({ cleanup: false, makeredundant: false, livepatch: false })
})
test.each(['state', 'owner', 'lookup'])('mixed %s failures block the whole original selection', async kind => {
  const w = await mount(); if (kind === 'state') vpcs.b.state = 'Inactive'
  else if (kind === 'owner') vpcs.b.account = 'foreign'
  else delete vpcs.b
  await w.vm.run(); expect(postAPI).not.toHaveBeenCalled(); expect(w.vm.started).toBe(false)
})
test('a mixed redundant selection cannot convert and a stopped router cannot patch', async () => {
  vpcs.b.redundantvpcrouter = true; const w = await mount(); expect(w.vm.capabilities.makeredundant).toBe(false)
  routerState = 'Stopped'; await w.vm.refreshTargets(); expect(w.vm.capabilities.livepatch).toBe(false); expect(w.vm.canStart).toBe(true)
})
test('unbound pending VPC jobs conservatively block writes', async () => {
  pending = [{ jobid: 'external', jobstatus: 0, jobinstancetype: 'Vpc', cmd: 'RestartVPCCmd' }]
  const w = await mount(); expect(w.vm.canStart).toBe(false); await w.vm.run(); expect(postAPI).not.toHaveBeenCalled()
})
test.each(['http', 'job'])('partial %s failure retries only failed targets and preserves attempt IDs', async kind => {
  const w = await mount()
  if (kind === 'http') postAPI.mockImplementation(async (cmd, p) => { jobs[p.customjobid] = p.id; if (p.id === 'b') throw Object.assign(new Error('Server rejected b'), { response: { status: 431, data: { restartvpcresponse: { errorcode: 431, errortext: 'Server rejected b' } } } }); return { restartvpcresponse: { jobid: p.customjobid } } })
  else query.mockImplementation(id => ({ jobid: id, userid: 'u', cmd: 'RestartVPCCmd', jobstatus: jobs[id] === 'b' ? 2 : 1, jobresult: jobs[id] === 'b' ? { errortext: 'Backend failed b' } : { success: true } }))
  await w.vm.run(); expect(w.vm.rows.map(r => r.status)).toEqual(['success', 'failed']); const failedJob = w.vm.rows[1].jobId
  query.mockImplementation(id => ({ jobid: id, cmd: 'RestartVPCCmd', userid: 'u', jobstatus: 1, jobresult: { success: true } }))
  postAPI.mockImplementation(async (cmd, p) => { jobs[p.customjobid] = p.id; return { restartvpcresponse: { jobid: p.customjobid } } })
  await w.vm.run(w.vm.failedIds); expect(postAPI.mock.calls.map(c => c[1].id)).toEqual(['a', 'b', 'b'])
  expect(w.vm.rows[1].history.map(x => x.status)).toEqual(['failed', 'success']); expect(w.vm.rows[1].history[0].jobId).toBe(failedJob)
})
test('a final lookup failure rechecks the existing job without a new POST', async () => {
  const w = await mount([a]); finalFailure = true; await w.vm.run(); expect(w.vm.rows[0].status).toBe('unknown'); const id = w.vm.rows[0].jobId
  finalFailure = false; await w.vm.recheck(w.vm.rows[0]); expect(w.vm.rows[0].jobId).toBe(id); expect(w.vm.succeeded).toBe(1); expect(postAPI).toHaveBeenCalledTimes(1)
})
test('an accepted response lost in transit recovers by the pre-saved custom job ID', async () => {
  const w = await mount([a]); postAPI.mockImplementation(async (cmd, p) => { jobs[p.customjobid] = p.id; throw new Error('Lost response') })
  await w.vm.run(); expect(w.vm.succeeded).toBe(1); expect(postAPI).toHaveBeenCalledTimes(1); expect(query).toHaveBeenCalledWith(w.vm.rows[0].jobId)
})
test('uncertain first target stops the rest; re-open restores receipt then retries only unsubmitted target', async () => {
  const w = await mount(); let release; query.mockImplementation(() => new Promise(resolve => { release = resolve }))
  const run = w.vm.run(); await flushPromises(); release({ jobid: w.vm.rows[0].jobId, cmd: 'RestartVPCCmd', userid: 'u', jobstatus: 1, jobresult: {} }); await run
  expect(w.vm.rows.map(r => r.status)).toEqual(['unknown', 'failed']); const jobId = w.vm.rows[0].jobId; w.unmount()
  const reopened = await mount(); expect(reopened.vm.rows[0].jobId).toBe(jobId); expect(reopened.vm.unsettled).toBe(true)
  query.mockImplementation(id => ({ jobid: id, cmd: 'RestartVPCCmd', userid: 'u', jobstatus: 1, jobresult: { success: true } }))
  await reopened.vm.recheck(reopened.vm.rows[0]); await reopened.vm.run(reopened.vm.failedIds)
  expect(postAPI.mock.calls.map(c => c[1].id)).toEqual(['a', 'b']); expect(reopened.vm.succeeded).toBe(2)
})
test('a failed conversion can safely finish the already applied default offering transition', async () => {
  const w = await mount([a]); w.vm.changeOption('makeredundant', true)
  query.mockImplementation(id => { vpcs.a.redundantvpcrouter = true; vpcs.a.vpcofferingid = 'r'; return { jobid: id, cmd: 'RestartVPCCmd', jobstatus: 2, jobresult: { errortext: 'Router start failed after conversion' } } })
  await w.vm.run(); expect(w.vm.failedIds).toEqual(['a'])
  query.mockImplementation(id => ({ jobid: id, cmd: 'RestartVPCCmd', jobstatus: 1, jobresult: { success: true } }))
  await w.vm.run(w.vm.failedIds); expect(w.vm.succeeded).toBe(1); expect(postAPI).toHaveBeenCalledTimes(2)
})
test('navigation while the first job runs restores the remaining target as unsubmitted and retryable', async () => {
  const w = await mount(); let release
  query.mockImplementation(() => new Promise(resolve => { release = resolve }))
  const running = w.vm.run(); await flushPromises(); const firstJob = w.vm.rows[0].jobId
  route.fullPath = '/vpc/other'; await flushPromises()
  release({ jobid: firstJob, cmd: 'RestartVPCCmd', userid: 'u', jobstatus: 1, jobresult: { success: true } }); await running; w.unmount()
  const reopened = await mount()
  expect(reopened.vm.rows[0].status).toBe('unknown'); expect(reopened.vm.failedIds).toEqual(['b']); expect(reopened.vm.rows[1].history).toEqual([])
  query.mockImplementation(id => ({ jobid: id, cmd: 'RestartVPCCmd', userid: 'u', jobstatus: 1, jobresult: { success: true } }))
  await reopened.vm.recheck(reopened.vm.rows[0]); await reopened.vm.run(reopened.vm.failedIds)
  expect(postAPI.mock.calls.map(c => c[1].id)).toEqual(['a', 'b']); expect(reopened.vm.succeeded).toBe(2)
})
test('a changed offering or owner is never accepted as conversion recovery', async () => {
  const w = await mount([a]); w.vm.changeOption('makeredundant', true); vpcs.a.vpcofferingid = 'unrelated'; await w.vm.run(); expect(postAPI).not.toHaveBeenCalled()
})
test.each(['project', 'route', 'login'])('late response after %s switch cannot submit another target', async kind => {
  const w = await mount(); let release; postAPI.mockImplementation(() => new Promise(resolve => { release = resolve }))
  const run = w.vm.run(); await flushPromises(); await w.vm.run(); expect(postAPI).toHaveBeenCalledTimes(1)
  if (kind === 'project') getters.project.id = 'other'
  else if (kind === 'route') route.fullPath = '/vpc/other'
  else getters.userInfo.id = 'other'
  await flushPromises(); release({ restartvpcresponse: { jobid: w.vm.rows[0].jobId } }); await run
  expect(postAPI).toHaveBeenCalledTimes(1); expect(w.emitted('refresh-data')).toBeUndefined(); expect(w.emitted('close-action')).toHaveLength(1)
})
test('a later target state change records a blocked item without restarting a success', async () => {
  const w = await mount(); query.mockImplementation(id => { vpcs.b.state = 'Inactive'; return { jobid: id, cmd: 'RestartVPCCmd', jobstatus: 1, jobresult: { success: true } } })
  await w.vm.run(); expect(postAPI.mock.calls.map(c => c[1].id)).toEqual(['a']); expect(w.vm.failedIds).toEqual(['b']); expect(w.vm.rows[1].history).toEqual([])
})
test('storage failures block writes and persisted records contain no authentication or router payload', async () => {
  const w = await mount([a]); const saved = JSON.stringify(sessionStorage); expect(saved).not.toContain('secret-token'); expect(saved).not.toContain('router-a')
  const spy = jest.spyOn(Storage.prototype, 'setItem').mockImplementation(() => { throw new Error('quota') }); await w.vm.run(); expect(postAPI).not.toHaveBeenCalled(); expect(w.vm.storageError).toBe(true); spy.mockRestore()
})
test('Ctrl+Enter shares guarded submit path, and successful close clears completed receipt', async () => {
  const w = await mount([a]); await w.find('a-form-stub').trigger('keydown', { key: 'Enter', ctrlKey: true }); await flushPromises(); expect(postAPI).toHaveBeenCalledTimes(1)
  w.vm.submitKey(); await flushPromises(); expect(postAPI).toHaveBeenCalledTimes(1); const key = w.vm.entryKey; w.vm.close(); expect(sessionStorage.getItem(key)).toBeNull()
})
test('closing an unsubmitted blocked selection permits a new reviewed action', async () => {
  const w = await mount(); vpcs.b.state = 'Inactive'; await w.vm.run(); const key = w.vm.entryKey; w.vm.close()
  expect(sessionStorage.getItem(key)).toBeNull(); expect(postAPI).not.toHaveBeenCalled()
})
