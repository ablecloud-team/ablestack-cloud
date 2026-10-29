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

export const ruleUid = rule => rule?.uid || rule?.metadata?.rule_uid || rule?.ruleUid || rule?.id

export function ruleIsPaused (rule) {
  if (typeof rule?.isPaused === 'boolean') return rule.isPaused
  return /^(true|paused|stopped)$/i.test(String(rule?.ispaused || ''))
}

export function ruleIsSilenced (rule) {
  if (typeof rule?.silenced === 'boolean') return rule.silenced
  // The API's display timestamps are in KST, regardless of the browser timezone.
  let end = rule?.silenceEndsAt || ''
  if (/^\d{4}-\d{2}-\d{2} \d{2}:\d{2}$/.test(end)) end = end.replace(' ', 'T') + ':00+09:00'
  return Date.parse(end) > Date.now()
}

export function uniqueRules (rules) {
  const seen = new Set()
  return rules.filter(rule => {
    const uid = ruleUid(rule)
    if (!uid || seen.has(uid)) return false
    seen.add(uid)
    return true
  })
}

export function eligibleRules (rules, action) {
  return uniqueRules(rules).filter(rule => {
    if (action === 'pause') return !ruleIsPaused(rule)
    if (action === 'resume') return ruleIsPaused(rule)
    if (action === 'silence') return !ruleIsSilenced(rule)
    if (action === 'unsilence') return !!rule.silenceId && rule.state === 'active'
    return false
  })
}

// Fetch every page before presenting an all-rules confirmation. Search filters
// and the current list page must never limit an operation labelled "all".
export async function fetchAllRules (request) {
  const rules = []
  const seen = new Set()
  for (let page = 1; ; page++) {
    const json = await request('listWallAlertRules', { page, pagesize: 100 })
    const response = json?.listwallalertrulesresponse
    // CloudStack serializes an empty ListResponse as an empty object.
    const count = response && Object.keys(response).length === 0 ? 0 : Number(response?.count)
    const rows = response?.wallalertruleresponse || []
    if (!response || !Number.isInteger(count) || count < 0 || !Array.isArray(rows)) throw new Error('Invalid rule list')
    for (const rule of rows) {
      const uid = ruleUid(rule)
      if (!uid || seen.has(uid)) throw new Error('Incomplete rule list')
      seen.add(uid)
      rules.push(rule)
    }
    if (rules.length === count) return rules
    if (rules.length > count) throw new Error('Invalid rule count')
    if (!rows.length) throw new Error('Incomplete rule list')
  }
}

export async function fetchActiveSilences (request) {
  const json = await request('listWallAlertSilences', { state: 'active' })
  const response = json?.listwallalertsilencesresponse
  const count = response && Object.keys(response).length === 0 ? 0 : Number(response?.count)
  const rows = response?.silence || []
  if (!response || !Number.isInteger(count) || count < 0 || !Array.isArray(rows) || rows.length !== count) throw new Error('Incomplete silence list')
  const seen = new Set()
  const targets = []
  for (const silence of rows) {
    const state = String(silence.state || '').toLowerCase()
    if (!silence.id || seen.has(silence.id) || !['active', 'pending', 'expired'].includes(state)) throw new Error('Invalid silence list')
    seen.add(silence.id)
    if (state !== 'active') continue
    const matcher = (silence.matchers || []).find(m => m.name === '__alert_rule_uid__' && !m.isRegex && m.isEqual !== false)
    // Expiry targets are keyed by silence ID: one rule can have multiple silences.
    targets.push({ ...silence, uid: silence.id, silenceId: silence.id, state, ruleUid: silence.ruleUid || matcher?.value, name: silence.ruleTitle || silence.id })
  }
  return targets
}

// Continue after individual failures, preserving failed targets for retry.
export async function applyRuleAction (request, rules, action, timing, onProgress = () => {}) {
  const succeeded = []
  const failed = []
  for (const rule of uniqueRules(rules)) {
    try {
      const uid = ruleUid(rule)
      if (action === 'silence') {
        await request('createWallAlertSilence', {
          'labels[0].key': '__alert_rule_uid__',
          'labels[0].value': uid,
          ...(timing?.endsAt
            ? { endsAt: timing.endsAt, comment: `action:silence:until:${timing.endsAt}` }
            : { durationMinutes: timing, comment: `action:silence:${timing}m` })
        })
      } else if (action === 'pause' || action === 'resume') {
        await request('pauseWallAlertRule', { id: uid, paused: action === 'pause' })
      } else if (action === 'unsilence') {
        if (!rule.silenceId) throw new Error('Missing silence ID')
        await request('expireWallAlertSilence', { id: rule.silenceId })
      } else {
        throw new Error('Unsupported rule action')
      }
      succeeded.push(rule)
    } catch (error) {
      failed.push({ rule, error })
    }
    onProgress(succeeded.length + failed.length)
  }
  return { succeeded, failed }
}
