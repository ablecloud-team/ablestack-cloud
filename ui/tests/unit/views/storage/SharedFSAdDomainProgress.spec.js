// Licensed to the Apache Software Foundation (ASF) under one or more
// contributor license agreements. See NOTICE. Apache License, Version 2.0.
// http://www.apache.org/licenses/LICENSE-2.0
import { shallowMount, flushPromises } from '@vue/test-utils'
import SharedFSTab from '@/views/storage/SharedFSTab'
import Panel from '@/components/storage/AdDomainProgressPanel'
import { getAPI, postAPI } from '@/api'
import { adDomainProgress, adDomainApplied, adDomainBusy } from '@/utils/adDomainProgress'
import wire from '../../../fixtures/storage-1453-ad-domain-wire.json'
jest.mock('@/api', () => ({ getAPI: jest.fn(), postAPI: jest.fn() }))
const domain = stage => wire[stage].joinstorageservicetoaddomainresponse.storageidentitydomain
describe('actual AD API wire and the mounted join/apply UI lifecycle', () => {
  let wrapper
  beforeEach(() => {
    getAPI.mockReset(); postAPI.mockReset()
    for (const method of ['fetchData', 'fetchStorageServiceData', 'fetchDiskOfferings', 'fetchStoragePools', 'refreshAfterStorageAction']) jest.spyOn(SharedFSTab.methods, method).mockResolvedValue()
    postAPI.mockResolvedValue({ joinstorageservicetoaddomainresponse: { jobid: 'job' } })
  })
  afterEach(() => { wrapper?.unmount(); wrapper = null; jest.restoreAllMocks() })
  const mount = async () => {
    wrapper = shallowMount(SharedFSTab, {
      props: { resource: { id: 'sharedfs', zoneid: 'zone' } },
      global: {
        stubs: { MoldDialog: { template: '<section><slot /></section>' }, AForm: { template: '<form><slot /></form>' }, AdDomainProgressPanel: false },
        provide: { parentFetchData: jest.fn() },
        mocks: { $t: x => x, $route: { fullPath: '/sharedfs/sharedfs?tab=smb', query: { tab: 'smb' }, params: { id: 'sharedfs' } },
          $router: { push: jest.fn(), replace: jest.fn() }, $store: { state: { app: { device: 'desktop' } }, getters: { apis: { listStorageServiceInstances: {} }, userInfo: { id: 'user' } } },
          $message: { error: jest.fn() }, $notifyError: jest.fn() }
      }
    })
    wrapper.vm.storageService.instance = { id: 'instance', configurationrevision: 2 }
    wrapper.vm.openActionModal('adJoin')
    wrapper.vm.forms.adJoin = { domainname: 'ablestack.local', username: 'Administrator', password: 'dummy-only' }
    await flushPromises()
    return wrapper
  }
  test('membership JOINED is not completion while service is APPLYING or FAILED', () => {
    expect(adDomainApplied(adDomainProgress(domain('APPLYING')))).toBe(false)
    expect(adDomainApplied(adDomainProgress(domain('FAILED')))).toBe(false)
    expect(adDomainApplied(adDomainProgress(domain('VERIFIED')))).toBe(true)
    expect(adDomainBusy(adDomainProgress(domain('APPLYING')))).toBe(true)
    expect(adDomainProgress({ config: '{broken' })).toEqual({})
  })
  test('keeps modal and same job until both stages verify, blocks duplicate and close', async () => {
    const w = await mount()
    let release
    w.vm.delay = jest.fn(() => new Promise(resolve => { release = resolve }))
    let queries = 0
    getAPI.mockImplementation(async api => api === 'queryAsyncJobResult'
      ? { queryasyncjobresultresponse: ++queries === 1 ? { jobstatus: 0 } : { jobstatus: 1, jobresult: { storageidentitydomain: domain('VERIFIED') } } }
      : { liststorageservicedomainstatusresponse: { storageidentitydomain: [domain(queries === 1 ? 'APPLYING' : 'VERIFIED')] } })
    const pending = w.vm.submitActionModal()
    await flushPromises()
    expect(w.vm.actionModal.visible).toBe(true)
    expect(w.vm.adProgress.jobid).toBe('job')
    expect(w.vm.adApplyStatus.serviceApply).toBe('APPLYING')
    expect(w.findAllComponents(Panel).length).toBeGreaterThan(0)
    expect(w.vm.forms.adJoin.password).toBe('')
    await w.vm.joinAdDomain(); w.vm.closeActionModal()
    expect(w.vm.actionModal.visible).toBe(true)
    expect(postAPI).toHaveBeenCalledTimes(1)
    release(); await pending; await flushPromises()
    expect(w.vm.actionModal.visible).toBe(false)
    expect(w.vm.adProgress.latest.serviceApply).toBe('VERIFIED')
  })
  test('an earlier completed job cannot satisfy the current accepted join', async () => {
    const w = await mount()
    let release
    w.vm.delay = jest.fn(() => new Promise(resolve => { release = resolve }))
    let queries = 0
    const old = JSON.parse(JSON.stringify(domain('VERIFIED')))
    const config = JSON.parse(old.config); config.adJoin.jobid = 'previous-job'; old.config = JSON.stringify(config)
    getAPI.mockImplementation(async api => api === 'queryAsyncJobResult'
      ? { queryasyncjobresultresponse: ++queries === 1 ? { jobstatus: 0 } : { jobstatus: 1, jobresult: { storageidentitydomain: domain('VERIFIED') } } }
      : { liststorageservicedomainstatusresponse: { storageidentitydomain: [queries === 1 ? old : domain('VERIFIED')] } })
    const pending = w.vm.submitActionModal(); await flushPromises()
    expect(w.vm.adApplyStatus.membership).toBe('JOINING')
    expect(w.vm.adApplyStatus.serviceApply).toBe('WAITING')
    expect(w.vm.actionModal.visible).toBe(true)
    release(); await pending; await flushPromises()
    expect(w.vm.actionModal.visible).toBe(false)
  })
  test('failed apply keeps joined stage, rollback and exact error in the open dialog', async () => {
    const w = await mount()
    getAPI.mockImplementation(async api => api === 'queryAsyncJobResult'
      ? { queryasyncjobresultresponse: { jobstatus: 2, jobresult: { errortext: 'SMB_ENDPOINT_IN_USE' } } }
      : { liststorageservicedomainstatusresponse: { storageidentitydomain: [domain('FAILED')] } })
    await w.vm.submitActionModal(); await flushPromises()
    expect(w.vm.actionModal.visible).toBe(true)
    expect(w.vm.adApplyStatus.membership).toBe('JOINED')
    expect(w.vm.adApplyStatus.rollback).toBe('SUCCEEDED')
    expect(w.vm.adProgress.error).toBe('SMB_ENDPOINT_IN_USE')
    expect(w.vm.forms.adJoin.password).toBe('')
    expect(postAPI).toHaveBeenCalledTimes(1)
  })
  test('API rejection is actionable and never leaves a false joining state', async () => {
    const w = await mount()
    postAPI.mockRejectedValueOnce({ response: { data: { errorresponse: { errortext: 'AD_DOMAIN_BASELINE_REQUIRED' } } } })
    await w.vm.submitActionModal(); await flushPromises()
    expect(w.vm.actionModal.visible).toBe(true)
    expect(w.vm.adProgress.error).toBe('AD_DOMAIN_BASELINE_REQUIRED')
    expect(w.vm.adApplyStatus.membership).toBe('UNCHANGED')
    expect(w.vm.adApplyStatus.serviceApply).toBe('NOT_APPLIED')
    expect(getAPI).not.toHaveBeenCalled()
  })
  test('post-commit query failure cannot turn verified service apply into failure', async () => {
    const w = await mount()
    w.vm.refreshAfterStorageAction = jest.fn().mockRejectedValue(new Error('QUERY_ONLY_UNAVAILABLE'))
    getAPI.mockImplementation(async api => api === 'queryAsyncJobResult'
      ? { queryasyncjobresultresponse: { jobstatus: 1, jobresult: { storageidentitydomain: domain('VERIFIED') } } }
      : { liststorageservicedomainstatusresponse: { storageidentitydomain: [domain('VERIFIED')] } })
    await w.vm.submitActionModal(); await flushPromises()
    expect(w.vm.adProgress.latest.serviceApply).toBe('VERIFIED')
    expect(w.vm.adProgress.error).toBe('')
    expect(w.vm.actionModal.visible).toBe(false)
    expect(postAPI).toHaveBeenCalledTimes(1)
  })
  test('late response cannot clear or populate a newly selected instance form', async () => {
    const w = await mount()
    let finish
    postAPI.mockReturnValueOnce(new Promise(resolve => { finish = resolve }))
    const pending = w.vm.joinAdDomain()
    w.vm.storageService.instance = { id: 'other' }
    await flushPromises()
    w.vm.forms.adJoin.password = 'new-scope-dummy'
    finish({ joinstorageservicetoaddomainresponse: { jobid: 'old-job' } })
    await pending; await flushPromises()
    expect(w.vm.adProgress.jobid).toBeNull()
    expect(w.vm.forms.adJoin.password).toBe('new-scope-dummy')
    expect(getAPI).not.toHaveBeenCalled()
  })
})
