// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements. See the NOTICE file
// distributed with this work for additional information.
import fs from 'fs'
import path from 'path'
import CreateSharedFS from '@/views/storage/CreateSharedFS'
import { getAPI, postAPI } from '@/api'

jest.mock('@/api', () => ({ getAPI: jest.fn(), postAPI: jest.fn() }))

// The same producer fixture is evaluated by StoragePreparationHealthWireContractTest
// in Java. These expectations are not invented UI-only health payloads.
const contract = JSON.parse(fs.readFileSync(path.resolve(__dirname, '../../../../../server/src/test/resources/storage-config/preparation-health-wire-contract.json'), 'utf8'))
const sample = name => {
  const value = contract.samples.find(value => value.name === name)
  if (!value) throw new Error('Missing actual health wire sample ' + name)
  return value
}
function apiReply (value, observed) {
  return {
    getstorageservicepreparationstatusresponse: {
      storageservicepreparationstatus: {
        id: 'instance',
        sharedfilesystemid: 'fs',
        purpose: 'INITIAL_CONFIG',
        ...value.expected,
        diagnostics: JSON.stringify({
          nativeBootBefore: value.before.bootId,
          nativeBootAfter: value.after.bootId,
          healthBootId: value.health.bootId || null,
          healthOrigin: value.health.cached ? 'CACHE' : 'LIVE',
          healthStatus: value.health.status,
          healthSuccess: value.health.success,
          bootProgressPresent: !!value.health.bootReconcile
        }),
        bootid: value.after.bootId,
        observedatmillis: observed,
        expiresatmillis: observed + 30000
      }
    }
  }
}
function wizard () {
  const value = {
    form: { name: 'new-service', setupmode: 'WITH_SERVICES', services: ['NFS'] },
    initialSetupCancelled: false,
    initialAdDisposed: false,
    initialSetupStatus: 'RUNNING',
    initialVmCreated: false,
    initialServiceWriteStarted: false,
    $t: key => key,
    $store: { getters: { apis: { listSharedFileSystems: {}, listStorageServiceInstances: {}, getStorageServicePreparationStatus: {}, enableStorageServiceProtocol: {}, createStorageNfsExport: {}, listStorageNfsExports: {} } } },
    notifyStorageServiceSetup: jest.fn(),
    parentFetchData: jest.fn(),
    delay: jest.fn().mockResolvedValue(),
    pollStorageServiceSetupJob: jest.fn().mockResolvedValue({}),
    resolveCreatedSharedFileSystem: jest.fn().mockResolvedValue({ id: 'fs', virtualmachineid: 'vm', volumeid: 'data' }),
    findStorageServiceInstance: jest.fn().mockResolvedValue({ id: 'instance', virtualmachineid: 'vm' }),
    enableSelectedProtocols: jest.fn().mockResolvedValue(),
    createInitialFileServices: jest.fn().mockResolvedValue({}),
    createInitialBlockServices: jest.fn().mockResolvedValue({}),
    verifyInitialStorageServiceSetup: jest.fn().mockResolvedValue()
  }
  for (const name of ['runInitialStorageServiceSetup', 'configureInitialStorageServices', 'waitForInitialPreparation', 'assertInitialSetupActive', 'isSetupServiceSelected', 'assertStorageServiceSetupApis', 'missingStorageServiceSetupApis', 'clearInitialCredentials', 'clearInitialBlockSecrets', 'clearInitialAdCredentials', 'initialSetupFailureDescription', 'cancelInitialSetup']) value[name] = CreateSharedFS.methods[name].bind(value)
  Object.defineProperty(value, 'initialSetupSteps', { get: () => CreateSharedFS.computed.initialSetupSteps.call(value) })
  return value
}
function sequence (names) {
  let index = 0
  let observed = Date.now() - 1000
  getAPI.mockImplementation(() => Promise.resolve(apiReply(sample(names[Math.min(index++, names.length - 1)]), ++observed)))
}

describe('Actual Native health wire to preparation API to visible creation workflow', () => {
  beforeEach(() => { getAPI.mockReset(); postAPI.mockReset() })

  it('covers real live and cached producer shapes rather than assuming every health reply has BOOT metadata', () => {
    expect(contract.producer.noProtocolMutation).toBe(true)
    expect(sample('LIVE_COLD').health).not.toHaveProperty('bootId')
    expect(sample('LIVE_COLD').health).not.toHaveProperty('bootReconcile')
    expect(sample('LIVE_COLD').health.success).toBe(true)
    expect(sample('CURRENT_BOOT_READY').health.cached).toBe(true)
    expect(sample('CURRENT_BOOT_READY').health.bootReconcile.bootId).toBe(sample('CURRENT_BOOT_READY').after.bootId)
    expect(sample('PREVIOUS_BOOT_CACHE').health.bootId).not.toBe(sample('PREVIOUS_BOOT_CACHE').after.bootId)
  })

  it.each([
    ['LIVE_COLD', 'CURRENT_BOOT_NO_RECEIPT', 'CURRENT_BOOT_RECONCILING', 'CURRENT_BOOT_READY'],
    ['PREVIOUS_BOOT_CACHE', 'CURRENT_BOOT_READY'],
    ['STALE_CACHE_LIVE_FALLBACK', 'CURRENT_BOOT_READY'],
    ['MALFORMED_CACHE_LIVE_FALLBACK', 'CURRENT_BOOT_READY'],
    ['COORDINATOR_PROGRESS_ONLY_RECONCILING', 'COORDINATOR_PROGRESS_ONLY_READY']
  ])('waits through producer startup observations %j and completes once, without a manual creation retry', async (...names) => {
    const value = wizard()
    sequence(names)
    const statusBeforeWrite = []
    value.enableSelectedProtocols.mockImplementation(() => { statusBeforeWrite.push(value.initialSetupStatus); expect(getAPI.mock.calls.length).toBeGreaterThan(names.length); return Promise.resolve() })
    await value.runInitialStorageServiceSetup('created-job', value.form, 'progress')
    expect(value.initialVmCreated).toBe(true)
    expect(value.initialServiceWriteStarted).toBe(true)
    expect(value.initialSetupStatus).toBe('COMPLETE')
    expect(value.enableSelectedProtocols).toHaveBeenCalledTimes(1)
    expect(value.verifyInitialStorageServiceSetup).toHaveBeenCalledTimes(1)
    expect(statusBeforeWrite).toHaveLength(1)
    expect(value.notifyStorageServiceSetup.mock.calls.some(call => call[1] === 'error')).toBe(false)
    expect(postAPI).not.toHaveBeenCalled()
  })

  it('discards a prior READY witness after an observed BOOT transition and requires two new observations', async () => {
    const value = wizard()
    sequence(['CURRENT_BOOT_READY', 'GENERATION_BOOT_CHANGE', 'CURRENT_BOOT_READY', 'CURRENT_BOOT_READY'])
    await value.waitForInitialPreparation({ id: 'fs' }, 'progress')
    expect(getAPI).toHaveBeenCalledTimes(4)
    expect(value.enableSelectedProtocols).not.toHaveBeenCalled()
    expect(postAPI).not.toHaveBeenCalled()
  })

  it.each(['CURRENT_BOOT_RECEIPT_READ_ERROR', 'EXPIRED_READY_OBSERVATION', 'CURRENT_BOOT_READY_NETWORK_ERROR', 'COORDINATOR_READY_NETWORK_ERROR'])('stops genuine failed observation %s before service changes with an accurate preparation-stage message', async name => {
    const value = wizard()
    sequence([name])
    await value.runInitialStorageServiceSetup('created-job', value.form, 'progress')
    expect(value.initialVmCreated).toBe(true)
    expect(value.initialServiceWriteStarted).toBe(false)
    expect(value.initialSetupStatus).toBe('ERROR')
    expect(value.initialSetupMessageKey).toBe('message.storage.service.create.preparation.stopped.before.services')
    expect(value.initialPreparationFirstFailure.reason).toBe(sample(name).expected.reason)
    expect(value.initialPreparationFirstFailure.diagnostics.healthOrigin).toBe('CACHE')
    value.cancelInitialSetup()
    expect(value.initialPreparationFirstFailure).toBeNull()
    expect(value.initialPreparationObservation).toBeNull()
    expect(value.notifyStorageServiceSetup.mock.calls.some(call => call[2] === 'message.storage.service.setup.partial.failed')).toBe(false)
    expect(value.enableSelectedProtocols).not.toHaveBeenCalled()
    expect(value.verifyInitialStorageServiceSetup).not.toHaveBeenCalled()
    expect(getAPI).toHaveBeenCalledTimes(1)
    expect(postAPI).not.toHaveBeenCalled()
  })

  it('never restarts preparation or mutation after a protocol write has an unknown result', async () => {
    const value = wizard()
    sequence(['CURRENT_BOOT_READY'])
    value.enableSelectedProtocols.mockRejectedValue(new Error('controlled lost write response'))
    await value.runInitialStorageServiceSetup('created-job', value.form, 'progress')
    expect(value.initialServiceWriteStarted).toBe(true)
    expect(value.initialSetupStatus).toBe('ERROR')
    expect(value.initialSetupMessageKey).toBe('message.storage.service.setup.partial.failed')
    expect(value.enableSelectedProtocols).toHaveBeenCalledTimes(1)
    expect(getAPI).toHaveBeenCalledTimes(4)
    expect(value.verifyInitialStorageServiceSetup).not.toHaveBeenCalled()
  })
})
