// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements. See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership. The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License. You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied. See the License for the
// specific language governing permissions and limitations
// under the License.

import AutogenView from '@/views/AutogenView'
import eventBus from '@/config/eventBus'
jest.mock('vue-code-highlight/src/CodeHighlight.vue', () => ({}), { virtual: true })
jest.mock('vue-code-highlight/themes/prism-okaidia.css', () => ({}), { virtual: true })
jest.mock('@/api', () => ({ getAPI: jest.fn(), postAPI: jest.fn() }))
jest.mock('@/vue-app', () => ({ vueProps: {} }))
jest.mock('@/config/eventBus', () => ({ emit: jest.fn(), on: jest.fn(), off: jest.fn() }))

describe('Bulk action lifecycle', () => {
  beforeEach(() => jest.clearAllMocks())
  it('preserves operation statuses when list refresh reconnects selected rows', () => {
    const selectedItems = [{ id: 'a', status: 'success', jobid: 'job' }]
    const vm = { showGroupActionModal: true, selectedItems, selectedRowKeys: ['a'], items: [{ id: 'a', status: 'Started' }] }
    AutogenView.methods.onRowSelectionChange.call(vm, ['a'])
    expect(vm.selectedItems).toBe(selectedItems)
    expect(vm.selectedItems[0].status).toBe('success')
  })
  it('ignores a previous batch completion after another batch has started', async () => {
    const old = [{ id: 'a', status: 'InProgress' }]
    let callbacks
    const vm = {
      selectedItems: old,
      showGroupActionModal: true,
      $t: k => k,
      shouldNavigateBack: () => false,
      $pollJob: options => { callbacks = options }
    }
    const pending = AutogenView.methods.pollActionCompletion.call(vm, 'job', { api: 'startVirtualMachine', label: 'start' }, 'a', 'a')
    vm.selectedItems = [{ id: 'a', status: 'InProgress' }]
    callbacks.successMethod({ jobresult: {} })
    await pending
    expect(eventBus.emit).not.toHaveBeenCalledWith('update-resource-state', expect.anything())
  })
  it('tracks Commvault restore without a long lived loading message', async () => {
    let pollOptions
    const vm = {
      resource: { id: 'backup', provider: 'ablestack-commvault' },
      selectedItems: [],
      $t: key => key,
      $pollJob: options => { pollOptions = options; options.successMethod({ jobresult: {} }) },
      fetchData: jest.fn().mockResolvedValue(),
      markBackupRestoreStarted: jest.fn(),
      shouldNavigateBack: () => false
    }

    await AutogenView.methods.pollActionCompletion.call(vm, 'job', { api: 'restoreBackup', label: 'label.backup.restore' }, '', 'backup')
    expect(pollOptions.showLoading).toBe(false)
    expect(pollOptions.showSuccessMessage).toBe(false)
    expect(vm.markBackupRestoreStarted).toHaveBeenCalledWith(vm.resource)
  })
  it('marks a restore request on restore fields', () => {
    const backup = { id: 'backup', status: 'BackedUp' }
    const vm = { items: [backup] }

    AutogenView.methods.markBackupRestoreStarted.call(vm, backup)

    expect(backup).toEqual(expect.objectContaining({ restoreoperationpending: true, restorejobstate: 'STARTING', restorejobstep: 'REQUESTED' }))
    expect(backup.backupjobstep).toBeUndefined()
  })
  it('acknowledges a Commvault restore as soon as the API returns a job id', async () => {
    const backup = { id: 'backup', provider: 'ablestack-commvault' }
    const vm = {
      resource: backup,
      selectedItems: [],
      $t: key => key,
      $message: { info: jest.fn() },
      markBackupRestoreStarted: jest.fn(),
      pollActionCompletion: jest.fn().mockResolvedValue(true)
    }

    await AutogenView.methods.handleResponse.call(vm, { restorebackupresponse: { jobid: 'job' } }, '', 'backup', { api: 'restoreBackup' })

    expect(vm.$message.info).toHaveBeenCalledWith(expect.objectContaining({ content: 'label.backup.restore.requested' }))
    expect(vm.pollActionCompletion).toHaveBeenCalledWith('job', { api: 'restoreBackup' }, '', 'backup', true, vm.selectedItems)
  })
})
