// Licensed to the Apache Software Foundation (ASF) under one or more
// contributor license agreements. See NOTICE. Apache License, Version 2.0.
// http://www.apache.org/licenses/LICENSE-2.0
import { shallowMount, flushPromises } from '@vue/test-utils'
import SharedFSTab from '@/views/storage/SharedFSTab'
import MoldDialog from '@/components/view/MoldDialog'
import InheritanceFields from '@/components/storage/SmbParentInheritanceFields'
import protectionWire from '../../../fixtures/storage-1453-protection-wire.json'
import PreflightPanel from '@/components/storage/StorageConfigurationPreflightPanel'
import { getAPI, postAPI } from '@/api'
import protocolWire from '../../../fixtures/storage-1444-protocols-wire.json'
import serializedWire from '../../../fixtures/storage-configuration-preflight-wire.json'
import { decodeConfigurationPreflight, preflightApplicable, configurationModalIntent } from '@/utils/storageConfigPreflight'
jest.mock('@/api', () => ({ getAPI: jest.fn(), postAPI: jest.fn() }))
const report = status => ({
  status,
  revision: 2,
  fingerprint: 'impact',
  observedat: new Date().toISOString(),
  expiresat: new Date(Date.now() + 30000).toISOString(),
  checks: [{ code: status === 'READY' ? 'MEMORY_AVAILABLE' : 'MEMORY_LOW', blocking: status !== 'READY' }],
  impact: { ackrequired: true, class: 'TEMPORARY_INTERRUPTION', affectedcount: 1, connections: 1 },
  profile: { softdeadlineseconds: 300 },
  cancellationsupported: false
})
describe('mounted SharedFS preflight admission contract', () => {
  const wrappers = []
  let status
  beforeEach(() => {
    global.crypto = require('crypto').webcrypto
    status = 'READY'; sessionStorage.clear()
    for (const method of ['fetchData', 'fetchStorageServiceData', 'fetchDiskOfferings', 'fetchStoragePools', 'refreshAfterStorageAction']) jest.spyOn(SharedFSTab.methods, method).mockResolvedValue()
    postAPI.mockReset(); getAPI.mockReset()
    postAPI.mockImplementation(async api => api === 'preflightStorageServiceConfiguration'
      ? { preflightstorageserviceconfigurationresponse: { configurationpreflight: report(status) } }
      : { [api.toLowerCase() + 'response']: { jobid: 'job' } })
    getAPI.mockImplementation(async api => api === 'listStorageServiceInstances'
      ? { liststorageserviceinstancesresponse: { storageserviceinstance: [{ id: 'instance', configurationrevision: 3, configurationprotection: false }] } }
      : api === 'listVolumes' ? { listvolumesresponse: { volume: [{ id: 'prepared', state: 'Ready' }] } }
        : { liststorageserviceconfigurationoperationsresponse: { configurationoperation: [{ id: 'operation', state: 'COMMITTED', result: {} }] } })
  })
  afterEach(() => { wrappers.splice(0).forEach(w => w.unmount()); jest.restoreAllMocks(); jest.useRealTimers() })
  const render = async () => {
    const wrapper = shallowMount(SharedFSTab, {
      props: { resource: { id: 'sharedfs', zoneid: 'zone', volumeid: 'data', filesystem: 'xfs' } },
      global: {
        stubs: { MoldDialog: { template: '<section><slot /></section>' }, SmbParentInheritanceFields: false, AForm: { template: '<form><slot /></form>' } },
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
    wrapper.vm.storageService.instance = { id: 'instance', configurationrevision: 2 }
    wrapper.vm.storageService.protocols = JSON.parse(JSON.stringify(protocolWire.liststorageserviceprotocolsresponse.storageserviceprotocol))
    wrapper.vm.volume = { id: 'data', filesystem: 'xfs', size: 20 * 1024 ** 3 }
    wrapper.vm.openActionModal('nfsExport')
    wrapper.vm.forms.nfsExport.volumeid = 'data'
    await flushPromises()
    return wrapper
  }
  const disabled = wrapper => wrapper.findComponent(MoldDialog).vm.$attrs.okButtonProps.disabled
  test('first submit is the credential-free preflight, never volume creation or apply', async () => {
    const w = await render()
    await w.vm.submitActionModal()
    expect(postAPI).toHaveBeenCalledTimes(1)
    const [api, args] = postAPI.mock.calls[0]
    expect(api).toBe('preflightStorageServiceConfiguration')
    expect(JSON.parse(args.intent)).toEqual(expect.objectContaining({ api: 'createStorageNfsExport', volumeid: 'data' }))
    expect(disabled(w)).toBe(true)
    expect(w.vm.actionModal.visible).toBe(true)
  })
  test('actual create method applies exactly once only after matching impact acknowledgement', async () => {
    const w = await render()
    await w.vm.submitActionModal()
    await flushPromises()
    w.findComponent(PreflightPanel).vm.$emit('acknowledge', true)
    await flushPromises()
    expect(disabled(w)).toBe(false)
    await w.vm.submitActionModal()
    expect(postAPI.mock.calls.map(([api]) => api)).toEqual(['preflightStorageServiceConfiguration', 'createStorageNfsExport'])
    expect(postAPI.mock.calls[1][1]).toEqual(expect.objectContaining({ impactack: 'impact', listenerports: '2049', expectedrevision: 2, requestid: expect.any(String) }))
  })
  test('blocked resources cannot be acknowledged or invoke any mutation', async () => {
    const w = await render(); status = 'BLOCKED'
    await w.vm.submitActionModal()
    w.vm.configurationImpactAcknowledged = true
    expect(disabled(w)).toBe(true)
    expect(postAPI.mock.calls.map(([api]) => api)).toEqual(['preflightStorageServiceConfiguration'])
  })
  test('editing invalidates the plan while equivalent form refreshes do not', async () => {
    const w = await render(); await w.vm.submitActionModal()
    w.vm.configurationImpactAcknowledged = true
    w.vm.forms = JSON.parse(JSON.stringify(w.vm.forms))
    await flushPromises()
    expect(w.vm.configurationPreflight.status).toBe('READY')
    w.vm.forms.nfsExport.readonly = !w.vm.forms.nfsExport.readonly
    await flushPromises()
    expect(w.vm.configurationPreflight).toBeNull()
    expect(w.vm.configurationImpactAcknowledged).toBe(false)
  })
  test('a disconnected preflight is unknown and never an empty success', async () => {
    const w = await render(); postAPI.mockRejectedValueOnce(new Error('QGA unavailable'))
    await w.vm.submitActionModal()
    expect(w.vm.configurationPreflight.status).toBe('UNKNOWN')
    expect(disabled(w)).toBe(true)
    expect(postAPI).toHaveBeenCalledTimes(1)
  })
  test.each(['UNKNOWN', 'BLOCKED'])('actual protection rejection %s remains visible and cannot apply after expiry', async rejectedStatus => {
    const w = await render()
    w.vm.actionModal.type = 'editSmbShare'
    Object.assign(w.vm.forms.smbShare, { id: 'share', volumeid: 'data', volumemode: 'CURRENT', directorypolicyid: 'policy', inheritowner: true, inheritownerbaseline: false })
    await flushPromises()
    const actual = JSON.parse(JSON.stringify(protectionWire))
    actual.preflightstorageserviceconfigurationresponse.configurationpreflight.status = rejectedStatus
    postAPI.mockResolvedValueOnce(actual)
    jest.useFakeTimers()
    await w.vm.checkConfigurationPreflight()
    await w.vm.$nextTick()
    jest.advanceTimersByTime(60000)
    await w.vm.$nextTick()
    expect(w.vm.configurationPreflight.status).toBe(rejectedStatus)
    expect(w.findComponent(InheritanceFields).vm.failure).toBe('message.storage.inheritance.protection')
    expect(disabled(w)).toBe(true)
    expect(postAPI.mock.calls.map(([api]) => api)).toEqual(['preflightStorageServiceConfiguration'])
    w.vm.forms.smbShare.inheritowner = false
    await w.vm.$nextTick()
    expect(w.vm.configurationPreflight).toBeNull()
  })
  test('a READY approval still expires and clears acknowledgement', async () => {
    const w = await render()
    jest.useFakeTimers()
    await w.vm.checkConfigurationPreflight()
    w.vm.configurationImpactAcknowledged = true
    expect(w.vm.configurationPreflight.status).toBe('READY')
    jest.advanceTimersByTime(30001)
    await w.vm.$nextTick()
    expect(w.vm.configurationPreflight).toBeNull()
    expect(w.vm.configurationImpactAcknowledged).toBe(false)
  })
  test('confirmed prepared volume survives component reentry without another createVolume', async () => {
    const w = await render()
    const params = { name: 'test-prepared', zoneid: 'zone', diskofferingid: 'sparse', storageid: 'pool', size: 20 }
    w.vm.rememberPreparedConfigurationVolume(params, 'prepared')
    w.unmount(); wrappers.splice(wrappers.indexOf(w), 1)
    const next = await render()
    Object.assign(next.vm.forms.nfsExport, { volumemode: 'NEW', newvolumename: params.name, diskofferingid: 'sparse', storageid: 'pool', newvolumesize: 20 })
    expect(await next.vm.prepareNfsExportVolume()).toBe('prepared')
    expect(postAPI).not.toHaveBeenCalled()
  })
  test('impact confirmation survives expiry while an already confirmed volume preparation completes', async () => {
    const w = await render(); await w.vm.submitActionModal()
    w.vm.configurationImpactAcknowledged = true
    jest.spyOn(w.vm, 'prepareNfsExportVolume').mockImplementation(async () => {
      w.vm.invalidateConfigurationPreflight()
      return 'data'
    })
    await w.vm.submitActionModal()
    expect(postAPI.mock.calls[1][0]).toBe('createStorageNfsExport')
    expect(postAPI.mock.calls[1][1].impactack).toBe('impact')
  })
  test('late preflight from another instance cannot populate the current form', async () => {
    const w = await render()
    let finishPreflight
    postAPI.mockImplementationOnce(() => new Promise(resolve => { finishPreflight = resolve }))
    const pending = w.vm.checkConfigurationPreflight()
    await w.vm.$nextTick()
    w.vm.storageService.instance = { id: 'other-instance', configurationrevision: 2 }
    await flushPromises()
    finishPreflight({ preflightstorageserviceconfigurationresponse: { configurationpreflight: report('READY') } })
    await pending
    expect(w.vm.configurationPreflight).toBeNull()
    expect(w.vm.configurationPreflightLoading).toBe(false)
    expect(postAPI).toHaveBeenCalledTimes(1)
  })
  test('the actual ApiResponseSerializer scalar wire is decoded without inventing a list envelope', () => {
    const decoded = decodeConfigurationPreflight(serializedWire)
    expect(decoded.status).toBe('READY')
    expect(decoded.revision).toBe(2)
    expect(decoded.cancellationsupported).toBe(false)
    expect(Number.isFinite(Date.parse(decoded.expiresat))).toBe(true)
  })
  test('decoder expiry and public intent never expose credential fields', () => {
    expect(decodeConfigurationPreflight({})).toBeNull()
    const value = report('READY')
    expect(preflightApplicable(value, true, Date.parse(value.expiresat) + 1)).toBe(false)
    const intent = configurationModalIntent('smbAcl', { smbAcl: { password: 'private', principal: 'user' } })
    expect(JSON.stringify(intent)).not.toContain('private')
  })
})
