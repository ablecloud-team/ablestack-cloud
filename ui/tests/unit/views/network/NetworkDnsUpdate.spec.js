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
import { networkDnsSupport, buildNetworkDnsParams, networkDnsMatches, networkDnsNeedsZone } from '@/utils/networkDnsUpdate'

jest.mock('@/api', () => ({ getAPI: jest.fn(), postAPI: jest.fn() }))
jest.mock('@/role', () => ({ isAdmin: () => true }))
jest.mock('@/utils/mixin', () => ({ mixinForm: { methods: { handleRemoveFields: values => values } } }))

const apiFields = { dns1: {}, dns2: {}, ip6dns1: {}, ip6dns2: {} }
const resource = { id: 'dns-network', name: 'DNS network', displaytext: 'Original', type: 'Isolated', zoneid: 'zone', networkofferingid: 'offering', dns1: '8.8.8.8' }
const offering = { id: 'offering', name: 'DNS offering', guestiptype: 'Isolated', internetprotocol: 'IPv4', state: 'Enabled', service: [{ name: 'Dns' }] }
const zone = { id: 'zone', dns1: '8.8.8.8', ip6dns1: '2001:4860:4860::8888', ip6dns2: '2001:4860:4860::8844' }
const both = { ipv4: true, ipv6: true }
let wrappers, serverNetwork, currentOffering, poll, failRead, failZone, submitted

test.each([
  ['L2', {}, offering, { ipv4: false, ipv6: false }],
  ['Isolated', { vpcid: 'vpc' }, offering, { ipv4: false, ipv6: false }],
  ['Isolated', {}, { ...offering, service: [] }, { ipv4: false, ipv6: false }],
  ['Isolated', {}, offering, { ipv4: true, ipv6: false }],
  ['Isolated', {}, { ...offering, internetprotocol: 'DualStack' }, both],
  ['Isolated', {}, { ...offering, internetprotocol: 'IPv6' }, both],
  ['Shared', { ip6cidr: 'fd55::/64' }, offering, both],
  ['Shared', {}, offering, { ipv4: true, ipv6: false }]
])('DNS support follows server type/service/protocol contract: %s %p', (type, extra, selected, expected) => {
  expect(networkDnsSupport({ ...resource, type, ...extra }, selected, apiFields)).toEqual(expected)
})

test('a partially exposed IPv6 API never exposes or transmits half of its pair', () => {
  expect(networkDnsSupport(resource, { ...offering, internetprotocol: 'DualStack' }, { dns1: {}, dns2: {}, ip6dns1: {} })).toEqual({ ipv4: true, ipv6: false })
})

test.each([
  [{ dns2: '1.1.1.1' }, { dns1: '8.8.8.8' }, { dns1: '8.8.8.8', dns2: '1.1.1.1' }],
  [{ dns1: '9.9.9.9' }, { dns1: '8.8.8.8', dns2: '1.1.1.1' }, { dns1: '9.9.9.9', dns2: '1.1.1.1' }],
  [{ dns1: '1.1.1.1', dns2: '1.0.0.1' }, resource, { dns1: '1.1.1.1', dns2: '1.0.0.1' }],
  [{ dns2: '' }, { dns1: '9.9.9.9', dns2: '1.1.1.1' }, { dns1: '9.9.9.9', dns2: '' }],
  [{ dns1: '', dns2: '' }, resource, { dns1: '', dns2: '' }],
  [{ dns1: undefined, dns2: undefined }, resource, {}],
  [{ dns1: ' 8.8.8.8 ', dns2: '' }, resource, {}],
  [{ ip6dns2: '2606:4700:4700::1001' }, { ip6dns1: '2606:4700:4700:0:0:0:0:1111' }, { ip6dns1: '2606:4700:4700::1111', ip6dns2: '2606:4700:4700::1001' }],
  [{ ip6dns1: '', ip6dns2: '' }, { ip6dns1: '2606:4700:4700::1111' }, { ip6dns1: '', ip6dns2: '' }]
])('DNS request preserves partner and explicit resets: %p', (form, original, expected) => {
  expect(buildNetworkDnsParams(form, original, both)).toEqual(expected)
})

test('unsupported or unchanged address families never leak into an independent update', () => {
  expect(buildNetworkDnsParams({ dns1: '8.8.8.8', ip6dns1: 'fd55::1' }, resource, { ipv4: true, ipv6: false })).toEqual({})
  expect(buildNetworkDnsParams({ dns1: '1.1.1.1' }, resource, { ipv4: false, ipv6: false })).toEqual({})
})

test('verification distinguishes secondary removal, pair inheritance, and equivalent IPv6 forms', () => {
  expect(networkDnsMatches({ dns1: '9.9.9.9' }, { dns1: '9.9.9.9', dns2: '' }, { dns2: '1.1.1.1' })).toBe(true)
  expect(networkDnsNeedsZone({ dns1: '', dns2: '' })).toBe(true)
  expect(networkDnsNeedsZone({ dns1: '9.9.9.9', dns2: '' })).toBe(false)
  expect(networkDnsMatches({ dns1: zone.dns1 }, { dns1: '', dns2: '' }, zone)).toBe(true)
  expect(networkDnsMatches({ dns1: '9.9.9.9' }, { dns1: '', dns2: '' }, zone)).toBe(false)
  expect(networkDnsMatches({ ip6dns1: '2606:4700:4700:0:0:0:0:1111' }, { ip6dns1: '2606:4700:4700::1111', ip6dns2: '' })).toBe(true)
})

async function mountDialog (extra = {}, selected = {}) {
  serverNetwork = { ...resource, ...extra }
  currentOffering = { ...offering, guestiptype: serverNetwork.type, ...selected }
  const wrapper = shallowMount(UpdateNetwork, {
    props: { resource: { ...serverNetwork } },
    global: {
      stubs: {
        AFormItem: { template: '<div><slot/></div>' },
        NetworkOfferingDiagnostics: { template: '<div/>', methods: { validate: () => Promise.resolve(true) } },
        ASpin: { template: '<div><slot/></div>' },
        MoldDialog: { template: '<div><slot/><slot name="footer"/></div>' },
        AForm: { template: '<form><slot/></form>', methods: { validate: () => Promise.resolve(), scrollToField: jest.fn() } }
      },
      directives: { focus: {}, 'ctrl-enter': {} },
      mocks: {
        $t: key => key,
        $getApiParams: () => new Proxy(apiFields, { get: (target, key) => target[key] || { description: '' } }),
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
  wrappers = []; failRead = false; failZone = false; submitted = false
  poll = jest.fn().mockResolvedValue({ jobstatus: 1 })
  getAPI.mockReset().mockImplementation((command) => {
    if (command === 'listZones') return submitted && failZone ? Promise.reject(new Error('Zone lookup failed')) : Promise.resolve({ listzonesresponse: { zone: [zone] } })
    if (command === 'listNetworks') return failRead ? Promise.reject(new Error('Network lookup failed')) : Promise.resolve({ listnetworksresponse: { network: [{ ...serverNetwork }] } })
    return Promise.resolve({ listnetworkofferingsresponse: { count: 1, networkoffering: [currentOffering] } })
  })
  postAPI.mockReset().mockImplementation((command, params) => {
    submitted = true
    serverNetwork = { ...serverNetwork, ...params }
    if (params.dns1 === '') { serverNetwork.dns1 = zone.dns1; serverNetwork.dns2 = zone.dns2 }
    if (params.ip6dns1 === '') { serverNetwork.ip6dns1 = zone.ip6dns1; serverNetwork.ip6dns2 = zone.ip6dns2 }
    return Promise.resolve({ updatenetworkresponse: { jobid: 'dns-job' } })
  })
})
afterEach(() => wrappers.forEach(w => w.unmount()))

test('DNS2-only edit sends the current DNS1 and verifies the final network before closing', async () => {
  const w = await mountDialog()
  w.vm.form.dns2 = '1.1.1.1'
  await w.vm.handleSubmit()
  expect(postAPI.mock.calls[0][1]).toMatchObject({ dns1: '8.8.8.8', dns2: '1.1.1.1' })
  expect(w.emitted('close-action')).toHaveLength(1)
})

test.each(['Isolated', 'L2', 'Shared'])('%s metadata edits preserve multiline/empty descriptions and omit an unchanged description', async type => {
  const w = await mountDialog({ type })
  const value = '네트워크 설명\n둘째 줄 <img src=x> & "특수문자"'
  w.vm.form.displaytext = value
  await w.vm.handleSubmit()
  expect(postAPI.mock.calls[0][1]).toMatchObject({ name: resource.name, displaytext: value })
  expect(w.emitted('close-action')).toHaveLength(1)
  const empty = await mountDialog({ type })
  empty.vm.form.displaytext = ''
  await empty.vm.handleSubmit()
  expect(postAPI.mock.calls[1][1].displaytext).toBe('')
  const name = await mountDialog({ type, displaytext: undefined })
  name.vm.form.name = 'Renamed'
  await name.vm.handleSubmit()
  expect(postAPI.mock.calls[2][1]).not.toHaveProperty('displaytext')
  expect(postAPI.mock.calls[2][1].name).toBe('Renamed')
})

test.each(['name', 'displaytext'])('a successful job with a mismatched final %s keeps input and rechecks the same job', async field => {
  const w = await mountDialog()
  const api = getAPI.getMockImplementation()
  let stale = true
  getAPI.mockImplementation((command, ...args) => command === 'listNetworks' && stale
    ? Promise.resolve({ listnetworksresponse: { network: [{ ...serverNetwork, [field]: resource[field] }] } })
    : api(command, ...args))
  w.vm.form[field] = '수정한 값\n둘째 줄'
  await w.vm.handleSubmit()
  expect(w.vm.operationError).toBe('message.network.description.verify.failed')
  expect(w.vm.form[field]).toBe('수정한 값\n둘째 줄')
  expect(w.props('resource')[field]).toBe(resource[field])
  expect(w.emitted('close-action')).toBeUndefined()
  stale = false
  await w.vm.recheckUpdate()
  expect(postAPI).toHaveBeenCalledTimes(1)
  expect(w.emitted('close-action')).toHaveLength(1)
})

test.each([{ type: 'L2' }, { vpcid: 'vpc' }])('hidden DNS fields cannot leak from retained form values: %p', async extra => {
  const w = await mountDialog(extra)
  w.vm.form.dns2 = '1.1.1.1'; w.vm.form.ip6dns2 = 'fd55::2'
  await w.vm.handleSubmit()
  expect(postAPI.mock.calls[0][1]).not.toHaveProperty('dns1')
  expect(postAPI.mock.calls[0][1]).not.toHaveProperty('dns2')
  expect(postAPI.mock.calls[0][1]).not.toHaveProperty('ip6dns2')
})

test('Shared IPv6 DNS2-only edit sends only its IPv6 pair', async () => {
  const w = await mountDialog({ type: 'Shared', ip6cidr: 'fd55::/64', ip6dns1: '2606:4700:4700::1111' })
  w.vm.form.ip6dns2 = '2606:4700:4700::1001'
  await w.vm.handleSubmit()
  expect(postAPI.mock.calls[0][1]).toMatchObject({ ip6dns1: '2606:4700:4700::1111', ip6dns2: '2606:4700:4700::1001' })
  expect(postAPI.mock.calls[0][1]).not.toHaveProperty('dns1')
  expect(w.emitted('close-action')).toHaveLength(1)
})

test('an independent description change omits both DNS families', async () => {
  const w = await mountDialog()
  w.vm.form.displaytext = 'Changed description'
  await w.vm.handleSubmit()
  expect(postAPI.mock.calls[0][1]).toMatchObject({ displaytext: 'Changed description' })
  expect(postAPI.mock.calls[0][1]).not.toHaveProperty('dns1')
  expect(postAPI.mock.calls[0][1]).not.toHaveProperty('ip6dns1')
})

test('empty DNS pair reaches the API and is checked against fresh zone defaults', async () => {
  const w = await mountDialog({ dns1: '9.9.9.9', dns2: '1.1.1.1' })
  w.vm.form.dns1 = ''; w.vm.form.dns2 = ''
  await w.vm.handleSubmit()
  expect(postAPI.mock.calls[0][1]).toMatchObject({ dns1: '', dns2: '' })
  expect(getAPI.mock.calls.filter(([command]) => command === 'listZones')).toHaveLength(2)
  expect(w.emitted('close-action')).toHaveLength(1)
})

test.each(['network', 'zone'])('post-job %s lookup failure preserves values and retry reuses the accepted job', async failure => {
  const w = await mountDialog()
  if (failure === 'zone') { w.vm.form.dns1 = ''; w.vm.form.dns2 = ''; failZone = true } else { w.vm.form.dns2 = '1.1.1.1'; failRead = true }
  await w.vm.handleSubmit()
  expect(w.vm.pendingJobId).toBe('dns-job')
  expect(w.vm.operationError).toContain('lookup failed')
  expect(w.emitted('close-action')).toBeUndefined()
  expect(w.vm.form.dns2).toBe(failure === 'zone' ? '' : '1.1.1.1')
  await w.vm.handleSubmit()
  expect(postAPI).toHaveBeenCalledTimes(1)
  failRead = false; failZone = false
  await w.vm.recheckUpdate()
  expect(postAPI).toHaveBeenCalledTimes(1)
  expect(poll.mock.calls.map(([options]) => options.jobId)).toEqual(['dns-job', 'dns-job'])
  expect(w.emitted('close-action')).toHaveLength(1)
})

test('job success with wrong final DNS remains open and blocks a second update', async () => {
  const w = await mountDialog()
  w.vm.form.dns2 = '1.1.1.1'
  postAPI.mockResolvedValue({ updatenetworkresponse: { jobid: 'dns-job' } })
  await w.vm.handleSubmit()
  expect(w.vm.operationError).toBe('message.network.dns.verify.failed')
  expect(w.vm.pendingJobId).toBe('dns-job')
  expect(w.emitted('close-action')).toBeUndefined()
  await w.vm.handleSubmit()
  expect(postAPI).toHaveBeenCalledTimes(1)
})

test.each(['request', 'job'])('%s refusal keeps the edited DNS and explicit retry succeeds', async failure => {
  const w = await mountDialog()
  w.vm.form.dns2 = '1.1.1.1'
  if (failure === 'request') postAPI.mockRejectedValueOnce(new Error('Request refused'))
  else poll.mockResolvedValueOnce({ jobstatus: 2, jobresult: { errortext: 'Job refused' } })
  await w.vm.handleSubmit()
  expect(w.vm.operationError).toContain('refused')
  expect(w.vm.form.dns2).toBe('1.1.1.1')
  expect(w.vm.pendingJobId).toBe('')
  expect(w.emitted('close-action')).toBeUndefined()
  await w.vm.handleSubmit()
  expect(w.emitted('close-action')).toHaveLength(1)
})
