// Licensed to the Apache Software Foundation (ASF) under one or more
// contributor license agreements. See NOTICE. Apache License, Version 2.0.
// http://www.apache.org/licenses/LICENSE-2.0
import { shallowMount, flushPromises } from '@vue/test-utils'
import SharedFSTab from '@/views/storage/SharedFSTab'
import MoldDialog from '@/components/view/MoldDialog'
import { getAPI, postAPI } from '@/api'
import wire from '../../../fixtures/storage-1444-protection-wire.json'

jest.mock('@/api', () => ({ getAPI: jest.fn(), postAPI: jest.fn() }))
const copy = value => JSON.parse(JSON.stringify(value))
const original = wire.operation.liststorageserviceconfigurationoperationsresponse.configurationoperation[0]
const instance = wire.instance
const modalTypes = ['editNfsExport', 'editSmbShare', 'editIscsiTarget', 'editNvmeSubsystem']
const pending = row => ({ liststorageserviceconfigurationoperationsresponse: { count: row ? 1 : 0, configurationoperation: row ? [copy(row)] : [] } })

describe('mounted SharedFS instance configuration protection', () => {
  let wrapper, server, row
  const render = async (protectedValue = true) => {
    wrapper = shallowMount(SharedFSTab, {
      props: { resource: { id: 'sharedfs', zoneid: instance.zoneid, virtualmachineid: instance.virtualmachineid, volumeid: 'data' } },
      global: {
        provide: { parentFetchData: jest.fn() },
        mocks: {
          $t: key => key,
          $route: { fullPath: '/sharedfs/sharedfs?tab=nfs', query: { tab: 'nfs' }, params: { id: 'sharedfs' } },
          $router: { push: jest.fn(), replace: jest.fn() },
          $store: { state: { app: { device: 'desktop' } }, getters: { apis: { listStorageServiceInstances: {}, listStorageServiceConfigurationOperations: {} }, userInfo: { id: 'user' } } },
          $message: { error: jest.fn() },
          $notifyError: jest.fn()
        }
      }
    })
    server = { ...copy(instance), configurationprotection: protectedValue }
    wrapper.vm.storageService.instance = copy(server)
    await flushPromises()
    return wrapper
  }
  const open = async type => {
    wrapper.vm.actionModal.type = type
    wrapper.vm.actionModal.visible = true
    await flushPromises()
  }
  const blocked = () => {
    expect(wrapper.vm.configurationProtected).toBe(true)
    expect(wrapper.findComponent(MoldDialog).vm.$attrs.okButtonProps.disabled).toBe(true)
  }

  beforeEach(() => {
    global.crypto = require('crypto').webcrypto
    for (const method of ['fetchData', 'fetchStorageServiceData', 'fetchDiskOfferings', 'fetchStoragePools', 'refreshAfterStorageAction']) {
      jest.spyOn(SharedFSTab.methods, method).mockResolvedValue()
    }
    row = copy(original)
    getAPI.mockReset()
    postAPI.mockReset()
    getAPI.mockImplementation(async api => {
      if (api === 'listStorageServiceInstances') return { liststorageserviceinstancesresponse: { storageserviceinstance: [copy(server)] } }
      if (api === 'listStorageServiceConfigurationOperations') return pending(row)
      if (api === 'queryAsyncJobResult') return { queryasyncjobresultresponse: { jobstatus: 1 } }
      return {}
    })
    postAPI.mockImplementation(async (api, params) => {
      if (api === 'resumeStorageServiceConfigurationOperation') {
        server.configurationprotection = false
        row = { ...copy(original), state: 'ROLLED_BACK', phase: 'COMPLETED', rollback: { state: 'SUCCEEDED' }, canresume: false }
        return { resumestorageserviceconfigurationoperationresponse: { jobid: 'resume-job' } }
      }
      row = { ...copy(original), id: 'new-op', requestid: params.requestid, state: 'COMMITTED', result: {} }
      return { [api.toLowerCase() + 'response']: { jobid: 'apply-job' } }
    })
  })
  afterEach(() => {
    if (wrapper) wrapper.unmount()
    jest.restoreAllMocks()
  })

  test('server protection alone blocks a new component and loads original diagnostics with GET only', async () => {
    await render()
    await open('editNfsExport')
    blocked()
    expect(wrapper.vm.configurationOperation?.id).toBe(original.id)
    expect(wrapper.vm.configurationOperation?.originalfailure).toEqual(original.originalfailure)
    await wrapper.vm.runConfigurationAction('editNfsExport', 'updateStorageNfsExport', { id: 'share' })
    expect(postAPI).not.toHaveBeenCalled()
    expect(getAPI).toHaveBeenCalledWith('listStorageServiceConfigurationOperations', {
      instanceid: instance.id, page: 1, pagesize: 200
    })
  })

  test('closing and opening each protocol modal cannot erase the unresolved instance operation', async () => {
    await render()
    wrapper.vm.configurationOperation = copy(original)
    for (const type of modalTypes) {
      await open(type)
      blocked()
      expect(wrapper.vm.configurationOperation?.id).toBe(original.id)
      wrapper.vm.closeActionModal()
      await flushPromises()
    }
    expect(postAPI).not.toHaveBeenCalled()
  })

  test('an uncertain own request remains protected even before the server reservation appears', async () => {
    await render(false)
    wrapper.vm.configurationOperation = { instanceid: instance.id, requestid: 'uncertain-key', state: 'SUBMISSION_UNCONFIRMED', phase: 'OBSERVING' }
    await open('editNfsExport')
    blocked()
    wrapper.vm.closeActionModal()
    await open('editSmbShare')
    blocked()
    expect(wrapper.vm.configurationOperation.requestid).toBe('uncertain-key')
  })

  test('diagnostic read failure keeps protection and the refresh path never posts', async () => {
    await render()
    getAPI.mockRejectedValue(new Error('read unavailable'))
    await open('editNfsExport')
    await wrapper.vm.refreshConfigurationOperation()
    blocked()
    expect(postAPI).not.toHaveBeenCalled()
    getAPI.mockImplementation(async api => api === 'listStorageServiceInstances'
      ? { liststorageserviceinstancesresponse: { storageserviceinstance: [copy(server)] } }
      : pending(original))
    await wrapper.vm.refreshConfigurationOperation()
    blocked()
    expect(wrapper.vm.configurationOperation.id).toBe(original.id)
  })

  test('same operation recovery refreshes server protection before permitting a new request', async () => {
    await render()
    wrapper.vm.configurationOperation = copy(original)
    await open('editNfsExport')
    await wrapper.vm.resumeConfigurationOperation(copy(original))
    await flushPromises()
    expect(postAPI).toHaveBeenCalledTimes(1)
    expect(postAPI).toHaveBeenCalledWith('resumeStorageServiceConfigurationOperation', { instanceid: instance.id, id: original.id })
    expect(wrapper.vm.storageService.instance.configurationprotection).toBe(false)
    expect(wrapper.vm.configurationProtected).toBe(false)
    expect(wrapper.vm.configurationOperation.state).toBe('ROLLED_BACK')
    expect(wrapper.vm.configurationOperation.originalfailure).toEqual(original.originalfailure)
    wrapper.vm.closeActionModal()
    await open('editSmbShare')
    wrapper.vm.configurationAcceptedPreflight = { scope: wrapper.vm.storageReadScope, instanceid: instance.id, fingerprint: 'confirmed' }
    await wrapper.vm.runConfigurationAction('editSmbShare', 'updateStorageSmbShare', { id: 'share' })
    expect(postAPI).toHaveBeenCalledTimes(2)
  })

  test('a late read from another instance cannot unlock or replace current protection', async () => {
    await render(false)
    let resolveOld
    getAPI.mockImplementationOnce(() => new Promise(resolve => { resolveOld = resolve }))
    server.configurationprotection = true
    wrapper.vm.storageService.instance = copy(server)
    await wrapper.vm.$nextTick()
    const old = resolveOld
    const next = { ...copy(server), id: 'other-instance', virtualmachineid: 'other-vm' }
    row = { ...copy(original), id: 'other-op', instanceid: next.id }
    server = next
    await wrapper.setProps({ resource: { id: 'other-sharedfs', virtualmachineid: next.virtualmachineid, zoneid: next.zoneid } })
    wrapper.vm.storageService.instance = copy(next)
    await flushPromises()
    old(pending({ ...copy(original), state: 'ROLLED_BACK', canresume: false }))
    await flushPromises()
    expect(wrapper.vm.configurationProtected).toBe(true)
    expect(wrapper.vm.configurationOperation?.id).not.toBe(original.id)
    expect(postAPI).not.toHaveBeenCalled()
  })
})
