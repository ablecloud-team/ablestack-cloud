// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements. See the NOTICE file
// distributed with this work for additional information.
// The ASF licenses this file under the Apache License, Version 2.0.
// You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
// Unless required by law or agreed in writing, software is distributed
// on an AS IS BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND.
import { backingRequest, backingFilesystem, publicCreationSetup, creationRequestId } from '@/utils/sharedFsBacking'
import storageConfig from '@/config/section/storage'
import Create from '@/views/storage/CreateSharedFS'
import SharedFSTab from '@/views/storage/SharedFSTab'
import { getAPI } from '@/api'
jest.mock('@/api', () => ({ getAPI: jest.fn(), postAPI: jest.fn() }))
describe('SharedFS initial backing contract', () => {
  beforeAll(() => { global.crypto = require('crypto').webcrypto })
  it('sends existing volume without hidden new-disk values', () => {
    const r = backingRequest({ useexistingvolume: true, existingvolumeid: 'data-1', diskofferingid: 'stale', size: 20, storageid: 'stale', miniops: 100, maxiops: 200 }, 'key')
    expect(r).toEqual({ volumeid: 'data-1', filesystem: 'AUTO', importmode: 'MOUNT_EXISTING', requestid: 'key' })
  })
  it('preserves explicit new EXT4 intent and existing allocation fields', () => {
    const r = backingRequest({ filesystem: 'EXT4', diskofferingid: 'sparse', storageid: 'pool', size: 20 }, 'key')
    expect(r).toEqual({ filesystem: 'EXT4', diskofferingid: 'sparse', storageid: 'pool', size: 20, requestid: 'key' })
  })
  it.each(['FORMAT_EMPTY', 'FORMAT_IF_EMPTY', 'FORMAT_NEW'])('rejects imported destructive mode %s', importmode => {
    expect(() => backingRequest({ useexistingvolume: true, existingvolumeid: 'data', importmode })).toThrow()
  })
  it('does not persist any protocol credentials in recovery data', () => {
    const publicSetup = publicCreationSetup({ name: 's', services: ['NFS'], smblocalpassword: 'test-only', smbadpassword: 'test-only', nvmedhchapkey: 'test-only' })
    expect(publicSetup).toEqual({ name: 's', services: ['NFS'] })
  })
  it('keeps observed EXT4 and never presents AUTO as XFS', () => {
    expect(backingFilesystem({ filesystem: 'EXT4' })).toBe('ext4')
    expect(backingFilesystem({ filesystem: 'AUTO' })).toBe('auto')
    expect(backingFilesystem({ filesystem: 'XFS' }, 'ext4')).toBe('ext4')
  })
  it('uses a canonical random key for the owner-scoped journal', () => {
    const one = creationRequestId()
    expect(one).toMatch(/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/)
    expect(creationRequestId()).not.toBe(one)
  })
  it('keeps imported CURRENT backing on a non-formatting path', () => {
    expect(SharedFSTab.methods.nfsExportImportMode.call({ resource: { backingmode: 'EXISTING' }, forms: { nfsExport: { volumemode: 'CURRENT' } } })).toBe('MOUNT_EXISTING')
    expect(SharedFSTab.methods.smbShareImportMode.call({ resource: { backingmode: 'EXISTING' }, forms: { smbShare: { volumemode: 'CURRENT' } } })).toBe('MOUNT_EXISTING')
  })
  it('reuses only the same initial share identity after a lost UI result', async () => {
    const params = { instanceid: 'new-instance', name: 'initial-nfs', volumeid: 'data', path: '/export/initial-nfs' }
    getAPI.mockResolvedValue({ liststoragenfsexportsresponse: { storagenfsexport: [{ id: 'share', name: params.name, volumeid: 'data', path: params.path }] } })
    const context = { runStorageServiceSetup: jest.fn(), $t: x => x }
    const result = await Create.methods.observeInitialFileShare.call(context, 'createStorageNfsExport', 'listStorageNfsExports', 'storagenfsexport', params)
    expect(result.storagenfsexport.id).toBe('share')
    expect(context.runStorageServiceSetup).not.toHaveBeenCalled()
    await expect(Create.methods.observeInitialFileShare.call(context, 'createStorageNfsExport', 'listStorageNfsExports', 'storagenfsexport', { ...params, volumeid: 'different' })).rejects.toThrow()
  })

  it('observes corrupt preparation without starting another creation or mount', async () => {
    getAPI.mockResolvedValue({ listsharedfilesystemsresponse: { sharedfs: [{ id: 'failed-fs', state: 'Allocated', creationstage: 'CORRUPT' }] } })
    const context = {
      pendingCreation: { request: { name: 'failed', requestid: 'key' }, setup: {} },
      loading: false,
      owner: {},
      $t: x => x,
      savePendingCreation: jest.fn(),
      runStorageServiceSetup: jest.fn(),
      $notification: { warning: jest.fn() },
      $notifyError: jest.fn()
    }
    await Create.methods.continuePendingCreation.call(context, 'CONTINUE')
    expect(context.savePendingCreation).toHaveBeenCalledWith(expect.objectContaining({ phase: 'CORRUPT', entityid: 'failed-fs' }))
    expect(context.$notification.warning).toHaveBeenCalled()
    expect(context.runStorageServiceSetup).not.toHaveBeenCalled()
    expect(context.$notifyError).not.toHaveBeenCalled()
    expect(context.loading).toBe(false)
  })

  it('exposes corrupt reservation cancellation only before a VM exists', () => {
    const section = storageConfig.children.find(x => x.name === 'sharedfs')
    const action = section.actions.find(x => x.api === 'resumeSharedFileSystemCreation')
    const record = { id: 'failed-fs', state: 'Allocated', creationstage: 'CORRUPT', creationrequestid: 'original-key' }
    expect(action.show(record)).toBe(true)
    expect(action.mapping.requestid.value(record)).toBe('original-key')
    expect(action.mapping.action.value()).toBe('CANCEL_PREPARATION')
    expect(action.show({ ...record, virtualmachineid: 'existing-vm' })).toBe(false)
    expect(action.show({ ...record, state: 'Ready' })).toBe(false)
    expect(action.show({ ...record, creationstage: 'INSPECTED' })).toBe(false)
  })
})
