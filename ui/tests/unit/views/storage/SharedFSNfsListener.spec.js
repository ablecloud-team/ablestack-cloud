// Licensed to the Apache Software Foundation (ASF) under one or more
// contributor license agreements. See NOTICE. Apache License, Version 2.0.
// http://www.apache.org/licenses/LICENSE-2.0
import { shallowMount, flushPromises } from '@vue/test-utils'
import SharedFSTab from '@/views/storage/SharedFSTab'
import { getAPI, postAPI } from '@/api'
import wire from '../../../fixtures/storage-1444-protocols-wire.json'

jest.mock('@/api', () => ({ getAPI: jest.fn(), postAPI: jest.fn() }))
const protocols = () => JSON.parse(JSON.stringify(wire.liststorageserviceprotocolsresponse.storageserviceprotocol))

describe('mounted SharedFS NFS listener request contract', () => {
  const wrappers = []
  beforeEach(() => {
    global.crypto = require('crypto').webcrypto
    jest.spyOn(SharedFSTab.methods, 'fetchData').mockResolvedValue()
    jest.spyOn(SharedFSTab.methods, 'fetchStorageServiceData').mockResolvedValue()
    jest.spyOn(SharedFSTab.methods, 'fetchDiskOfferings').mockResolvedValue()
    jest.spyOn(SharedFSTab.methods, 'fetchStoragePools').mockResolvedValue()
    jest.spyOn(SharedFSTab.methods, 'refreshAfterStorageAction').mockResolvedValue()
    getAPI.mockReset()
    postAPI.mockReset()
    getAPI.mockImplementation(async api => api === 'listStorageServiceInstances'
      ? { liststorageserviceinstancesresponse: { storageserviceinstance: [{ id: 'instance', configurationprotection: false, configurationrevision: 0 }] } }
      : api === 'listStorageServiceConfigurationOperations'
        ? { liststorageserviceconfigurationoperationsresponse: { count: 1, configurationoperation: [{ id: 'operation', state: 'COMMITTED', result: {} }] } }
        : {})
    postAPI.mockResolvedValue({ createstoragenfsexportresponse: { jobid: 'job' } })
  })
  afterEach(() => {
    wrappers.splice(0).forEach(wrapper => wrapper.unmount())
    jest.restoreAllMocks()
  })

  const render = async rows => {
    const wrapper = shallowMount(SharedFSTab, {
      props: { resource: { id: 'sharedfs', zoneid: 'zone', volumeid: 'data', filesystem: 'xfs' } },
      global: {
        provide: { parentFetchData: jest.fn() },
        mocks: {
          $t: key => key,
          $route: { fullPath: '/sharedfs/sharedfs?tab=nfs', query: { tab: 'nfs' }, params: { id: 'sharedfs' } },
          $router: { push: jest.fn(), replace: jest.fn() },
          $store: { state: { app: { device: 'desktop' } }, getters: { apis: { listStorageServiceInstances: {}, createStorageNfsExport: {} }, userInfo: { id: 'user' } } },
          $message: { error: jest.fn() },
          $notifyError: jest.fn()
        }
      }
    })
    wrappers.push(wrapper)
    wrapper.vm.storageService.instance = { id: 'instance', configurationrevision: 0 }
    wrapper.vm.storageService.protocols = rows
    wrapper.vm.volume = { id: 'data', filesystem: 'xfs', size: 20 * 1024 ** 3 }
    await flushPromises()
    return wrapper
  }

  const submit = async wrapper => {
    wrapper.vm.openActionModal('nfsExport')
    await flushPromises()
    // This test supplies the existing test DATA selection; no volume operation is stubbed.
    wrapper.vm.forms.nfsExport.volumeid = 'data'
    wrapper.vm.configurationAcceptedPreflight = { scope: wrapper.vm.storageReadScope, instanceid: 'instance', fingerprint: 'confirmed' }
    await wrapper.vm.createNfsExport()
    return postAPI.mock.calls.find(([api]) => api === 'createStorageNfsExport')?.[1]
  }

  test.each([2049, 3049])('mixed live API protocols submit only the registered NFS port %s', async port => {
    const rows = protocols()
    const nfs = rows.find(row => row.protocol === 'NFS')
    nfs.port = port
    nfs.config = JSON.stringify({ protocolMode: 'V4_ONLY', listenerGroups: [{ port }] })
    const wrapper = await render(rows)
    const request = await submit(wrapper)
    expect(request.listenerports).toBe(String(port))
    expect(wrapper.vm.nfsListenerGroupOptions.map(option => option.value)).toEqual([port])
    expect(request).toEqual(expect.objectContaining({
      instanceid: 'instance',
      listenerports: String(port),
      protocolmode: 'V4_ONLY',
      expectedrevision: 0,
      requestid: expect.stringMatching(/^[0-9a-f-]{36}$/)
    }))
    expect(postAPI).toHaveBeenCalledTimes(1)
  })

  test('single NFS retains its ordinary 2049 creation request', async () => {
    const wrapper = await render(protocols().filter(row => row.protocol === 'NFS'))
    expect((await submit(wrapper)).listenerports).toBe('2049')
  })

  test('only absent NFS information falls back to 2049, never another protocol port', async () => {
    const wrapper = await render(protocols().filter(row => row.protocol !== 'NFS'))
    expect(wrapper.vm.nfsListenerGroupOptions.map(option => option.value)).toEqual([2049])
    expect((await submit(wrapper)).listenerports).toBe('2049')
  })

  test('dual mode keeps the required 2049 request policy', async () => {
    const rows = protocols()
    const nfs = rows.find(row => row.protocol === 'NFS')
    nfs.protocolmode = 'V3V4_DUAL'
    nfs.config = JSON.stringify({ protocolMode: 'V3V4_DUAL' })
    const wrapper = await render(rows)
    const request = await submit(wrapper)
    expect(request.listenerports).toBe('2049')
    expect(request.protocolmode).toBe('V3V4_DUAL')
    expect(request.endpointmode).toBeUndefined()
  })

  test('editing preserves an existing explicit custom NFS listener', async () => {
    const wrapper = await render(protocols())
    wrapper.vm.openActionModal('editNfsExport', {
      id: 'existing',
      name: 'existing',
      path: '/export/existing',
      volumeid: 'data',
      config: JSON.stringify({ listenerGroupPorts: [8449], protocolMode: 'V4_ONLY' })
    })
    await flushPromises()
    expect(wrapper.vm.forms.nfsExport.listenerports).toEqual([8449])
  })
})
