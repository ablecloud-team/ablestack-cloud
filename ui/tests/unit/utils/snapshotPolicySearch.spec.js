// Licensed to the Apache Software Foundation (ASF) under one or more
// contributor license agreements. See NOTICE for copyright ownership.
// Licensed under the Apache License, Version 2.0 (the "License");
// http://www.apache.org/licenses/LICENSE-2.0 . Distributed AS IS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND.
import { normalizePolicySearch, policySearchError, policySearchQuery, policySearchParams, policyListPage } from '@/utils/snapshotPolicySearch'
const id = '11111111-1111-4111-8111-111111111111'; const volume = '22222222-2222-4222-8222-222222222222'; const project = '33333333-3333-4333-8333-333333333333'
const root = { userInfo: { roletype: 'Admin' } }
test('personal scope is explicit, project markers never reach the API and sorting is retained', () => {
  const query = policySearchQuery({ projectid: '0', sortkey: 'schedule', sortorder: 'asc', state: 'unsupported', page: '3', account: 'old' }, { id, fordisplay: 'false', keyword: '한글' }, 50)
  expect(query).toEqual({ projectid: '0', sortkey: 'schedule', sortorder: 'asc', id, fordisplay: 'false', keyword: '한글', q: '한글', page: '1', pagesize: '50' })
  expect(policySearchParams(query)).toMatchObject({ ignoreproject: true, id, fordisplay: 'false' }); expect(policySearchParams(query).projectid).toBeUndefined()
  expect(policySearchParams({ projectid: project })).toEqual({ projectid: project })
})
test.each([{ state: 'active' }, { zoneid: id }, { intervaltype: 'HOURLY' }, { customid: 'x' }])('unsupported filters are blocked %j', query => { expect(policySearchError(query, root)).toBe('unsupported') })
test.each([{ id: 'bad' }, { volumeid: [] }, { fordisplay: 'yes' }, { projectid: [] }])('invalid input is blocked %j', query => { expect(policySearchError(query, root)).toBe('invalid') })
test('owner and project filters are unambiguous and role-gated', () => {
  expect(policySearchError({ account: 'a' }, root)).toBe('account.domain')
  expect(policySearchError({ projectid: project, domainid: id }, root)).toBe('project.owner')
  expect(policySearchError({ fordisplay: 'false' }, { userInfo: { roletype: 'DomainAdmin' } })).toBe('permission')
  expect(policySearchError({ domainid: id }, { userInfo: { roletype: 'User' } })).toBe('permission')
  expect(policySearchError({ id, volumeid: volume, fordisplay: 'false' }, root)).toBe('')
})
const params = { page: 1, pagesize: 20, ignoreproject: true, fordisplay: 'false' }
const row = { id, volumeid: volume, schedulingenabled: false }
const reply = rows => ({ listsnapshotpoliciesresponse: { count: 1, snapshotpolicy: rows } })
test('a malformed keyword alias cannot silently broaden the query', () => { expect(policySearchError(normalizePolicySearch({ q: ['private', 'scope'] }), root)).toBe('invalid') })
test('server count, scope, UUIDs and requested scheduling flag must be trustworthy', () => {
  expect(policyListPage(reply([row]), params)).toEqual({ count: 1, rows: [row] })
  for (const rows of [[], [row, row], [{ ...row, projectid: project }], [{ ...row, schedulingenabled: true }], [{ ...row, id: 'bad' }]]) expect(() => policyListPage(reply(rows), params)).toThrow()
  expect(() => policyListPage({}, params)).toThrow()
  expect(policyListPage({ listsnapshotpoliciesresponse: { count: 20 } }, { ...params, page: 2 })).toEqual({ rows: [], count: 20 })
})
