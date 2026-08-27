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
import { createI18n } from 'vue-i18n'
import { createStore } from 'vuex'

import EventSidebar from '@/components/view/EventSidebar.vue'
import { api } from '@/api'

jest.mock('@/api', () => ({
  api: jest.fn()
}))

const factory = (props = {}) => {
  const store = createStore({
    getters: {
      apis: () => ({ listEvents: {}, listAlerts: {} }),
      userInfo: () => ({ roletype: 'User' })
    }
  })
  const i18n = createI18n({
    legacy: false,
    locale: 'en',
    messages: { en: {} }
  })

  return shallowMount(EventSidebar, {
    props: { isVisible: false, ...props },
    global: {
      plugins: [store, i18n],
      stubs: {
        'a-button': { template: '<button><slot /></button>' },
        'a-table': true,
        'close-outlined': true,
        'down-outlined': true,
        'up-outlined': true
      }
    }
  })
}

describe('Components > View > EventSidebar.vue', () => {
  beforeEach(() => {
    jest.clearAllMocks()
    window.localStorage.clear()
  })

  it('renders event and alert tabs without an overlay drawer', () => {
    const wrapper = factory()

    expect(wrapper.find('.bottom-activity-panel').exists()).toBe(true)
    expect(wrapper.findAll('.bottom-activity-panel__tab')).toHaveLength(2)
    expect(wrapper.find('a-drawer-stub').exists()).toBe(false)
  })

  it('shows the correct visible icon for collapsing and restoring the panel', async () => {
    const wrapper = factory()

    expect(wrapper.find('down-outlined-stub').exists()).toBe(true)
    expect(wrapper.find('up-outlined-stub').exists()).toBe(false)

    wrapper.vm.toggleCollapsed()
    await wrapper.vm.$nextTick()

    expect(wrapper.find('down-outlined-stub').exists()).toBe(false)
    expect(wrapper.find('up-outlined-stub').exists()).toBe(true)
  })

  it('loads existing alert data when the alert tab is selected', async () => {
    api.mockResolvedValue({
      listalertsresponse: {
        alert: [{ id: 'alert-1', name: 'Test alert' }]
      }
    })
    const wrapper = factory()

    wrapper.vm.selectTab('alerts')
    await flushPromises()

    expect(api).toHaveBeenCalledWith('listAlerts', {
      page: 1,
      pagesize: 20,
      listall: true
    })
    expect(wrapper.vm.alerts).toEqual([{ id: 'alert-1', name: 'Test alert' }])
  })

  it('clamps and persists keyboard resizing', () => {
    const wrapper = factory()
    const preventDefault = jest.fn()

    wrapper.vm.expandedHeight = wrapper.vm.maxHeight
    wrapper.vm.onResizeKeydown({ key: 'ArrowUp', shiftKey: false, preventDefault })

    expect(preventDefault).toHaveBeenCalled()
    expect(wrapper.vm.expandedHeight).toBe(wrapper.vm.maxHeight)
    expect(window.localStorage.getItem('ablestack.bottomActivityPanel.height.v1'))
      .toBe(String(wrapper.vm.maxHeight))
  })

  it('keeps only events inside the configured recent window', () => {
    const wrapper = factory()
    const now = Date.now()
    const events = [
      { id: 'recent', created: new Date(now - 1000).toISOString() },
      { id: 'old', created: new Date(now - 600000).toISOString() }
    ]

    expect(wrapper.vm.filterRecentEvents(events, 60000).map(event => event.id))
      .toEqual(['recent'])
  })
})
