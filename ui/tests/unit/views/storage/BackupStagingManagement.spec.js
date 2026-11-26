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
import ko from '../../../../public/locales/ko_KR.json'

jest.mock('@/api', () => ({ getAPI: jest.fn(), postAPI: jest.fn() }))

async function mount (info, operation = 'BACKUP', resource = {}, translations = {}, props = {}) {
  const responseKey = (props.inspectionApi || 'getBackupStagingInfo').toLowerCase() + 'response'
  getAPI.mockResolvedValue({ [responseKey]: { backupstaginginfo: { stagingjobid: 'staging-1', ...info } } })
  const wrapper = shallowMount(BackupStagingManagement, {
    props: { resource: { id: 'backup-1', status: 'Failed', stagingqueue: { operation }, ...resource }, ...props },
    global: {
      stubs: {
        ADescriptions: { template: '<div><slot /></div>' },
        ADescriptionsItem: { props: ['label'], template: '<div>{{ label }}<slot /></div>' },
        ACollapse: { template: '<div><slot /></div>' },
        ACollapsePanel: { template: '<div><slot /></div>' },
        ATag: { template: '<span><slot /></span>' },
        ATable: {
          props: ['columns', 'dataSource'],
          template: '<table><tbody><tr v-for="record in dataSource" :key="record.path"><td v-for="column in columns" :key="column.key"><slot name="bodyCell" :column="column" :record="record" /></td></tr></tbody></table>'
        }
      },
      mocks: {
        $store: { getters: { apis: { reconcileBackupStagingJob: {} } } },
        $t: key => translations[key] || key,
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

test.each(['WAITING', 'RETRYING', 'STOPPING', 'FAILED'])('external completion retains a separate Host receipt state: %s', async state => {
  const w = await mount({
    hoststate: 'RUNNING',
    artifact: [{ path: '/stage/restore/payload/root.raw', completed: true, restorestate: 'COMPLETED', acknowledgmentstate: state, acknowledgmenterror: 'RESTORE_ACK_REJECTED: Request fields differ' }]
  }, 'RESTORE', { provider: 'ablestack-netbackup' }, ko)
  expect(w.text()).toContain('COMPLETED')
  expect(w.text()).toContain(ko['label.backup.restore.receipt'])
  expect(w.text()).toContain(ko['label.backup.restore.receipt.' + state.toLowerCase()])
  expect(w.text()).toContain('RESTORE_ACK_REJECTED: Request fields differ')
  expect(postAPI).not.toHaveBeenCalled()
  w.unmount()
})

test('confirmed restore receipts retain the normal completed-file presentation', async () => {
  const w = await mount({
    hoststate: 'COMPLETED',
    artifact: [{ path: '/stage/restore/payload/root.raw', completed: true, restorestate: 'COMPLETED', acknowledgmentstate: 'ACKNOWLEDGED' }]
  }, 'RESTORE', { provider: 'ablestack-netbackup' }, ko)
  expect(w.text()).toContain('COMPLETED')
  expect(w.text()).not.toContain(ko['label.backup.restore.receipt'])
  w.unmount()
})

test('NetBackup image inspection retains exact owned and inherited references after Host cleanup', async () => {
  const artifact = [
    { path: '/stage/root', volumeid: 'root-volume', backupid: 'backup-1', owned: true, externalid: 'host_123', externaljobid: '901', completed: true },
    { path: '/stage/data', volumeid: 'data-volume', backupid: 'backup-1', owned: true, externalid: 'host_124', externaljobid: '902', completed: true },
    { path: '/stage/metadata', metadata: true, backupid: 'backup-1', owned: true, externalid: 'host_125', externaljobid: '903', completed: true },
    { path: '/old/root', volumeid: 'root-volume', backupid: 'parent-backup', owned: false, externalid: 'host_100', externaljobid: '800', completed: true }
  ]
  const w = await mount({ id: 'backup-1', hoststate: 'COMPLETED', artifact }, 'RESTORE', {
    provider: 'ablestack-netbackup',
    status: 'BackedUp',
    type: 'INCREMENTAL',
    catalogstate: 'AVAILABLE',
    catalogchecked: '2026-11-13T00:00:00Z',
    volumes: JSON.stringify([{ uuid: 'root-volume', type: 'ROOT' }, { uuid: 'data-volume', type: 'DATADISK' }])
  }, ko, { readOnly: true, inspectionApi: 'getBackupImageInfo' })
  expect(getAPI).toHaveBeenCalledWith('getBackupImageInfo', { id: 'backup-1' })
  expect(w.vm.operation).toBe('BACKUP')
  for (const value of ['backup-1', 'ROOT', 'DATA', 'host_123', 'host_124', 'host_125', 'host_100', '901', '902', '903', '800', 'parent-backup', '2026-11-13T00:00:00Z']) expect(w.text()).toContain(value)
  expect(w.text()).toContain(ko['label.backup.image.owned'])
  expect(w.text()).toContain(ko['label.backup.image.inherited'])
  expect(w.html()).toContain(ko['message.backup.image.saved.references'])
  expect(w.text()).toContain(ko['label.backup.staging.file.not.checked'])
  expect(w.vm.canManage).toBe(false)
  expect(w.vm.canMutate).toBe(false)
  for (const action of ['RECHECK', 'RETRY_CLEANUP', 'LINK_JOB']) await w.vm.run(action)
  expect(postAPI).not.toHaveBeenCalled()
  await w.vm.load()
  expect(getAPI).toHaveBeenCalledTimes(2)
  w.unmount()
})

test('NetBackup image lookup errors retain their message and keep mutation actions disabled', async () => {
  const w = await mount({}, 'BACKUP', {}, ko, { readOnly: true, inspectionApi: 'getBackupImageInfo' })
  getAPI.mockRejectedValueOnce({ response: { data: { getbackupimageinforesponse: { errortext: 'NetBackup query unavailable' } } } })
  await w.vm.load()
  expect(w.vm.errorMessage).toBe('NetBackup query unavailable')
  expect(w.vm.canMutate).toBe(false)
  expect(postAPI).not.toHaveBeenCalled()
  w.unmount()
})

test.each(['BACKUP', 'RESTORE'])('explains a busy %s staging query in Korean without reporting a restore failure', async operation => {
  const wrapper = await mount({}, operation, {}, ko)
  getAPI.mockRejectedValueOnce({ response: { data: { getbackupstaginginforesponse: { errortext: 'Staging job is being reconciled; retry shortly' } } } })
  await wrapper.vm.load()
  expect(wrapper.vm.loading).toBe(false)
  expect(wrapper.vm.isReconciling).toBe(true)
  expect(wrapper.vm.errorMessage).toBe('백업·복원 작업 처리 중')
  expect(wrapper.html()).toContain(ko['message.backup.staging.reconciling'])
  expect(wrapper.html()).toContain('type="info"')
  expect(wrapper.html()).not.toContain('Staging job is being reconciled')
  expect(wrapper.vm.canMutate).toBe(false)
  expect(postAPI).not.toHaveBeenCalled()
  wrapper.unmount()
})

test('other staging query failures keep their diagnostic message', async () => {
  const wrapper = await mount({})
  getAPI.mockRejectedValueOnce(new Error('Worker Host is unavailable'))
  await wrapper.vm.load()
  expect(wrapper.vm.isReconciling).toBe(false)
  expect(wrapper.vm.errorMessage).toBe('Worker Host is unavailable')
  expect(wrapper.html()).toContain('type="warning"')
  wrapper.unmount()
})

test.each([
  ['Failed', false], ['Failed', true], ['Canceled', false], ['Canceled', true], ['Error', true]
])('ended NetBackup %s shows the failure and historical retention in readOnly=%s', async (status, readOnly) => {
  const wrapper = await mount({
    provider: 'ablestack-netbackup',
    hoststate: 'FAILED',
    cleanupstate: 'COMPLETED',
    stagingqueue: { state: 'RELEASED' },
    retentionstate: 'WAITING',
    retentionreason: 'NETBACKUP_RETENTION_UNCONFIRMED: ancestor-1'
  }, 'BACKUP', { status, backupjobdetails: 'Volume backup failed: Staging queue timeout expired' }, ko, { readOnly })
  expect(wrapper.vm.situation.title).toBe('message.backup.staging.backup.ended.title')
  expect(wrapper.vm.retentionHistorical).toBe(true)
  expect(wrapper.text()).toContain('Staging queue timeout expired')
  expect(wrapper.text()).toContain(ko['label.backup.retention.historical.unconfirmed'])
  expect(wrapper.text()).toContain(ko['message.backup.retention.historical'])
  expect(wrapper.text()).toContain('ancestor-1')
  expect(wrapper.text()).not.toContain(ko['label.backup.retention.pending'])
  expect(wrapper.text()).not.toContain(ko['message.backup.retention.pending'])
  expect(wrapper.vm.canCleanup).toBe(false)
  expect(postAPI).not.toHaveBeenCalled()
  wrapper.unmount()
})

test.each(['BackingUp', 'BackedUp'])('NetBackup %s retains a current retention warning', async status => {
  const wrapper = await mount({ provider: 'ablestack-netbackup', retentionstate: 'WAITING' }, 'BACKUP', { status }, ko)
  expect(wrapper.vm.retentionHistorical).toBe(false)
  expect(wrapper.vm.situation.title).toBe('label.backup.retention.pending')
  expect(wrapper.text()).toContain(ko['message.backup.retention.pending'])
  wrapper.unmount()
})

test.each([
  ['ablestack-netbackup', 'RESTORE'],
  ['ablestack-veeam', 'BACKUP']
])('does not relabel %s %s retention using the source backup failure', async (provider, operation) => {
  const wrapper = await mount({ provider, retentionstate: 'WAITING' }, operation, { status: 'Failed' }, ko)
  expect(wrapper.vm.retentionHistorical).toBe(false)
  expect(wrapper.vm.situation.title).toBe('label.backup.retention.pending')
  expect(wrapper.text()).not.toContain(ko['message.backup.retention.historical'])
  wrapper.unmount()
})

test('expired NetBackup retention stays historical without replacing the expiration warning', async () => {
  const wrapper = await mount({ provider: 'ablestack-netbackup', retentionstate: 'COMPLETED' }, 'BACKUP', { status: 'Expired' }, ko)
  expect(wrapper.vm.situation.title).toBe('state.expired')
  expect(wrapper.vm.retentionHistorical).toBe(true)
  expect(wrapper.text()).toContain(ko['label.backup.retention.historical.verified'])
  expect(wrapper.text()).not.toContain(ko['message.backup.retention.pending'])
  wrapper.unmount()
})

test('expired backup diagnostics explain expiration and hide restoration instructions', async () => {
  const wrapper = await mount({
    hoststate: 'COMPLETED',
    cleanupstate: 'WAITING',
    cleanupreason: 'External artifact absence will be reconfirmed before expiration cleanup'
  }, 'BACKUP', {
    status: 'Expired',
    catalogstate: 'EXPIRED',
    externalrestoreentrypath: '/old/MOLD-RESTORE.json',
    catalogexpiredsince: '2026-11-04T13:35:33',
    catalogcleanupafter: '2026-11-04T13:38:33'
  })
  expect(wrapper.vm.situation.title).toBe('state.expired')
  expect(wrapper.vm.situation.description).toBe('message.backup.catalog.expired')
  expect(wrapper.text()).toContain('message.backup.catalog.expiration.confirmation')
  expect(wrapper.text()).toContain('2026-11-04T13:38:33')
  expect(wrapper.html()).not.toContain('message.backup.netbackup.restore.entry')
  expect(wrapper.html()).not.toContain('MOLD-RESTORE.json')
  wrapper.unmount()
})

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

test.each([
  [{ deletionpending: true }, {}],
  [{}, { deletionpending: true }]
])('pending deletion keeps diagnostics readable and blocks staging changes', async (info, resource) => {
  const wrapper = await mount({ hostname: 'ablecube3', hoststate: 'FAILED', cleanupstate: 'WAITING', ...info }, 'BACKUP', resource)
  expect(wrapper.vm.situation.title).toBe('message.backup.staging.delete.pending.title')
  expect(wrapper.text()).toContain('ablecube3')
  expect(wrapper.vm.canMutate).toBe(false)
  expect(wrapper.vm.canCleanup).toBe(false)
  for (const action of ['RECHECK', 'RETRY_CLEANUP', 'LINK_JOB']) await wrapper.vm.run(action)
  expect(postAPI).not.toHaveBeenCalled()
  await wrapper.vm.load()
  expect(getAPI).toHaveBeenCalledTimes(2)
  expect(wrapper.vm.info.hostname).toBe('ablecube3')
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

test.each(['WAITING', 'UNKNOWN'])('retention %s stays visible after successful staging cleanup and permits rechecking', async retentionstate => {
  const wrapper = await mount({
    hoststate: 'COMPLETED',
    cleanupstate: 'COMPLETED',
    jobcleanupstate: 'COMPLETED',
    stagingqueue: { state: 'RELEASED' },
    retentionstate,
    retentionreason: 'NETBACKUP_RETENTION_UNCONFIRMED: ticket-1',
    retentionuntil: '2026-11-26T05:29:10Z'
  }, 'BACKUP', { status: 'BackedUp' }, ko)
  expect(wrapper.vm.situation.type).toBe('warning')
  expect(wrapper.text()).toContain('보존 확인 대기')
  expect(wrapper.text()).toContain('ticket-1')
  expect(wrapper.text()).toContain('2026-11-26T05:29:10Z')
  expect(wrapper.text()).toContain(ko['message.backup.retention.pending'])
  expect(wrapper.vm.canCleanup).toBe(false)
  expect(wrapper.vm.canMutate).toBe(true)
  await wrapper.vm.run('RECHECK')
  expect(postAPI).toHaveBeenCalledWith('reconcileBackupStagingJob', expect.objectContaining({ action: 'RECHECK' }))
  wrapper.unmount()
})

test('verified retention shows completion without the pending instruction', async () => {
  const wrapper = await mount({
    hoststate: 'COMPLETED',
    cleanupstate: 'COMPLETED',
    stagingqueue: { state: 'RELEASED' },
    retentionstate: 'COMPLETED',
    retentionuntil: '2026-11-26T05:29:10Z'
  }, 'BACKUP', { status: 'BackedUp' }, ko)
  expect(wrapper.text()).toContain('보존 확인 완료')
  expect(wrapper.text()).not.toContain(ko['message.backup.retention.pending'])
  expect(wrapper.vm.situation.type).toBe('success')
  wrapper.unmount()
})

test('successful staging permits retrying pending execution record cleanup only', async () => {
  for (const jobcleanupstate of ['WAITING', 'RUNNING', 'COMPLETED']) {
    const wrapper = await mount({ hoststate: 'COMPLETED', cleanupstate: 'COMPLETED', jobcleanupstate, stagingqueue: { state: 'RELEASED' } }, 'BACKUP', { status: 'BackedUp' })
    expect(wrapper.vm.canCleanup).toBe(jobcleanupstate !== 'COMPLETED')
    expect(wrapper.vm.situation.type).toBe(jobcleanupstate === 'COMPLETED' ? 'success' : 'warning')
    wrapper.unmount()
  }
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

test('shows external selection reason and restore entry even without a VM restore Job', async () => {
  const wrapper = await mount({ stagingjobid: '' }, 'BACKUP', {
    externalrestorevalidation: { jobid: '901', state: 'WAITING_SELECTION', code: 'VOLUME_ONLY' },
    externalrestoreentrypath: '/stage/MOLD-RESTORE.json'
  })
  const alerts = wrapper.findAll('a-alert-stub')
  expect(alerts.some(alert => alert.attributes('message')?.includes('901'))).toBe(true)
  expect(alerts.some(alert => alert.attributes('description') === 'message.backup.external.selection.VOLUME_ONLY')).toBe(true)
  expect(wrapper.find('.staging-catalog').text()).toContain('/stage/MOLD-RESTORE.json')
  expect(wrapper.find('.staging-catalog').text()).toContain('message.backup.netbackup.restore.entry')
  expect(alerts.some(alert => alert.attributes('description') === '/stage/MOLD-RESTORE.json')).toBe(false)
  expect(wrapper.vm.canMutate).toBe(false)
  expect(postAPI).not.toHaveBeenCalled()
  wrapper.unmount()
})
