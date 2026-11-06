// Licensed under the Apache License, Version 2.0. See NOTICE.
// http://www.apache.org/licenses/LICENSE-2.0
import { shallowMount, flushPromises } from '@vue/test-utils'
import SharedFSTab from '@/views/storage/SharedFSTab'
import PreflightPanel from '@/components/storage/StorageConfigurationPreflightPanel'
import { getAPI, postAPI } from '@/api'
import { nfsClientPolicyFields } from '@/utils/nfsClientPolicy'
import actualWire from '../../../fixtures/storage-1460-online-preflight-wire.json'
jest.mock('@/api', () => ({ getAPI: jest.fn(), postAPI: jest.fn() }))
describe('real SharedFSTab online NFS planning and submission contract', () => {
  let wrapper
  const acl = {
    id: 'acl',
    resourceid: 'export',
    principaltype: 'CIDR',
    principal: '10.10.254.251/32',
    permission: 'READ_WRITE',
    state: 'Ready',
    config: JSON.stringify({ rootSquash: true, allSquash: false, sync: true, secure: false })
  }
  const admission = () => {
    const wire = JSON.parse(JSON.stringify(actualWire))
    wire.preflightstorageserviceconfigurationresponse.configurationpreflight.expiresat = new Date(Date.now() + 30000).toISOString()
    return wire
  }
  beforeEach(async () => {
    global.crypto = require('crypto').webcrypto
    global.TextEncoder = require('util').TextEncoder
    sessionStorage.clear()
    for (const name of ['fetchData', 'fetchStorageServiceData', 'fetchDiskOfferings', 'fetchStoragePools', 'refreshAfterStorageAction']) jest.spyOn(SharedFSTab.methods, name).mockResolvedValue()
    postAPI.mockReset(); getAPI.mockReset()
    postAPI.mockImplementation(async api => api === 'preflightStorageServiceConfiguration' ? admission() : { [api.toLowerCase() + 'response']: { jobid: 'job' } })
    getAPI.mockImplementation(async api => api === 'listStorageServiceInstances'
      ? { liststorageserviceinstancesresponse: { storageserviceinstance: [{ id: 'instance', configurationrevision: 0, configurationprotection: false }] } }
      : { liststorageserviceconfigurationoperationsresponse: { configurationoperation: [{ id: 'operation', state: 'COMMITTED', result: {} }] } })
    wrapper = shallowMount(SharedFSTab, {
      props: { resource: { id: 'sharedfs', zoneid: 'zone', volumeid: 'volume', filesystem: 'xfs' } },
      global: {
        stubs: { StorageConfigurationPreflightPanel: false, MoldDialog: { template: '<section><slot /></section>' }, AForm: { template: '<form><slot /></form>' }, ATag: { template: '<span><slot /></span>' } },
        provide: { parentFetchData: jest.fn() },
        mocks: {
          $t: key => key,
          $route: { fullPath: '/sharedfs/sharedfs?tab=nfs', query: { tab: 'nfs' }, params: { id: 'sharedfs' } },
          $router: { push: jest.fn(), replace: jest.fn() },
          $store: { state: { app: { device: 'desktop' } }, getters: { apis: {}, userInfo: { id: 'user' } } },
          $message: { error: jest.fn(), success: jest.fn() },
          $notifyError: jest.fn()
        }
      }
    })
    wrapper.vm.storageService.instance = { id: 'instance', configurationrevision: 0, configurationprotection: false }
    wrapper.vm.storageService.nfsExports = [{ id: 'export', name: 'shared', path: '/export/shared', state: 'Ready' }]
    wrapper.vm.storageService.nfsAcls = [acl]
    await flushPromises()
  })
  afterEach(() => { wrapper.unmount(); jest.restoreAllMocks() })
  const submit = async api => {
    await wrapper.vm.submitActionModal(); await flushPromises()
    const planning = JSON.parse(postAPI.mock.calls.find(([name]) => name === 'preflightStorageServiceConfiguration')[1].intent)
    expect(wrapper.vm.configurationPreflight.impact.method).toBe('NFS_CLIENT_RELOAD')
    const panel = wrapper.findComponent(PreflightPanel)
    expect(panel.html()).toContain('message.storage.nfs.client.online')
    expect(panel.text()).toContain('label.storage.nfs.client.online.check')
    panel.vm.$emit('acknowledge', true); await flushPromises()
    await wrapper.vm.submitActionModal(); await flushPromises()
    const parameters = postAPI.mock.calls.find(([name]) => name === api)[1]
    expect(planning.nfsclientpolicy).toEqual(nfsClientPolicyFields(parameters))
    expect(parameters.impactack).toBe(actualWire.preflightstorageserviceconfigurationresponse.configurationpreflight.fingerprint)
    expect(parameters).not.toHaveProperty('owneruid')
    expect(parameters).not.toHaveProperty('ownergid')
    expect(parameters).not.toHaveProperty('mode')
    return { planning, parameters }
  }
  test('edit uses exactly the submitted access and squash patch, including false and zero', async () => {
    wrapper.vm.openActionModal('editNfsAcl', { raw: acl })
    Object.assign(wrapper.vm.forms.nfsAcl, { permission: 'READ_ONLY', squashpolicy: 'NONE', anonuid: 0, anongid: 0 })
    await flushPromises()
    const { planning, parameters } = await submit('updateStorageNfsAcl')
    expect(parameters.id).toBe('acl')
    expect(planning.nfsclientpolicy).toEqual(expect.objectContaining({ permission: 'READ_ONLY', rootsquash: false, allsquash: false, anonuid: 0, anongid: 0 }))
  })
  test('create shares the normalized principal list and inherited mapping', async () => {
    wrapper.vm.openActionModal('nfsAcl', { exportid: 'export' })
    Object.assign(wrapper.vm.forms.nfsAcl, { principals: ['10.10.254.251/32', '10.10.254.252/32'], squashpolicy: 'INHERIT' })
    await flushPromises()
    const { planning, parameters } = await submit('createStorageNfsAcl')
    expect(planning.exportid).toBe(parameters.exportid)
    expect(planning.nfsclientpolicy).toEqual(expect.objectContaining({ principals: '10.10.254.251/32,10.10.254.252/32', inheritpolicy: true }))
  })
  test('delete intent is resource-bound and contains no stale edit fields', async () => {
    wrapper.vm.openDeleteModal('nfsAcl', { raw: acl })
    wrapper.vm.forms.deleteConfirm.confirmation = acl.principal
    await flushPromises()
    const { planning, parameters } = await submit('deleteStorageNfsAcl')
    expect(planning.resourceid).toBe(parameters.id)
    expect(planning.nfsclientpolicy).toEqual({})
  })
  test('real blocking drain cannot be acknowledged into an online mutation', async () => {
    const wire = admission(); const report = wire.preflightstorageserviceconfigurationresponse.configurationpreflight
    report.status = 'BLOCKED'; report.impact = { class: 'DRAIN_REQUIRED', drainrequired: true, ackrequired: true, connections: 3 }
    report.checks = [{ code: 'DRAIN_REQUIRED', blocking: true }]
    postAPI.mockResolvedValue(wire)
    wrapper.vm.openDeleteModal('nfsAcl', { raw: acl }); wrapper.vm.forms.deleteConfirm.confirmation = acl.principal
    await wrapper.vm.submitActionModal(); wrapper.vm.configurationImpactAcknowledged = true
    await wrapper.vm.submitActionModal()
    expect(postAPI.mock.calls.every(([name]) => name === 'preflightStorageServiceConfiguration')).toBe(true)
  })
})
