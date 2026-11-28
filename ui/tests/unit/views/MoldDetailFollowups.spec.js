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

import { flushPromises, shallowMount } from '@vue/test-utils'
import { getAPI, postAPI } from '@/api'
import ResourceLimitTab from '@/components/view/ResourceLimitTab'
import PhysicalNetworksTab from '@/views/infra/zone/PhysicalNetworksTab'

jest.mock('@/api', () => ({ getAPI: jest.fn(), postAPI: jest.fn() }))

const apis = { listResourceLimits: {}, updateResourceLimit: {}, listTrafficTypes: {} }
const getters = { apis, userInfo: { id: 'admin', roletype: 'Admin' } }
const limit = max => ({ resourcetype: 8, resourcetypename: 'cpu', max })
const limitResponse = max => ({ listresourcelimitsresponse: { resourcelimit: [limit(max)] } })

beforeEach(() => {
  getAPI.mockReset()
  postAPI.mockReset()
})

function limits (name, resource, userInfo = getters.userInfo) {
  return shallowMount(ResourceLimitTab, {
    props: { resource },
    global: {
      mocks: { $t: key => key, $route: { meta: { name } }, $store: { getters: { ...getters, userInfo } } }
    }
  })
}

test('saves an edited account quota in its account and domain scope', async () => {
  getAPI.mockResolvedValueOnce(limitResponse(10)).mockResolvedValueOnce(limitResponse(12))
  postAPI.mockResolvedValue({})
  const tab = limits('account', { id: 'a', name: 'owner', domainid: 'd', cputotal: 2 })
  await flushPromises()
  tab.vm.edit(tab.vm.rows[0], 12)
  await tab.vm.save()
  expect(postAPI).toHaveBeenCalledWith('updateResourceLimit', { account: 'owner', domainid: 'd', resourcetype: 8, max: 12 }, { preserveOnFailure: true })
  expect(tab.vm.rows[0]).toMatchObject({ saved: 12, status: 'saved' })
  expect(tab.emitted('refresh')).toHaveLength(1)
  tab.unmount()
})

test('a domain administrator can read their own domain and cannot save its limits', async () => {
  getAPI.mockResolvedValue(limitResponse(10))
  const tab = limits('domain', { id: 'd', name: 'domain', level: 1 }, { id: 'da', roletype: 'DomainAdmin', domainid: 'd' })
  await flushPromises()
  expect(getAPI).toHaveBeenCalledWith('listResourceLimits', { domainid: 'd' }, { preserveOnFailure: true })
  expect(tab.vm.canEdit).toBe(false)
  tab.vm.edit(tab.vm.rows[0], 12)
  await tab.vm.save()
  expect(postAPI).not.toHaveBeenCalled()
  tab.unmount()
})

test('shows physical network rows before late traffic types and ignores unrelated zone changes', async () => {
  let resolveTraffic
  getAPI.mockImplementation(command => command === 'listPhysicalNetworks'
    ? Promise.resolve({ listphysicalnetworksresponse: { physicalnetwork: [{ id: 'n1', name: 'Network' }] } })
    : new Promise(resolve => { resolveTraffic = resolve }))
  const tab = shallowMount(PhysicalNetworksTab, {
    props: { resource: { id: 'z1', name: 'Zone' } },
    global: {
      stubs: ['detail-tab-table'],
      mocks: { $t: key => key, $getApiParams: () => ({}), $route: { path: '/zone/z1', query: { tab: 'physical.network' } }, $store: { getters } }
    }
  })
  await flushPromises()
  expect(tab.vm.networks[0].name).toBe('Network')
  expect(tab.vm.fetchLoading).toBe(false)
  await tab.setProps({ resource: { id: 'z1', name: 'Zone renamed' } })
  expect(getAPI.mock.calls.filter(([command]) => command === 'listPhysicalNetworks')).toHaveLength(1)
  resolveTraffic({ listtraffictypesresponse: { traffictype: [{ traffictype: 'Guest' }] } })
  await flushPromises()
  expect(tab.vm.networks[0].traffictype).toBe('Guest')
  tab.unmount()
})
