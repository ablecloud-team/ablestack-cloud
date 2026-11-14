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
import MigrateVpc from '@/views/network/MigrateVpc'
import { getAPI, postAPI } from '@/api'
import { fixture, copy, partial, completed } from '../../../fixtures/vpcMigration'
import { vpcMigrationApis, readVpcMigrationContext, diagnoseVpcMigrationPlan } from '@/utils/vpcMigration'
jest.mock('@/api', () => ({ getAPI: jest.fn(), postAPI: jest.fn() }))
jest.mock('@/utils/vpcMigration', () => ({ ...jest.requireActual('@/utils/vpcMigration'), readVpcMigrationContext: jest.fn(), diagnoseVpcMigrationPlan: jest.fn() }))
let context, wrappers, route, getters, query, submitted, failureMode
async function mount () {
  route = reactive({ fullPath: '/vpc/v' }); getters = reactive({ userInfo: { id: 'u', roleid: 'r', roletype: 'Admin' }, project: {}, apis: Object.fromEntries(vpcMigrationApis.map(a => [a, {}])) })
  const w = shallowMount(MigrateVpc, { props: { resource: copy(fixture().vpc) }, global: { stubs: { MoldDialog: { template: '<div><slot /></div>' }, MoldDialogActions: { template: '<div><slot /></div>' } }, mocks: { $t: x => x, $route: route, $store: { getters, state: { user: { token: 'private-auth-token' } } } } } })
  wrappers.push(w); await flushPromises(); return w
}
async function ready (w) { await w.vm.selectTarget('o2'); await w.vm.selectTier('t1', 'n2'); await w.vm.selectTier('t2', 'n2'); w.vm.acknowledged = true }
beforeEach(() => {
  sessionStorage.clear(); wrappers = []; context = fixture(); submitted = null; failureMode = ''
  readVpcMigrationContext.mockReset().mockImplementation(async () => copy(context))
  diagnoseVpcMigrationPlan.mockReset().mockResolvedValue()
  query = jest.fn().mockImplementation(async id => {
    if (failureMode === 'job') { context = partial(context); return { jobid: id, userid: 'u', cmd: 'org.apache.cloudstack.api.command.admin.network.MigrateVPCCmd', jobstatus: 2, jobresultcode: 431, jobresult: { errortext: 'Second Tier failed' } } }
    context = completed(context, submitted)
    return { jobid: id, userid: 'u', cmd: 'org.apache.cloudstack.api.command.admin.network.MigrateVPCCmd', jobstatus: 1, jobresult: { vpc: copy(context.vpc) } }
  })
  getAPI.mockReset().mockImplementation(async (api, p) => ({ queryasyncjobresultresponse: await query(p.jobid) }))
  postAPI.mockReset().mockImplementation(async (api, p) => {
    submitted = { vpcId: p.vpcid, offeringId: p.vpcofferingid, resume: p.resume, rows: [0, 1].map(i => ({ id: p[`tiernetworkofferings[${i}].networkid`], offeringId: p[`tiernetworkofferings[${i}].networkofferingid`], physicalId: 'ph' })) }
    if (failureMode === 'http') throw Object.assign(new Error('Rejected request'), { response: { status: 431, data: { migratevpcresponse: { errorcode: 431, errortext: 'Rejected request' } } } })
    if (failureMode === 'transport') throw new Error('Lost receipt')
    return { migratevpcresponse: { jobid: p.customjobid } }
  })
})
afterEach(() => { wrappers.forEach(w => w.unmount()); jest.restoreAllMocks() })

test('requires every Tier and impact acknowledgement, then sends one complete explicit request', async () => {
  const w = await mount(); expect(w.vm.canStart).toBe(false); await w.vm.selectTarget('o2'); await w.vm.selectTier('t1', 'n2'); w.vm.acknowledged = true
  await w.vm.start(); expect(postAPI).not.toHaveBeenCalled(); await w.vm.selectTier('t2', 'n2'); expect(w.vm.acknowledged).toBe(false)
  w.vm.acknowledged = true; await w.vm.start()
  expect(postAPI).toHaveBeenCalledTimes(1); expect(postAPI.mock.calls[0][1]).toMatchObject({ vpcid: 'v', vpcofferingid: 'o2', resume: false, 'tiernetworkofferings[0].networkid': 't1', 'tiernetworkofferings[1].networkid': 't2' })
  expect(w.vm.status).toBe('success'); expect(w.emitted('refresh-data')).toHaveLength(1)
})
test.each(['tier', 'owner', 'offering', 'read'])('fresh %s change prevents POST and requires a new review', async kind => {
  const w = await mount(); await ready(w)
  if (kind === 'tier') context.tiers[1].network.state = 'Shutdown'
  else if (kind === 'owner') context.vpc.account = 'other'
  else if (kind === 'offering') context.networkOfferings[1].networkrate = 999
  else readVpcMigrationContext.mockRejectedValue(new Error('Read failed'))
  await w.vm.start(); expect(postAPI).not.toHaveBeenCalled(); expect(w.vm.canStart).toBe(false); expect(w.vm.mappings).toEqual({ t1: 'n2', t2: 'n2' })
})
test('provider verification failure prevents acknowledgement and submission', async () => {
  diagnoseVpcMigrationPlan.mockRejectedValue(new Error('Provider disabled')); const w = await mount(); await ready(w)
  expect(w.vm.diagnostic).toBe('failed'); expect(w.vm.canStart).toBe(false); await w.vm.start(); expect(postAPI).not.toHaveBeenCalled()
})
test('known partial failure preserves history and resumes server checkpoint with fixed applied Tier', async () => {
  const w = await mount(); await ready(w); failureMode = 'job'; await w.vm.start()
  const first = w.vm.jobId; expect(w.vm.status).toBe('failed'); expect(w.vm.context.previous.id).toBe('old-vpc')
  await w.vm.selectTarget('o1'); await w.vm.selectTier('t1', 'n1'); expect(w.vm.targetId).toBe('o2'); expect(w.vm.mappings.t1).toBe('n2')
  failureMode = ''; w.vm.acknowledged = true; await w.vm.start()
  expect(postAPI).toHaveBeenCalledTimes(2); expect(postAPI.mock.calls[1][1]).toMatchObject({ resume: true, vpcofferingid: 'o2', 'tiernetworkofferings[0].networkofferingid': 'n2', 'tiernetworkofferings[1].networkofferingid': 'n2' })
  expect(w.vm.status).toBe('success'); expect(w.vm.history.map(h => h.status)).toEqual(['failed', 'success']); expect(w.vm.history[0].jobId).toBe(first)
})
test('HTTP rejection keeps selections and permits only explicit newly validated retry', async () => {
  const w = await mount(); await ready(w); failureMode = 'http'; await w.vm.start(); expect(w.vm.status).toBe('failed'); const first = w.vm.jobId
  expect(w.vm.mappings).toEqual({ t1: 'n2', t2: 'n2' }); failureMode = ''; w.vm.acknowledged = true; await w.vm.start()
  expect(w.vm.status).toBe('success'); expect(postAPI).toHaveBeenCalledTimes(2); expect(w.vm.history[0]).toMatchObject({ jobId: first, status: 'failed' })
})
test('lost receipt and reopen use the pre-saved ID, never a second POST', async () => {
  const w = await mount(); await ready(w); failureMode = 'transport'; await w.vm.start(); expect(w.vm.status).toBe('unknown'); const id = w.vm.jobId; w.vm.close(); w.unmount()
  const reopened = await mount(); expect(reopened.vm.jobId).toBe(id); expect(reopened.vm.status).toBe('unknown'); expect(reopened.vm.canStart).toBe(false)
  failureMode = ''; await reopened.vm.settle(); expect(reopened.vm.status).toBe('success'); expect(postAPI).toHaveBeenCalledTimes(1)
})
test('final resource lookup failure remains uncertain and can recheck the same successful job', async () => {
  const w = await mount(); await ready(w); let queried = false
  const original = query.getMockImplementation(); query.mockImplementation(async id => { queried = true; return original(id) })
  readVpcMigrationContext.mockImplementation(async () => { if (queried) throw new Error('Final lookup timeout'); return copy(context) })
  await w.vm.start(); expect(w.vm.status).toBe('unknown'); const id = w.vm.jobId
  readVpcMigrationContext.mockImplementation(async () => copy(context)); await w.vm.settle()
  expect(w.vm.status).toBe('success'); expect(w.vm.jobId).toBe(id); expect(postAPI).toHaveBeenCalledTimes(1)
})
test('tracker uncertainty blocks resume until a same-job query succeeds', async () => {
  const w = await mount(); await ready(w); const tracker = jest.spyOn(w.vm.tracker, 'track').mockResolvedValueOnce({ jobstatus: null, trackingStatus: 'unknown' })
  await w.vm.start(); const id = w.vm.jobId; expect(w.vm.status).toBe('unknown'); await w.vm.start(); expect(postAPI).toHaveBeenCalledTimes(1)
  tracker.mockRestore(); await w.vm.settle(); expect(w.vm.jobId).toBe(id); expect(w.vm.status).toBe('success'); expect(postAPI).toHaveBeenCalledTimes(1)
})
test.each(['route', 'project', 'login', 'role'])('late response after %s change cannot affect a new view', async kind => {
  const w = await mount(); await ready(w); let release
  postAPI.mockImplementation((api, p) => new Promise(resolve => { release = () => resolve({ migratevpcresponse: { jobid: p.customjobid } }) }))
  const run = w.vm.start(); await flushPromises(); await w.vm.start(); expect(postAPI).toHaveBeenCalledTimes(1)
  if (kind === 'route') route.fullPath = '/vpc/other'
  else if (kind === 'project') getters.project.id = 'other'
  else if (kind === 'login') getters.userInfo.id = 'other'
  else getters.userInfo.roletype = 'User'
  await flushPromises(); release(); await run
  expect(getAPI).not.toHaveBeenCalled(); expect(w.emitted('refresh-data')).toBeUndefined(); expect(w.emitted('close-action')).toHaveLength(1)
})
test('storage failure blocks POST and records never include authentication payloads', async () => {
  const w = await mount(); await ready(w); expect(JSON.stringify(sessionStorage)).not.toContain('private-auth-token')
  jest.spyOn(Storage.prototype, 'setItem').mockImplementation(() => { throw new Error('Quota') })
  await w.vm.start(); expect(postAPI).not.toHaveBeenCalled(); expect(w.vm.storageError).toBe(true)
})
test('Ctrl+Enter uses the guarded action; successful close removes only its receipt', async () => {
  const w = await mount(); await ready(w); sessionStorage.setItem('unrelated', 'preserve')
  await w.find('a-form-stub').trigger('keydown', { key: 'Enter', ctrlKey: true }); await flushPromises()
  expect(postAPI).toHaveBeenCalledTimes(1); expect(w.vm.status).toBe('success'); w.vm.submitKey(); await flushPromises(); expect(postAPI).toHaveBeenCalledTimes(1)
  w.vm.close(); expect(sessionStorage.getItem(w.vm.entryKey)).toBeNull(); expect(sessionStorage.getItem('unrelated')).toBe('preserve')
})
test('all API reads including job queries have bounded timeouts', async () => {
  const w = await mount(); await ready(w); await w.vm.start()
  expect(getAPI.mock.calls.every(c => c[2].timeout === 15000 && c[2].preserveOnFailure && c[2].backgroundJob)).toBe(true)
})
