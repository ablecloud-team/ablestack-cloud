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
import { getAPI } from '@/api'
jest.mock('@/api', () => ({ getAPI: jest.fn() }))
const params = { virtualmachineid: 'source', name: 'batch', count: 2, startvm: false, clonetype: 'full' }
beforeEach(() => { getAPI.mockReset(); window.localStorage.clear() })
test('response loss resubmits exactly the persisted identifier and original inputs', () => {
  const request = newCloneRequest(params)
  const storage = window.localStorage, key = batchStorageKey('user', 'source')
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
