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

import Runtime from '@/views/storage/StorageServiceRuntimeUpgrade'
import Preparation from '@/views/storage/StoragePreparationStatus'
import Fixture from '@/views/storage/StorageValidationPreparation'
import { readStorageValidationPreparation, storageValidationScope } from '@/utils/storageValidationPreparation'
import { getAPI, postAPI } from '@/api'

jest.mock('@/api', () => ({ getAPI: jest.fn(), postAPI: jest.fn() }))
jest.mock('@/utils/listRefresh', () => ({ canRefreshList: () => true }))

const bundle = (id = 'bundle-a') => ({ id, state: 'AVAILABLE', version: 'test-version', catalog: JSON.stringify({ verification: { verified: true, manifest: {} } }) })
const store = (apis = {}) => ({ getters: { userInfo: { id: 'admin', roletype: 'Admin' }, apis, project: null }, state: { user: { token: 'session-a' } } })
const apis = { getStorageServicePreparationStatus: {}, preflightStorageServiceRuntimeUpgrade: {}, upgradeStorageServiceRuntime: {}, rollbackStorageServiceRuntimeUpgrade: {}, getStorageRenderedValidationPreparation: {}, prepareStorageRenderedValidationProfile: {} }
const bind = (component, extra = {}) => {
  const value = { ...component.data(), ...component.methods, $store: store(apis), $t: key => key, $emit: jest.fn(), $notifyError: jest.fn(), ...extra }
  Object.entries(component.computed || {}).forEach(([key, getter]) => { Object.defineProperty(value, key, { configurable: true, get: () => getter.call(value) }) })
  return value
}
const ready = (offset = 0, extra = {}) => ({ id: 'instance', sharedfilesystemid: 'sharedfs', purpose: 'RUNTIME_UPGRADE', ready: true, status: 'READY', bootid: 'boot-a', observedatmillis: Date.now() - 1000 + offset, expiresatmillis: Date.now() + 29000 + offset, ...extra })
const prepResponse = value => ({ getstorageservicepreparationstatusresponse: { storageservicepreparationstatus: value } })
const runtime = (extra = {}) => bind(Runtime, { resource: { id: 'sharedfs', name: 'service' }, bundles: [bundle()], selectedBundleId: 'bundle-a', preparationReady: true, preparation: ready(), capability: { available: true }, ...extra })
const volume = (type, extra = {}) => ({ type, provisioningType: 'SPARSE', attachedToFixture: true, ownerAndZoneVerified: true, newDataWithoutBacking: type === 'DATADISK', ...extra })
const preview = (extra = {}) => ({
  instanceUuid: 'instance',
  instanceName: 'service',
  vmUuid: 'vm-a',
  sharedFileSystemUuid: 'sharedfs',
  productionCapability: false,
  eligible: true,
  enabled: false,
  blockers: [],
  profileRevision: 0,
  observedAtMillis: Date.now(),
  previewExpiresAtMillis: Date.now() + 300000,
  expiresAtMillis: 0,
  previewToken: '1'.repeat(64),
  expectedCliSha256: '2'.repeat(64),
  sourceCommit: '3'.repeat(40),
  bindings: { rootVolumeUuid: 'root', volumes: { root: volume('ROOT'), nfs: volume('DATADISK'), smb: volume('DATADISK'), iscsi: volume('DATADISK'), nvme: volume('DATADISK') } },
  ...extra
})
const fixtureResponse = value => ({ storageserviceruntime: { success: true, resultjson: JSON.stringify(value) } })
const fixture = (extra = {}) => {
  const value = bind(Fixture, { instanceId: 'instance', instanceName: 'service', active: true, scopeKey: '/operations', preview: preview(), ...extra })
  value.confirmation = 'service'; value.reviewedScope = storageValidationScope(value.preview)
  return value
}

describe('Runtime preparation and explicit target controls', () => {
  beforeEach(() => { getAPI.mockReset(); postAPI.mockReset() })
  it('leaves the target unselected regardless of catalog order and existing preflight', async () => {
    getAPI.mockImplementation(command => Promise.resolve({
      getstorageserviceruntimeupgradecapabilitiesresponse: { storageserviceruntimecapability: { available: true } },
      liststorageserviceruntimebundlesresponse: { storageserviceruntimebundle: [bundle('old-foundation'), bundle('new-fix')] },
      liststorageserviceruntimeupgradesresponse: { storageserviceruntimeupgrade: [{ state: 'PREFLIGHT_READY', bundleid: 'old-foundation' }] }
    }))
    const value = runtime({ selectedBundleId: undefined })
    await value.fetchData()
    expect(value.selectedBundleId).toBeUndefined()
    expect(value.canUpgrade).toBe(false)
  })
  it.each([
    { bundles: [bundle('foreign-bundle')] },
    { $store: store({}) }
  ])('does not preflight without an available selected bundle or permission %p', async extra => {
    const value = runtime(extra)
    await value.runPreflight()
    expect(postAPI).not.toHaveBeenCalled()
    expect(getAPI).not.toHaveBeenCalled()
  })
  it('preserves legacy runtime preflight without a preparation API observation', () => {
    const value = runtime({ preparation: null, preparationReady: false, capability: { available: false }, startAsync: jest.fn(), $store: store({ preflightStorageServiceRuntimeUpgrade: {} }) })
    value.runPreflight()
    expect(value.startAsync).toHaveBeenCalledWith('preflight', 'preflightStorageServiceRuntimeUpgrade', { sharedfilesystemid: 'sharedfs', bundleid: 'bundle-a' })
  })
  it('preserves protected runtime activation without an extra preparation prerequisite', () => {
    const value = runtime({ preparation: null, preparationReady: false, upgrades: [{ id: 'preflight', state: 'PREFLIGHT_READY', bundleid: 'bundle-a' }], startAsync: jest.fn() })
    value.runUpgrade()
    expect(value.startAsync).toHaveBeenCalledWith('upgrade', 'upgradeStorageServiceRuntime', { upgradeid: 'preflight' })
    value.selectedBundleId = 'bundle-b'
    value.runUpgrade()
    expect(value.startAsync).toHaveBeenCalledTimes(1)
  })
})

describe('Visible preparation observation', () => {
  beforeEach(() => { jest.useFakeTimers(); getAPI.mockReset() })
  afterEach(() => { jest.clearAllTimers(); jest.useRealTimers() })
  const widget = () => bind(Preparation, { sharedFileSystemId: 'sharedfs', instanceId: 'instance', purpose: 'INITIAL_CONFIG', targetBundleId: '', active: true, scopeKey: '/operations' })
  it('requires two fresh same-BOOT observations and accepts an empty newly created service', async () => {
    const value = widget()
    getAPI.mockResolvedValueOnce(prepResponse(ready(0, { purpose: 'INITIAL_CONFIG', conditions: [{ name: 'configuration', ready: true, status: 'UNCONFIGURED' }] })))
      .mockResolvedValueOnce(prepResponse(ready(500, { purpose: 'INITIAL_CONFIG' })))
    await value.refresh()
    expect(value.confirmedReady).toBe(false)
    await value.refresh()
    expect(value.confirmedReady).toBe(true)
  })
  it('withdraws readiness after observation failure instead of retaining old success', async () => {
    const value = widget(); value.receipt = ready(500, { purpose: 'INITIAL_CONFIG' }); value.previousReceipt = ready(0, { purpose: 'INITIAL_CONFIG' })
    getAPI.mockRejectedValue(new Error('timeout'))
    await value.refresh()
    expect(value.readError).toBe(true)
    expect(value.receipt).toBeNull()
    expect(value.confirmedReady).toBe(false)
  })
  it('does not publish a previous service or login observation', async () => {
    let complete
    getAPI.mockImplementation(() => new Promise(resolve => { complete = resolve }))
    const value = widget()
    const result = value.refresh()
    value.$store.state.user.token = 'new-session'
    complete(prepResponse(ready(0, { purpose: 'INITIAL_CONFIG' })))
    await result
    expect(value.receipt).toBeNull()
    expect(value.$emit).not.toHaveBeenCalled()
  })
})

describe('Disposable fixture authorization through UI', () => {
  beforeEach(() => { getAPI.mockReset(); postAPI.mockReset() })
  afterEach(() => { jest.clearAllTimers() })
  it.each(['User', 'DomainAdmin'])('hides test-only authorization from %s', role => {
    const value = fixture(); value.$store.getters.userInfo.roletype = role
    expect(value.supported).toBe(false)
    expect(value.canOpen).toBe(false)
  })
  it('does not allow authorization from a project context or with missing API permission', async () => {
    const value = fixture(); value.$store.getters.project = { id: 'project' }
    await value.refresh(); expect(getAPI).not.toHaveBeenCalled()
    value.$store.getters.project = null; value.$store.getters.apis = { getStorageRenderedValidationPreparation: {} }
    await value.save(); expect(postAPI).not.toHaveBeenCalled()
  })
  it('shows server blockers without turning them into an approvable default', async () => {
    const value = fixture()
    getAPI.mockResolvedValue({ getstoragerenderedvalidationpreparationresponse: fixtureResponse(preview({ eligible: false, blockers: ['Exactly four new DATA required'] })) })
    await value.refresh()
    expect(value.preview.blockers).toContain('Exactly four new DATA required')
    expect(value.canOpen).toBe(false)
  })
  it('keeps an unavailable approved artifact as a visible blocker without inventing an expiry', () => {
    const result = readStorageValidationPreparation(fixtureResponse(preview({ eligible: false, enabled: true, expiresAtMillis: null, blockers: ['PROFILE_ARTIFACT_UNAVAILABLE'] })), 'getStorageRenderedValidationPreparation', 'instance', 'service')
    expect(result.blockers).toEqual(['PROFILE_ARTIFACT_UNAVAILABLE'])
    expect(result.expiresAtMillis).toBeNull()
  })
  it.each([
    { instanceUuid: 'other-instance' },
    { instanceName: 'other-service' },
    { productionCapability: true },
    { observedAtMillis: Date.now() - 300001 },
    { previewToken: 'unverified' },
    { previewExpiresAtMillis: Date.now() - 1 }
  ])('rejects foreign, stale or unprotected fixture evidence %p', extra => {
    expect(() => readStorageValidationPreparation(fixtureResponse(preview(extra)), 'getStorageRenderedValidationPreparation', 'instance', 'service')).toThrow()
  })
  it('rejects THIN, reused DATA, missing DATA and a changed ROOT binding', () => {
    for (const update of [
      data => { data.bindings.volumes.nfs.provisioningType = 'THIN' },
      data => { data.bindings.volumes.smb.newDataWithoutBacking = false },
      data => { delete data.bindings.volumes.nvme },
      data => { delete data.bindings.rootVolumeUuid }
    ]) {
      const data = preview(); update(data)
      expect(() => readStorageValidationPreparation(fixtureResponse(data), 'getStorageRenderedValidationPreparation', 'instance', 'service')).toThrow()
    }
  })
  it('requires exact displayed name, reviewed scope and a bounded lifetime', async () => {
    const value = fixture(); value.confirmation = 'service '
    await value.save(); expect(postAPI).not.toHaveBeenCalled()
    value.confirmation = 'service'; value.preview.previewToken = '9'.repeat(64)
    await value.save(); expect(postAPI).not.toHaveBeenCalled()
    value.reviewedScope = storageValidationScope(value.preview); value.expiresInHours = 25
    await value.save(); expect(postAPI).not.toHaveBeenCalled()
  })
  it('sends the reviewed preview token and revision and accepts only verified matching completion', async () => {
    const value = fixture({ visible: true })
    postAPI.mockResolvedValue({ preparestoragerenderedvalidationprofileresponse: { jobid: 'job' } })
    getAPI.mockResolvedValue({ queryasyncjobresultresponse: { jobstatus: 1, jobresult: fixtureResponse(preview({ enabled: true, profileRevision: 1, expiresAtMillis: Date.now() + 3600000, artifactUuid: 'artifact', artifactSha256: '4'.repeat(64) })) } })
    await value.save()
    expect(postAPI.mock.calls[0][1]).toEqual({ instanceid: 'instance', expectedprofilerevision: 0, previewtoken: '1'.repeat(64), confirmation: 'service', expiresinhours: 4 })
    expect(value.visible).toBe(false)
    expect(value.preview.enabled).toBe(true)
    expect(value.$emit).toHaveBeenCalledWith('operation-updated', 'instance')
  })
  it('does not claim completion for a foreign or rebound fixture async result', async () => {
    const value = fixture({ visible: true })
    postAPI.mockResolvedValue({ preparestoragerenderedvalidationprofileresponse: { jobid: 'job' } })
    getAPI.mockResolvedValue({ queryasyncjobresultresponse: { jobstatus: 1, jobresult: fixtureResponse(preview({ instanceUuid: 'other', enabled: true, profileRevision: 1, expiresAtMillis: Date.now() + 3600000 })) } })
    await value.save()
    expect(value.visible).toBe(true)
    expect(value.preview.enabled).toBe(false)
    expect(value.$emit).not.toHaveBeenCalled()
  })
  it('never repeats an approval POST automatically after unknown transport outcome', async () => {
    const value = fixture({ visible: true })
    postAPI.mockRejectedValue(new Error('response lost'))
    await value.save()
    value.close(); value.open()
    await value.save()
    expect(postAPI).toHaveBeenCalledTimes(1)
    expect(value.attempted).toBe(true)
    expect(value.canOpen).toBe(false)
  })
  it('follows a known async job by READ after a polling interruption without reposting approval', async () => {
    const value = fixture({ visible: true })
    postAPI.mockResolvedValue({ preparestoragerenderedvalidationprofileresponse: { jobid: 'known-job' } })
    getAPI.mockRejectedValueOnce(new Error('poll lost'))
    await value.save()
    expect(value.jobId).toBe('known-job')
    getAPI.mockResolvedValue({ queryasyncjobresultresponse: { jobstatus: 1, jobresult: fixtureResponse(preview({ enabled: true, profileRevision: 1, expiresAtMillis: Date.now() + 3600000, artifactUuid: 'artifact', artifactSha256: '4'.repeat(64) })) } })
    await value.save()
    expect(postAPI).toHaveBeenCalledTimes(1)
    expect(value.visible).toBe(false)
  })
})
