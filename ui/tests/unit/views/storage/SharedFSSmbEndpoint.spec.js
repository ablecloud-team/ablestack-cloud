// Licensed to the Apache Software Foundation (ASF) under one or more
// contributor license agreements. See NOTICE. Apache License, Version 2.0.
// http://www.apache.org/licenses/LICENSE-2.0
import { shallowMount, flushPromises } from '@vue/test-utils'
import SharedFSTab from '@/views/storage/SharedFSTab'
import { getAPI, postAPI } from '@/api'
import wire from '../../../fixtures/storage-1449-smb-wire.json'

jest.mock('@/api', () => ({ getAPI: jest.fn(), postAPI: jest.fn() }))
const rows = () => JSON.parse(JSON.stringify(wire.liststorageserviceprotocolsresponse.storageserviceprotocol))

describe('mounted SMB endpoint request and API observation contract', () => {
  const wrappers = []
  beforeEach(() => {
    global.crypto = require('crypto').webcrypto
    jest.spyOn(SharedFSTab.methods, 'fetchData').mockResolvedValue()
    jest.spyOn(SharedFSTab.methods, 'fetchStorageServiceData').mockResolvedValue()
    jest.spyOn(SharedFSTab.methods, 'refreshAfterStorageAction').mockResolvedValue()
    getAPI.mockReset()
    postAPI.mockReset()
    getAPI.mockImplementation(async api => api === 'listStorageServiceInstances'
      ? { liststorageserviceinstancesresponse: { storageserviceinstance: [{ id: 'instance', configurationprotection: false, configurationrevision: 0 }] } }
      : api === 'listStorageServiceConfigurationOperations'
        ? { liststorageserviceconfigurationoperationsresponse: { count: 1, configurationoperation: [{ id: 'operation', state: 'COMMITTED', result: {} }] } }
        : {})
    postAPI.mockImplementation(async api => ({ [api.toLowerCase() + 'response']: { jobid: 'job' } }))
  })
  afterEach(() => {
    wrappers.splice(0).forEach(wrapper => wrapper.unmount())
    jest.restoreAllMocks()
  })
  const render = async () => {
    const wrapper = shallowMount(SharedFSTab, {
      props: { resource: { id: 'sharedfs', zoneid: 'zone' } },
      global: {
        provide: { parentFetchData: jest.fn() },
        mocks: {
          $t: key => key,
          $route: { fullPath: '/sharedfs/sharedfs?tab=smb', query: { tab: 'smb' }, params: { id: 'sharedfs' } },
          $router: { push: jest.fn(), replace: jest.fn() },
          $store: { state: { app: { device: 'desktop' } }, getters: { apis: { listStorageServiceInstances: {}, enableStorageServiceProtocol: {}, deleteStorageServiceProtocol: {} }, userInfo: { id: 'user' } } },
          $message: { error: jest.fn(), success: jest.fn() },
          $notifyError: jest.fn()
        }
      }
    })
    wrappers.push(wrapper)
    wrapper.vm.storageService.instance = { id: 'instance', configurationrevision: 0 }
    wrapper.vm.storageService.protocols = rows()
    wrapper.vm.virtualmachines = [{ id: 'vm', nic: [{ ipaddress: '192.0.2.10' }, { ipaddress: '192.0.2.11' }] }]
    await flushPromises()
    return wrapper
  }
  const accepted = wrapper => {
    wrapper.vm.configurationAcceptedPreflight = { scope: wrapper.vm.storageReadScope, instanceid: 'instance', fingerprint: 'confirmed' }
  }

  test('editing custom port preserves the selected UUID across the protocol watcher and submits the exact tuple', async () => {
    const wrapper = await render()
    const second = wrapper.vm.smbListenerRows[1]
    wrapper.vm.openSmbEndpointEdit(second)
    await flushPromises()
    expect(wrapper.vm.forms.enableProtocol.port).toBe(1445)
    wrapper.vm.forms.enableProtocol.listenip = '192.0.2.12'
    wrapper.vm.forms.enableProtocol.listenipmode = 'EXISTING'
    await flushPromises()
    expect(wrapper.vm.configurationIntent).toEqual({
      api: 'enableStorageServiceProtocol',
      protocol: 'SMB',
      endpointid: second.id,
      listenip: '192.0.2.12',
      port: 1445
    })
    accepted(wrapper)
    await wrapper.vm.enableProtocol()
    expect(postAPI.mock.calls.find(([api]) => api === 'enableStorageServiceProtocol')[1])
      .toEqual(expect.objectContaining({ endpointid: second.id, listenip: '192.0.2.12', port: 1445, protocol: 'SMB', instanceid: 'instance' }))
  })

  test('adding after edit cannot accidentally target the previous UUID', async () => {
    const wrapper = await render()
    wrapper.vm.openSmbEndpointEdit(wrapper.vm.smbListenerRows[1])
    wrapper.vm.closeActionModal()
    wrapper.vm.openActionModal('enableProtocol', { protocol: 'SMB' })
    await flushPromises()
    expect(wrapper.vm.forms.enableProtocol.endpointid).toBe('')
    expect(wrapper.vm.forms.enableProtocol.port).toBe(445)
    expect(wrapper.vm.configurationIntent.endpointid).toBeUndefined()
  })

  test('delete and its preflight keep UUID plus old tuple and do not disable another live share path', async () => {
    const wrapper = await render()
    const first = wrapper.vm.smbListenerRows[0]
    expect(first.canDelete).toBe(true)
    wrapper.vm.openSmbEndpointDelete(first)
    wrapper.vm.forms.deleteEndpoint.confirmation = first.listenIp
    await flushPromises()
    expect(wrapper.vm.configurationIntent).toEqual({
      api: 'deleteStorageServiceProtocol', protocol: 'SMB', endpointid: first.id, listenip: first.listenIp, port: first.port
    })
    accepted(wrapper)
    await wrapper.vm.deleteStorageEndpoint()
    expect(postAPI.mock.calls.find(([api]) => api === 'deleteStorageServiceProtocol')[1])
      .toEqual(expect.objectContaining({ endpointid: first.id, listenip: first.listenIp, port: first.port }))
    wrapper.vm.storageService.protocols = [rows()[1]]
    expect(wrapper.vm.smbListenerRows[0].canDelete).toBe(false)
  })

  test('DB Ready is not an observed listener and PENDING is preserved', async () => {
    const wrapper = await render()
    expect(wrapper.vm.smbListenerRows.map(row => row.state)).toEqual(['READY', 'PENDING'])
    const old = rows()[0]
    delete old.runtimestate
    old.state = 'Ready'
    wrapper.vm.storageService.protocols = [old]
    expect(wrapper.vm.smbListenerRows[0].state).toBe('UNKNOWN')
  })

  test('covered UUID remains visible and its wildcard owner stays protected', async () => {
    const wrapper = await render()
    const [a, b] = rows()
    a.listenip = '0.0.0.0'
    b.port = a.port
    b.runtimestate = 'COVERED'
    b.coveredbyid = a.id
    wrapper.vm.storageService.protocols = [a, b]
    expect(wrapper.vm.smbListenerRows.map(row => row.id)).toEqual([a.id, b.id])
    expect(wrapper.vm.smbListenerRows[0].canDelete).toBe(false)
    expect(wrapper.vm.smbListenerRows[1].canDelete).toBe(true)
  })

  test('editing the tuple invalidates an accepted preflight before submission', async () => {
    const wrapper = await render()
    wrapper.vm.openSmbEndpointEdit(wrapper.vm.smbListenerRows[1])
    await flushPromises()
    wrapper.vm.configurationPreflight = { status: 'READY', checks: [], fingerprint: 'before', expiresat: new Date(Date.now() + 60000).toISOString() }
    wrapper.vm.configurationImpactAcknowledged = true
    wrapper.vm.forms.enableProtocol.port = 2445
    await flushPromises()
    expect(wrapper.vm.configurationPreflight).toBeNull()
    expect(wrapper.vm.configurationImpactAcknowledged).toBe(false)
    await wrapper.vm.submitActionModal()
    expect(postAPI.mock.calls.some(([api]) => api === 'enableStorageServiceProtocol')).toBe(false)
  })
})
