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

import { shallowMount } from '@vue/test-utils'
import Widget from '@/views/storage/StorageVolumePreparation'
import { getAPI, postAPI } from '@/api'
jest.mock('@/api', () => ({ getAPI: jest.fn(), postAPI: jest.fn() }))
const reply = status => ({ getstorageservicevolumepreparationresponse: { storageserviceruntime: { success: true, resultjson: JSON.stringify({ status, operationId: 'volume-v', operation: { phase: status, devicePath: '/dev/sdc' }, currentIdentity: { observedDevicePath: '/dev/sdb' } }) } } })
function instance () {
  const vm = { ...Widget.data(), instanceId: 'a', selectedVolume: 'v' }
  vm.operation = {}
  vm.refresh = () => Widget.methods.refresh.call(vm)
  vm.invalidate = () => Widget.methods.invalidate.call(vm)
  return vm
}
describe('Durable volume preparation observation', () => {
  beforeEach(() => { getAPI.mockReset(); postAPI.mockReset(); jest.useFakeTimers() })
  afterEach(() => { jest.clearAllTimers(); jest.useRealTimers() })
  it('renders an initial empty observation without reading null diagnostics', () => {
    const wrapper = shallowMount(Widget, { props: { instanceId: 'a', volumes: [] }, global: { mocks: { $t: value => value, $store: { getters: { apis: {} } } }, stubs: ['a-space', 'a-select', 'a-select-option', 'a-button', 'a-switch', 'a-alert', 'a-descriptions', 'a-descriptions-item'] } })
    expect(wrapper.find('.storage-volume-preparation').exists()).toBe(true)
    expect(wrapper.find('h4').text()).toBe('label.storage.volume.preparation')
    wrapper.unmount()
  })
  it('blocks resume without exact observed identity or while formatting is active', () => {
    const vm = { resumeSupported: true, readError: false, observation: { currentIdentityStatus: 'EXACT', formatterActive: true }, operation: { formatStarted: true, formatterExitCode: 0, formatterSuccessReceipt: { schemaVersion: 1, formatterExitCode: 0 } }, identity: { filesystemUuid: 'known' } }
    expect(Widget.computed.canResume.call(vm)).toBe(false)
    vm.observation.formatterActive = false; expect(Widget.computed.canResume.call(vm)).toBe(true)
    vm.operation.formatterSuccessReceipt = null; expect(Widget.computed.canResume.call(vm)).toBe(false)
    vm.operation.formatterSuccessReceipt = { schemaVersion: 1, formatterExitCode: 0 }
    vm.operation.filesystemUuid = 'other'; expect(Widget.computed.canResume.call(vm)).toBe(false)
    vm.operation.filesystemUuid = 'known'; vm.observation.currentIdentityStatus = 'UNAVAILABLE'; expect(Widget.computed.canResume.call(vm)).toBe(false)
  })
  it('uses the existing operation identity and never sends a formatting mode', async () => {
    const vm = { ...instance(), canResume: true, operation: {}, identity: { filesystemUuid: 'known-fs' }, observation: { operationId: 'volume-v' }, resumeKey: 'same-request', refresh: jest.fn(), $emit: jest.fn(), $t: value => value }
    postAPI.mockResolvedValue({ resumestorageservicevolumepreparationresponse: { jobid: 'job' } })
    getAPI.mockResolvedValue({ queryasyncjobresultresponse: { jobstatus: 1 } })
    await Widget.methods.resume.call(vm)
    expect(postAPI.mock.calls[0][1]).toEqual({ instanceid: 'a', volumeid: 'v', operationid: 'volume-v', expectedfilesystemuuid: 'known-fs', idempotencykey: 'same-request' })
    expect(vm.$emit).toHaveBeenCalledWith('operation-updated', 'a')
    expect(vm.resuming).toBe(false)
  })
  it('keeps a failed resume visible and does not mark it complete', async () => {
    const vm = { ...instance(), canResume: true, operation: {}, identity: { filesystemUuid: 'known-fs' }, observation: { operationId: 'volume-v' }, resumeVisible: true, refresh: jest.fn(), $emit: jest.fn(), $t: value => value }
    postAPI.mockResolvedValue({ resumestorageservicevolumepreparationresponse: { jobid: 'job' } })
    getAPI.mockResolvedValue({ queryasyncjobresultresponse: { jobstatus: 2, jobresult: { errortext: 'Manual recovery required' } } })
    await Widget.methods.resume.call(vm)
    expect(vm.resumeVisible).toBe(true); expect(vm.resumeError).toBe('Manual recovery required'); expect(vm.$emit).not.toHaveBeenCalled()
  })
  it('keeps historical and fresh device identities separate', async () => {
    const vm = instance(); getAPI.mockResolvedValue(reply('COMPLETE')); await vm.refresh()
    expect(vm.observation.operation.devicePath).toBe('/dev/sdc')
    expect(vm.observation.currentIdentity.observedDevicePath).toBe('/dev/sdb')
    expect(getAPI).toHaveBeenCalledWith('getStorageServiceVolumePreparation', { instanceid: 'a', volumeid: 'v' }, { timeout: 15000, preserveOnFailure: true })
  })
  it('ignores a delayed read after switching volumes', async () => {
    let done; getAPI.mockImplementation(() => new Promise(resolve => { done = resolve }))
    const vm = instance(); const pending = vm.refresh()
    vm.invalidate(); vm.selectedVolume = 'new-v'
    done(reply('COMPLETE')); await pending
    expect(vm.observation).toBeNull(); expect(vm.loading).toBe(false)
  })
  it('preserves the previous observation on failure and stops automatic polling', async () => {
    const vm = instance(); vm.observation = { status: 'FORMATTING' }; vm.automatic = true
    getAPI.mockRejectedValue(new Error('timeout')); await vm.refresh()
    expect(vm.observation.status).toBe('FORMATTING'); expect(vm.readError).toBe(true); expect(vm.timer).toBeNull()
  })
  it('never overlaps reads and only schedules a running operation', async () => {
    let done; getAPI.mockImplementation(() => new Promise(resolve => { done = resolve }))
    const vm = instance(); vm.automatic = true
    const pending = vm.refresh(); await vm.refresh(); expect(getAPI).toHaveBeenCalledTimes(1)
    done(reply('FORMATTING')); await pending
    expect(vm.timer).not.toBeNull()
    vm.invalidate(); getAPI.mockResolvedValue(reply('RECOVERY_REQUIRED')); await vm.refresh()
    expect(vm.timer).toBeNull()
  })
  it('rejects a foreign volume journal and malformed successful payload', async () => {
    const vm = instance(); vm.observation = { status: 'FORMATTING' }
    getAPI.mockResolvedValue({ getstorageservicevolumepreparationresponse: { success: true, resultjson: '{"status":"COMPLETE","operation":{"volumeUuid":"foreign"}}' } })
    await vm.refresh(); expect(vm.observation.status).toBe('FORMATTING'); expect(vm.readError).toBe(true)
    getAPI.mockResolvedValue({ getstorageservicevolumepreparationresponse: { success: true, resultjson: 'invalid-json' } })
    await vm.refresh(); expect(vm.readError).toBe(true); expect(vm.observation.status).toBe('FORMATTING')
  })
  it('does not publish a response or schedule a poll after unmount', async () => {
    let done; getAPI.mockImplementation(() => new Promise(resolve => { done = resolve }))
    const vm = instance(); vm.automatic = true; const pending = vm.refresh()
    Widget.beforeUnmount.call(vm); done(reply('FORMATTING')); await pending
    expect(vm.observation).toBeNull(); expect(vm.timer).toBeNull()
  })
})
