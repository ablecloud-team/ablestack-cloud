// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements. See the NOTICE file
// distributed with this work for additional information.
// The ASF licenses this file under the Apache License, Version 2.0.
// You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
// Unless required by law or agreed in writing, software is distributed
// on an AS IS BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND.
import { needsInitialPreparation, isPreparedBacking, freshPreparation, unavailablePreparation, canResumePreparation, observeInitialCreation } from '@/utils/sharedFsPreparation'
import Create from '@/views/storage/CreateSharedFS'
import Tab from '@/views/storage/SharedFSTab'
import { getAPI } from '@/api'
jest.mock('@/api', () => ({ getAPI: jest.fn(), postAPI: jest.fn() }))
describe('initial DATA preparation UI safety', () => {
  it.each([['NFS', true], ['SMB', true], ['ISCSI', false], ['NVME_OF', false]])('selects worker only for new file DATA %s', (type, expected) => {
    expect(needsInitialPreparation({ services: [type] })).toBe(expected)
    expect(needsInitialPreparation({ services: [type], useexistingvolume: true })).toBe(false)
  })
  it('invalidates cached resume permission after observation failure', () => {
    const original = freshPreparation({ canResume: true, status: 'OBSERVATION_PENDING' })
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
      if (api === 'getSharedFileSystemPreparation') return { getsharedfilesystempreparationresponse: { preparation: { status: loop ? 'PREPARED' : 'RUNNING' } } }
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
      : { getsharedfilesystempreparationresponse: { preparation: { status: 'OBSERVATION_PENDING', canResume: false } } })
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
})
