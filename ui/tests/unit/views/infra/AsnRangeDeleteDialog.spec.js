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
import { h, reactive, nextTick, Teleport } from 'vue'
import Dialog from '@/views/infra/zone/AsnRangeDeleteDialog.vue'
import { asnCreationContext } from '@/utils/asnRange'
import { deletionTarget, deletionOperation, clearDeletion, confirmedList } from '@/utils/asnRangeDeletion'
import { getAPI, postAPI } from '@/api'
jest.mock('@/api', () => ({ getAPI: jest.fn(), postAPI: jest.fn() }))
const drain = async () => { for (let i = 0; i < 35; i++) { await Promise.resolve(); await nextTick() } }
const deferred = () => { let fulfill, fail; const promise = new Promise((resolve, reject) => { fulfill = resolve; fail = reject }); return { promise, resolve: fulfill, reject: fail } }
const slot = { render () { return h('div', this.$slots.default?.()) } }
const button = { props: ['disabled', 'loading'], render () { return h('button', { disabled: this.disabled || this.loading }, this.$slots.default?.()) } }
let serial = 0; let wrappers; let identities; let rows; let allocated; let lookupFailure; let emptyResponse; let omitZeroCount
const target = Object.freeze({ id: 'range', zoneid: 'zone', zonename: 'Zone', startasn: '64512', endasn: '64514', count: 3 })
beforeEach(() => {
  jest.clearAllMocks(); wrappers = []; identities = []; rows = [{ id: 'range', zoneid: 'zone', startasn: 64512, endasn: 64514 }]; allocated = 0; lookupFailure = false; emptyResponse = false; omitZeroCount = false
  getAPI.mockImplementation(async (command, params) => {
    if (lookupFailure) throw Error('offline')
    if (command === 'listZones') return { listzonesresponse: { zone: [{ id: params.id }] } }
    if (command === 'listASNRanges') return { listasnrangesresponse: omitZeroCount && !rows.length ? {} : { count: rows.length, asnumberrange: rows } }
    if (emptyResponse) return { listasnumbersresponse: {} }
    const count = params.asnrangeid ? (params.isallocated ? allocated : 3) : rows.length * 3
    if (omitZeroCount && !count) return { listasnumbersresponse: {} }
    return { listasnumbersresponse: { count, asnumber: count ? [{ zoneid: 'zone', asnrangeid: 'range', allocationstate: params.isallocated ? 'Allocated' : 'Free' }] : [] } }
  })
  postAPI.mockImplementation(async () => { rows = []; return { deleteasnrangeresponse: { success: true } } })
})
afterEach(() => { wrappers.forEach(w => { if (w.exists()) w.unmount() }); identities.forEach(c => clearDeletion(c, 'range')) })
async function mount (identity) {
  const getters = identity?.getters || reactive({ userInfo: { id: 'admin', roleid: 'root', roletype: 'Admin' }, project: {}, apis: Object.fromEntries(['deleteASNRange', 'listASNumbers', 'listASNRanges', 'listZones'].map(x => [x, {}])) })
  const state = identity?.state || reactive({ user: { token: 'token' + ++serial } })
  const context = asnCreationContext(getters, state.user.token, 'zone'); identities.push(context)
  const w = shallowMount(Dialog, { props: { target, context }, global: { mocks: { $store: { getters, state }, $t: k => k }, stubs: { teleport: false, MoldDialog: slot, MoldDialogActions: identity?.actions || slot, ADescriptions: slot, ADescriptionsItem: slot, AAlert: slot, AButton: button, ACheckbox: slot } } })
  wrappers.push(w); await drain(); return { w, getters, state, context }
}

test('summary freezes Zone UUID, range UUID, endpoints and count; cancellation never posts', async () => {
  const { w } = await mount(); expect(w.text()).toContain('zone'); expect(w.text()).toContain('range'); expect(w.text()).toContain('64512'); expect(w.text()).toContain('64514'); expect(w.vm.allocated).toBe(0)
  w.vm.cancel(); expect(w.emitted('cancel')).toHaveLength(1); expect(postAPI).not.toHaveBeenCalled()
  expect(() => deletionTarget({ id: 'other' }, rows[0])).toThrow()
  expect(Object.isFrozen(deletionTarget({ id: 'zone' }, rows[0]))).toBe(true)
})
test('empty or failed count is unknown and never enables deletion', async () => {
  emptyResponse = true; const { w } = await mount(); expect(w.vm.ready).toBe(false); expect(w.vm.allocated).toBeNull(); expect(w.vm.error).toBeTruthy()
  await w.vm.submit(); expect(postAPI).not.toHaveBeenCalled()
  expect(() => confirmedList({ listasnumbersresponse: { count: 2 } }, 'listasnumbersresponse', 'asnumber')).toThrow()
})
test('legacy empty allocation and post-delete lists confirm the last range was removed', async () => {
  omitZeroCount = true
  const { w } = await mount()
  expect(w.vm.allocated).toBe(0)
  expect(w.vm.ready).toBe(true)
  await w.vm.submit()
  expect(postAPI).toHaveBeenCalledTimes(1)
  expect(w.emitted('complete')[0][0]).toEqual({ ranges: [], accepted: true })
})

test('allocated count blocks deletion', async () => {
  allocated = 1; const { w } = await mount(); expect(w.vm.allocated).toBe(1); expect(w.vm.ready).toBe(false); await w.vm.submit(); expect(postAPI).not.toHaveBeenCalled()
})
test('allocation appears between preview and confirmation, so no write is sent', async () => {
  const { w } = await mount(); allocated = 1; await w.vm.submit(); expect(postAPI).not.toHaveBeenCalled(); expect(w.vm.message).toBe('allocated')
})
test('changed endpoints, wrong Zone or read failure cannot delete', async () => {
  const { w } = await mount(); rows[0].endasn = 64515; await w.vm.submit(); expect(postAPI).not.toHaveBeenCalled(); expect(w.vm.error).toBeTruthy()
  rows[0].endasn = 64514; rows[0].zoneid = 'other'; await w.vm.recheck(); expect(w.vm.ready).toBe(false)
})
test('double click and repeated keyboard submits issue one write; cancellation is locked while pending', async () => {
  const { w } = await mount(); const slow = deferred(); postAPI.mockReturnValue(slow.promise)
  const first = w.vm.submit(); await drain(); await w.vm.submit(); w.vm.cancel(); expect(w.emitted('cancel')).toBeUndefined(); expect(postAPI).toHaveBeenCalledTimes(1)
  rows = []; slow.resolve({ deleteasnrangeresponse: { success: true } }); await first; expect(w.emitted('complete')[0][0]).toEqual({ ranges: [], accepted: true })
})
test('permission or login changes after preflight block write and late completion', async () => {
  const { w, getters } = await mount(); delete getters.apis.deleteASNRange; await w.vm.submit(); expect(postAPI).not.toHaveBeenCalled()
})
test('identity changes while preflight is pending block the write', async () => {
  const { w, state } = await mount(); const slow = deferred(); getAPI.mockReturnValueOnce(slow.promise)
  const pending = w.vm.submit(); state.user.token = 'new-login'; slow.resolve({ listzonesresponse: { zone: [{ id: 'zone' }] } }); await pending; expect(postAPI).not.toHaveBeenCalled(); expect(w.emitted('complete')).toBeUndefined()
})
test.each(['deleteasnrangeresponse', 'errorresponse'])('known %s rejection retains target and requires a fresh preview', async responseName => {
  const { w, context } = await mount(); postAPI.mockRejectedValue({ response: { data: { [responseName]: { errorcode: 431, errortext: 'Allocated' } } } })
  await w.vm.submit(); expect(w.vm.error).toBe('Allocated'); expect(w.vm.ready).toBe(false); expect(deletionOperation(context, 'range')).toBeUndefined(); expect(w.emitted('complete')).toBeUndefined()
})
test('timeout never retries and survives dialog reopening', async () => {
  const { w, getters, state, context } = await mount(); postAPI.mockRejectedValue(Error('timeout')); await w.vm.submit(); expect(w.vm.message).toBe('unknown'); w.unmount()
  const reopened = (await mount({ getters, state })).w; expect(reopened.vm.operation).toBe(deletionOperation(context, 'range')); await reopened.vm.submit(); expect(postAPI).toHaveBeenCalledTimes(1)
  rows = []; await reopened.vm.recheck(); expect(reopened.emitted('complete')[0][0].accepted).toBe(false)
})
test('malformed success receipt is unknown, not success', async () => {
  const { w } = await mount(); postAPI.mockResolvedValue({ deleteasnrangeresponse: {} }); await w.vm.submit(); expect(w.vm.message).toBe('unknown'); expect(w.emitted('complete')).toBeUndefined()
})
test('accepted deletion followed by failed refresh keeps confirmation and only rechecks with GET', async () => {
  const { w } = await mount(); postAPI.mockImplementation(async () => { rows = []; lookupFailure = true; return { deleteasnrangeresponse: { success: true } } })
  await w.vm.submit(); expect(w.vm.message).toBe('refreshfailed'); expect(w.emitted('complete')).toBeUndefined()
  lookupFailure = false; await w.vm.recheck(); expect(postAPI).toHaveBeenCalledTimes(1); expect(w.emitted('complete')[0][0].accepted).toBe(true)
})
test('already deleted target synchronizes without POST or false success', async () => {
  rows = []; const { w } = await mount(); expect(w.emitted('complete')[0][0]).toEqual({ ranges: [], accepted: false }); expect(postAPI).not.toHaveBeenCalled()
})
test('a late write response cannot update a different login screen', async () => {
  const { w, state } = await mount(); const slow = deferred(); postAPI.mockReturnValue(slow.promise); const pending = w.vm.submit(); await drain()
  state.user.token = 'other'; const reads = getAPI.mock.calls.length; slow.resolve({ deleteasnrangeresponse: { success: true } }); await pending
  expect(w.emitted('complete')).toBeUndefined(); expect(getAPI.mock.calls).toHaveLength(reads)
})

test('repeated Ctrl+Enter never submits', async () => {
  const { w } = await mount()
  await w.find('.asn-range-delete').trigger('keydown', { key: 'Enter', ctrlKey: true, repeat: true })
  expect(postAPI).not.toHaveBeenCalled()
})

test('Ctrl+Enter on the teleported footer submits once and ignores key repeat', async () => {
  const footer = document.createElement('div'); document.body.appendChild(footer)
  const actions = { render () { return h(Teleport, { to: footer }, this.$slots.default?.()) } }
  const { w } = await mount({ actions }); const slow = deferred(); postAPI.mockReturnValue(slow.promise)
  const confirm = footer.querySelector('button:last-child')
  confirm.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', ctrlKey: true, repeat: true, bubbles: true })); await drain(); expect(postAPI).not.toHaveBeenCalled()
  confirm.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', ctrlKey: true, bubbles: true })); await drain()
  confirm.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', ctrlKey: true, bubbles: true })); await drain(); expect(postAPI).toHaveBeenCalledTimes(1)
  rows = []; slow.resolve({ deleteasnrangeresponse: { success: true } }); await drain(); expect(w.emitted('complete')[0][0].accepted).toBe(true)
  w.unmount(); footer.remove()
})
