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
jest.mock('@/api', () => ({ getAPI: jest.fn(), postAPI: jest.fn() }))

const context = () => ({
  instanceId: 'owned-instance',
  instanceName: 'owned-service',
  resource: { name: 'different-sharedfs-name' },
  includeRuntime: true,
  retentionHours: 168,
  includeAdIdentity: false,
  backupMaintenance: false,
  backupConfirmation: '',
  identityBackupSupported: true,
  backupDialog: true,
  busy: '',
  error: '',
  $t: key => key,
  mutation: jest.fn().mockResolvedValue({}),
  refresh: jest.fn().mockResolvedValue(),
  buildBackupRequest: Widget.methods.buildBackupRequest,
  closeBackupDialog: Widget.methods.closeBackupDialog
})

describe('Protected SMB identity backup opt-in', () => {
  beforeEach(() => { getAPI.mockReset(); postAPI.mockReset() })

  it('preserves default backup fields without any approval or identity parameters', () => {
    const vm = context(); vm.instanceName = ''; vm.identityBackupSupported = false
    expect(vm.buildBackupRequest()).toEqual({ includeruntime: true, retentionhours: 168 })
  })

  it('only reports opt-in input support for an exact service name and all three API fields', () => {
    const vm = context(); vm.$getApiParams = () => ({ includeadidentity: {}, maintenancewindow: {}, confirmation: {} })
    expect(Widget.computed.identityBackupSupported.call(vm)).toBe(true)
    vm.$getApiParams = () => ({ includeadidentity: {} })
    expect(Widget.computed.identityBackupSupported.call(vm)).toBe(false)
    vm.$getApiParams = () => ({ includeadidentity: {}, maintenancewindow: {}, confirmation: {} }); vm.instanceName = ''
    expect(Widget.computed.identityBackupSupported.call(vm)).toBe(false)
  })

  it('sends explicit approval using the user-entered instance name and no alternate local-user field', () => {
    const vm = context(); Object.assign(vm, { includeAdIdentity: true, backupMaintenance: true, backupConfirmation: 'owned-service' })
    expect(vm.buildBackupRequest()).toEqual({ includeruntime: true, retentionhours: 168, includeadidentity: true, maintenancewindow: true, confirmation: 'owned-service' })
    expect(vm.buildBackupRequest().includeLocalUsers).toBeUndefined()
  })

  it('blocks missing approval, wrong SharedFS name, whitespace or case changes before mutation', async () => {
    for (const extra of [{ backupMaintenance: false }, { backupConfirmation: 'different-sharedfs-name' }, { backupConfirmation: 'owned-service ' }, { backupConfirmation: 'OWNED-SERVICE' }]) {
      const vm = { ...context(), includeAdIdentity: true, backupMaintenance: true, backupConfirmation: 'owned-service', ...extra }
      await Widget.methods.createBackup.call(vm)
      expect(vm.mutation).not.toHaveBeenCalled()
      expect(vm.error).toBe('message.storage.service.ad.maintenance.required')
      expect(vm.includeAdIdentity).toBe(true)
      expect(vm.backupMaintenance).toBe(false)
      expect(vm.backupConfirmation).toBe('')
      await Widget.methods.createBackup.call(vm)
      expect(vm.mutation).not.toHaveBeenCalled()
    }
  })

  it('rejects string opt-in/approval flags without coercing them to true', () => {
    for (const extra of [{ includeAdIdentity: 'true' }, { backupMaintenance: 'true' }]) {
      const vm = { ...context(), includeAdIdentity: true, backupMaintenance: true, backupConfirmation: 'owned-service', ...extra }
      expect(() => vm.buildBackupRequest()).toThrow('message.storage.service.ad.maintenance.required')
    }
  })

  it('rejects unsupported API schema before submitting an opt-in request', async () => {
    const vm = { ...context(), includeAdIdentity: true, identityBackupSupported: false, backupMaintenance: true, backupConfirmation: 'owned-service' }
    await Widget.methods.createBackup.call(vm)
    expect(vm.mutation).not.toHaveBeenCalled()
    expect(vm.error).toBe('message.storage.service.ad.maintenance.unsupported')
  })

  it('reopens without reusing a previous identity opt-in or service-name approval', () => {
    const vm = { ...context(), includeAdIdentity: true, backupMaintenance: true, backupConfirmation: 'owned-service' }
    Widget.methods.openBackupDialog.call(vm)
    expect(vm.backupDialog).toBe(true)
    expect(vm.includeAdIdentity).toBe(false)
    expect(vm.backupMaintenance).toBe(false)
    expect(vm.backupConfirmation).toBe('')
  })

  it('clears old approvals when the instance ID or service name changes', () => {
    const first = { ...context(), generation: 0, rows: [{ id: 'old' }], closePlan: jest.fn() }
    Widget.watch.instanceId.call(first)
    expect(first.backupDialog).toBe(false)
    expect(first.backupConfirmation).toBe('')
    expect(first.rows).toEqual([])
    const second = { ...context(), includeAdIdentity: true, backupMaintenance: true, backupConfirmation: 'owned-service' }
    Widget.watch.instanceName.call(second)
    expect(second.includeAdIdentity).toBe(false)
    expect(second.backupMaintenance).toBe(false)
  })

  it('removing the opt-in clears approval while preserving runtime and retention choices', () => {
    const vm = { ...context(), backupMaintenance: true, backupConfirmation: 'owned-service' }
    Widget.watch.includeAdIdentity.call(vm, false)
    expect(vm.backupMaintenance).toBe(false)
    expect(vm.backupConfirmation).toBe('')
    expect(vm.includeRuntime).toBe(true)
    expect(vm.retentionHours).toBe(168)
  })

  it('does not refresh a new identity scope after a pending backup returns', async () => {
    const vm = context(); vm.mutation.mockImplementation(async () => { vm.instanceName = 'renamed-service'; return {} })
    await Widget.methods.createBackup.call(vm)
    expect(vm.refresh).not.toHaveBeenCalled()
    expect(vm.error).toBe('message.storage.config.scope.changed')
  })

  it('preserves artifacts and clears approvals after failure without automatic retry or delete', async () => {
    const vm = { ...context(), includeAdIdentity: true, backupMaintenance: true, backupConfirmation: 'owned-service' }
    vm.mutation.mockRejectedValue(new Error('controlled failure'))
    await Widget.methods.createBackup.call(vm)
    expect(vm.mutation).toHaveBeenCalledTimes(1)
    expect(vm.refresh).not.toHaveBeenCalled()
    expect(vm.includeAdIdentity).toBe(false)
    expect(vm.backupMaintenance).toBe(false)
    expect(vm.busy).toBe('')
  })

  it('prevents duplicate submission while an operation is already pending', async () => {
    const vm = { ...context(), busy: 'BACKUP' }
    await Widget.methods.createBackup.call(vm)
    expect(vm.mutation).not.toHaveBeenCalled()
  })

  it('uses normal async failure as a failed backup rather than successful identity inclusion', async () => {
    const vm = context(); vm.unwrap = Widget.methods.unwrap; vm.$emit = jest.fn(); vm.mutation = Widget.methods.mutation
    postAPI.mockResolvedValue({ createstorageserviceconfigbackupresponse: { jobid: 'job-a' } })
    getAPI.mockResolvedValue({ queryasyncjobresultresponse: { jobstatus: 2, jobresult: { errortext: 'controlled job failure' } } })
    await Widget.methods.createBackup.call(vm)
    expect(vm.refresh).not.toHaveBeenCalled()
    expect(vm.error).toBe('controlled job failure')
  })
  it('uses exact public descriptor shape and server coverage to display encrypted identity preservation', () => {
    const descriptor = { schemaVersion: 1, kind: 'STORAGE_AD_SEMANTIC_SOURCE', ownerArtifactUuid: '11111111-1111-4111-8111-111111111111', sourceInstanceUuid: '22222222-2222-4222-8222-222222222222', sourceOperationUuid: '33333333-3333-4333-8333-333333333333', sourceConfigurationSha256: 'a'.repeat(64), ciphertextSha256: 'b'.repeat(64), issuerMac: 'c'.repeat(64) }
    const vm = context()
    const label = extra => Widget.methods.identityCoverageLabel.call(vm, { metadata: { adIdentityCoverage: 'VERIFIED_ENCRYPTED_FULL_IDENTITY', adIdentitySourceDescriptor: { ...descriptor, ...extra } } })
    expect(label({})).toBe('label.storage.config.identity.preserved')
    for (const extra of [{ schemaVersion: '1' }, { kind: 'foreign' }, { issuerMac: 'C'.repeat(64) }, { sourceOperationUuid: 'foreign' }, { privateKey: 'unexpected' }]) expect(label(extra)).toBe('label.storage.config.identity.unverified')
  })

  it('does not infer identity preservation from request flags or a missing descriptor', () => {
    const vm = context()
    for (const metadata of [{ includeadidentity: true }, { adIdentityCoverage: 'VERIFIED_ENCRYPTED_FULL_IDENTITY' }, { adIdentityCoverage: 'VERIFIED_ENCRYPTED_FULL_IDENTITY', adIdentitySourceDescriptor: null }]) expect(Widget.methods.identityCoverageLabel.call(vm, { metadata })).toBe('label.storage.config.identity.unverified')
  })
})
