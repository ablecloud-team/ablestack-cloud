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

import { deploymentStorageQuery, dataDiskDeviceIds, dataDiskRequest, completeIsoDiskDeployment, additionalIsoConfigDriveConflict } from '@/utils/vmDiskDeployment'

const record = (start = false) => ({
  deployJobId: 'deploy',
  startAfterCreation: start,
  volumeBaseParams: { zoneid: 'zone', diskofferingid: 'offering', size: 17, storageid: 'pool' },
  volumeNamePrefix: 'test-Data-',
  disks: dataDiskDeviceIds(3).map((deviceId, index) => ({ deviceId, index, status: 'pending' }))
})
function backend (failAt) {
  let next = 0
  let failed = false
  const jobs = { deploy: { virtualmachine: { id: 'vm' } } }
  const volumes = {}
  const call = jest.fn(async (command, params) => {
    if (command === 'queryAsyncJobResult') return { jobstatus: 1, jobresult: jobs[params.jobid] }
    if (command === 'listVolumes') return { volume: volumes[params.id] ? [volumes[params.id]] : [] }
    if (command === 'createVolume') {
      const id = 'volume-' + (++next)
      const volume = { id, state: 'Ready' }
      volumes[id] = volume
      jobs[id] = { volume }
      return { jobid: id, id }
    }
    if (command === 'attachVolume') {
      if (params.id === failAt && !failed) { failed = true; throw new Error('attach denied') }
      volumes[params.id].virtualmachineid = params.virtualmachineid
      jobs['attach-' + params.id] = { volume: volumes[params.id] }
      return { jobid: 'attach-' + params.id }
    }
    if (command === 'startVirtualMachine') { jobs.start = {}; return { jobid: 'start' } }
    throw new Error(command)
  })
  return call
}
test('arbitrary count skips root and CD-ROM device IDs; no ten-disk hardcoding', () => {
  expect(dataDiskDeviceIds(12)).toEqual([1, 2, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13])
  expect(() => dataDiskDeviceIds(0)).toThrow()
  expect(() => dataDiskDeviceIds(1.5)).toThrow()
})
test('fixed offering omits size while custom offering preserves arbitrary size and pool per disk', () => {
  const fixed = dataDiskRequest({ id: 'fixed' }, 123, 2)
  expect(fixed['datadisksdetails[0].size']).toBeUndefined()
  const custom = dataDiskRequest({ id: 'custom', iscustomized: true }, 37, 12, { storageid: 'pool' })
  expect(custom['datadisksdetails[11].size']).toBe(37)
  expect(custom['datadisksdetails[11].storageid']).toBe('pool')
  expect(custom['datadisksdetails[2].deviceid']).toBe(4)
})
test('default stopped VM receives every disk without start or bulk API', async () => {
  const call = backend()
  const state = record()
  await completeIsoDiskDeployment(call, state, () => Promise.resolve())
  expect(state.disks.every(d => d.status === 'attached')).toBe(true)
  expect(call.mock.calls.filter(([command]) => command === 'createVolume')).toHaveLength(3)
  expect(call.mock.calls.some(([command]) => command === 'startVirtualMachine')).toBe(false)
})
test('partial failure survives serialization; retry reuses created volume and starts only after final attach', async () => {
  const call = backend('volume-2')
  const state = record(true)
  await expect(completeIsoDiskDeployment(call, state)).rejects.toThrow('attach denied')
  expect(state.disks[0].status).toBe('attached')
  expect(state.disks[1].volumeId).toBe('volume-2')
  expect(call.mock.calls.some(([command]) => command === 'startVirtualMachine')).toBe(false)
  const restored = JSON.parse(JSON.stringify(state))
  await completeIsoDiskDeployment(call, restored)
  expect(call.mock.calls.filter(([command]) => command === 'createVolume')).toHaveLength(3)
  expect(call.mock.calls.at(-2)[0]).toBe('startVirtualMachine')
  await completeIsoDiskDeployment(call, restored)
  expect(call.mock.calls.filter(([command]) => command === 'startVirtualMachine')).toHaveLength(1)
})
test('transient polling error retains pending job and retry never submits it again', async () => {
  const base = backend()
  let fail = true
  const call = jest.fn(async (command, params) => {
    if (command === 'queryAsyncJobResult' && params.jobid === 'volume-1' && fail) { fail = false; throw new Error('network') }
    return base(command, params)
  })
  const state = record()
  await expect(completeIsoDiskDeployment(call, state)).rejects.toThrow('network')
  expect(state.disks[0].createjobid).toBe('volume-1')
  await completeIsoDiskDeployment(call, state)
  expect(base.mock.calls.filter(([command]) => command === 'createVolume')).toHaveLength(3)
})

test('lost create response does not blindly create a second volume on retry', async () => {
  const state = record()
  state.vmId = 'vm'
  const call = jest.fn(async command => {
    if (command === 'createVolume') throw new Error('connection lost after submit')
    if (command === 'listVolumes') return {}
    throw new Error(command)
  })
  await expect(completeIsoDiskDeployment(call, state)).rejects.toThrow('connection lost')
  expect(state.disks[0].createUncertain).toBe(true)
  await expect(completeIsoDiskDeployment(call, JSON.parse(JSON.stringify(state)))).rejects.toThrow('submission result is unknown')
  expect(call.mock.calls.filter(([command]) => command === 'createVolume')).toHaveLength(1)
})

test('per-disk storage, encryption and IOPS choices survive template expansion', () => {
  const request = dataDiskRequest({ id: 'custom', iscustomized: true }, 23, 3, { kmskeyid: 'kms', miniops: 100, maxiops: 500, storageid: 'pool' })
  for (let index = 0; index < 3; index++) {
    expect(request['datadisksdetails[' + index + '].size']).toBe(23)
    expect(request['datadisksdetails[' + index + '].kmskeyid']).toBe('kms')
    expect(request['datadisksdetails[' + index + '].miniops']).toBe(100)
    expect(request['datadisksdetails[' + index + '].maxiops']).toBe(500)
    expect(request['datadisksdetails[' + index + '].storageid']).toBe('pool')
  }
  expect(() => dataDiskDeviceIds(0)).toThrow()
  expect(() => dataDiskDeviceIds(1.5)).toThrow()
})

test('ISO storage queries use the selected form hypervisor before derived state catches up', () => {
  const query = deploymentStorageQuery({
    form: { zoneid: 'zone', computeofferingid: 'compute', isoid: 'iso', hypervisor: 'KVM', vmNumber: 1 },
    imageType: 'isoid',
    hypervisor: null,
    template: null
  })
  expect(query.hypervisor).toBe('KVM')
  expect(query.templateid).toBe('iso')
})

test('storage queries are safe before the form is initialized', () => {
  expect(deploymentStorageQuery({ imageType: 'isoid' }).zoneid).toBeUndefined()
  expect(deploymentStorageQuery({ imageType: 'isoid' }).templateid).toBeUndefined()
})

const configDriveNetwork = { id: 'config-drive', service: [{ name: 'UserData', provider: [{ name: 'ConfigDrive' }] }] }
const extraIso = { enabled: true, ids: ['driver'] }
test('two ISOs conflict only with a selected ConfigDrive network and recover when the selection changes', () => {
  const selection = { imageType: 'isoid', additionalIsoSelection: extraIso, networks: [configDriveNetwork, { id: 'plain' }], networkIds: ['config-drive'] }
  expect(additionalIsoConfigDriveConflict(selection)).toBe(true)
  expect(additionalIsoConfigDriveConflict({ ...selection, networkIds: ['plain'] })).toBe(false)
  expect(additionalIsoConfigDriveConflict({ ...selection, additionalIsoSelection: { enabled: false, ids: [] } })).toBe(false)
  expect(additionalIsoConfigDriveConflict({ ...selection, additionalIsoSelection: { enabled: true, ids: [] } })).toBe(false)
})
test.each(['templateid', 'volumeid', 'snapshotid'])('ConfigDrive check preserves %s creation', imageType => {
  expect(additionalIsoConfigDriveConflict({ imageType, additionalIsoSelection: extraIso, networks: [configDriveNetwork], networkIds: ['config-drive'] })).toBe(false)
})
