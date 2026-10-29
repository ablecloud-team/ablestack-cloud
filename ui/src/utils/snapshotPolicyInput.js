// Licensed to the Apache Software Foundation (ASF) under one or more
// contributor license agreements. See NOTICE for copyright ownership.
// Licensed under the Apache License, Version 2.0 (the "License");
// http://www.apache.org/licenses/LICENSE-2.0 . Distributed AS IS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND.
export const policyIntervals = ['hourly', 'daily', 'weekly', 'monthly']
const integer = (value, min = 0) => value !== null && value !== undefined && value !== '' &&
  /^(0|[1-9]\d*)$/.test(String(value)) && Number.isSafeInteger(Number(value)) && Number(value) >= min
export const emptyPolicyLimits = () => ({ maxima: {}, owner: null, domain: null, exempt: null })

export function policyFieldErrors (form, maximum) {
  const errors = []
  const add = (name, key, params = {}) => errors.push({ name, key, params })
  if (!policyIntervals.includes(form.intervaltype)) add('intervaltype', 'message.snapshot.policy.interval.invalid')
  if (form.intervaltype === 'hourly') {
    if (!integer(form.time) || Number(form.time) > 59) add('time', 'message.snapshot.policy.minute.range')
  } else if (policyIntervals.includes(form.intervaltype) && (typeof form.timeSelect?.isValid !== 'function' || typeof form.timeSelect?.format !== 'function' || !form.timeSelect.isValid() ||
    !integer(Number(form.timeSelect.format('mm'))) || Number(form.timeSelect.format('mm')) > 59 ||
    !integer(Number(form.timeSelect.format('HH'))) || Number(form.timeSelect.format('HH')) > 23)) {
    add('timeSelect', 'message.error.time')
  }
  if (form.intervaltype === 'weekly' && (!integer(form['day-of-week']) || Number(form['day-of-week']) > 6)) add('day-of-week', 'message.snapshot.policy.weekday.range')
  if (form.intervaltype === 'monthly' && (!integer(form['day-of-month'], 1) || Number(form['day-of-month']) > 28)) add('day-of-month', 'message.snapshot.policy.monthday.range')
  if (!integer(form.maxsnaps, 1)) add('maxsnaps', 'message.snapshot.policy.keep.integer')
  else if (maximum === 0) add('maxsnaps', 'message.snapshot.policy.interval.disabled')
  else if (maximum != null && Number(form.maxsnaps) > maximum) add('maxsnaps', 'message.snapshot.policy.keep.range', { max: maximum })
  return errors
}

export function encodePolicySchedule (form) {
  if (policyFieldErrors(form).some(e => e.name !== 'maxsnaps')) throw new Error('Invalid snapshot schedule')
  if (form.intervaltype === 'hourly') return Number(form.time)
  const time = form.timeSelect.format('mm:HH')
  if (form.intervaltype === 'daily') return time
  return `${time}:${Number(form[form.intervaltype === 'weekly' ? 'day-of-week' : 'day-of-month']) + (form.intervaltype === 'weekly' ? 1 : 0)}`
}

export function effectivePolicyMaximum (limits, interval) {
  const values = [limits.maxima[interval]]
  if (limits.exempt === false) values.push(limits.owner, limits.domain)
  const known = values.filter(value => Number.isSafeInteger(value) && value >= 0)
  return known.length ? Math.min(...known) : null
}

// Never infer a scope from the request: non-admin resource-limit queries can be rewritten to the caller.
export async function readPolicyLimits (request, apis, resource) {
  const result = emptyPolicyLimits()
  const attempt = async (command, params) => {
    if (!(command in apis)) return null
    try { return await request(command, params, { timeout: 15000 }) } catch (_) { return null }
  }
  const caps = attempt('listCapabilities', {}).then(json => {
    const values = json?.listcapabilitiesresponse?.capability?.snapshotpolicymax
    for (const interval of policyIntervals) {
      if (integer(values?.[interval])) result.maxima[interval] = Number(values[interval])
    }
  })
  const identity = resource.projectid
    ? Promise.resolve().then(() => { result.exempt = false })
    : resource.account && resource.domainid
      ? attempt('listAccounts', { name: resource.account, domainid: resource.domainid, listall: true }).then(json => {
        const rows = json?.listaccountsresponse?.account
        const account = (Array.isArray(rows) ? rows : []).find(a => a && a.name === resource.account && a.domainid === resource.domainid)
        if (account && integer(account.accounttype)) result.exempt = Number(account.accounttype) === 1
      }) : Promise.resolve()
  const scope = resource.projectid ? { projectid: resource.projectid } : resource.account && resource.domainid ? { account: resource.account, domainid: resource.domainid } : null
  const read = async (params, owner) => {
    if (!params) return null
    const json = await attempt('listResourceLimits', { ...params, resourcetype: 3 })
    const rows = json?.listresourcelimitsresponse?.resourcelimit
    const limit = (Array.isArray(rows) ? rows : []).find(row => row && String(row.resourcetype) === '3' && !row.tag &&
      (owner ? resource.projectid ? row.projectid === resource.projectid : row.account === resource.account && row.domainid === resource.domainid && !row.projectid
        : row.domainid === resource.domainid && !row.account && !row.projectid))
    return limit && (limit.max === -1 || limit.max === '-1' || integer(limit.max)) ? Number(limit.max) : null
  }
  await Promise.all([caps, identity, read(scope, true).then(value => { result.owner = value }),
    read(resource.domainid ? { domainid: resource.domainid } : null, false).then(value => { result.domain = value })])
  return result
}
