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

import Upgrade from '@/views/storage/StorageServiceRuntimeUpgrade'
import Compatibility from '@/views/storage/StorageRuntimeCompatibility'
import { getAPI } from '@/api'
jest.mock('@/api', () => ({ getAPI: jest.fn(), postAPI: jest.fn() }))

describe('Verified runtime consumer observations', () => {
  beforeEach(() => getAPI.mockReset())
  it.each(['broken', 'null', '[]'])('treats malformed consumer observation %s as unverified', consumerobservation => {
    expect(Upgrade.computed.consumerObservation.call({ capability: { consumerobservation } })).toEqual({})
  })
  it('reads branded manager and agent versions without inventing a protected template version', () => {
    const observation = { managerVersion: '4.23.0.0-Mold.Europa-202610011115', agentVersion: '4.23.0.0-Mold.Europa-202610011115', templatePlatformVersion: '4.23.0.0', platformVersionKnown: false }
    const vm = { observation, observationVerified: false, $t: key => key }
    expect(Compatibility.methods.observedVersion.call(vm, 'manager')).toBe(observation.managerVersion)
    expect(Compatibility.methods.observedVersion.call(vm, 'agent')).toBe(observation.agentVersion)
    expect(Compatibility.methods.observedVersion.call(vm, 'template')).toBe('label.storage.runtime.consumer.unknown')
  })
  it('shows template version only when protected observation and updater verification are present', () => {
    const observation = { templatePlatformVersion: '4.23.0.0', platformVersionKnown: true, platformObservationRecorded: true, updaterVerified: true, templateManifestSha256: 'manifest-sha' }
    const vm = { observation, $t: key => key }
    vm.observationVerified = Compatibility.computed.observationVerified.call(vm)
    expect(vm.observationVerified).toBe(true)
    expect(Compatibility.methods.observedVersion.call(vm, 'template')).toBe('4.23.0.0')
    observation.updaterVerified = false
    expect(Compatibility.computed.observationVerified.call(vm)).toBe(false)
  })
  it('ignores delayed capability and runtime history belonging to a previous service', async () => {
    let resolveCapability
    let signalEntered
    const entered = new Promise(resolve => { signalEntered = resolve })
    getAPI.mockImplementation(api => {
      if (api === 'getStorageServiceRuntimeUpgradeCapabilities') return new Promise(resolve => { resolveCapability = resolve; signalEntered() })
      if (api === 'listStorageServiceRuntimeBundles') return Promise.resolve({ liststorageserviceruntimebundlesresponse: { storageserviceruntimebundle: [] } })
      return Promise.resolve({ liststorageserviceruntimeupgradesresponse: { storageserviceruntimeupgrade: [] } })
    })
    const vm = { generation: 0, loading: false, resource: { id: 'old-service' }, capability: { currentversion: 'new-service-runtime' }, bundles: [], upgrades: [], $notifyError: jest.fn() }
    const pending = Upgrade.methods.fetchData.call(vm)
    await entered
    vm.resource = { id: 'new-service' }; vm.generation++
    resolveCapability({ getstorageserviceruntimeupgradecapabilitiesresponse: { storageserviceruntimecapability: { currentversion: 'old-service-runtime' } } })
    await pending
    expect(vm.capability.currentversion).toBe('new-service-runtime')
    expect(vm.$notifyError).not.toHaveBeenCalled()
  })
})
