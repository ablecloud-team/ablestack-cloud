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
import dayjs from 'dayjs'
import eventBus from '@/config/eventBus'
import { getAPI, postAPI } from '@/api'
import RuleModal from '@/views/infra/WallAlertRuleActionModal.vue'
import AutogenView from '@/views/AutogenView.vue'
import ListView from '@/components/view/ListView.vue'
import AutoAlertBanner from '@/components/header/AutoAlertBanner.vue'
import config from '@/config/section/infra/alertRules'
import { groupActionMenuEntries, resolveActionMenuGroup } from '@/utils/actionMenu'
import ko from '@public/locales/ko_KR.json'
import en from '@public/locales/en.json'

jest.mock('@/api', () => ({ getAPI: jest.fn(), postAPI: jest.fn() }))

const rules = [{ uid: 'a', name: 'CPU', isPaused: false }, { uid: 'b', name: 'Memory', isPaused: false }]
const pause = config.actions.find(action => action.wallRuleAction === 'pause')
const message = { success: jest.fn() }
const translate = (key, params = {}) => Object.entries(params).reduce((text, [name, value]) => text.replace(`{${name}}`, value), ko[key] || key)
const mountModal = props => shallowMount(RuleModal, {
  props: { currentAction: pause, ...props },
  global: {
    mocks: { $t: translate, $message: message },
    stubs: {
      'a-modal': { template: '<div><slot /></div>' },
      'a-spin': { name: 'ASpin', props: { spinning: Boolean }, template: '<div><slot /></div>' }
    }
  }
})

beforeEach(() => { jest.clearAllMocks(); postAPI.mockResolvedValue({}) })
afterEach(() => jest.restoreAllMocks())

test.each([
  ['pause', '경보 규칙 일시 중지 시 영향', [
    '일시 중지되는 동안 이 규칙의 알림(배너/경보)은 트리거되지 않습니다.',
    '경보 중인 상태라도 일시 정지하게 되면 정상으로 상태가 변경됩니다.',
    '재개하면 알림이 다시 활성화 됩니다.'
  ]],
  ['silence', '사일런스 적용 시 영향', [
    '선택 기간 동안 해당 경보의 알림(배너/경보)이 차단됩니다.',
    '경보 평가와 상태는 계속 갱신되며 규칙 자체는 바뀌지 않습니다.',
    '기간이 끝나면 사일런스가 자동 해제됩니다.',
    '사일런스 활성 시 버튼이 숨겨져 중복 생성이 방지됩니다.'
  ]]
])('%s keeps the original warning title and every original sentence', async (kind, title, sentences) => {
  const wrapper = shallowMount(RuleModal, {
    props: { currentAction: config.actions.find(action => action.wallRuleAction === kind), resource: rules[0] },
    global: {
      mocks: { $t: translate, $message: message },
      stubs: {
        'a-modal': { template: '<div><slot /></div>' },
        'a-spin': { template: '<div><slot /></div>' },
        'a-alert': { props: ['message', 'description'], template: '<section><strong>{{ message }}</strong><slot name="description">{{ description }}</slot></section>' }
      }
    }
  })
  await flushPromises()
  expect(wrapper.find('.rule-impact strong').text()).toBe(title)
  expect(wrapper.findAll('.rule-impact li').map(line => line.text())).toEqual(sentences)
  expect(wrapper.text()).not.toContain(ko['message.wall.rules.effect.' + kind])
  expect(postAPI).not.toHaveBeenCalled()
  wrapper.unmount()
})

test('selected-rule confirmation ignores the context-menu resource and snapshots the selection', async () => {
  const wrapper = mountModal({ currentAction: { ...pause, invokedAsGroupAction: true }, resource: { uid: 'other' }, selectedItems: rules })
  await flushPromises()
  expect(wrapper.vm.targets.map(r => r.uid)).toEqual(['a', 'b'])
  await wrapper.setProps({ selectedItems: [{ uid: 'replacement' }] })
  await wrapper.vm.submit()
  expect(postAPI).not.toHaveBeenCalled()
  await wrapper.setData({ acknowledged: true })
  await wrapper.vm.submit()
  expect(postAPI.mock.calls.map(call => call[1].id)).toEqual(['a', 'b'])
  expect(wrapper.emitted('refresh-data')).toHaveLength(1)
  expect(wrapper.emitted('close-action')).toHaveLength(1)
  wrapper.unmount()
})

test('a single-rule action works without any selection', async () => {
  const wrapper = mountModal({ resource: rules[0] })
  await flushPromises()
  await wrapper.setData({ acknowledged: true })
  await wrapper.vm.submit()
  expect(postAPI).toHaveBeenCalledWith('pauseWallAlertRule', { id: 'a', paused: true })
  wrapper.unmount()
})

test.each(['selected', 'all'])('%s silence keeps one absolute end time through sequential requests and failed-item retries', async scope => {
  const now = jest.spyOn(Date, 'now').mockReturnValue(Date.parse('2026-10-29T01:00:00Z'))
  try {
    getAPI.mockResolvedValue({ listwallalertrulesresponse: { count: rules.length, wallalertruleresponse: rules } })
    postAPI.mockImplementationOnce(async () => {
      now.mockReturnValue(Date.parse('2026-10-29T01:01:00Z'))
      return {}
    }).mockRejectedValueOnce(new Error('temporary failure')).mockResolvedValue({})
    const wrapper = mountModal({
      currentAction: { ...config.actions.find(action => action.wallRuleAction === 'silence'), wallRuleScope: scope, invokedAsGroupAction: true },
      selectedItems: rules
    })
    await flushPromises()
    expect(wrapper.vm.endTimeText(30)).not.toBe('')
    await wrapper.setData({ acknowledged: true })
    await wrapper.vm.submit()
    expect(postAPI.mock.calls.map(call => call[1].endsAt)).toEqual(['2026-10-29T01:30:00.000Z', '2026-10-29T01:30:00.000Z'])
    now.mockReturnValue(Date.parse('2026-10-29T01:05:00Z'))
    await wrapper.vm.submit()
    expect(postAPI).toHaveBeenLastCalledWith('createWallAlertSilence', expect.objectContaining({
      'labels[0].value': 'b', endsAt: '2026-10-29T01:30:00.000Z'
    }))
    wrapper.unmount()
  } finally {
    now.mockRestore()
  }
})

test('custom silence converts the selected timezone to UTC and refuses missing, invalid or past dates before writing', async () => {
  const wrapper = mountModal({ currentAction: config.actions.find(action => action.wallRuleAction === 'silence'), resource: rules[0] })
  await flushPromises()
  await wrapper.setData({ acknowledged: true, duration: 'custom' })
  for (const customEndsAt of [null, dayjs('invalid'), dayjs('2000-01-01T00:00:00Z')]) {
    wrapper.vm.customEndsAt = customEndsAt
    await wrapper.vm.$nextTick()
    await wrapper.vm.submit()
    expect(wrapper.vm.timingError).toBe(ko['message.silence.end.future'])
    expect(postAPI).not.toHaveBeenCalled()
  }
  wrapper.vm.customEndsAt = dayjs('2050-01-02T18:45:00+09:00')
  await wrapper.vm.$nextTick()
  await wrapper.vm.submit()
  expect(postAPI).toHaveBeenCalledWith('createWallAlertSilence', expect.objectContaining({ endsAt: '2050-01-02T09:45:00.000Z' }))
  expect(postAPI.mock.calls[0][1]).not.toHaveProperty('durationMinutes')
  wrapper.unmount()
})

test('a retry after the original silence deadline never extends it or sends new requests', async () => {
  const now = jest.spyOn(Date, 'now').mockReturnValue(Date.parse('2026-10-29T01:00:00Z'))
  try {
    postAPI.mockRejectedValue(new Error('temporary failure'))
    const wrapper = mountModal({ currentAction: config.actions.find(action => action.wallRuleAction === 'silence'), resource: rules[0] })
    await flushPromises()
    await wrapper.setData({ acknowledged: true })
    await wrapper.vm.submit()
    now.mockReturnValue(Date.parse('2026-10-29T01:31:00Z'))
    await wrapper.vm.submit()
    expect(postAPI).toHaveBeenCalledTimes(1)
    expect(wrapper.vm.timingError).toBe(ko['message.silence.end.future'])
    expect(wrapper.vm.running).toBe(false)
    wrapper.unmount()
  } finally {
    now.mockRestore()
  }
})

test('failed all-rules loading never falls back to the current selection', async () => {
  getAPI.mockRejectedValue(new Error('offline'))
  const wrapper = mountModal({ currentAction: { ...pause, wallRuleScope: 'all', invokedAsGroupAction: true }, selectedItems: rules })
  await flushPromises()
  await wrapper.setData({ acknowledged: true })
  await wrapper.vm.submit()
  expect(wrapper.vm.loadFailed).toBe(true)
  expect(wrapper.vm.targets).toEqual([])
  expect(postAPI).not.toHaveBeenCalled()
  wrapper.unmount()
})

test('all-rules menu action ignores the selected row and skips stopped rules without writing before confirmation', async () => {
  getAPI.mockResolvedValue({ listwallalertrulesresponse: { wallalertruleresponse: [...rules, { uid: 'c', isPaused: true }], count: 3 } })
  const wrapper = mountModal({ currentAction: config.actions.find(action => action.wallRuleAction === 'pause' && action.wallRuleScope === 'all'), selectedItems: [rules[0]], resource: rules[0] })
  await flushPromises()
  expect(wrapper.vm.rules).toHaveLength(3)
  expect(wrapper.vm.targets).toHaveLength(2)
  expect(getAPI).toHaveBeenCalledWith('listWallAlertRules', { page: 1, pagesize: 100 })
  expect(postAPI).not.toHaveBeenCalled()
  await wrapper.setData({ acknowledged: true })
  await wrapper.vm.submit()
  expect(postAPI).toHaveBeenCalledTimes(2)
  wrapper.unmount()
})

test('partial failure stays open, refreshes completed work and retries only failed targets', async () => {
  const notify = jest.spyOn(eventBus, 'emit')
  postAPI.mockRejectedValueOnce(new Error('temporary failure')).mockResolvedValue({})
  const wrapper = mountModal({ currentAction: { ...pause, invokedAsGroupAction: true }, selectedItems: rules })
  await flushPromises()
  await wrapper.setData({ acknowledged: true })
  await wrapper.vm.submit()
  expect(wrapper.vm.failures.map(f => f.rule.uid)).toEqual(['a'])
  expect(notify).toHaveBeenCalledTimes(1)
  expect(notify).toHaveBeenCalledWith('wall-alert-rules-changed')
  expect(wrapper.emitted('close-action')).toBeUndefined()
  expect(wrapper.emitted('refresh-data')).toHaveLength(1)
  await wrapper.vm.submit()
  expect(postAPI.mock.calls.map(call => call[1].id)).toEqual(['a', 'b', 'a'])
  expect(wrapper.emitted('close-action')).toHaveLength(1)
  wrapper.unmount()
})

test.each([
  new Error('Request failed with status code 431'),
  { response: { data: { errorresponse: { errortext: 'Internal backend exception at WallApiClient.java:123' } } } }
])('failed-rule results show rule names without raw request or backend errors', async error => {
  const notify = jest.spyOn(eventBus, 'emit')
  postAPI.mockRejectedValue(error)
  const wrapper = shallowMount(RuleModal, {
    props: { currentAction: { ...pause, invokedAsGroupAction: true }, selectedItems: rules },
    global: {
      mocks: { $t: translate, $message: message },
      stubs: {
        'a-modal': { template: '<div><slot /></div>' },
        'a-spin': { template: '<div><slot /></div>' },
        'a-alert': { props: ['message'], template: '<section>{{ message }}<slot name="description" /></section>' }
      }
    }
  })
  await flushPromises()
  await wrapper.setData({ acknowledged: true })
  await wrapper.vm.submit()
  await wrapper.vm.$nextTick()
  expect(wrapper.findAll('.rule-failures li').map(item => item.text())).toEqual(['CPU', 'Memory'])
  expect(wrapper.text()).toContain('0개 완료, 2개 실패했습니다.')
  expect(wrapper.text()).not.toMatch(/431|Request failed|Internal backend exception|WallApiClient/)
  expect(notify).not.toHaveBeenCalled()
  expect(wrapper.vm.failures).toHaveLength(2)
  expect(wrapper.emitted('close-action')).toBeUndefined()
  wrapper.unmount()
})

test('submitting twice or closing while running cannot duplicate or interrupt the batch', async () => {
  let finish
  postAPI.mockImplementation(() => new Promise(resolve => { finish = resolve }))
  const wrapper = mountModal({ resource: rules[0] })
  await flushPromises()
  await wrapper.setData({ acknowledged: true })
  const pending = wrapper.vm.submit()
  wrapper.vm.submit()
  wrapper.vm.close()
  expect(postAPI).toHaveBeenCalledTimes(1)
  expect(wrapper.emitted('close-action')).toBeUndefined()
  finish({})
  await pending
  wrapper.unmount()
})

test.each(['selected', 'all'])('%s batch keeps the existing AntD spinner active through the last item and clears it after failure', async scope => {
  getAPI.mockResolvedValue({ listwallalertrulesresponse: { count: rules.length, wallalertruleresponse: rules } })
  const pendingRequests = []
  postAPI.mockImplementation(() => new Promise((resolve, reject) => { pendingRequests.push({ resolve, reject }) }))
  const wrapper = mountModal({ currentAction: { ...pause, wallRuleScope: scope, invokedAsGroupAction: true }, selectedItems: rules })
  const spinner = () => wrapper.findComponent({ name: 'ASpin' }).props('spinning')
  await flushPromises()
  expect(spinner()).toBe(false)
  await wrapper.setData({ acknowledged: true })
  const pending = wrapper.vm.submit()
  await wrapper.vm.$nextTick()
  expect(spinner()).toBe(true)
  expect(pendingRequests).toHaveLength(1)
  pendingRequests[0].resolve({})
  await flushPromises()
  expect(pendingRequests).toHaveLength(2)
  expect(spinner()).toBe(true)
  expect(wrapper.vm.completed).toBe(1)
  pendingRequests[1].reject(new Error('temporary failure'))
  await pending
  await wrapper.vm.$nextTick()
  expect(spinner()).toBe(false)
  expect(wrapper.vm.failures.map(failure => failure.rule.uid)).toEqual(['b'])

  const retry = wrapper.vm.submit()
  await wrapper.vm.$nextTick()
  expect(spinner()).toBe(true)
  expect(pendingRequests).toHaveLength(3)
  pendingRequests[2].resolve({})
  await retry
  await wrapper.vm.$nextTick()
  expect(spinner()).toBe(false)
  expect(wrapper.emitted('close-action')).toHaveLength(1)
  wrapper.unmount()
})

test.each([[], [rules[0]], [...rules, { uid: 'c', isPaused: true }]].map(selectedItems => [selectedItems]))('alert-rule list has no header action buttons for selection %j', selectedItems => {
  const apis = Object.fromEntries(config.actions.map(action => [action.api, {}]))
  const view = { actions: config.actions, dataView: false, selectedRowKeys: selectedItems.map(rule => rule.uid), selectedItems, resource: {}, $store: { getters: { apis } } }
  expect(AutogenView.computed.visibleListActions.call(view)).toEqual([])
})

test('alert-rule list enables checkboxes and exposes eligible context actions for mixed states', async () => {
  const wrapper = shallowMount(ListView, {
    props: { columns: [], items: [...rules, { uid: 'c', isPaused: true }], actions: config.actions },
    global: {
      provide: { parentFetchData: jest.fn(), parentToggleLoading: jest.fn() },
      mocks: { $route: { name: 'alertRules', path: '/alertRules', meta: {} }, $store: { getters: { apis: { pauseWallAlertRule: {}, createWallAlertSilence: {}, expireWallAlertSilence: {}, listWallAlertSilences: {} } } }, $t: translate }
    }
  })
  expect(wrapper.vm.enableGroupAction()).toBe(true)
  wrapper.vm.onSelectChange(['a', 'c'])
  await wrapper.vm.$nextTick()
  expect(wrapper.vm.selectedItems.map(r => r.uid)).toEqual(['a', 'c'])
  expect(wrapper.vm.contextMenuActions.map(a => [a.wallRuleAction, a.wallRuleScope || 'selected'])).toEqual([
    ['pause', 'selected'], ['resume', 'selected'], ['silence', 'selected'], ['silence', 'all'], ['unsilence', 'all'], ['pause', 'all'], ['resume', 'all']
  ])
  wrapper.unmount()
})

test('unselected row menu separates existing row actions and all-rule actions using the shared sections', async () => {
  const wrapper = shallowMount(ListView, {
    props: { columns: [], items: rules, actions: config.actions },
    global: {
      provide: { parentFetchData: jest.fn(), parentToggleLoading: jest.fn() },
      mocks: { $route: { name: 'alertRules', path: '/alertRules', meta: {} }, $store: { getters: { apis: { updateWallAlertRuleThreshold: {}, pauseWallAlertRule: {}, createWallAlertSilence: {}, expireWallAlertSilence: {}, listWallAlertSilences: {} } } }, $t: translate }
    }
  })
  await wrapper.setData({ contextQuickViewRecord: rules[0] })
  const groups = groupActionMenuEntries(wrapper.vm.contextMenuActions.map(action => ({ group: resolveActionMenuGroup(action), action })))
  expect(groups.map(group => group.key)).toEqual(['MANAGEMENT', 'GENERAL', 'ALL_RULES'])
  expect(groups.find(group => group.key === 'ALL_RULES').entries.map(entry => entry.action.wallRuleAction)).toEqual(['silence', 'unsilence', 'pause', 'resume'])
  expect(wrapper.vm.selectedRowKeys).toEqual([])

  await wrapper.setData({ contextQuickViewRecord: null })
  expect(wrapper.vm.contextMenuActions.map(action => action.wallRuleScope)).toEqual(['all', 'all', 'all', 'all'])
  wrapper.vm.$store.getters.apis = { pauseWallAlertRule: {} }
  expect(ListView.computed.contextMenuActions.call(wrapper.vm).map(action => action.wallRuleAction)).toEqual(['pause', 'resume'])
  wrapper.unmount()
})

test.each([
  [[{ uid: 'a', isPaused: false, silenced: true }, { uid: 'b', isPaused: false, silenced: true }], ['pause', 'unsilence']],
  [[{ uid: 'a', isPaused: true, silenced: true }, { uid: 'b', ispaused: 'Stopped', silenced: true }], ['resume', 'unsilence']],
  [[{ uid: 'a', isPaused: false, silenced: true }, { uid: 'b', isPaused: true, silenced: false }], ['pause', 'resume', 'silence', 'unsilence']]
])('selection menu exposes the matching forward and reverse actions for %j', (records, expected) => {
  const getters = { apis: { pauseWallAlertRule: {}, createWallAlertSilence: {}, expireWallAlertSilence: {}, listWallAlertSilences: {} } }
  const context = { actions: config.actions, selectedRowKeys: records.map(rule => rule.uid), selectionList: records, $store: { getters } }
  const actions = () => ListView.computed.contextMenuActions.call(context).filter(action => !action.contextOnly)
  expect(actions().map(action => action.wallRuleAction)).toEqual(expected)
  delete getters.apis.listWallAlertSilences
  expect(actions().map(action => action.wallRuleAction)).not.toContain('unsilence')
})

test.each(['pause', 'resume'])('selected-rule %s only shows and writes eligible selected rules', async kind => {
  const action = config.actions.find(action => action.wallRuleAction === kind && !action.wallRuleScope)
  const paused = kind === 'resume'
  const selectedItems = [
    { uid: 'eligible-a', isPaused: paused },
    { uid: 'eligible-b', isPaused: paused },
    { uid: 'eligible-c', isPaused: paused },
    { uid: 'ineligible', isPaused: !paused }
  ]
  const wrapper = mountModal({ currentAction: { ...action, invokedAsGroupAction: true }, selectedItems, resource: { uid: 'other', isPaused: paused } })
  await flushPromises()
  expect(wrapper.vm.targets.map(rule => rule.uid)).toEqual(['eligible-a', 'eligible-b', 'eligible-c'])
  await wrapper.setData({ acknowledged: true })
  await wrapper.vm.submit()
  expect(postAPI.mock.calls).toEqual(['eligible-a', 'eligible-b', 'eligible-c'].map(id => ['pauseWallAlertRule', { id, paused: !paused }]))
  expect(getAPI).not.toHaveBeenCalled()
  wrapper.unmount()
})

test('selected silence expiry snapshots the selection and excludes other rules, shared and regex silences', async () => {
  const action = config.actions.find(action => action.wallRuleAction === 'unsilence' && !action.wallRuleScope)
  const silence = (id, uid) => ({ id, state: 'active', matchers: [{ name: '__alert_rule_uid__', value: uid, isRegex: false, isEqual: true }] })
  const rows = [silence('a-1', 'a'), silence('a-2', 'a'), silence('b-1', 'b'), silence('other-1', 'other'), silence('unapplied-1', 'unapplied'),
    { id: 'shared', state: 'active', ruleUid: 'a', matchers: [{ name: 'alertname', value: 'CPU' }] },
    { id: 'regex', state: 'active', ruleUid: 'a', matchers: [{ name: '__alert_rule_uid__', value: 'a|other', isRegex: true }] },
    { ...silence('expired', 'a'), state: 'expired' }]
  getAPI.mockResolvedValue({ listwallalertsilencesresponse: { count: rows.length, silence: rows } })
  const selectedItems = [...rules.map(rule => ({ ...rule, silenced: true })), { uid: 'unapplied', name: 'Not silenced', silenced: false }]
  const wrapper = mountModal({ currentAction: { ...action, invokedAsGroupAction: true }, selectedItems, resource: { uid: 'other' } })
  await flushPromises()
  expect(getAPI.mock.calls).toEqual([['listWallAlertSilences', { state: 'active' }]])
  expect(wrapper.vm.targets.map(rule => [rule.silenceId, rule.name])).toEqual([['a-1', 'CPU'], ['a-2', 'CPU'], ['b-1', 'Memory']])
  expect(wrapper.vm.displayTargets.map(target => [target.name, target.count])).toEqual([['CPU', 2], ['Memory', 1]])
  expect(wrapper.findAll('.rule-targets li').map(item => item.text())).toEqual(['CPU (사일런스 2개)', 'Memory'])
  await wrapper.setProps({ selectedItems: [{ uid: 'other' }] })
  expect(postAPI).not.toHaveBeenCalled()
  postAPI.mockRejectedValueOnce(new Error('temporary failure'))
  await wrapper.setData({ acknowledged: true })
  await wrapper.vm.submit()
  expect(wrapper.findAll('.rule-targets li').map(item => item.text())).toEqual(['CPU'])
  await wrapper.vm.submit()
  expect(postAPI.mock.calls.map(call => call[1].id)).toEqual(['a-1', 'a-2', 'b-1', 'a-1'])
  expect(wrapper.emitted('close-action')).toHaveLength(1)
  wrapper.unmount()
})

test('selected silence expiry never writes when loading fails or no exact rule silence matches', async () => {
  const action = config.actions.find(action => action.wallRuleAction === 'unsilence' && !action.wallRuleScope)
  for (const failed of [true, false]) {
    if (failed) getAPI.mockRejectedValue(new Error('offline'))
    else getAPI.mockResolvedValue({ listwallalertsilencesresponse: { count: 1, silence: [{ id: 'other-silence', state: 'active', matchers: [{ name: '__alert_rule_uid__', value: 'other' }] }] } })
    const wrapper = mountModal({ currentAction: { ...action, invokedAsGroupAction: true }, selectedItems: rules })
    await flushPromises()
    await wrapper.setData({ acknowledged: true })
    await wrapper.vm.submit()
    expect(wrapper.vm.loadFailed).toBe(failed)
    expect(wrapper.vm.targets).toEqual([])
    expect(wrapper.find('.rule-targets').exists()).toBe(false)
    expect(wrapper.find('a-form-stub').exists()).toBe(!failed)
    expect(wrapper.findAll('.rule-actions a-button-stub')).toHaveLength(2)
    expect(postAPI).not.toHaveBeenCalled()
    wrapper.unmount()
  }
})

test('all-rule resume only targets paused rules and does not change silences', async () => {
  getAPI.mockResolvedValue({ listwallalertrulesresponse: { count: 3, wallalertruleresponse: [rules[0], { uid: 'paused-a', isPaused: true }, { uid: 'paused-b', ispaused: 'Stopped' }] } })
  const action = config.actions.find(action => action.wallRuleAction === 'resume' && action.wallRuleScope === 'all')
  const wrapper = mountModal({ currentAction: action, selectedItems: [rules[0]] })
  await flushPromises()
  expect(wrapper.vm.targets.map(rule => rule.uid)).toEqual(['paused-a', 'paused-b'])
  expect(postAPI).not.toHaveBeenCalled()
  await wrapper.setData({ acknowledged: true })
  await wrapper.vm.submit()
  expect(postAPI.mock.calls).toEqual([
    ['pauseWallAlertRule', { id: 'paused-a', paused: false }],
    ['pauseWallAlertRule', { id: 'paused-b', paused: false }]
  ])
  wrapper.unmount()
})

test('all-silence expiry resolves names, confirms every active silence and retries only failed IDs', async () => {
  const action = config.actions.find(action => action.wallRuleAction === 'unsilence' && action.wallRuleScope === 'all')
  const silence = id => ({ id, state: 'active', matchers: [{ name: '__alert_rule_uid__', value: 'a' }] })
  getAPI.mockImplementation(name => Promise.resolve(name === 'listWallAlertRules'
    ? { listwallalertrulesresponse: { count: rules.length, wallalertruleresponse: rules } }
    : { listwallalertsilencesresponse: { count: 2, silence: [silence('s-a'), silence('s-b')] } }))
  const wrapper = mountModal({ currentAction: action, resource: rules[1], selectedItems: [rules[1]] })
  await flushPromises()
  expect(wrapper.vm.targets.map(rule => [rule.uid, rule.name])).toEqual([['s-a', 'CPU'], ['s-b', 'CPU']])
  expect(postAPI).not.toHaveBeenCalled()
  postAPI.mockRejectedValueOnce(new Error('temporary failure'))
  await wrapper.setData({ acknowledged: true })
  await wrapper.vm.submit()
  expect(wrapper.vm.failures.map(failure => failure.rule.silenceId)).toEqual(['s-a'])
  expect(wrapper.emitted('close-action')).toBeUndefined()
  await wrapper.vm.submit()
  expect(postAPI.mock.calls.map(call => [call[0], call[1].id])).toEqual([
    ['expireWallAlertSilence', 's-a'], ['expireWallAlertSilence', 's-b'], ['expireWallAlertSilence', 's-a']
  ])
  expect(message.success).toHaveBeenCalledWith('사일런스 2개를 종료했습니다.')
  expect(wrapper.emitted('close-action')).toHaveLength(1)
  wrapper.unmount()
})

test('failed silence loading cannot expire anything or fall back to selected rules', async () => {
  getAPI.mockImplementation(name => name === 'listWallAlertRules'
    ? Promise.resolve({ listwallalertrulesresponse: { count: rules.length, wallalertruleresponse: rules } })
    : Promise.reject(new Error('Wall unavailable')))
  const wrapper = mountModal({ currentAction: config.actions.find(action => action.wallRuleAction === 'unsilence' && action.wallRuleScope === 'all'), selectedItems: rules })
  await flushPromises()
  await wrapper.setData({ acknowledged: true })
  await wrapper.vm.submit()
  expect(wrapper.vm.loadFailed).toBe(true)
  expect(postAPI).not.toHaveBeenCalled()
  wrapper.unmount()
})

test('global expiry requires both silence-list and expiry permissions', () => {
  const action = config.actions.find(action => action.wallRuleAction === 'unsilence' && action.wallRuleScope === 'all')
  expect(action.show({}, { apis: { expireWallAlertSilence: {} } })).toBe(false)
  expect(action.show({}, { apis: { listWallAlertSilences: {}, expireWallAlertSilence: {} } })).toBe(true)
})

test('blank list space opens only the all-rule menu without a checkbox selection', () => {
  const context = {
    actions: config.actions,
    selectedRowKeys: [],
    $route: { name: 'alertRules' },
    $store: { getters: { apis: { pauseWallAlertRule: {}, createWallAlertSilence: {} } } },
    quickViewEnabled: () => true,
    closeContextQuickView: jest.fn(),
    contextMenuActions: config.actions.filter(action => action.contextOnly)
  }
  const event = { target: { closest: () => null }, clientX: 100, clientY: 200, preventDefault: jest.fn(), stopPropagation: jest.fn() }
  ListView.methods.handleGlobalContextMenu.call(context, event)
  expect(context.contextQuickViewVisible).toBe(true)
  expect(context.contextQuickViewRecord).toEqual({})
  expect(event.preventDefault).toHaveBeenCalled()
})

test('all-rule actions retain their global scope even with multiple rows selected', () => {
  const context = { selectedRowKeys: ['a', 'b'], closeContextQuickView: jest.fn(), $parent: { execAction: jest.fn() } }
  const action = config.actions.find(action => action.wallRuleScope === 'all')
  ListView.methods.handleContextAction.call(context, action)
  expect(context.$parent.execAction).toHaveBeenCalledWith(action, false)
  ListView.methods.handleContextAction.call(context, pause)
  expect(context.$parent.execAction).toHaveBeenLastCalledWith(pause, true)
})

test.each([ko, en])('availability messages are complete sentences for every known state and unknown fallback', locale => {
  for (const state of ['Disabled', 'NotConfigured', 'Unavailable', 'ConnectionFailed', 'DnsError', 'TlsError', 'Timeout', 'AuthenticationFailed', 'InvalidResponse', 'Degraded', 'CheckFailed', 'future-state']) {
    const vm = { wallState: state, $te: key => key in locale, $t: key => locale[key] || key }
    const message = AutoAlertBanner.computed.wallAvailabilityMessage.call(vm)
    if (['ConnectionFailed', 'DnsError', 'TlsError', 'Timeout'].includes(state)) {
      expect(message).toBe(locale['message.wall.availability.disconnected'])
    } else {
      expect(message).toBe((locale['message.wall.availability.' + state] || locale['message.wall.availability.CheckFailed']) + ' ' + locale['message.wall.availability.impact'])
    }
    expect(message).not.toContain('message.wall.')
    expect(message).not.toMatch(/비활성$|Disabled$/)
  }
})
