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

import { shallowMount, flushPromises } from '@vue/test-utils'
import { reactive } from 'vue'
import GuestIpRanges from '@/views/network/GuestIpRanges'
import Editor from '@/views/network/CreateVlanIpRange'
import { getAPI, postAPI } from '@/api'
import { guestIpRangeSupported, guestIpRangeVisible, guestRangesOverlap, buildRangeParams, rangeProject, listAllGuestRanges, currentRangeNetwork } from '@/utils/guestIpRanges'

jest.mock('@/api', () => ({ getAPI: jest.fn(), postAPI: jest.fn() }))
const base = { id: 'n1', zoneid: 'z1', type: 'Isolated', specifyipranges: true, service: [], account: 'owner', domainid: 'd1', cidr: '172.26.31.0/24', gateway: '172.26.31.1', netmask: '255.255.255.0' }
const range = { id: 'r1', networkid: 'n1', startip: '172.26.31.10', endip: '172.26.31.20', gateway: base.gateway, netmask: base.netmask }
const commands = ['listNetworks', 'listVlanIpRanges', 'createVlanIpRange', 'updateVlanIpRange', 'deleteVlanIpRange']
let getters, rows, networks, wrappers
const reply = data => Promise.resolve(data)
const httpError = text => ({ response: { status: 530, data: { errorresponse: { errortext: text } } } })
beforeEach(() => {
  jest.clearAllMocks(); wrappers = []; rows = [{ ...range }]; networks = [base]
  getters = reactive({ apis: Object.fromEntries(commands.map(a => [a, {}])), userInfo: { id: 'user' }, project: null })
  getAPI.mockImplementation((command, params) => {
    if (command === 'listNetworks') return reply({ listnetworksresponse: { network: networks.filter(n => n.id === params.id) } })
    if (command === 'listVlanIpRanges') { const found = rows.filter(r => r.networkid === params.networkid); return reply({ listvlaniprangesresponse: { count: found.length, vlaniprange: found } }) }
    throw Error('Unexpected API ' + command)
  })
  postAPI.mockImplementation((command, params) => {
    if (command === 'createVlanIpRange') { rows.push({ ...params, id: 'new' }); return reply({ createvlaniprangeresponse: { vlan: { id: 'new' } } }) }
    if (command === 'updateVlanIpRange') { rows = rows.map(r => r.id === params.id ? { ...r, ...params } : r); return reply({ updatevlaniprangeresponse: { id: params.id } }) }
    if (command === 'deleteVlanIpRange') { rows = rows.filter(r => r.id !== params.id); return reply({ deletevlaniprangeresponse: { success: true } }) }
    throw Error('Unexpected write ' + command)
  })
})
afterEach(() => wrappers.forEach(w => w.unmount()))
async function mountView (component, props = {}) {
  const w = shallowMount(component, { props: { resource: { ...base }, ...props }, global: { stubs: { MoldDialog: { template: '<div><slot /></div>' } }, mocks: { $store: { getters, state: { user: { token: 'session' } } }, $t: k => k, $getApiParams: () => Object.fromEntries(['startipv6', 'endipv6', 'ip6gateway', 'ip6cidr'].map(k => [k, {}])), $notification: { success: jest.fn() }, $message: { success: jest.fn() } } } })
  wrappers.push(w); await flushPromises(); return w
}
const form4 = { family: 'ipv4', startip: '172.26.31.30', endip: '172.26.31.34', gateway: base.gateway, netmask: base.netmask }
const form6 = { family: 'ipv6', startipv6: 'fd31::10', endipv6: 'fd31::20', ip6gateway: 'fd31::1', ip6cidr: 'fd31::/64' }

test.each([
  [{}, true], [{ type: 'Shared' }, true], [{ type: 'L2' }, false], [{ type: null }, false],
  [{ service: [{ name: 'SourceNat' }] }, false], [{ service: undefined }, false], [{ service: null }, false], [{ service: [{}] }, false],
  [{ specifyipranges: undefined }, false], [{ specifyipranges: 'true' }, false], [{ specifyipranges: false }, false],
  [{ vpcid: 'v' }, false], [{ vpcname: 'tier' }, false], [{ ip4routing: 'Dynamic' }, false], [{ ip6routing: 'Static' }, false]
])('eligibility %j => %p', (extra, expected) => expect(guestIpRangeSupported({ ...base, ...extra })).toBe(expected))
test('list permission and explicit project binding', () => {
  expect(guestIpRangeVisible(base, {})).toBe(false)
  expect(rangeProject(base)).toEqual({ ignoreproject: true })
  expect(rangeProject({ projectid: 'p' })).toEqual({ projectid: 'p' })
})
test('IPv4, IPv6-only, dual payloads and optional end address', () => {
  expect(buildRangeParams({ ...form4, endip: '' }, base).endip).toBe(form4.startip)
  expect(buildRangeParams(form6, base)).not.toHaveProperty('startip')
  expect(buildRangeParams({ ...form4, ...form6, family: 'dual' }, base, range)).toMatchObject({ id: 'r1', startip: form4.startip, startipv6: form6.startipv6 })
})
test.each([
  { ...form4, startip: '172.26.31.999' }, { ...form4, endip: '172.26.31.29' },
  { ...form4, gateway: '172.26.31.31' }, { ...form4, netmask: '255.0.255.0' },
  { ...form4, gateway: '172.27.31.1' }, { ...form6, ip6cidr: 'fd31::/65' },
  { ...form6, startipv6: 'fd32::10' }, { ...form6, endipv6: 'fd31::0' }, { ...form6, family: 'invalid' }
])('invalid range %j is rejected', form => expect(() => buildRangeParams(form, base)).toThrow())
test('partial or duplicate list responses cannot be treated as complete', async () => {
  getAPI.mockResolvedValueOnce({ listvlaniprangesresponse: { count: 4, vlaniprange: [range] } })
  await expect(listAllGuestRanges(getAPI, base)).rejects.toThrow('message.guest.range.read.failed')
  getAPI.mockResolvedValueOnce({ listvlaniprangesresponse: { count: 2, vlaniprange: [range, range] } })
  await expect(listAllGuestRanges(getAPI, base)).rejects.toThrow()
})
test('an administrator rechecks the exact network ID in the accessible inventory', async () => {
  await expect(currentRangeNetwork(getAPI, base)).resolves.toMatchObject({ id: base.id })
  expect(getAPI).toHaveBeenCalledWith('listNetworks', { id: base.id, listall: true, ignoreproject: true }, { preserveOnFailure: true })
})

test('overlap includes a shared endpoint but does not include adjacent addresses', () => {
  expect(guestRangesOverlap(range, { startip: '172.26.31.20', endip: '172.26.31.30' })).toBe(true)
  expect(guestRangesOverlap(range, { startip: '172.26.31.21' })).toBe(false)
  expect(guestRangesOverlap({ startipv6: '2001:db8::1', endipv6: '2001:db8::10' }, { startipv6: '2001:0db8:0:0::10' })).toBe(true)
})

test('network changed to NAT or another owner is rejected before writing', async () => {
  networks = [{ ...base, service: [{ name: 'SourceNat' }] }]
  await expect(currentRangeNetwork(getAPI, base)).rejects.toThrow('scope.changed')
  networks = [{ ...base, projectid: 'other' }]
  await expect(currentRangeNetwork(getAPI, base)).rejects.toThrow('scope.changed')
})
test('failed refresh retains rows, blocks actions and recovers', async () => {
  const w = await mountView(GuestIpRanges)
  expect(w.vm.ipranges).toHaveLength(1)
  getAPI.mockRejectedValueOnce(httpError('Read failed')); await w.vm.fetchData(); await flushPromises()
  expect(w.vm.ipranges).toHaveLength(1); expect(w.vm.listRefreshFailed).toBe(true)
  w.vm.openCreate(); expect(w.vm.editor).toBe(false)
  await w.vm.fetchData(); await flushPromises(); expect(w.vm.ready).toBe(true)
})

test('closing a successful busy editor releases parent actions before child unmount', async () => {
  const w = await mountView(GuestIpRanges)
  w.vm.openCreate(); await flushPromises()
  const child = w.findComponent(Editor)
  child.vm.$emit('busy', true); await flushPromises()
  expect(w.vm.editorBusy).toBe(true)
  child.vm.$emit('close-action'); await flushPromises()
  expect(w.vm.editor).toBe(false); expect(w.vm.editorBusy).toBe(false)
  w.vm.openUpdate(range); expect(w.vm.editor).toBe(true)
})
test('late response from the old network cannot replace a new network list', async () => {
  let resolveOld
  getAPI.mockImplementationOnce(() => new Promise(resolve => { resolveOld = resolve }))
  const w = await mountView(GuestIpRanges)
  rows = [{ ...range, id: 'r2', networkid: 'n2' }]
  await w.setProps({ resource: { ...base, id: 'n2' } }); await flushPromises()
  resolveOld({ listvlaniprangesresponse: { count: 1, vlaniprange: [range] } }); await flushPromises()
  expect(w.vm.ipranges.map(r => r.id)).toEqual(['r2'])
})
test('read-only API role does not expose writes, and API loss closes the editor', async () => {
  delete getters.apis.createVlanIpRange; delete getters.apis.deleteVlanIpRange
  const w = await mountView(GuestIpRanges)
  expect(w.vm.allowed('createVlanIpRange')).toBe(false); expect(w.vm.allowed('updateVlanIpRange')).toBe(true)
  w.vm.openUpdate(range); expect(w.vm.editor).toBe(true)
  delete getters.apis.updateVlanIpRange; await flushPromises(); expect(w.vm.editor).toBe(false)
})
test('delete refusal retains the row and dialog; explicit retry succeeds', async () => {
  const w = await mountView(GuestIpRanges); w.vm.openDelete(range)
  postAPI.mockRejectedValueOnce(httpError('IP addresses are in use'))
  await w.vm.removeIpRange(); expect(w.vm.deleteItem.id).toBe('r1'); expect(w.vm.deleteError).toContain('in use'); expect(w.vm.ipranges).toHaveLength(1)
  await w.vm.removeIpRange(); await flushPromises(); expect(w.vm.deleteItem).toBe(null); expect(w.vm.ipranges).toHaveLength(0)
})
test('delete result lookup failure is reconciled without another write', async () => {
  const w = await mountView(GuestIpRanges); w.vm.openDelete(range)
  postAPI.mockImplementationOnce(() => { rows = []; getAPI.mockRejectedValueOnce(httpError('Read failed')); return reply({ deletevlaniprangeresponse: { success: true } }) })
  await w.vm.removeIpRange(); expect(w.vm.deletePosted).toBe(true)
  await w.vm.removeIpRange(); expect(postAPI).toHaveBeenCalledTimes(1); expect(w.vm.deleteItem).toBe(null)
})
test('post success followed by failed read preserves draft and checks without reposting', async () => {
  const w = await mountView(Editor); Object.assign(w.vm.form, form4)
  postAPI.mockImplementationOnce((command, params) => { rows.push({ ...params, id: 'new' }); getAPI.mockRejectedValueOnce(httpError('Read failed')); return reply({ createvlaniprangeresponse: { id: 'new' } }) })
  await w.vm.handleSubmit(); expect(w.vm.phase).toBe('saved'); expect(w.vm.form.startip).toBe(form4.startip)
  await w.vm.handleSubmit(); expect(postAPI).toHaveBeenCalledTimes(1); expect(w.emitted('close-action')).toBeTruthy()
})
test('unknown create response can be located by its new ID and values without reposting', async () => {
  const w = await mountView(Editor); Object.assign(w.vm.form, form4)
  postAPI.mockImplementationOnce((command, params) => { rows.push({ ...params, id: 'new' }); return Promise.reject(new Error('timeout')) })
  await w.vm.handleSubmit(); expect(w.vm.phase).toBe('unknown')
  await w.vm.handleSubmit(); expect(postAPI).toHaveBeenCalledTimes(1); expect(w.emitted('close-action')).toBeTruthy()
})
test('changed or deleted range cannot overwrite another edit', async () => {
  const w = await mountView(Editor, { range: { ...range } }); w.vm.form.endip = '172.26.31.25'; rows[0].endip = '172.26.31.22'
  await w.vm.handleSubmit(); expect(postAPI).not.toHaveBeenCalled(); expect(w.vm.error).toContain('scope.changed'); expect(w.vm.form.endip).toBe('172.26.31.25')
})
test('IPv6-only edits preserve their family and compare canonical addresses', async () => {
  const net = { ...base, type: 'Shared', ip6cidr: 'fd31::/64', ip6gateway: 'fd31::1' }; networks = [net]
  const row = { id: 'r6', networkid: base.id, startipv6: 'fd31::10', endipv6: 'fd31::20', ip6gateway: 'fd31::1', ip6cidr: 'fd31::/64' }; rows = [row]
  const w = await mountView(Editor, { resource: net, range: row }); w.vm.form.endipv6 = 'FD31:0:0:0:0:0:0:25'
  await w.vm.handleSubmit(); expect(postAPI.mock.calls[0][1]).not.toHaveProperty('gateway'); expect(w.emitted('close-action')).toBeTruthy()
})
test('double submission sends one write', async () => {
  const w = await mountView(Editor); Object.assign(w.vm.form, form4)
  await Promise.all([w.vm.handleSubmit(), w.vm.handleSubmit()]); expect(postAPI).toHaveBeenCalledTimes(1)
})

test('Shared IPv6 is offered through API support even when parent subnet metadata is absent', async () => {
  const w = await mountView(Editor, { resource: { ...base, type: 'Shared', gateway: undefined, cidr: undefined }, defaults: { gateway: form4.gateway, netmask: form4.netmask, ip6gateway: form6.ip6gateway, ip6cidr: form6.ip6cidr } })
  expect(w.vm.supportsIpv6).toBe(true)
  expect(w.vm.form).toMatchObject({ family: 'ipv4', gateway: form4.gateway, netmask: form4.netmask, ip6cidr: form6.ip6cidr })
})

test('defaults use individual table ranges rather than comma separated parent subnets', async () => {
  rows = [{ ...range }, { id: 'r6', networkid: base.id, ...form6 }]
  const w = await mountView(GuestIpRanges, { resource: { ...base, type: 'Shared', gateway: '172.26.31.1,172.28.31.1' } })
  expect(w.vm.rangeDefaults).toEqual({ gateway: range.gateway, netmask: range.netmask, ip6gateway: form6.ip6gateway, ip6cidr: form6.ip6cidr })
})
