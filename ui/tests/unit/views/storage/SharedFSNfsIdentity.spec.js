// Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements.
// See NOTICE. Apache License, Version 2.0. http://www.apache.org/licenses/LICENSE-2.0
import { mount, shallowMount, flushPromises } from '@vue/test-utils'
import { h } from 'vue'
import SharedFSTab from '@/views/storage/SharedFSTab'
import { getAPI, postAPI } from '@/api'
import wire from '../../../fixtures/storage-1451-identity-wire.json'
import StorageNfsIdentityPanel from '@/components/view/StorageNfsIdentityPanel'
import { isConfigurationApi } from '@/utils/storageConfigOperation'
jest.mock('@/api', () => ({ getAPI: jest.fn(), postAPI: jest.fn() }))
describe('NFS identity actual serializer and mounted request contract', () => {
  let wrapper
  beforeEach(() => {
    global.crypto = require('crypto').webcrypto
    jest.spyOn(SharedFSTab.methods, 'fetchData').mockResolvedValue()
    jest.spyOn(SharedFSTab.methods, 'fetchStorageServiceData').mockResolvedValue()
    jest.spyOn(SharedFSTab.methods, 'refreshAfterStorageAction').mockResolvedValue()
    getAPI.mockReset(); postAPI.mockReset()
    getAPI.mockImplementation(async api => api === 'listStorageServiceInstances'
      ? { liststorageserviceinstancesresponse: { storageserviceinstance: [{ id: 'instance', configurationprotection: false, configurationrevision: 0 }] } }
      : api === 'listStorageServiceConfigurationOperations'
        ? { liststorageserviceconfigurationoperationsresponse: { count: 1, configurationoperation: [{ id: 'operation', state: 'COMMITTED', result: {} }] } }
        : {})
    postAPI.mockImplementation(async api => ({ [api.toLowerCase() + 'response']: { jobid: 'job' } }))
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
    wrapper.vm.storageService.instance = { id: 'instance', configurationrevision: 0 }
    wrapper.vm.storageService.protocols = [{ id: 'listener', protocol: 'NFS', enabled: true, port: 2049 }]
    wrapper.vm.selectedNfsListenerPorts = () => [2049]
  })
  afterEach(() => { wrapper.unmount(); jest.restoreAllMocks() })

  test('actual serialized named object loads separately from initial SharedFS loading', async () => {
    getAPI.mockResolvedValue(wire)
    await wrapper.vm.fetchNfsIdentity()
    expect(wrapper.vm.nfsIdentity.report.mode).toBe('NUMERIC')
    expect(wrapper.vm.nfsIdentity.report.endpoints[0].observedrevision).toBe(13)
    expect(wrapper.vm.nfsIdentity.loading).toBe(false)
    expect(wrapper.vm.storageService.loading).toBe(false)
  })
  test('preflight and actual update submit the same target without any POSIX or endpoint mutation', async () => {
    wrapper.vm.nfsIdentity.report = wire.getstoragenfsidentitymappingresponse.nfsidentitymapping
    wrapper.vm.openActionModal('nfsIdentity')
    await flushPromises()
    expect(wrapper.vm.forms.nfsIdentity.idmappingmode).toBe('NUMERIC')
    wrapper.vm.forms.nfsIdentity.idmappingmode = 'NAME_DOMAIN'
    expect(wrapper.vm.configurationIntent).toEqual({ api: 'updateStorageNfsIdentityMapping', protocol: 'NFS', idmappingmode: 'NAME_DOMAIN' })
    wrapper.vm.configurationAcceptedPreflight = { scope: wrapper.vm.storageReadScope, instanceid: 'instance', fingerprint: 'confirmed' }
    await wrapper.vm.updateNfsIdentity()
    const params = postAPI.mock.calls.find(([api]) => api === 'updateStorageNfsIdentityMapping')[1]
    expect(params.idmappingmode).toBe('NAME_DOMAIN')
    for (const key of ['mode', 'owneruid', 'ownergid', 'recursivepermission', 'port', 'listenip', 'volumeid']) expect(params[key]).toBeUndefined()
    expect(params.impactack).toBe('confirmed')
    expect(isConfigurationApi('updateStorageNfsIdentityMapping')).toBe(true)
    expect(wrapper.vm.configurationResult.state).toBe('COMMITTED')
  })
  test('optional read failure leaves old NFS exports and loading usable', async () => {
    wrapper.vm.storageService.nfsExports = [{ id: 'preserve' }]
    getAPI.mockRejectedValue(new Error('unavailable'))
    await wrapper.vm.fetchNfsIdentity()
    expect(wrapper.vm.nfsIdentity.error).toBe(true)
    expect(wrapper.vm.nfsIdentity.loading).toBe(false)
    expect(wrapper.vm.storageService.loading).toBe(false)
    expect(wrapper.vm.storageService.nfsExports[0].id).toBe('preserve')
  })
  test('stale optional read cannot overwrite another instance', async () => {
    let done
    getAPI.mockImplementation(() => new Promise(resolve => { done = resolve }))
    const read = wrapper.vm.fetchNfsIdentity()
    wrapper.vm.storageService.instance = { id: 'another' }
    done(wire); await read
    expect(wrapper.vm.nfsIdentity.report).toBe(null)
    expect(wrapper.vm.nfsIdentity.loading).toBe(false)
  })
  test('service observation panel is owned by the NFS pane', async () => {
    wrapper.unmount()
    wrapper = shallowMount(SharedFSTab, {
      props: { resource: { id: 'sharedfs' } },
      global: {
        stubs: {
          'a-tabs': { template: '<div><slot /></div>' },
          'a-tab-pane': { props: ['tab'], setup: (props, { slots }) => () => h('section', { 'data-pane': props.tab }, props.tab === 'NFS' ? slots.default?.() : []) }
        },
        provide: { parentFetchData: jest.fn() },
        mocks: {
          $t: k => k,
          $route: { fullPath: '/sharedfs/sharedfs?tab=nfs', query: { tab: 'nfs' }, params: { id: 'sharedfs' } },
          $router: { push: jest.fn(), replace: jest.fn() },
          $store: { state: { app: { device: 'desktop' } }, getters: { apis: { listStorageServiceInstances: {}, listSharedFileSystems: {} }, capabilities: {}, features: {}, project: {}, listAllProjects: [], userInfo: { id: 'user' } } },
          $message: { error: jest.fn(), success: jest.fn() }, $notifyError: jest.fn()
        }
      }
    })
    wrapper.vm.storageService.instance = { id: 'instance' }
    await flushPromises()
    const nfs = wrapper.find('[data-pane="NFS"]')
    const details = wrapper.find('[data-pane="label.details"]')
    expect(nfs.findComponent(StorageNfsIdentityPanel).exists()).toBe(true)
    expect(details.findComponent(StorageNfsIdentityPanel).exists()).toBe(false)
  })
  test('panel exposes configured vs applied policy and scoped client requirements', () => {
    const panel = mount(StorageNfsIdentityPanel, { props: { report: wire.getstoragenfsidentitymappingresponse.nfsidentitymapping, mode: 'NUMERIC' }, global: { mocks: { $t: k => k } } })
    expect(panel.text()).toContain('NUMERIC')
    expect(panel.text()).toContain('nfs4_disable_idmapping')
    expect(panel.text()).toContain('ls -ln')
    panel.unmount()
  })
})
