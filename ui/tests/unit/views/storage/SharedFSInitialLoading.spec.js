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

import { mount, flushPromises } from '@vue/test-utils'
import SharedFSTab from '@/views/storage/SharedFSTab'
import { listRefreshMixin } from '@/utils/listRefreshMixin'

describe('SharedFS initial runtime loading ownership', () => {
  let wrapper
  let instanceReads
  const instances = [{ id: 'instance', virtualmachineid: 'vm' }]
  beforeEach(() => {
    instanceReads = []
    wrapper = mount({
      mixins: [listRefreshMixin(['fetchStorageServiceData'], { active: () => false })],
      template: '<div />',
      data: () => ({
        resource: { id: 'sharedfs', zoneid: 'zone', virtualmachineid: 'vm' },
        vm: {},
        currentTab: 'details',
        hasStorageServiceApi: true,
        storageRefreshGeneration: 0,
        storageService: { loading: false, loaded: false, instance: null }
      }),
      methods: {
        fetchStorageServiceData: SharedFSTab.methods.fetchStorageServiceData,
        listApi (command) {
          if (command === 'listStorageServiceInstances') return new Promise(resolve => instanceReads.push(resolve))
          return Promise.resolve([])
        },
        fetchNvmeStorageSnapshot: () => Promise.resolve({ inventory: [], sessions: [], nvmeSubsystems: [], nvmeNamespaces: [], nvmeHostAcls: [] }),
        loadAccessRules: () => Promise.resolve({}),
        loadBackingVolumes: () => Promise.resolve([]),
        $notifyError: jest.fn()
      }
    }, { global: { mocks: { $route: { fullPath: '/sharedfs/sharedfs' }, $store: { getters: {} } } } })
  })
  afterEach(() => wrapper.unmount())

  it.each(['vm', 'tab'])('retries an initial response invalidated by %s information', async change => {
    wrapper.vm.fetchStorageServiceData()
    expect(instanceReads).toHaveLength(1)
    if (change === 'vm') await wrapper.setData({ vm: { id: 'vm' } })
    else await wrapper.setData({ currentTab: 'nfs' })
    instanceReads[0](instances)
    await flushPromises()
    expect(wrapper.vm.storageService.instance).toBeNull()
    expect(instanceReads).toHaveLength(2)
    expect(wrapper.vm.storageService.loading).toBe(true)
    instanceReads[1](instances)
    await flushPromises()
    expect(wrapper.vm.storageService.instance.id).toBe('instance')
    expect(wrapper.vm.storageService.loaded).toBe(true)
    expect(wrapper.vm.storageService.loading).toBe(false)
  })

  it('keeps the newer pending request in control when an older scope resolves', async () => {
    wrapper.vm.fetchStorageServiceData()
    await wrapper.setData({ vm: { id: 'vm' } })
    wrapper.vm.fetchStorageServiceData()
    expect(instanceReads).toHaveLength(2)
    instanceReads[0](instances)
    await flushPromises()
    expect(instanceReads).toHaveLength(2)
    expect(wrapper.vm.storageService.loading).toBe(true)
    expect(wrapper.vm.storageService.instance).toBeNull()
    instanceReads[1](instances)
    await flushPromises()
    expect(wrapper.vm.storageService.instance.id).toBe('instance')
    expect(wrapper.vm.storageService.loading).toBe(false)
  })

  it('does not retry or apply an initial response after unmount', async () => {
    const vm = wrapper.vm
    vm.fetchStorageServiceData()
    await wrapper.setData({ vm: { id: 'vm' } })
    wrapper.unmount()
    instanceReads[0](instances)
    await flushPromises()
    expect(instanceReads).toHaveLength(1)
    expect(vm.storageService.instance).toBeNull()
  })
})
