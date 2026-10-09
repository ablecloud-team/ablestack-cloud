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

import fs from 'fs'
import path from 'path'
import { shallowMount } from '@vue/test-utils'
import Widget from '@/views/storage/StorageOperationHistory'
import { getAPI, postAPI } from '@/api'
jest.mock('@/api', () => ({ getAPI: jest.fn(), postAPI: jest.fn() }))

describe('Configuration operation recovery history', () => {
  beforeEach(() => { getAPI.mockReset(); postAPI.mockReset() })
  it.each([
    ['ko_KR', ['보존된 원본 설정', '후속 정상 리비전', 'DATA는 포맷하지 않습니다']],
    ['en', ['preserved source configuration', 'later verified revision', 'DATA volumes are not formatted']]
  ])('shows both recovery outcomes in the actual %s confirmation dialog', async (locale, requiredInformation) => {
    const messages = JSON.parse(fs.readFileSync(path.resolve(__dirname, '../../../../public/locales', locale + '.json'), 'utf8'))
    getAPI.mockResolvedValue({ liststorageserviceoperationsresponse: { storageserviceoperation: [] } })
    const wrapper = shallowMount(Widget, {
      props: { instanceId: 'a' },
      global: {
        mocks: { $t: key => messages[key] || key, $store: { getters: { apis: { reconcileStorageServiceOperation: {} } } } },
        stubs: {
          'a-modal': { props: ['visible'], template: '<div v-if="visible" class="recovery-dialog"><slot /></div>' },
          'a-alert': { props: ['message'], template: '<p>{{ message }}</p>' }
        }
      }
    })
    await wrapper.setData({ reconcileTarget: { id: 'failed', state: 'RECOVERY_REQUIRED', diagnostic: 'original cause' } })
    const explanation = wrapper.find('.recovery-dialog').text()
    requiredInformation.forEach(information => expect(explanation).toContain(information))
    expect(explanation).toContain('original cause')
    expect(postAPI).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it.each(['ROLLED_BACK', 'RECONCILED_SUPERSEDED'])('lets the server resolve %s without client recovery flags', async state => {
    const verified = { id: 'failed', state, diagnostic: 'original cause' }
    postAPI.mockResolvedValue({ reconcilestorageserviceoperationresponse: { jobid: 'job' } })
    getAPI.mockImplementation(command => Promise.resolve(command === 'queryAsyncJobResult'
      ? { queryasyncjobresultresponse: { jobstatus: 1, jobresult: { storageserviceoperation: verified } } }
      : { liststorageserviceoperationsresponse: { storageserviceoperation: [verified] } }))
    const vm = { canReconcile: true, instanceId: 'a', reconcileTarget: { id: 'failed', state: 'RECOVERY_REQUIRED' }, saving: '', generation: 0, rows: [] }
    vm.refresh = jest.fn(() => Widget.methods.refresh.call(vm))
    await Widget.methods.reconcile.call(vm)
    expect(postAPI).toHaveBeenCalledTimes(1)
    expect(postAPI).toHaveBeenCalledWith('reconcileStorageServiceOperation', { operationid: 'failed' })
    expect(vm.rows).toEqual([verified])
    expect(vm.refresh).toHaveBeenCalledTimes(1)
    expect(vm.saving).toBe('')
  })

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
