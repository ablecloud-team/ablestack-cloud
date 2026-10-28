// Licensed to the Apache Software Foundation (ASF) under one or more
// contributor license agreements. See NOTICE. Apache License, Version 2.0.
// http://www.apache.org/licenses/LICENSE-2.0
import { shallowMount, flushPromises } from '@vue/test-utils'
import Panel from '@/components/storage/StorageDirectoryPolicyPanel'
import SharedFSTab from '@/views/storage/SharedFSTab'
import { getAPI, postAPI } from '@/api'
import { directoryChanges, directoryIntent, directoryParameters } from '@/utils/sharedDirectoryPolicy'
import { isConfigurationApi } from '@/utils/storageConfigOperation'
import wire from '../../../fixtures/storage-1452-directory-wire.json'
jest.mock('@/api', () => ({ getAPI: jest.fn(), postAPI: jest.fn() }))
const clone = value => JSON.parse(JSON.stringify(value))
describe('Shared directory actual serializer and component request contracts', () => {
  let wrapper
  const row = () => clone(wire.list.liststoragedirectorypoliciesresponse.directorypolicy[0])
  const create = props => shallowMount(Panel, { props: { instance: { id: 'instance', configurationrevision: 12 }, execute: jest.fn().mockResolvedValue({ state: 'COMMITTED' }), ...props }, global: { mocks: { $t: k => k } } })
  beforeEach(() => {
    getAPI.mockReset(); postAPI.mockReset()
    getAPI.mockResolvedValue(clone(wire.list))
    const response = clone(wire.preflight)
    response.preflightstorageserviceconfigurationresponse.configurationpreflight.expiresat = new Date(Date.now() + 30000).toISOString()
    postAPI.mockResolvedValue(response)
  })
  afterEach(() => { if (wrapper) wrapper.unmount(); jest.restoreAllMocks() })
  test('list request has page and real serializer values remain typed', async () => {
    wrapper = create(); await flushPromises()
    expect(getAPI).toHaveBeenCalledWith('listStorageDirectoryPolicies', { instanceid: 'instance', page: 1, pagesize: 500 }, expect.anything())
    expect(wrapper.vm.rows[0].observation.owneruid).toBe(1001)
    expect(wrapper.vm.rows[0].policyrevision).toBe(3)
    expect(wrapper.vm.loading).toBe(false)
  })
  test('unchanged POSIX and recursive false are omitted; adoption sends no POSIX', () => {
    const value = row()
    const draft = { owneruid: 1001, ownergid: 1001, mode: '0770', recursive: false }
    expect(directoryChanges(value, draft)).toEqual({})
    const legacy = { ...value, id: undefined }
    expect(directoryParameters(legacy, draft)).toEqual({ volumeid: 'volume', path: '/export/team' })
    expect(directoryIntent(legacy, draft)).toEqual({ api: 'createStorageDirectoryPolicy', volumeid: 'volume', path: '/export/team' })
  })
  test('mounted preflight and actual update carry the same single changed mode', async () => {
    wrapper = create(); await flushPromises()
    wrapper.vm.open(wrapper.vm.rows[0]); await flushPromises()
    expect(wrapper.vm.dirty).toBe(false)
    wrapper.vm.draft.mode = '0775'; await flushPromises()
    await wrapper.vm.check()
    const args = postAPI.mock.calls[0]
    expect(args[0]).toBe('preflightStorageServiceConfiguration')
    expect(JSON.parse(args[1].intent)).toEqual({ api: 'updateStorageDirectoryPolicy', resourceid: 'policy', expectedpolicyrevision: 3, posixchanges: { mode: '0775' } })
    expect(wrapper.vm.report.directorypreview.after.mode).toBe('0775')
    expect(wrapper.vm.applicable).toBe(false)
    wrapper.vm.ack = true
    await wrapper.vm.$nextTick()
    await wrapper.vm.apply()
    expect(wrapper.props('execute')).toHaveBeenCalledWith('updateStorageDirectoryPolicy', { instanceid: 'instance', id: 'policy', expectedpolicyrevision: 3, mode: '0775' }, expect.anything())
  })
  test('draft changes invalidate the old acknowledgement and protected reentry does not open', async () => {
    wrapper = create(); await flushPromises()
    wrapper.vm.open(wrapper.vm.rows[0]); wrapper.vm.draft.mode = '0775'; await flushPromises()
    await wrapper.vm.check(); wrapper.vm.ack = true
    wrapper.vm.draft.mode = '0777'; await flushPromises()
    expect(wrapper.vm.report).toBe(null); expect(wrapper.vm.ack).toBe(false)
    wrapper.vm.close(); await wrapper.setProps({ guarded: true })
    wrapper.vm.open(row()); expect(wrapper.vm.selected).toBe(null)
  })
  test('late response from another instance cannot replace current rows', async () => {
    let complete
    getAPI.mockReturnValueOnce(new Promise(resolve => { complete = resolve }))
    wrapper = create()
    await wrapper.setProps({ instance: { id: 'other', configurationrevision: 0 } })
    await flushPromises()
    complete({ liststoragedirectorypoliciesresponse: { directorypolicy: [{ id: 'stale' }] } })
    await flushPromises()
    expect(wrapper.vm.rows.some(r => r.id === 'stale')).toBe(false)
  })
  test('bound protocol forms omit independent POSIX but leave anonymous mapping separate', () => {
    const context = { forms: { nfsExport: { directorypolicyid: 'policy', owneruid: 123 } }, nfsPosixBaseline: {}, nfsPosixEdit: true }
    expect(SharedFSTab.computed.nfsPosixParameters.call(context)).toEqual({})
    expect(isConfigurationApi('createStorageDirectoryPolicy')).toBe(true)
    expect(isConfigurationApi('updateStorageDirectoryPolicy')).toBe(true)
  })
  test('explicit drift correction uses current observations without silently retaining an old desired UID', () => {
    const value = row(); value.owneruid = 1002
    expect(directoryChanges(value, { owneruid: 1001, ownergid: 1001, mode: '0770' })).toEqual({ ownerUid: 1001 })
  })
  test('consecutive completed policy actions use different request IDs while unresolved work stays protected', async () => {
    global.crypto = require('crypto').webcrypto
    jest.spyOn(SharedFSTab.methods, 'fetchData').mockResolvedValue()
    jest.spyOn(SharedFSTab.methods, 'fetchStorageServiceData').mockResolvedValue()
    jest.spyOn(SharedFSTab.methods, 'refreshAfterStorageAction').mockResolvedValue()
    let revision = 12
    getAPI.mockImplementation(async (api, params) => api === 'listStorageServiceInstances'
      ? { liststorageserviceinstancesresponse: { storageserviceinstance: [{ id: 'instance', configurationrevision: ++revision, configurationprotection: false }] } }
      : { liststorageserviceconfigurationoperationsresponse: { configurationoperation: [{ id: params.requestid, requestid: params.requestid, instanceid: 'instance', state: 'COMMITTED', result: {} }] } })
    postAPI.mockImplementation(async api => ({ [api.toLowerCase() + 'response']: { jobid: 'job' } }))
    wrapper = shallowMount(SharedFSTab, {
      props: { resource: { id: 'sharedfs' } },
      global: {
        provide: { parentFetchData: jest.fn() },
        mocks: {
          $t: k => k,
          $route: { fullPath: '/sharedfs/sharedfs?tab=directory', query: { tab: 'directory' }, params: { id: 'sharedfs' } },
          $router: { push: jest.fn(), replace: jest.fn() },
          $store: { state: { app: { device: 'desktop' } }, getters: { apis: {}, userInfo: { id: 'user' } } },
          $message: { error: jest.fn(), success: jest.fn() },
          $notifyError: jest.fn()
        }
      }
    })
    wrapper.vm.storageService.instance = { id: 'instance', configurationrevision: 12 }
    await wrapper.vm.executeDirectoryPolicy('createStorageDirectoryPolicy', { volumeid: 'volume', path: '/export/team' }, { fingerprint: 'confirmed' })
    await wrapper.vm.executeDirectoryPolicy('updateStorageDirectoryPolicy', { id: 'policy', expectedpolicyrevision: 1, mode: '0775' }, { fingerprint: 'confirmed-again' })
    const writes = postAPI.mock.calls.filter(([api]) => ['createStorageDirectoryPolicy', 'updateStorageDirectoryPolicy'].includes(api))
    expect(writes).toHaveLength(2)
    expect(writes[0][1].requestid).not.toBe(writes[1][1].requestid)
    wrapper.vm.configurationOperation = { state: 'RECOVERY_REQUIRED', requestid: writes[1][1].requestid }
    await wrapper.vm.executeDirectoryPolicy('updateStorageDirectoryPolicy', { id: 'policy' }, { fingerprint: 'third' })
    expect(postAPI.mock.calls.filter(([api]) => api === 'updateStorageDirectoryPolicy')).toHaveLength(1)
  })
})
