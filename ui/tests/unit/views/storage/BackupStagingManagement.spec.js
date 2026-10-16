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

import { shallowMount } from '@vue/test-utils'
import { getAPI, postAPI } from '@/api'
import BackupStagingManagement from '@/views/storage/BackupStagingManagement.vue'

jest.mock('@/api', () => ({ getAPI: jest.fn(), postAPI: jest.fn() }))

async function mount (info, operation = 'BACKUP', resource = {}) {
  getAPI.mockResolvedValue({ getbackupstaginginforesponse: { backupstaginginfo: { stagingjobid: 'staging-1', ...info } } })
  const wrapper = shallowMount(BackupStagingManagement, {
    props: { resource: { id: 'backup-1', status: 'Failed', stagingqueue: { operation }, ...resource } },
    global: {
      stubs: {
        ADescriptions: { template: '<div><slot /></div>' },
        ADescriptionsItem: { props: ['label'], template: '<div>{{ label }}<slot /></div>' }
      },
      mocks: {
        $store: { getters: { apis: { reconcileBackupStagingJob: {} } } },
        $t: key => key,
        $toLocaleDate: value => value,
        $route: { path: '/backup' },
        $pollJob: jest.fn(),
        $notifyError: jest.fn()
      }
    }
  })
  for (let i = 0; i < 5; i++) await Promise.resolve()
  return wrapper
}

beforeEach(() => jest.clearAllMocks())

test.each([
  ['QCOW2', 'label.backup.qcow2.source.cleanup.state'],
  ['RBD_DIFF', 'label.backup.source.cleanup.state'],
  [undefined, 'label.backup.source.cleanup.generic.state']
])('shows the %s parent cleanup state and waiting reason in staging management', async (backupengine, label) => {
  const wrapper = await mount({ sourcecleanupstate: 'WAITING', sourcecleanupreason: 'Parent bitmap is busy' }, 'BACKUP', { backupengine })
  expect(wrapper.html()).toContain(label)
  expect(wrapper.text()).toContain('Parent bitmap is busy')
  wrapper.unmount()
})

test('serialized staging details are unwrapped and their exact job ID is sent for recheck', async () => {
  const wrapper = await mount({ stagingjobid: 'exact-job-id', vmname: 'i-2-25-VM', hoststate: 'FAILED' })
  postAPI.mockResolvedValue({ reconcilebackupstagingjobresponse: { jobid: 'async-1' } })
  expect(wrapper.vm.info.vmname).toBe('i-2-25-VM')
  expect(wrapper.vm.canMutate).toBe(true)
  await wrapper.vm.run('RECHECK')
  expect(postAPI).toHaveBeenCalledWith('reconcileBackupStagingJob', {
    id: 'backup-1', operation: 'BACKUP', stagingjobid: 'exact-job-id', action: 'RECHECK'
  })
  wrapper.unmount()
})

test('missing job ID hides empty diagnostics and blocks every management request', async () => {
  for (const stagingjobid of [undefined, '', '   ']) {
    const wrapper = await mount({ stagingjobid, hoststate: 'FAILED', cleanupstate: 'WAITING' })
    expect(wrapper.vm.info).toBe(null)
    expect(wrapper.vm.canMutate).toBe(false)
    expect(wrapper.vm.canCleanup).toBe(false)
    for (const action of ['RECHECK', 'RETRY_CLEANUP', 'LINK_JOB']) await wrapper.vm.run(action)
    wrapper.unmount()
  }
  expect(postAPI).not.toHaveBeenCalled()
})

test('historical restore inspection never submits a management action', async () => {
  const wrapper = await mount({ historical: true }, 'RESTORE')
  expect(wrapper.vm.canMutate).toBe(false)
  await wrapper.vm.run('RECHECK')
  expect(postAPI).not.toHaveBeenCalled()
  wrapper.unmount()
})

test('completed staging and record cleanup with a released reservation cannot be retried', async () => {
  const wrapper = await mount({ hoststate: 'FAILED', cleanupstate: 'COMPLETED', jobcleanupstate: 'COMPLETED', stagingqueue: { state: 'RELEASED' } })
  expect(wrapper.vm.canCleanup).toBe(false)
  wrapper.unmount()
})

test('finished staging still allows confirmed pending Host record or reservation cleanup', async () => {
  for (const info of [{ jobcleanupstate: 'WAITING', stagingqueue: { state: 'RELEASED' } }, { jobcleanupstate: 'COMPLETED', stagingqueue: { state: 'ADMITTED' } }]) {
    const wrapper = await mount({ ...info, hoststate: 'UNKNOWN', startstate: 'STARTED', cleanupstate: 'COMPLETED' })
    expect(wrapper.vm.canCleanup).toBe(true)
    wrapper.unmount()
  }
})

test('a completed restore or historical attempt cannot acquire a cleanup action', async () => {
  for (const info of [{ cleanupstate: 'COMPLETED' }, { historical: true, cleanupstate: 'WAITING' }]) {
    const wrapper = await mount({ ...info, hoststate: 'FAILED', vmrestore: { outcome: 'RECOVERY_REQUIRED' } }, 'RESTORE')
    expect(wrapper.vm.canCleanup).toBe(false)
    wrapper.unmount()
  }
})

test('a successful backup reports confirmed full staging cleanup', async () => {
  const wrapper = await mount({ hoststate: 'COMPLETED', cleanupstate: 'COMPLETED', stagingqueue: { state: 'RELEASED' } }, 'BACKUP', { status: 'BackedUp' })
  expect(wrapper.vm.situation.title).toBe('message.backup.staging.backup.completed.title')
  expect(wrapper.vm.situation.type).toBe('success')
  expect(wrapper.vm.canCleanup).toBe(false)
  wrapper.unmount()
})

test('completion is not shown while the staging reservation or source cleanup is pending', async () => {
  for (const info of [{ stagingqueue: { state: 'ADMITTED' } }, { stagingqueue: { state: 'RELEASED' }, sourcecleanupstate: 'WAITING' }, { stagingqueue: { state: 'RELEASED' }, cleanupstate: 'NONE' }]) {
    const wrapper = await mount({ hoststate: 'COMPLETED', cleanupstate: 'NONE', ...info }, 'BACKUP', { status: 'BackedUp' })
    expect(wrapper.vm.situation.title).not.toBe('message.backup.staging.backup.completed.title')
    wrapper.unmount()
  }
})

test('a catalog query error remains visible for completed external jobs', async () => {
  const artifact = { completed: true, availability: 'UNKNOWN', availabilitycheckedat: '2026-10-14T00:21:57Z', availabilityerror: 'Commvault artifact catalog query failed' }
  const wrapper = await mount({ hoststate: 'COMPLETED', cleanupstate: 'NONE', stagingqueue: { state: 'RELEASED' }, artifact: [artifact] }, 'BACKUP', { status: 'BackedUp' })
  expect(wrapper.vm.situation.title).toBe('message.backup.staging.query.failed.title')
  expect(wrapper.vm.availabilityLabel(artifact)).toBe('label.backup.staging.file.query.failed')
  expect(wrapper.vm.availabilityColor(artifact)).toBe('orange')
  wrapper.unmount()
})

test('unqueried, unconfirmed, present and missing catalog files have distinct labels', async () => {
  const wrapper = await mount({})
  for (const [artifact, label] of [
    [{ completed: true, availability: 'UNKNOWN' }, 'not.checked'],
    [{ availability: 'UNKNOWN', availabilitycheckedat: '2026-10-14T00:21:57Z' }, 'unknown'],
    [{ availability: 'AVAILABLE' }, 'available'],
    [{ availability: 'MISSING' }, 'missing']
  ]) {
    expect(wrapper.vm.availabilityLabel(artifact)).toBe('label.backup.staging.file.' + label)
  }
  expect(wrapper.vm.backupTime('1791791903')).toBe('2026-10-12T07:58:23.000Z')
  wrapper.unmount()
})
