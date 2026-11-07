// Licensed to the Apache Software Foundation (ASF) under one or more
// contributor license agreements. See the NOTICE file distributed with this
// work for additional information regarding copyright ownership. The ASF
// licenses this file under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License. You may
// obtain a copy at http://www.apache.org/licenses/LICENSE-2.0 . Unless required
// by applicable law or agreed to in writing, software distributed under the
// License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS.
import { mount, shallowMount, flushPromises } from '@vue/test-utils'
import { reactive } from 'vue'
import NetworkOperationPolicy from '@/components/view/NetworkOperationPolicy'
import UpdateNetwork from '@/views/network/UpdateNetwork'
import { standardNetworkUpdateParams } from '@/utils/networkOperationPolicy'
import { getAPI, postAPI } from '@/api'
import { isAdmin } from '@/role'

jest.mock('@/api', () => ({ getAPI: jest.fn(), postAPI: jest.fn() }))
jest.mock('@/role', () => ({ isAdmin: jest.fn() }))
jest.mock('@/utils/mixin', () => ({ mixinForm: { methods: { handleRemoveFields: values => values } } }))
const base = { id: 'network-1', name: 'Review', type: 'Isolated', zoneid: 'zone-1', networkofferingid: 'offering-1', account: 'owner', domainid: 'domain-1', displaytext: 'Old' }
let wrappers
beforeEach(() => {
  wrappers = []
  jest.clearAllMocks()
  getAPI.mockImplementation(command => Promise.resolve(command === 'listNetworks' ? { listnetworksresponse: { network: [base] } } : command === 'listZones' ? { listzonesresponse: { zone: [{}] } } : { listnetworkofferingsresponse: { count: 1, networkoffering: [{ id: base.networkofferingid, state: 'Enabled', guestiptype: 'Isolated', service: [] }] } }))
  postAPI.mockResolvedValue({ updatenetworkresponse: { jobid: 'job-1' } })
})
afterEach(() => wrappers.forEach(w => w.unmount()))

test.each(['Isolated', 'L2', 'Shared'])('%s standard request preserves ordinary false/blank values and omits advanced arguments', type => {
  const input = { id: 'a', name: 'Name', displaytext: '', displaynetwork: false, hideipaddressusage: false, forced: true, customid: 'b', customId: 'c', changecidr: true, changeCidr: true, unexpected: 'value', guestvmcidr: '10.0.0.0/25' }
  const result = standardNetworkUpdateParams(input, type)
  expect(result).toMatchObject({ id: 'a', name: 'Name', displaytext: '', displaynetwork: false, hideipaddressusage: false })
  expect(Object.keys(result).sort()).toEqual(['id', 'name', 'displaytext', 'displaynetwork', 'hideipaddressusage', ...(type === 'Isolated' ? ['guestvmcidr'] : [])].sort())
  expect(input.forced).toBe(true)
})

test.each(['Isolated', 'L2', 'Shared'])('%s guidance switches between update and deletion without an executable advanced control', async type => {
  const w = mount(NetworkOperationPolicy, { props: { networkType: type }, global: { mocks: { $t: key => key } } })
  wrappers.push(w)
  await w.get('.ant-collapse-header').trigger('click')
  expect(w.text()).toContain('message.network.operation.policy.intro.update')
  expect(w.findAll('dt')).toHaveLength(3)
  expect(w.text()).toContain('message.network.operation.policy.cidr.' + (type === 'Isolated' ? 'isolated' : 'other'))
  expect(w.find('input, [role="switch"], a').exists()).toBe(false)
  await w.setProps({ context: 'delete' })
  expect(w.findAll('dt')).toHaveLength(1)
  expect(w.text()).toContain('message.network.operation.policy.delete')
  expect(w.text()).not.toContain('message.network.operation.policy.id')
})

test.each(['Admin', 'DomainAdmin', 'User'])('%s edit submits only the standard contract, even if stale advanced fields exist in a draft', async role => {
  isAdmin.mockReturnValue(role === 'Admin')
  const w = shallowMount(UpdateNetwork, {
    props: { resource: base },
    global: {
      stubs: { AFormItem: { template: '<div><slot name="label"/><slot/></div>' }, ASpin: { template: '<div><slot/></div>' }, AForm: { template: '<form><slot/></form>', methods: { validate: () => Promise.resolve() } }, MoldDialog: { template: '<div><slot/><slot name="footer"/></div>' } },
      mocks: { $t: key => key, $getApiParams: () => new Proxy({}, { get: () => ({ description: '' }) }), $store: { getters: reactive({ apis: { updateNetwork: {}, listNetworks: {} }, project: {}, userInfo: { id: role, roletype: role } }) }, $notifyError: jest.fn(), $pollJob: () => Promise.resolve({ jobstatus: 1 }) }
    }
  })
  wrappers.push(w)
  await flushPromises()
  w.vm.form.displaytext = 'New'
  w.vm.form.forced = true
  w.vm.form.customid = 'replacement'
  w.vm.form.changecidr = true
  expect(w.find('[name="forced"]').exists()).toBe(false)
  await w.vm.handleSubmit()
  expect(postAPI).toHaveBeenCalledTimes(1)
  expect(postAPI.mock.calls[0][1]).toMatchObject({ id: base.id, displaytext: 'New' })
  for (const key of ['forced', 'customid', 'changecidr']) expect(postAPI.mock.calls[0][1]).not.toHaveProperty(key)
})
