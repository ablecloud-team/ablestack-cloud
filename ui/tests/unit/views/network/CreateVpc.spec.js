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
import { h, reactive, nextTick } from 'vue'
import Dialog from '@/views/network/CreateVpc.vue'
import { fixture, getters as makeGetters, response, copy } from '../../../fixtures/vpcCreate'
import { getAPI, postAPI } from '@/api'
jest.mock('@/api', () => ({ getAPI: jest.fn(), postAPI: jest.fn() }))
const drain = async () => { for (let i = 0; i < 60; i++) { await Promise.resolve(); await nextTick() } }
const dialog = { render () { return h('section', this.$slots.default?.()) } }
const actions = { render () { return h('footer', this.$slots.default?.()) } }
const button = { props: ['type', 'disabled', 'loading'], render () { return h('button', { class: this.type, disabled: this.disabled || this.loading }, this.$slots.default?.()) } }
const form = { methods: { validate: () => Promise.resolve(), scrollToField: () => {} }, render () { return h('form', this.$slots.default?.()) } }
let data, wrappers, submitted, poll, failure, override
function lookup (api, p) {
  if (api === failure) return Promise.reject(Error('503 lookup unavailable'))
  if (override) { const result = override(api, p); if (result !== undefined) return result }
  if (api === 'queryAsyncJobResult') return Promise.resolve({ queryasyncjobresultresponse: { jobid: p.jobid, userid: 'u', cmd: 'org.apache.cloudstack.api.command.admin.vpc.CreateVPCCmdByAdmin', jobstatus: 1 } })
  return Promise.resolve(response(data, api, p))
}
beforeEach(() => {
  jest.clearAllMocks(); sessionStorage.clear(); data = fixture(); wrappers = []; submitted = {}; poll = jest.fn(); failure = ''; override = null
  getAPI.mockImplementation(lookup); postAPI.mockImplementation((api, p) => { submitted = p; return Promise.resolve({ createvpcresponse: { jobid: p.customjobid } }) })
})
afterEach(() => { wrappers.forEach(w => w.unmount()); jest.restoreAllMocks() })
async function open (role = 'Admin', project = false, editGetters) {
  const getters = reactive(makeGetters(role)); if (project) getters.project = { id: 'p', name: 'Project' }; if (editGetters) editGetters(getters)
  const route = reactive({ fullPath: '/vpc' }); const state = reactive({ user: { token: 'session' } })
  const w = shallowMount(Dialog, { global: { mocks: { $t: k => k, $store: { getters, state }, $route: route, $pollJob: poll }, stubs: { MoldDialog: dialog, MoldDialogActions: actions, AButton: button, AForm: form } } })
  wrappers.push(w); await drain()
  if (w.vm.selectOwner) { await w.vm.fetchOwnerOptions({ ready: true, selectedAccountType: 'Account', selectedAccount: 'admin', selectedDomain: 'd' }); await drain() }
  return { w, getters, route, state }
}
const fill = w => Object.assign(w.vm.form, { name: 'own-vpc', cidr: '10.9.0.0/24', start: false })
test.each([{}, { count: 0, vpcoffering: [] }, { count: 1 }, { count: 1, vpcoffering: null }])('empty/malformed candidate response %j is recoverable and never dispatches', async body => {
  override = a => a === 'listVPCOfferings' ? Promise.resolve({ listvpcofferingsresponse: body }) : undefined
  const { w } = await open(); fill(w); expect(w.vm.canSubmit).toBe(false); await w.vm.handleSubmit(); expect(postAPI).not.toHaveBeenCalled(); expect(w.vm.form.vpcofferingid).toBeUndefined()
  override = null; await w.vm.reload(); fill(w); expect(w.vm.canSubmit).toBe(true); expect(w.vm.error).toBe('')
})
test('filtered zero, permission loss and HTTP outage stay distinguishable from an empty list', async () => {
  data.offerings.forEach(o => { o.fornsx = true }); const { w } = await open(); expect(w.vm.loadReason).toBe('incompatible')
  failure = 'listVPCOfferings'; await w.vm.reload(); expect(w.vm.loadReason).toBe('lookup'); expect(w.vm.error).toContain('503')
  const noPermission = await open('User', false, g => { delete g.apis.listVPCOfferings }); expect(noPermission.w.vm.loadReason).toBe('permission'); expect(noPermission.w.vm.canSubmit).toBe(false)
})
test('a No-DNS transition clears inputs and independently strips injected hidden fields from the real dispatch', async () => {
  const { w } = await open(); fill(w); w.vm.form.dns1 = '8.8.8.8'; await w.vm.handleVpcOfferingChange('no-dns'); expect(w.vm.form.dns1).toBeUndefined()
  Object.assign(w.vm.form, { dns1: 'stale', ip6dns1: 'stale', bgppeerids: 'stale', fordisplay: false }); await w.vm.handleSubmit()
  expect(postAPI).toHaveBeenCalledTimes(1); for (const k of ['dns1', 'ip6dns1', 'bgppeerids', 'fordisplay']) expect(submitted).not.toHaveProperty(k)
  expect(poll).toHaveBeenCalledWith(expect.objectContaining({ jobId: submitted.customjobid })); expect(w.emitted('close-action')).toHaveLength(1); expect(w.emitted('refresh-data')).toHaveLength(1)
})
test.each([['Admin', false], ['User', false], ['Admin', true], ['User', true]])('%s project=%s submits only the current explicit ownership scope', async (role, project) => {
  const { w } = await open(role, project); fill(w); await w.vm.handleSubmit(); expect(postAPI).toHaveBeenCalledTimes(1)
  expect(submitted).toMatchObject(project ? { projectid: 'p' } : { account: 'admin', domainid: 'd' }); expect(submitted).not.toHaveProperty(project ? 'account' : 'projectid')
  if (project) expect(submitted).not.toHaveProperty('domainid')
})
test('a late old-Zone lookup cannot overwrite a newer Zone and conditional inputs are cleared', async () => {
  const { w } = await open(); fill(w); w.vm.form.dns1 = '8.8.8.8'; let finish
  override = (a, p) => a === 'listVPCOfferings' && p.zoneid === 'z' ? new Promise(resolve => { finish = () => resolve(response(data, a, p)) }) : undefined
  const old = w.vm.fetchOfferings(); await drain(); await w.vm.changeZone('z2'); expect(w.vm.form.cidr).toBeUndefined(); expect(w.vm.form.dns1).toBeUndefined(); expect(w.vm.loaded).toBe(true)
  finish(); await old; expect(w.vm.form.zoneid).toBe('z2'); expect(w.vm.loaded).toBe(true); expect(w.vm.loadingOffering).toBe(false)
})
test('a late owner lookup cannot replace the next owner selection', async () => {
  const { w } = await open(); let finish
  override = (a, p) => a === 'listVPCOfferings' && p.domainid === 'd' ? new Promise(resolve => { finish = () => resolve(response(data, a, p)) }) : undefined
  const old = w.vm.fetchOfferings(); await drain(); await w.vm.fetchOwnerOptions({ ready: true, selectedAccountType: 'Account', selectedAccount: 'other', selectedDomain: 'd2' }); finish(); await old
  expect(w.vm.owner).toEqual({ account: 'other', domainid: 'd2' }); fill(w); await w.vm.handleSubmit(); expect(submitted.account).toBe('other'); expect(submitted.domainid).toBe('d2')
})
test('late AS results are discarded after leaving a Dynamic offering', async () => {
  data.offerings.push({ ...copy(data.offerings[0]), id: 'dynamic', routingmode: 'Dynamic', specifyasnumber: true }); const { w } = await open(); let finish
  override = a => a === 'listASNumbers' ? new Promise(resolve => { finish = () => resolve({ listasnumbersresponse: { asnumber: [{ id: 'as', asnumber: 64512 }] } }) }) : undefined
  const old = w.vm.handleVpcOfferingChange('dynamic'); await drain(); await w.vm.handleVpcOfferingChange('o'); finish(); await old; expect(w.vm.asNumbers).toEqual([]); expect(w.vm.asLoading).toBe(false); expect(w.vm.form.asnumber).toBeUndefined()
})
test.each(['zone', 'offering', 'provider'])('fresh %s changes block POST and require a fresh review', async kind => {
  const { w } = await open(); fill(w)
  if (kind === 'zone') data.zones[0].routedmodeenabled = false
  if (kind === 'offering') data.offerings[0].state = 'Disabled'
  if (kind === 'provider') data.providers[0].state = 'Disabled'
  await w.vm.handleSubmit(); expect(postAPI).not.toHaveBeenCalled(); expect(w.vm.loadReason).toBe('changed'); expect(w.vm.error).toContain('changed')
})
test.each(['route', 'project', 'login', 'role', 'api'])('a %s transition during preflight closes the stale dialog without a POST', async kind => {
  const { w, getters, state, route } = await open(); fill(w); let finish
  override = (a, p) => a === 'listZones' && p.id ? new Promise(resolve => { finish = () => resolve(response(data, a, p)) }) : undefined
  const running = w.vm.handleSubmit(); await drain()
  if (kind === 'route') route.fullPath = '/other'
  if (kind === 'project') getters.project = { id: 'p' }
  if (kind === 'login') state.user.token = 'other'
  if (kind === 'role') getters.userInfo.roleid = 'other'
  if (kind === 'api') delete getters.apis.createVPC
  await drain(); finish(); await running; expect(postAPI).not.toHaveBeenCalled(); expect(w.emitted('close-action')).toHaveLength(1)
})
test('in-flight duplicate clicks and close attempts cannot dispatch a second POST', async () => {
  const { w } = await open(); fill(w); let finish
  postAPI.mockImplementationOnce((a, p) => new Promise(resolve => { submitted = p; finish = () => resolve({ createvpcresponse: { jobid: p.customjobid } }) }))
  const running = w.vm.handleSubmit(); await drain(); await w.vm.handleSubmit(); w.vm.closeAction(); expect(postAPI).toHaveBeenCalledTimes(1); expect(w.vm.closed).toBe(false); finish(); await running; expect(poll).toHaveBeenCalledTimes(1)
})
test('known 431 preserves form inputs and permits a corrected, explicitly retried request', async () => {
  const { w } = await open(); fill(w); w.vm.form.dns1 = '8.8.8.8'
  postAPI.mockRejectedValueOnce({ response: { status: 431, data: { errorresponse: { errorcode: 431, errortext: 'invalid request' } } } }); await w.vm.handleSubmit()
  expect(w.vm.pending).toBeNull(); expect(w.vm.form.name).toBe('own-vpc'); expect(w.vm.form.cidr).toBe('10.9.0.0/24'); expect(w.vm.error).toContain('431'); expect(w.vm.closed).toBe(false)
  await w.vm.handleSubmit(); expect(postAPI).toHaveBeenCalledTimes(2); expect(poll).toHaveBeenCalledTimes(1)
})
test('lost receipt survives reopen, stores no credentials, and rechecks the same job without replay', async () => {
  let { w } = await open(); fill(w); postAPI.mockRejectedValueOnce(Error('connection lost')); await w.vm.handleSubmit(); const id = w.vm.pending.id; const key = w.vm.entryKey
  expect(sessionStorage.getItem(key)).not.toMatch(/password|sessionkey|token/); w.unmount(); ({ w } = await open()); expect(w.vm.pending.id).toBe(id)
  await w.vm.handleSubmit(); expect(postAPI).toHaveBeenCalledTimes(1); expect(poll).toHaveBeenCalledWith(expect.objectContaining({ jobId: id })); expect(sessionStorage.getItem(key)).toBeNull()
})
test('Mold synchronous 530 with no accepted job keeps Source NAT input and permits explicit corrected retry', async () => {
  const { w } = await open(); fill(w); w.vm.form.sourcenatipaddress = '10.253.31.9'
  postAPI.mockRejectedValueOnce({ response: { status: 530, data: { createvpcresponse: { errorcode: 530, cserrorcode: 4250, errortext: 'new source NAT address cannot be acquired' } } } }); await w.vm.handleSubmit()
  expect(w.vm.pending).toBeNull(); expect(w.vm.form.sourcenatipaddress).toBe('10.253.31.9'); expect(w.vm.form.cidr).toBe('10.9.0.0/24'); expect(w.vm.closed).toBe(false)
  w.vm.form.sourcenatipaddress = ''; await w.vm.handleSubmit(); expect(postAPI).toHaveBeenCalledTimes(2); expect(poll).toHaveBeenCalledTimes(1)
})
test('a proxy 503 stays unconfirmed and never enables creation replay', async () => {
  const { w } = await open(); fill(w); postAPI.mockRejectedValueOnce({ response: { status: 503, data: { errorresponse: { errorcode: 503, errortext: 'proxy unavailable' } } } }); await w.vm.handleSubmit()
  expect(w.vm.pending).not.toBeNull(); failure = 'queryAsyncJobResult'; await w.vm.handleSubmit(); expect(postAPI).toHaveBeenCalledTimes(1); expect(poll).not.toHaveBeenCalled(); expect(w.vm.pending).not.toBeNull()
})
test.each(['cmd', 'userid', 'jobid'])('a mismatched recovered %s is never attached to this creation', async key => {
  const { w } = await open(); fill(w); postAPI.mockRejectedValueOnce(Error('connection lost')); await w.vm.handleSubmit()
  override = (a, p) => a === 'queryAsyncJobResult' ? Promise.resolve({ queryasyncjobresultresponse: { jobid: p.jobid, userid: 'u', cmd: 'CreateVPCCmdByAdmin', jobstatus: 1, [key]: 'foreign' } }) : undefined
  await w.vm.recheck(); expect(poll).not.toHaveBeenCalled(); expect(w.vm.pending).not.toBeNull(); expect(w.vm.error).toContain('receipt'); expect(postAPI).toHaveBeenCalledTimes(1)
})
test('storage failure blocks creation before the request can be lost', async () => {
  const { w } = await open(); fill(w); jest.spyOn(Storage.prototype, 'setItem').mockImplementation(() => { throw Error('quota') }); await w.vm.handleSubmit(); expect(postAPI).not.toHaveBeenCalled(); expect(w.vm.storageError).toBe(true)
})
test('DualStack and external environment choices require explicit acknowledgement of unverified readiness', async () => {
  data.offerings[0].internetprotocol = 'DualStack'; const { w } = await open(); fill(w); expect(w.vm.canSubmit).toBe(false); w.vm.advancedAcknowledged = true; expect(w.vm.canSubmit).toBe(true)
  await w.vm.handleVpcOfferingChange('no-dns'); expect(w.vm.advancedAcknowledged).toBe(false)
})
