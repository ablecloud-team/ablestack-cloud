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

function mount () {
  return shallowMount(VmProcessesTab, {
    props: { resource: { id: vmId, name: 'Ubuntu 24', state: 'Running' }, active: true },
    global: {
      mocks: {
        $store: {
          getters: { apis: { getVirtualMachineProcessCapabilities: {}, refreshVirtualMachineProcesses: {}, listVirtualMachineProcesses: {} }, userInfo: { id: 'admin', roletype: 'Admin' }, project: {} },
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
