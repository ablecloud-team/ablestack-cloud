// Licensed to the Apache Software Foundation (ASF) under one or more
// contributor license agreements. See NOTICE. Apache License, Version 2.0.
// http://www.apache.org/licenses/LICENSE-2.0
import { shallowMount, flushPromises } from '@vue/test-utils'
import SharedFSTab from '@/views/storage/SharedFSTab'
import { getAPI, postAPI } from '@/api'
import wire from '../../../fixtures/storage-1456-file-identity-wire.json'
import submittedWire from '../../../fixtures/storage-1456-submit-contract.json'
jest.mock('@/api', () => ({ getAPI: jest.fn(), postAPI: jest.fn() }))
const instance = 'c1eca707-a554-4c42-85cd-1e9e9744d804'
const volume = 'e070a0aa-dbf5-48c0-83e0-09f874446277'
const policy = '77262649-396b-35ca-85b2-ac72188c020d'
const share = '0fc08aa0-61e2-4a35-a23f-94eb4afcf7de'
const ordinary = { mode: 'AUTHENTICATED', uid: null, gid: null }
const fixed = { mode: 'FIXED', uid: 7001, gid: 7002 }
const clone = x => JSON.parse(JSON.stringify(x))
const captures = []
describe('mounted SMB preflight and actual API submit contract', () => {
  let wrapper
  beforeEach(() => {
    global.crypto = require('crypto').webcrypto
    global.TextEncoder = require('util').TextEncoder
    sessionStorage.clear()
    for (const method of ['fetchData', 'fetchStorageServiceData', 'fetchDiskOfferings', 'fetchStoragePools', 'refreshAfterStorageAction']) jest.spyOn(SharedFSTab.methods, method).mockResolvedValue()
    postAPI.mockReset(); getAPI.mockReset()
    postAPI.mockImplementation(async api => {
      if (api === 'preflightStorageServiceConfiguration') {
        const value = clone(wire)
        value.preflightstorageserviceconfigurationresponse.configurationpreflight.expiresat = new Date(Date.now() + 30000).toISOString()
        return value
      }
      return { [api.toLowerCase() + 'response']: { jobid: 'job' } }
    })
    getAPI.mockImplementation(async api => api === 'listStorageServiceInstances'
      ? { liststorageserviceinstancesresponse: { storageserviceinstance: [{ id: instance, configurationrevision: 1, configurationprotection: false }] } }
      : { liststorageserviceconfigurationoperationsresponse: { configurationoperation: [{ id: 'operation', state: 'COMMITTED', result: {} }] } })
  })
  afterEach(() => { if (wrapper) wrapper.unmount(); wrapper = null; jest.restoreAllMocks() })
  afterAll(() => {
    if (process.env.SMB_CONTRACT_REQUEST_FIXTURE) require('fs').writeFileSync(process.env.SMB_CONTRACT_REQUEST_FIXTURE, JSON.stringify(captures, null, 2) + '\n')
  })
  const render = async (kind, identity, inheritowner = false) => {
    wrapper = shallowMount(SharedFSTab, {
      props: { resource: { id: 'sharedfs', zoneid: 'zone', volumeid: volume, filesystem: 'xfs' } },
      global: {
        stubs: { MoldDialog: { template: '<section><slot /></section>' }, AForm: { template: '<form><slot /></form>' } },
        provide: { parentFetchData: jest.fn() },
        mocks: {
          $t: x => x,
          $route: { fullPath: '/sharedfs/sharedfs?tab=smb', query: { tab: 'smb' }, params: { id: 'sharedfs' } },
          $router: { push: jest.fn(), replace: jest.fn() },
          $store: { state: { app: { device: 'desktop' } }, getters: { apis: { createStorageSmbShare: {}, updateStorageSmbShare: {}, listStorageServiceInstances: {} }, userInfo: { id: 'user' } } },
          $message: { error: jest.fn() },
          $notifyError: jest.fn()
        }
      }
    })
    wrapper.vm.storageService.instance = { id: instance, configurationrevision: 1 }
    wrapper.vm.openActionModal('smbShare')
    Object.assign(wrapper.vm.forms.smbShare, {
      name: 'contract1456',
      path: '/export/contract1456',
      filesystem: 'xfs',
      volumeid: volume,
      volumemode: 'CURRENT',
      directorypolicyid: policy,
      nestedpath: true,
      relativepath: 'epic1456/projects/team-a',
      createdirectory: false,
      readonly: false,
      browseable: true,
      guestok: false,
      crossprotocol: true,
      inheritowner,
      inheritownerbaseline: false,
      fileidentity: clone(identity)
    })
    if (kind === 'update') {
      wrapper.vm.actionModal.type = 'editSmbShare'
      wrapper.vm.actionModal.context = { raw: { id: share } }
      wrapper.vm.forms.smbShare.id = share
      wrapper.vm.forms.smbShare.baseline = clone(wrapper.vm.forms.smbShare)
      wrapper.vm.forms.smbShare.baseline.fileidentity = clone(ordinary)
    }
    await flushPromises()
    return wrapper
  }
  test.each([
    ['fixed-create', 'create', fixed, false],
    ['ordinary-create', 'create', ordinary, false],
    ['inherit-create', 'create', ordinary, true],
    ['fixed-update', 'update', fixed, false]
  ])('%s preview is derived from the parameters sent by the actual component', async (name, kind, identity, inheritowner) => {
    const w = await render(kind, identity, inheritowner)
    await w.vm.submitActionModal()
    await flushPromises()
    expect(postAPI.mock.calls.map(([api]) => api)).toEqual(['preflightStorageServiceConfiguration'])
    const intent = JSON.parse(postAPI.mock.calls[0][1].intent)
    w.vm.configurationImpactAcknowledged = true
    await flushPromises()
    await w.vm.submitActionModal()
    const expectedApi = kind === 'create' ? 'createStorageSmbShare' : 'updateStorageSmbShare'
    const request = postAPI.mock.calls.find(([api]) => api === expectedApi)?.[1]
    expect(request).toBeDefined()
    expect(intent.smbfullapply).toBe(kind === 'update' ? true : undefined)
    expect(intent.smbinheritance).toEqual({ ...(request.inheritowner === undefined ? {} : { inheritOwner: request.inheritowner }), directoryPolicyId: request.directorypolicyid })
    expect(intent.smboptions).toEqual({ readOnly: request.readonly, browseable: request.browseable })
    expect(intent.guestok).toBe(request.guestok)
    expect(intent.smbfileidentity).toEqual(identity.mode === 'FIXED' ? fixed : undefined)
    expect(request).not.toHaveProperty('owneruid')
    expect(request).not.toHaveProperty('ownergid')
    expect(request).not.toHaveProperty('mode')
    const recorded = submittedWire.find(item => item.name === name)
    expect(intent).toEqual(recorded.intent)
    const stableRequest = value => Object.fromEntries(Object.entries(value).filter(([key]) => key !== 'requestid'))
    expect(stableRequest(request)).toEqual(stableRequest(recorded.request))
    captures.push({ name, api: expectedApi, intent, request })
  })
})
