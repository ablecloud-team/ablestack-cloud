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
import { h, reactive } from 'vue'
import VmSnapshotDialog from '@/views/compute/VmSnapshotDialog'
import ko from '@public/locales/ko_KR.json'
import { getAPI, postAPI } from '@/api'
import { clearSnapshotJobs, snapshotBusy } from '@/utils/vmSnapshotActions'
jest.mock('@/api', () => ({ getAPI: jest.fn(), postAPI: jest.fn() }))
jest.mock('@/config/section/compute', () => ({ children: [{ name: 'vm', actions: [{ api: 'createVMSnapshot', show: () => true, disabled: () => false }] }] }))
const row = { id: 'b', virtualmachineid: 'vb', displayname: 'B', type: 'Disk', state: 'Ready', virtualmachinestate: 'Stopped' }
const flush = async () => { for (let i = 0; i < 25; i++) await Promise.resolve() }
let vmState
let poll
function mount (mode = 'delete', targets = [Object.freeze({ ...row })], renderContent = false) {
  return shallowMount(VmSnapshotDialog, {
    props: { resource: row, currentAction: { snapshotMode: mode, snapshotTargets: Object.freeze(targets) } },
    global: {
      mocks: { $store: reactive({ getters: { apis: { listVMSnapshot: {}, listVirtualMachines: {}, revertToVMSnapshot: {}, deleteVMSnapshot: {} }, userInfo: { id: 'user' }, project: {} }, state: { user: { token: 'token' } } }), $route: { path: '/vmsnapshot', fullPath: '/vmsnapshot' }, $t: key => renderContent ? ko[key] || key : key, $toLocaleDate: value => value, $notifyError: jest.fn(), $pollJob: poll },
      stubs: renderContent ? {
        MoldDialog: { template: '<section><slot /><footer><slot name="footer" /></footer></section>' },
        'a-spin': { template: '<div><slot /></div>' },
        'a-descriptions': { template: '<dl><slot /></dl>' },
        'a-descriptions-item': { props: ['label'], render () { return h('div', { 'data-label': this.label }, [h('dt', this.label), h('dd', this.$slots.default?.())]) } },
        'a-table': { props: ['columns', 'dataSource'], render () { return h('table', this.dataSource.map(record => h('tr', this.columns.map(column => h('td', this.$slots.bodyCell({ column, record })))))) } }
      } : {}
    }
  })
}
beforeEach(() => {
  jest.clearAllMocks(); clearSnapshotJobs(); vmState = 'Stopped'
  poll = jest.fn().mockResolvedValue({ jobstatus: 1 })
  getAPI.mockImplementation((api, args) => Promise.resolve(api === 'listVirtualMachines' ? { listvirtualmachinesresponse: { virtualmachine: [{ id: 'vb', state: vmState }] } } : { listvmsnapshotresponse: { vmSnapshot: args.state ? [] : [row], count: args.state ? 0 : 1 } }))
  postAPI.mockResolvedValue({ deletevmsnapshotresponse: { jobid: 'job-b' }, reverttovmsnapshotresponse: { jobid: 'job-b' } })
})

test('captured B remains the submitted UUID after resource refresh and requires acknowledgement', async () => {
  const wrapper = mount(); await flush()
  await wrapper.vm.submit()
  expect(postAPI).not.toHaveBeenCalled()
  await wrapper.setProps({ resource: { ...row, id: 'a', virtualmachineid: 'va' } })
  wrapper.vm.acknowledged = true
  await wrapper.vm.submit()
  expect(postAPI).toHaveBeenCalledWith('deleteVMSnapshot', { vmsnapshotid: 'b' })
  expect(wrapper.vm.results[0].id).toBe('b')
  wrapper.unmount()
})

test('VM state changed after confirmation blocks Disk restore before POST', async () => {
  const wrapper = mount('restore'); await flush()
  expect(wrapper.vm.blockedReason).toBe('')
  vmState = 'Running'; wrapper.vm.acknowledged = true
  await wrapper.vm.submit()
  expect(wrapper.vm.blockedReason).toBe('message.vmsnapshot.stop.first')
  expect(postAPI).not.toHaveBeenCalled()
  wrapper.unmount()
})

test('double submit sends one job and unknown result stays distinct from success', async () => {
  poll.mockResolvedValue({ jobstatus: null, trackingStatus: 'unknown' })
  const wrapper = mount(); await flush(); wrapper.vm.acknowledged = true
  await Promise.all([wrapper.vm.submit(), wrapper.vm.submit()])
  expect(postAPI).toHaveBeenCalledTimes(1)
  expect(wrapper.vm.results[0].outcome).toBe('unknown')
  wrapper.unmount()
})

test('ACL withdrawal at fresh lookup prevents every captured target from starting', async () => {
  const wrapper = mount(); await flush(); wrapper.vm.acknowledged = true
  getAPI.mockResolvedValue({ listvmsnapshotresponse: { count: 0 }, listvirtualmachinesresponse: {} })
  await wrapper.vm.submit()
  expect(wrapper.vm.blockedReason).toBe('message.vmsnapshot.not.ready')
  expect(postAPI).not.toHaveBeenCalled()
  wrapper.unmount()
})

test('project changes during the last fresh lookup prevent POST', async () => {
  const wrapper = mount(); await flush()
  const security = wrapper.vm.security
  const original = getAPI.getMockImplementation()
  getAPI.mockImplementation((api, args) => {
    if (api === 'listVirtualMachines') wrapper.vm.$store.getters.project.id = 'different-project'
    return original(api, args)
  })
  await expect(wrapper.vm.execute(row, security)).rejects.toThrow('message.vmsnapshot.permission')
  expect(postAPI).not.toHaveBeenCalled()
  wrapper.unmount()
})

test.each(['lost response', 'missing job ID'])('unconfirmed %s remains unknown and blocks the VM', async kind => {
  if (kind === 'lost response') postAPI.mockRejectedValue(Object.assign(new Error('Network Error'), { isAxiosError: true }))
  else postAPI.mockResolvedValue({ deletevmsnapshotresponse: {} })
  const wrapper = mount(); await flush(); wrapper.vm.acknowledged = true
  await wrapper.vm.submit()
  expect(postAPI).toHaveBeenCalledTimes(1)
  expect(wrapper.vm.results[0].outcome).toBe('unknown')
  expect(snapshotBusy(row.virtualmachineid)).toBe(true)
  wrapper.unmount()
})

const errorRow = { ...row, state: 'Error', type: 'DiskAndMemory', forcedeletionallowed: true }
function errorLookup (allowed = true) {
  getAPI.mockImplementation((api, args) => Promise.resolve(api === 'listVirtualMachines' ? { listvirtualmachinesresponse: { virtualmachine: [{ id: 'vb', state: 'Running' }] } } : { listvmsnapshotresponse: { vmSnapshot: args.state ? [] : [{ ...errorRow, forcedeletionallowed: allowed }], count: args.state ? 0 : 1 } }))
}

test('administrator Error recovery uses the existing dialog, renewed confirmation and exactly one force request', async () => {
  errorLookup()
  const wrapper = mount('delete', [errorRow]); wrapper.vm.$store.getters.userInfo.roletype = 'Admin'; await flush()
  expect(wrapper.vm.forceAvailable).toBe(true)
  wrapper.vm.acknowledged = true; wrapper.vm.force = true; await flush()
  expect(wrapper.vm.acknowledged).toBe(false)
  wrapper.vm.acknowledged = true
  await Promise.all([wrapper.vm.submit(), wrapper.vm.submit()])
  expect(postAPI).toHaveBeenCalledTimes(1)
  expect(postAPI).toHaveBeenCalledWith('deleteVMSnapshot', { vmsnapshotid: 'b', force: true })
  wrapper.unmount()
})

test.each(['User', 'DomainAdmin'])('force option is hidden for %s even with a stale eligible response', async role => {
  errorLookup()
  const wrapper = mount('delete', [errorRow]); wrapper.vm.$store.getters.userInfo.roletype = role; await flush()
  expect(wrapper.vm.forceAvailable).toBe(false)
  wrapper.vm.force = true; await flush(); wrapper.vm.acknowledged = true; await wrapper.vm.submit()
  expect(postAPI).not.toHaveBeenCalled(); wrapper.unmount()
})

test('capability withdrawn at the fresh lookup blocks force and retains a meaningful failure', async () => {
  errorLookup()
  const wrapper = mount('delete', [errorRow]); wrapper.vm.$store.getters.userInfo.roletype = 'Admin'; await flush()
  wrapper.vm.force = true; await flush(); wrapper.vm.acknowledged = true; errorLookup(false)
  await wrapper.vm.submit()
  expect(postAPI).not.toHaveBeenCalled()
  expect(wrapper.vm.results[0].error).toContain('message.vmsnapshot.force.delete.unavailable')
  wrapper.unmount()
})

test('batch recovery option is hidden and normal Error deletion omits force', async () => {
  errorLookup()
  const wrapper = mount('delete', [errorRow, { ...errorRow, id: 'c' }]); wrapper.vm.$store.getters.userInfo.roletype = 'Admin'; await flush()
  expect(wrapper.vm.forceAvailable).toBe(false); wrapper.unmount()
  const single = mount('delete', [errorRow]); await flush(); single.vm.acknowledged = true; await single.vm.submit()
  expect(postAPI).toHaveBeenCalledWith('deleteVMSnapshot', { vmsnapshotid: 'b' }); single.unmount()
})

test('failed recovery job retains the backend explanation in results', async () => {
  errorLookup(); poll.mockResolvedValue({ jobstatus: 2, jobresult: { errortext: 'Native VM job is active or unknown' } })
  const wrapper = mount('delete', [errorRow]); await flush(); wrapper.vm.acknowledged = true; await wrapper.vm.submit()
  expect(wrapper.vm.results[0].outcome).toBe('failed')
  expect(wrapper.vm.results[0].error).toBe('Native VM job is active or unknown')
  wrapper.unmount()
})

describe('delete dialog summary', () => {
  const targets = [
    { ...row, displayname: 'Captured B', virtualmachinename: 'Old VM B' },
    { ...row, id: 'c', virtualmachineid: 'vc', displayname: 'Captured C', virtualmachinename: 'Old VM C' },
    { ...row, id: 'd', virtualmachineid: 'vc', displayname: 'Captured D', virtualmachinename: 'Old VM C' }
  ]
  let snapshots
  let vms
  beforeEach(() => {
    snapshots = [
      { ...targets[2], displayname: 'Fresh D', created: '2026-10-03T09:00:00Z', type: 'Disk', current: false, parentName: 'Fresh C' },
      { ...targets[0], displayname: 'Fresh B', created: '2026-10-01T09:00:00Z', type: 'DiskAndMemory', current: true },
      { ...targets[1], displayname: 'Fresh C', created: '2026-10-02T09:00:00Z', type: 'Disk', current: false, parentName: 'Ancestor C' }
    ]
    vms = [{ id: 'vc', displayname: 'VM C', state: 'Stopped' }, { id: 'vb', displayname: 'VM B', state: 'Stopped' }]
    getAPI.mockImplementation((api, args) => Promise.resolve(api === 'listVirtualMachines' ? { listvirtualmachinesresponse: { virtualmachine: vms } } : { listvmsnapshotresponse: { vmSnapshot: args.state ? [] : snapshots, count: args.state ? 0 : snapshots.length } }))
  })

  test('three snapshots on two VMs show aggregate counts and their own fresh metadata despite response order', async () => {
    const wrapper = mount('delete', targets, true); await flush()
    expect(wrapper.findComponent({ name: 'VmSnapshotSummary' }).exists()).toBe(false)
    expect(wrapper.get('[data-label="VM 수"] dd').text()).toBe('2')
    expect(wrapper.get('[data-label="선택한 VM 스냅샷 수"] dd').text()).toBe('3')
    const cells = wrapper.findAll('tr').map(tr => tr.findAll('td').map(td => td.text()))
    expect(cells).toEqual([
      ['VM B', 'Fresh B', '2026-10-01T09:00:00Z', '디스크 + 메모리', '현재 기준점', '—', '실행 가능'],
      ['VM C', 'Fresh C', '2026-10-02T09:00:00Z', '디스크', '—', 'Ancestor C', '실행 가능'],
      ['VM C', 'Fresh D', '2026-10-03T09:00:00Z', '디스크', '—', 'Fresh C', '실행 가능']
    ])
    snapshots[0].parentName = 'New parent'; vms[0].displayname = 'Renamed VM C'
    await wrapper.vm.refreshContexts(); await flush()
    expect(wrapper.findAll('tr')[2].text()).toContain('Renamed VM C')
    expect(wrapper.findAll('tr')[2].text()).toContain('New parent')
    expect(wrapper.vm.targets.map(target => target.id)).toEqual(['b', 'c', 'd'])
    wrapper.unmount()
  })

  test('an unavailable target stays counted and cannot inherit the first snapshot metadata or eligibility', async () => {
    snapshots = snapshots.filter(snapshot => snapshot.id !== 'c')
    const wrapper = mount('delete', targets, true); await flush()
    const missing = wrapper.findAll('tr')[1].findAll('td').map(td => td.text())
    expect(missing.slice(0, 6)).toEqual(['VM C', 'Captured C', '—', '—', '—', '—'])
    expect(missing[6]).toBe(ko['message.vmsnapshot.not.ready'])
    expect(wrapper.get('[data-label="선택한 VM 스냅샷 수"] dd').text()).toBe('3')
    wrapper.vm.acknowledged = true; await wrapper.vm.submit()
    expect(postAPI).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  test.each(['delete', 'restore'])('single %s keeps its own existing summary', async mode => {
    const wrapper = mount(mode, [targets[0]], true); await flush()
    expect(wrapper.find('dl').exists()).toBe(false)
    expect(wrapper.find('table').exists()).toBe(false)
    expect(wrapper.findComponent({ name: 'VmSnapshotSummary' }).props('snapshot').id).toBe('b')
    wrapper.unmount()
  })
})
