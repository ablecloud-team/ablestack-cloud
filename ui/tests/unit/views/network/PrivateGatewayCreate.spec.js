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

import { shallowMount } from '@vue/test-utils'
import { h, reactive, nextTick } from 'vue'
import { v4 as uuid } from 'uuid'
import Dialog from '@/views/network/PrivateGatewayCreate.vue'
import { privateGatewayApis } from '@/utils/privateGateway'
import { getAPI, postAPI } from '@/api'
jest.mock('@/api', () => ({ getAPI: jest.fn(), postAPI: jest.fn() }))
jest.mock('uuid', () => ({ v4: jest.fn() }))
const vpc = { id: 'vpc', name: 'Gateway test', account: 'owner', domainid: 'domain', zoneid: 'zone', state: 'Enabled' }
const physical = { id: 'physical', name: 'Physical', zoneid: 'zone', state: 'Enabled' }
const deny = { id: 'deny', name: 'default_deny' }; const allow = { id: 'allow', name: 'default_allow' }
const network = { id: 'network', name: 'Owner L2', account: 'owner', domainid: 'domain', zoneid: 'zone', type: 'L2', state: 'Setup' }
const dialog = { props: ['visible', 'closable'], render () { return this.visible ? h('section', [this.$slots.default?.(), h('footer', this.$slots.footer?.())]) : null } }
const actions = { props: ['busy'], render () { return h('fieldset', { disabled: this.busy }, this.$slots.default?.()) } }
const button = { props: ['type', 'disabled', 'loading'], render () { return h('button', { class: this.type, disabled: this.disabled || this.loading }, this.$slots.default?.()) } }
const form = { methods: { validate: () => Promise.resolve() }, render () { return h('form', this.$slots.default?.()) } }
const drain = async () => { for (let i = 0; i < 50; i++) { await Promise.resolve(); await nextTick() } }
let wrappers, serial, outcome, lookupFailure, resultFailure, rows, submitted, queryFailure
const entity = () => ({ ...vpc, vpcid: vpc.id, id: 'gateway-' + serial, ipaddress: submitted.ipaddress, gateway: submitted.gateway, netmask: submitted.netmask, aclid: submitted.aclid, sourcenatsupported: submitted.sourcenatsupported, physicalnetworkid: submitted.physicalnetworkid, vlan: 'vlan://' + submitted.vlan, associatednetworkid: submitted.associatednetworkid, state: 'Ready' })
function lookup (command, params) {
  if (lookupFailure === command || (resultFailure && command === 'listPrivateGateways' && params.id)) return Promise.reject(Error('503 lookup'))
  if (command === 'queryAsyncJobResult') return queryFailure ? Promise.reject(Error('503 query')) : Promise.resolve({ queryasyncjobresultresponse: { jobid: params.jobid, userid: 'user', cmd: 'CreatePrivateGatewayByAdminCmd', jobstatus: outcome, jobresult: outcome === 2 ? { errorcode: 530, errortext: 'Private gateway apply failed' } : { privategateway: entity() } } })
  const map = { listVPCs: ['vpc', [vpc]], listNetworks: ['network', rows.networks], listNetworkACLLists: ['networkacllist', rows.acls], listPhysicalNetworks: ['physicalnetwork', [physical]], listPrivateGateways: ['privategateway', params.id ? [entity()] : rows.gateways] }[command]
  return Promise.resolve({ [command.toLowerCase() + 'response']: { [map[0]]: map[1] } })
}
beforeEach(() => {
  jest.clearAllMocks(); sessionStorage.clear(); wrappers = []; serial = 0; outcome = 1; lookupFailure = ''; resultFailure = false; submitted = {}; queryFailure = false
  rows = { networks: [network, { ...network, id: 'foreign', account: 'other' }, { ...network, id: 'foreign-zone', zoneid: 'other' }, { ...network, id: 'foreign-project', projectid: 'other' }], acls: [allow, deny], gateways: [] }
  uuid.mockImplementation(() => '00000000-0000-4000-8000-' + String(++serial).padStart(12, '0'))
  getAPI.mockImplementation(lookup)
  postAPI.mockImplementation((c, p) => { submitted = p; return Promise.resolve({ createprivategatewayresponse: { jobid: p.customjobid, id: 'gateway-' + serial } }) })
})
afterEach(() => { wrappers.forEach(w => w.unmount()); jest.restoreAllMocks(); jest.useRealTimers() })
async function open (role = 'Admin', options = {}) {
  const getters = reactive({ project: { id: '' }, userInfo: { id: 'user', roleid: 'role', roletype: role }, apis: Object.fromEntries([...privateGatewayApis, 'listPhysicalNetworks'].map(k => [k, { params: [{ name: 'vlan' }, { name: 'bypassvlanoverlapcheck' }] }])) })
  const route = reactive({ fullPath: '/vpc/vpc?tab=pgw' }); const state = reactive({ user: { token: 'session' } })
  const w = shallowMount(Dialog, { props: { resource: { ...vpc } }, global: { mocks: { $t: k => k, $store: { getters, state }, $route: route, $message: { success: jest.fn() } }, stubs: { MoldDialog: dialog, MoldDialogActions: actions, AButton: button, AForm: form, ASpin: { render () { return h('div', this.$slots.default?.()) } } } }, ...options })
  wrappers.push(w); await drain(); return { w, getters, route, state }
}
const fill = w => Object.assign(w.vm.draft, { physicalnetwork: 'physical', vlan: '123', associatednetworkid: 'network', ipaddress: '10.2.3.2', gateway: '10.2.3.1', netmask: '255.255.255.0' })
test('initial, cached reopen and explicit reload always default to deny and preserve explicit allow', async () => {
  let { w } = await open(); expect(w.vm.draft.acl).toBe('deny'); w.vm.draft.acl = 'allow'; fill(w); await drain(); w.vm.close(); w.unmount()
  ;({ w } = await open()); expect(w.vm.draft.acl).toBe('allow'); expect(w.vm.draft.vlan).toBe('123'); await w.vm.load(); expect(w.vm.draft.acl).toBe('allow')
  const data = sessionStorage.getItem(w.vm.entryKey); expect(data).not.toContain('sessionkey'); expect(data).not.toContain('token'); expect(data).not.toContain('password')
})
test('first lookup failure is distinct from empty and explicit reload recovers default ACL', async () => {
  lookupFailure = 'listNetworkACLLists'; const { w } = await open(); expect(w.vm.loaded).toBe(false); expect(w.vm.loadError).toContain('503'); expect(w.vm.fieldsDisabled).toBe(true)
  lookupFailure = ''; await w.vm.load(); expect(w.vm.draft.acl).toBe('deny'); expect(w.vm.loadError).toBe(''); expect(w.vm.error).toBe('')
})
test('empty or unavailable ACL never falls back to allow or silently replaces a selection', async () => {
  const { w } = await open(); fill(w); w.vm.draft.acl = 'allow'; rows.acls = [deny]; await w.vm.load(); expect(w.vm.draft.acl).toBe('allow'); await w.vm.submit(); expect(postAPI).not.toHaveBeenCalled(); expect(w.vm.error).toContain('candidate')
  rows.acls = []; await w.vm.load(); expect(w.vm.loadError).toContain('acl.empty')
})
test.each(['Admin', 'User'])('%s sends one explicit ACL and only allowed connection mode, confirms final entity then closes', async role => {
  const { w } = await open(role); fill(w); w.vm.draft.acl = 'allow'; await w.vm.submit()
  expect(postAPI).toHaveBeenCalledTimes(1); expect(w.emitted('success')).toHaveLength(1); expect(w.emitted('cancel')).toHaveLength(1); expect(submitted.aclid).toBe('allow')
  expect(submitted).toMatchObject({ account: 'owner', domainid: 'domain', ignoreproject: true })
  expect(submitted).not.toHaveProperty('networkofferingid')
  expect(submitted).not.toHaveProperty(role === 'Admin' ? 'associatednetworkid' : 'vlan')
  expect(getAPI).toHaveBeenCalledWith('listPrivateGateways', expect.objectContaining({ id: 'gateway-1', vpcid: 'vpc' }), expect.any(Object)); expect(sessionStorage.getItem(w.vm.entryKey)).toBeNull()
})
test('HTTP 431 keeps malformed-IP input, source selections and request ID; same dialog corrected retry succeeds', async () => {
  const { w } = await open(); fill(w)
  postAPI.mockRejectedValueOnce({ response: { status: 431, data: { errorresponse: { errorcode: 431, errortext: 'Invalid format for the ip address parameter' } } } })
  await w.vm.submit(); expect(w.vm.closed).toBe(false); expect(w.vm.draft.vlan).toBe('123'); expect(w.vm.draft.acl).toBe('deny'); expect(w.vm.requestId).not.toBe(''); expect(w.vm.jobId).toBe(''); expect(w.vm.error).toContain('431: Invalid format')
  w.vm.draft.ipaddress = '10.2.3.3'; await w.vm.submit(); expect(postAPI).toHaveBeenCalledTimes(2); expect(w.emitted('success')).toHaveLength(1)
})
test('job failure keeps input, final failed job ID and server details, corrected retry remains guarded', async () => {
  const { w } = await open(); fill(w); outcome = 2; await w.vm.submit(); expect(w.vm.closed).toBe(false); expect(w.vm.error).toContain('530: Private gateway apply failed'); expect(w.vm.lastJobId).not.toBe(''); expect(w.vm.draft.vlan).toBe('123')
  outcome = 1; w.vm.draft.vlan = '124'; await w.vm.submit(); expect(postAPI).toHaveBeenCalledTimes(2); expect(w.emitted('success')).toHaveLength(1)
})
test('result lookup failure retains receipt and same-job retry never posts twice', async () => {
  const { w } = await open(); fill(w); resultFailure = true; await w.vm.submit(); expect(w.vm.jobId).not.toBe(''); expect(w.vm.closed).toBe(false); expect(w.vm.fieldsDisabled).toBe(true)
  resultFailure = false; await w.vm.submit(); expect(postAPI).toHaveBeenCalledTimes(1); expect(w.emitted('success')).toHaveLength(1)
})
test('query outage retains input and receipt across unmount/reopen, recovery uses same job', async () => {
  jest.useFakeTimers(); let { w } = await open(); fill(w); queryFailure = true; const pending = w.vm.submit(); await drain(); jest.advanceTimersByTime(1000); await drain(); jest.advanceTimersByTime(2000); await drain(); await pending
  const id = w.vm.jobId; expect(id).not.toBe(''); expect(w.vm.closed).toBe(false); w.vm.close(); w.unmount(); queryFailure = false
  ;({ w } = await open()); expect(w.vm.jobId).toBe(id); expect(w.vm.draft.vlan).toBe('123'); await w.vm.submit(); expect(postAPI).toHaveBeenCalledTimes(1); expect(w.emitted('success')).toHaveLength(1)
})
test('lost receipt reconciles preallocated custom job ID rather than replaying create', async () => {
  const { w } = await open(); fill(w)
  postAPI.mockImplementationOnce((c, p) => { submitted = p; return Promise.reject(Error('network disconnected')) })
  await w.vm.submit(); expect(w.emitted('success')).toHaveLength(1); expect(postAPI).toHaveBeenCalledTimes(1)
})
test.each(['form', 'footer'])('actual %s Ctrl+Enter, button and cancel remain locked while POST is pending', async surface => {
  const { w } = await open(); fill(w); let finish
  postAPI.mockImplementationOnce((c, p) => { submitted = p; return new Promise(resolve => { finish = () => resolve({ createprivategatewayresponse: { jobid: p.customjobid, id: 'gateway-1' } }) }) })
  await w.get(surface === 'form' ? 'form' : 'footer fieldset').trigger('keydown', { ctrlKey: true, key: 'Enter' }); await drain(); expect(postAPI).toHaveBeenCalledTimes(1)
  await w.vm.submit(); w.vm.close(); expect(w.vm.closed).toBe(false); expect(w.vm.fieldsDisabled).toBe(true); finish(); await drain(); expect(w.emitted('success')).toHaveLength(1)
})
test.each(['source', 'route', 'project', 'login'])('late result after %s transition cannot close/refresh another context', async kind => {
  const { w, getters, route, state } = await open(); fill(w); let finish
  postAPI.mockImplementationOnce((c, p) => { submitted = p; return new Promise(resolve => { finish = () => resolve({ createprivategatewayresponse: { jobid: p.customjobid } }) }) })
  const pending = w.vm.submit(); await drain()
  if (kind === 'source') await w.setProps({ resource: { ...vpc, id: 'other-vpc' } })
  if (kind === 'route') route.fullPath = '/another-page'
  if (kind === 'project') getters.project = { id: 'other-project' }
  if (kind === 'login') state.user.token = 'other-session'
  await drain(); finish(); await pending; expect(w.emitted('success')).toBeUndefined(); expect(w.emitted('cancel')).toHaveLength(1); expect(postAPI).toHaveBeenCalledTimes(1)
})
test('restricted role, foreign owner/project/Zone and changed candidate are denied before dispatch', async () => {
  const { w, getters } = await open('User'); fill(w); expect(w.vm.networks.map(n => n.id)).toEqual(['network'])
  w.vm.draft.associatednetworkid = 'foreign'; await w.vm.submit(); expect(postAPI).not.toHaveBeenCalled()
  getters.project = { id: 'foreign' }; await drain(); await w.vm.submit(); expect(postAPI).not.toHaveBeenCalled()
})
test('removed create permission and stale VPC block the action', async () => {
  const { w, getters } = await open(); fill(w); delete getters.apis.createPrivateGateway; await drain(); await w.vm.submit(); expect(postAPI).not.toHaveBeenCalled(); expect(w.vm.allowed).toBe(false)
})
test.each(['account', 'domainid', 'vpcid', 'zoneid', 'aclid', 'sourcenatsupported', 'state'])('mismatched final %s never becomes a completed gateway', async field => {
  const { w } = await open(); fill(w)
  getAPI.mockImplementation((c, p) => c === 'queryAsyncJobResult' ? Promise.resolve({ queryasyncjobresultresponse: { jobid: p.jobid, userid: 'user', cmd: 'CreatePrivateGatewayByAdminCmd', jobstatus: 1, jobresult: { privategateway: { ...entity(), [field]: 'other' } } } }) : lookup(c, p))
  await w.vm.submit(); expect(w.vm.closed).toBe(false); expect(w.vm.jobId).not.toBe(''); expect(w.vm.error).toContain('result'); expect(w.emitted('success')).toBeUndefined()
})
test('candidate pagination includes an eligible network after a full page and excludes foreign entries', async () => {
  getAPI.mockImplementation((c, p) => c === 'listNetworks' ? Promise.resolve({ listnetworksresponse: { network: p.page === 1 ? Array.from({ length: 100 }, (_, i) => ({ ...network, id: 'foreign-' + i, account: 'other' })) : [network] } }) : lookup(c, p))
  const { w } = await open('User'); expect(w.vm.networks.map(n => n.id)).toEqual(['network']); expect(getAPI).toHaveBeenCalledWith('listNetworks', expect.objectContaining({ page: 2, account: 'owner', domainid: 'domain', zoneid: 'zone', ignoreproject: true }), expect.any(Object))
})
test('empty associated candidates disable submission and keep the form recoverable', async () => {
  rows.networks = []; const { w } = await open('User'); fill(w); expect(w.vm.loadError).toBe(''); expect(w.get('footer button.primary').attributes('disabled')).toBeDefined(); await w.vm.submit(); expect(postAPI).not.toHaveBeenCalled()
})
test('storage failure blocks dispatch before a receipt could be lost', async () => {
  const { w } = await open(); fill(w); jest.spyOn(Storage.prototype, 'setItem').mockImplementation(() => { throw Error('storage full') })
  await drain(); await w.vm.submit(); expect(postAPI).not.toHaveBeenCalled(); expect(w.vm.storageError).toBe(true); expect(w.vm.error).toContain('storage')
})
