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

import Widget from '@/views/storage/StorageVolumePreparation'
import { getAPI } from '@/api'
jest.mock('@/api', () => ({ getAPI: jest.fn() }))
const reply = status => ({ getstorageservicevolumepreparationresponse: { storageserviceruntime: { success: true, resultjson: JSON.stringify({ status, operationId: 'volume-v', operation: { phase: status, devicePath: '/dev/sdc' }, currentIdentity: { observedDevicePath: '/dev/sdb' } }) } } })
function instance () {
  const vm = { ...Widget.data(), instanceId: 'a', selectedVolume: 'v' }
  vm.operation = {}
  vm.refresh = () => Widget.methods.refresh.call(vm)
  vm.invalidate = () => Widget.methods.invalidate.call(vm)
  return vm
}
describe('Durable volume preparation observation', () => {
  beforeEach(() => { getAPI.mockReset(); jest.useFakeTimers() })
  afterEach(() => { jest.clearAllTimers(); jest.useRealTimers() })
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
