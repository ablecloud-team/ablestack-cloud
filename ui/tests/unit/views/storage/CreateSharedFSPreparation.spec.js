// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements. See the NOTICE file
// distributed with this work for additional information.
import CreateSharedFS from '@/views/storage/CreateSharedFS'
import { getAPI, postAPI } from '@/api'

jest.mock('@/api', () => ({ getAPI: jest.fn(), postAPI: jest.fn() }))
const methods = CreateSharedFS.methods
const fs = { id: 'fs', virtualmachineid: 'vm', volumeid: 'data' }
const wire = (status, timestamp, boot = 'boot-1') => ({
  getstorageservicepreparationstatusresponse: {
    storageservicepreparationstatus: {
      id: 'instance',
      sharedfilesystemid: 'fs',
      purpose: 'INITIAL_CONFIG',
      status,
      ready: status === 'READY',
      bootid: boot,
      observedatmillis: timestamp,
      expiresatmillis: timestamp + 30000
    }
  }
})
function context () {
  const vm = {
    form: { services: ['NFS'], setupmode: 'WITH_SERVICES' },
    initialSetupCancelled: false,
    initialAdDisposed: false,
    $t: key => key,
    $store: { getters: { apis: {} } },
    notifyStorageServiceSetup: jest.fn(),
    parentFetchData: jest.fn(),
    delay: jest.fn().mockResolvedValue(),
    assertStorageServiceSetupApis: jest.fn(),
    resolveCreatedSharedFileSystem: jest.fn().mockResolvedValue(fs),
    findStorageServiceInstance: jest.fn().mockResolvedValue({ id: 'instance', virtualmachineid: 'vm' }),
    enableSelectedProtocols: jest.fn(),
    createInitialFileServices: jest.fn().mockResolvedValue({}),
    createInitialBlockServices: jest.fn().mockResolvedValue({}),
    verifyInitialStorageServiceSetup: jest.fn(),
    cancelAdMutation: jest.fn(),
    clearInitialBlockSecrets: methods.clearInitialBlockSecrets,
    clearInitialAdCredentials: methods.clearInitialAdCredentials
  }
  for (const method of ['assertInitialSetupActive', 'waitForInitialPreparation', 'configureInitialStorageServices', 'clearInitialCredentials', 'isSetupServiceSelected', 'cancelInitialSetup']) vm[method] = methods[method].bind(vm)
  return vm
}

describe('CreateSharedFS visible preparation order', () => {
  beforeEach(() => { getAPI.mockReset(); postAPI.mockReset() })

  it('does not enable a protocol while VM is Running but Native BOOT is still busy', async () => {
    const vm = context()
    let secondReply
    let tick = Date.now()
    getAPI.mockResolvedValueOnce(wire('WAITING_BOOT', tick))
      .mockImplementationOnce(() => new Promise(resolve => { secondReply = resolve }))
      .mockImplementation(() => Promise.resolve(wire('READY', ++tick)))
    const running = vm.configureInitialStorageServices({}, { services: ['NFS'] }, 'progress')
    for (let step = 0; step < 20; step++) await Promise.resolve()
    expect(secondReply).toEqual(expect.any(Function))
    expect(vm.enableSelectedProtocols).not.toHaveBeenCalled()
    expect(vm.initialSetupStep).toBe(1)
    secondReply(wire('READY', ++tick))
    await running
    expect(vm.enableSelectedProtocols).toHaveBeenCalledTimes(1)
    expect(vm.verifyInitialStorageServiceSetup).toHaveBeenCalledTimes(1)
  })

  it('requires another observation when BOOT changes between READY replies', async () => {
    const vm = context()
    let tick = Date.now()
    getAPI.mockResolvedValueOnce(wire('READY', ++tick, 'old'))
      .mockResolvedValueOnce(wire('READY', ++tick, 'new'))
      .mockResolvedValueOnce(wire('READY', ++tick, 'new'))
    const result = await vm.waitForInitialPreparation(fs, 'progress')
    expect(result.bootid).toBe('new')
    expect(getAPI).toHaveBeenCalledTimes(3)
    expect(postAPI).not.toHaveBeenCalled()
  })

  it('completes a VM-only preparation without protocol, share, ACL or format calls', async () => {
    const vm = context()
    let tick = Date.now()
    getAPI.mockImplementation(() => Promise.resolve(wire('READY', ++tick)))
    await vm.configureInitialStorageServices({}, { setupmode: 'DEFERRED', services: [] }, 'progress')
    expect(vm.initialSharedFsId).toBe('fs')
    for (const method of ['findStorageServiceInstance', 'enableSelectedProtocols', 'createInitialFileServices', 'createInitialBlockServices', 'verifyInitialStorageServiceSetup']) expect(vm[method]).not.toHaveBeenCalled()
    expect(postAPI).not.toHaveBeenCalled()
  })

  it.each(['BLOCKED', 'OBSERVATION_UNAVAILABLE'])('preserves the created resource and stops without retry for %s', async status => {
    const vm = context()
    getAPI.mockResolvedValue(wire(status, Date.now()))
    const preparation = {}
    await expect(vm.configureInitialStorageServices({}, { services: ['NFS'] }, 'progress', preparation)).rejects.toThrow('STORAGE_PREPARATION_' + status)
    expect(preparation.sharedFsId).toBe('fs')
    expect(getAPI).toHaveBeenCalledTimes(1)
    expect(vm.enableSelectedProtocols).not.toHaveBeenCalled()
    expect(postAPI).not.toHaveBeenCalled()
  })

  it('does not use an expired receipt or a rejected read to continue configuration', async () => {
    for (const response of [() => Promise.resolve(wire('READY', Date.now() - 31000)), () => Promise.reject(new Error('transport'))]) {
      const vm = context()
      getAPI.mockReset().mockImplementation(response)
      await expect(vm.configureInitialStorageServices({}, { services: ['NFS'] }, 'progress')).rejects.toThrow()
      expect(vm.enableSelectedProtocols).not.toHaveBeenCalled()
    }
  })

  it('closing during a read cancels subsequent work and clears all transient credentials', async () => {
    const vm = context()
    Object.assign(vm.form, { smblocalpassword: 'test-local-secret', smblocalpasswordconfirm: 'test-local-secret', smbadpassword: 'test-ad-secret', iscsichapsecret: 'test-chap-secret', nvmedhchapkey: 'test-dhchap-secret' })
    vm.currentSetupSnapshot = { ...vm.form }
    let reply
    getAPI.mockImplementation(() => new Promise(resolve => { reply = resolve }))
    const running = vm.configureInitialStorageServices({}, { services: ['NFS'] }, 'progress')
    for (let step = 0; step < 20; step++) await Promise.resolve()
    expect(reply).toEqual(expect.any(Function))
    vm.cancelInitialSetup()
    reply(wire('READY', Date.now()))
    await expect(running).rejects.toMatchObject({ code: 'STORAGE_INITIAL_SETUP_CANCELLED' })
    for (const field of ['smblocalpassword', 'smblocalpasswordconfirm', 'smbadpassword', 'iscsichapsecret', 'nvmedhchapkey']) {
      expect(vm.form[field]).toBe('')
      expect(vm.currentSetupSnapshot[field]).toBe('')
    }
    expect(vm.enableSelectedProtocols).not.toHaveBeenCalled()
    expect(postAPI).not.toHaveBeenCalled()
  })

  it('changing the setup mode clears unneeded secrets and explicit service selection', () => {
    const vm = context()
    vm.form.setupmode = 'DEFERRED'
    vm.form.smblocalpassword = 'transient-test-only'
    methods.changeInitialSetupMode.call(vm)
    expect(vm.form.services).toEqual([])
    expect(vm.form.smblocalpassword).toBe('')
    expect(methods.isServiceSelected.call(vm, 'NFS')).toBe(false)
    const snapshot = methods.buildStorageServiceSetupSnapshot.call(vm, { services: ['SMB'] })
    expect(snapshot.services).toEqual([])
  })

  it('stops a timed-out readiness read without another poll or any mutation', async () => {
    jest.useFakeTimers()
    try {
      const vm = context()
      let reply
      getAPI.mockImplementation(() => new Promise(resolve => { reply = resolve }))
      const running = vm.waitForInitialPreparation(fs, 'progress')
      const stopped = expect(running).rejects.toMatchObject({ code: 'STORAGE_READ_TIMEOUT' })
      jest.advanceTimersByTime(60000)
      await stopped
      reply(wire('READY', Date.now()))
      await Promise.resolve()
      expect(getAPI).toHaveBeenCalledTimes(1)
      expect(postAPI).not.toHaveBeenCalled()
      expect(vm.enableSelectedProtocols).not.toHaveBeenCalled()
    } finally { jest.useRealTimers() }
  })

  it('treats a rejected transport after dialog cancellation as cancellation', async () => {
    const vm = context()
    let rejectRead
    getAPI.mockImplementation(() => new Promise((resolve, reject) => { rejectRead = reject }))
    const running = vm.waitForInitialPreparation(fs, 'progress')
    vm.cancelInitialSetup()
    rejectRead(new Error('axios route cancellation'))
    await expect(running).rejects.toMatchObject({ code: 'STORAGE_INITIAL_SETUP_CANCELLED' })
    expect(getAPI).toHaveBeenCalledTimes(1)
    expect(postAPI).not.toHaveBeenCalled()
  })

  it('stops the instance discovery read loop when the dialog closes', async () => {
    const vm = context()
    vm.firstListValue = methods.firstListValue
    vm.normalizeApiItems = methods.normalizeApiItems
    let release
    vm.delay = () => new Promise(resolve => { release = resolve })
    getAPI.mockResolvedValue({ liststorageserviceinstancesresponse: { storageserviceinstance: [] } })
    const running = methods.findStorageServiceInstance.call(vm, fs, {})
    for (let step = 0; step < 20; step++) await Promise.resolve()
    expect(release).toEqual(expect.any(Function))
    vm.cancelInitialSetup()
    release()
    await expect(running).rejects.toMatchObject({ code: 'STORAGE_INITIAL_SETUP_CANCELLED' })
    expect(getAPI).toHaveBeenCalledTimes(1)
    expect(postAPI).not.toHaveBeenCalled()
  })

  it('does not create a VM if the dialog closes while form validation is pending', async () => {
    const vm = context()
    let complete
    vm.syncInitialNfsPath = jest.fn()
    vm.formRef = { value: { validate: () => new Promise(resolve => { complete = resolve }) } }
    const running = methods.handleSubmit.call(vm)
    vm.cancelInitialSetup()
    complete()
    await running
    expect(postAPI).not.toHaveBeenCalled()
  })

  it('requires an initial service only in the immediate configuration mode', async () => {
    const vm = context()
    // Inspect the real form rule to ensure the visible deferred option has a
    // valid submission path instead of only hiding required controls.
    vm.$store = { getters: {} }
    methods.initForm.call(vm)
    vm.form.setupmode = 'DEFERRED'
    await expect(vm.rules.services[0].validator({}, [])).resolves.toBeUndefined()
    vm.form.setupmode = 'WITH_SERVICES'
    await expect(vm.rules.services[0].validator({}, [])).rejects.toBe('message.storage.service.select.one')
  })

  it('locks submission during validation and after creation to avoid duplicate VM jobs', () => {
    for (const state of [{ loading: true }, { creationSubmitted: true }]) {
      const validate = jest.fn()
      methods.handleSubmit.call({ ...state, formRef: { value: { validate } } })
      expect(validate).not.toHaveBeenCalled()
    }
    expect(postAPI).not.toHaveBeenCalled()
  })
})
