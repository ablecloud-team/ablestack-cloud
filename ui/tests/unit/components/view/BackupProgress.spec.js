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
import korean from '@public/locales/ko_KR.json'

jest.mock('@/api', () => ({ getAPI: jest.fn() }))

const statusWithTooltip = {
  props: ['text'],
  template: '<div><span class="status-text">{{ text }}</span><div class="status-tooltip"><slot name="tooltip" /></div></div>'
}

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

test.each(['ablestack-nas', 'ablestack-commvault', 'ablestack-netbackup', 'ablestack-veeam'])(
  'hides RBD bandwidth values and update status for %s before and after polling', async provider => {
    const step = provider === 'ablestack-nas' ? 'RBD_EXPORT' : provider.substring('ablestack-'.length).toUpperCase() + '_TRANSFER'
    getAPI.mockResolvedValue({
      getbackupjobstatusresponse: {
        status: 'BackingUp',
        state: 'RUNNING',
        step,
        progress: 71,
        volumeindex: 2,
        volumecount: 2,
        bandwidthlimitmbps: 0,
        bandwidthstatus: 'applied'
      }
    })
    const wrapper = shallowMount(BackupProgress, {
      props: {
        record: {
          id: 'backup-1',
          status: 'BackingUp',
          provider,
          backupengine: 'RBD_DIFF',
          bandwidthlimitmbps: 100,
          bandwidthstatus: 'pending'
        }
      },
      global: {
        stubs: { Status: statusWithTooltip },
        mocks: { $t: key => key, $store: { getters: { apis: { getBackupJobStatus: {} } } } }
      }
    })

    expect(wrapper.find('.status-tooltip').text()).not.toContain('label.bandwidth')
    expect(wrapper.find('.status-tooltip').text()).not.toContain('message.backup.bandwidth.')
    for (let i = 0; i < 5; i++) await Promise.resolve()
    expect(wrapper.find('.status-tooltip').text()).toContain(step)
    expect(wrapper.find('.status-tooltip').text()).toContain('2/2')
    expect(wrapper.find('.status-tooltip').text()).not.toContain('label.bandwidth')
    expect(wrapper.find('.status-tooltip').text()).not.toContain('label.backup.bandwidth.no.limit')
    expect(wrapper.find('.status-tooltip').text()).not.toContain('message.backup.bandwidth.')
    wrapper.unmount()
  }
)

test.each([0, 100])('shows the configured QCOW2 bandwidth %s and update status', bandwidth => {
  const wrapper = shallowMount(BackupProgress, {
    props: {
      record: {
        id: 'backup-1',
        status: 'BackingUp',
        backupengine: 'QCOW2',
        bandwidthlimitmbps: bandwidth,
        bandwidthstatus: 'applied'
      }
    },
    global: {
      stubs: { Status: statusWithTooltip },
      mocks: { $t: key => key, $store: { getters: { apis: {} } } }
    }
  })

  expect(wrapper.find('.status-tooltip').text()).toContain('label.bandwidth')
  expect(wrapper.find('.status-tooltip').text()).toContain(bandwidth === 0 ? 'label.backup.bandwidth.no.limit' : '100 Mbps')
  expect(wrapper.find('.status-tooltip').text()).toContain('message.backup.bandwidth.applied')
  wrapper.unmount()
})

test('shows the QCOW2 backup step separately from the running state', async () => {
  getAPI.mockResolvedValue({ getbackupjobstatusresponse: { status: 'BackingUp', state: 'RUNNING', step: 'QCOW2_BACKUP', progress: 16 } })
  const wrapper = shallowMount(BackupProgress, {
    props: { record: { id: 'backup-1', status: 'BackingUp', backupengine: 'QCOW2' } },
    global: {
      stubs: { Status: statusWithTooltip },
      mocks: {
        $t: key => korean[key] || key,
        $store: { getters: { apis: { getBackupJobStatus: {} } } }
      }
    }
  })

  for (let i = 0; i < 5; i++) await Promise.resolve()
  expect(wrapper.vm.jobState).toBe('RUNNING')
  expect(wrapper.vm.displayStep).toBe('QCOW2_BACKUP')
  expect(wrapper.find('.status-tooltip').text()).toContain('단계 :')
  expect(wrapper.find('.status-tooltip').text()).toContain('QCOW2_BACKUP')
  expect(wrapper.vm.progress).toBe(16)
  wrapper.unmount()
})

test('shows the current backup wait reason after reload despite an earlier failed restore', async () => {
  const reason = 'Waiting for NetBackup bootstrap Job 83 (ACTIVE), policy=ablecube4, schedule=schedule'
  const wrapper = shallowMount(BackupProgress, {
    props: {
      record: {
        id: 'backup-1',
        status: 'BackingUp',
        backupjobstate: 'RUNNING',
        backupjobstep: 'PREPARING',
        backupjobdetails: reason,
        restorejobstate: 'FAILED',
        restorejobstep: 'RESTORE_DATA',
        restorejobdetails: 'An earlier restore failed'
      }
    },
    global: {
      stubs: { Status: statusWithTooltip },
      mocks: { $t: key => key, $store: { getters: { apis: {} } } }
    }
  })

  expect(wrapper.find('.status-tooltip').text()).toContain(reason)
  expect(wrapper.vm.jobState).toBe('RUNNING')
  expect(wrapper.vm.displayStep).toBe('PREPARING')
  await wrapper.setProps({ record: { ...wrapper.props('record'), backupjobdetails: 'Source preparation is unconfirmed: HTTP 401' } })
  expect(wrapper.find('.status-tooltip').text()).toContain('Source preparation is unconfirmed: HTTP 401')
  expect(wrapper.find('.status-tooltip').text()).not.toContain(reason)
  wrapper.unmount()
})

test('updates the backup wait reason from polling and removes it when source preparation starts', async () => {
  getAPI.mockResolvedValue({
    getbackupjobstatusresponse: {
      status: 'BackingUp',
      state: 'RUNNING',
      step: 'PREPARING',
      progress: 5,
      details: 'Waiting for NetBackup bootstrap Job 83 (ACTIVE)'
    }
  })
  const wrapper = shallowMount(BackupProgress, {
    props: { record: { id: 'backup-1', status: 'BackingUp' } },
    global: {
      stubs: { Status: statusWithTooltip },
      mocks: { $t: key => key, $store: { getters: { apis: { getBackupJobStatus: {} } } } }
    }
  })

  for (let i = 0; i < 5; i++) await Promise.resolve()
  expect(wrapper.find('.status-tooltip').text()).toContain('Waiting for NetBackup bootstrap Job 83 (ACTIVE)')
  wrapper.vm.applyStatus({ status: 'BackingUp', state: 'RUNNING', step: 'QCOW2_BACKUP', progress: 12 })
  await wrapper.vm.$nextTick()
  expect(wrapper.find('.status-tooltip').text()).not.toContain('bootstrap Job 83')
  expect(wrapper.vm.displayStep).toBe('QCOW2_BACKUP')
  wrapper.unmount()
})

test.each(['poll', 'list refresh'])('finishes a backup without showing or tracking a restore after %s', async mode => {
  const running = { status: 'BackingUp', state: 'RUNNING', step: 'FINALIZING', progress: 95 }
  const completed = { status: 'BackedUp', state: 'COMPLETED', step: 'COMPLETED', progress: 100 }
  getAPI.mockResolvedValue({ getbackupjobstatusresponse: running })
  const wrapper = shallowMount(BackupProgress, {
    props: { record: { id: 'backup-1', status: 'BackingUp', provider: 'ablestack-netbackup' } },
    global: {
      stubs: { Status: statusWithTooltip },
      mocks: { $t: key => key, $store: { getters: { apis: { getBackupJobStatus: {}, getBackupRestoreJobStatus: {} } } } }
    }
  })

  for (let i = 0; i < 5; i++) await Promise.resolve()
  expect(wrapper.vm.displayStep).toBe('FINALIZING')
  if (mode === 'poll') {
    getAPI.mockResolvedValueOnce({ getbackupjobstatusresponse: completed })
    wrapper.vm.fetchStatus()
  } else {
    await wrapper.setProps({ record: { id: 'backup-1', status: 'BackedUp', catalogstate: 'PENDING', cleanupstate: 'COMPLETED' } })
  }
  for (let i = 0; i < 5; i++) await Promise.resolve()

  expect(wrapper.find('.status-text').text()).toBe('BackedUp')
  expect(wrapper.vm.hasTrackedRestoreJob).toBe(false)
  expect(wrapper.vm.shouldPoll()).toBe(false)
  expect(wrapper.emitted('restore-finished')).toBeUndefined()
  expect(getAPI.mock.calls.every(([api]) => api === 'getBackupJobStatus')).toBe(true)
  wrapper.unmount()
})

test.each(['PREPARE_SOURCE', 'RESTORE_PREPARE_TARGET', 'RESTORE_CONVERT'])('shows the restore phase %s as an English code in the Korean UI', step => {
  const wrapper = shallowMount(BackupProgress, {
    props: { record: { id: 'backup-1', status: 'Restoring', restorejobstate: 'RUNNING', restorejobstep: step } },
    global: {
      stubs: { Status: statusWithTooltip },
      mocks: {
        $t: key => korean[key] || key,
        $store: { getters: { apis: {} } }
      }
    }
  })

  expect(wrapper.vm.displayStep).toBe(step)
  expect(wrapper.find('.status-tooltip').text()).toContain('단계 :')
  expect(wrapper.find('.status-tooltip').text()).toContain(step)
  wrapper.unmount()
})

test('keeps restore status compact and shows volume, phase and details in the tooltip', async () => {
  getAPI.mockResolvedValue({
    getbackuprestorejobstatusresponse: {
      status: 'Restoring',
      state: 'RUNNING',
      step: 'RESTORE_COMPARE',
      volumeindex: 2,
      volumecount: 2,
      details: 'DATA · QCOW2 ↔ QCOW2',
      progress: 45
    }
  })
  const wrapper = shallowMount(BackupProgress, {
    props: { record: { id: 'backup-1', status: 'Restoring', restorejobstate: 'RUNNING', restorejobstep: 'RESTORE_DATA' } },
    global: {
      stubs: { Status: statusWithTooltip },
      mocks: {
        $t: key => korean[key] || key,
        $store: { getters: { apis: { getBackupRestoreJobStatus: {} } } }
      }
    }
  })

  for (let i = 0; i < 5; i++) await Promise.resolve()
  expect(wrapper.vm.volumeIndex).toBe(2)
  expect(wrapper.vm.volumeCount).toBe(2)
  expect(wrapper.vm.jobDetails).toBe('DATA · QCOW2 ↔ QCOW2')
  expect(wrapper.find('.status-text').text()).toBe('Restoring')
  expect(wrapper.find('.backup-restore-step').exists()).toBe(false)
  expect(wrapper.find('.status-tooltip').text()).toContain('2/2')
  expect(wrapper.find('.status-tooltip').text()).toContain('RESTORE_COMPARE')
  expect(wrapper.find('.status-tooltip').text()).toContain('DATA · QCOW2 ↔ QCOW2')
  expect(wrapper.find('.backup-progress-line').exists()).toBe(false)
  expect(wrapper.vm.showProgress).toBe(false)
  wrapper.vm.applyStatus({ state: 'RUNNING', step: 'RESTORE_PROMOTE', volumeindex: 2, volumecount: 2, details: 'DATA' })
  expect(wrapper.vm.displayStep).toBe('RESTORE_PROMOTE')
  expect(wrapper.vm.jobDetails).toBe('DATA')
  wrapper.unmount()
})

test('keeps unknown restore phases available in the tooltip', () => {
  const wrapper = shallowMount(BackupProgress, {
    props: { record: { id: 'backup-1', status: 'Restoring', restorejobstate: 'RUNNING', restorejobstep: 'FUTURE_RESTORE_PHASE' } },
    global: {
      stubs: { Status: statusWithTooltip },
      mocks: { $t: key => key, $store: { getters: { apis: {} } } }
    }
  })
  expect(wrapper.vm.displayStep).toBe('FUTURE_RESTORE_PHASE')
  expect(wrapper.find('.backup-restore-step').exists()).toBe(false)
  expect(wrapper.find('.status-tooltip').text()).toContain('FUTURE_RESTORE_PHASE')
  wrapper.unmount()
})

test.each(['Restoring', 'BackedUp'])('shows current restore details without backup catalog or cleanup history for %s', status => {
  const wrapper = shallowMount(BackupProgress, {
    props: {
      record: {
        id: 'backup-restore',
        status,
        backupengine: 'RBD_DIFF',
        restorejobid: 'current-restore',
        restorejobstate: 'RUNNING',
        restorejobstep: 'COMMVAULT_RESTORE',
        restorejobdetails: 'Restoring backup metadata',
        catalogstate: 'AVAILABLE',
        catalogchecked: '2026-10-19T14:20:40',
        catalogdetails: 'Old catalog details',
        cleanupstate: 'COMPLETED',
        cleanupdetails: 'Old backup cleanup',
        sourcecleanupstate: 'COMPLETED',
        sourcecleanupdetails: 'Old source snapshot cleanup',
        restorecleanupstate: 'WAITING',
        restorecleanupdetails: 'Restore job has not finished'
      }
    },
    global: {
      stubs: { Status: statusWithTooltip },
      mocks: { $t: key => korean[key] || key, $store: { getters: { apis: {} } } }
    }
  })

  const text = wrapper.find('.status-tooltip').text()
  expect(text).toContain('COMMVAULT_RESTORE')
  expect(text).toContain('Restoring backup metadata')
  expect(text).toContain('복원 정리 상태')
  expect(text).toContain('Scheduled after restoration finishes')
  for (const hidden of ['카탈로그 상태', '카탈로그 확인 시간', '이전 RBD 스냅샷', 'AVAILABLE', 'COMPLETED', 'Old catalog', 'Old backup', 'Old source', 'Restore job has not finished']) {
    expect(text).not.toContain(hidden)
  }
  wrapper.unmount()
})

test('preserves actual restore cleanup problems while restoration is running', () => {
  const wrapper = shallowMount(BackupProgress, {
    props: {
      record: {
        id: 'backup-restore',
        status: 'Restoring',
        restorejobstate: 'RUNNING',
        restorecleanupstate: 'WAITING',
        restorecleanupdetails: 'Worker host is unavailable'
      }
    },
    global: {
      stubs: { Status: statusWithTooltip },
      mocks: { $t: key => key, $store: { getters: { apis: {} } } }
    }
  })
  expect(wrapper.find('.status-tooltip').text()).toContain('WAITING')
  expect(wrapper.find('.status-tooltip').text()).toContain('Worker host is unavailable')
  wrapper.unmount()
})

test.each(['poll', 'reload'])('shows the completed restore result without stale volume progress after %s', async mode => {
  const vmrestore = { stagingjobid: 'restore-current', outcome: 'COMMITTED', phase: 'COMPLETED', primarycleanupstate: 'COMPLETED' }
  const wrapper = shallowMount(BackupProgress, {
    props: {
      record: {
        id: 'backup-restore',
        status: mode === 'poll' ? 'Restoring' : 'BackedUp',
        restorejobstate: mode === 'poll' ? 'RUNNING' : 'COMPLETED',
        restorejobid: 'restore-current',
        restorecleanupstate: 'COMPLETED',
        catalogstate: 'AVAILABLE',
        catalogchecked: '2026-10-19T14:37:33',
        cleanupstate: 'COMPLETED',
        sourcecleanupstate: 'COMPLETED',
        backupengine: 'RBD_DIFF',
        vmrestore
      }
    },
    global: {
      stubs: { Status: statusWithTooltip },
      mocks: { $t: key => korean[key] || key, $store: { getters: { apis: {} } } }
    }
  })
  if (mode === 'poll') {
    wrapper.vm.applyStatus({ status: 'BackedUp', state: 'COMPLETED', step: 'COMPLETED', progress: 100, volumeindex: 2, volumecount: 2, vmrestore })
    await wrapper.vm.$nextTick()
  }
  const text = wrapper.find('.status-tooltip').text()
  expect(wrapper.find('.status-text').text()).toBe('BackedUp')
  expect(text).toContain('최근 복원 결과')
  expect(text).toContain('COMMITTED / COMPLETED')
  expect(text).toContain('복원 정리 상태')
  expect(text).toContain('기본 스토리지 볼륨 정리 상태')
  for (const hidden of ['2/2', '카탈로그 상태', '카탈로그 확인 시간', '이전 RBD 스냅샷', 'AVAILABLE']) {
    expect(text).not.toContain(hidden)
  }
  expect(wrapper.vm.volumeIndex).toBeNull()
  expect(wrapper.vm.volumeCount).toBeNull()
  expect(wrapper.vm.progress).toBeNull()
  if (mode === 'poll') {
    expect(wrapper.emitted('restore-finished')).toEqual([['COMPLETED']])
  }
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

test.each([
  ['QCOW2', '이전 QCOW2 비트맵 정리 상태'],
  ['RBD_DIFF', '이전 RBD 스냅샷 정리 상태'],
  [undefined, '이전 증분 기준 정리 상태']
])('labels the previous source cleanup for %s separately from staging cleanup', (engine, label) => {
  const wrapper = shallowMount(BackupProgress, {
    props: { record: { id: 'backup-1', status: 'BackedUp', backupengine: engine, sourcecleanupstate: 'WAITING', catalogstate: 'PENDING', cleanupstate: 'COMPLETED' } },
    global: {
      stubs: { Status: statusWithTooltip },
      mocks: { $t: key => korean[key] || key, $store: { getters: { apis: {} } } }
    }
  })
  expect(wrapper.find('.status-tooltip').text()).toContain(label)
  expect(wrapper.find('.status-tooltip').text()).toContain('WAITING')
  expect(wrapper.find('.status-tooltip').text()).toContain('COMPLETED')
  wrapper.unmount()
})

test.each(['Failed', 'Error'])('shows the %s backup reason after staging cleanup has completed', status => {
  const reason = 'Volume backup failed: [Errno 9] Bad file descriptor'
  const wrapper = shallowMount(BackupProgress, {
    props: { record: { id: 'backup-1', status, backupjobdetails: reason, catalogstate: 'PENDING', cleanupstate: 'COMPLETED' } },
    global: {
      stubs: { Status: statusWithTooltip },
      mocks: { $t: key => korean[key] || key, $store: { getters: { apis: {} } } }
    }
  })
  const text = wrapper.find('.status-tooltip').text()
  expect(text).toContain(korean['label.failure.reason'])
  expect(text).toContain(reason)
  expect(text).toContain('PENDING')
  expect(text).toContain('COMPLETED')
  wrapper.unmount()
})

test('pending deletion is visible with its persisted English state and reason', () => {
  const wrapper = shallowMount(BackupProgress, {
    props: { record: { id: 'backup', status: 'BackedUp', deletionpending: true, deletionstate: 'WAITING_DEPENDENCIES', deletiondetails: 'Dependent recovery points remain' } },
    global: { stubs: { Status: statusWithTooltip }, mocks: { $t: key => korean[key] || key, $store: { getters: { apis: {} } } } }
  })
  expect(wrapper.vm.displayStatus).toBe('삭제 대기')
  expect(wrapper.text()).toContain('삭제 상태')
  expect(wrapper.text()).toContain('WAITING_DEPENDENCIES')
  expect(wrapper.text()).toContain('Dependent recovery points remain')
  wrapper.unmount()
})

test('shows rejected external restore details without changing a stored backup into Restoring', () => {
  const wrapper = shallowMount(BackupProgress, {
    props: {
      record: {
        id: 'backup-1',
        status: 'BackedUp',
        restorejobstate: 'FAILED',
        restorejobstep: 'PRECHECK',
        restorejobdetails: 'The original VM must be Stopped',
        restorecleanupstate: 'WAITING',
        externalrestorefailure: { jobid: '121', date: '2026-10-23T10:35:00+0900', host: 'ablecube4', cleanupstate: 'WAITING', cleanupdetails: 'Host unavailable' }
      }
    },
    global: {
      stubs: { 'a-tooltip': { template: '<span><slot /><slot name="title" /></span>' } },
      mocks: { $t: key => key, $store: { getters: { apis: { getBackupRestoreJobStatus: {} } } } }
    }
  })
  expect(wrapper.vm.showRestoreFailure).toBe(true)
  expect(wrapper.vm.localStatus).toBe('BackedUp')
  expect(wrapper.vm.isActive).toBe(false)
  expect(wrapper.text()).toContain('121')
  expect(wrapper.text()).toContain('ablecube4')
  expect(wrapper.text()).toContain('Host unavailable')
  expect(getAPI).not.toHaveBeenCalled()
  wrapper.unmount()
})
