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

import { mount, shallowMount, flushPromises } from '@vue/test-utils'
import { Select, Form } from 'ant-design-vue'
import { h } from 'vue'
import Ops from '@/views/image/TemplateZoneOperations'
import Zones from '@/views/image/TemplateZones'
import Autogen from '@/views/AutogenView'
import { preparePoolEligible, primaryPreparationState } from '@/utils/templateZoneOperations'
import { templateObservationContext } from '@/utils/templateAvailability'
import { getAPI, postAPI } from '@/api'
jest.mock('@/api', () => ({ getAPI: jest.fn(), postAPI: jest.fn() }))
const resource = { id: 'template', name: 'image', hypervisor: 'KVM', format: 'QCOW2' }
const row = { id: 'template', zoneid: 'zone', zonename: 'Zone', isready: true, status: 'Download Complete', created: '2026-11-13T00:00:00Z', downloaddetails: [] }
const pool = { id: 'pool', name: 'Pool', zoneid: 'zone', state: 'Up', hypervisor: 'KVM', type: 'SharedMountPoint', managed: false }
function context () {
  const c = { ...Ops.data(), resource: { ...resource }, context: 'scope', permitted: true, $store: { getters: { apis: { deleteTemplate: {}, copyTemplate: {}, prepareTemplate: {}, listStoragePools: {} }, userInfo: { id: 'user', roletype: 'Admin' } } }, $route: { path: '/template/template', query: {}, meta: { name: 'template' } }, $router: { push: jest.fn() }, $emit: jest.fn(), $nextTick: fn => fn(), $t: k => k }
  for (const [k, v] of Object.entries(Ops.methods))c[k] = v.bind(c)
  for (const [k, v] of Object.entries(Ops.computed))Object.defineProperty(c, k, { get: () => v.call(c) })
  return c
}
beforeEach(() => { jest.clearAllMocks(); getAPI.mockResolvedValue({ queryasyncjobresultresponse: { jobstatus: 1 } }); postAPI.mockResolvedValue({ deletetemplateresponse: { jobid: 'job' } }) })
test('confirmation snapshots preserve raw readiness, status and dates without mutating selected rows', async () => {
  const input = Object.freeze({ ...row, downloaddetails: [{ downloadState: 'DOWNLOADED' }] }); const c = context(); await c.open('delete', [input]); expect(c.rows[0]).toMatchObject({ status: 'Download Complete', isready: true, created: row.created, operationState: 'pending' }); c.rows[0].downloaddetails[0].downloadState = 'ERROR'; expect(input.downloaddetails[0].downloadState).toBe('DOWNLOADED'); expect(c.displayColumns.map(x => x.key)).not.toContain('operationState')
})
test('single or selected deletion retains terminal results until explicit dismissal', async () => {
  const c = context(); await c.open('delete', [row, { ...row, zoneid: 'other' }]); await c.submit(); expect(postAPI).toHaveBeenCalledTimes(2); expect(c.rows.every(r => r.operationState === 'success')).toBe(true); expect(c.rows[0].status).toBe('Download Complete'); expect(c.visible).toBe(true); expect(c.$router.push).not.toHaveBeenCalled(); c.close(); expect(c.$router.push).toHaveBeenCalledWith(expect.objectContaining({ path: '/template' }))
})
test('a second submission cannot duplicate mutations', async () => {
  const c = context(); await c.open('delete', [row]); let done; postAPI.mockImplementationOnce(() => new Promise(resolve => { done = resolve })); const first = c.submit(); await c.submit(); expect(postAPI).toHaveBeenCalledTimes(1); done({ deletetemplateresponse: { jobid: 'job' } }); await first
})
test('copy has an explicit empty-target state and cannot send the source Zone', async () => {
  getAPI.mockResolvedValue({ listzonesresponse: { zone: [{ id: 'zone', name: 'Zone' }] } }); const c = context(); await c.open('copy', [row]); expect(c.destinations).toEqual([]); expect(c.canSubmit).toBe(false); await c.submit(); expect(postAPI).not.toHaveBeenCalled()
})
test('copy partial failure keeps successful destinations and retries only failed items', async () => {
  const c = context(); getAPI.mockResolvedValueOnce({ listzonesresponse: { zone: [{ id: 'zone' }, { id: 'a', name: 'A' }, { id: 'b', name: 'B' }] } }); await c.open('copy', [row]); c.selectedZones = ['a', 'b']; postAPI.mockImplementationOnce(() => Promise.resolve({ copytemplateresponse: { jobid: 'a' } })).mockRejectedValueOnce({ response: { status: 431 }, message: 'denied' }).mockResolvedValueOnce({ copytemplateresponse: { jobid: 'b' } }); await c.submit(); expect(c.rows.map(r => r.operationState)).toEqual(['success', 'failed']); await c.runFailed(); expect(postAPI.mock.calls.map(x => x[1].destzoneids)).toEqual(['a', 'b', 'b']); expect(c.rows.map(r => r.operationState)).toEqual(['success', 'success'])
})
test('an ambiguous mutation never replays without a definite failure', async () => {
  const c = context(); await c.open('delete', [row]); postAPI.mockRejectedValueOnce(new Error('timeout')); await c.submit(); expect(c.rows[0].operationState).toBe('unknown'); await c.runFailed(); await c.observeUnknown(); expect(postAPI).toHaveBeenCalledTimes(1)
})
test('status re-observation polls the existing job instead of submitting it again', async () => {
  const c = context(); await c.open('delete', [row]); c.phase = 'result'; c.rows[0].operationState = 'unknown'; c.rows[0].jobid = 'existing'; await c.observeUnknown(); expect(postAPI).not.toHaveBeenCalled(); expect(getAPI).toHaveBeenCalledWith('queryAsyncJobResult', { jobid: 'existing' }, expect.anything()); expect(c.rows[0].operationState).toBe('success')
})
test('a late mutation reply cannot paint a different scope', async () => {
  const c = context(); await c.open('delete', [row]); let done; postAPI.mockImplementationOnce(() => new Promise(resolve => { done = resolve })); const pending = c.submit(); c.context = 'new-owner'; Ops.watch.context.call(c); done({ deletetemplateresponse: { jobid: 'old' } }); await pending; expect(c.visible).toBe(false); expect(c.busy).toBe(false); expect(getAPI).not.toHaveBeenCalled()
})
test('late option lookup cannot lock a newly opened deletion form', async () => {
  const c = context(); let done; getAPI.mockImplementationOnce(() => new Promise(resolve => { done = resolve })); const old = c.open('copy', [row]); c.close(); await c.open('delete', [row]); done({ listzonesresponse: { zone: [] } }); await old; expect(c.optionsLoading).toBe(false); expect(c.canSubmit).toBe(true)
})
test.each([{ zoneid: 'other' }, { state: 'Disabled' }, { type: 'RBD' }, { type: 'DatastoreCluster' }, { managed: true }, { hypervisor: 'VMware' }])('prepare rejects ineligible pools %j', bad => { expect(preparePoolEligible({ ...pool, ...bad }, 'zone')).toBe(false) })
test('prepare requires an explicit pool and treats its response as synchronous acceptance', async () => {
  const c = context(); getAPI.mockResolvedValueOnce({ liststoragepoolsresponse: { storagepool: [pool] } }); await c.open('prepare', [row]); await c.submit(); expect(postAPI).not.toHaveBeenCalled(); c.poolid = 'pool'; postAPI.mockResolvedValueOnce({ preparetemplateresponse: { template: [{ isready: true }] } }); getAPI.mockResolvedValueOnce({ listtemplatesresponse: { template: [{ ...row, downloaddetails: [{ datastoreRole: 'Primary', datastoreId: 'pool', downloadState: 'DOWNLOADED' }] }] } }); await c.submit(); expect(postAPI).toHaveBeenCalledWith('prepareTemplate', { templateid: 'template', zoneid: 'zone', storageid: 'pool' }, expect.anything()); expect(c.rows[0].operationState).toBe('success'); expect(getAPI.mock.calls.some(x => x[0] === 'queryAsyncJobResult')).toBe(false)
})
test('a revoked prepare role cannot dispatch a mutation', async () => {
  const c = context(); await c.open('delete', [row]); c.kind = 'prepare'; c.poolid = 'pool'; c.pools = [pool]; c.$store.getters.userInfo.roletype = 'User'; await c.request(c.rows[0], c.epoch); expect(postAPI).not.toHaveBeenCalled()
})
test.each(['Image', 'Secondary', undefined])('secondary or unscoped %s evidence cannot prove Primary completion', role => { expect(primaryPreparationState([{ ...row, downloaddetails: [{ datastoreRole: role, datastoreId: 'pool', downloadState: 'DOWNLOADED' }] }], 'zone', 'pool', 'template')).toBe('running') })
test('only the exact template, Zone, Primary role and selected pool may complete observation', () => {
  const detail = { datastoreRole: 'Primary', datastoreId: 'pool', downloadState: 'DOWNLOADED' }
  for (const change of [{ id: 'other' }, { zoneid: 'other' }, { downloaddetails: [{ ...detail, datastoreId: 'other' }] }])expect(primaryPreparationState([{ ...row, downloaddetails: [detail], ...change }], 'zone', 'pool', 'template')).toBe('running')
  expect(primaryPreparationState([{ ...row, downloaddetails: [detail] }], 'zone', 'pool', 'template')).toBe('success')
  expect(primaryPreparationState([{ ...row, downloaddetails: [detail, { ...detail, downloadState: 'DOWNLOAD_ERROR' }] }], 'zone', 'pool', 'template')).toBe('running')
})
test('a retained template operation gates parent reads until dismissal', async () => {
  const c = { $route: { path: '/template/template', params: { id: 'template' }, query: { tab: 'zones' }, meta: { name: 'template' } }, resource: row, $store: { getters: { userInfo: { id: 'admin' }, apis: {} }, state: { user: { token: 'session' } } } }
  const scope = templateObservationContext(c.$route, c.resource, c.$store.getters, c.$store.state.user.token); Autogen.methods.retainTemplateOperation.call(c, { active: true, context: scope }); await Autogen.methods.fetchData.call(c); expect(getAPI).not.toHaveBeenCalled(); Autogen.methods.retainTemplateOperation.call(c, { active: false, context: scope }); expect(c.templateOperationContext).toBe('')
})
test('prepare UI requires Admin API schema and avoids implicit all-pool behavior', () => {
  const c = { $store: { getters: { userInfo: { roletype: 'Admin' }, apis: { prepareTemplate: {}, listStoragePools: {} } } }, $getApiParams: () => ({ storageid: {} }), resource }
  expect(Zones.computed.canPrepare.call(c)).toBe(true); c.$getApiParams = () => ({}); expect(Zones.computed.canPrepare.call(c)).toBe(false); c.$getApiParams = () => ({ storageid: {} }); c.$store.getters.userInfo.roletype = 'User'; expect(Zones.computed.canPrepare.call(c)).toBe(false)
})

test('empty copy target reason is rendered through the registered alert component', async () => {
  getAPI.mockResolvedValue({ listzonesresponse: { zone: [{ id: 'zone', name: 'Zone' }] } })
  const w = shallowMount(Ops, { props: { resource, context: 'scope', permitted: true }, global: { mocks: { $t: k => k, $toLocaleDate: x => x, $store: { getters: { apis: { copyTemplate: {} }, userInfo: { roletype: 'Admin' } } } }, stubs: { 'mold-dialog': { render () { return h('div', this.$slots.default()) } }, 'a-alert': { props: ['message'], template: '<p>{{ message }}</p>' }, 'mold-dialog-actions': { template: '<div><slot /></div>' } } } })
  await w.vm.open('copy', [row]); await flushPromises()
  expect(w.text()).toContain('message.template.zone.copy.empty'); expect(w.vm.canSubmit).toBe(false); w.unmount()
})

test('closing a Zone dialog restores its trigger after it is enabled again', async () => {
  const trigger = document.createElement('button'); document.body.appendChild(trigger); trigger.focus()
  jest.spyOn(trigger, 'getClientRects').mockReturnValue([{}])
  const c = context(); c.$emit.mockImplementation((name, value) => { if (name === 'retained') trigger.disabled = value.active })
  getAPI.mockResolvedValueOnce({ listzonesresponse: { zone: [] } })
  await c.open('copy', [row]); expect(trigger.disabled).toBe(true); c.close()
  expect(trigger.disabled).toBe(false); expect(document.activeElement).toBe(trigger); trigger.remove()
})
test('closing cannot focus a disconnected or changed-scope trigger', async () => {
  const trigger = document.createElement('button'); document.body.appendChild(trigger); trigger.focus()
  jest.spyOn(trigger, 'getClientRects').mockReturnValue([{}]); const focus = jest.spyOn(trigger, 'focus')
  const c = context(); await c.open('delete', [row]); c.context = 'other-scope'; c.close()
  expect(focus).not.toHaveBeenCalled(); trigger.remove()
})

test.each([['copy', 'label.select.zones'], ['prepare', 'label.primary.storage']])('actual %s selector exposes a native accessible name', async (kind, label) => {
  getAPI.mockResolvedValue({ listzonesresponse: { zone: [] }, liststoragepoolsresponse: { storagepool: [pool] } })
  const slot = { template: '<div><slot /></div>' }
  const w = mount(Ops, { attachTo: document.body, props: { resource, context: 'scope', permitted: true }, global: { mocks: { $store: context().$store, $route: context().$route, $router: { push: jest.fn() }, $t: k => k, $toLocaleDate: v => v }, components: { 'a-select': Select, 'a-form-item': Form.Item }, stubs: { MoldDialog: slot, MoldDialogActions: slot, AFormItem: false, ATable: true, AAlert: true, AButton: true, TemplateAvailability: true, Wave: false }, directives: { 'ctrl-enter': {} } } })
  await w.vm.open(kind, [row]); await flushPromises()
  expect(Array.from(w.get('input[role="combobox"]').element.labels || []).some(item => item.textContent.includes(label))).toBe(true)
  w.unmount()
})

test('store progress never coerces unknown or invalid values into a completed download', () => {
  const { templateStorePresentation } = require('@/utils/templateZoneOperations')
  expect(templateStorePresentation({ downloadState: 'DOWNLOADED', downloadPercent: '100' })).toMatchObject({ percent: 100, badge: 'success' })
  for (const value of [null, undefined, '', -1, 101, 'bad'])expect(templateStorePresentation({ downloadPercent: value }).percent).toBeNull()
  expect(templateStorePresentation({ downloadState: 'NEW_STATE' })).toMatchObject({ badge: 'default', label: 'label.unknown' })
})
