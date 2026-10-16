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

import { reactive } from 'vue'
import { shallowMount } from '@vue/test-utils'
import VmBackupsTab from '@/views/compute/VmBackupsTab.vue'
import { getAPI, postAPI } from '@/api'
import eventBus from '@/config/eventBus'
import storage from '@/config/section/storage'
import offering from '@/config/section/offering'
jest.mock('@/api', () => ({ getAPI: jest.fn(), postAPI: jest.fn() }))
jest.mock('@/store', () => ({ getters: { userInfo: { roletype: 'Admin' }, apis: {}, features: {} } }))
jest.mock('@/utils/listRefresh', () => ({ ...jest.requireActual('@/utils/listRefresh'), canRefreshList: () => true }))
const vm = { id: 'vm', name: 'VM', state: 'Running', zoneid: 'zone', backupofferingid: 'offering', backupprovider: 'kboss' }
const row = { id: 'backup', name: 'daily', virtualmachineid: 'vm', status: 'BackedUp', provider: 'kboss' }
const response = rows => ({ listbackupsresponse: { backup: rows, count: rows.length } })
const flush = async () => { for (let i = 0; i < 30; i++) await Promise.resolve() }
const apis = Object.fromEntries(['listBackups', 'listVirtualMachines', 'restoreBackup', 'deleteBackup', 'cancelBackup', 'updateBackupJobBandwidth', 'createBackup', 'assignVirtualMachineToBackupOffering', 'createBackupSchedule', 'removeVirtualMachineFromBackupOffering', 'createVMFromBackup', 'restoreVolumeFromBackupAndAttachToVM'].map(api => [api, {}]))
function mount (overrides = {}) {
  return shallowMount(VmBackupsTab, {
    props: { resource: { ...vm } },
    global: {
      stubs: { RouterLink: true, AInputSearch: { template: '<input />' } },
      mocks: {
        $store: reactive({ getters: { apis: { ...apis }, features: {}, project: {}, userInfo: { id: 'user', roletype: 'Admin' } }, state: { user: { token: 'token' } } }),
        $route: { path: '/vm/vm', fullPath: '/vm/vm?tab=backups' },
        $t: key => key,
        $toLocaleDate: value => value,
        $notifyError: jest.fn(),
        $pollJob: jest.fn().mockResolvedValue({ jobstatus: 1 }),
        ...overrides
      }
    }
  })
}
beforeEach(() => {
  jest.useFakeTimers(); jest.clearAllMocks()
  Object.defineProperty(document, 'hidden', { configurable: true, value: false })
  getAPI.mockImplementation(api => Promise.resolve(api === 'listVirtualMachines' ? { listvirtualmachinesresponse: { virtualmachine: [{ ...vm }] } } : response([{ ...row }])))
  postAPI.mockResolvedValue({ restorebackupresponse: { jobid: 'job' }, deletebackupresponse: { jobid: 'job' } })
})
afterEach(() => jest.useRealTimers())
test('automatic refresh preserves row identity, pagination, columns and no loading overlay', async () => {
  const w = mount(); await flush(); const rows = w.vm.rows; const columns = w.vm.columns
  let finish
  getAPI.mockReturnValue(new Promise(resolve => { finish = resolve }))
  jest.advanceTimersByTime(10000); await flush()
  expect(w.vm.loading).toBe(false); expect(w.vm.rows).toBe(rows)
  finish(response([{ ...row }])); await flush()
  expect(w.vm.rows).toBe(rows); expect(w.vm.columns).toBe(columns)
  const calls = getAPI.mock.calls.length; w.unmount(); jest.advanceTimersByTime(20000); await flush(); expect(getAPI).toHaveBeenCalledTimes(calls)
})
test('refresh failure retains list, and same VM telemetry does not reset presentation', async () => {
  const w = mount(); await flush(); const rows = w.vm.rows
  await w.setProps({ resource: { ...vm, cpuused: '2%' } }); await flush(); expect(w.vm.rows).toBe(rows)
  getAPI.mockRejectedValue(new Error('offline')); await w.vm.fetchData()
  expect(w.vm.rows).toBe(rows); expect(w.vm.listRefreshFailed).toBe(true); expect(w.vm.$notifyError).not.toHaveBeenCalled(); w.unmount()
})
test('old VM response cannot replace new VM list', async () => {
  let old
  getAPI.mockReturnValue(new Promise(resolve => { old = resolve }))
  const w = mount(); getAPI.mockResolvedValue(response([{ ...row, id: 'other', virtualmachineid: 'other-vm' }]))
  await w.setProps({ resource: { ...vm, id: 'other-vm' } }); await flush(); old(response([row])); await flush()
  expect(w.vm.rows[0].id).toBe('other'); w.unmount()
})
test('actual provider rules prohibit Veeam and NetBackup deletion but allow supported provider', async () => {
  const w = mount(); await flush()
  for (const provider of ['veeam', 'ablestack-veeam', 'netbackup', 'ablestack-netbackup']) expect(w.vm.visible('deleteBackup', { ...row, provider })).toBe(false)
  expect(w.vm.visible('deleteBackup', row)).toBe(true)
  expect(w.vm.disabled('restoreBackup', { ...row, status: 'Failed' })).toBe(true)
  delete w.vm.$store.getters.apis.deleteBackup; expect(w.vm.visible('deleteBackup', row)).toBe(false); w.unmount()
})
test('expunged source disables only in-place restore while retained backup actions stay available', async () => {
  const w = mount(); await flush()
  const retained = { ...row, provider: 'ablestack-nas', restoreavailable: true, vmrestoreavailable: false, isbackupvmexpunged: true }
  expect(w.vm.disabled('restoreBackup', retained)).toBe(true)
  expect(w.vm.disabled('restoreVolumeFromBackupAndAttachToVM', retained)).toBe(false)
  expect(w.vm.disabled('createVMFromBackup', retained)).toBe(false)
  expect(w.vm.disabled('deleteBackup', retained)).toBe(false)
  expect(w.vm.definition('restoreBackup').tooltip(retained)).toBe('message.backup.original.vm.unavailable')
  w.unmount()
})
test('opening restore rechecks whether the original instance was expunged', async () => {
  const w = mount(); await flush()
  const backup = { ...row, provider: 'ablestack-nas' }
  getAPI.mockResolvedValue(response([{ ...backup, vmrestoreavailable: false }]))
  await w.vm.openAction('restoreBackup', backup)
  expect(w.vm.selected).toBeNull()
  expect(w.vm.$notifyError).toHaveBeenCalled()
  expect(postAPI).not.toHaveBeenCalled()
  w.unmount()
})
test('original VM restore rules leave existing providers unchanged', async () => {
  const w = mount(); await flush()
  for (const provider of ['nas', 'commvault', 'netbackup', 'veeam', 'kboss']) {
    const legacy = { ...row, provider, vmrestoreavailable: false, isbackupvmexpunged: true }
    expect(w.vm.disabled('restoreBackup', legacy)).toBe(false)
    expect(w.vm.definition('restoreBackup').tooltip(legacy)).toBe('label.backup.restore')
  }
  w.unmount()
})
test('offering removal explains each ABLESTACK providers retention policy', async () => {
  const w = mount(); await flush()
  const action = w.vm.definition('removeVirtualMachineFromBackupOffering')
  for (const backupprovider of ['ablestack-nas', 'ablestack-commvault', 'ablestack-netbackup', 'ablestack-veeam']) {
    expect(action.message({ backupprovider })).toBe('message.backup.offering.remove.preserve')
    expect(action.args({ backupprovider })).toEqual(['virtualmachineid'])
  }
  expect(action.message({ backupprovider: 'nas' })).toBe('message.backup.offering.remove')
  expect(action.args({ backupprovider: 'nas' })).toEqual(['virtualmachineid', 'forced'])
  const backupAction = storage.children.find(item => item.name === 'backup').actions.find(item => item.api === 'removeVirtualMachineFromBackupOffering')
  expect(backupAction.message({ provider: 'ablestack-nas' })).toBe('message.backup.offering.remove.preserve')
  expect(backupAction.message({ provider: 'ablestack-commvault' })).toBe('message.backup.offering.remove.preserve')
  expect(backupAction.mapping.forced.value({ provider: 'ablestack-commvault' })).toBe(false)
  expect(backupAction.mapping.forced.value({ provider: 'commvault' })).toBe(true)
  w.unmount()
})

test('deleting an imported Commvault offering explains BackupSet cleanup', () => {
  const action = offering.children.find(item => item.name === 'backupoffering').actions.find(item => item.api === 'deleteBackupOffering')
  expect(action.message({ provider: 'ablestack-commvault' })).toBe('message.backup.offering.delete.commvault')
  expect(action.message({ provider: 'ablestack-nas' })).toBe('message.action.delete.backup.offering')
  expect(action.message({ provider: 'commvault' })).toBe('message.action.delete.backup.offering')
})
test('Commvault cancel action disappears after the host export submits a Commvault job', async () => {
  const w = mount(); await flush()
  const active = { ...row, status: 'BackingUp', provider: 'ablestack-commvault' }
  expect(w.vm.visible('cancelBackup', { ...active, externalid: '/stage/backup-1' })).toBe(true)
  expect(w.vm.visible('cancelBackup', { ...active, externalid: '/stage/backup-1,12345' })).toBe(false)
  expect(w.vm.visible('cancelBackup', { ...active, status: 'BackedUp' })).toBe(false)
  w.unmount()
})
test.each(['ablestack-commvault', 'ablestack-netbackup', 'ablestack-veeam'])('volume backup controls follow the latest capabilities for %s', async provider => {
  const w = mount(); await flush()
  const active = {
    ...row,
    provider,
    status: 'BackingUp',
    externalid: '/stage/backup-1',
    vmdetails: { 'thirdparty.staging.mode': 'VOLUME' },
    backupjobstep: 'QCOW2_BACKUP',
    capabilities: 'cancel,live-bandwidth,events,log,progress',
    backupjobcapabilities: 'cancel,live-bandwidth'
  }
  expect(w.vm.visible('cancelBackup', active)).toBe(true)
  expect(w.vm.visible('updateBackupJobBandwidth', active)).toBe(true)
  for (const step of [provider.replace('ablestack-', '').toUpperCase() + '_TRANSFER', 'METADATA_TRANSFER', 'FINALIZING']) {
    for (const capabilities of ['', 'events,log,progress']) {
      const transferring = { ...active, backupjobstep: step, capabilities }
      expect(w.vm.visible('cancelBackup', transferring)).toBe(false)
      expect(w.vm.visible('updateBackupJobBandwidth', transferring)).toBe(false)
    }
  }
  // A later volume may be canceled during local generation after the prior transfer finishes.
  expect(w.vm.visible('cancelBackup', { ...active, backupjobstep: 'RBD_EXPORT', capabilities: 'cancel,events,log,progress' })).toBe(true)
  expect(w.vm.visible('cancelBackup', { ...active, capabilities: undefined, backupjobcapabilities: undefined })).toBe(false)
  w.unmount()
})
test('Commvault live bandwidth action is available only during host backup', async () => {
  const w = mount(); await flush()
  const active = { ...row, status: 'BackingUp', provider: 'ablestack-commvault', capabilities: 'live-bandwidth' }
  expect(w.vm.visible('updateBackupJobBandwidth', { ...active, externalid: '/stage/backup-1' })).toBe(true)
  expect(w.vm.visible('updateBackupJobBandwidth', { ...active, externalid: '/stage/backup-1,12345' })).toBe(false)
  expect(w.vm.visible('updateBackupJobBandwidth', { ...active, status: 'BackedUp' })).toBe(false)
  expect(w.vm.visible('updateBackupJobBandwidth', { ...active, capabilities: '' })).toBe(false)
  w.unmount()
})
test('Commvault restore shows the requested phase without a long lived loading message', async () => {
  const backup = { ...row, provider: 'ablestack-commvault' }
  const info = jest.fn()
  getAPI.mockImplementation(api => Promise.resolve(api === 'listVirtualMachines' ? { listvirtualmachinesresponse: { virtualmachine: [{ ...vm }] } } : response([backup])))
  const w = mount({ $message: { info } }); await flush()
  w.vm.selected = backup; w.vm.actionApi = 'restoreBackup'

  await w.vm.submitAction()

  expect(w.vm.$pollJob).toHaveBeenCalledWith(expect.objectContaining({ showLoading: false, showSuccessMessage: false }))
  expect(info).toHaveBeenCalledWith(expect.objectContaining({ content: 'label.backup.restore.requested' }))
  expect(w.vm.rows[0].restorejobstep).toBe('REQUESTED')
  w.unmount()
})
test('defaults to non-forced deletion and rechecks lost permissions before submission', async () => {
  const w = mount(); await flush(); await w.vm.openAction('deleteBackup', row)
  expect(w.vm.forced).toBe(false); delete w.vm.$store.getters.apis.deleteBackup; await w.vm.submitAction()
  expect(postAPI).not.toHaveBeenCalled(); w.unmount()
})
test('server status changes before restore block mutation', async () => {
  const w = mount(); await flush(); await w.vm.openAction('restoreBackup', row)
  getAPI.mockImplementation(api => Promise.resolve(api === 'listBackups' ? response([{ ...row, status: 'Restoring' }]) : { listvirtualmachinesresponse: { virtualmachine: [vm] } }))
  await w.vm.submitAction(); expect(postAPI).not.toHaveBeenCalled(); expect(w.vm.$notifyError).toHaveBeenCalled(); w.unmount()
})
test('non-admin cannot send host override, and successful job refreshes list', async () => {
  const w = mount(); await flush(); await w.vm.openAction('restoreBackup', row)
  w.vm.$store.getters.userInfo.roletype = 'User'; w.vm.quickrestore = true; w.vm.hostId = 'host'
  await w.vm.submitAction()
  expect(postAPI).toHaveBeenCalledWith('restoreBackup', { id: 'backup', quickrestore: true })
  const job = w.vm.$pollJob.mock.calls[0][0]; expect(job.jobId).toBe('job'); job.successMethod(); expect(w.vm.pending).toBe(false); w.unmount()
})
test('unknown submission is blocked instead of resending a mutation', async () => {
  const w = mount(); await flush(); await w.vm.openAction('deleteBackup', row); postAPI.mockResolvedValue({ deletebackupresponse: {} })
  await w.vm.submitAction(); expect(w.vm.unknown).toBe(true); await w.vm.submitAction(); expect(postAPI).toHaveBeenCalledTimes(1); w.unmount()
})
test('VM offering action preserves VM context, component action does not mutate parent resource', async () => {
  const spy = jest.spyOn(eventBus, 'emit'); const w = mount(); await flush()
  await w.vm.openAction('removeVirtualMachineFromBackupOffering')
  expect(spy).toHaveBeenCalledWith('exec-action', expect.objectContaining({ action: expect.objectContaining({ resource: expect.objectContaining({ id: 'vm' }) }) }))
  await w.vm.openAction('createVMFromBackup', row)
  expect(w.vm.selected.id).toBe('backup'); expect(w.vm.resource.id).toBe('vm'); expect(w.vm.modalComponent).toBeTruthy(); w.unmount(); spy.mockRestore()
})
test('search is server paginated and scoped to current VM', async () => {
  const w = mount(); await flush(); w.vm.page = 3; w.vm.search = ' daily '; w.vm.searchBackups(); await flush()
  expect(getAPI).toHaveBeenLastCalledWith('listBackups', expect.objectContaining({ virtualmachineid: 'vm', name: 'daily', page: 1, pagesize: 10, listvmdetails: true })); w.unmount()
})

test('bounded polling interruption marks unknown and retry completion clears it', async () => {
  const w = mount(); await flush(); await w.vm.openAction('restoreBackup', row); await w.vm.submitAction()
  const job = w.vm.$pollJob.mock.calls[0][0]; job.catchMethod({ trackingStatus: 'unknown' })
  expect(w.vm.pending).toBe(false); expect(w.vm.unknown).toBe(true)
  job.successMethod(); expect(w.vm.unknown).toBe(false); w.unmount()
})

test('hides staging management for a cleaned failure and ordinary volume backup', async () => {
  const w = mount(); await flush()
  w.vm.$store.getters.apis.getBackupStagingInfo = {}
  const backup = {
    ...row,
    provider: 'ablestack-commvault',
    status: 'Failed',
    catalogstate: 'PENDING',
    cleanupstate: 'COMPLETED',
    vmdetails: { 'thirdparty.staging.mode': 'VOLUME' },
    stagingqueue: { state: 'RELEASED' },
    stagingmanagementrequired: false
  }
  expect(w.vm.visible('getBackupStagingInfo', backup)).toBe(false)
  expect(w.vm.rowActions(backup).some(action => action.api === 'getBackupStagingInfo')).toBe(false)
  expect(w.vm.visible('getBackupStagingInfo', { ...backup, status: 'BackingUp', cleanupstate: 'NONE', stagingqueue: { state: 'WAITING' } })).toBe(false)
  w.unmount()
})

test('shows staging management only for an ABLESTACK provider with a confirmed recovery flag', async () => {
  const w = mount(); await flush()
  w.vm.$store.getters.apis.getBackupStagingInfo = {}
  for (const provider of ['ablestack-commvault', 'ablestack-netbackup', 'ablestack-veeam']) {
    expect(w.vm.visible('getBackupStagingInfo', { ...row, provider, stagingmanagementrequired: true })).toBe(true)
    expect(w.vm.visible('getBackupStagingInfo', { ...row, provider, stagingmanagementrequired: 'true' })).toBe(false)
  }
  expect(w.vm.visible('getBackupStagingInfo', { ...row, stagingmanagementrequired: true })).toBe(false)
  w.unmount()
})

test('does not open staging management after a fresh lookup confirms recovery is no longer needed', async () => {
  const w = mount(); await flush()
  w.vm.$store.getters.apis.getBackupStagingInfo = {}
  const backup = { ...row, provider: 'ablestack-commvault', stagingmanagementrequired: true }
  getAPI.mockResolvedValue(response([{ ...backup, stagingmanagementrequired: false }]))
  await w.vm.openAction('getBackupStagingInfo', backup)
  expect(w.vm.selected).toBeNull()
  expect(w.vm.$notifyError).toHaveBeenCalled()
  w.unmount()
})
