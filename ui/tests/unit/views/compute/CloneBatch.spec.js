// Licensed to the Apache Software Foundation (ASF) under one or more
// contributor license agreements. See the NOTICE file distributed with this
// work for additional information regarding copyright ownership. The ASF
// licenses this file to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance with the License.
// You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0 .
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
// WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
// License for the specific language governing permissions and limitations.
import Clone from '@/views/compute/CloneVM'
import { cloneAttemptParams, cloneBatchBusy, newCloneRequest, retryCloneRequest, readCloneRequest, batchStorageKey } from '@/utils/cloneBatch'
import { getAPI, postAPI } from '@/api'
jest.mock('@/api', () => ({ getAPI: jest.fn(), postAPI: jest.fn() }))
const params = { virtualmachineid: 'source', name: 'batch', count: 2, startvm: false, clonetype: 'full' }
beforeEach(() => { getAPI.mockReset(); postAPI.mockReset(); window.localStorage.clear() })
test('response loss resubmits exactly the persisted identifier and original inputs', () => {
  const request = newCloneRequest(params)
  const storage = window.localStorage
  const key = batchStorageKey('user', 'source')
  storage.setItem(key, JSON.stringify(request))
  expect(cloneAttemptParams(readCloneRequest(storage, key, 'source'))).toEqual(cloneAttemptParams(request))
  expect(readCloneRequest(storage, key, 'another-source')).toBeNull()
})
test('retry includes only failed eligible targets and keeps count and original suffix indices', () => {
  const request = newCloneRequest(params)
  const retry = retryCloneRequest(request, { targets: [{ index: 1, retryaction: 'NONE' }, { index: 2, retryaction: 'START' }] })
  expect(cloneAttemptParams(retry)).toMatchObject({ ...params, batchid: request.id, retryindices: '2' })
  expect(cloneAttemptParams(retry).customjobid).not.toBe(request.id)
})
test('active owner job prevents retry even when last checkpoint says partial', () => {
  expect(cloneBatchBusy({ status: 'PARTIAL', jobstatus: 0 })).toBe(true)
  expect(cloneBatchBusy({ status: 'INTERRUPTED', jobstatus: 2 })).toBe(false)
})
test('corrupt or excessive persisted input is not restored', () => {
  const request = newCloneRequest({ ...params, count: 51 })
  window.localStorage.setItem('key', JSON.stringify(request))
  expect(readCloneRequest(window.localStorage, 'key', 'source')).toBeNull()
})
test('unacknowledged retry cannot be replaced with a new request', () => {
  const request = newCloneRequest(params)
  const vm = { batchRequest: { ...request, attempt: { id: 'new-attempt' } }, batchResult: { attemptid: request.id, status: 'PARTIAL' } }
  expect(Clone.computed.batchUnconfirmed.call(vm)).toBe(true)
})
test('duplicate submit while API pending is suppressed', async () => {
  const vm = { batchRequest: newCloneRequest(params), loading: true }
  await Clone.methods.submitBatch.call(vm)
  expect(getAPI).not.toHaveBeenCalled()
})

test('late lookup cannot populate another request with the previous targets', async () => {
  let finish
  getAPI.mockReturnValue(new Promise(resolve => { finish = resolve }))
  const vm = { batchRequest: newCloneRequest(params), batchResult: null, queryingBatch: false, batchDisposed: false }
  const pending = Clone.methods.refreshBatch.call(vm)
  vm.batchRequest = newCloneRequest(params)
  finish({ queryvmclonebatchresponse: { clonebatch: { status: 'SUCCEEDED', targets: [{ id: 'previous-vm' }] } } })
  await pending
  expect(vm.batchResult).toBeNull()
  expect(vm.queryingBatch).toBe(false)
})

test('new request waits for the current lookup to settle', () => {
  const request = newCloneRequest(params)
  const vm = { batchRequest: request, batchBusy: false, batchUnconfirmed: false, queryingBatch: true, loading: false, initForm: jest.fn() }
  Clone.methods.newBatch.call(vm)
  expect(vm.batchRequest).toBe(request)
  expect(vm.initForm).not.toHaveBeenCalled()
})

test('transport failure preserves displayed targets and lets the view own recovery', async () => {
  getAPI.mockRejectedValue(new Error('connection closed'))
  const result = { status: 'PARTIAL', targets: [{ id: 'preserved-vm' }] }
  const vm = { batchRequest: newCloneRequest(params), batchResult: result, queryingBatch: false, batchDisposed: false, $t: key => key }
  await Clone.methods.refreshBatch.call(vm)
  expect(getAPI).toHaveBeenCalledWith('queryVMCloneBatch', { requestid: vm.batchRequest.id }, { preserveOnFailure: true })
  expect(vm.batchResult).toBe(result)
  expect(vm.batchError).toBe('message.clone.batch.query.failed')
  expect(vm.queryingBatch).toBe(false)
})

test('lost submission response keeps its receipt and queries the accepted batch', async () => {
  getAPI.mockRejectedValue(new Error('connection closed'))
  const request = newCloneRequest(params)
  const vm = { batchRequest: request, loading: false, $notifyError: jest.fn(), refreshBatch: jest.fn().mockResolvedValue({ status: 'RUNNING' }), batchUnconfirmed: false }
  await Clone.methods.submitBatch.call(vm)
  expect(getAPI).toHaveBeenCalledWith('cloneVirtualMachine', cloneAttemptParams(request), { preserveOnFailure: true })
  expect(vm.batchRequest).toBe(request)
  expect(vm.refreshBatch).toHaveBeenCalledTimes(1)
  expect(vm.loading).toBe(false)
})

test('an active cleanup job blocks clone retry after the original job failed', () => {
  expect(cloneBatchBusy({ status: 'PARTIAL', jobstatus: 2, cleanupjobstatus: 0 })).toBe(true)
  expect(cloneBatchBusy({ status: 'PARTIAL', jobstatus: 2, cleanupjobstatus: 1 })).toBe(false)
})

test('cleanup submits only the existing batch identifier and never creates another clone', async () => {
  postAPI.mockResolvedValue({ cleanupvmclonebatchresponse: { jobid: 'cleanup-job' } })
  const request = newCloneRequest(params)
  const vm = { batchRequest: request, batchResult: { cleanupavailable: true }, loading: false, queryingBatch: false, batchBusy: false, refreshBatch: jest.fn(), $notifyError: jest.fn() }
  await Clone.methods.cleanupBatch.call(vm)
  expect(postAPI).toHaveBeenCalledWith('cleanupVMCloneBatch', { requestid: request.id }, { preserveOnFailure: true })
  expect(getAPI).not.toHaveBeenCalled()
  expect(vm.batchRequest).toBe(request)
  expect(vm.cleanupJob).toBe('cleanup-job')
  expect(vm.refreshBatch).toHaveBeenCalledTimes(1)
})

test('cleanup transport failure retains targets and their original request', async () => {
  postAPI.mockRejectedValue(new Error('connection closed'))
  const request = newCloneRequest(params)
  const result = { cleanupavailable: true, targets: [{ id: 'first-success' }] }
  const vm = { batchRequest: request, batchResult: result, loading: false, queryingBatch: false, batchBusy: false, refreshBatch: jest.fn(), $notifyError: jest.fn() }
  await Clone.methods.cleanupBatch.call(vm)
  expect(vm.batchRequest).toBe(request)
  expect(vm.batchResult).toBe(result)
  expect(vm.$notifyError).toHaveBeenCalledTimes(1)
  expect(vm.loading).toBe(false)
})
