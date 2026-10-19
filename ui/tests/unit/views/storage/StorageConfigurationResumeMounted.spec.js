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
import { nextTick, reactive } from 'vue'
import Widget from '@/views/storage/StorageServiceConfiguration'
import { getAPI, postAPI } from '@/api'
import { clearConfigurationIntents } from '@/utils/storageConfigurationJobs'
jest.mock('@/api', () => ({ getAPI: jest.fn(), postAPI: jest.fn() }))
const flush = async () => { for (let i = 0; i < 12; i++) await Promise.resolve(); await nextTick() }
const deferred = () => { let complete; const promise = new Promise(resolve => { complete = resolve }); return { promise, resolve: value => complete(value) } }
describe('mounted partial restore hydration and delayed responses', () => {
  const id = number => '00000000-0000-4000-8000-' + String(number).padStart(12, '0')
  const copy = value => JSON.parse(JSON.stringify(value))
  function artifact (created = true) {
    const requestedVolumeMapping = {
      createNew: { name: 'recorded-clone', zoneid: id(10), networkid: id(11), serviceofferingid: id(12), diskofferingid: id(13), storageid: id(14), backingvolumemode: 'NEW', size: 20, filesystem: 'EXT4', networkmode: 'STATIC', ipcidr: '10.10.13.224/16', gateway: '10.10.0.1', dns1: '8.8.8.8', dns2: '' },
      volumes: { [id(1)]: 'NEW', [id(2)]: 'NEW', [id(3)]: 'NEW', [id(4)]: 'NEW' },
      newVolumes: Object.fromEntries([2, 3, 4].map(value => [id(value), { diskofferingid: id(13), storageid: id(14), sizeGiB: 2, dataPolicy: 'PRESERVE' }])),
      initialVolumeSourceUuid: id(1),
      runtimeBundleUuid: id(40)
    }
    const metadata = {
      requestedVolumeMapping,
      cloneAllocation: { scope: { artifactUuid: id(50), plannedTargetInstanceUuid: id(79) }, sharedFS: { uuid: id(78) } },
      plan: {
        targetMode: 'CREATE_NEW',
        targetName: 'recorded-clone',
        targetInstanceUuid: id(79),
        createNew: { ...requestedVolumeMapping.createNew, templateid: id(90) },
        cloneTemplatePin: { selectionMode: 'DEFAULT_SYSTEM', templateUuid: id(90), templateChecksum: 'recorded-checksum', templateDetailsSha256: 'a'.repeat(64) },
        cloneRuntimePin: { bundleUuid: id(40), cliSha256: 'b'.repeat(64) },
        volumeAllocationPlan: { schemaVersion: 1, planSha256: 'c'.repeat(64), allocations: [1, 2, 3, 4].map(value => ({ sourceUuid: id(value), plannedUuid: id(value + 60), mode: 'NEW' })) },
        resourceMappings: { 'protocol-endpoints': { [id(20)]: id(80) } },
        listenerAddressMappings: { '10.10.13.223': 'PRIMARY' },
        create: [],
        update: [],
        keep: [],
        preserve: [],
        blockers: [],
        requiredCredentials: [{ ruleUuid: id(30), principal: 'local-user', fields: ['password'] }],
        forcedFileExecutePolicies: [],
        forceFileExecuteConfirmation: true
      },
      planToken: { hash: 'synthetic-old-capability-not-copied' },
      adIdentityCipherReference: { ciphertext: 'synthetic-private-cipher-not-copied' }
    }
    if (created) metadata.createdTargetInstanceUuid = id(79)
    return { id: id(50), kind: 'BACKUP', state: 'COMPLETE', metadata }
  }

  function explicitArtifact () {
    const row = artifact()
    row.metadata.requestedVolumeMapping.createNew.templateid = id(90)
    row.metadata.plan.cloneTemplatePin.selectionMode = 'EXPLICIT'
    return row
  }
  function reply (row, token = 'fresh-reviewed-token') {
    return { planstorageserviceconfigrestoreresponse: { storageserviceconfiguration: { result: JSON.stringify({ id: row.id, metadata: copy(row.metadata), planToken: token }) } } }
  }
  let wrapper
  function view () {
    wrapper = shallowMount({ ...Widget, methods: { ...Widget.methods, refresh: jest.fn().mockResolvedValue() } }, {
      props: { instanceId: 'instance-a', instanceName: 'source', resource: { account: 'owner', domainid: 'domain' } },
      global: {
        mocks: {
          $t: key => key,
          $store: { getters: { apis: { applyStorageServiceConfigRestore: {} }, userInfo: { id: 'actor' }, project: null }, state: { user: { token: 'session' } } }
        }
      }
    })
    return wrapper.vm
  }
  beforeEach(() => { clearConfigurationIntents(); getAPI.mockReset(); postAPI.mockReset() })
  afterEach(() => { if (wrapper) wrapper.unmount(); wrapper = null; clearConfigurationIntents() })

  it.each([['DEFAULT_SYSTEM', artifact], ['EXPLICIT', explicitArtifact]])('keeps recorded %s mappings through actual Vue watchers and a delayed review', async (_, make) => {
    const row = reactive(make()); const expected = copy(row); const pending = deferred()
    postAPI.mockReturnValue(pending.promise)
    const vm = view(); const opening = vm.openPlan(row)
    await nextTick()
    expect(vm.clone).toEqual(expected.metadata.requestedVolumeMapping.createNew)
    expect(vm.volumeMapping).toEqual(expected.metadata.requestedVolumeMapping.volumes)
    expect(vm.listenerMapping).toEqual(expected.metadata.plan.listenerAddressMappings)
    expect(vm.mappingLocked).toBe(true)
    pending.resolve(reply(row)); await opening; await flush()
    expect(vm.error).toBe('')
    expect(vm.planPhase).toBe('REVIEW')
    expect(vm.planToken).toBe('fresh-reviewed-token')
    expect(vm.reviewedPlan).toBe(vm.restoreReviewFingerprint())
    expect(wrapper.findAllComponents({ name: 'AModal' }).find(modal => modal.props('title') === 'label.storage.config.plan').props('okText')).toBe('label.storage.config.apply')
    expect(postAPI).toHaveBeenCalledTimes(1)
    expect(postAPI.mock.calls[0][0]).toBe('planStorageServiceConfigRestore')
    expect(row).toEqual(expected)
    expect(vm.credentialValues).toEqual({ [id(30)]: {} })
  })

  it('preserves a valid review across receipt/state updates and equal metadata replacement', async () => {
    const row = reactive(explicitArtifact()); postAPI.mockResolvedValue(reply(row))
    const vm = view(); await vm.openPlan(row); await flush()
    vm.credentialValues[id(30)] = { password: 'synthetic-current-input' }; vm.confirmation = 'recorded-clone'
    row.metadata.receipts = { completed: true }; row.metadata.restoreState = 'FAILED'
    await nextTick(); row.metadata = copy(row.metadata); await nextTick()
    expect(vm.planToken).toBe('fresh-reviewed-token'); expect(vm.planPhase).toBe('REVIEW')
    expect(vm.clone.templateid).toBe(id(90)); expect(Object.keys(vm.volumeMapping)).toHaveLength(4)
    expect(vm.credentialValues[id(30)].password).toBe('synthetic-current-input')
    expect(vm.confirmation).toBe('recorded-clone'); expect(postAPI).toHaveBeenCalledTimes(1)
  })

  it.each([
    row => { row.metadata.createdTargetInstanceUuid = id(200) },
    row => { row.metadata.plan.cloneTemplatePin.templateUuid = id(201) },
    row => { row.metadata.requestedVolumeMapping.volumes[id(2)] = id(202) },
    row => { row.metadata.plan.listenerAddressMappings['10.10.13.223'] = '10.10.13.226' }
  ])('invalidates changed frozen metadata before a delayed response can authorize apply', async change => {
    const row = reactive(explicitArtifact()); const response = reply(row); const pending = deferred()
    postAPI.mockReturnValue(pending.promise)
    const vm = view(); const opening = vm.openPlan(row); await nextTick()
    change(row); await nextTick()
    expect(vm.cloneResumeUnavailable).toBe(true)
    pending.resolve(response); await opening; await flush()
    vm.confirmation = 'recorded-clone'; await vm.applyPlan()
    expect(vm.planToken).toBe(''); expect(vm.reviewedPlan).toBe(''); expect(vm.credentialValues).toEqual({})
    expect(vm.planPhase).toBe('MAPPING'); expect(vm.error).toBe('message.storage.config.clone.resume.unavailable')
    expect(postAPI).toHaveBeenCalledTimes(1)
  })

  it('blocks a changed owner without clearing or rebinding the recorded source request', async () => {
    const row = reactive(explicitArtifact()); postAPI.mockResolvedValue(reply(row))
    const vm = view(); await vm.openPlan(row); await flush()
    vm.credentialValues[id(30)] = { password: 'synthetic-old-input' }; vm.confirmation = 'recorded-clone'
    await wrapper.setProps({ resource: { account: 'other-owner', domainid: 'domain' } })
    expect(vm.cloneResumeUnavailable).toBe(true); expect(vm.planToken).toBe(''); expect(vm.credentialValues).toEqual({})
    expect(vm.clone.templateid).toBe(id(90)); expect(vm.volumeMapping).toEqual(row.metadata.requestedVolumeMapping.volumes)
    await vm.applyPlan(); expect(postAPI).toHaveBeenCalledTimes(1)
  })

  it('ignores an old response after closing and reopening the same row', async () => {
    const row = reactive(explicitArtifact()); const first = deferred(); const second = deferred()
    postAPI.mockReturnValueOnce(first.promise).mockReturnValueOnce(second.promise)
    const vm = view(); const opening = vm.openPlan(row); await nextTick()
    vm.closePlan(); await nextTick()
    await vm.openPlan(row); await nextTick()
    expect(postAPI).toHaveBeenCalledTimes(1)
    first.resolve(reply(row, 'old-review')); await opening; await flush()
    expect(vm.planToken).toBe(''); expect(vm.planPhase).toBe('MAPPING')
    const reopened = vm.preparePlan()
    second.resolve(reply(row, 'new-review')); await reopened; await flush()
    expect(vm.planToken).toBe('new-review'); expect(vm.planPhase).toBe('REVIEW')
    expect(vm.credentialValues).toEqual({ [id(30)]: {} }); expect(postAPI).toHaveBeenCalledTimes(2)
  })

  it('keeps existing-service review and ordinary mapping invalidation working with Vue watchers', async () => {
    const row = reactive({ id: id(50), metadata: {} })
    getAPI.mockResolvedValue({ listvolumesresponse: { volume: [] } })
    const ordinary = { targetMode: 'RESTORE_EXISTING', targetName: 'source', create: [], update: [], keep: [], preserve: [], blockers: [], requiredCredentials: [] }
    postAPI.mockResolvedValue({ planstorageserviceconfigrestoreresponse: { storageserviceconfiguration: { result: JSON.stringify({ id: row.id, metadata: { plan: ordinary }, planToken: 'existing-token' }) } } })
    const vm = view(); await vm.openPlan(row); await flush()
    expect(vm.planPhase).toBe('REVIEW'); expect(vm.planToken).toBe('existing-token')
    vm.clone.templateid = id(90); await nextTick()
    expect(vm.planToken).toBe(''); expect(vm.reviewedPlan).toBe('')
  })
})
