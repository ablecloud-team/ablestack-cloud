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

import { shallowMount } from '@vue/test-utils'
import { getAPI } from '@/api'
import BackupProgress from '@/components/view/BackupProgress.vue'

jest.mock('@/api', () => ({ getAPI: jest.fn() }))

beforeEach(() => {
  jest.useFakeTimers()
  jest.clearAllMocks()
})

afterEach(() => jest.useRealTimers())

test('shows the Commvault transfer phase after the host export finishes', async () => {
  getAPI.mockResolvedValue({
    getbackupjobstatusresponse: {
      status: 'BackingUp', state: 'RUNNING', step: 'COMMVAULT_TRANSFER'
    }
  })
  const wrapper = shallowMount(BackupProgress, {
    props: { record: { id: 'backup-1', status: 'BackingUp', backupjobstate: 'COMPLETED', backupjobprogress: 100 } },
    global: {
      mocks: {
        $t: key => key,
        $store: { getters: { apis: { getBackupJobStatus: {} } } }
      }
    }
  })

  expect(wrapper.vm.displayStep).toBe('FINALIZING')
  for (let i = 0; i < 5; i++) await Promise.resolve()
  expect(getAPI).toHaveBeenCalledWith('getBackupJobStatus', { id: 'backup-1', limit: 5 })
  expect(wrapper.vm.displayStep).toBe('COMMVAULT_TRANSFER')
  expect(wrapper.vm.jobState).toBe('RUNNING')
  expect(wrapper.vm.showProgress).toBe(false)
  wrapper.unmount()
})

test('shows finalizing when an incremental RBD export finished without a progress value', () => {
  const wrapper = shallowMount(BackupProgress, {
    props: { record: { id: 'backup-1', status: 'BackingUp', backupengine: 'RBD_DIFF', backupjobstate: 'COMPLETED', backupjobstep: 'COMPLETED' } },
    global: {
      mocks: {
        $t: key => key,
        $store: { getters: { apis: {} } }
      }
    }
  })

  expect(wrapper.vm.progress).toBeNull()
  expect(wrapper.vm.jobState).toBe('COMPLETED')
  expect(wrapper.vm.displayStep).toBe('FINALIZING')
  wrapper.unmount()
})

test('Commvault host capability enables live bandwidth only until transfer starts', async () => {
  getAPI
    .mockResolvedValueOnce({ getbackupjobstatusresponse: { status: 'BackingUp', state: 'RUNNING', step: 'RUNNING', capabilities: 'cancel,live-bandwidth' } })
    .mockResolvedValueOnce({ getbackupjobstatusresponse: { status: 'BackingUp', state: 'RUNNING', step: 'COMMVAULT_TRANSFER', capabilities: '' } })
  const wrapper = shallowMount(BackupProgress, {
    props: { record: { id: 'backup-1', status: 'BackingUp', provider: 'ablestack-commvault' } },
    global: {
      mocks: {
        $t: key => key,
        $store: { getters: { apis: { getBackupJobStatus: {} } } }
      }
    }
  })

  for (let i = 0; i < 5; i++) await Promise.resolve()
  expect(wrapper.emitted('capabilities-change')?.[0]).toEqual(['cancel,live-bandwidth'])
  wrapper.vm.fetchStatus()
  for (let i = 0; i < 5; i++) await Promise.resolve()
  expect(wrapper.emitted('capabilities-change')?.[1]).toEqual([''])
  wrapper.unmount()
})

test('shows the QCOW2 backup step separately from the running state', async () => {
  getAPI.mockResolvedValue({ getbackupjobstatusresponse: { status: 'BackingUp', state: 'RUNNING', step: 'QCOW2_BACKUP', progress: 16 } })
  const wrapper = shallowMount(BackupProgress, {
    props: { record: { id: 'backup-1', status: 'BackingUp', backupengine: 'QCOW2' } },
    global: {
      mocks: {
        $t: key => key,
        $store: { getters: { apis: { getBackupJobStatus: {} } } }
      }
    }
  })

  for (let i = 0; i < 5; i++) await Promise.resolve()
  expect(wrapper.vm.jobState).toBe('RUNNING')
  expect(wrapper.vm.displayStep).toBe('QCOW2_BACKUP')
  expect(wrapper.vm.progress).toBe(16)
  wrapper.unmount()
})

test('shows restore step codes without translating them', () => {
  const wrapper = shallowMount(BackupProgress, {
    props: { record: { id: 'backup-1', status: 'Restoring', restorejobstate: 'RUNNING', restorejobstep: 'PREPARE_SOURCE' } },
    global: {
      mocks: {
        $t: key => key,
        $store: { getters: { apis: {} } }
      }
    }
  })

  expect(wrapper.vm.displayStep).toBe('PREPARE_SOURCE')
  wrapper.unmount()
})

test('shows Commvault restore before host data restore begins', async () => {
  getAPI
    .mockResolvedValueOnce({ getbackuprestorejobstatusresponse: { status: 'BackedUp', state: 'RUNNING', step: 'COMMVAULT_RESTORE' } })
    .mockResolvedValueOnce({ getbackuprestorejobstatusresponse: { status: 'BackedUp', state: 'RUNNING', step: 'RESTORE_DATA' } })
  const wrapper = shallowMount(BackupProgress, {
    props: { record: { id: 'backup-1', status: 'BackedUp', provider: 'ablestack-commvault', restoreoperationpending: true, restorejobstate: 'STARTING' } },
    global: {
      mocks: {
        $t: key => key,
        $store: { getters: { apis: { getBackupRestoreJobStatus: {} } } }
      }
    }
  })

  for (let i = 0; i < 5; i++) await Promise.resolve()
  expect(wrapper.vm.displayStatus).toBe('Restoring')
  expect(wrapper.vm.displayStep).toBe('COMMVAULT_RESTORE')
  wrapper.vm.fetchStatus()
  for (let i = 0; i < 5; i++) await Promise.resolve()
  expect(wrapper.vm.displayStep).toBe('RESTORE_DATA')
  wrapper.unmount()
})

test('cancellation stays pending until termination and cleanup are confirmed', async () => {
  getAPI.mockResolvedValue({
    getbackupjobstatusresponse: { status: 'BackingUp', state: 'CANCEL_PENDING', step: 'CANCEL_PENDING', capabilities: '', details: 'QEMU job still active' }
  })
  const wrapper = shallowMount(BackupProgress, {
    props: { record: { id: 'backup-1', status: 'BackingUp', backupjobprogress: 50 } },
    global: { mocks: { $t: key => key, $store: { getters: { apis: { getBackupJobStatus: {} } } } } }
  })
  for (let i = 0; i < 5; i++) await Promise.resolve()
  expect(wrapper.vm.displayStatus).toBe('label.backup.cancellation.pending')
  expect(wrapper.vm.cancellationDetails).toBe('QEMU job still active')
  expect(wrapper.vm.failureDetails).toBe('')
  expect(wrapper.vm.showProgress).toBe(false)
  expect(wrapper.vm.shouldPoll()).toBeTruthy()
  expect(wrapper.emitted('cancellation-change')?.[0]).toEqual([true])
  wrapper.vm.applyStatus({ status: 'Canceled', state: 'Canceled', capabilities: '' })
  expect(wrapper.vm.displayStatus).toBe('Canceled')
  expect(wrapper.vm.shouldPoll()).toBe(false)
  expect(wrapper.emitted('cancellation-change')?.[1]).toEqual([false])
  wrapper.unmount()
})

test('reloaded cancellation remains pending while cleanup is outstanding', () => {
  const wrapper = shallowMount(BackupProgress, {
    props: { record: { id: 'backup-1', status: 'Canceled', backupcancellationpending: true, backupjobdetails: 'Cleanup pending' } },
    global: { mocks: { $t: key => key, $store: { getters: { apis: {} } } } }
  })
  expect(wrapper.vm.displayStatus).toBe('label.backup.cancellation.pending')
  expect(wrapper.vm.isActive).toBe(true)
  expect(wrapper.vm.cancellationDetails).toBe('Cleanup pending')
  wrapper.unmount()
})
