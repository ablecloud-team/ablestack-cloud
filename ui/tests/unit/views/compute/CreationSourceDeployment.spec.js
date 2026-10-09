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

import DeployVM from '@/views/compute/DeployVM.vue'
import { getAPI, postAPI } from '@/api'
jest.mock('@/api', () => ({ getAPI: jest.fn(), postAPI: jest.fn() }))
jest.mock('@/store', () => ({ getters: {} }))
const context = () => ({
  selectedCreationSource: { id: 'source-uuid', revision: 'revision-1', allowed: true },
  sourceLoading: false,
  sourceOperationPending: false,
  creationSourceQuery: { zoneid: 'zone', sourcekind: 'volume' },
  form: { computeofferingid: 'offering', clusterid: 'cluster' },
  rootStorageSelection: {},
  $t: key => key
})
beforeEach(() => jest.clearAllMocks())
test('parent deployment consumes the API single-source list envelope and preserves inspection parameters', async () => {
  const source = { id: 'source-uuid', allowed: true, revision: 'revision-1' }
  getAPI.mockResolvedValue({ validatevirtualmachinecreationresponse: { count: 1, creationsource: [source] } })
  await expect(DeployVM.methods.validateCreationSource.call(context())).resolves.toEqual(source)
  expect(getAPI).toHaveBeenCalledWith('validateVirtualMachineCreation', {
    zoneid: 'zone', sourcekind: 'volume', id: 'source-uuid', sourcerevision: 'revision-1', serviceofferingid: 'offering', clusterid: 'cluster'
  })
})
test('parent deployment rejects blocked and malformed source responses before allocation', async () => {
  getAPI.mockResolvedValue({ validatevirtualmachinecreationresponse: { count: 1, creationsource: [{ allowed: false, reasoncodes: ['SOURCE_ATTACHED'] }] } })
  await expect(DeployVM.methods.validateCreationSource.call(context())).rejects.toThrow('message.creation.source.reason.SOURCE_ATTACHED')
  getAPI.mockResolvedValue({ validatevirtualmachinecreationresponse: { count: 0 } })
  await expect(DeployVM.methods.validateCreationSource.call(context())).rejects.toThrow('message.creation.source.required')
})

test.each(['volumeid', 'snapshotid'])('parent deployment posts all %s source, placement and compute parameters using the API contract', async sourceKey => {
  const params = { [sourceKey]: 'source-uuid', sourcerevision: 'revision-1', zoneid: 'zone', serviceofferingid: 'offering', clusterid: 'cluster', startvm: false, 'details[0].cpuNumber': 2, 'details[0].memory': 2048 }
  const operation = { status: 'submitting' }
  postAPI.mockResolvedValue({ deployvirtualmachineresponse: { jobid: 'job', id: 'vm' } })
  await expect(DeployVM.methods.deployVM.call({ currentSourceOperation: operation }, params)).resolves.toBe('job')
  expect(postAPI).toHaveBeenCalledWith('deployVirtualMachine', params)
  expect(operation.vmid).toBe('vm')
})
test('legacy volume deployment uses the same two-argument POST contract', async () => {
  const params = { volumeid: 'volume', serviceofferingid: 'offering' }
  postAPI.mockResolvedValue({ deployvirtualmachineforvolumeresponse: { jobid: 'job' } })
  await expect(DeployVM.methods.deployVirtualMachineForVolume.call({}, params)).resolves.toBe('job')
  expect(postAPI).toHaveBeenCalledWith('deployVirtualMachineForVolume', params)
})

const sourceDiskPlan = () => ({
  isCreationSource: true,
  selectedCreationSource: { allowed: true },
  sourceLoading: false,
  sourceOperationPending: false,
  rootStorageSelection: {},
  selectedDataDiskOffering: { id: 'custom-data', iscustomized: true },
  selectedDataDiskSize: 20,
  selectedDataDiskCount: 2,
  storageSelectionEnabled: true,
  dataStorageSelection: {}
})
test.each([0, undefined, -1])('source wizard blocks an additional data disk without a positive size (%s)', size => {
  const vm = { ...sourceDiskPlan(), selectedDataDiskSize: size }
  expect(DeployVM.computed.diskPlanIncomplete.call(vm)).toBe(true)
})
test('source wizard validates additional disk count and a changed manual data target', () => {
  expect(DeployVM.computed.diskPlanIncomplete.call({ ...sourceDiskPlan(), selectedDataDiskCount: 0 })).toBe(true)
  expect(DeployVM.computed.diskPlanIncomplete.call({ ...sourceDiskPlan(), selectedDataDiskCount: 1.5 })).toBe(true)
  expect(DeployVM.computed.diskPlanIncomplete.call({ ...sourceDiskPlan(), dataStorageSelection: { id: 'pool', valid: false } })).toBe(true)
  expect(DeployVM.computed.diskPlanIncomplete.call(sourceDiskPlan())).toBe(false)
  expect(DeployVM.computed.diskPlanIncomplete.call({ ...sourceDiskPlan(), selectedDataDiskOffering: null, selectedDataDiskSize: 0 })).toBe(false)
})
test('snapshot ROOT capacity query accounts for the requested additional data disks', () => {
  const vm = { ...sourceDiskPlan(), imageType: 'snapshotid', selectedCreationSource: { id: 'snapshot' }, form: { zoneid: 'zone', computeofferingid: 'compute', hostid: 'host' }, diskIOpsMin: 100 }
  expect(DeployVM.computed.rootStorageQuery.call(vm)).toMatchObject({
    snapshotid: 'snapshot',
    rootdisk: true,
    diskcount: 1,
    vmcount: 1,
    otherrequiredbytes: 40 * 1024 ** 3,
    otherrequirediops: 200
  })
})
test('snapshot DATA query supplies the source image and both pool capacity requirements', () => {
  const vm = {
    ...sourceDiskPlan(),
    imageType: 'snapshotid',
    selectedCreationSource: { id: 'snapshot' },
    storageQuery: { zoneid: 'zone', templateid: 'stale-template', serviceofferingid: 'compute', hostid: 'host', vmcount: 5 },
    selectedRootDiskSize: 100,
    form: { vmNumber: 1 },
    diskIOpsMin: 100,
    rootStorageSelection: { id: 'root-pool' }
  }
  expect(DeployVM.computed.dataStorageQuery.call(vm)).toMatchObject({
    zoneid: 'zone',
    templateid: undefined,
    snapshotid: 'snapshot',
    hypervisor: 'KVM',
    serviceofferingid: 'compute',
    hostid: 'host',
    rootdisk: false,
    diskofferingid: 'custom-data',
    size: 20,
    diskcount: 2,
    vmcount: 1,
    miniops: 100,
    otherstorageid: 'root-pool',
    otherrequiredbytes: 100 * 1024 ** 3
  })
  expect(DeployVM.computed.rootStorageQuery.call({ ...vm, form: { zoneid: 'zone', computeofferingid: 'compute' }, dataStorageSelection: { id: 'data-pool' } })).toMatchObject({ otherstorageid: 'data-pool' })
})
