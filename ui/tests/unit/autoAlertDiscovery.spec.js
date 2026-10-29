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

import { mount, shallowMount } from '@vue/test-utils'
import { getAPI } from '@/api'
import store from '@/store'
import AutoAlertBanner from '@/components/header/AutoAlertBanner.vue'
import ko from '@public/locales/ko_KR.json'
import Cookies from 'js-cookie'
import eventBus from '@/config/eventBus'

jest.mock('@/api', () => ({ getAPI: jest.fn() }))
jest.mock('@/store', () => ({ state: { user: { discoveryGeneration: 0 } }, getters: { apis: {}, userInfo: { id: 'user1' } } }))
const flush = async () => { for (let i = 0; i < 30; i++) await Promise.resolve() }
let wrapper
beforeEach(() => {
  jest.useFakeTimers()
  getAPI.mockReset()
  sessionStorage.clear()
  Cookies.remove('sessionkey')
  store.state.user.token = 'wall-banner-test-session'
  store.state.user.discoveryGeneration = 0
  store.getters.apis = {}
})
afterEach(() => {
  if (wrapper) wrapper.unmount()
  wrapper = null
  jest.clearAllTimers()
  jest.useRealTimers()
  jest.restoreAllMocks()
  delete window.__bannerPoll
  Cookies.remove('sessionkey')
})

test('unauthorized banner does not request or poll Wall or inventory APIs', async () => {
  wrapper = shallowMount(AutoAlertBanner, { global: { mocks: { $t: key => key, $te: () => true } } })
  await flush()
  jest.advanceTimersByTime(120000)
  await flush()
  expect(getAPI).not.toHaveBeenCalled()
})

test('authorized banner catches a 432 and remains mounted', async () => {
  store.getters.apis = { getWallAlertAvailability: {}, listWallAlertRules: {} }
  getAPI.mockResolvedValueOnce({ getwallalertavailabilityresponse: { wallavailability: { state: 'Ready', available: true } } }).mockRejectedValue(new Error('432 forbidden'))
  wrapper = shallowMount(AutoAlertBanner, { global: { mocks: { $t: key => key, $te: () => true } } })
  await flush()
  expect(wrapper.exists()).toBe(true)
  expect(getAPI.mock.calls.map(call => call[0])).toEqual(['getWallAlertAvailability', 'listWallAlertRules'])
})

test('disabled Wall integration hides the banner and never requests alert rules', async () => {
  store.getters.apis = { getWallAlertAvailability: {}, listWallAlertRules: {} }
  getAPI.mockResolvedValue({ getwallalertavailabilityresponse: { wallavailability: { state: 'Disabled', available: false } } })
  wrapper = shallowMount(AutoAlertBanner, {
    global: { mocks: { $t: key => key, $te: () => true }, stubs: { teleport: { template: '<div><slot /></div>' } } }
  })
  await flush()
  expect(wrapper.vm.wallState).toBe('Disabled')
  expect(wrapper.vm.showBanner).toBe(false)
  expect(wrapper.find('.auto-alert-banner-container').exists()).toBe(false)
  expect(getAPI.mock.calls.map(call => call[0])).toEqual(['getWallAlertAvailability'])
})

test.each(['NotConfigured', 'Unavailable', 'ConnectionFailed', 'DnsError', 'TlsError', 'Timeout', 'AuthenticationFailed', 'InvalidResponse', 'Degraded'])(
  'enabled integration still shows an availability warning for %s and hides it when disabled', async state => {
    store.getters.apis = { getWallAlertAvailability: {}, listWallAlertRules: {} }
    getAPI.mockResolvedValue({ getwallalertavailabilityresponse: { wallavailability: { state, available: false } } })
    wrapper = shallowMount(AutoAlertBanner, {
      global: { mocks: { $t: key => key, $te: () => true }, stubs: { teleport: { template: '<div><slot /></div>' } } }
    })
    await flush()
    expect(wrapper.vm.showBanner).toBe(true)
    expect(wrapper.find('.auto-alert-banner-container').exists()).toBe(true)
    expect(getAPI.mock.calls.map(call => call[0])).toEqual(['getWallAlertAvailability'])

    wrapper.vm.wallState = 'Disabled'
    await wrapper.vm.$nextTick()
    expect(wrapper.vm.showBanner).toBe(false)
    expect(wrapper.find('.auto-alert-banner-container').exists()).toBe(false)
  }
)

test('unmount during a pending request cannot resurrect polling', async () => {
  store.getters.apis = { getWallAlertAvailability: {}, listWallAlertRules: {}, listHostsMetrics: {}, listVirtualMachines: {} }
  let finish
  getAPI.mockImplementation(() => new Promise(resolve => { finish = resolve }))
  wrapper = shallowMount(AutoAlertBanner, { global: { mocks: { $t: key => key, $te: () => true } } })
  await flush()
  wrapper.unmount()
  wrapper = null
  store.state.user.discoveryGeneration++
  finish({ getwallalertavailabilityresponse: { wallavailability: { state: 'Ready', available: true } } })
  await flush()
  jest.advanceTimersByTime(120000)
  await flush()
  expect(getAPI).toHaveBeenCalledTimes(1)
})

const statusReply = (state) => ({ getwallalertavailabilityresponse: { wallavailability: { state, available: state === 'Ready' } } })
const emptyRules = { listwallalertrulesresponse: { wallalertruleresponse: [] } }
const mountAvailabilityBanner = () => mount(AutoAlertBanner, {
  global: {
    mocks: { $t: key => ko[key] || key, $te: key => key in ko },
    stubs: { teleport: { template: '<div><slot /></div>' }, 'a-drawer': true, 'a-modal': true }
  }
})

test('Wall status is unknown until the initial probe completes, and healthy empty alerts show no banner', async () => {
  store.getters.apis = { getWallAlertAvailability: {}, listWallAlertRules: {} }
  let finish
  getAPI.mockImplementation(name => name === 'getWallAlertAvailability'
    ? new Promise(resolve => { finish = resolve }) : Promise.resolve(emptyRules))
  wrapper = mountAvailabilityBanner()
  await flush()
  expect(wrapper.vm.wallState).toBe('Unknown')
  expect(wrapper.find('.wall-availability-alert').exists()).toBe(false)
  finish(statusReply('Ready'))
  await flush()
  expect(wrapper.vm.wallState).toBe('Ready')
  expect(wrapper.vm.showBanner).toBe(false)
})

test('real blue Alert closes with X and dismissal survives remount/reload and prolonged outage, but not a new session', async () => {
  let now = 1000
  jest.spyOn(Date, 'now').mockImplementation(() => now)
  store.getters.apis = { getWallAlertAvailability: {}, listWallAlertRules: {} }
  getAPI.mockResolvedValue(statusReply('ConnectionFailed'))
  wrapper = mountAvailabilityBanner()
  await flush()
  expect(wrapper.findAll('.wall-availability-alert')).toHaveLength(1)
  expect(wrapper.find('.ant-alert-info').exists()).toBe(true)
  expect(wrapper.find('.ant-alert-message').text()).toBe('Wall 모니터링 연결이 끊어졌습니다. 모니터링 및 경보 정보가 갱신되지 않습니다. Mold의 다른 기능은 계속 이용할 수 있습니다.')
  await wrapper.find('button.ant-alert-close-icon').trigger('click')
  expect(wrapper.vm.wallState).toBe('ConnectionFailed')
  expect(wrapper.vm.showBanner).toBe(false)
  expect(document.documentElement.style.getPropertyValue('--autoBannerHeight')).toBe('0px')
  wrapper.unmount()
  wrapper = mountAvailabilityBanner()
  await flush()
  expect(wrapper.vm.showBanner).toBe(false)
  now += 24 * 60 * 60 * 1000
  await window.__bannerPoll.pollTick()
  await flush()
  expect(wrapper.vm.showBanner).toBe(false)
  expect(getAPI.mock.calls.every(([name]) => name === 'getWallAlertAvailability')).toBe(true)

  wrapper.unmount()
  store.state.user.discoveryGeneration++
  store.state.user.token = 'new-wall-banner-session'
  wrapper = mountAvailabilityBanner()
  await flush()
  expect(wrapper.vm.showBanner).toBe(true)
})

test('dismissal persists across failed probes and partial data failures, and resets only on successful recovery', async () => {
  let now = 1000
  let state = 'ConnectionFailed'
  let probeFails = false
  let rulesFail = false
  jest.spyOn(Date, 'now').mockImplementation(() => now)
  store.getters.apis = { getWallAlertAvailability: {}, listWallAlertRules: {} }
  getAPI.mockImplementation(name => {
    if (name === 'getWallAlertAvailability') return probeFails ? Promise.reject(new Error('Mold request failed')) : Promise.resolve(statusReply(state))
    return rulesFail ? Promise.reject(new Error('single query failed')) : Promise.resolve(emptyRules)
  })
  const poll = async () => { now += 60000; await window.__bannerPoll.pollTick(); await flush() }
  wrapper = mountAvailabilityBanner()
  await flush()
  await wrapper.find('button.ant-alert-close-icon').trigger('click')
  probeFails = true
  await poll()
  expect(wrapper.vm.wallState).toBe('ConnectionFailed')
  expect(wrapper.vm.showBanner).toBe(false)
  probeFails = false
  state = 'Ready'
  rulesFail = true
  await poll()
  expect(wrapper.vm.wallState).toBe('Degraded')
  expect(wrapper.vm.showBanner).toBe(false)
  expect(sessionStorage.length).toBe(1)
  await poll()
  expect(wrapper.vm.showBanner).toBe(false)
  expect(sessionStorage.length).toBe(1)

  rulesFail = false
  await poll()
  expect(wrapper.vm.wallState).toBe('Ready')
  expect(wrapper.vm.showBanner).toBe(false)
  expect(sessionStorage.length).toBe(0)
  state = 'ConnectionFailed'
  await poll()
  expect(wrapper.vm.showBanner).toBe(true)
  expect(wrapper.findAll('.wall-availability-alert')).toHaveLength(1)
})

test('single rule query failure reports stale alert data rather than a complete Wall disconnection', async () => {
  store.getters.apis = { getWallAlertAvailability: {}, listWallAlertRules: {} }
  getAPI.mockImplementation(name => name === 'getWallAlertAvailability'
    ? Promise.resolve(statusReply('Ready')) : Promise.reject(new Error('internal details must stay private')))
  wrapper = mountAvailabilityBanner()
  await flush()
  expect(wrapper.vm.wallState).toBe('Degraded')
  expect(wrapper.find('.ant-alert-message').text()).toContain('Wall 경보 정보를 갱신하지 못했습니다.')
  expect(wrapper.text()).not.toMatch(/연결이 끊어졌습니다|모니터링 및 경보 정보가 갱신되지 않습니다|internal details/)
})

test('status request failure does not assert that the entire Wall service is down', async () => {
  store.getters.apis = { getWallAlertAvailability: {}, listWallAlertRules: {} }
  getAPI.mockRejectedValue(new Error('Mold transport unavailable'))
  wrapper = mountAvailabilityBanner()
  await flush()
  expect(wrapper.vm.wallState).toBe('CheckFailed')
  expect(wrapper.find('.ant-alert-message').text()).toContain('Wall 모니터링 상태를 확인할 수 없습니다.')
  expect(wrapper.text()).not.toContain('연결이 끊어졌습니다')
})

test('cookie-authenticated SSO sessions also restore dismissal after remount', async () => {
  store.state.user.token = ''
  Cookies.set('sessionkey', 'sso-test-session')
  store.getters.apis = { getWallAlertAvailability: {}, listWallAlertRules: {} }
  getAPI.mockResolvedValue(statusReply('ConnectionFailed'))
  wrapper = mountAvailabilityBanner()
  await flush()
  await wrapper.find('button.ant-alert-close-icon').trigger('click')
  wrapper.unmount()
  wrapper = mountAvailabilityBanner()
  await flush()
  expect(wrapper.vm.showBanner).toBe(false)
})

test('closing an outage banner does not suppress real alert summaries after recovery', async () => {
  let now = 1000
  let state = 'ConnectionFailed'
  jest.spyOn(Date, 'now').mockImplementation(() => now)
  store.getters.apis = { getWallAlertAvailability: {}, listWallAlertRules: {} }
  getAPI.mockImplementation(name => Promise.resolve(name === 'getWallAlertAvailability' ? statusReply(state) : {
    listwallalertrulesresponse: { wallalertruleresponse: [{ uid: 'firing-rule', name: 'CPU', state: 'ALERTING' }] }
  }))
  wrapper = mountAvailabilityBanner()
  await flush()
  await wrapper.find('button.ant-alert-close-icon').trigger('click')
  state = 'Ready'
  now += 60000
  await window.__bannerPoll.pollTick()
  await flush()
  expect(wrapper.vm.wallState).toBe('Ready')
  expect(wrapper.vm.showBanner).toBe(true)
  expect(wrapper.find('.wall-availability-alert').exists()).toBe(false)
  expect(wrapper.find('.alert-summary').exists()).toBe(true)
})

test('rule changes bypass the silence cache immediately and stop notifying an unmounted banner', async () => {
  let silenced = []
  store.getters.apis = { getWallAlertAvailability: {}, listWallAlertRules: {}, listWallAlertSilences: {} }
  getAPI.mockImplementation(name => Promise.resolve(name === 'getWallAlertAvailability' ? statusReply('Ready')
    : name === 'listWallAlertRules' ? { listwallalertrulesresponse: { wallalertruleresponse: ['a', 'b'].map(uid => ({ uid, name: uid, state: 'ALERTING' })) } }
      : { listwallalertsilencesresponse: { silence: silenced.map(uid => ({ state: 'active', endsAt: '2050-01-01T00:00:00Z', matchers: [{ name: '__alert_rule_uid__', value: uid }] })) } }))
  wrapper = mountAvailabilityBanner()
  await flush()
  expect(wrapper.vm.visibleAlerts.map(rule => rule.uid)).toEqual(['a', 'b'])
  silenced = ['a']
  eventBus.emit('wall-alert-rules-changed')
  await flush()
  expect(wrapper.vm.visibleAlerts.map(rule => rule.uid)).toEqual(['b'])
  silenced = ['a', 'b']
  eventBus.emit('wall-alert-rules-changed')
  await flush()
  expect(wrapper.vm.showBanner).toBe(false)
  silenced = []
  eventBus.emit('wall-alert-rules-changed')
  await flush()
  expect(wrapper.vm.visibleAlerts.map(rule => rule.uid)).toEqual(['a', 'b'])
  wrapper.unmount()
  wrapper = null
  getAPI.mockClear()
  eventBus.emit('wall-alert-rules-changed')
  await flush()
  expect(getAPI).not.toHaveBeenCalled()
})

test('rule changes during a pending refresh queue one fresh read instead of dropping the update', async () => {
  let finish
  let calls = 0
  store.getters.apis = { getWallAlertAvailability: {}, listWallAlertRules: {} }
  getAPI.mockImplementation(name => {
    if (name === 'getWallAlertAvailability') return Promise.resolve(statusReply('Ready'))
    if (++calls === 1) return new Promise(resolve => { finish = resolve })
    return Promise.resolve(emptyRules)
  })
  wrapper = mountAvailabilityBanner()
  await flush()
  eventBus.emit('wall-alert-rules-changed')
  eventBus.emit('wall-alert-rules-changed')
  expect(calls).toBe(1)
  finish(emptyRules)
  await flush()
  expect(calls).toBe(2)
})
