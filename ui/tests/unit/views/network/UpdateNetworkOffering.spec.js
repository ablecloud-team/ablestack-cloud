// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements. See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership. The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License. You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied. See the License for the
// specific language governing permissions and limitations
// under the License.

import { shallowMount, flushPromises } from '@vue/test-utils'
import UpdateNetwork from '@/views/network/UpdateNetwork.vue'
import { getAPI, postAPI } from '@/api'
import { networkOfferingCandidateParams, readNetworkOfferingCandidates, networkOfferingFingerprint } from '@/utils/networkOfferingChange'

jest.mock('@/api', () => ({ getAPI: jest.fn(), postAPI: jest.fn() }))
jest.mock('@/role', () => ({ isAdmin: () => true }))
jest.mock('@/utils/mixin', () => ({ mixinForm: { methods: { handleRemoveFields: values => values } } }))

const current = { id: 'current-uuid', name: 'Current', state: 'Enabled', guestiptype: 'Isolated', service: [{ name: 'Dns', provider: [{ name: 'VirtualRouter' }] }] }
const candidate = { ...current, id: 'candidate-uuid', name: 'Compatible' }
const resource = { id: 'network-a', name: 'Network A', displaytext: 'Description', zoneid: 'zone', type: 'Isolated', networkofferingid: current.id, account: 'owner-a', domainid: 'domain' }
const defer = () => { let finish; let fail; const promise = new Promise((resolve, reject) => { finish = resolve; fail = reject }); return { promise, resolve: finish, reject: fail } }
const offeringResponse = items => ({ listnetworkofferingsresponse: { count: items.length, networkoffering: items } })
let offerings, currentOffering, serverNetwork, wrappers, poll
const mountDialog = async (props = {}) => {
  const wrapper = shallowMount(UpdateNetwork, {
    props: { resource: { ...resource, ...props } },
    global: {
      stubs: {
        AFormItem: { template: '<div><slot/></div>' },
        NetworkOfferingDiagnostics: { template: '<div/>', methods: { validate: () => Promise.resolve(true) } },
        ASpin: { template: '<div><slot/></div>' },
        MoldDialog: { template: '<div role="dialog"><slot/><footer><slot name="footer"/></footer></div>' },
        AForm: { template: '<form><slot/></form>', methods: { validate: () => Promise.resolve(), scrollToField: jest.fn() } }
      },
      directives: { focus: {}, 'ctrl-enter': {} },
      mocks: {
        $t: key => key,
        $getApiParams: () => new Proxy({}, { get: () => ({ description: '' }) }),
        $store: { getters: { project: {} } },
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
  wrappers = []; offerings = [candidate]; currentOffering = { ...current }; serverNetwork = { ...resource }
  poll = jest.fn().mockResolvedValue({ jobstatus: 1 })
  getAPI.mockReset().mockImplementation((command, params) => {
    if (command === 'listZones') return Promise.resolve({ listzonesresponse: { zone: [{}] } })
    if (command === 'listNetworks') return Promise.resolve({ listnetworksresponse: { network: [{ ...serverNetwork }] } })
    return Promise.resolve(offeringResponse(params.networkid ? offerings : [currentOffering]))
  })
  postAPI.mockReset().mockImplementation((command, params) => {
    serverNetwork = { ...serverNetwork, ...params }
    return Promise.resolve({ updatenetworkresponse: { jobid: 'job-uuid' } })
  })
})
afterEach(() => wrappers.forEach(w => w.unmount()))

test('candidate query is network-specific and preserves VPC/type scope', () => {
  expect(networkOfferingCandidateParams({ ...resource, vpcid: 'vpc' })).toEqual({ networkid: resource.id, zoneid: resource.zoneid, state: 'Enabled', guestiptype: 'Isolated', forvpc: true })
})
test('all server pages are read and duplicate/incomplete pages fail closed', async () => {
  const call = jest.fn().mockResolvedValueOnce({ listnetworkofferingsresponse: { count: 2, networkoffering: [candidate] } })
    .mockResolvedValueOnce({ listnetworkofferingsresponse: { count: 2, networkoffering: [{ ...candidate, id: 'second' }] } })
  expect((await readNetworkOfferingCandidates(call, resource)).map(o => o.id)).toEqual([candidate.id, 'second'])
  expect(call.mock.calls[1][1].page).toBe(2)
  const duplicate = jest.fn().mockResolvedValue({ listnetworkofferingsresponse: { count: 2, networkoffering: [candidate] } })
  await expect(readNetworkOfferingCandidates(duplicate, resource)).rejects.toThrow('changed while paging')
  await expect(readNetworkOfferingCandidates(jest.fn().mockResolvedValue({ listnetworkofferingsresponse: { count: 1 } }), resource)).rejects.toThrow('Incomplete')
})
test('current UUID is a separate option even when absent from compatible candidates', async () => {
  const w = await mountDialog()
  expect(w.vm.form.networkofferingid).toBe(current.id)
  expect(w.vm.offeringOptions.map(o => o.id)).toEqual([current.id, candidate.id])
  expect(getAPI.mock.calls.some(([cmd, args]) => cmd === 'listNetworkOfferings' && args.networkid === resource.id)).toBe(true)
  w.vm.form.networkofferingid = candidate.id
  offerings = [{ ...candidate, id: 'other' }, candidate]
  await w.vm.fetchNetworkOfferingData()
  expect(w.vm.form.networkofferingid).toBe(candidate.id)
  expect(w.vm.networkOffering.id).toBe(candidate.id)
})
test.each(['Disabled', 'Enabled'])('current %s offering and zero candidates allow independent edits', async state => {
  currentOffering.state = state; offerings = []
  const w = await mountDialog()
  expect(w.vm.offeringOptions[0].state).toBe(state)
  expect(w.vm.submitBlocked).toBe(false)
  w.vm.form.displaytext = 'New description'
  await w.vm.handleSubmit()
  expect(postAPI.mock.calls[0][1]).toMatchObject({ id: resource.id, displaytext: 'New description' })
  expect(postAPI.mock.calls[0][1]).not.toHaveProperty('networkofferingid')
  expect(w.emitted('close-action')).toHaveLength(1)
})
test.each(['L2', 'Shared'])('%s does not expose an Isolated offering change query', async type => {
  const w = await mountDialog({ type })
  expect(w.vm.isUpdatingIsolatedNetwork).toBe(false)
  expect(getAPI.mock.calls.some(([, args]) => args.networkid)).toBe(false)
  expect(w.vm.form.networkofferingid).toBe(current.id)
})
test('candidate failure blocks a change, keeps inputs, and retry restores the UUID list', async () => {
  const w = await mountDialog()
  w.vm.form.networkofferingid = candidate.id
  w.vm.form.displaytext = 'Keep this input'
  getAPI.mockRejectedValueOnce(new Error('lookup failed'))
  await w.vm.fetchNetworkOfferingData()
  expect(w.vm.networkOfferingError).toBeTruthy()
  expect(w.vm.submitBlocked).toBe(true)
  expect(w.vm.form.displaytext).toBe('Keep this input')
  await w.vm.fetchNetworkOfferingData()
  expect(w.vm.networkOfferingError).toBe('')
  expect(w.vm.form.networkofferingid).toBe(candidate.id)
})
test('late owner/network response cannot replace the new scope or revive an A-B-A read', async () => {
  const w = await mountDialog()
  const old = defer()
  const normal = getAPI.getMockImplementation()
  getAPI.mockImplementation((cmd, args) => cmd === 'listNetworkOfferings' && args.networkid === resource.id ? old.promise : normal(cmd, args))
  const read = w.vm.fetchNetworkOfferingData()
  await w.setProps({ resource: { ...resource, id: 'network-b', account: 'owner-b' } })
  getAPI.mockImplementation(normal)
  await w.setProps({ resource: { ...resource } }); await flushPromises()
  old.resolve(offeringResponse([{ ...candidate, id: 'stale' }]))
  await read
  expect(w.vm.networkOfferings.map(o => o.id)).toEqual([candidate.id])
  expect(w.vm.form.networkofferingid).toBe(current.id)
})
test('first submit reviews without a write; confirmation revalidates and writes the UUID only', async () => {
  const w = await mountDialog()
  w.vm.form.networkofferingid = candidate.id
  await w.vm.handleSubmit()
  expect(postAPI).not.toHaveBeenCalled()
  expect(w.vm.offeringReview).toBeTruthy()
  const reads = getAPI.mock.calls.filter(([, args]) => args.networkid).length
  await w.vm.handleSubmit(undefined, true)
  expect(getAPI.mock.calls.filter(([, args]) => args.networkid).length).toBeGreaterThan(reads)
  expect(postAPI.mock.calls[0][1].networkofferingid).toBe(candidate.id)
  expect(w.emitted('refresh-data')).toHaveLength(1)
  expect(w.emitted('close-action')).toHaveLength(1)
})

test('provider service loss after review prevents confirmed offering change', async () => {
  const w = await mountDialog()
  w.vm.form.networkofferingid = candidate.id
  await w.vm.handleSubmit()
  expect(w.vm.offeringReview).toBeTruthy()
  w.vm.$refs.offeringDiagnostics.validate = () => Promise.resolve(false)
  await w.vm.handleSubmit(undefined, true)
  expect(postAPI).not.toHaveBeenCalled()
  expect(w.vm.operationError).toBe('message.network.diagnostics.invalid')
})
test.each(['disabled', 'network-changed'])('concurrent %s refuses a write and preserves the form', async reason => {
  const w = await mountDialog()
  w.vm.form.networkofferingid = candidate.id
  w.vm.form.displaytext = 'Keep me'
  await w.vm.handleSubmit()
  if (reason === 'disabled') offerings = [{ ...candidate, state: 'Disabled' }]
  else serverNetwork.networkofferingid = 'someone-elses-change'
  await w.vm.handleSubmit(undefined, true)
  expect(postAPI).not.toHaveBeenCalled()
  expect(w.vm.operationError).toBe(reason === 'disabled' ? 'message.network.offering.selection.expired' : 'message.network.offering.network.changed')
  expect(w.vm.submitBlocked).toBe(true)
  expect(w.vm.form.displaytext).toBe('Keep me')
  if (reason === 'network-changed') {
    w.vm.form.networkofferingid = current.id
    expect(w.vm.submitBlocked).toBe(true)
  }
})
test('a disappeared candidate keeps its UUID and name, never displays the current offering as its target', async () => {
  const w = await mountDialog()
  w.vm.form.networkofferingid = candidate.id
  w.vm.selectNetworkOffering(candidate.id)
  offerings = []
  await w.vm.fetchNetworkOfferingData()
  expect(w.vm.form.networkofferingid).toBe(candidate.id)
  expect(w.vm.networkOffering).toMatchObject({ id: candidate.id, name: candidate.name, unavailable: true })
  expect(w.vm.submitBlocked).toBe(true)
  expect(w.find('.network-offering-review').exists()).toBe(false)
  offerings = [candidate]
  await w.vm.fetchNetworkOfferingData()
  expect(w.vm.submitBlocked).toBe(false)
  expect(w.vm.networkOffering.unavailable).toBeUndefined()
})
test('changed provider/capability requires another comparison rather than applying stale consent', async () => {
  const w = await mountDialog()
  w.vm.form.networkofferingid = candidate.id
  await w.vm.handleSubmit()
  offerings = [{ ...candidate, service: [{ name: 'Dns', provider: [{ name: 'OtherProvider' }] }] }]
  await w.vm.handleSubmit(undefined, true)
  expect(postAPI).not.toHaveBeenCalled()
  expect(w.vm.operationError).toBe('message.network.offering.review.again')
  expect(networkOfferingFingerprint(candidate)).not.toBe(networkOfferingFingerprint(offerings[0]))
})
test('accepted job cannot be submitted twice and dialog closes only after job plus network verification', async () => {
  const pending = defer(); poll.mockReturnValue(pending.promise)
  const w = await mountDialog()
  const saving = w.vm.handleSubmit(); await flushPromises()
  expect(w.vm.pendingJobId).toBe('job-uuid')
  expect(w.emitted('close-action')).toBeUndefined()
  await w.vm.handleSubmit()
  expect(postAPI).toHaveBeenCalledTimes(1)
  pending.resolve({ jobstatus: 1 }); await saving
  expect(w.emitted('close-action')).toHaveLength(1)
})
test('job failure keeps edited fields and retry never silently changes the chosen offering', async () => {
  poll.mockResolvedValue({ jobstatus: 2, jobresult: { errortext: 'Rejected by provider' } })
  const w = await mountDialog()
  w.vm.form.displaytext = 'Keep failed input'
  await w.vm.handleSubmit(); await flushPromises()
  expect(w.vm.operationError).toBe('Rejected by provider')
  expect(w.vm.form.displaytext).toBe('Keep failed input')
  expect(w.emitted('close-action')).toBeUndefined()
  expect(w.vm.pendingJobId).toBe('')
})
test('a job success with an unexpected final offering stays open and prevents duplicate writes', async () => {
  const w = await mountDialog()
  w.vm.form.networkofferingid = candidate.id
  await w.vm.handleSubmit()
  postAPI.mockResolvedValue({ updatenetworkresponse: { jobid: 'job-uuid' } })
  await w.vm.handleSubmit(undefined, true)
  expect(w.vm.operationError).toBe('message.network.offering.verify.failed')
  expect(w.emitted('close-action')).toBeUndefined()
  expect(w.vm.submitBlocked).toBe(true)
})

test('the own completed offering refresh retains tracking until the final network read', async () => {
  const pending = defer(); poll.mockReturnValue(pending.promise)
  const w = await mountDialog()
  w.vm.form.networkofferingid = candidate.id
  w.vm.form.displaytext = 'Retain accepted input'
  await w.vm.handleSubmit()
  const saving = w.vm.handleSubmit(undefined, true); await flushPromises()
  await w.setProps({ resource: { ...serverNetwork } })
  expect(w.vm.pendingJobId).toBe('job-uuid')
  expect(w.vm.form.displaytext).toBe('Retain accepted input')
  expect(w.emitted('close-action')).toBeUndefined()
  const reads = getAPI.mock.calls.filter(([cmd]) => cmd === 'listNetworks').length
  pending.resolve({ jobstatus: 1 }); await saving
  expect(getAPI.mock.calls.filter(([cmd]) => cmd === 'listNetworks').length).toBeGreaterThan(reads)
  expect(w.emitted('close-action')).toHaveLength(1)
})

test.each([{ id: 'network-b' }, { account: 'owner-b' }, { networkofferingid: 'unexpected-uuid' }])('an unrelated scope refresh never adopts an old pending update: %p', async changed => {
  const pending = defer(); poll.mockReturnValue(pending.promise)
  const w = await mountDialog()
  w.vm.form.networkofferingid = candidate.id
  await w.vm.handleSubmit()
  const saving = w.vm.handleSubmit(undefined, true); await flushPromises()
  await w.setProps({ resource: { ...serverNetwork, ...changed } }); await flushPromises()
  expect(w.vm.pendingJobId).toBe('')
  pending.resolve({ jobstatus: 1 }); await saving
  expect(w.emitted('close-action')).toBeUndefined()
})
