// Licensed to the Apache Software Foundation (ASF) under one or more
// contributor license agreements. See NOTICE for copyright ownership.
// Licensed under the Apache License, Version 2.0 (the "License");
// http://www.apache.org/licenses/LICENSE-2.0 . Distributed AS IS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND.
import moment from 'moment'
import { policyFieldErrors, encodePolicySchedule, readPolicyLimits, effectivePolicyMaximum, emptyPolicyLimits } from '@/utils/snapshotPolicyInput'
const form = (extra = {}) => ({ intervaltype: 'hourly', time: 0, maxsnaps: 1, timezone: 'UTC', ...extra })
const apis = { listCapabilities: {}, listAccounts: {}, listResourceLimits: {} }
const volume = { account: 'owner', domainid: 'domain' }
const capabilities = { listcapabilitiesresponse: { capability: { snapshotpolicymax: { hourly: 3, daily: 0, weekly: 11, monthly: 5 } } } }

test.each([0, 59])('hourly boundary %s encodes without replacing zero', value => {
  expect(policyFieldErrors(form({ time: value }), 3)).toEqual([])
  expect(encodePolicySchedule(form({ time: value }))).toBe(value)
})
test.each([-1, 60, 1.5, undefined, null, '', NaN, Infinity, '1e1'])('invalid minute %s is rejected', time => {
  expect(policyFieldErrors(form({ time })).map(e => e.name)).toContain('time')
  expect(() => encodePolicySchedule(form({ time }))).toThrow()
})
test.each([['daily', undefined, '00:00'], ['weekly', 0, '00:00:1'], ['weekly', 6, '00:00:7'], ['monthly', 1, '00:00:1'], ['monthly', 28, '00:00:28']])('encodes %s boundary %s', (intervaltype, day, expected) => {
  const values = form({ intervaltype, timeSelect: moment('00:00', 'HH:mm'), 'day-of-week': day, 'day-of-month': day })
  expect(policyFieldErrors(values)).toEqual([]); expect(encodePolicySchedule(values)).toBe(expected)
})
test.each([['weekly', -1], ['weekly', 7], ['weekly', 1.5], ['monthly', 0], ['monthly', 29], ['monthly', 1.5]])('rejects %s day %s', (intervaltype, day) => {
  expect(policyFieldErrors(form({ intervaltype, timeSelect: moment(), 'day-of-week': day, 'day-of-month': day })).some(e => e.name.startsWith('day-'))).toBe(true)
})
test.each([0, -1, 1.5, null, undefined, '', '1e1'])('retention %s must be a positive whole number', maxsnaps => {
  expect(policyFieldErrors(form({ maxsnaps }), 3).some(e => e.name === 'maxsnaps')).toBe(true)
})
test('runtime limits support zero, non-default maxima and unknown metadata', () => {
  expect(policyFieldErrors(form({ maxsnaps: 11 }), 11)).toEqual([])
  expect(policyFieldErrors(form({ maxsnaps: 4 }), 3)[0].params).toEqual({ max: 3 })
  expect(policyFieldErrors(form(), 0)[0].key).toContain('disabled')
  expect(policyFieldErrors(form({ maxsnaps: 9 }), null)).toEqual([])
})
test('global and exact owner/domain limits are read, and root-owner exemption is respected', async () => {
  const request = jest.fn((cmd, params) => Promise.resolve(cmd === 'listCapabilities' ? capabilities : cmd === 'listAccounts'
    ? { listaccountsresponse: { account: [{ name: 'owner', domainid: 'domain', accounttype: 0 }] } }
    : { listresourcelimitsresponse: { resourcelimit: [{ resourcetype: '3', domainid: 'domain', max: params.account ? 2 : 4, ...(params.account ? { account: 'owner' } : {}) }] } }))
  const result = await readPolicyLimits(request, apis, volume)
  expect(effectivePolicyMaximum(result, 'hourly')).toBe(2); expect(effectivePolicyMaximum(result, 'daily')).toBe(0)
  expect(effectivePolicyMaximum({ ...result, exempt: true, owner: 0, domain: 0 }, 'hourly')).toBe(3)
  expect(request.mock.calls.some(([cmd]) => cmd === 'listConfigurations')).toBe(false)
})
test('a domain query rewritten to the caller account is never accepted as a domain limit', async () => {
  const request = jest.fn(() => Promise.resolve({ listresourcelimitsresponse: { resourcelimit: [{ resourcetype: '3', domainid: 'domain', account: 'owner', max: 1 }] } }))
  const result = await readPolicyLimits(request, { listResourceLimits: {} }, volume)
  expect(result.owner).toBe(1); expect(result.domain).toBeNull(); expect(result.exempt).toBeNull()
  expect(effectivePolicyMaximum(result, 'hourly')).toBeNull()
})
test('project queries cannot use another project or the caller account as their owner limit', async () => {
  const request = jest.fn(() => Promise.resolve({ listresourcelimitsresponse: { resourcelimit: [{ resourcetype: '3', domainid: 'domain', account: 'caller', max: 0 }] } }))
  const result = await readPolicyLimits(request, apis, { ...volume, projectid: 'project' })
  expect(request).toHaveBeenCalledWith('listResourceLimits', expect.objectContaining({ projectid: 'project' }), expect.anything())
  expect(result.owner).toBeNull(); expect(result.domain).toBeNull(); expect(result.exempt).toBe(false)
})
test('missing, denied and malformed metadata never becomes a fabricated limit', async () => {
  const request = jest.fn().mockRejectedValue(new Error('403'))
  expect(await readPolicyLimits(request, {}, volume)).toEqual(emptyPolicyLimits()); expect(request).not.toHaveBeenCalled()
  expect(await readPolicyLimits(request, apis, volume)).toEqual(emptyPolicyLimits())
  request.mockResolvedValue({ listcapabilitiesresponse: { capability: { snapshotpolicymax: { hourly: null, daily: '', weekly: false, monthly: 1.5 } } } })
  expect((await readPolicyLimits(request, apis, volume)).maxima).toEqual({})
})
test('malformed account and limit arrays finish as unavailable instead of leaving loading stuck', async () => {
  const request = jest.fn().mockResolvedValue({ listaccountsresponse: { account: {} }, listresourcelimitsresponse: { resourcelimit: {} } })
  expect(await readPolicyLimits(request, apis, volume)).toEqual(emptyPolicyLimits())
  request.mockResolvedValue({ listaccountsresponse: { account: [null] }, listresourcelimitsresponse: { resourcelimit: [null] } })
  expect(await readPolicyLimits(request, apis, volume)).toEqual(emptyPolicyLimits())
})
