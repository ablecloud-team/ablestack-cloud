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

import { applyRuleAction, eligibleRules, fetchActiveSilences, fetchAllRules, ruleIsPaused, ruleIsSilenced } from '@/utils/wallAlertRules'

const response = (rules, count = rules.length) => ({ listwallalertrulesresponse: { wallalertruleresponse: rules, count } })

test('all rules includes subsequent pages without list filters', async () => {
  const rules = Array.from({ length: 203 }, (_, i) => ({ uid: `r${i}` }))
  const request = jest.fn((api, params) => Promise.resolve(response(rules.slice((params.page - 1) * 100, params.page * 100), rules.length)))
  expect(await fetchAllRules(request)).toEqual(rules)
  expect(request.mock.calls).toEqual([1, 2, 3].map(page => ['listWallAlertRules', { page, pagesize: 100 }]))
})

test('empty CloudStack list response is a valid empty rule set', async () => {
  expect(await fetchAllRules(jest.fn().mockResolvedValue({ listwallalertrulesresponse: {} }))).toEqual([])
})

test('all rules refuses an incomplete or malformed result', async () => {
  for (const result of [{}, response([], 4), response([{ uid: 'a' }, { uid: 'a' }], 2)]) {
    await expect(fetchAllRules(jest.fn().mockResolvedValue(result))).rejects.toThrow()
  }
  const request = jest.fn().mockResolvedValueOnce(response([{ uid: 'a' }], 2)).mockRejectedValue(new Error('offline'))
  await expect(fetchAllRules(request)).rejects.toThrow('offline')
})

test('pause and resume use the boolean state before display text', () => {
  expect(ruleIsPaused({ isPaused: false, ispaused: 'Stopped' })).toBe(false)
  expect(ruleIsPaused({ ispaused: 'Running' })).toBe(false)
  expect(ruleIsPaused({ ispaused: 'Stopped' })).toBe(true)
  const rules = [{ uid: 'a', isPaused: false }, { uid: 'b', isPaused: true }, { uid: 'a' }]
  expect(eligibleRules(rules, 'pause').map(r => r.uid)).toEqual(['a'])
  expect(eligibleRules(rules, 'resume').map(r => r.uid)).toEqual(['b'])
})

test('silence skips already silenced rules and handles KST display dates', () => {
  jest.spyOn(Date, 'now').mockReturnValue(Date.parse('2026-10-16T00:00:00Z'))
  expect(ruleIsSilenced({ silenceEndsAt: '2026-10-16 09:30' })).toBe(true)
  expect(ruleIsSilenced({ silenceEndsAt: '2026-10-16 08:30' })).toBe(false)
  expect(ruleIsSilenced({ silenced: false, silenceEndsAt: '2099-01-01 00:00' })).toBe(false)
  const rules = [{ uid: 'a', silenced: true }, { uid: 'b', silenced: false }]
  expect(eligibleRules(rules, 'silence')).toEqual([rules[1]])
  jest.restoreAllMocks()
})

test('partial failures do not stop other targets and retries exclude successes', async () => {
  const rules = [{ uid: 'a' }, { uid: 'b' }, { uid: 'c' }]
  const request = jest.fn().mockResolvedValueOnce({}).mockRejectedValueOnce(new Error('failed')).mockResolvedValue({})
  const progress = jest.fn()
  const result = await applyRuleAction(request, rules, 'pause', undefined, progress)
  expect(result.succeeded).toEqual([rules[0], rules[2]])
  expect(result.failed.map(f => f.rule)).toEqual([rules[1]])
  expect(progress.mock.calls).toEqual([[1], [2], [3]])
  await applyRuleAction(request, result.failed.map(f => f.rule), 'pause')
  expect(request.mock.calls.map(call => call[1].id)).toEqual(['a', 'b', 'c', 'b'])
  expect(request.mock.calls.every(call => call[1].paused === true)).toBe(true)
})

test('silence uses a rule-specific matcher, duration and deduplicated targets', async () => {
  const request = jest.fn().mockResolvedValue({})
  await applyRuleAction(request, [{ uid: 'a' }, { uid: 'a' }, { metadata: { rule_uid: 'b' } }], 'silence', 120)
  expect(request).toHaveBeenCalledTimes(2)
  expect(request.mock.calls[0]).toEqual(['createWallAlertSilence', {
    'labels[0].key': '__alert_rule_uid__',
    'labels[0].value': 'a',
    durationMinutes: 120,
    comment: 'action:silence:120m'
  }])
  await applyRuleAction(request, [{ uid: 'c' }], 'resume')
  expect(request).toHaveBeenLastCalledWith('pauseWallAlertRule', { id: 'c', paused: false })
})

test('active silences use distinct silence IDs even when they belong to the same rule', async () => {
  const rows = [
    { id: 'silence-a', state: 'active', matchers: [{ name: '__alert_rule_uid__', value: 'rule-a', isRegex: false, isEqual: true }] },
    { id: 'silence-b', state: 'active', matchers: [{ name: '__alert_rule_uid__', value: 'rule-a', isRegex: false, isEqual: true }] },
    { id: 'silence-old', state: 'expired' },
    { id: 'silence-later', state: 'pending' }
  ]
  const request = jest.fn().mockResolvedValue({ listwallalertsilencesresponse: { count: rows.length, silence: rows } })
  const targets = await fetchActiveSilences(request)
  expect(request).toHaveBeenCalledWith('listWallAlertSilences', { state: 'active' })
  expect(targets.map(target => [target.uid, target.ruleUid])).toEqual([['silence-a', 'rule-a'], ['silence-b', 'rule-a']])
  expect(eligibleRules(targets, 'unsilence')).toHaveLength(2)
  request.mockClear()
  await applyRuleAction(request, targets, 'unsilence')
  expect(request.mock.calls).toEqual([
    ['expireWallAlertSilence', { id: 'silence-a' }],
    ['expireWallAlertSilence', { id: 'silence-b' }]
  ])
})

test('empty silences are valid but incomplete or ambiguous lists cannot authorize expiry', async () => {
  expect(await fetchActiveSilences(jest.fn().mockResolvedValue({ listwallalertsilencesresponse: {} }))).toEqual([])
  for (const response of [undefined, { count: 2, silence: [{ id: 'a', state: 'active' }] }, { count: 1, silence: [{ state: 'active' }] }, { count: 2, silence: [{ id: 'a', state: 'active' }, { id: 'a', state: 'active' }] }]) {
    await expect(fetchActiveSilences(jest.fn().mockResolvedValue({ listwallalertsilencesresponse: response }))).rejects.toThrow()
  }
})

test('expiry remains sequential and retries only failed silence IDs', async () => {
  const targets = [{ uid: 's-a', silenceId: 's-a', state: 'active' }, { uid: 's-b', silenceId: 's-b', state: 'active' }]
  let active = 0
  let peak = 0
  let calls = 0
  const request = jest.fn(async () => {
    active++
    peak = Math.max(peak, active)
    await Promise.resolve()
    active--
    if (++calls === 1) throw new Error('temporary failure')
    return {}
  })
  const result = await applyRuleAction(request, targets, 'unsilence')
  expect(peak).toBe(1)
  expect(result.succeeded.map(target => target.silenceId)).toEqual(['s-b'])
  await applyRuleAction(request, result.failed.map(failure => failure.rule), 'unsilence')
  expect(request.mock.calls.map(call => call[1].id)).toEqual(['s-a', 's-b', 's-a'])
})
