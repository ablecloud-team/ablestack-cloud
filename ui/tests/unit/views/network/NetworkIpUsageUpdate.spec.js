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
import UpdateNetwork from '@/views/network/UpdateNetwork.vue'
import { getAPI, postAPI } from '@/api'
import { isAdmin } from '@/role'
import { networkIpUsageValue } from '@/utils/networkIpUsage'

jest.mock('@/api', () => ({ getAPI: jest.fn(), postAPI: jest.fn() }))
jest.mock('@/role', () => ({ isAdmin: jest.fn() }))
jest.mock('@/utils/mixin', () => ({ mixinForm: { methods: { handleRemoveFields: values => values } } }))

const resource = { id: 'usage-network', name: 'Usage network', displaytext: 'Original', type: 'Shared', zoneid: 'zone', networkofferingid: 'offering', account: 'owner', domainid: 'domain' }
let wrappers, server, getters, poll, failRead, failWrite, fields
const response = network => ({ listnetworksresponse: { network: [network] } })
const defer = () => { let finish; const promise = new Promise(resolve => { finish = resolve }); return { promise, resolve: finish } }
async function mount (extra = {}) {
  const wrapper = shallowMount(UpdateNetwork, {
    props: { resource: { ...resource, ...extra } },
    global: {
      stubs: {
        AFormItem: { template: '<div><slot name="label"/><slot/></div>' },
        ASpin: { template: '<div><slot/></div>' },
        AForm: { template: '<form><slot/></form>', methods: { validate: () => Promise.resolve(), scrollToField: jest.fn() } },
        MoldDialog: { template: '<div><slot/><slot name="footer"/></div>' },
        NetworkOfferingDiagnostics: true
      },
      directives: { focus: {}, 'ctrl-enter': {} },
      mocks: {
        $t: key => key,
        $getApiParams: () => new Proxy(fields, { get: (target, key) => target[key] || { description: '' } }),
        $store: { getters },
        $notifyError: jest.fn(),
        $pollJob: (...args) => poll(...args)
      }
    }
  })
  wrappers.push(wrapper)
  await flushPromises()
  return wrapper
}
beforeEach(() => {
  wrappers = []; server = { ...resource, details: {} }; fields = { hideipaddressusage: {} }
  getters = reactive({ project: {}, userInfo: { id: 'root', roletype: 'Admin' }, sessionkey: 'session-a', apis: { updateNetwork: {}, listNetworks: {} } })
  isAdmin.mockReturnValue(true)
  failRead = false; failWrite = false
  poll = jest.fn().mockResolvedValue({ jobstatus: 1 })
  getAPI.mockReset().mockImplementation((command, params) => {
    if (command === 'listNetworks') return failRead ? Promise.reject(new Error('read denied')) : Promise.resolve(response({ ...server }))
    if (command === 'listZones') return Promise.resolve({ listzonesresponse: { zone: [{ id: 'zone' }] } })
    return Promise.resolve({ listnetworkofferingsresponse: { networkoffering: [{ id: 'offering', guestiptype: 'Shared', service: [] }] } })
  })
  postAPI.mockReset().mockImplementation((command, params) => {
    if (failWrite) return Promise.reject(new Error('write denied'))
    server = { ...server, ...params, details: { ...server.details, ...('hideipaddressusage' in params ? { hideIpAddressUsage: String(params.hideipaddressusage) } : {}) } }
    return Promise.resolve({ updatenetworkresponse: { jobid: 'job-usage' } })
  })
})
afterEach(() => wrappers.forEach(w => w.unmount()))

test.each([[true, true], [false, false], ['true', true], [' FALSE ', false], [null, null], ['invalid', null], [0, null]])('normalizes returned value %p without treating unknown as false', (value, expected) => {
  expect(networkIpUsageValue({ details: { hideIpAddressUsage: value } })).toBe(expected)
})
test('only a confirmed full details map supplies the absent-key false default', () => {
  expect(networkIpUsageValue({ details: {} })).toBe(false)
  expect(networkIpUsageValue({})).toBe(null)
  expect(networkIpUsageValue({ details: [] })).toBe(null)
})
test.each(['true', 'false'])('loads current %s from a fresh read and omits it on unchanged save', async value => {
  server.details.hideIpAddressUsage = value
  const w = await mount()
  expect(w.vm.form.hideipaddressusage).toBe(value === 'true')
  w.vm.form.displaytext = 'Independent change'
  await w.vm.handleSubmit()
  expect(postAPI.mock.calls[0][1]).not.toHaveProperty('hideipaddressusage')
  expect(server.details.hideIpAddressUsage).toBe(value)
  expect(w.emitted('close-action')).toHaveLength(1)
})
test.each([[false, true], [true, false]])('saves %s to %s explicitly and verifies the result', async (before, after) => {
  server.details.hideIpAddressUsage = String(before)
  const w = await mount()
  w.vm.form.hideipaddressusage = after
  await w.vm.handleSubmit()
  expect(postAPI.mock.calls[0][1].hideipaddressusage).toBe(after)
  expect(w.emitted('close-action')).toHaveLength(1)
})
test('failed read leaves an unknown value, preserves independent input and retries', async () => {
  failRead = true
  const w = await mount()
  expect(w.vm.usageOriginal).toBe(null)
  expect(w.vm.form.hideipaddressusage).toBeUndefined()
  w.vm.form.displaytext = 'Keep me'
  failRead = false
  server.details.hideIpAddressUsage = 'true'
  await w.vm.fetchUsageSetting()
  expect(w.vm.form.displaytext).toBe('Keep me')
  expect(w.vm.form.hideipaddressusage).toBe(true)
})
test('a missing details map is unknown and cannot be posted as false', async () => {
  delete server.details
  const w = await mount()
  expect(w.vm.usageOriginal).toBe(null)
  w.vm.form.displaytext = 'Other change'
  await w.vm.handleSubmit()
  expect(postAPI.mock.calls[0][1]).not.toHaveProperty('hideipaddressusage')
})
test.each(['L2', 'Isolated'])('%s never sends the Shared-only flag', async type => {
  const w = await mount({ type })
  w.vm.form.hideipaddressusage = true
  await w.vm.handleSubmit()
  expect(w.vm.usageSupported).toBe(false)
  expect(postAPI.mock.calls[0][1]).not.toHaveProperty('hideipaddressusage')
})
test('a User or unavailable update parameter never exposes or sends the admin field', async () => {
  isAdmin.mockReturnValue(false)
  delete fields.hideipaddressusage
  const w = await mount()
  expect(w.vm.usageSupported).toBe(false)
  w.vm.form.hideipaddressusage = true
  await w.vm.handleSubmit()
  expect(postAPI.mock.calls[0][1]).not.toHaveProperty('hideipaddressusage')
})
test('read permission absence shows unknown without issuing an unauthorized lookup', async () => {
  delete getters.apis.listNetworks
  const w = await mount()
  expect(w.vm.usageError).toBeTruthy()
  expect(w.vm.usageOriginal).toBe(null)
  expect(getAPI.mock.calls.some(([cmd]) => cmd === 'listNetworks')).toBe(false)
})
test('a server conflict before submit retains the draft and sends no update', async () => {
  const w = await mount()
  w.vm.form.hideipaddressusage = true
  server.details.hideIpAddressUsage = 'true'
  await w.vm.handleSubmit()
  expect(postAPI).not.toHaveBeenCalled()
  expect(w.vm.form.hideipaddressusage).toBe(true)
  expect(w.vm.operationError).toBe('message.network.ip.usage.conflict')
})
test('write failure keeps the draft and original value', async () => {
  const w = await mount()
  w.vm.form.hideipaddressusage = true
  failWrite = true
  await w.vm.handleSubmit()
  expect(w.vm.form.hideipaddressusage).toBe(true)
  expect(w.vm.usageOriginal).toBe(false)
  expect(w.emitted('close-action')).toBeUndefined()
})
test('job failure keeps the draft and allows a deliberate retry', async () => {
  const w = await mount()
  w.vm.form.hideipaddressusage = true
  poll.mockResolvedValueOnce({ jobstatus: 2, jobresult: { errortext: 'job failed' } })
  await w.vm.handleSubmit()
  expect(w.vm.form.hideipaddressusage).toBe(true)
  expect(w.vm.usageOriginal).toBe(false)
  expect(w.vm.pendingJobId).toBe('')
  expect(w.emitted('close-action')).toBeUndefined()
})
test('success with an unconfirmed saved flag remains open and rechecks without another POST', async () => {
  const w = await mount()
  w.vm.form.hideipaddressusage = true
  poll.mockImplementation(async () => { server.details = {}; return { jobstatus: 1 } })
  await w.vm.handleSubmit()
  expect(w.vm.operationError).toBe('message.network.ip.usage.verify.failed')
  expect(w.emitted('close-action')).toBeUndefined()
  server.details.hideIpAddressUsage = 'true'
  poll.mockResolvedValue({ jobstatus: 1 })
  await w.vm.recheckUpdate()
  expect(postAPI).toHaveBeenCalledTimes(1)
  expect(w.emitted('close-action')).toHaveLength(1)
})
test('a late response cannot restore a prior network or prior login scope', async () => {
  const w = await mount()
  const old = defer()
  const normal = getAPI.getMockImplementation()
  getAPI.mockImplementationOnce(() => old.promise)
  const pending = w.vm.fetchUsageSetting()
  getters.sessionkey = 'session-b'
  await flushPromises()
  old.resolve(response({ ...resource, details: { hideIpAddressUsage: 'true' } }))
  await pending
  expect(w.vm.form.hideipaddressusage).toBe(false)
  getAPI.mockImplementation(normal)
})
