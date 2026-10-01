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
jest.mock('@/api', () => ({ getAPI: jest.fn(), postAPI: jest.fn() }))
jest.mock('@/store', () => ({ getters: { userInfo: { roletype: 'Admin' }, apis: {}, features: {} } }))
jest.mock('@/utils/listRefresh', () => ({ ...jest.requireActual('@/utils/listRefresh'), canRefreshList: () => true }))
const vm = { id: 'vm', name: 'VM', state: 'Running', zoneid: 'zone', backupofferingid: 'offering', backupprovider: 'kboss' }
const row = { id: 'backup', name: 'daily', virtualmachineid: 'vm', status: 'BackedUp', provider: 'kboss' }
const response = rows => ({ listbackupsresponse: { backup: rows, count: rows.length } })
const flush = async () => { for (let i = 0; i < 30; i++) await Promise.resolve() }
const apis = Object.fromEntries(['listBackups', 'listVirtualMachines', 'restoreBackup', 'deleteBackup', 'createBackup', 'assignVirtualMachineToBackupOffering', 'createBackupSchedule', 'removeVirtualMachineFromBackupOffering', 'createVMFromBackup', 'restoreVolumeFromBackupAndAttachToVM'].map(api => [api, {}]))
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
