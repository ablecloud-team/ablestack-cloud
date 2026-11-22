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
import Tab from '@/views/infra/zone/AsNumbersTab.vue'
import { getAPI, postAPI } from '@/api'
import { clearAsnOperation, asnOperation } from '@/utils/asnRange'
jest.mock('@/api', () => ({ getAPI: jest.fn(), postAPI: jest.fn() }))
jest.mock('@/utils/listRefresh', () => ({ createListRefresh: () => ({ stop: jest.fn() }), canRefreshList: () => false, listRowKey: x => x.id }))
const drain = async () => { for (let i = 0; i < 30; i++) { await Promise.resolve(); await nextTick() } }
const deferred = () => { let fulfill, fail; const promise = new Promise((resolve, reject) => { fulfill = resolve; fail = reject }); return { promise, resolve: fulfill, reject: fail } }
const slot = { render () { return h('div', this.$slots.default?.()) } }
const dialog = { props: ['visible'], render () { return this.visible ? h('section', this.$slots.default?.()) : null } }
let serial = 0; let wrappers; let contexts; let rows; let validation; let failMembers; let staleList
const form = { methods: { validate: () => validation, scrollToField: jest.fn() }, render () { return h('form', this.$slots.default?.()) } }
const input = { methods: { focus: jest.fn() }, render () { return h('input') } }
const actions = { props: ['busy'], render () { return h('fieldset', { disabled: this.busy }, this.$slots.default?.()) } }
const button = { props: ['disabled', 'loading'], render () { return h('button', { disabled: this.disabled || this.loading }, this.$slots.default?.()) } }

beforeEach(() => {
  jest.clearAllMocks(); wrappers = []; contexts = []; rows = []; validation = Promise.resolve(); failMembers = false; staleList = false
  getAPI.mockImplementation(async (c, p) => {
    if (c === 'listZones') return { listzonesresponse: { zone: [{ id: p.id, routedmodeenabled: true }] } }
    if (c === 'listASNRanges') {
      if (staleList) throw Error('lookup failed')
      return { listasnrangesresponse: { count: rows.filter(x => x.zoneid === p.zoneid).length, asnumberrange: rows.filter(x => x.zoneid === p.zoneid) } }
    }
    if (failMembers) throw Error('members unavailable')
    const row = rows.find(x => x.id === p.asnrangeid)
    return { listasnumbersresponse: { count: 3, asnumber: [64512, 64513, 64514].map(n => ({ zoneid: row.zoneid, asnrangeid: row.id, asnumber: n })) } }
  })
  postAPI.mockImplementation(async (_, p) => {
    const row = { id: 'range', ...p }; rows.push(row)
    return { createasnrangeresponse: row }
  })
})
afterEach(() => { wrappers.forEach(w => w.unmount()); contexts.forEach(clearAsnOperation) })

async function mount () {
  const getters = reactive({ userInfo: { id: 'user', roletype: 'Admin', roleid: 'root' }, project: {}, apis: { createASNRange: {}, listZones: {}, listASNRanges: {}, listASNumbers: {} } })
  const state = reactive({ user: { token: 'token-' + ++serial } })
  const success = jest.fn()
  const w = shallowMount(Tab, {
    props: { resource: { id: 'zone', name: 'Zone', routedmodeenabled: true } },
    global: {
      mocks: { $t: k => k, $store: { getters, state }, $route: reactive({ fullPath: '/zone/zone?tab=asnumber' }), $notifyError: jest.fn(), $notification: { success } },
      stubs: { MoldDialog: dialog, MoldDialogActions: actions, AButton: button, AForm: form, AFormItem: slot, ADescriptions: slot, ADescriptionsItem: slot, AAlert: slot, AInput: input, ATable: true, TooltipButton: true },
      directives: { focus: () => {} }
    }
  })
  wrappers.push(w); await drain(); contexts.push(w.vm.creationContext)
  w.vm.showCreateASRange(); await drain()
  w.vm.form = { startasn: '64512', endasn: '64514' }
  return { w, getters, state, success }
}

test('a synchronous create is confirmed with range and every number before closing', async () => {
  const { w, success } = await mount()
  await w.vm.handleSubmit(); await drain()
  expect(postAPI).toHaveBeenCalledTimes(1)
  expect(postAPI).toHaveBeenCalledWith('createASNRange', { zoneid: 'zone', startasn: '64512', endasn: '64514' }, { preserveOnFailure: true })
  expect(w.vm.addASRangeModal).toBe(false); expect(w.vm.asnRanges).toHaveLength(1)
  expect(w.emitted('refresh-data')).toHaveLength(1); expect(success).toHaveBeenCalledTimes(1)
})

test('validation and slow request cannot be duplicated or closed while busy', async () => {
  const { w } = await mount(); const gate = deferred(); validation = gate.promise
  const first = w.vm.handleSubmit(); w.vm.handleSubmit(); w.vm.closeAction()
  expect(w.vm.addASRangeModal).toBe(true); expect(postAPI).not.toHaveBeenCalled()
  gate.resolve(); await first; await drain(); expect(postAPI).toHaveBeenCalledTimes(1)
})

test('invalid domain input is never posted even if a form adapter resolves validation', async () => {
  const { w } = await mount(); w.vm.form = { startasn: '4294967296', endasn: '4294967296' }
  await w.vm.handleSubmit(); expect(postAPI).not.toHaveBeenCalled()
})

test('known parameter rejection preserves the draft and allows correction', async () => {
  const { w, success } = await mount()
  postAPI.mockRejectedValue({ response: { data: { createasnrangeresponse: { errorcode: 431, errortext: 'Overlap' } } } })
  await w.vm.handleSubmit(); await drain()
  expect(w.vm.error).toBe('Overlap'); expect(w.vm.form.startasn).toBe('64512')
  expect(w.vm.addASRangeModal).toBe(true); expect(w.vm.locked).toBe(false); expect(success).not.toHaveBeenCalled()
})

test('an unknown outcome survives reopen and cannot be retried without an absent-state acknowledgement', async () => {
  const { w } = await mount(); postAPI.mockRejectedValue(Error('connection lost'))
  await w.vm.handleSubmit(); await drain(); expect(w.vm.operation.status).toBe('unknown')
  w.vm.closeAction(); w.vm.showCreateASRange(); await drain(); await w.vm.handleSubmit()
  expect(postAPI).toHaveBeenCalledTimes(1)
  await w.vm.recheck(); expect(w.vm.operation.absent).toBe(true)
  w.vm.endTracking(); expect(w.vm.locked).toBe(true)
  w.vm.acknowledged = true; w.vm.endTracking(); expect(w.vm.locked).toBe(false)
})

test('success followed by read failure is unverified and only recheck completes it', async () => {
  const { w, success } = await mount(); failMembers = true
  await w.vm.handleSubmit(); expect(w.vm.operation.status).toBe('unverified'); expect(success).not.toHaveBeenCalled()
  failMembers = false; await w.vm.recheck(); expect(success).toHaveBeenCalledTimes(1); expect(postAPI).toHaveBeenCalledTimes(1)
})

test('late Zone preflight cannot submit after identity, permission or Zone changes', async () => {
  const { w, state } = await mount(); const gate = deferred()
  getAPI.mockImplementationOnce(() => gate.promise)
  const submit = w.vm.handleSubmit(); await drain(); state.user.token = 'different'; await drain()
  gate.resolve({ listzonesresponse: { zone: [{ id: 'zone', routedmodeenabled: true }] } }); await submit
  expect(postAPI).not.toHaveBeenCalled(); expect(w.vm.addASRangeModal).toBe(false)
})

test('a late accepted write is retained for its old Zone and does not change the new screen', async () => {
  const { w, success } = await mount(); const gate = deferred(); const old = w.vm.creationContext
  postAPI.mockImplementationOnce(() => gate.promise)
  const submit = w.vm.handleSubmit(); await drain()
  await w.setProps({ resource: { id: 'other', name: 'Other', routedmodeenabled: true } }); await drain()
  gate.resolve({ createasnrangeresponse: { id: 'range', zoneid: 'zone', startasn: 64512, endasn: 64514 } }); await submit
  expect(success).not.toHaveBeenCalled(); expect(w.vm.asnRanges).toEqual([]); expect(asnOperation(old)).toBeTruthy()
  await w.setProps({ resource: { id: 'zone', name: 'Zone', routedmodeenabled: true } }); await drain()
  w.vm.showCreateASRange(); expect(w.vm.locked).toBe(true); expect(postAPI).toHaveBeenCalledTimes(1)
})

test('permission revoked before validation resolves prevents a write', async () => {
  const { w, getters } = await mount(); const gate = deferred(); validation = gate.promise
  const submit = w.vm.handleSubmit(); delete getters.apis.createASNRange; await drain(); gate.resolve(); await submit
  expect(postAPI).not.toHaveBeenCalled(); expect(w.vm.canCreate).toBe(false)
})

test('same-context refresh failure retains prior rows, then a new Zone clears them', async () => {
  const { w } = await mount(); rows.push({ id: 'old', zoneid: 'zone', startasn: 64512, endasn: 64514 })
  await w.vm.fetchData(); staleList = true; await w.vm.fetchData()
  expect(w.vm.asnRanges).toHaveLength(1); expect(w.vm.listRefreshFailed).toBe(true)
  await w.setProps({ resource: { id: 'other', routedmodeenabled: true } }); await drain(); expect(w.vm.asnRanges).toEqual([])
})

test('both native finishFailed and guarded validation focus the first invalid input', async () => {
  const { w } = await mount()
  w.findComponent(form).vm.$emit('finishFailed', { errorFields: [{ name: ['endasn'] }] })
  await drain()
  expect(input.methods.focus).toHaveBeenCalledTimes(1)
  expect(form.methods.scrollToField).toHaveBeenCalledWith('endasn')
  validation = Promise.reject(Object.assign(new Error('validation'), { errorFields: [{ name: ['startasn'] }] }))
  await w.vm.handleSubmit(); await drain()
  expect(input.methods.focus).toHaveBeenCalledTimes(2)
  expect(form.methods.scrollToField).toHaveBeenLastCalledWith('startasn')
  expect(postAPI).not.toHaveBeenCalled()
})

test('range delete permission is independent of create permission and opens a frozen target without posting', async () => {
  const { w, getters } = await mount(); w.vm.closeAction()
  expect(w.vm.canDelete).toBe(false)
  getters.apis.deleteASNRange = {}; delete getters.apis.createASNRange; await drain()
  expect(w.vm.canDelete).toBe(true); expect(w.vm.canCreate).toBe(false)
  w.vm.onDelete({ id: 'delete-range', zoneid: 'zone', startasn: 64512, endasn: 64514 })
  expect(w.vm.deleteTarget.id).toBe('delete-range'); expect(Object.isFrozen(w.vm.deleteTarget)).toBe(true); expect(postAPI).not.toHaveBeenCalled()
  delete getters.apis.deleteASNRange; await drain(); expect(w.vm.deleteTarget).toBeNull()
})

test('cancellation restores focus to the native row button with a safe fallback after removal', async () => {
  const { w } = await mount(); w.vm.closeAction()
  const row = document.createElement('span'); const source = document.createElement('button'); row.appendChild(source); document.body.appendChild(row)
  w.vm.deleteButtons.range = row; w.vm.deleteTarget = { id: 'range' }; w.vm.closeDelete(); await nextTick()
  expect(document.activeElement).toBe(source)
  row.remove(); w.vm.deleteTarget = { id: 'range' }; expect(() => w.vm.closeDelete()).not.toThrow(); await nextTick()
})

test('the serializer empty range response is a ready list with creation available', async () => {
  getAPI.mockResolvedValueOnce({ listasnrangesresponse: {} })
  const { w } = await mount()
  expect(w.vm.asnRanges).toEqual([])
  expect(w.vm.listRefreshFailed).toBe(false)
  expect(w.vm.addASRangeModal).toBe(true)
})
