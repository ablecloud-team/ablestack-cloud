// Licensed to the Apache Software Foundation (ASF) under one or more
// contributor license agreements. See NOTICE for ownership information.
// Licensed under the Apache License, Version 2.0 (the "License");
// http://www.apache.org/licenses/LICENSE-2.0 . Distributed AS IS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND.
import { readPolicyDetail } from '@/utils/snapshotPolicyDetail'
import { policyInterval, policySchedule, policyScheduling } from '@/utils/snapshotPolicyPresentation'
import ko from '../../../public/locales/ko_KR.json'
const id = '12345678-1234-1234-1234-123456789abc'; const volumeid = '22345678-1234-1234-1234-123456789abc'
const route = { params: { id }, query: {} }
const record = () => ({ id, volumeid, intervaltype: 2, schedule: '5:3:2', timezone: 'UTC', schedulingenabled: true, account: 'owner', domainid: id, tags: [], zone: [], storage: [], policycontext: { version: 1, observedat: '2026-10-27T00:00:00Z', volumeid, schedulingenabled: true, schedulestate: 'SCHEDULED', pollintervalseconds: 300, historysource: 'RETAINED_SCHEDULER_JOBS', historylimit: 50, currentjobknown: false } })
const reply = p => ({ listsnapshotpoliciesresponse: { count: 1, snapshotpolicy: [p] } })
const t = (key, values) => Object.entries(values || {}).reduce((s, [k, v]) => s.replace('{' + k + '}', v), ko[key] || key)
test('all four periods, Korean weekdays and month use one formatter', () => {
  expect([0, 1, 2, 3].map(n => policyInterval(n, t))).toEqual(['매시간', '매일', '매주', '매월'])
  expect(policySchedule({ intervaltype: 0, schedule: '5' }, t)).toBe('5' + ko['label.min.past.hour'])
  expect(policySchedule(record(), t)).toBe('03:05 · ' + ko['label.every'] + ' ' + ko['label.monday'])
  expect(policySchedule({ intervaltype: 3, schedule: '9:7:28' }, t)).toBe('07:09 · 매월 28일')
  expect(policySchedule({ intervaltype: 3, schedule: '9:7:29' }, t)).toBe(ko['label.snapshot.list.unknown'])
  expect(policyScheduling({ schedulingenabled: false }, t)).toBe(ko['label.snapshot.policy.schedule.paused'])
})
test('direct detail makes one bounded ACL-controlled read and never fabricates context', async () => {
  const request = jest.fn().mockResolvedValue(reply(record())); await expect(readPolicyDetail(request, route, {})).resolves.toEqual(reply(record()))
  expect(request).toHaveBeenCalledWith('listSnapshotPolicies', expect.objectContaining({ id, includepolicycontext: true, pagesize: 1 }), { preserveOnFailure: true, timeout: 20000 })
})
test.each(['version', 'volume', 'count', 'scope', 'context', 'job', 'date', 'state'])('rejects incomplete or mismatched %s', async kind => {
  const p = record(); const json = reply(p)
  if (kind === 'version') p.policycontext.version = 2
  if (kind === 'volume') p.policycontext.volumeid = id
  if (kind === 'count') json.listsnapshotpoliciesresponse.count = 2
  if (kind === 'scope') p.id = volumeid
  if (kind === 'context') delete p.policycontext
  if (kind === 'job') p.policycontext.latestjob = { id }
  if (kind === 'date') p.policycontext.nextscheduled = 'not a date'
  if (kind === 'state') { p.policycontext.schedulestate = 'PAUSED'; p.policycontext.nextscheduled = '2026-10-27T00:00:00Z' }
  await expect(readPolicyDetail(jest.fn().mockResolvedValue(json), route, {})).rejects.toThrow('message.snapshot.policy.detail.incomplete')
})
test('missing, invalid UUID, project change and late response cannot become successful details', async () => {
  const request = jest.fn().mockResolvedValue({ listsnapshotpoliciesresponse: {} })
  await expect(readPolicyDetail(request, route, {})).rejects.toMatchObject({ response: { status: 404 } })
  await expect(readPolicyDetail(request, { params: { id: 'bad' } }, {})).rejects.toMatchObject({ response: { status: 404 } })
  request.mockResolvedValue(reply(record()))
  await expect(readPolicyDetail(request, route, { id })).rejects.toThrow('message.snapshot.policy.detail.incomplete')
  await expect(readPolicyDetail(request, route, {}, () => false)).rejects.toThrow('message.snapshot.policy.context.changed')
})
test.each([401, 403, 404, 431, 432, 531])('API scope or absent UUID %s has a localized safe message', async code => {
  const request = jest.fn().mockRejectedValue({ response: { status: code, data: { errorresponse: { errorcode: code, errortext: 'Internal API class and UUID resolver details' } } } })
  await expect(readPolicyDetail(request, route, {})).rejects.toMatchObject({ message: 'message.snapshot.policy.detail.missing', response: { status: code } })
})
