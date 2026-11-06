// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements. See the NOTICE file
// distributed with this work for additional information.
// The ASF licenses this file under the Apache License, Version 2.0.
// You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
// Unless required by law or agreed in writing, software is distributed
// on an AS IS BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND.
import { needsInitialPreparation, isPreparedBacking, freshPreparation, unavailablePreparation, canResumePreparation, observeInitialCreation, preparationFromResponse, observedPrepared } from '@/utils/sharedFsPreparation'
import Create from '@/views/storage/CreateSharedFS'
import Tab from '@/views/storage/SharedFSTab'
import { getAPI } from '@/api'
jest.mock('@/api', () => ({ getAPI: jest.fn(), postAPI: jest.fn() }))
const actualWire = JSON.parse(require('fs').readFileSync(require('path').resolve(__dirname, '../../../../../server/src/test/resources/sharedfs-preparation-api-wire.json'), 'utf8'))
const wireWith = patch => {
  const wire = JSON.parse(JSON.stringify(actualWire))
  Object.assign(wire.getsharedfilesystempreparationresponse.sharedfilesystempreparation.preparation, patch)
  return wire
}

describe('initial DATA preparation UI safety', () => {
  it.each([['NFS', true], ['SMB', true], ['ISCSI', false], ['NVME_OF', false]])('selects worker only for new file DATA %s', (type, expected) => {
    expect(needsInitialPreparation({ services: [type] })).toBe(expected)
    expect(needsInitialPreparation({ services: [type], useexistingvolume: true })).toBe(false)
  })
  it('invalidates cached resume permission after observation failure', () => {
    const original = freshPreparation({ canResume: true, status: 'OBSERVATION_PENDING', observationAvailable: true })
    expect(canResumePreparation(original)).toBe(true)
    expect(canResumePreparation(unavailablePreparation(original))).toBe(false)
    expect(canResumePreparation({ ...original, status: 'RUNNING' })).toBe(false)
    expect(canResumePreparation({ ...original, status: 'RECOVERY_REQUIRED' })).toBe(false)
  })
  it('does not treat generic Ready or formatting as prepared', () => {
    expect(isPreparedBacking({ state: 'Ready' })).toBe(false)
    expect(isPreparedBacking({ preparation: { phase: 'FORMATTING' } })).toBe(false)
    expect(isPreparedBacking({ preparation: { status: 'PREPARED' } })).toBe(true)
  })
  it.each(['nfsExport', 'smbShare'])('uses prepared CURRENT %s without formatting', type => {
    const method = type === 'nfsExport' ? 'nfsExportImportMode' : 'smbShareImportMode'
    const ctx = { resource: { preparation: { status: 'PREPARED' } }, forms: { [type]: { volumemode: 'CURRENT' } } }
    expect(Tab.methods[method].call(ctx)).toBe('MOUNT_EXISTING')
    ctx.forms[type].volumemode = 'NEW'
    expect(Tab.methods[method].call(ctx)).toBe('FORMAT_EMPTY')
  })
  it('retains new NFS POSIX defaults while reusing prepared DATA mount', async () => {
    const observe = jest.fn().mockResolvedValue({ storagenfsexport: { id: 'nfs' } })
    const ctx = { initialBackingVolumeId: () => 'data', isSetupServiceSelected: (s, p) => p === 'NFS', observeInitialFileShare: observe, toCapacityBytes: () => undefined, extractCreatedId: () => 'nfs' }
    await Create.methods.createInitialFileServices.call(ctx, { id: 'instance' }, { preparation: { status: 'PREPARED' } }, { name: 'new', nfsrootsquash: true })
    expect(observe.mock.calls[0][3]).toMatchObject({ importmode: 'MOUNT_EXISTING', owneruid: 65534, ownergid: 65534, mode: '0775' })
  })
  it('keeps pending and avoids protocol APIs before Ready for NEW too', async () => {
    const fs = { id: 'fs', state: 'Allocated', creationstage: 'PREPARING' }
    const ctx = {
      pendingCreation: { request: { requestid: 'key' } },
      owner: {},
      notifyStorageServiceSetup: jest.fn(),
      pollStorageServiceSetupJob: jest.fn().mockResolvedValue({ sharedfs: fs }),
      resolveCreatedSharedFileSystem: jest.fn().mockResolvedValue(fs),
      savePendingCreation: jest.fn(),
      parentFetchData: jest.fn(),
      configureInitialStorageServices: jest.fn(),
      $t: x => x
    }
    await Create.methods.runInitialStorageServiceSetup.call(ctx, 'job', { name: 'test', services: [] }, 'notification')
    expect(ctx.configureInitialStorageServices).not.toHaveBeenCalled()
    expect(ctx.savePendingCreation).not.toHaveBeenCalledWith(null)
  })
  it('waits in five-second observations until fresh prepared and Ready', async () => {
    let loop = 0
    const get = jest.fn(async api => {
      if (api === 'listSharedFileSystems') return { listsharedfilesystemsresponse: { sharedfs: [{ id: 'fs', state: loop ? 'Ready' : 'Allocated', preparation: { status: loop ? 'PREPARED' : 'RUNNING' } }] } }
      if (api === 'getSharedFileSystemPreparation') return wireWith({ status: loop ? 'PREPARED' : 'RUNNING', canResume: !!loop })
      return { queryasyncjobresultresponse: { jobstatus: 0 } }
    })
    const sleep = jest.fn(async ms => { expect(ms).toBe(5000); loop++ })
    const result = await observeInitialCreation({ get, jobid: 'job', requestid: 'key', changed: jest.fn(), sleep })
    expect(result.sharedfs.state).toBe('Ready')
    expect(sleep).toHaveBeenCalledTimes(1)
    expect(get.mock.calls.some(x => x[0] === 'getSharedFileSystemPreparation')).toBe(true)
  })
  it('returns pending for unknown physical result even if job is successful', async () => {
    const get = jest.fn(async api => api === 'listSharedFileSystems'
      ? { listsharedfilesystemsresponse: { sharedfs: [{ id: 'fs', state: 'Allocated', preparation: {} }] } }
      : wireWith({ status: 'OBSERVATION_PENDING', canResume: false }))
    const result = await observeInitialCreation({ get, jobid: 'job', requestid: 'key', changed: jest.fn() })
    expect(result.sharedfs.state).toBe('Allocated')
    expect(get).toHaveBeenCalledTimes(2)
  })
  it('failed status GET clears a previously enabled resume', async () => {
    getAPI.mockRejectedValue(new Error('offline'))
    const ctx = {
      resource: { id: 'fs', creationrequestid: 'key' },
      preparationObserving: false,
      preparationClosed: false,
      preparationGeneration: 0,
      preparationObservation: { canResume: true, status: 'OBSERVATION_PENDING', observationAvailable: true }
    }
    jest.useFakeTimers()
    await Create.methods.observePreparation.call(ctx)
    expect(ctx.preparationObservation.canResume).toBe(false)
    expect(ctx.preparationObservation.observationAvailable).toBe(false)
    clearTimeout(ctx.preparationTimer)
    jest.useRealTimers()
  })

  it('decodes the actual cluster API wire fixture instead of a flat mock', () => {
    const state = preparationFromResponse(actualWire)
    expect(state).toMatchObject({ status: 'PREPARED', phase: 'PREPARED', formatCount: 1, canResume: true })
    expect(observedPrepared(state)).toBe(true)
    expect(() => preparationFromResponse({ getsharedfilesystempreparationresponse: { preparation: state } })).toThrow()
  })
  it('does not turn a server observation failure into fresh resume permission', () => {
    const state = preparationFromResponse(wireWith({ observationAvailable: false, canResume: true, status: 'OBSERVATION_PENDING' }))
    expect(canResumePreparation(freshPreparation(state))).toBe(false)
    expect(observedPrepared(state)).toBe(false)
  })
  it('continues observing a canceled management job while the guest worker is running', async () => {
    let loop = 0
    const get = jest.fn(async api => {
      if (api === 'listSharedFileSystems') return { listsharedfilesystemsresponse: { sharedfs: [{ id: 'fs', state: loop ? 'Ready' : 'Allocated', preparation: {} }] } }
      if (api === 'getSharedFileSystemPreparation') return wireWith({ status: loop ? 'PREPARED' : 'RUNNING', canResume: !!loop })
      return { queryasyncjobresultresponse: { jobstatus: 2, jobresult: { errortext: 'Canceled on restart' } } }
    })
    const result = await observeInitialCreation({ get, jobid: 'old-job', requestid: 'same-key', changed: jest.fn(), sleep: async () => { loop++ } })
    expect(result.sharedfs.state).toBe('Ready')
    expect(observedPrepared(result.preparationObservation)).toBe(true)
    expect(loop).toBe(1)
  })
  it('the dialog reads the same actual serialized preparation object', async () => {
    getAPI.mockResolvedValue(actualWire)
    const ctx = { resource: { id: 'fs', creationrequestid: 'key' }, preparationObserving: false, preparationClosed: false, preparationGeneration: 0 }
    jest.useFakeTimers()
    await Create.methods.observePreparation.call(ctx)
    expect(ctx.preparationObservation.status).toBe('PREPARED')
    expect(ctx.preparationObservation.operationId).toBe(preparationFromResponse(actualWire).operationId)
    clearTimeout(ctx.preparationTimer); jest.useRealTimers()
  })
  it('Ready with an unavailable fresh observation cannot apply protocols or clear pending', async () => {
    const fs = { id: 'fs', state: 'Ready', preparation: {} }
    getAPI.mockImplementation(async api => api === 'listSharedFileSystems'
      ? { listsharedfilesystemsresponse: { sharedfs: [fs] } }
      : wireWith({ status: 'OBSERVATION_PENDING', observationAvailable: false, canResume: false }))
    const ctx = {
      pendingCreation: { request: { requestid: 'key' } },
      owner: {},
      notifyStorageServiceSetup: jest.fn(),
      resolveCreatedSharedFileSystem: async () => fs,
      savePendingCreation: jest.fn(),
      parentFetchData: jest.fn(),
      configureInitialStorageServices: jest.fn()
    }
    await Create.methods.runInitialStorageServiceSetup.call(ctx, 'job', { name: 'test', services: ['NFS'] }, 'notice')
    expect(ctx.configureInitialStorageServices).not.toHaveBeenCalled()
    expect(ctx.savePendingCreation).not.toHaveBeenCalledWith(null)
  })

  it('uses preparation observation after reopening without the original wizard snapshot', async () => {
    const fs = { id: 'fs', state: 'Ready', preparation: {} }
    getAPI.mockImplementation(async api => api === 'listSharedFileSystems'
      ? { listsharedfilesystemsresponse: { sharedfs: [fs] } } : actualWire)
    const ctx = {
      preparationMode: true,
      resource: fs,
      pendingCreation: { request: { requestid: 'key' } },
      owner: {},
      notifyStorageServiceSetup: jest.fn(),
      pollStorageServiceSetupJob: jest.fn(),
      resolveCreatedSharedFileSystem: async () => fs,
      savePendingCreation: jest.fn(),
      parentFetchData: jest.fn(),
      configureInitialStorageServices: jest.fn()
    }
    await Create.methods.runInitialStorageServiceSetup.call(ctx, 'resume-job', { name: 'test', services: [] }, 'notice')
    expect(ctx.pollStorageServiceSetupJob).not.toHaveBeenCalled()
    expect(ctx.configureInitialStorageServices).toHaveBeenCalled()
    expect(ctx.savePendingCreation).toHaveBeenCalledWith(null)
  })
})
