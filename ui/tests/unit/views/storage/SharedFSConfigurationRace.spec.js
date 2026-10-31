// Licensed to the Apache Software Foundation (ASF) under one or more
// contributor license agreements. See NOTICE. Apache License, Version 2.0.
// http://www.apache.org/licenses/LICENSE-2.0
import { shallowMount, flushPromises } from '@vue/test-utils'
import SharedFSTab from '@/views/storage/SharedFSTab'
import MoldDialog from '@/components/view/MoldDialog'
import { getAPI, postAPI } from '@/api'
import wire from '../../../fixtures/storage-1451-rollback-race-wire.json'

jest.mock('@/api', () => ({ getAPI: jest.fn(), postAPI: jest.fn() }))
const instanceid = wire.instance.id
describe('mounted configuration form completion race', () => {
  let wrapper
  beforeEach(() => {
    global.crypto = require('crypto').webcrypto
    for (const method of ['fetchData', 'fetchStorageServiceData', 'refreshAfterStorageAction']) jest.spyOn(SharedFSTab.methods, method).mockResolvedValue()
    getAPI.mockReset(); postAPI.mockReset()
    getAPI.mockImplementation(async api => api === 'listStorageServiceInstances'
      ? { liststorageserviceinstancesresponse: { storageserviceinstance: [wire.instance] } } : {})
    wrapper = shallowMount(SharedFSTab, {
      props: { resource: { id: 'sharedfs' } },
      global: {
        provide: { parentFetchData: jest.fn() },
        mocks: {
          $t: k => k,
          $route: { fullPath: '/sharedfs/sharedfs?tab=nfs', query: { tab: 'nfs' }, params: { id: 'sharedfs' } },
          $router: { push: jest.fn(), replace: jest.fn() },
          $store: { state: { app: { device: 'desktop' } }, getters: { apis: {}, userInfo: { id: 'user' } } },
          $message: { error: jest.fn(), success: jest.fn() },
          $notifyError: jest.fn()
        }
      }
    })
    wrapper.vm.storageService.instance = { ...wire.instance }
    wrapper.vm.storageService.protocols = [{ id: 'listener', protocol: 'NFS', enabled: true, port: 2049 }]
    wrapper.vm.selectedNfsListenerPorts = () => [2049]
  })
  afterEach(() => { wrapper.unmount(); jest.restoreAllMocks() })

  test('actual rollback wire automatically unlocks the form, retains draft and diagnostics, and never resumes or reposts', async () => {
    wrapper.vm.openActionModal('nfsIdentity')
    await flushPromises()
    wrapper.vm.forms.nfsIdentity.idmappingmode = 'NUMERIC'
    wrapper.vm.configurationAcceptedPreflight = { scope: wrapper.vm.storageReadScope, instanceid, fingerprint: 'confirmed' }
    let submitted
    let reads = 0
    postAPI.mockImplementation(async (api, params) => {
      submitted = params.requestid
      return { [api.toLowerCase() + 'response']: { jobid: wire.job.params.jobid } }
    })
    getAPI.mockImplementation(async api => {
      if (api === 'listStorageServiceInstances') return { liststorageserviceinstancesresponse: { storageserviceinstance: [wire.instance] } }
      if (api === 'queryAsyncJobResult') return wire.job.body
      if (api === 'listStorageServiceConfigurationOperations') {
        const source = reads++ === 0 ? wire.stale.body : wire.terminal.body
        return { liststorageserviceconfigurationoperationsresponse: { configurationoperation: source.liststorageserviceconfigurationoperationsresponse.configurationoperation.map(row => ({ ...row, requestid: submitted })) } }
      }
      return {}
    })
    await wrapper.vm.updateNfsIdentity()
    await flushPromises()
    expect(reads).toBe(2)
    expect(wrapper.vm.configurationOperation.state).toBe('ROLLED_BACK')
    expect(wrapper.vm.configurationResult.originalfailure).toEqual(wire.terminal.body.liststorageserviceconfigurationoperationsresponse.configurationoperation[0].originalfailure)
    expect(wrapper.vm.configurationOperation.canresume).toBe(false)
    expect(wrapper.vm.configurationProtected).toBe(false)
    expect(wrapper.vm.actionLoading.nfsIdentity).toBe(false)
    expect(wrapper.vm.forms.nfsIdentity.idmappingmode).toBe('NUMERIC')
    expect(wrapper.findComponent(MoldDialog).vm.$attrs.okButtonProps.disabled).not.toBe(true)
    expect(postAPI).toHaveBeenCalledTimes(1)
    expect(postAPI.mock.calls[0][0]).toBe('updateStorageNfsIdentityMapping')
  })
})
