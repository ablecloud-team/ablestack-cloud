// Licensed to the Apache Software Foundation (ASF) under one or more
// contributor license agreements. See NOTICE. Apache License, Version 2.0.
// http://www.apache.org/licenses/LICENSE-2.0
import { shallowMount, flushPromises } from '@vue/test-utils'
import CreateSharedFS from '@/views/storage/CreateSharedFS'
import { getAPI, postAPI } from '@/api'
import wire from '../../../fixtures/storage-initial-configuration-wire.json'

jest.mock('@/api', () => ({ getAPI: jest.fn(), postAPI: jest.fn() }))
const clone = value => JSON.parse(JSON.stringify(value))
const target = wire.observations.at(-1).liststorageserviceconfigurationoperationsresponse.configurationoperation[0]

describe('mounted initial NFS configuration with recorded API responses', () => {
  let wrapper
  beforeEach(() => {
    jest.useFakeTimers()
    jest.spyOn(Date, 'now').mockReturnValue(Date.parse(wire.preflightReady.preflightstorageserviceconfigurationresponse.configurationpreflight.observedat) + 1000)
    global.crypto = require('crypto').webcrypto
    getAPI.mockReset(); postAPI.mockReset()
    jest.spyOn(CreateSharedFS.methods, 'fetchData').mockResolvedValue()
    wrapper = shallowMount(CreateSharedFS, {
      props: { resource: {} },
      global: {
        stubs: { RouterLink: true },
        provide: { parentFetchData: jest.fn() },
        mocks: {
          $t: key => key,
          $route: { path: '/sharedfs', query: {} },
          $store: { getters: { userInfo: { id: 'test-user', account: 'admin', domainid: 'domain' }, apis: { createStorageNfsExport: {}, createStorageNfsAcl: {}, preflightStorageServiceConfiguration: {} } } },
          $getApiParams: () => new Proxy({}, { get: () => ({ description: '' }) }),
          $notifyError: jest.fn(),
          $confirm: jest.fn(options => options.onOk()),
          $notification: { info: jest.fn(), error: jest.fn(), warning: jest.fn(), success: jest.fn() }
        }
      }
    })
  })
  afterEach(() => { wrapper.unmount(); jest.useRealTimers(); jest.restoreAllMocks() })

  test('blocked initial admission preserves the pending request and never posts an apply', async () => {
    postAPI.mockResolvedValue(clone(wire.preflightBlocked))
    await expect(wrapper.vm.runStorageServiceSetup('createStorageNfsExport', { instanceid: target.instanceid, name: 'blocked' })).rejects.toThrow('MEMORY_LOW')
    expect(postAPI.mock.calls.map(([api]) => api)).toEqual(['preflightStorageServiceConfiguration'])
    expect(wrapper.vm.$confirm).not.toHaveBeenCalled()
    expect(wrapper.vm.pendingCreation.configurationRequests).toBeTruthy()
  })
  test('declining impact acknowledgement cannot create a configuration writer', async () => {
    postAPI.mockResolvedValue(clone(wire.preflightReady))
    wrapper.vm.$confirm.mockImplementation(options => options.onCancel())
    await expect(wrapper.vm.runStorageServiceSetup('createStorageNfsExport', { instanceid: target.instanceid, name: 'declined' })).rejects.toThrow('CONFIGURATION_IMPACT_NOT_ACKNOWLEDGED')
    expect(postAPI).toHaveBeenCalledTimes(1)
  })
  test('reentry observes an already committed request without another admission or apply', async () => {
    const params = { instanceid: target.instanceid, name: 'existing' }
    const identity = JSON.stringify(['createStorageNfsExport', params])
    wrapper.vm.pendingCreation = { configurationRequests: { [identity]: target.requestid } }
    getAPI.mockResolvedValue(clone(wire.observations.at(-1)))
    await expect(wrapper.vm.runStorageServiceSetup('createStorageNfsExport', params)).resolves.toEqual(target.result)
    expect(postAPI).not.toHaveBeenCalled()
    expect(wrapper.vm.$confirm).not.toHaveBeenCalled()
  })
  test('does not end creation on COMMITTING/canresume while the job runs; ACL uses the committed export ID', async () => {
    let index = 0
    let exportCommitted = false
    let aclIndex = 0
    let aclPosted = false
    getAPI.mockImplementation(async api => {
      if (api === 'listStorageNfsExports') return { liststoragenfsexportsresponse: {} }
      if (api === 'queryAsyncJobResult') return clone(aclPosted ? wire.aclJobRunning : wire.jobRunning)
      if (api === 'listStorageServiceConfigurationOperations') {
        if (aclPosted) return clone(wire.aclObservations[Math.min(aclIndex++, wire.aclObservations.length - 1)])
        const value = clone(wire.observations[Math.min(index++, wire.observations.length - 1)])
        exportCommitted = value.liststorageserviceconfigurationoperationsresponse.configurationoperation[0].state === 'COMMITTED'
        return value
      }
      throw new Error('Unexpected API ' + api)
    })
    postAPI.mockImplementation(async api => {
      if (api === 'preflightStorageServiceConfiguration') return clone(wire.preflightReady)
      if (api === 'createStorageNfsExport') return clone(wire.accepted)
      if (api === 'createStorageNfsAcl') {
        expect(exportCommitted).toBe(true)
        aclPosted = true
        return clone(wire.aclAccepted)
      }
      throw new Error('Unexpected POST ' + api)
    })
    const instance = { id: target.instanceid }
    const sharedfs = { volumeid: target.result.volumeid }
    const setup = {
      services: ['NFS'],
      name: 'recorded-initial',
      nfsname: target.result.name,
      nfspath: target.result.path,
      filesystem: 'XFS',
      useexistingvolume: true,
      nfsprincipal: '10.10.0.0/16',
      nfspermission: 'READ_WRITE',
      nfsrootsquash: true,
      nfssync: true
    }
    const task = wrapper.vm.createInitialFileServices(instance, sharedfs, setup)
    await flushPromises()
    for (let n = 0; n < wire.observations.length + wire.aclObservations.length; n++) {
      jest.advanceTimersByTime(2000)
      await flushPromises()
    }
    const acl = wire.aclObservations.at(-1).liststorageserviceconfigurationoperationsresponse.configurationoperation[0]
    expect(await task).toEqual({ nfsExportId: target.result.id, nfsAclId: acl.result.id })
    expect(aclIndex).toBe(wire.aclObservations.length)
    expect(postAPI.mock.calls.map(([api]) => api)).toEqual(['preflightStorageServiceConfiguration', 'createStorageNfsExport', 'preflightStorageServiceConfiguration', 'createStorageNfsAcl'])
    expect(postAPI.mock.calls[3][1]).toEqual(expect.objectContaining({
      exportid: target.result.id, principal: '10.10.0.0/16', requestid: expect.any(String)
    }))
    expect(index).toBe(wire.observations.length)
    expect(wrapper.vm.pendingCreation.configurationRequests).toBeTruthy()
    expect(wrapper.vm.$notification.error).not.toHaveBeenCalled()
  })
})
