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
import { configurationIntents, configurationScope, clearConfigurationIntents, configurationDifference } from '@/utils/storageConfigurationJobs'
jest.mock('@/api', () => ({ getAPI: jest.fn(), postAPI: jest.fn() }))
const flush = async () => { for (let i = 0; i < 10; i++) await Promise.resolve() }
const context = () => ({
  instanceId: 'instance-a',
  resource: { account: 'owner', domainid: 'domain' },
  $store: { getters: { userInfo: { id: 'actor' }, project: null }, state: { user: { token: 'session' } } },
  $t: key => key,
  $emit: jest.fn(),
  unwrap: Widget.methods.unwrap,
  busy: '',
  refresh: jest.fn().mockResolvedValue()
})
describe('configuration known jobs and non-secret review', () => {
  beforeEach(() => { clearConfigurationIntents(); getAPI.mockReset(); postAPI.mockReset(); jest.useFakeTimers() })
  afterEach(() => { clearConfigurationIntents(); jest.useRealTimers() })
  it('blocks a second POST after an ambiguous reply and immediately clears request credentials', async () => {
    const vm = context(); const request = { artifactid: 'backup', credentials: '{"password":"synthetic-private"}' }
    postAPI.mockRejectedValue(new Error('transport'))
    const pending = Widget.methods.mutation.call(vm, 'applyStorageServiceConfigRestore', request)
    expect(request.credentials).toBe('')
    await expect(pending).rejects.toThrow('message.storage.config.job.unknown')
    expect(JSON.stringify(configurationIntents)).not.toContain('synthetic-private')
    await expect(Widget.methods.mutation.call(vm, 'applyStorageServiceConfigRestore', {})).rejects.toThrow('message.storage.config.job.blocked')
    expect(postAPI).toHaveBeenCalledTimes(1)
    expect(postAPI.mock.calls[0][2]).toEqual({ preserveOnFailure: true, backgroundJob: true })
    expect(vm.refresh).not.toHaveBeenCalled()
  })
  it('does not promote a missing async job ID to success', async () => {
    const vm = context(); postAPI.mockResolvedValue({ result: '{}' })
    await expect(Widget.methods.mutation.call(vm, 'createStorageServiceConfigBackup', {})).rejects.toThrow('message.storage.config.job.unknown')
    expect(configurationIntents[configurationScope(vm)].status).toBe('unknown')
    expect(getAPI).not.toHaveBeenCalled()
  })
  it('keeps an approved request without a job ACK unknown even when a runtime row claims completion', async () => {
    const vm = context()
    postAPI.mockResolvedValue({ applystorageserviceapprovedmaintenanceresponse: { storageserviceruntime: { id: vm.instanceId, success: true, status: 'COMPLETE', resultjson: JSON.stringify({ id: 'artifact', result: '{}' }) } } })
    await expect(Widget.methods.mutation.call(vm, 'applyStorageServiceApprovedMaintenance', {})).rejects.toThrow('message.storage.config.job.unknown')
    expect(configurationIntents[configurationScope(vm)].status).toBe('unknown')
    await expect(Widget.methods.mutation.call(vm, 'applyStorageServiceApprovedMaintenance', {})).rejects.toThrow('message.storage.config.job.blocked')
    expect(postAPI).toHaveBeenCalledTimes(1)
    expect(getAPI).not.toHaveBeenCalled()
  })

  it('tracks approved maintenance by its known job and unwraps the actual nested runtime artifact result', async () => {
    const vm = context(); const artifact = { id: 'artifact-a', state: 'ARCHIVE_VALIDATED', metadata: { restoreState: 'COMPLETE' } }
    const request = { artifactid: artifact.id, credentials: '{"password":"synthetic-approved-secret"}', maintenancewindow: true }
    postAPI.mockResolvedValue({ applystorageserviceapprovedmaintenanceresponse: { jobid: 'approved-known-job' } })
    getAPI.mockResolvedValue({ queryasyncjobresultresponse: { jobstatus: 1, jobresultcode: 0, jobresult: { storageserviceruntime: { id: vm.instanceId, success: true, status: 'COMPLETE', resultjson: JSON.stringify({ id: artifact.id, result: JSON.stringify(artifact) }) } } } })
    await expect(Widget.methods.mutation.call(vm, 'applyStorageServiceApprovedMaintenance', request)).resolves.toEqual(artifact)
    expect(getAPI.mock.calls.every(call => call[1].jobid === 'approved-known-job')).toBe(true)
    expect(postAPI).toHaveBeenCalledTimes(1)
    expect(postAPI.mock.calls[0][1].instanceid).toBe('instance-a')
    expect(request.credentials).toBe('')
    expect(JSON.stringify(configurationIntents)).not.toContain('synthetic-approved-secret')
    expect(vm.$emit).toHaveBeenCalledWith('operation-updated', vm.instanceId)
  })

  it('rejects approved runtime failure and another-instance completion without a fallback POST', async () => {
    for (const runtime of [{ id: 'instance-a', success: false, status: 'FAILED' }, { id: 'other-instance', success: true, status: 'COMPLETE' }]) {
      clearConfigurationIntents(); postAPI.mockClear()
      const vm = context()
      postAPI.mockResolvedValue({ applystorageserviceapprovedmaintenanceresponse: { jobid: 'approved-invalid-result' } })
      getAPI.mockResolvedValue({ queryasyncjobresultresponse: { jobstatus: 1, jobresultcode: 0, jobresult: { storageserviceruntime: { ...runtime, resultjson: JSON.stringify({ id: 'artifact', result: '{}' }) } } } })
      await expect(Widget.methods.mutation.call(vm, 'applyStorageServiceApprovedMaintenance', {})).rejects.toThrow('message.storage.config.failed')
      expect(postAPI).toHaveBeenCalledTimes(1)
    }
  })

  it('blocks approved maintenance at the same current-writer gate before any POST', async () => {
    const vm = { ...context(), storageMutationGate: { blocked: () => true, message: () => 'current-writer-blocked' } }
    await expect(Widget.methods.mutation.call(vm, 'applyStorageServiceApprovedMaintenance', {})).rejects.toThrow('current-writer-blocked')
    expect(postAPI).not.toHaveBeenCalled()
    expect(getAPI).not.toHaveBeenCalled()
  })

  it('accepts a synchronous validated artifact without inventing a job', async () => {
    const vm = context(); postAPI.mockResolvedValue({ validatestorageserviceconfigimportresponse: { result: '{"id":"import-a","state":"ARCHIVE_VALIDATED"}' } })
    await expect(Widget.methods.mutation.call(vm, 'validateStorageServiceConfigImport', { artifactid: 'import-a' })).resolves.toEqual({ id: 'import-a', state: 'ARCHIVE_VALIDATED' })
    expect(getAPI).not.toHaveBeenCalled()
  })
  it.each(['applyStorageServiceConfigRestore', 'applyStorageServiceApprovedMaintenance'])('retains the same %s job across interruption and re-entry without another POST', async api => {
    const vm = context()
    postAPI.mockResolvedValue({ [api.toLowerCase() + 'response']: { jobid: 'accepted-job' } })
    getAPI.mockImplementation(() => new Promise(() => {}))
    const pending = Widget.methods.mutation.call(vm, api, {}).catch(error => error)
    await flush(); jest.advanceTimersByTime(180000); await flush(); await pending
    const intent = configurationIntents[configurationScope(vm)]
    expect(intent).toMatchObject({ jobId: 'accepted-job', status: 'unknown' })
    const reentered = { ...context(), configurationIntent: intent }
    getAPI.mockResolvedValue({ queryasyncjobresultresponse: { jobstatus: 1, jobresultcode: 0, jobresult: { result: '{}' } } })
    await Widget.methods.resumeConfigurationJob.call(reentered)
    expect(intent.status).toBe('complete')
    expect(getAPI.mock.calls.every(call => call[1].jobid === 'accepted-job')).toBe(true)
    expect(postAPI).toHaveBeenCalledTimes(1)
    expect(reentered.refresh).toHaveBeenCalled()
  })
  it('keeps the terminal job receipt after unmount without updating the disposed view history', async () => {
    const vm = context(); let finish
    postAPI.mockResolvedValue({ applystorageserviceconfigrestoreresponse: { jobid: 'detached-job' } })
    getAPI.mockImplementation(() => new Promise(resolve => { finish = resolve }))
    const pending = Widget.methods.mutation.call(vm, 'applyStorageServiceConfigRestore', {})
    await flush(); vm.disposed = true
    finish({ queryasyncjobresultresponse: { jobstatus: 1, jobresultcode: 0, jobresult: { result: '{}' } } })
    await expect(pending).rejects.toThrow('message.storage.config.scope.changed')
    expect(configurationIntents[configurationScope(context())]).toMatchObject({ jobId: 'detached-job', status: 'complete' })
    expect(vm.$emit).not.toHaveBeenCalled()
  })
  it('permits a fresh reviewed request after a definitive terminal failure, without storing server error secrets', async () => {
    const vm = context()
    postAPI.mockResolvedValue({ applystorageserviceconfigrestoreresponse: { jobid: 'failed-job' } })
    getAPI.mockResolvedValue({ queryasyncjobresultresponse: { jobstatus: 2, jobresultcode: 530, jobresult: { errortext: 'synthetic-private-error' } } })
    await expect(Widget.methods.mutation.call(vm, 'applyStorageServiceConfigRestore', {})).rejects.toThrow('message.storage.config.job.failed')
    expect(configurationIntents[configurationScope(vm)].status).toBe('failed')
    expect(JSON.stringify(configurationIntents)).not.toContain('synthetic-private-error')
    postAPI.mockResolvedValue({ planstorageserviceconfigrestoreresponse: { result: '{"id":"new-plan","metadata":{"plan":{}}}' } })
    await Widget.methods.mutation.call(vm, 'planStorageServiceConfigRestore', {})
    expect(postAPI).toHaveBeenCalledTimes(2)
  })
  it('separates project and account scopes instead of exposing another resource operation', () => {
    const vm = context(); const first = configurationScope(vm)
    vm.$store.getters.project = { id: 'project-b' }; expect(configurationScope(vm)).not.toBe(first)
    vm.$store.getters.project = null; vm.resource.account = 'other'; expect(configurationScope(vm)).not.toBe(first)
  })
  it('renders current/desired settings while redacting nested and JSON-encoded credential values', () => {
    const rows = configurationDifference({ config: { permission: 'READ', password: 'before-private', apiKey: 'key-private' }, certificate: '-----BEGIN PRIVATE KEY-----synthetic-private-pem' }, { config: '{"permission":"READWRITE","chapPassword":"after-private"}' })
    const rendered = JSON.stringify(rows)
    expect(rendered).toContain('READWRITE'); expect(rendered).toContain('[REDACTED]')
    expect(rendered).not.toContain('before-private'); expect(rendered).not.toContain('after-private'); expect(rendered).not.toContain('key-private'); expect(rendered).not.toContain('synthetic-private-pem')
  })
  it('clears previous project artifacts immediately even when the new scoped refresh fails', async () => {
    const vm = { ...context(), generation: 0, rows: [{ id: 'old-project-private-artifact' }], readFailed: true, can: () => true, closeBackupDialog: jest.fn(), closePlan: jest.fn() }
    vm.$store.getters.project = { id: 'new-project' }
    vm.refresh = () => Widget.methods.refresh.call(vm)
    getAPI.mockRejectedValue(new Error('new scoped discovery failed'))
    Widget.watch.configurationActor.call(vm, Widget.computed.configurationActor.call(vm))
    expect(vm.rows).toEqual([]); expect(vm.readFailed).toBe(false)
    await flush()
    expect(vm.rows).toEqual([]); expect(vm.readFailed).toBe(true)
    expect(postAPI).not.toHaveBeenCalled()
  })
})
describe('existing resource mappings and ordinary explicit clone templates', () => {
  it.each(['ko_KR', 'en'])('explains a protocol mapping blocker with existing %s labels and its source UUID', language => {
    const labels = require('@public/locales/' + language + '.json')
    const source = '11111111-1111-4111-8111-111111111111'
    const message = Widget.methods.configurationBlockerLabel.call({ $t: key => labels[key] }, 'PROTOCOL_MAPPING_REQUIRED:' + source)
    for (const key of ['label.protocol', 'label.storage.config.resource.mapping', 'label.required', 'message.error.select']) expect(message).toContain(labels[key])
    expect(message).toContain(source)
    expect(message).not.toContain('PROTOCOL_MAPPING_REQUIRED')
    expect(message).not.toContain('undefined')
  })

  it('keeps a colliding protocol plan in mapping until its preserved endpoint is explicitly selected and replanned', async () => {
    const source = '11111111-1111-4111-8111-111111111111'; const target = '22222222-2222-4222-8222-222222222222'
    const row = { kind: 'protocols', sourceUuid: source, desired: { uuid: source, protocol: 'NFS', enabled: true } }
    const blocked = { targetName: 'service', create: [row], update: [], keep: [], preserve: [{ kind: 'protocols', targetUuid: target, current: { uuid: target, protocol: 'NFS' } }], blockers: ['PROTOCOL_MAPPING_REQUIRED:' + source], requiredCredentials: [] }
    const reviewed = { ...blocked, create: [], update: [{ ...row, targetUuid: target, current: { uuid: target, protocol: 'NFS' } }], preserve: [], blockers: [], resourceMappings: { protocols: { [source]: target } } }
    const vm = { instanceId: 'protocol-mapping-instance', planTarget: { id: 'artifact' }, targetMode: 'RESTORE_EXISTING', volumeMapping: {}, resourceMapping: {}, $t: key => key, mutation: jest.fn() }
    vm.mutation.mockResolvedValueOnce({ metadata: { plan: blocked } }).mockResolvedValueOnce({ planToken: 'explicit-mapping-reviewed-token', metadata: { plan: reviewed } })
    await Widget.methods.preparePlan.call(vm)
    expect(vm.planPhase).toBe('MAPPING')
    expect(vm.planToken).toBe('')
    const options = Widget.computed.resourceMappingRows.call(vm)
    expect(options[0].options).toContainEqual({ value: target, label: 'NFS · ' + target })
    Widget.methods.setResourceMapping.call(vm, options[0], target)
    expect(vm.planToken).toBe('')
    expect(vm.reviewedPlan).toBe('')
    await Widget.methods.preparePlan.call(vm)
    expect(vm.mutation).toHaveBeenCalledTimes(2)
    expect(vm.mutation.mock.calls.every(call => call[0] === 'planStorageServiceConfigRestore')).toBe(true)
    expect(JSON.parse(vm.mutation.mock.calls[1][1].mapping).protocols).toEqual({ [source]: target })
    expect(vm.planPhase).toBe('REVIEW')
    expect(vm.planToken).toBe('explicit-mapping-reviewed-token')
  })

  it('offers only same-kind existing targets and invalidates the reviewed token/credentials when changed', () => {
    const vm = {
      targetMode: 'RESTORE_EXISTING',
      resourceMapping: {},
      planToken: 'old',
      reviewedPlan: 'old',
      credentialValues: { rule: { password: 'synthetic' } },
      $t: key => key,
      plan: { create: [{ kind: 'file-shares', sourceUuid: 'source', desired: { name: 'share' } }], update: [], keep: [], preserve: [{ kind: 'file-shares', targetUuid: 'existing' }, { kind: 'block-targets', targetUuid: 'other-kind' }] }
    }
    const rows = Widget.computed.resourceMappingRows.call(vm)
    expect(rows[0].options.map(row => row.value)).toEqual(['NEW', 'existing'])
    Widget.methods.setResourceMapping.call(vm, rows[0], 'existing')
    expect(vm.resourceMapping['file-shares'].source).toBe('existing')
    expect(vm.planToken).toBe(''); expect(vm.reviewedPlan).toBe(''); expect(vm.credentialValues).toEqual({})
    expect(vm.planPhase).toBe('MAPPING')
  })
  it('includes preserved target identities in the review instead of silently omitting them', () => {
    const rows = Widget.computed.changes.call({ plan: { create: [], update: [], keep: [], preserve: [{ kind: 'file-shares', targetUuid: 'kept' }] } })
    expect(rows[0]).toMatchObject({ targetUuid: 'kept', action: 'PRESERVE' })
  })
  it('transmits explicit existing resource mappings without passing the UI NEW sentinel as a UUID', async () => {
    const vm = {
      instanceId: 'a',
      planTarget: { id: 'backup' },
      targetMode: 'RESTORE_EXISTING',
      resourceMapping: { 'file-shares': { source: 'target', create: 'NEW' } },
      volumeMapping: { data: 'attached' },
      $t: key => key,
      mutation: jest.fn().mockResolvedValue({ id: 'plan', planToken: 'token', metadata: { plan: { create: [], update: [], keep: [], preserve: [], blockers: [], requiredCredentials: [] } } })
    }
    await Widget.methods.preparePlan.call(vm)
    expect(JSON.parse(vm.mutation.mock.calls[0][1].mapping)).toMatchObject({ 'file-shares': { source: 'target' }, volumes: { data: 'attached' } })
  })
  const template = () => ({ id: '11111111-1111-4111-8111-111111111111', templatetype: 'USER', isready: true, isdynamicallyscalable: true, hypervisor: 'KVM', arch: 'x86_64', format: 'QCOW2', templatestate: 'Active', zoneid: 'zone', checksum: 'checksum', ispublic: false, account: 'owner', domainid: 'domain' })
  it.each([{ ispublic: true }, { arch: 'aarch64' }, { templatestate: 'Inactive' }, { account: 'other' }, { zoneid: 'other' }])('rejects an ineligible private clone image %j', fields => {
    const vm = { clone: { zoneid: 'zone', backingvolumemode: 'NEW' }, resource: { account: 'owner', domainid: 'domain' } }
    expect(Widget.methods.eligibleOrdinaryCloneTemplate.call(vm, { ...template(), ...fields })).toBe(false)
    expect(Widget.methods.eligibleOrdinaryCloneTemplate.call(vm, template())).toBe(true)
  })
  it('requires the exact private template permit before constructing a clone plan', () => {
    const row = template(); const vm = { cloneRequiresIdentityTemplate: false, cloneTemplateSelection: 'EXPLICIT', clone: { zoneid: 'zone', templateid: row.id, backingvolumemode: 'NEW' }, cloneTemplateRows: [row], cloneTemplateScope: 'scope', cloneTemplateBackingMode: 'NEW', cloneDiscoveryScope: () => 'scope', resource: { account: 'owner', domainid: 'domain' }, $t: key => key }
    vm.eligibleOrdinaryCloneTemplate = value => Widget.methods.eligibleOrdinaryCloneTemplate.call(vm, value)
    expect(() => Widget.methods.assertOrdinaryCloneTemplate.call(vm)).toThrow('message.storage.config.clone.template.approval.required')
    vm.clone.validationartifactuuid = '22222222-2222-4222-8222-222222222222'; vm.clone.validationartifactsha256 = 'a'.repeat(64)
    expect(() => Widget.methods.assertOrdinaryCloneTemplate.call(vm)).not.toThrow()
  })
  it.each(['DEFAULT_SYSTEM', 'EXPLICIT'])('keeps default and explicitly approved template requests distinct: %s', async selection => {
    const row = template()
    const clone = { name: 'clone', zoneid: 'zone', backingvolumemode: 'NEW', templateid: row.id, validationartifactuuid: '22222222-2222-4222-8222-222222222222', validationartifactsha256: 'a'.repeat(64) }
    const vm = { instanceId: 'a', resource: { account: 'owner', domainid: 'domain' }, planTarget: { id: 'backup' }, targetMode: 'CREATE_NEW', cloneTemplateSelection: selection, cloneRequiresIdentityTemplate: false, ordinaryClonePrivateTemplate: selection === 'EXPLICIT', clone, cloneTemplateRows: [row], cloneTemplateScope: 'scope', cloneTemplateBackingMode: 'NEW', cloneDiscoveryScope: () => 'scope', initialVolumeSource: 'data', cloneRuntime: 'bundle', volumeMapping: {}, $t: key => key, assertCloneOffering: jest.fn() }
    vm.eligibleOrdinaryCloneTemplate = value => Widget.methods.eligibleOrdinaryCloneTemplate.call(vm, value)
    vm.assertOrdinaryCloneTemplate = () => Widget.methods.assertOrdinaryCloneTemplate.call(vm)
    vm.mutation = jest.fn().mockResolvedValue({ planToken: 'token', metadata: { plan: { blockers: [], requiredCredentials: [] } } })
    await Widget.methods.preparePlan.call(vm)
    const blueprint = JSON.parse(vm.mutation.mock.calls[0][1].mapping).createNew
    if (selection === 'DEFAULT_SYSTEM') {
      expect(blueprint.templateid).toBeUndefined(); expect(blueprint.validationartifactuuid).toBeUndefined(); expect(blueprint.validationartifactsha256).toBeUndefined()
    } else {
      expect(blueprint.templateid).toBe(row.id); expect(blueprint.validationartifactuuid).toBe(clone.validationartifactuuid); expect(blueprint.validationartifactsha256).toBe(clone.validationartifactsha256)
    }
  })
  it('maps an explicit source listener to PRIMARY and clears all previous review/secret inputs', async () => {
    const vm = {
      instanceId: 'a',
      planTarget: { id: 'backup' },
      targetMode: 'RESTORE_EXISTING',
      volumeMapping: {},
      resourceMapping: {},
      listenerMapping: {},
      listenerChoices: {},
      planToken: 'old',
      reviewedPlan: 'old',
      credentialValues: { rule: { password: 'synthetic' } },
      $t: key => key,
      mutation: jest.fn().mockResolvedValue({ planToken: 'new', metadata: { plan: { create: [], update: [], keep: [], preserve: [], blockers: [], requiredCredentials: [], listenerAddressMappings: { '10.10.13.220': 'PRIMARY' } } } })
    }
    Widget.methods.setListenerMapping.call(vm, '10.10.13.220', 'PRIMARY')
    expect(vm.credentialValues).toEqual({}); expect(vm.planToken).toBe(''); expect(vm.planPhase).toBe('MAPPING')
    await Widget.methods.preparePlan.call(vm)
    expect(JSON.parse(vm.mutation.mock.calls[0][1].mapping).listenerAddresses).toEqual({ '10.10.13.220': 'PRIMARY' })
  })
  it('shows unresolved listener requirements without guessing and keeps a custom target literal explicit', () => {
    const vm = { plan: { listenerAddressRequirements: [{ sourceAddress: '10.10.13.220', target: null, locations: [{ kind: 'file-shares', sourceUuid: 'share', path: 'listen_ip' }] }] }, listenerMapping: {}, listenerChoices: {}, credentialValues: {} }
    expect(Widget.computed.listenerAddressRows.call(vm)[0].choice).toBeUndefined()
    Widget.methods.setListenerMapping.call(vm, '10.10.13.220', 'CUSTOM')
    Widget.methods.setListenerAddress.call(vm, '10.10.13.220', ' 10.10.13.237 ')
    expect(Widget.computed.listenerAddressRows.call(vm)[0]).toMatchObject({ choice: 'CUSTOM', address: '10.10.13.237' })
  })
  it.each(['STATIC', 'DHCP'])('passes static network choices only for the chosen mode %s', async networkmode => {
    const vm = {
      instanceId: 'a',
      planTarget: { id: 'backup' },
      targetMode: 'CREATE_NEW',
      cloneTemplateSelection: 'DEFAULT_SYSTEM',
      cloneRequiresIdentityTemplate: false,
      volumeMapping: {},
      initialVolumeSource: 'data',
      cloneRuntime: 'bundle',
      clone: { name: 'clone', networkmode, ipcidr: '10.10.13.237/16', gateway: '10.10.0.1', dns1: '8.8.8.8', dns2: '1.1.1.1' },
      assertCloneOffering: jest.fn(),
      $t: key => key,
      mutation: jest.fn().mockResolvedValue({ planToken: 'token', metadata: { plan: { blockers: [], requiredCredentials: [] } } })
    }
    await Widget.methods.preparePlan.call(vm)
    const blueprint = JSON.parse(vm.mutation.mock.calls[0][1].mapping).createNew
    if (networkmode === 'STATIC') expect(blueprint).toMatchObject({ networkmode, ipcidr: vm.clone.ipcidr, gateway: vm.clone.gateway, dns1: vm.clone.dns1, dns2: vm.clone.dns2 })
    else { expect(blueprint.networkmode).toBe('DHCP'); expect(blueprint.ipcidr).toBeUndefined(); expect(blueprint.gateway).toBeUndefined(); expect(blueprint.dns1).toBeUndefined() }
  })
  it('uses the public readiness fields when listTemplates has no private database state property', () => {
    const row = template(); delete row.templatestate
    const vm = { clone: { zoneid: 'zone', backingvolumemode: 'NEW' }, resource: { account: 'owner', domainid: 'domain' } }
    expect(Widget.methods.eligibleOrdinaryCloneTemplate.call(vm, row)).toBe(true)
    expect(Widget.methods.eligibleOrdinaryCloneTemplate.call(vm, { ...row, removed: '2026-10-18' })).toBe(false)
  })
  it('proposes the selected FILE filesystem hint visibly and invalidates previous review before planning', () => {
    const vm = { plan: { sourceVolumeInventory: [{ uuid: 'file', usage: 'FILE', filesystemHint: 'EXT4' }] }, clone: { filesystem: 'XFS' }, planToken: 'old', reviewedPlan: 'old', credentialValues: { rule: { password: 'synthetic' } }, editPlanMapping: Widget.methods.editPlanMapping }
    Widget.methods.selectInitialVolume.call(vm, 'file')
    expect(vm.clone.filesystem).toBe('EXT4'); expect(Widget.computed.initialSourceFilesystemHint.call(vm)).toBe('EXT4')
    expect(vm.planToken).toBe(''); expect(vm.reviewedPlan).toBe(''); expect(vm.credentialValues).toEqual({})
  })
  it('excludes known RAW from initial choices and rejects a programmatically selected RAW before any plan POST', async () => {
    const inventory = [{ uuid: 'raw', type: 'DATADISK', usage: 'BLOCK_RAW' }, { uuid: 'unused', type: 'DATADISK', usage: 'UNUSED' }, { uuid: 'file', type: 'DATADISK', usage: 'FILE', filesystemHint: 'EXT4' }]
    expect(Widget.computed.initialSourceVolumeOptions.call({ plan: { sourceVolumeInventory: inventory }, sourceVolumes: ['raw', 'unused', 'file'] }).map(row => row.value)).toEqual(['file', 'unused'])
    const vm = { instanceId: 'a', planTarget: { id: 'backup' }, targetMode: 'CREATE_NEW', initialVolumeSource: 'raw', cloneRuntime: 'bundle', volumeMapping: {}, plan: { sourceVolumeInventory: inventory }, mutation: jest.fn(), $t: key => key }
    await Widget.methods.preparePlan.call(vm)
    expect(vm.mutation).not.toHaveBeenCalled(); expect(vm.error).toBe('message.storage.config.clone.initial.raw')
  })
})

describe('partial CREATE_NEW re-entry preserves the original allocation request', () => {
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
  function view () {
    const vm = { ...Widget.data(), ...context() }
    for (const [name, method] of Object.entries(Widget.methods)) vm[name] = method.bind(vm)
    for (const [name, getter] of Object.entries(Widget.computed)) Object.defineProperty(vm, name, { get: () => getter.call(vm), configurable: true })
    return vm
  }
  function reply (row, change = () => {}) {
    const result = { id: row.id, metadata: copy(row.metadata), planToken: 'fresh-reviewed-token' }
    result.metadata.plan.reviewedCreatedTargetTemplateBinding = { vmUuid: id(100), rootVolumeUuid: id(101) }
    change(result)
    return { planstorageserviceconfigrestoreresponse: { storageserviceconfiguration: { result: JSON.stringify(result) } } }
  }
  beforeEach(() => { clearConfigurationIntents(); getAPI.mockReset(); postAPI.mockReset() })
  afterEach(() => { clearConfigurationIntents() })

  it.each([true, false])('opens the recorded CREATE_NEW request directly for createdTarget=%s without a default restore plan', async created => {
    const row = artifact(created)
    const original = copy(row)
    postAPI.mockResolvedValue(reply(row))
    const vm = view()
    await vm.openPlan(row)
    expect(getAPI).not.toHaveBeenCalled()
    expect(postAPI).toHaveBeenCalledTimes(1)
    expect(postAPI.mock.calls[0][0]).toBe('planStorageServiceConfigRestore')
    expect(postAPI.mock.calls[0][1]).toMatchObject({ instanceid: vm.instanceId, artifactid: row.id, targetmode: 'CREATE_NEW' })
    const mapping = JSON.parse(postAPI.mock.calls[0][1].mapping)
    expect(mapping).toEqual({ ...row.metadata.requestedVolumeMapping, listenerAddresses: { '10.10.13.223': 'PRIMARY' }, confirmFileExecute: false })
    expect(mapping.createNew).not.toHaveProperty('templateid')
    expect(mapping.createNew).toHaveProperty('dns2', '')
    expect(mapping.createNew.size).toBe(20)
    expect(mapping.newVolumes[id(2)].sizeGiB).toBe(2)
    expect(vm.clone).toEqual(row.metadata.requestedVolumeMapping.createNew)
    expect(vm.cloneTemplateSelection).toBe('DEFAULT_SYSTEM')
    expect(vm.targetMode).toBe('CREATE_NEW')
    expect(vm.mappingLocked).toBe(true)
    expect(vm.planPhase).toBe('REVIEW')
    expect(vm.planToken).toBe('fresh-reviewed-token')
    expect(vm.plan.resourceMappings).toEqual(row.metadata.plan.resourceMappings)
    expect(vm.plan.reviewedCreatedTargetTemplateBinding).toEqual({ vmUuid: id(100), rootVolumeUuid: id(101) })
    expect(vm.credentialValues).toEqual({ [id(30)]: {} })
    expect(JSON.stringify(vm.cloneResume)).not.toContain('synthetic-old-capability-not-copied')
    expect(JSON.stringify(vm.cloneResume)).not.toContain('synthetic-private-cipher-not-copied')
    expect(row).toEqual(original)
  })
  it('replans the recorded scope even if a stale form event changes a displayed field', async () => {
    const row = artifact()
    postAPI.mockResolvedValue(reply(row))
    const vm = view()
    await vm.openPlan(row)
    vm.clone.dns2 = '1.1.1.1'
    await vm.preparePlan()
    expect(JSON.parse(postAPI.mock.calls[1][1].mapping).createNew.dns2).toBe('')
  })
  it.each([
    row => { delete row.metadata.requestedVolumeMapping },
    row => { row.metadata.requestedVolumeMapping.createNew.templateid = id(90) },
    row => { row.metadata.plan.targetName = 'other-target' },
    row => { delete row.metadata.plan.volumeAllocationPlan },
    row => { row.metadata.requestedVolumeMapping.createNew.password = 'synthetic-disallowed-secret' }
  ])('warns and blocks malformed partial metadata without sending any automatic plan', async change => {
    const row = artifact(false)
    change(row)
    const vm = view()
    await vm.openPlan(row)
    expect(postAPI).not.toHaveBeenCalled()
    expect(getAPI).not.toHaveBeenCalled()
    expect(vm.planTarget).toBe(row)
    expect(vm.targetMode).toBe('CREATE_NEW')
    expect(vm.cloneResumeUnavailable).toBe(true)
    expect(vm.mappingLocked).toBe(true)
    expect(vm.planToken).toBe('')
    expect(vm.error).toBe('message.storage.config.clone.resume.unavailable')
  })
  it.each([
    result => { result.metadata.plan.targetMode = 'RESTORE_EXISTING' },
    result => { result.metadata.plan.volumeAllocationPlan.planSha256 = 'd'.repeat(64) },
    result => { result.metadata.plan.listenerAddressMappings['10.10.13.223'] = '10.10.13.225' },
    result => { result.metadata.createdTargetInstanceUuid = id(200) }
  ])('does not accept a new review that rebinds the recorded target or endpoint/allocation scope', async change => {
    const row = artifact()
    postAPI.mockResolvedValue(reply(row, change))
    const vm = view()
    await vm.openPlan(row)
    expect(postAPI).toHaveBeenCalledTimes(1)
    expect(vm.planPhase).toBe('MAPPING')
    expect(vm.planToken).toBe('')
    expect(vm.error).toBe('message.storage.config.clone.resume.unavailable')
  })
  it('keeps the normal existing-service openPlan request unchanged', async () => {
    const row = { id: id(50), kind: 'BACKUP', state: 'COMPLETE', metadata: {} }
    getAPI.mockResolvedValue({ listvolumesresponse: { volume: [{ id: id(1), name: 'attached', type: 'DATADISK' }] } })
    postAPI.mockResolvedValue({ planstorageserviceconfigrestoreresponse: { storageserviceconfiguration: { result: JSON.stringify({ id: row.id, metadata: { plan: { targetMode: 'RESTORE_EXISTING', create: [], update: [], keep: [], preserve: [], blockers: [], requiredCredentials: [] } }, planToken: 'ordinary-token' }) } } })
    const vm = view()
    await vm.openPlan(row)
    expect(getAPI).toHaveBeenCalledTimes(1)
    expect(postAPI).toHaveBeenCalledTimes(1)
    expect(postAPI.mock.calls[0][1].targetmode).toBe('RESTORE_EXISTING')
    expect(JSON.parse(postAPI.mock.calls[0][1].mapping)).toEqual({ volumes: {}, confirmFileExecute: false })
    expect(vm.cloneResume).toBeNull()
    expect(vm.planPhase).toBe('REVIEW')
  })

  it('refreshes a known failed clone once and reopens the fresh partial metadata through CREATE_NEW', async () => {
    const row = artifact()
    const vm = view()
    vm.rows = [{ id: row.id, kind: 'BACKUP', state: 'COMPLETE', metadata: {} }]
    vm.can = api => api === 'listStorageServiceConfigBackups'
    postAPI.mockResolvedValueOnce({ applystorageserviceconfigrestoreresponse: { jobid: 'failed-partial-create' } })
    getAPI.mockImplementation(api => {
      if (api === 'queryAsyncJobResult') return Promise.resolve({ queryasyncjobresultresponse: { jobstatus: 2, jobresultcode: 530, jobresult: { errortext: 'synthetic-private-error' } } })
      if (api === 'listStorageServiceConfigBackups') return Promise.resolve({ liststorageserviceconfigbackupsresponse: { storageserviceconfiguration: { result: JSON.stringify({ artifacts: [row] }) } } })
      throw new Error('unexpected discovery')
    })
    await expect(vm.mutation('applyStorageServiceConfigRestore', { artifactid: row.id })).rejects.toThrow('message.storage.config.job.failed')
    expect(getAPI.mock.calls.filter(call => call[0] === 'listStorageServiceConfigBackups')).toHaveLength(1)
    expect(vm.rows[0].metadata.createdTargetInstanceUuid).toBe(id(79))
    expect(vm.readFailed).toBe(false)
    postAPI.mockResolvedValueOnce(reply(row))
    await vm.openPlan(vm.rows[0])
    expect(postAPI).toHaveBeenCalledTimes(2)
    expect(postAPI.mock.calls[1][0]).toBe('planStorageServiceConfigRestore')
    expect(postAPI.mock.calls[1][1].targetmode).toBe('CREATE_NEW')
    expect(vm.cloneResume.createdTargetInstanceUuid).toBe(id(79))
  })
  it('keeps the original failed-job error and blocks old rows when artifact refresh fails', async () => {
    const row = artifact()
    const vm = view()
    const stale = { id: row.id, kind: 'BACKUP', state: 'COMPLETE', metadata: {} }
    vm.rows = [stale]
    vm.can = api => api === 'listStorageServiceConfigBackups'
    postAPI.mockResolvedValue({ applystorageserviceconfigrestoreresponse: { jobid: 'failed-refresh-create' } })
    getAPI.mockImplementation(api => api === 'queryAsyncJobResult'
      ? Promise.resolve({ queryasyncjobresultresponse: { jobstatus: 2, jobresultcode: 530, jobresult: {} } })
      : Promise.reject(new Error('history temporarily unavailable')))
    await expect(vm.mutation('applyStorageServiceConfigRestore', { artifactid: row.id })).rejects.toThrow('message.storage.config.job.failed')
    expect(vm.rows).toEqual([stale])
    expect(vm.readFailed).toBe(true)
    const reads = getAPI.mock.calls.length
    await vm.openPlan(stale)
    expect(getAPI).toHaveBeenCalledTimes(reads)
    expect(postAPI).toHaveBeenCalledTimes(1)
    expect(vm.planTarget).toBeNull()
    expect(vm.error).toBe('message.storage.config.read.failed')
  })
  it('does not replace the known job failure if the refresh method itself rejects', async () => {
    const vm = view()
    vm.refresh = jest.fn().mockRejectedValue(new Error('refresh rejected'))
    postAPI.mockResolvedValue({ applystorageserviceconfigrestoreresponse: { jobid: 'failed-direct-refresh' } })
    getAPI.mockResolvedValue({ queryasyncjobresultresponse: { jobstatus: 2, jobresultcode: 530, jobresult: {} } })
    await expect(vm.mutation('applyStorageServiceConfigRestore', {})).rejects.toThrow('message.storage.config.job.failed')
    expect(vm.refresh).toHaveBeenCalledTimes(1)
    expect(vm.readFailed).toBe(true)
  })
})
