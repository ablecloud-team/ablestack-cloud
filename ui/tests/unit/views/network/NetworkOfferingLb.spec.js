// Licensed to the Apache Software Foundation (ASF) under one or more
// contributor license agreements. See the NOTICE file distributed with this
// work for additional information regarding copyright ownership. The ASF
// licenses this file under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License. You may
// obtain a copy at http://www.apache.org/licenses/LICENSE-2.0 . Unless required
// by applicable law or agreed to in writing, software distributed under the
// License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS.

import { shallowMount, flushPromises } from '@vue/test-utils'
import { offeringLbSupport, lbSettingsParams } from '@/utils/networkOfferingLb'
import UpdateNetworkOfferingLb from '@/views/offering/UpdateNetworkOfferingLb'
import AddNetworkOffering from '@/views/offering/AddNetworkOffering'
import CloneNetworkOffering from '@/views/offering/CloneNetworkOffering'
import { getAPI } from '@/utils/storageApi'
import { postAPI } from '@/api'
jest.mock('@/utils/storageApi', () => ({ getAPI: jest.fn() }))
jest.mock('@/api', () => ({ postAPI: jest.fn() }))
const row = { id: 'a', name: 'QA', guestiptype: 'Isolated', maxconnections: 257, keepaliveenabled: true, service: [{ name: 'Lb', provider: [{ name: 'VirtualRouter' }] }] }
const response = r => ({ listnetworkofferingsresponse: { networkoffering: [r] } })
let wrappers
beforeEach(() => { jest.clearAllMocks(); wrappers = []; getAPI.mockResolvedValue(response(row)); postAPI.mockResolvedValue({}) })
afterEach(() => wrappers.forEach(w => w.unmount()))
function mountDialog () {
  const w = shallowMount(UpdateNetworkOfferingLb, { props: { resource: row }, global: { mocks: { $t: k => k, $message: { success: jest.fn() } } } })
  wrappers.push(w)
  return w
}
test.each(['VirtualRouter', 'VpcVirtualRouter', 'InternalLbVm'])('%s HAProxy service is eligible', provider => {
  expect(offeringLbSupport({ ...row, service: [{ name: 'Lb', provider: [{ name: provider }] }] })).toBe('')
})
test.each([
  [{ ...row, service: [] }, 'no.service'],
  [{ ...row, service: [{ name: 'Lb' }] }, 'unknown.provider'],
  [{ ...row, service: [{ name: 'Lb', provider: [{ name: 'Netscaler' }] }] }, 'provider'],
  [{ ...row, guestiptype: 'Shared' }, 'type'],
  [{ ...row, guestiptype: 'L2' }, 'type']
])('unsupported combinations provide a reason', (offering, reason) => expect(offeringLbSupport(offering)).toBe(reason))
test.each([0, 1, 2147483647])('API and database intersection boundary %i is preserved', maxconnections => expect(lbSettingsParams({ maxconnections, keepaliveenabled: false })).toEqual({ maxconnections, keepaliveenabled: false }))
test.each([-2147483648, -1, 1.1, 2147483648, 'text'])('invalid maxconnections %s is rejected', maxconnections => expect(() => lbSettingsParams({ maxconnections })).toThrow())
test('unknown and blank are omitted; false is distinct; unchanged fields are absent', () => {
  expect(lbSettingsParams({ maxconnections: '', keepaliveenabled: undefined }, row, true)).toEqual({})
  expect(lbSettingsParams({ maxconnections: 257, keepaliveenabled: true }, row, true)).toEqual({})
  expect(lbSettingsParams({ keepaliveenabled: false }, row, true)).toEqual({ keepaliveenabled: false })
})
test('no-change save does not write', async () => {
  const w = mountDialog(); await flushPromises(); await w.vm.save()
  expect(postAPI).not.toHaveBeenCalled()
  expect(w.emitted('close-action')).toHaveLength(1)
})
test('false-only edit submits exactly id and false, and verifies persisted value', async () => {
  const w = mountDialog(); await flushPromises(); w.vm.draft.keepaliveenabled = false
  getAPI.mockResolvedValue(response({ ...row, keepaliveenabled: false }))
  await w.vm.save()
  expect(postAPI).toHaveBeenCalledWith('updateNetworkOffering', { id: 'a', keepaliveenabled: false })
  expect(w.emitted('refresh-data')).toHaveLength(1)
})
test('write/read failures preserve draft and do not report success', async () => {
  const w = mountDialog(); await flushPromises(); w.vm.draft.maxconnections = 300
  postAPI.mockRejectedValue(new Error('write failed')); await w.vm.save()
  expect(w.vm.draft.maxconnections).toBe(300)
  expect(w.emitted('close-action')).toBeUndefined()
  postAPI.mockResolvedValue({}); getAPI.mockRejectedValue(new Error('read failed')); await w.vm.save()
  expect(w.vm.draft.maxconnections).toBe(300)
  expect(w.emitted('refresh-data')).toBeUndefined()
})
test('failed initial read cannot save unknown defaults', async () => {
  getAPI.mockRejectedValue(new Error('denied'))
  const w = mountDialog(); await flushPromises()
  expect(w.vm.canSave).toBe(false); await w.vm.save(); expect(postAPI).not.toHaveBeenCalled()
})
test('late old resource response does not replace current target', async () => {
  let oldResolve
  getAPI.mockReturnValueOnce(new Promise(resolve => { oldResolve = resolve }))
  const w = mountDialog(); await flushPromises()
  getAPI.mockResolvedValue(response({ ...row, id: 'b', keepaliveenabled: false }))
  await w.setProps({ resource: { ...row, id: 'b' } }); await flushPromises()
  oldResolve(response(row)); await flushPromises()
  expect(w.vm.current.id).toBe('b')
  expect(w.vm.draft.keepaliveenabled).toBe(false)
})

test.each([AddNetworkOffering, CloneNetworkOffering])('$name preserves false and zero, omits unsupported stale fields and prevents duplicate submission', async component => {
  for (const [type, providers, supported] of [['isolated', { Lb: 'VirtualRouter' }, true], ['isolated', { Lb: 'Netscaler' }, false], ['l2', {}, false], ['shared', { Lb: 'VirtualRouter' }, false]]) {
    postAPI.mockReset()
    let complete
    postAPI.mockReturnValue(new Promise(resolve => { complete = resolve }))
    const form = { name: 'Keep name', displaytext: 'Keep draft', guestiptype: type, maxconnections: 0, keepaliveenabled: false, ispublic: true, conservemode: true }
    const context = {
      loading: false,
      form,
      formRef: { value: { validate: () => Promise.resolve(), scrollToField: jest.fn() } },
      resource: { id: 'source', traffictype: 'Guest' },
      customize: true,
      servicesReady: true,
      guestType: type,
      selectedServiceProviderMap: providers,
      supportedServices: Object.entries(providers).map(([name, provider]) => ({ name, provider: [{ name: provider }] })),
      registeredServicePackages: [],
      domains: [],
      zones: [],
      $t: k => k,
      $message: { success: jest.fn(), error: jest.fn() },
      $emit: jest.fn(),
      $notifyError: jest.fn(),
      closeAction: jest.fn()
    }
    component.methods.handleSubmit.call(context, { preventDefault: jest.fn() })
    component.methods.handleSubmit.call(context, { preventDefault: jest.fn() })
    await flushPromises()
    expect(postAPI).toHaveBeenCalledTimes(1)
    const params = postAPI.mock.calls[0][1]
    if (supported) expect(params).toMatchObject({ maxconnections: 0, keepaliveenabled: false })
    else { expect(params).not.toHaveProperty('maxconnections'); expect(params).not.toHaveProperty('keepaliveenabled') }
    complete({}); await flushPromises()
    expect(context.loading).toBe(false)
    expect(form.displaytext).toBe('Keep draft')
  }
})
