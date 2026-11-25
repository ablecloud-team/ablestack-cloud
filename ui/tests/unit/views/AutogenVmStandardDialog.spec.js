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
import Autogen from '@/views/AutogenView'
import { postAPI } from '@/api'
import { flushPromises } from '@vue/test-utils'
import { computed, nextTick, reactive, watch } from 'vue'
import store from '@/store'
jest.mock('@/api', () => ({ getAPI: jest.fn(), callAPI: jest.fn(), postAPI: jest.fn() }))
jest.mock('@/store', () => ({ getters: {}, state: { user: { token: 'session' } } }))
const id = '11111111-1111-4111-8111-111111111111'
let c
beforeEach(() => {
  c = { $route: { name: 'vnfapp-detail', meta: { name: 'vnfapp' }, fullPath: '/vnfapp/' + id, path: '/vnfapp/' + id, params: { id }, query: {} }, $store: { state: { user: { token: 'session' } }, getters: { userInfo: { id: 'actor', roletype: 'Admin' }, apis: { stopVirtualMachine: {} }, project: {} } }, $t: key => key, $notifyError: jest.fn(), $router: { go: jest.fn() }, $message: { info: jest.fn() } }
  Object.assign(c, Autogen.data.call(c)); Object.entries(Autogen.methods).forEach(([key, fn]) => { c[key] = fn.bind(c) })
  c.resource = { id, name: 'host', displayname: '한글 대상', state: 'Running', templatetype: 'VNF' }
  c.formRef = { value: { validate: jest.fn().mockResolvedValue(), scrollToField: jest.fn() } }; c.form = { forced: false }; c.dataView = true; c.showAction = true; c.isSubmitDisabled = false
  c.currentAction = { api: 'stopVirtualMachine', label: 'label.stop', params: [{ name: 'id' }, { name: 'forced', type: 'boolean' }], paramFields: [{ name: 'forced', type: 'boolean' }] }
  Object.defineProperty(c, 'vmStandardDialog', { get: () => Autogen.computed.vmStandardDialog.call(c) })
  c.postSnapshotAwareAction = jest.fn().mockResolvedValue({ stopvirtualmachineresponse: { jobid: 'job' } }); c.handleResponse = jest.fn().mockResolvedValue('job'); c.closeAction = jest.fn(); c.fetchData = jest.fn()
})
test('VM and VNF use the existing shared standard dialog without changing unrelated routes', () => {
  expect(c.vmStandardDialog).toBe(true); c.$route.meta.name = 'vm'; expect(c.vmStandardDialog).toBe(true)
  c.$route.meta.name = 'guestnetwork'; expect(c.vmStandardDialog).toBe(false)
})
test('common confirmation click and Enter do not duplicate a request while validation is pending', async () => {
  let done; c.formRef.value.validate.mockReturnValue(new Promise(resolve => { done = resolve }))
  c.handleSubmit({ preventDefault: jest.fn() }); c.handleSubmit({ preventDefault: jest.fn() }); expect(c.formRef.value.validate).toHaveBeenCalledTimes(1)
  done(); await flushPromises(); expect(c.postSnapshotAwareAction).toHaveBeenCalledTimes(1)
  expect(c.postSnapshotAwareAction).toHaveBeenCalledWith(c.currentAction, { id, forced: false })
})
test.each(['target', 'token', 'project', 'role'])('a %s switch during validation cannot submit a different resource', async kind => {
  let done; c.formRef.value.validate.mockReturnValue(new Promise(resolve => { done = resolve })); c.execSubmit({ preventDefault: jest.fn() })
  if (kind === 'target') c.resource = { ...c.resource, id: 'other' }
  if (kind === 'token') c.$store.state.user.token = 'next'
  if (kind === 'project') c.$store.getters.project = { id: 'other' }
  if (kind === 'role') c.$store.getters.userInfo.roletype = 'User'
  done(); await flushPromises(); expect(c.postSnapshotAwareAction).not.toHaveBeenCalled()
})
test('an old accepted response cannot close or update the next target dialog', async () => {
  let done; c.postSnapshotAwareAction.mockReturnValue(new Promise(resolve => { done = resolve })); c.execSubmit({ preventDefault: jest.fn() }); await flushPromises(); expect(c.postSnapshotAwareAction).toHaveBeenCalledTimes(1)
  c.resource = { ...c.resource, id: 'other' }; c.currentAction = { ...c.currentAction }; done({ stopvirtualmachineresponse: { jobid: 'old' } }); await flushPromises()
  expect(c.handleResponse).not.toHaveBeenCalled(); expect(c.closeAction).not.toHaveBeenCalled()
})

test('target or authentication context invalidates the open common dialog', () => {
  Autogen.watch.vmDialogScope.handler.call(c)
  expect(c.closeAction).toHaveBeenCalledTimes(1)
  c.showAction = false; Autogen.watch.vmDialogScope.handler.call(c)
  expect(c.closeAction).toHaveBeenCalledTimes(1)
})

test.each([undefined, 503])('an ambiguous HTTP result (%s) retains the dialog and blocks another submission', async status => {
  c.postSnapshotAwareAction.mockRejectedValue(status ? { response: { status } } : new Error('connection lost'))
  c.execSubmit({}); await flushPromises()
  expect(c.vmActionUnknown).toBe(true); expect(c.vmActionError).toBe('message.vm.start.unknown')
  c.execSubmit({}); await flushPromises(); expect(c.postSnapshotAwareAction).toHaveBeenCalledTimes(1)
  expect(c.closeAction).not.toHaveBeenCalled()
})

test('a known rejection keeps false/form values and permits an explicit retry', async () => {
  c.postSnapshotAwareAction.mockRejectedValueOnce({ response: { status: 431, data: { errorresponse: { errortext: '정책 거절' } } } })
  c.execSubmit({}); await flushPromises()
  expect(c.vmActionUnknown).toBe(false); expect(c.vmActionError).toBe('정책 거절'); expect(c.form.forced).toBe(false)
  c.execSubmit({}); await flushPromises(); expect(c.postSnapshotAwareAction).toHaveBeenCalledTimes(2)
})

test('an async response without a job identity is not treated as a successful submission', async () => {
  c.postSnapshotAwareAction.mockResolvedValue({ stopvirtualmachineresponse: {} })
  c.execSubmit({}); await flushPromises()
  expect(c.vmActionUnknown).toBe(true); expect(c.handleResponse).not.toHaveBeenCalled(); expect(c.closeAction).not.toHaveBeenCalled()
})

test('an old group response cannot close a later dialog even on the same VM', async () => {
  let done; postAPI.mockReturnValueOnce(new Promise(resolve => { done = resolve }))
  const pending = c.callGroupApi({ id }, '원래 대상')
  c.currentAction = { ...c.currentAction }; c.storageActionGeneration++
  done({ stopvirtualmachineresponse: { jobid: 'old-group-job' } })
  await expect(pending).resolves.toBe(false)
  expect(c.handleResponse).not.toHaveBeenCalled(); expect(c.closeAction).not.toHaveBeenCalled()
})

test('all accepted group receipts remain tracked after the first receipt closes the dialog', async () => {
  let first, second
  postAPI.mockReturnValueOnce(new Promise(resolve => { first = resolve })).mockReturnValueOnce(new Promise(resolve => { second = resolve }))
  c.closeAction = jest.fn(() => { c.showAction = false; c.currentAction = {} })
  const pending = [c.callGroupApi({ id }, '첫 대상'), c.callGroupApi({ id: 'second' }, '둘째 대상')]
  first({ stopvirtualmachineresponse: { jobid: 'first' } }); await flushPromises()
  second({ stopvirtualmachineresponse: { jobid: 'second' } }); await Promise.all(pending)
  expect(c.handleResponse).toHaveBeenCalledTimes(2); expect(c.closeAction).toHaveBeenCalledTimes(1)
})

test('synchronous recovery shares the VM standard dialog and keeps its existing synchronous response contract', async () => {
  c.currentAction = { api: 'recoverVirtualMachine', params: [{ name: 'id' }], paramFields: [] }
  expect(c.vmStandardDialog).toBe(true)
  c.postSnapshotAwareAction.mockResolvedValue({ recovervirtualmachineresponse: { virtualmachine: { id, state: 'Stopped' } } })
  c.execSubmit({}); await flushPromises()
  expect(c.vmActionUnknown).toBe(false); expect(c.handleResponse).toHaveBeenCalledTimes(1)
})

test('Public IP reservation uses a fresh VM-standard dialog and invalidates its previous instance', () => {
  c.$route.meta.name = 'publicip'; c.resource = { id, state: 'Free' }; c.$store.getters.apis.reserveIpAddress = {}
  const action = { api: 'reserveIpAddress', component: {} }
  c.execAction(action, false)
  expect(c.currentAction.standardDialogSize).toBe('md'); expect(Autogen.computed.publicIpReservationDialog.call(c)).toBe(true)
  const current = c.currentAction.reservationCurrent; expect(current()).toBe(true)
  c.execAction(action, false); expect(current()).toBe(false)
})

test.each(['permission', 'state', 'group'])('Public IP %s gate prevents opening an invalid reservation', kind => {
  c.$route.meta.name = 'publicip'; c.resource = { id, state: kind === 'state' ? 'Reserved' : 'Free' }
  c.$store.getters.apis = kind === 'permission' ? {} : { reserveIpAddress: {} }; c.showAction = false
  c.execAction({ api: 'reserveIpAddress', component: {} }, kind === 'group')
  expect(c.showAction).toBe(false)
})

function watchVmListDialog (api, routeName = 'vm') {
  postAPI.mockClear()
  c.$route = { name: routeName, meta: { name: routeName }, path: '/' + routeName, fullPath: '/' + routeName, params: {}, query: {} }
  c.resource = {}; c.showAction = false; c.currentAction = {}; c.dataView = false
  c.storageActionReady = true
  c.$emit = jest.fn()
  c.$store.getters.apis = { [api]: { params: [] } }
  store.getters.apis = c.$store.getters.apis
  c = reactive(c)
  c.execAction = Autogen.methods.execAction.bind(c)
  c.getArgs = jest.fn(() => [])
  c.getFilters = jest.fn()
  c.getFirstIndexFocus = jest.fn()
  c.closeAction = jest.fn(() => { c.showAction = false })
  const config = Autogen.watch.vmDialogScope
  const handler = typeof config === 'function' ? config : config.handler
  const stop = watch(computed(() => Autogen.computed.vmDialogScope.call(c)), handler.bind(c), { flush: config.flush })
  const resource = { id, name: 'target', state: api === 'startVirtualMachine' ? 'Stopped' : 'Running' }
  c.execAction({ api, resource, popup: true }, false)
  return stop
}

test.each(['vm', 'vnfapp'].flatMap(route => ['startVirtualMachine', 'stopVirtualMachine', 'rebootVirtualMachine'].map(api => [route, api])))('%s row %s keeps its confirmation open after Vue updates', async (route, api) => {
  const stop = watchVmListDialog(api, route)
  try {
    expect(c.showAction).toBe(true)
    await nextTick()
    expect(c.showAction).toBe(true)
    expect(c.resource.id).toBe(id)
    expect(c.closeAction).not.toHaveBeenCalled()
    expect(postAPI).not.toHaveBeenCalled()
  } finally { stop() }
})

test.each(['target', 'token', 'project', 'role', 'route', 'permission'])('%s change still closes an already open VM list dialog', async kind => {
  const stop = watchVmListDialog('startVirtualMachine')
  try {
    await nextTick()
    expect(c.showAction).toBe(true)
    if (kind === 'target') c.resource = { ...c.resource, id: 'other' }
    if (kind === 'token') c.$store.state.user.token = 'next'
    if (kind === 'project') c.$store.getters.project = { id: 'other' }
    if (kind === 'role') c.$store.getters.userInfo.roletype = 'User'
    if (kind === 'route') c.$route.fullPath = '/vm?page=2'
    if (kind === 'permission') c.$store.getters.apis = {}
    await nextTick()
    expect(c.showAction).toBe(false)
    expect(c.closeAction).toHaveBeenCalledTimes(1)
  } finally { stop() }
})
