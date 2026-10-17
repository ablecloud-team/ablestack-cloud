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

import { flushPromises, shallowMount } from '@vue/test-utils'
import Delete from '@/views/compute/DeleteCniConfiguration.vue'
import Autogen from '@/views/AutogenView.vue'
import ListView from '@/components/view/ListView.vue'
import compute from '@/config/section/compute'
import { getAPI, postAPI } from '@/api'
import { cniDeleteTargets, cniDeleteIdentity, cniDeleteLookup, cniDeleteParams, cniDetailLink, cniListLocation } from '@/utils/cniDeletion'

jest.mock('@/api', () => ({ getAPI: jest.fn(), postAPI: jest.fn() }))
const one = { id: '11111111-1111-4111-a111-111111111111', name: '한글 <CNI>', account: 'A', domainid: 'dddddddd-dddd-4ddd-addd-dddddddddddd', domain: 'D', projectid: undefined, project: undefined }
const two = { ...one, id: '22222222-2222-4222-a222-222222222222' }
const response = (rows = [one]) => ({ listcniconfigurationresponse: { count: rows.length, cniconfig: rows } })
const usage = (target = one, count = 0) => ({ listkubernetesclustersresponse: { count, kubernetescluster: count ? [{ ...target, id: two.id, state: 'Created', cniconfigurationid: target.id, virtualmachines: [] }] : [] } })
const error = (code, reason = 'denied/in use') => ({ response: { status: code, data: { errorresponse: { errorcode: code, errortext: reason } } } })
function model (targets = [one]) {
  const c = { currentAction: { api: 'deleteCniConfiguration', cniTargets: targets }, $route: { path: '/cniconfiguration', fullPath: '/cniconfiguration', params: {}, query: {} }, $store: { getters: { apis: { listCniConfiguration: {}, deleteCniConfiguration: {}, listKubernetesClusters: { params: [{ name: 'cniconfigurationid' }] } }, userInfo: { id: 'user' }, project: {} }, state: { user: { token: 'session' } } }, $t: key => key, $emit: jest.fn() }
  Object.assign(c, Delete.data.call(c)); for (const [k, fn] of Object.entries(Delete.methods)) c[k] = fn.bind(c); for (const [k, fn] of Object.entries(Delete.computed)) Object.defineProperty(c, k, { get: () => fn.call(c) }); c.session = c.sessionKey(); return c
}
let c
beforeEach(() => { jest.clearAllMocks(); c = model(); getAPI.mockImplementation((api, p) => Promise.resolve(api === 'listKubernetesClusters' ? usage(p.cniconfigurationid === two.id ? two : one) : response([p.id === two.id ? two : one]))); postAPI.mockResolvedValue({ deletecniconfigurationresponse: { success: true } }) })
test('selection snapshot is immutable and list owner filters are exclusive', () => {
  const row = { ...one }; const targets = cniDeleteTargets({ invokedAsGroupAction: true }, [row]); row.id = two.id; expect(targets).toEqual([one]); expect(Object.isFrozen(targets[0])).toBe(true)
  expect(cniDeleteParams(one)).toEqual({ id: one.id, account: 'A', domainid: one.domainid, ignoreproject: true }); expect(cniDeleteParams({ ...one, projectid: two.id })).toEqual({ id: one.id, projectid: two.id })
})
test.each([{}, { ...one, id: 'bad' }, { ...one, domainid: '' }, { ...one, projectid: 'bad' }])('invalid target blocks lookup', target => expect(cniDeleteLookup(target)).toBeNull())
test.each([{}, { listcniconfigurationresponse: { count: 2, cniconfig: [one] } }, { listcniconfigurationresponse: { count: '1', cniconfig: [one] } }, { listcniconfigurationresponse: { count: 1 } }, { listcniconfigurationresponse: { cniconfig: {} } }])('malformed CNI identity fails closed', json => expect(cniDeleteIdentity(json, one)).toBe('error'))
test.each([{ ...one, account: 'Other' }, { ...one, projectid: two.id }, { ...one, domainid: two.id }, { ...one, name: 'Other' }])('wrong owner or name cannot confirm absence', row => expect(cniDeleteIdentity(response([row]), one, true)).toBe('mismatch'))
test('complete owner-scoped same-name replacement confirms original absence', () => { expect(cniDeleteIdentity(response([two]), one, true)).toBe('missing'); expect(cniDeleteIdentity(response([two]), one)).toBe('mismatch') })
test('mount preflight reads identity, exact usage and identity again', async () => {
  await c.loadReferences(); expect(c.rows[0].references).toEqual({ clusters: 0 }); expect(getAPI.mock.calls.map(r => r[0])).toEqual(['listCniConfiguration', 'listKubernetesClusters', 'listCniConfiguration'])
  expect(getAPI.mock.calls[1][1]).toEqual({ cniconfigurationid: one.id, account: 'A', domainid: one.domainid, ignoreproject: true, listall: true, page: 1, pagesize: 1 })
})
test('project requests carry only explicit project and actual CNI UUID', async () => {
  const target = { ...one, projectid: two.id }; c = model([target]); getAPI.mockImplementation((api) => Promise.resolve(api === 'listKubernetesClusters' ? usage(target) : response([target]))); await c.loadReferences(); await c.execute()
  expect(postAPI.mock.calls[0][1]).toEqual({ id: one.id, projectid: two.id }); expect(getAPI.mock.calls.filter(r => r[0] === 'listKubernetesClusters').every(r => r[1].projectid === two.id && !r[1].account)).toBe(true)
})
test('initial unchecked rows cannot delete; cancellation never sends POST', async () => { await c.execute(); c.close(); expect(postAPI).not.toHaveBeenCalled(); expect(c.$emit.mock.calls[0][1]).toEqual({ started: false, removed: [], retained: [one.id] }) })
test.each(['no-api', 'no-filter', 'failure', 'mismatch'])('usage %s is not treated as zero', async kind => {
  if (kind === 'no-api') delete c.$store.getters.apis.listKubernetesClusters
  if (kind === 'no-filter') c.$store.getters.apis.listKubernetesClusters.params = []
  if (kind === 'failure') getAPI.mockImplementation((api) => api === 'listKubernetesClusters' ? Promise.reject(error(503)) : Promise.resolve(response()))
  if (kind === 'mismatch') getAPI.mockImplementation((api) => Promise.resolve(api === 'listKubernetesClusters' ? usage(two, 1) : response()))
  await c.loadReferences(); await c.execute(); expect(c.rows[0].references).toBe(false); expect(postAPI).not.toHaveBeenCalled(); expect(c.eligibleRows).toHaveLength(0)
})
test('in-use count blocks button and refresh recovers after reference removal', async () => {
  getAPI.mockImplementation((api) => Promise.resolve(api === 'listKubernetesClusters' ? usage(one, 1) : response())); await c.loadReferences(); await c.execute(); expect(c.eligibleRows).toHaveLength(0); expect(postAPI).not.toHaveBeenCalled()
  getAPI.mockImplementation((api) => Promise.resolve(api === 'listKubernetesClusters' ? usage() : response())); await c.loadReferences(); await c.execute(); expect(postAPI).toHaveBeenCalledTimes(1); expect(c.$emit.mock.calls[0][1].removed).toEqual([one.id])
})
test('submit rechecks newly created reference before POST', async () => {
  await c.loadReferences(); getAPI.mockImplementation((api) => Promise.resolve(api === 'listKubernetesClusters' ? usage(one, 1) : response())); await c.execute(); expect(c.rows[0].references.clusters).toBe(1); expect(postAPI).not.toHaveBeenCalled(); expect(c.$emit).not.toHaveBeenCalled()
})
test('server reference race retains target/error and reloads current usage', async () => {
  await c.loadReferences(); postAPI.mockImplementation(() => { getAPI.mockImplementation((api) => Promise.resolve(api === 'listKubernetesClusters' ? usage(one, 1) : response())); return Promise.reject(error(431, 'CNI in use')) }); await c.execute()
  expect(c.rows[0].status).toBe('failed'); expect(c.rows[0].reason).toContain('CNI in use'); expect(c.rows[0].references.clusters).toBe(1); expect(c.$emit).not.toHaveBeenCalled(); await c.execute(); expect(postAPI).toHaveBeenCalledTimes(1)
})
test('partial completion retains failed original and retries only that UUID', async () => {
  c = model([one, two]); await c.loadReferences(); postAPI.mockImplementation((api, p) => p.id === two.id ? Promise.reject(error(503, 'service failure')) : Promise.resolve({ deletecniconfigurationresponse: { success: true } })); await c.execute(); expect(c.rows.map(r => r.status)).toEqual(['success', 'failed']); expect(c.$emit).not.toHaveBeenCalled()
  postAPI.mockResolvedValue({ deletecniconfigurationresponse: { success: true } }); await c.execute(); expect(postAPI.mock.calls.map(r => r[1].id)).toEqual([one.id, two.id, two.id]); expect(c.$emit.mock.calls[0][1].removed).toEqual([one.id, two.id])
})
test('all success closes immediately; detail does not refresh the deleted resource', async () => {
  c.$route.params.id = one.id; c.session = c.sessionKey(); await c.loadReferences(); await c.execute(); expect(c.$emit.mock.calls).toEqual([['close-action', { started: true, removed: [one.id], retained: [] }]])
})
test('busy locks duplicate delete, navigation, Escape/close and unload', async () => {
  await c.loadReferences(); let release; postAPI.mockImplementation(() => new Promise(resolve => { release = resolve })); const task = c.execute(); await flushPromises(); await c.execute(); c.close(); expect(c.blocksNavigation).toBe(true); expect(c.$emit).not.toHaveBeenCalled(); expect(postAPI).toHaveBeenCalledTimes(1)
  const event = { preventDefault: jest.fn() }; c.beforeUnload(event); expect(event.preventDefault).toHaveBeenCalled(); release({ deletecniconfigurationresponse: { success: true } }); await task; expect(c.blocksNavigation).toBe(false)
})
test('timeout after server success confirms absent UUID without a second delete', async () => {
  await c.loadReferences(); postAPI.mockImplementation(() => { getAPI.mockRejectedValueOnce(error(431)).mockResolvedValueOnce(response([two])); return Promise.reject(new Error('timeout')) }); await c.execute(); expect(c.rows[0].status).toBe('success'); expect(postAPI).toHaveBeenCalledTimes(1)
})
test('already removed target does not delete same-name replacement', async () => {
  await c.loadReferences(); getAPI.mockRejectedValueOnce(error(431)).mockResolvedValueOnce(response([two])); await c.execute(); expect(c.rows[0].status).toBe('absent'); expect(postAPI).not.toHaveBeenCalled(); expect(c.$emit.mock.calls[0][1].removed).toEqual([one.id])
})
test.each([401, 403, 432, 531, 503])('lookup %s blocks delete and preserves selection', async code => { await c.loadReferences(); getAPI.mockRejectedValue(error(code)); await c.execute(); expect(c.rows[0].status).toBe('blocked'); expect(postAPI).not.toHaveBeenCalled(); expect(c.$emit).not.toHaveBeenCalled() })
test('failed fallback never confirms absence; unknown outcome requires explicit check', async () => {
  await c.loadReferences(); postAPI.mockRejectedValue(error(503)); getAPI.mockResolvedValueOnce(response()).mockResolvedValueOnce(usage()).mockResolvedValueOnce(response()).mockRejectedValue(error(431)); await c.execute(); expect(c.rows[0].status).toBe('unknown'); await c.execute(); expect(postAPI).toHaveBeenCalledTimes(1)
})
test.each(['user', 'token', 'project', 'route', 'target', 'permission', 'unmount'])('changing %s during read stops mutations', async kind => {
  await c.loadReferences(); getAPI.mockImplementation(() => { if (kind === 'user') c.$store.getters.userInfo.id = 'other'; if (kind === 'token') c.$store.state.user.token = 'other'; if (kind === 'project') c.$store.getters.project.id = two.id; if (kind === 'route') c.$route.fullPath += '?other'; if (kind === 'target') c.currentAction.cniTargets = [two]; if (kind === 'permission') delete c.$store.getters.apis.deleteCniConfiguration; if (kind === 'unmount') c.disposed = true; return Promise.resolve(response()) }); await c.execute(); expect(postAPI).not.toHaveBeenCalled(); expect(c.$emit).not.toHaveBeenCalled()
})
test('permission and scope invalidation removes old reference counts', async () => { await c.loadReferences(); Delete.watch.allowed.call(c, false); expect(c.rows[0].references).toBe(false); expect(c.rows[0].status).toBe('blocked') })
test('list query roundtrip is whitelisted and fixed to CNI path', () => {
  const route = { query: { keyword: '한글', page: '2', pagesize: '10', account: 'A', domainid: one.domainid, tab: 'comments', sessionkey: 'private', action: 'deleteCniConfiguration' } }; const link = cniDetailLink(route, one, 'comments'); expect(link.path).toBe('/cniconfiguration/' + one.id); expect(link.query.tab).toBe('comments'); expect(cniListLocation({ query: link.query })).toEqual({ path: '/cniconfiguration', query: { keyword: '한글', page: '2', pagesize: '10', account: 'A', domainid: one.domainid } })
  expect(cniListLocation({ query: { cnireturn: '{bad', keyword: 'fallback' } }).query).toEqual({ keyword: 'fallback' }); expect(cniListLocation({ query: { cnireturn: '{"path":"https://evil.invalid","keyword":"safe"}' } })).toEqual({ path: '/cniconfiguration', query: { keyword: 'safe' } })
})
test('metadata and Autogen snapshot selection; detail clears resource/cache and replaces exact return route', () => {
  const action = compute.children.find(s => s.name === 'cniconfiguration').actions.find(a => a.api === 'deleteCniConfiguration'); expect(action.selfManagedDialog).toBe(true); expect(action.groupAction).toBe(true)
  const row = { ...one }; const ctx = { $route: { path: '/cniconfiguration/' + one.id, query: { cnireturn: JSON.stringify({ keyword: 'CNI', page: '2' }) } }, $store: c.$store, selectedItems: [row], listScope: () => 'scope', resource: one, items: [one, two], dataView: true, listRequestVersion: 3, $router: { replace: jest.fn() }, $nextTick: () => {} }
  Autogen.methods.execAction.call(ctx, { ...action, resource: row }, false); row.name = 'changed'; expect(ctx.currentAction.cniTargets).toEqual([one]); Autogen.methods.closeAction.call(ctx, { started: true, removed: [one.id], retained: [] }); expect(ctx.resource).toEqual({}); expect(ctx.items).toEqual([]); expect(ctx.listRequestVersion).toBe(4); expect(ctx.$router.replace).toHaveBeenCalledWith({ path: '/cniconfiguration', query: { keyword: 'CNI', page: '2' } })
})
test('list partial result drops only removed rows and preserves failed selection', () => {
  const ctx = { currentAction: { api: 'deleteCniConfiguration', cniListScope: 'scope' }, listScope: () => 'scope', dataView: false, items: [one, two], resource: {}, listRequestVersion: 1, $nextTick: () => {}, $route: { path: '/cniconfiguration' } }; Autogen.methods.closeAction.call(ctx, { started: true, removed: [one.id], retained: [two.id] }); expect(ctx.items).toEqual([two]); expect(ctx.selectedRowKeys).toEqual([two.id])
})
test('standard dialog renders frozen name/owner and blocks shell while busy', async () => {
  const wrapper = shallowMount(Delete, { props: { currentAction: { cniTargets: [one] } }, global: { mocks: { $store: c.$store, $route: c.$route, $t: key => key }, stubs: { MoldDialog: { name: 'MoldDialog', props: ['keyboard', 'closable'], template: '<section><slot /><footer><slot name="footer" /></footer></section>' }, 'a-button': { props: ['disabled'], template: '<button :disabled="disabled"><slot /></button>' } } } }); await flushPromises(); expect(wrapper.find('footer').text()).toContain('label.cancel'); await wrapper.setData({ busy: true }); expect(wrapper.findComponent({ name: 'MoldDialog' }).props('keyboard')).toBe(false); expect(wrapper.findComponent({ name: 'MoldDialog' }).props('closable')).toBe(false); wrapper.unmount()
})

test('CNI group action is reachable and reference links preserve list context without page leaking into detail', () => {
  expect(ListView.methods.enableGroupAction.call({ resourcePath: '', $route: { name: 'cniconfiguration' } })).toBe(true)
  c.$route.query = { keyword: 'CNI', page: '2', pagesize: '10' }
  const link = c.referenceLink(one)
  expect(link.query.page).toBeUndefined(); expect(link.query.tab).toBe('cni.usage'); expect(cniListLocation({ query: link.query }).query).toEqual(c.$route.query)
})

test('reconciliation cannot overlap usage refresh and change the same row outcome', async () => {
  c.rows[0].status = 'unknown'; c.referencePending = true; await c.checkUnknown(); expect(getAPI).not.toHaveBeenCalled(); expect(c.rows[0].status).toBe('unknown')
})
