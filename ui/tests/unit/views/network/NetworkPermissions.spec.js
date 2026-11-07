// Licensed to the Apache Software Foundation (ASF) under one or more
// contributor license agreements. See the NOTICE file for ownership information.
// Licensed under the Apache License, Version 2.0; http://www.apache.org/licenses/LICENSE-2.0
// Distributed on an AS IS BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND.

import { flushPromises, shallowMount } from '@vue/test-utils'
import NetworkPermissions from '@/views/network/NetworkPermissions.vue'
import { getAPI, postAPI } from '@/api'
import { canReadNetworkPermissions, permissionGrant } from '@/utils/networkPermissions'

jest.mock('@/api', () => ({ getAPI: jest.fn(), postAPI: jest.fn() }))

const network = { id: 'network', type: 'Isolated', acltype: 'Account', domainid: 'domain', domainpath: 'ROOT/Main', account: 'owner' }
const getters = {
  apis: { listNetworkPermissions: {}, createNetworkPermissions: {}, removeNetworkPermissions: {} },
  userInfo: { roletype: 'User', account: 'owner', domainid: 'domain' }
}
const row = { networkid: 'network', domainid: 'domain', accountid: 'peer-id', account: 'peer' }
const response = (...rows) => ({ listnetworkpermissionsresponse: { count: rows.length, networkpermission: rows } })

function mount () {
  return shallowMount(NetworkPermissions, {
    props: { resource: network },
    global: {
      mocks: { $store: { getters }, $route: { params: {} }, $t: value => value },
      stubs: { 'a-button': true, 'a-alert': true, 'a-table': true, 'a-spin': true, 'plus-outlined': true, 'reload-outlined': true }
    }
  })
}

beforeEach(() => jest.resetAllMocks())

test('keeps project and domain scopes separate from grant, and protects specified Shared VLANs', () => {
  expect(canReadNetworkPermissions(network, getters)).toBe(true)
  expect(canReadNetworkPermissions({ ...network, account: 'peer' }, getters)).toBe(false)
  expect(canReadNetworkPermissions({ ...network, projectid: 'project' }, getters)).toBe(false)
  expect(canReadNetworkPermissions({ ...network, type: 'Shared', specifyvlan: true }, getters)).toBe(false)
  const admin = { roletype: 'DomainAdmin', domainid: 'domain', domainpath: 'ROOT/Main' }
  expect(canReadNetworkPermissions({ ...network, domainid: 'child', domainpath: 'ROOT/Main/Child' }, getters, admin)).toBe(true)
  expect(canReadNetworkPermissions({ ...network, domainid: 'sibling', domainpath: 'ROOT/MainOther' }, getters, admin)).toBe(false)
  expect(permissionGrant({ accounts: 'owner,peer, new, new', projectids: ['p', 'p'] }, network, [row])).toEqual({ accounts: 'new', projectids: 'p' })
})

test('network changes discard responses from the previous permission tab', async () => {
  let old
  getAPI.mockImplementationOnce(() => new Promise(resolve => { old = resolve })).mockResolvedValueOnce(response())
  const wrapper = mount()
  await wrapper.setProps({ resource: { ...network, id: 'second' } })
  await flushPromises()
  old(response(row))
  await flushPromises()
  expect(wrapper.vm.rows).toEqual([])
  expect(wrapper.vm.ready).toBe(true)
  wrapper.unmount()
})

test('ambiguous write disables resubmission until a fresh permission read', async () => {
  getAPI.mockResolvedValueOnce(response()).mockResolvedValueOnce(response(row))
  postAPI.mockRejectedValueOnce(new Error('request timed out'))
  const wrapper = mount()
  await flushPromises()
  wrapper.vm.open('grant')
  await wrapper.vm.grant({ accounts: 'peer' })
  expect(wrapper.vm.ready).toBe(false)
  await wrapper.vm.grant({ accounts: 'peer' })
  expect(postAPI).toHaveBeenCalledTimes(1)
  await wrapper.vm.refresh()
  expect(wrapper.vm.rows).toEqual([row])
  expect(wrapper.vm.ready).toBe(true)
  await wrapper.vm.grant({ accounts: 'peer' })
  expect(postAPI).toHaveBeenCalledTimes(1)
  wrapper.unmount()
})

test('a rejected grant target keeps the dialog available for correction', async () => {
  getAPI.mockResolvedValueOnce(response()).mockResolvedValueOnce(response(row))
  postAPI.mockRejectedValueOnce({ response: { status: 431, data: { errorresponse: { errorcode: 431, errortext: 'Invalid target' } } } })
    .mockResolvedValueOnce({ createnetworkpermissionsresponse: { success: true } })
  const wrapper = mount()
  await flushPromises()
  wrapper.vm.open('grant')
  await wrapper.vm.grant({ accounts: 'invalid' })
  expect(wrapper.vm.writeError).toBe('Invalid target')
  expect(wrapper.vm.dialog).toBe('grant')
  expect(wrapper.vm.ready).toBe(true)
  await wrapper.vm.grant({ accounts: 'peer' })
  expect(wrapper.vm.dialog).toBe('')
  expect(wrapper.vm.rows).toEqual([row])
  wrapper.unmount()
})
