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
import DrPlanVmTab from '@/views/compute/dr/DrPlanVmTab.vue'
import { getDrVmProtectionView } from '@/api/dr'

jest.mock('@/api/dr', () => ({
  getDrVmProtectionView: jest.fn()
}))

const translations = {
  'message.dr.vm.not.managed.here': 'Not managed here',
  'message.dr.vm.view.load.failed': 'Local view failed',
  'label.dr.vm.role.recovery.target': 'Recovery target',
  'label.dr.source.vm': 'Source virtual machine',
  'label.dr.target.vm': 'Target virtual machine',
  'label.dr.direction.kvm.to.kvm': 'ABLESTACK to ABLESTACK'
}

const createWrapper = (view, loadError = false) => shallowMount(DrPlanVmTab, {
  props: {
    view: view || { configured: false, association: [] },
    loadError,
    loading: false
  },
  global: {
    mocks: {
      $store: { getters: { apis: { getDrVmProtectionView: {} } } },
      $t: key => translations[key] || key
    },
    stubs: {
      'a-spin': { template: '<div><slot /></div>' },
      'a-alert': { props: ['message'], template: '<div class="alert">{{ message }}<slot /></div>' },
      'a-empty': { props: ['description'], template: '<div class="empty">{{ description }}<slot /></div>' },
      'a-button': { template: '<button><slot /></button>' },
      'router-link': { template: '<a><slot /></a>' },
      DrRpoKpi: true,
      DrStatusPill: true,
      ArrowRightOutlined: true
    }
  }
})

describe('DrPlanVmTab local DB projection', () => {
  beforeEach(() => jest.clearAllMocks())

  test('loads and renders the local recovery-target relationship', async () => {
    const view = {
      configured: true,
      relationconflict: false,
      association: [{
        planid: 'plan-uuid',
        planname: 'u26-base DR Plan',
        relationshiprole: 'RECOVERY_TARGET',
        authorityrole: 'STANDBY',
        sourcevmname: 'u26-base',
        targetvmname: 'dr-u26-base',
        protectionstate: 'READY',
        direction: 'KVM_TO_KVM'
      }]
    })

    const wrapper = createWrapper()
    await flushPromises()

    expect(getDrVmProtectionView).not.toHaveBeenCalled()
    await wrapper.findAll('button')[1].trigger('click')
    expect(wrapper.emitted('refresh')).toHaveLength(1)
    expect(wrapper.text()).toContain('Recovery target')
    expect(wrapper.text()).toContain('u26-base')
    expect(wrapper.text()).toContain('dr-u26-base')
    expect(wrapper.text()).toContain('ABLESTACK to ABLESTACK')
    expect(wrapper.findAll('button')).toHaveLength(2)
  })

  test('shows a local-scope empty state when no relationship exists', async () => {
    const view = { configured: false, association: [] }

    const wrapper = createWrapper()
    await flushPromises()

    expect(wrapper.text()).toContain('Not managed here')
    expect(wrapper.text()).not.toContain('Local view failed')
    expect(wrapper.findAll('button')).toHaveLength(2)
  })

  test('does not mislabel an API failure as an unconfigured VM', async () => {
    // Failed refresh retains previously confirmed data.

    const wrapper = createWrapper()
    await flushPromises()

    expect(wrapper.text()).toContain('Local view failed')
    expect(wrapper.text()).not.toContain('Not managed here')
  })
})
