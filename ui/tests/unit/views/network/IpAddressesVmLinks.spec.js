// Licensed to the Apache Software Foundation (ASF) under one or more
// contributor license agreements. See the NOTICE file distributed with this
// work for additional information regarding copyright ownership. The ASF
// licenses this file under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License. You may
// obtain a copy at http://www.apache.org/licenses/LICENSE-2.0 . Unless required
// by applicable law or agreed to in writing, software distributed under the
// License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS.
import { mount, flushPromises } from '@vue/test-utils'
import { createRouter, createMemoryHistory } from 'vue-router'
import IpAddressesTab from '@/views/network/IpAddressesTab'
import { getAPI } from '@/api'
jest.mock('@/api', () => ({ getAPI: jest.fn(), postAPI: jest.fn() }))

const row = { id: 'ip-1', ipaddress: '10.10.31.142', state: 'Allocated', forvirtualnetwork: true, issourcenat: true, associatednetworkid: 'net-1', associatednetworkname: 'QA network' }
const user = { ...row, id: 'ip-2', ipaddress: '10.10.31.141', issourcenat: false, isstaticnat: true, virtualmachineid: 'vm-7', virtualmachinetype: 'User', virtualmachinename: 'host-7' }
let response
beforeEach(() => {
  jest.clearAllMocks()
  response = [row, user]
  getAPI.mockImplementation(command => Promise.resolve(command === 'listZones' ? { listzonesresponse: { zone: [] } } : { listpublicipaddressesresponse: { count: response.length, publicipaddress: response } }))
})
async function render (type = 'Isolated') {
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/guestnetwork/:id', name: 'guestnetwork', component: { template: '<div />' } }, { path: '/:pathMatch(.*)*', component: { template: '<div />' } }] })
  await router.push('/guestnetwork/net-1')
  await router.isReady()
  const wrapper = mount(IpAddressesTab, {
    props: { resource: { id: 'net-1', zoneid: 'zone-1', type } },
    global: { plugins: [router], provide: { parentFetchData: jest.fn() }, mocks: { $t: x => x, $store: { getters: { apis: { associateIpAddress: {}, disassociateIpAddress: {} } } }, $notifyError: jest.fn() } }
  })
  await flushPromises()
  return wrapper
}
test('real IP table retains IP/network links and NAT tags while removing the Source NAT VM focus target', async () => {
  const w = await render()
  const cells = w.findAll('.network-ip-vm-reference')
  expect(cells).toHaveLength(2)
  expect(cells[0].text()).toBe('—')
  expect(cells[0].find('a').exists()).toBe(false)
  expect(cells[1].get('a').attributes('href')).toBe('/vm/vm-7')
  expect(w.get('a[href="/publicip/ip-1"]').text()).toBe(row.ipaddress)
  expect(w.findAll('a[href="/guestnetwork/net-1"]')).toHaveLength(2)
  expect(w.text()).toContain('label.sourcenat')
  expect(w.text()).toContain('label.staticnat')
  expect(getAPI.mock.calls.map(c => c[0])).toEqual(['listZones', 'listPublicIpAddresses'])
  w.unmount()
})
test('Shared guest IPs stay plain and router/user cells retain typed targets without VM lookup APIs', async () => {
  response = [{ ...row, forvirtualnetwork: false, issourcenat: false, virtualmachineid: 'router-9', virtualmachinetype: 'DomainRouter', virtualmachinename: 'r-9-VM' }, { ...user, forvirtualnetwork: false, isstaticnat: false }]
  const w = await render('Shared')
  expect(w.find('a[href^="/publicip/"]').exists()).toBe(false)
  expect(w.get('a[href="/router/router-9"]').text()).toBe('r-9-VM')
  expect(w.get('a[href="/vm/vm-7"]').text()).toBe('host-7')
  expect(w.text()).toContain('message.shared.guest.addresses')
  expect(w.findAll('button').some(b => b.text() === 'label.acquire.new.ip')).toBe(false)
  expect(getAPI.mock.calls.map(c => c[0])).toEqual(['listPublicIpAddresses'])
  expect(getAPI.mock.calls[0][1]).toMatchObject({ networkid: 'net-1', forvirtualnetwork: false, allocatedonly: false })
  w.unmount()
})
test('refresh failure keeps confirmed links and retry replaces them immediately with a noninteractive dash', async () => {
  const w = await render()
  getAPI.mockRejectedValueOnce(new Error('QA read failure'))
  await w.vm.fetchData()
  await flushPromises()
  expect(w.get('a[href="/vm/vm-7"]').text()).toBe('host-7')
  expect(w.get('[role="status"]').text()).toContain('message.list.refresh.stale')
  response = [row]
  await w.vm.fetchData()
  await flushPromises()
  expect(w.find('a[href="/vm/vm-7"]').exists()).toBe(false)
  expect(w.get('.network-ip-vm-reference').text()).toBe('—')
  expect(w.find('[role="status"]').exists()).toBe(false)
  w.unmount()
})
