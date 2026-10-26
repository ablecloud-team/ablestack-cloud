// Licensed to the Apache Software Foundation (ASF) under one or more
// contributor license agreements. See NOTICE. Apache License, Version 2.0.
// http://www.apache.org/licenses/LICENSE-2.0
import { shallowMount, flushPromises } from '@vue/test-utils'
import SharedFSTab from '@/views/storage/SharedFSTab'
import { getAPI, postAPI } from '@/api'
import wire from '../../../fixtures/storage-1450-nfs-wire.json'
import { nfsPosixRecommendation, nfsPermissionDelta, nfsSquashFlags, nfsMappingDelta } from '@/utils/nfsPosixPolicy'
import { decodeConfigurationPreflight, configurationPreflightFailure } from '@/utils/storageConfigPreflight'
import errorWire from '../../../fixtures/storage-1450-preflight-api-error.json'
import StorageConfigurationPreflightPanel from '@/components/storage/StorageConfigurationPreflightPanel'
jest.mock('@/api', () => ({ getAPI: jest.fn(), postAPI: jest.fn() }))
const dto = () => ({ ...JSON.parse(JSON.stringify(wire.liststoragenfsexportsresponse.storagenfsexport[0])), path: '/export/fixture' })
describe('NFS POSIX mounted request and actual serializer contract', () => {
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
  const edit = async (row = dto()) => { wrapper.vm.openActionModal('editNfsExport', { id: row.id, raw: row }); await flushPromises() }
  const submit = async () => {
    wrapper.vm.configurationAcceptedPreflight = { scope: wrapper.vm.storageReadScope, instanceid: 'instance', fingerprint: 'confirmed' }
    await wrapper.vm.updateNfsExport()
    return postAPI.mock.calls.find(([api]) => api === 'updateStorageNfsExport')[1]
  }
  test.each([['ROOT', false, 65534, '0775'], ['NONE', false, 0, '0755'], ['ALL', false, 65534, '0775'],
    ['ROOT', true, 0, '0755'], ['NONE', true, 0, '0755'], ['ALL', true, 0, '0755']])('policy %s readonly %s has an independent recommendation', (policy, ro, owner, mode) => {
    expect(nfsPosixRecommendation(policy, ro)).toEqual({ owneruid: owner, ownergid: owner, mode, recursivepermission: false })
  })
  test('actual API zero observation is shown without synthesizing a nobody owner', async () => {
    await edit()
    expect(wrapper.vm.forms.nfsExport.owneruid).toBe(0)
    expect(wrapper.vm.nfsCurrentPosixSummary).toBe('0:2345 / 0750')
    expect(wrapper.vm.nfsPosixEdit).toBe(false)
  })
  test('unchanged edit omits every POSIX field at the actual postAPI boundary', async () => {
    await edit()
    expect(wrapper.vm.configurationIntent.posixchanges).toEqual({})
    const params = await submit()
    for (const key of ['owneruid', 'ownergid', 'mode', 'recursivepermission']) expect(Object.prototype.hasOwnProperty.call(params, key)).toBe(false)
  })
  test('RO and squash changes never request an ownership change', async () => {
    await edit()
    wrapper.vm.forms.nfsExport.readonly = true
    wrapper.vm.nfsExportSquashPolicy = 'ALL'
    const params = await submit()
    expect(params).toEqual(expect.objectContaining({ readonly: true, rootsquash: true, allsquash: true }))
    expect(params.owneruid).toBeUndefined(); expect(params.mode).toBeUndefined()
  })
  test('an explicit partial zero change keeps other POSIX keys absent', async () => {
    const row = dto(); row.posixobservation.owneruid = 1234
    await edit(row)
    wrapper.vm.nfsPosixEdit = true
    wrapper.vm.forms.nfsExport.owneruid = 0
    expect(wrapper.vm.configurationIntent.posixchanges).toEqual({ ownerUid: 0 })
    const params = await submit()
    expect(params.owneruid).toBe(0)
    expect(params.ownergid).toBeUndefined(); expect(params.mode).toBeUndefined(); expect(params.recursivepermission).toBeUndefined()
  })
  test('changing policy does not modify draft; using recommendation is explicit', async () => {
    await edit()
    wrapper.vm.nfsExportSquashPolicy = 'ROOT'
    expect(wrapper.vm.forms.nfsExport.owneruid).toBe(0)
    wrapper.vm.useNfsRecommendedPosix()
    expect(wrapper.vm.nfsPosixEdit).toBe(true)
    expect(wrapper.vm.nfsPosixParameters).toEqual({ owneruid: 65534, ownergid: 65534, mode: '0775' })
  })
  test('modal reentry does not repeat stored recursion or previous draft', async () => {
    const row = dto(); row.config = JSON.stringify({ rootSquash: true, recursivePermission: true })
    await edit(row)
    wrapper.vm.nfsPosixEdit = true; wrapper.vm.forms.nfsExport.owneruid = 1666
    wrapper.vm.closeActionModal()
    await edit(row)
    expect(wrapper.vm.nfsPosixEdit).toBe(false)
    expect(wrapper.vm.forms.nfsExport.recursivepermission).toBe(false)
    expect(wrapper.vm.nfsPosixParameters).toEqual({})
  })
  test('unavailable observation is not replaced by a recommendation', async () => {
    const row = dto(); delete row.posixobservation
    await edit(row); wrapper.vm.useNfsRecommendedPosix()
    expect(wrapper.vm.nfsCurrentPosixSummary).toBe('label.storage.nfs.posix.unavailable')
    expect(wrapper.vm.nfsPosixEdit).toBe(false)
  })
  test('inherited ACL does not submit anonymous defaults or squash overrides', async () => {
    wrapper.vm.openActionModal('nfsAcl', { exportid: 'export' })
    await flushPromises()
    expect(wrapper.vm.nfsAclPolicyParameters).toEqual({ inheritpolicy: true })
  })
  test('octal normalization, mapping zero, and all-squash flags keep their contracts', () => {
    expect(nfsPermissionDelta({ mode: '0775' }, { mode: '775' }, true)).toEqual({})
    expect(nfsMappingDelta({ anonuid: null }, { anonuid: 0 })).toEqual({ anonuid: 0 })
    expect(nfsSquashFlags('ALL')).toEqual({ rootsquash: true, allsquash: true })
  })
  test('actual preflight API serialization exposes the current and next preview', () => {
    const report = decodeConfigurationPreflight(wire)
    expect(report.posixobservation.owneruid).toBe(0)
    expect(report.posixchanges).toEqual({ ownerUid: 1666 })
  })
  test('actual 431 wrapper stays an API rejection with its cause and cannot submit a mutation', async () => {
    await edit()
    wrapper.vm.forms.nfsExport.volumeid = 'volume'
    await flushPromises()
    const error = { response: { status: 431, data: errorWire }, config: { data: 'password=should_not_show' } }
    postAPI.mockRejectedValueOnce(error)
    await wrapper.vm.checkConfigurationPreflight()
    expect(wrapper.vm.configurationPreflight.status).toBe('BLOCKED')
    expect(wrapper.vm.configurationPreflight.checks[0]).toEqual(expect.objectContaining({
      code: 'PREFLIGHT_API_REJECTED',
      blocking: true,
      httpstatus: 431,
      message: expect.stringContaining('max allowed length 255')
    }))
    expect(wrapper.vm.actionModalOkButtonProps.disabled).toBe(true)
    expect(postAPI.mock.calls.filter(([api]) => api === 'updateStorageNfsExport')).toHaveLength(0)
    expect(JSON.stringify(wrapper.vm.configurationPreflight)).not.toContain('should_not_show')
    const panel = shallowMount(StorageConfigurationPreflightPanel, { props: { report: wrapper.vm.configurationPreflight }, global: { mocks: { $t: key => key } } })
    expect(panel.find('.check-message').text()).toContain('HTTP 431')
    expect(panel.find('.check-message').text()).toContain('max allowed length 255')
    panel.unmount()
  })
  test('a transport failure remains UNKNOWN and is not mislabelled as API input rejection', () => {
    const report = configurationPreflightFailure(new Error('NetworkError'), 20)
    expect(report.status).toBe('UNKNOWN')
    expect(report.checks[0].code).toBe('RESOURCE_OBSERVATION_UNAVAILABLE')
  })
})
