// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements. See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership. The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License. You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied. See the License for the
// specific language governing permissions and limitations
// under the License.
import { loadVmSnapshotRelations, snapshotRelationTree, freshSnapshotContext, runSnapshotDeleteBatch } from '@/utils/vmSnapshotList'

const row = (id, vm = 'v1', parent) => ({ id: String(id), virtualmachineid: vm, displayname: 'same-name', parent, state: 'Ready' })
const response = (rows, count = rows.length) => ({ listvmsnapshotresponse: { vmSnapshot: rows, count } })

test('relations fetch every VM page without carrying the filtered list query', async () => {
  const rows = Array.from({ length: 225 }, (_, i) => row(i))
  const get = jest.fn((api, args) => Promise.resolve(response(rows.slice((args.page - 1) * 100, args.page * 100), rows.length)))
  const result = await loadVmSnapshotRelations(get, 'v1')
  expect(result.rows).toHaveLength(225)
  expect(result.partial).toBe(false)
  expect(get).toHaveBeenCalledTimes(3)
  expect(get.mock.calls.every(call => !('keyword' in call[1]) && !('state' in call[1]))).toBe(true)
  const partial = await loadVmSnapshotRelations(get, 'v1', 130)
  expect(partial.rows).toHaveLength(130)
  expect(partial.partial).toBe(true)
})

test('repeated pages stop with an explicit partial result', async () => {
  const get = jest.fn().mockResolvedValue(response([row(1)], 25))
  const result = await loadVmSnapshotRelations(get, 'v1')
  expect(result.partial).toBe(true)
  expect(get).toHaveBeenCalledTimes(2)
})

test('parent IDs survive duplicate names and missing/cyclic parents are never invented', () => {
  const rows = [row('root'), row('child', 'v1', 'root'), row('missing', 'v1', 'gone'), row('a', 'v1', 'b'), row('b', 'v1', 'a')]
  const tree = snapshotRelationTree(rows)
  expect(tree.roots.find(node => node.key === 'root').children[0].key).toBe('child')
  expect(tree.warnings).toEqual([{ id: 'missing', reason: 'missing' }, { id: 'a', reason: 'cycle' }, { id: 'b', reason: 'cycle' }])
  expect(tree.roots.filter(node => ['a', 'b', 'missing'].includes(node.key)).every(node => !node.children.length)).toBe(true)
})

test('fresh checks detect withdrawn ACL rows and other operations with a fixed number of batched queries', async () => {
  const get = jest.fn((api, args) => Promise.resolve(api === 'listVirtualMachines' ? { listvirtualmachinesresponse: { virtualmachine: [{ id: 'v1', state: 'Running' }] } } : args.state ? response(args.state === 'Reverting' ? [row('other')] : []) : response([row('one')])))
  const contexts = await freshSnapshotContext(get, [row('one'), row('removed', 'v2')])
  expect(contexts[0].busy).toBe(true)
  expect(contexts[1].snapshot).toBeUndefined()
  expect(contexts[1].vm).toBeUndefined()
  expect(get).toHaveBeenCalledTimes(6)
})

test('same VM delete failure stops its remaining requests and other VM completes', async () => {
  const targets = [row('a'), row('b'), row('c'), row('x', 'v2')]
  const execute = jest.fn(target => Promise.resolve({ jobstatus: target.id === 'b' ? 2 : 1 }))
  const results = await runSnapshotDeleteBatch(targets, execute)
  expect(execute.mock.calls.map(call => call[0].id)).not.toContain('c')
  expect(results.map(result => result.outcome)).toEqual(['success', 'failed', 'notrun', 'success'])
})

test('unknown results stop the VM chain and cross VM concurrency is bounded', async () => {
  let concurrent = 0
  let max = 0
  const targets = [row('a'), row('b'), row('x', 'v2'), row('y', 'v3')]
  const results = await runSnapshotDeleteBatch(targets, async target => {
    concurrent++; max = Math.max(max, concurrent)
    await Promise.resolve(); concurrent--
    return { jobstatus: target.id === 'a' ? null : 1, trackingStatus: target.id === 'a' ? 'unknown' : undefined }
  })
  expect(max).toBeLessThanOrEqual(2)
  expect(results.map(result => result.outcome)).toEqual(['unknown', 'notrun', 'success', 'success'])
})

test('purged parents are explicit roots with a missing warning', () => {
  const tree = snapshotRelationTree([{ ...row('orphan'), parentmissing: true }])
  expect(tree.warnings).toEqual([{ id: 'orphan', reason: 'missing' }])
  expect(tree.roots[0].key).toBe('orphan')
})

test('concurrent page count changes never claim a complete relationship', async () => {
  const get = jest.fn((api, args) => Promise.resolve(response(args.page === 1 ? Array.from({ length: 100 }, (_, i) => row(i)) : [row(100)], args.page === 1 ? 102 : 101)))
  const result = await loadVmSnapshotRelations(get, 'v1')
  expect(result.partial).toBe(true)
})

test('fresh action queries obey the API page and pagesize contract', async () => {
  const get = jest.fn(async (command, params) => {
    if (params.pagesize && !params.page) throw new Error('page is required with pagesize')
    if (params.pagesize > 100) throw new Error('action queries exceed the safe page size')
    if (command === 'listVirtualMachines') return { listvirtualmachinesresponse: { virtualmachine: [{ id: 'v1', state: 'Stopped' }] } }
    if (command === 'listBackups') return { listbackupsresponse: { count: 0, backup: [] } }
    return { listvmsnapshotresponse: { count: params.vmsnapshotids ? 1 : 0, vmSnapshot: params.vmsnapshotids ? [row('a')] : [] } }
  })
  const [context] = await freshSnapshotContext(get, [row('a')], true)
  expect(context.snapshot.id).toBe('a')
  expect(context.vm.id).toBe('v1')
  expect(context.busy).toBe(false)
})
