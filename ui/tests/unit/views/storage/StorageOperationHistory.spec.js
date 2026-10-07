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

import Widget from '@/views/storage/StorageOperationHistory'
import { getAPI, postAPI } from '@/api'
jest.mock('@/api', () => ({ getAPI: jest.fn(), postAPI: jest.fn() }))

describe('Configuration operation recovery history', () => {
  beforeEach(() => { getAPI.mockReset(); postAPI.mockReset() })
  it('preserves known diagnostics when a history refresh fails', async () => {
    const vm = { instanceId: 'a', generation: 0, rows: [{ id: 'failed', diagnostic: 'original cause' }], loading: false, readError: false }
    getAPI.mockRejectedValue(new Error('timeout')); await Widget.methods.refresh.call(vm)
    expect(vm.rows[0].diagnostic).toBe('original cause'); expect(vm.readError).toBe(true)
  })
  it('ignores a late operation history from a different instance', async () => {
    let complete; getAPI.mockImplementation(() => new Promise(resolve => { complete = resolve }))
    const vm = { instanceId: 'a', generation: 0, rows: [{ id: 'current' }] }; const pending = Widget.methods.refresh.call(vm)
    vm.instanceId = 'b'; vm.generation++; complete({ liststorageserviceoperationsresponse: { storageserviceoperation: [{ id: 'old' }] } })
    await pending; expect(vm.rows).toEqual([{ id: 'current' }])
  })
  it('distinguishes recovery required from successfully verified current revision', () => {
    expect(Widget.methods.color('RECOVERY_REQUIRED')).toBe('red'); expect(Widget.methods.color('RECONCILED_SUPERSEDED')).toBe('green')
  })
  it('blocks writes without the reconciliation API permission', async () => {
    const vm = { canReconcile: false, reconcileTarget: { id: 'failed' } }
    await Widget.methods.reconcile.call(vm)
    expect(postAPI).not.toHaveBeenCalled()
    expect(Widget.computed.canReconcile.call({ $store: { getters: { apis: {} } } })).toBe(false)
  })
  it('closes the dialog after accepting the async operation and refreshes only its own instance', async () => {
    postAPI.mockResolvedValue({ reconcilestorageserviceoperationresponse: { jobid: 'job' } })
    const vm = { canReconcile: true, instanceId: 'a', reconcileTarget: { id: 'failed' }, saving: '', refresh: jest.fn() }
    getAPI.mockImplementation(() => {
      expect(vm.reconcileTarget).toBe(null)
      return Promise.resolve({ queryasyncjobresultresponse: { jobstatus: 1 } })
    })
    await Widget.methods.reconcile.call(vm)
    expect(vm.refresh).toHaveBeenCalledTimes(1); expect(vm.saving).toBe('')
  })
  it('does not show a stale async response on a different instance', async () => {
    let complete; postAPI.mockImplementation(() => new Promise(resolve => { complete = resolve }))
    const vm = { canReconcile: true, instanceId: 'a', reconcileTarget: { id: 'failed' }, saving: '', refresh: jest.fn() }
    const pending = Widget.methods.reconcile.call(vm); vm.instanceId = 'b'; vm.saving = ''
    complete({ reconcilestorageserviceoperationresponse: { jobid: 'job' } }); await pending
    expect(getAPI).not.toHaveBeenCalled(); expect(vm.refresh).not.toHaveBeenCalled()
  })
})
