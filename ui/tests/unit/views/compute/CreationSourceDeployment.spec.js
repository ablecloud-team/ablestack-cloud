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
