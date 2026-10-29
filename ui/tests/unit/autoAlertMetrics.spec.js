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
import AutoAlertBanner from '@/components/header/AutoAlertBanner.vue'
import ko from '@public/locales/ko_KR.json'
import { getAPI } from '@/api'
import store from '@/store'

jest.mock('@/api', () => ({ getAPI: jest.fn() }))
jest.mock('@/store', () => ({ state: { user: { discoveryGeneration: 0 } }, getters: { apis: {}, userInfo: { id: 'metrics-test' } } }))

let wrapper
beforeEach(() => {
  jest.useFakeTimers()
  getAPI.mockReset()
  store.getters.apis = {}
  window.$t = key => ko[key] || key
  wrapper = shallowMount(AutoAlertBanner, { global: { mocks: { $t: key => ko[key] || key, $te: key => key in ko } } })
})
afterEach(() => {
  wrapper.unmount()
  delete window.$t
  jest.clearAllTimers()
  jest.useRealTimers()
})

function cpuAlert (rule = {}, alerts = []) {
  return { title: '사용자_VM_CPU 사용률 (%)', rule: { kind: 'vm', threshold: 75, ...rule }, alerts }
}
const oldAlert = { labels: { domain: 'i-2-281-VM' }, annotations: { current: '105' }, value: '105' }

test('maximum uses current target measurements rather than older alert annotations', () => {
  const item = cpuAlert({ currentValue: 4.51, currentTargets: [{ key: 'i-2-281-VM', value: 80, breached: true }] }, [oldAlert])
  expect(wrapper.vm.drawerItemMetricUi(item)).toMatchObject({ kind: 'metric', curText: '80% (평균 4.51%)', thrText: '75%' })
  expect(wrapper.vm.drawerItemMetricInlineText(item)).toBe('최대 80% (평균 4.51%) · 임계값 75%')
  expect(wrapper.vm.drawerItemMetricLineText(item)).toBe('최대 80% (평균 4.51%) · 임계값 75%')
  expect(wrapper.vm.breachedLinksByKind(item, 'vm')[0].valueText).toBe('80%')
})

test('actual CPU percentages above 100 are preserved in the maximum and target tooltip', () => {
  const item = cpuAlert({ currentValue: 4.51, currentTargets: [{ key: 'i-2-281-VM', value: 105, breached: true }] }, [oldAlert])
  expect(wrapper.vm.drawerItemMetricUi(item).curText).toBe('105% (평균 4.51%)')
  expect(wrapper.vm.breachedLinksByKind(item, 'vm')[0].valueText).toBe('105%')
  expect(wrapper.vm.vmCpuOver100(item)).toBe(true)
})

test('percent values below one remain percentage points in all metric displays', () => {
  const item = cpuAlert({ currentValue: 0.3, threshold: 0.75, currentTargets: [{ key: 'i-2-281-VM', value: 0.8, breached: true }] }, [oldAlert])
  expect(wrapper.vm.drawerItemMetricUi(item)).toMatchObject({ curText: '0.8% (평균 0.3%)', thrText: '0.75%' })
  expect(wrapper.vm.breachedLinksByKind(item, 'vm')[0].valueText).toBe('0.8%')
})

test('zero average and threshold are retained instead of falling back to old values', () => {
  const item = cpuAlert({ currentValue: 0, threshold: 0, currentTargets: [{ key: 'i-2-281-VM', value: 0.2, breached: true }] }, [oldAlert])
  expect(wrapper.vm.drawerItemMetricUi(item)).toMatchObject({ curText: '0.2% (평균 0%)', thrText: '0%' })
})

test('missing target in a current snapshot does not show its older alert value', () => {
  const item = cpuAlert({ currentValue: 4.51, currentTargets: [{ key: 'i-2-282-VM', value: 4.51 }] }, [oldAlert])
  expect(wrapper.vm.breachedLinksByKind(item, 'vm')[0].valueText).toBe('')
  expect(wrapper.vm.drawerItemMetricUi(item).curText).toBe('4.51% (평균 4.51%)')
})

test('instance values remain a fallback when current measurements are unavailable', () => {
  const item = cpuAlert({}, [oldAlert])
  expect(wrapper.vm.drawerItemMetricUi(item).curText).toBe('105% (평균 105%)')
})

test('binary status rules retain their state display', () => {
  const item = { title: 'VM 에이전트 상태', rule: { kind: 'vm', currentTargets: [{ key: 'i-2-281-VM', value: 1, breached: true }] }, alerts: [] }
  expect(wrapper.vm.drawerItemMetricUi(item)).toMatchObject({ kind: 'binary', text: ko['label.current.state.bad'] })
})

test.each([0.8, 99, 100])('CPU percentage %s does not show an over-100 explanation', value => {
  const item = cpuAlert({ currentValue: value, currentTargets: [{ key: 'i-2-281-VM', value }] })
  expect(wrapper.vm.vmCpuOver100(item)).toBe(false)
})

test.each([
  ['호스트 CPU 사용률 (%)', 'host'],
  ['사용자_VM_메모리 사용률 (%)', 'vm'],
  ['사용자_VM_네트워크 사용률 (%)', 'vm'],
  ['사용자_VM_CPU 사용 시간 (ms)', 'vm']
])('over-100 guidance does not apply to %s', (title, kind) => {
  const item = { ...cpuAlert({ kind, currentValue: 105, currentTargets: [{ key: 'target', value: 105 }] }), title }
  expect(wrapper.vm.vmCpuOver100(item)).toBe(false)
})

test('VM CPU title also identifies rules without a VM kind label', () => {
  const item = cpuAlert({ kind: 'USER', currentValue: 105 })
  expect(wrapper.vm.vmCpuOver100(item)).toBe(true)
})

test('a threshold above 100 alone does not trigger the explanation', () => {
  expect(wrapper.vm.vmCpuOver100(cpuAlert({ currentValue: 80, threshold: 105 }))).toBe(false)
})

test('over-100 card shows an accessible icon beside the value without visible explanatory text', async () => {
  wrapper.unmount()
  store.getters.apis = { getWallAlertAvailability: {}, listWallAlertRules: {} }
  getAPI.mockImplementation(name => Promise.resolve(name === 'getWallAlertAvailability'
    ? { getwallalertavailabilityresponse: { wallavailability: { state: 'Ready', available: true } } }
    : {
      listwallalertrulesresponse: {
        wallalertruleresponse: [{
          uid: 'cpu-rule',
          name: '사용자_VM_CPU 사용률 (%)',
          kind: 'vm',
          state: 'ALERTING',
          currentValue: 4.51,
          currentTargets: [{ key: 'i-2-281-VM', value: 105, breached: true }],
          instances: [{ ...oldAlert, state: 'Alerting' }]
        }]
      }
    }))
  wrapper = shallowMount(AutoAlertBanner, {
    global: {
      mocks: { $t: window.$t, $te: key => key in ko },
      stubs: {
        teleport: true,
        'a-drawer': { props: ['visible'], template: '<section v-if="visible"><slot /></section>' },
        'a-alert': { template: '<article><slot name="message" /></article>' },
        'a-popover': { template: '<div data-target-values><slot name="content" /><slot /></div>' },
        'a-tooltip': { props: ['title'], template: '<div :data-explanation="title"><slot /></div>' }
      }
    }
  })
  await flushPromises()
  expect(wrapper.vm.visibleAlerts).toHaveLength(1)
  wrapper.vm.drawerVisible = true
  await wrapper.vm.$nextTick()
  const note = wrapper.find('.cpu-over100-icon')
  expect(note.exists()).toBe(true)
  expect(note.text()).toBe('')
  expect(note.attributes('aria-label')).toBe(ko['label.alert.vm.cpu.over100'])
  expect(note.attributes('role')).toBe('img')
  expect(wrapper.text()).not.toContain(ko['label.alert.vm.cpu.over100'])
  expect(wrapper.text()).not.toContain(ko['message.alert.vm.cpu.over100'])
  expect(wrapper.find('.drawer-item-metric-hover').element.contains(note.element)).toBe(true)
  expect(note.element.closest('[data-target-values]')).toBeNull()
  expect(note.attributes('tabindex')).toBe('0')
  expect(wrapper.find('[data-explanation]').attributes('data-explanation')).toBe(ko['message.alert.vm.cpu.over100'])
  expect(wrapper.find('.cpu-over100-description').exists()).toBe(false)
  expect(wrapper.find('.target-value-metric').text()).toBe('105%')
})
