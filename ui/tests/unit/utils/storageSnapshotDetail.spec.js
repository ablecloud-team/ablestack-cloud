// Licensed to the Apache Software Foundation (ASF) under one or more
// contributor license agreements. See the NOTICE file for copyright ownership.
// Licensed under the Apache License, Version 2.0 (the "License"); you may obtain
// a copy at http://www.apache.org/licenses/LICENSE-2.0 . Distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND.
import { readSnapshotDetail, snapshotDetailParams, snapshotDetailRoute, snapshotDetailRevertReason, snapshotDetailStorePath } from '@/utils/storageSnapshotDetail'
const id = '11111111-1111-4111-8111-111111111111'; const vid = '22222222-2222-4222-8222-222222222222'; const mid = '33333333-3333-4333-8333-333333333333'; const pid = '44444444-4444-4444-8444-444444444444'
const record = { id, volumeid: vid, name: 'snapshot', account: 'owner', domainid: pid, state: 'BackedUp', revertable: true, virtualsize: 1073741824, physicalsize: 196624, snapshotcontext: { version: 1, sourceavailable: true, volumeid: vid, volumestate: 'Ready', revertreason: '' } }
const volume = { id: vid, name: 'source', state: 'Ready', virtualmachineid: mid }
const vm = { id: mid, name: 'vm', state: 'Stopped' }
const copy = i => ({ ...record, copy: { id: 'copy-' + i, role: 'Image', state: 'Ready', availability: 'Ready', datastoreknown: true } })
const reply = (cmd, key, rows, count = rows.length) => ({ [cmd.toLowerCase() + 'response']: { count, [key]: rows } })
let request; let getters
beforeEach(() => {
  getters = { apis: { listSnapshots: {}, listVolumes: {}, listVirtualMachines: {} }, features: {} }
  request = jest.fn(async (cmd, args) => cmd === 'listSnapshots' ? reply(cmd, 'snapshot', args.showunique ? [record] : [copy(1)]) : cmd === 'listVolumes' ? reply(cmd, 'volume', [volume]) : reply(cmd, 'virtualmachine', [vm]))
})
test('logical identity, source and per-copy data are read atomically with bounded preserved requests', async () => {
  const json = await readSnapshotDetail(request, snapshotDetailParams({ params: { id }, query: {} }), getters)
  expect(json.listsnapshotsresponse.snapshot[0].snapshotdetail).toEqual({ volume, vm, parent: null, copies: [copy(1)] })
  expect(request.mock.calls).toHaveLength(4)
  expect(request.mock.calls.every(call => call[2].preserveOnFailure && call[2].timeout === 20000)).toBe(true)
  expect(request.mock.calls[0][1]).toMatchObject({ id, showunique: true, includesnapshotcontext: true })
})
test('detail query ignores list/copy filters and retains actual project scope', () => {
  expect(snapshotDetailParams({ params: { id }, query: { projectid: pid, tab: 'zones', showunique: 'false', zoneid: mid, keyword: 'other' } }, {})).toEqual({ id, projectid: pid, listall: true, showunique: true, includesnapshotcontext: true, page: 1, pagesize: 100 })
  expect(() => snapshotDetailParams({ params: { id: 'bad' }, query: {} })).toThrow('message.snapshot.detail.missing')
  expect(() => snapshotDetailParams({ params: { id }, query: { projectid: 'bad' } })).toThrow()
})
test.each([null, {}, { ...record, id: mid }, { ...record, snapshotcontext: {} }])('missing or mismatched identity/context never becomes a successful card: %p', async row => {
  request.mockResolvedValueOnce(reply('listSnapshots', 'snapshot', row ? [row] : []))
  await expect(readSnapshotDetail(request, { id }, getters)).rejects.toThrow()
})
test('all copy pages are read, with stable totals and no duplicate identities', async () => {
  request.mockImplementation(async (cmd, args) => cmd === 'listSnapshots' ? reply(cmd, 'snapshot', args.showunique ? [record] : Array.from({ length: args.page === 1 ? 100 : 1 }, (_, i) => copy((args.page - 1) * 100 + i)), args.showunique ? 1 : 101) : cmd === 'listVolumes' ? reply(cmd, 'volume', [volume]) : reply(cmd, 'virtualmachine', [vm]))
  const out = await readSnapshotDetail(request, { id }, getters); expect(out.listsnapshotsresponse.snapshot[0].snapshotdetail.copies).toHaveLength(101)
})
test.each(['short', 'duplicate', 'other'])('malformed copy response is rejected: %s', async kind => {
  request.mockImplementationOnce(async () => reply('listSnapshots', 'snapshot', [record])).mockImplementationOnce(async () => reply('listSnapshots', 'snapshot', kind === 'other' ? [{ ...copy(1), id: mid }] : kind === 'duplicate' ? [copy(1), copy(1)] : [copy(1)], 2))
  await expect(readSnapshotDetail(request, { id }, getters)).rejects.toThrow()
})
test('related lookups respect API permissions and never generate inaccessible links', async () => {
  getters.apis = { listSnapshots: {} }; const json = await readSnapshotDetail(request, { id }, getters)
  expect(request).toHaveBeenCalledTimes(2); expect(json.listsnapshotsresponse.snapshot[0].snapshotdetail.volume).toBeNull()
  expect(snapshotDetailStorePath({ datastoreid: pid, copy: { role: 'Primary' } }, getters.apis)).toBeNull()
})
test('removed source remains a readable backed-up snapshot without a dead source link', async () => {
  request.mockImplementationOnce(async () => reply('listSnapshots', 'snapshot', [{ ...record, snapshotcontext: { ...record.snapshotcontext, sourceavailable: false } }]))
  const row = (await readSnapshotDetail(request, { id }, getters)).listsnapshotsresponse.snapshot[0]
  expect(row.state).toBe('BackedUp'); expect(row.snapshotdetail.volume).toBeNull(); expect(request).toHaveBeenCalledTimes(2)
  expect(snapshotDetailRevertReason(row)).toBe('SOURCE_VOLUME_UNAVAILABLE')
})
test('related 403 becomes unavailable; transient errors fail the atomic refresh', async () => {
  const base = request.getMockImplementation(); request.mockImplementation((cmd, args, opts) => cmd === 'listVolumes' ? Promise.reject(Object.assign(new Error('forbidden'), { response: { status: 403 } })) : base(cmd, args, opts))
  const row = (await readSnapshotDetail(request, { id }, getters)).listsnapshotsresponse.snapshot[0]; expect(row.snapshotdetail.volume).toBeNull()
  request.mockImplementation((cmd, args, opts) => cmd === 'listVolumes' ? Promise.reject(Object.assign(new Error('unavailable'), { response: { status: 503 } })) : base(cmd, args, opts))
  await expect(readSnapshotDetail(request, { id }, getters)).rejects.toMatchObject({ response: { status: 503 } })
})
test('parent fields are feature gated and parent identity/ownership are validated', async () => {
  const parent = { ...record, id: pid, name: 'parent' }; const base = request.getMockImplementation()
  request.mockImplementation((cmd, args, opts) => cmd === 'listSnapshots' && args.showunique ? Promise.resolve(reply(cmd, 'snapshot', args.id === pid ? [parent] : [{ ...record, parent: pid }])) : base(cmd, args, opts))
  let row = (await readSnapshotDetail(request, { id }, getters)).listsnapshotsresponse.snapshot[0]; expect(row.snapshotdetail.parent).toBeNull()
  getters.features.snapshotshowchainsize = true; row = (await readSnapshotDetail(request, { id }, getters)).listsnapshotsresponse.snapshot[0]; expect(row.snapshotdetail.parent.id).toBe(pid)
  parent.account = 'foreign'; await expect(readSnapshotDetail(request, { id }, getters)).rejects.toThrow()
})
test('changing scope prevents late related responses from becoming usable data', async () => {
  let current = true; const base = request.getMockImplementation(); request.mockImplementation(async (...args) => { const out = await base(...args); if (args[0] === 'listVolumes') current = false; return out })
  await expect(readSnapshotDetail(request, { id }, getters, () => current)).rejects.toThrow('message.snapshot.action.context.changed')
})
test.each([['Ready', 'Stopped', ''], ['Ready', 'Running', 'VM_MUST_BE_STOPPED'], ['Allocated', 'Stopped', 'VOLUME_NOT_READY']])('current source %s and VM %s qualify revert conservatively', (state, vmstate, expected) => {
  expect(snapshotDetailRevertReason({ ...record, snapshotdetail: { volume: { ...volume, state }, vm: { ...vm, state: vmstate } } })).toBe(expected)
})

test('actual generated unnamed detail routes use snapshot metadata', () => { expect(snapshotDetailRoute({ params: { id }, meta: { name: 'snapshot' } })).toBe(true); expect(snapshotDetailRoute({ params: { id }, meta: { name: 'volume' } })).toBe(false) })
