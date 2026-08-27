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

import { shallowMount } from '@vue/test-utils'
import { createI18n } from 'vue-i18n'
import { createStore } from 'vuex'

import HeaderNotice from '@/components/header/HeaderNotice.vue'
import Drawer from '@/components/widgets/Drawer.vue'

const createI18nPlugin = () => createI18n({
  legacy: false,
  locale: 'en',
  messages: { en: {} }
})

describe('Components > Header > activity navigation', () => {
  it('opens the activity panel and closes the notification popover', () => {
    const store = createStore({
      getters: {
        apis: () => ({ listEvents: {}, listAlerts: {} }),
        headerNotices: () => []
      }
    })
    const wrapper = shallowMount(HeaderNotice, {
      global: { plugins: [store, createI18nPlugin()] }
    })

    wrapper.vm.visible = true
    wrapper.vm.openActivityPanel()

    expect(wrapper.vm.visible).toBe(false)
    expect(wrapper.emitted('open-activity-panel')).toHaveLength(1)
  })

  it('does not offer the activity panel without event or alert APIs', () => {
    const store = createStore({
      getters: {
        apis: () => ({}),
        headerNotices: () => []
      }
    })
    const wrapper = shallowMount(HeaderNotice, {
      global: { plugins: [store, createI18nPlugin()] }
    })

    expect(wrapper.vm.canOpenActivityPanel).toBe(false)
  })
})

describe('Components > Widgets > Drawer.vue', () => {
  it('hides the fixed handler and keeps an internal close action', async () => {
    const close = jest.fn()
    const wrapper = shallowMount(Drawer, {
      props: {
        visible: true,
        showHandler: false,
        title: 'Display settings'
      },
      global: {
        plugins: [createI18nPlugin()],
        provide: { parentToggleSetting: close },
        stubs: {
          'a-button': { template: '<button @click="$emit(\'click\', $event)"><slot /></button>' },
          'close-outlined': true
        }
      }
    })

    expect(wrapper.find('.handler-container').exists()).toBe(false)
    expect(wrapper.find('.drawer-header').exists()).toBe(true)

    await wrapper.find('.drawer-header button').trigger('click')
    expect(close).toHaveBeenCalledWith(false)
  })
})
