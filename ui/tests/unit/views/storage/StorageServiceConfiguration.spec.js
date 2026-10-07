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

import Widget from '@/views/storage/StorageServiceConfiguration'
import { getAPI, postAPI } from '@/api'
import SHA from 'sha.js'
jest.mock('@/api', () => ({ getAPI: jest.fn(), postAPI: jest.fn() }))

describe('Configuration backup and restore UI boundaries', () => {
  beforeEach(() => { getAPI.mockReset(); postAPI.mockReset() })
  it('preserves known backup rows if an independent refresh fails', async () => {
    const vm = { instanceId: 'a', generation: 0, rows: [{ id: 'known' }], can: () => true, unwrap: Widget.methods.unwrap }
    getAPI.mockRejectedValue(new Error('timeout')); await Widget.methods.refresh.call(vm)
    expect(vm.rows).toEqual([{ id: 'known' }]); expect(vm.readFailed).toBe(true)
  })
  it('does not publish an old service history on a new service', async () => {
    let complete
    getAPI.mockImplementation(() => new Promise(resolve => { complete = resolve }))
    const vm = { instanceId: 'a', generation: 0, rows: [{ id: 'current' }], can: api => api === 'listStorageServiceConfigBackups', unwrap: Widget.methods.unwrap }
    const pending = Widget.methods.refresh.call(vm)
    vm.instanceId = 'b'; vm.generation++
    complete({ liststorageserviceconfigbackupsresponse: { result: JSON.stringify({ artifacts: [{ id: 'old' }] }) } })
    await pending; expect(vm.rows).toEqual([{ id: 'current' }])
  })
  it('closes backup confirmation before waiting for the async operation', async () => {
    const vm = { backupDialog: true, includeRuntime: true, retentionHours: 168, refresh: jest.fn(), error: '' }
    vm.mutation = jest.fn(async () => { expect(vm.backupDialog).toBe(false); expect(vm.busy).toBe('BACKUP') })
    await Widget.methods.createBackup.call(vm)
    expect(vm.mutation).toHaveBeenCalledWith('createStorageServiceConfigBackup', { includeruntime: true, retentionhours: 168 })
    expect(vm.refresh).toHaveBeenCalled()
  })
  it('requires the exact service name before applying a reviewed plan', async () => {
    const vm = { confirmation: 'other', plan: { targetName: 'service' }, planToken: 'scoped', $t: key => key, mutation: jest.fn() }
    await Widget.methods.applyPlan.call(vm); expect(vm.mutation).not.toHaveBeenCalled()
    expect(vm.error).toBe('message.storage.config.confirmation.required')
  })
  it('clears credential values when the restore dialog closes', () => {
    const vm = { credentialValues: { rule: { password: 'synthetic' } } }
    Widget.methods.closePlan.call(vm)
    expect(vm.credentialValues).toEqual({}); expect(vm.planToken).toBe(''); expect(vm.confirmation).toBe('')
  })
  it('hashes binary data without requiring a secure-origin WebCrypto API', () => {
    const Sha256 = SHA.sha256
    expect(new Sha256().update(new Uint8Array([97, 98, 99])).digest('hex')).toBe('ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad')
  })
  it('unwraps both direct and entity-wrapped API results', () => {
    const value = { artifacts: [{ id: 'artifact' }] }
    expect(Widget.methods.unwrap({ liststorageserviceconfigbackupsresponse: { result: JSON.stringify(value) } }, 'listStorageServiceConfigBackups')).toEqual(value)
    expect(Widget.methods.unwrap({ storageserviceconfiguration: { result: JSON.stringify(value) } }, 'queryAsyncJobResult')).toEqual(value)
  })
  it('does not use a previous service download token after navigating away', async () => {
    let complete
    postAPI.mockImplementation(() => new Promise(resolve => { complete = resolve }))
    const vm = { instanceId: 'a', unwrap: Widget.methods.unwrap, $t: key => key }
    const pending = Widget.methods.download.call(vm, { id: 'backup' })
    vm.instanceId = 'b'
    complete({ downloadstorageserviceconfigbackupresponse: { result: JSON.stringify({ downloadToken: 'synthetic' }) } })
    await pending
    expect(postAPI).toHaveBeenCalledTimes(1)
    expect(postAPI.mock.calls[0][1].instanceid).toBe('a')
  })
  it('ignores late volume inventory after the restore target has changed', async () => {
    let complete
    getAPI.mockImplementation(() => new Promise(resolve => { complete = resolve }))
    const vm = { instanceId: 'a', resource: { virtualmachineid: 'vm-a' }, closePlan: Widget.methods.closePlan, preparePlan: jest.fn() }
    const pending = Widget.methods.openPlan.call(vm, { id: 'backup' })
    vm.instanceId = 'b'; vm.planTarget = null
    complete({ listvolumesresponse: { volume: [{ id: 'old-volume', type: 'DATADISK', name: 'old' }] } })
    await pending
    expect(vm.preparePlan).not.toHaveBeenCalled()
    expect(vm.targetVolumes).toBeUndefined()
  })
  it('requires a reviewed source initial volume and runtime before clone planning', async () => {
    const vm = { instanceId: 'a', planTarget: { id: 'backup' }, targetMode: 'CREATE_NEW', volumeMapping: {}, initialVolumeSource: '', cloneRuntime: '', $t: key => key, mutation: jest.fn() }
    await Widget.methods.preparePlan.call(vm)
    expect(vm.mutation).not.toHaveBeenCalled()
    expect(vm.error).toBe('message.storage.config.clone.required')
  })
  it('passes explicit clone resource choices and asks the server to allocate planned volume identity', async () => {
    const vm = { instanceId: 'a', planTarget: { id: 'backup' }, targetMode: 'CREATE_NEW', volumeMapping: {}, initialVolumeSource: 'source-volume', cloneRuntime: 'runtime', clone: { name: 'new-service', size: 20 }, $t: key => key }
    vm.mutation = jest.fn(async () => ({ metadata: { plan: { blockers: [], requiredCredentials: [] } }, planToken: 'synthetic' }))
    await Widget.methods.preparePlan.call(vm)
    const parameters = vm.mutation.mock.calls[0][1]
    expect(parameters.targetmode).toBe('CREATE_NEW')
    expect(JSON.parse(parameters.mapping)).toEqual({ volumes: { 'source-volume': 'NEW' }, createNew: { name: 'new-service', size: 20, backingvolumemode: 'NEW' }, initialVolumeSourceUuid: 'source-volume', runtimeBundleUuid: 'runtime' })
    expect(vm.planPhase).toBe('REVIEW')
  })
  it('maps an existing initial clone disk without passing a new-disk offering or format size', async () => {
    const vm = { instanceId: 'a', planTarget: { id: 'backup' }, targetMode: 'CREATE_NEW', volumeMapping: {}, initialVolumeSource: 'source-volume', cloneRuntime: 'runtime', clone: { name: 'new-service', backingvolumemode: 'EXISTING', existingvolumeid: 'selected-data', diskofferingid: 'not-needed', storageid: 'not-needed', size: 20 }, $t: key => key }
    vm.mutation = jest.fn(async () => ({ metadata: { plan: { blockers: [], requiredCredentials: [] } }, planToken: 'synthetic' }))
    await Widget.methods.preparePlan.call(vm)
    const mappings = JSON.parse(vm.mutation.mock.calls[0][1].mapping)
    expect(mappings.volumes['source-volume']).toBe('selected-data')
    expect(mappings.createNew).toEqual({ name: 'new-service', backingvolumemode: 'EXISTING', existingvolumeid: 'selected-data' })
  })
})
