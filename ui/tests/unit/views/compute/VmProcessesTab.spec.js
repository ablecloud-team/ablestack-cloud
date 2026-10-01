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
import { getAPI, postAPI } from '@/api'
import VmProcessesTab from '@/views/compute/VmProcessesTab.vue'
import { requiredRpcs } from '@/views/compute/vmProcessDisplay'

jest.mock('@/api', () => ({ getAPI: jest.fn(), postAPI: jest.fn() }))

const vmId = '04125c04-cfa5-4f43-be41-39e3c7d536d3'
const capability = {
  readiness: 'TOOLS_REQUIRED',
  os: { id: 'ubuntu', family: 'linux', version: '24.04' },
  rpcs: Object.fromEntries(requiredRpcs.map(rpc => [rpc, 'ENABLED']))
}
const flush = async () => { for (let i = 0; i < 30; i++) await Promise.resolve() }
const response = (command, body) => ({ [command.toLowerCase() + 'response']: body })

function mount (mutationApis = {}) {
  return shallowMount(VmProcessesTab, {
    props: { resource: { id: vmId, name: 'Ubuntu 24', state: 'Running' }, active: true },
    global: {
      stubs: { 'a-tag': { template: '<span><slot /></span>' } },
      mocks: {
        $store: {
          getters: { apis: { getVirtualMachineProcessCapabilities: {}, refreshVirtualMachineProcesses: {}, listVirtualMachineProcesses: {}, ...mutationApis }, userInfo: { id: 'admin', roletype: 'Admin' }, project: {} },
          state: { user: { token: 'token' } }
        },
        $t: key => key,
        $toLocaleDate: value => value
      }
    }
  })
}

beforeEach(() => { jest.clearAllMocks(); jest.useFakeTimers(); sessionStorage.clear() })
afterEach(() => jest.useRealTimers())

test('waits for the first process collection before showing a Tools warning', async () => {
  let finishRefresh
  getAPI.mockImplementation(command => {
    if (command === 'getVirtualMachineProcessCapabilities') return Promise.resolve(response(command, { processcapability: { processstate: capability } }))
    if (command === 'queryAsyncJobResult') return Promise.resolve(response(command, { jobstatus: 1, jobresult: { processsnapshot: { processstate: { kind: 'snapshot', authority: { vmUuid: vmId }, snapshotId: 'snapshot-1', observedAt: '2026-09-29T12:00:00Z' } } } }))
    return Promise.resolve(response(command, { processsnapshot: { stale: false, processstate: { kind: 'snapshot', authority: { vmUuid: vmId }, snapshotId: 'snapshot-1', processes: [{ name: 'systemd', identity: { vmUuid: vmId, pid: 1, startTicks: 1 } }] } }, count: 1 }))
  })
  postAPI.mockImplementation(() => new Promise(resolve => { finishRefresh = resolve }))
  const wrapper = mount()
  await flush()
  expect(wrapper.vm.capability.readiness).toBe('TOOLS_REQUIRED')
  expect(wrapper.vm.initializing).toBe(true)
  expect(wrapper.vm.diagnostic).toBeNull()
  expect(wrapper.text()).not.toContain('message.vmprocess.status.tools')

  finishRefresh(response('refreshVirtualMachineProcesses', { jobid: 'job-1' }))
  await flush()
  expect(wrapper.vm.initializing).toBe(false)
  expect(wrapper.vm.snapshotId).toBe('snapshot-1')
  expect(wrapper.vm.rows).toHaveLength(1)
  expect(wrapper.vm.diagnostic).toBeNull()
  wrapper.unmount()
})

test('shows the verified Tools warning when the first collection fails', async () => {
  getAPI.mockImplementation(command => command === 'getVirtualMachineProcessCapabilities'
    ? Promise.resolve(response(command, { processcapability: { processstate: capability } }))
    : Promise.resolve(response(command, { jobstatus: 1, jobresult: { processsnapshot: { processstate: { kind: 'failure', authority: { vmUuid: vmId }, error: { code: 'CHECK_FAILED' } } } } })))
  postAPI.mockResolvedValue(response('refreshVirtualMachineProcesses', { jobid: 'job-1' }))
  const wrapper = mount()
  await flush()
  expect(wrapper.vm.initializing).toBe(false)
  expect(wrapper.vm.snapshotId).toBeNull()
  expect(wrapper.vm.diagnostic).toEqual({ kind: 'tools', install: true })
  wrapper.unmount()
})

test('partial pages retain their rows but never claim a complete process list', async () => {
  let status = 'PARTIAL'
  getAPI.mockImplementation(command => {
    if (command === 'getVirtualMachineProcessCapabilities') return Promise.resolve(response(command, { processcapability: { processstate: capability } }))
    if (command === 'queryAsyncJobResult') return Promise.resolve(response(command, { jobstatus: 1, jobresult: { processsnapshot: { processstate: { kind: 'snapshot', authority: { vmUuid: vmId }, snapshotId: 'snapshot-1' } } } }))
    return Promise.resolve(response(command, { processsnapshot: { stale: false, count: 1, processstate: { kind: 'snapshot', status, authority: { vmUuid: vmId }, snapshotId: 'snapshot-1', processes: [{ name: 'fixture', identity: { pid: 3 } }] } } }))
  })
  postAPI.mockResolvedValue(response('refreshVirtualMachineProcesses', { jobid: 'job-1' }))
  const wrapper = mount()
  await flush()
  expect(wrapper.vm.partialSnapshot).toBe(true)
  expect(wrapper.vm.rows).toHaveLength(1)
  expect(wrapper.html()).toContain('message.vmprocess.snapshot.partial')
  expect(wrapper.text()).toContain('label.vmprocess.collected')
  expect(wrapper.text()).not.toContain('label.vmprocess.snapshot.ready')
  status = 'OK'
  await wrapper.vm.loadPage()
  await flush()
  expect(wrapper.vm.partialSnapshot).toBe(false)
  expect(wrapper.html()).not.toContain('message.vmprocess.snapshot.partial')
  expect(wrapper.text()).toContain('label.vmprocess.snapshot.ready')
  status = 'PARTIAL'
  await wrapper.vm.loadPage()
  getAPI.mockRejectedValueOnce(new Error('API unavailable'))
  await wrapper.vm.checkCapability(false)
  await flush()
  expect(wrapper.vm.snapshotId).toBe('snapshot-1')
  expect(wrapper.vm.rows).toHaveLength(1)
  expect(wrapper.vm.errorText).toBe('API unavailable')
  expect(wrapper.html()).toContain('message.vmprocess.snapshot.partial')
  wrapper.unmount()
})

test('read-only readiness never enables mutations and SCM processes cannot be force-killed', async () => {
  const wrapper = mount({ killVirtualMachineProcess: {}, restartVirtualMachineService: {} })
  await flush()
  const service = { manager: 'scm', name: 'AbleC7Ui' }
  const row = { identity: { vmUuid: vmId, pid: 500 }, services: [] }
  await wrapper.setData({ capability: { ...capability, os: { family: 'windows' }, allowedActions: ['process.list'] }, initializing: false })
  expect(wrapper.vm.actionAvailable('process.kill', row)).toBe(false)
  await wrapper.setData({ capability: { ...wrapper.vm.capability, allowedActions: ['process.list', 'process.kill', 'service.restart'] } })
  expect(wrapper.vm.actionAvailable('process.kill', row)).toBe(true)
  expect(wrapper.vm.actionAvailable('process.kill', { ...row, services: [service] })).toBe(false)
  expect(wrapper.vm.actionAvailable('service.restart', { ...row, services: [service] }, service)).toBe(true)
  expect(wrapper.vm.actionAvailable('process.kill', { ...row, identity: { vmUuid: vmId, pid: 4 } })).toBe(false)
  wrapper.unmount()
})

test('profile restart is enabled only for an approved matching VM identity', async () => {
  getAPI.mockResolvedValue({})
  const wrapper = mount({ restartVirtualMachineProcess: {}, manageVirtualMachineProcessProfile: {} })
  await flush()
  const row = { name: 'fixture', identity: { vmUuid: vmId, bootId: 'boot', pid: 22, startTicks: '999' }, services: [] }
  wrapper.vm.capability = { ...capability, readiness: 'READY', allowedActions: ['process.kill'] }
  wrapper.vm.profilesAvailable = true
  wrapper.vm.profiles = [{ id: 'profile', version: 1, definitionHash: 'hash', displayName: 'approved', registrationState: 'REGISTERED', identity: { ...row.identity } }]
  expect(wrapper.vm.rowProfiles(row)).toEqual([])
  wrapper.vm.profiles[0].registrationState = 'APPROVED'
  expect(wrapper.vm.actionAvailable('process.restart', row, wrapper.vm.profiles[0])).toBe(true)
  wrapper.vm.selected = row
  expect(wrapper.vm.primaryAction).toBe('process.restart')
  wrapper.vm.profiles[0].identity.vmUuid = 'other-vm'
  expect(wrapper.vm.actionAvailable('process.restart', row, wrapper.vm.profiles[0])).toBe(false)
  wrapper.vm.profiles[0].identity = { ...row.identity, startTicks: '998' }
  expect(wrapper.vm.rowProfiles(row)).toEqual([])
  wrapper.unmount()
})

test('timer and manual refresh cannot overlap capability collection', async () => {
  let finishCapability
  getAPI.mockImplementation(command => {
    if (command === 'getVirtualMachineProcessCapabilities') return new Promise(resolve => { finishCapability = resolve })
    return Promise.resolve({})
  })
  const wrapper = mount()
  await flush()
  jest.advanceTimersByTime(9000)
  await wrapper.vm.refreshAll()
  expect(getAPI.mock.calls.filter(call => call[0] === 'getVirtualMachineProcessCapabilities')).toHaveLength(1)
  expect(postAPI).not.toHaveBeenCalled()
  finishCapability(response('getVirtualMachineProcessCapabilities', { processcapability: { processstate: { ...capability, rpcs: {} } } }))
  await flush()
  expect(wrapper.vm.refreshing).toBe(false)
  wrapper.unmount()
})

test('periodic refresh updates profile binding after collecting the new process identity', async () => {
  getAPI.mockResolvedValue({})
  const wrapper = mount()
  await flush()
  wrapper.vm.capability = capability
  wrapper.vm.initializing = false
  wrapper.vm.refreshing = false
  const sequence = []
  wrapper.vm.checkCapability = jest.fn(async () => { sequence.push('capability') })
  wrapper.vm.refreshSnapshot = jest.fn(async () => { sequence.push('snapshot'); return true })
  wrapper.vm.fetchProfiles = jest.fn(async () => { sequence.push('profiles') })
  await wrapper.vm.refreshAll()
  expect(sequence).toEqual(['capability', 'snapshot', 'profiles'])
  expect(wrapper.vm.refreshing).toBe(false)
  wrapper.unmount()
})

test('restart retries only the read verification and submits the mutation once', async () => {
  Object.defineProperty(window, 'crypto', { configurable: true, value: { getRandomValues: bytes => { bytes.fill(7); return bytes } } })
  getAPI.mockResolvedValue({})
  const wrapper = mount({ restartVirtualMachineProcess: {} })
  await flush()
  const row = { name: 'fixture', identity: { vmUuid: vmId, bootId: 'boot', pid: 22, startTicks: '999' }, services: [] }
  const profile = { id: 'profile', version: 1, definitionHash: 'hash', registrationState: 'APPROVED', identity: { ...row.identity } }
  await wrapper.setData({ capability: { ...capability, allowedActions: ['process.kill'] }, profilesAvailable: true, profiles: [profile], initializing: false, refreshing: false, rows: [row], snapshotId: 'fresh-snapshot', confirm: { action: 'process.restart', row, service: profile } })
  wrapper.vm.refreshSnapshot = jest.fn().mockResolvedValueOnce(false).mockResolvedValueOnce(true)
  postAPI.mockResolvedValueOnce(response('restartVirtualMachineProcess', { jobid: 'restart-job' }))
  getAPI.mockResolvedValueOnce(response('queryAsyncJobResult', { jobstatus: 2 }))
  wrapper.vm.checkOperation = jest.fn(async () => {})
  await wrapper.vm.submitAction()
  expect(wrapper.vm.refreshSnapshot).toHaveBeenCalledTimes(2)
  expect(postAPI).toHaveBeenCalledTimes(1)
  expect(postAPI.mock.calls[0][0]).toBe('restartVirtualMachineProcess')
  expect(postAPI.mock.calls[0][1]).toMatchObject({ pid: 22, snapshotid: 'fresh-snapshot', profileid: 'profile', profileversion: 1 })
  wrapper.unmount()
})

test('failed read verification preserves the target and reports a read failure', async () => {
  getAPI.mockResolvedValue({})
  const wrapper = mount({ killVirtualMachineProcess: {} })
  await flush()
  const row = { name: 'fixture', identity: { vmUuid: vmId, bootId: 'boot', pid: 22, startTicks: '999' }, services: [] }
  await wrapper.setData({ capability: { ...capability, allowedActions: ['process.kill'] }, initializing: false, refreshing: false, ack: true, confirm: { action: 'process.kill', row, service: null } })
  wrapper.vm.refreshSnapshot = jest.fn().mockResolvedValue(false)
  await wrapper.vm.submitAction()
  expect(postAPI).not.toHaveBeenCalled()
  expect(wrapper.vm.errorText).toBe('message.vmprocess.snapshot.refresh.failed')
  expect(wrapper.vm.confirm.row.identity.pid).toBe(22)
  wrapper.unmount()
})
