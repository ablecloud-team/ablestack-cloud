// Licensed to the Apache Software Foundation (ASF) under one or more
// contributor license agreements. See the NOTICE file for copyright ownership.
// Licensed under the Apache License, Version 2.0 (the "License"); you may obtain
// a copy at http://www.apache.org/licenses/LICENSE-2.0 . Distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND.
import { snapshotListSize, snapshotListInterval, snapshotSourceVolumeRoute, snapshotListPage, snapshotListColumns, snapshotListSizeValue, loadSnapshotListColumns, saveSnapshotListColumns } from '@/utils/storageSnapshotPresentation'
import ko from '@/../public/locales/ko_KR.json'
const route = { name: 'snapshot', params: {}, query: {} }
test.each([[0, '0 B'], ['0', '0 B'], [1, '1 B'], [1024, '1.00 KiB'], [1373634, '1.31 MiB'], [1073741824, '1.00 GiB'], [1099511627776, '1.00 TiB'], [1125899906842624, '1.00 PiB']])('byte value %p is precise and uses dynamic binary units', (bytes, expected) => expect(snapshotListSize(bytes)).toBe(expected))
test.each([undefined, null, '', ' ', 'bad', '-1', -1, NaN, Infinity, {}, false])('%p is unknown, never a fabricated zero', value => expect(snapshotListSize(value)).toBeNull())
test.each([['MANUAL', '수동'], ['HOURLY', '매시간'], ['DAILY', '매일'], ['WEEKLY', '매주'], ['MONTHLY', '매월'], ['INTERNAL', '내부'], ['other', '미확인'], [undefined, '미확인']])('snapshot interval %p has a Korean label', (value, expected) => expect(ko[snapshotListInterval(value)]).toBe(expected))
test('creation navigation retains only source ownership/Zone and uses the existing volume workflow', () => {
  expect(snapshotSourceVolumeRoute({ query: { projectid: 'project', account: 'owner', domainid: 'domain', zoneid: 'zone', keyword: 'snapshot-name', intervaltype: 'DAILY', 'tags[0].key': 'snap-only', showunique: 'false', page: '9' } })).toEqual({ path: '/volume', query: { projectid: 'project', account: 'owner', domainid: 'domain', zoneid: 'zone' } })
  expect(snapshotSourceVolumeRoute({ query: { projectid: '0' } })).toEqual({ path: '/volume', query: {} })
})
test('an empty response is valid and an out-of-range page can be corrected by the caller', () => {
  expect(snapshotListPage({ listsnapshotsresponse: {} }, route, 1, 20)).toEqual({ rows: [], count: 0 })
  expect(snapshotListPage({ listsnapshotsresponse: { count: 3 } }, route, 2, 20).count).toBe(3)
})
test.each([{ count: 2, snapshot: [{ id: 'a' }] }, { count: 1, snapshot: { id: 'a' } }, { count: -1 }, { count: 2, snapshot: [{ id: 'a' }, { id: 'a' }] }, { count: 1, snapshot: [{}] }])('malformed/partial response cannot become successful data: %p', data => expect(() => snapshotListPage({ listsnapshotsresponse: data }, route, 1, 20)).toThrow('message.snapshot.list.incomplete'))
test('copy rows keep separate identities and require a real reference key', () => {
  const copy = { ...route, query: { showunique: 'false' } }
  expect(snapshotListPage({ listsnapshotsresponse: { count: 2, snapshot: [{ id: 's', copy: { id: 'p' } }, { id: 's', copy: { id: 'i' } }] } }, copy, 1, 20).rows).toHaveLength(2)
  expect(() => snapshotListPage({ listsnapshotsresponse: { count: 1, snapshot: [{ id: 's' }] } }, copy, 1, 20)).toThrow()
})

test('old default columns gain logical size once; other user selections remain unchanged', () => {
  const available = ['name', 'physicalsize', 'virtualsize', 'created']
  expect(snapshotListColumns(['name', 'physicalsize', 'created'], available)).toEqual(available)
  const custom = ['name', 'created']; expect(snapshotListColumns(custom, available)).toBe(custom)
  expect(snapshotListColumns(available, available)).toBe(available)
})

test('missing per-copy metric is unknown even when the legacy top-level response defaults to zero', () => {
  const row = { physicalsize: 0, virtualsize: 0, copy: { virtualsize: 1073741824 } }
  expect(snapshotListSize(snapshotListSizeValue(row, 'physicalsize'))).toBeNull()
  expect(snapshotListSize(snapshotListSizeValue(row, 'virtualsize'))).toBe('1.00 GiB')
  expect(snapshotListSizeValue({ physicalsize: 0 }, 'physicalsize')).toBe(0)
})

test('explicit hidden columns survive a stale common-column cache and are scoped by user and copy mode', () => {
  const data = new Map(); const storage = { get: key => data.get(key), set: (key, value) => data.set(key, JSON.parse(JSON.stringify(value))) }
  const available = ['name', 'physicalsize', 'virtualsize']
  expect(loadSnapshotListColumns(storage, 'a', false, ['name', 'physicalsize'], available)).toEqual(available)
  saveSnapshotListColumns(storage, 'a', false, ['name', 'physicalsize'])
  expect(loadSnapshotListColumns(storage, 'a', false, available, available)).toEqual(['name', 'physicalsize'])
  saveSnapshotListColumns(storage, 'a', true, ['name']); expect(loadSnapshotListColumns(storage, 'a', false, available, available)).toEqual(['name', 'physicalsize'])
  expect(loadSnapshotListColumns(storage, 'a', true, available, available)).toEqual(['name'])
  expect(loadSnapshotListColumns(storage, 'b', false, available, available)).toEqual(available)
})
test('an explicit empty selection remains empty and old migration booleans are upgraded safely', () => {
  const data = new Map([['SNAPSHOT_LIST_COLUMNS_V1:a', true]]); const storage = { get: key => data.get(key), set: (key, value) => data.set(key, value) }
  expect(loadSnapshotListColumns(storage, 'a', false, ['name'], ['name', 'virtualsize'])).toEqual(['name', 'virtualsize'])
  saveSnapshotListColumns(storage, 'a', false, []); expect(loadSnapshotListColumns(storage, 'a', false, ['name'], ['name', 'virtualsize'])).toEqual([])
})
