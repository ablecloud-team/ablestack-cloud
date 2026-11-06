// Licensed to the Apache Software Foundation (ASF) under one or more
// contributor license agreements. See NOTICE. Apache License, Version 2.0.
import { shallowMount } from '@vue/test-utils'
import SharedFSTab from '@/views/storage/SharedFSTab'
import SmbClientAccessFields from '@/components/storage/SmbClientAccessFields'
jest.mock('@/api', () => ({ getAPI: jest.fn(), postAPI: jest.fn() }))

// Keep the real parent template, defaults and modal entry points. Only unrelated
// inventory I/O is replaced; the modal slot must render to catch template errors.
const Parent = {
  ...SharedFSTab,
  methods: {
    ...SharedFSTab.methods,
    fetchData: jest.fn(),
    fetchDirectoryPolicies: jest.fn(),
    fetchDiskOfferings: jest.fn(),
    fetchStoragePools: jest.fn(),
    runStorageAction: jest.fn().mockResolvedValue()
  }
}
const dialog = { props: ['visible'], template: '<section v-if="visible" class="modal-body"><slot/><slot name="footer"/></section>' }
const raw = {
  id: 'share-1',
  name: 'existing',
  path: '/export/existing',
  volumeid: 'data-1',
  config: JSON.stringify({ clientAccess: { version: 1, cidrs: ['10.10.254.224/32'] }, directoryMode: '0770', fileIdentity: { version: 1, mode: 'AUTHENTICATED' } })
}
describe('SharedFSTab actual SMB create/edit modal rendering', () => {
  let wrapper, renderErrors
  afterEach(() => { if (wrapper) wrapper.unmount(); wrapper = null; expect(renderErrors).not.toHaveBeenCalled() })
  function mountParent () {
    renderErrors = jest.fn()
    wrapper = shallowMount(Parent, {
      props: { resource: { id: 'sharedfs-1', virtualmachineid: 'vm-1', volumeid: 'data-1' } },
      global: {
        config: { errorHandler: renderErrors },
        renderStubDefaultSlot: true,
        provide: { parentFetchData: jest.fn() },
        mocks: {
          $t: key => key,
          $route: { path: '/sharedfs/sharedfs-1', fullPath: '/sharedfs/sharedfs-1?tab=smb', query: { tab: 'smb' } },
          $store: { state: { app: { device: 'desktop' } }, getters: { apis: {}, capabilities: {}, features: {}, userInfo: { id: 'user-1' }, project: {}, device: 'desktop' } }
        },
        stubs: { MoldDialog: dialog, SmbClientAccessFields: false, ATable: true, ASelect: { props: ['value', 'children'], template: '<div><slot/></div>' }, AInput: { props: ['value', 'prefix'], template: '<input :value="value" />' } }
      }
    })
    return wrapper
  }
  test('create entry renders the real source-policy fields and existing SMB fields', async () => {
    mountParent()
    wrapper.vm.openActionModal('smbShare')
    await wrapper.vm.$nextTick()
    expect(wrapper.find('.modal-body .storage-action-form').exists()).toBe(true)
    expect(wrapper.findComponent(SmbClientAccessFields).exists()).toBe(true)
    expect(wrapper.findComponent(SmbClientAccessFields).props('value')).toEqual({ mode: 'ANY_SOURCE', input: '', confirmed: false })
    expect(wrapper.find('smb-file-identity-fields-stub').exists()).toBe(true)
    expect(wrapper.find('smb-parent-inheritance-fields-stub').exists()).toBe(true)
  })
  test('edit entry renders existing fields, excludes creation-only source fields and leaves stored policy untouched', async () => {
    mountParent()
    const original = raw.config
    wrapper.vm.openActionModal('editSmbShare', { raw })
    await wrapper.vm.$nextTick()
    expect(wrapper.find('.modal-body .storage-action-form').exists()).toBe(true)
    expect(wrapper.findComponent(SmbClientAccessFields).exists()).toBe(false)
    expect(wrapper.find('smb-file-identity-fields-stub').exists()).toBe(true)
    expect(wrapper.vm.forms.smbShare.name).toBe('existing')
    expect(raw.config).toBe(original)
    await wrapper.vm.updateSmbShare()
    expect(Parent.methods.runStorageAction.mock.calls.slice(-1)[0][2]).not.toHaveProperty('clientcidrs')
    expect(Parent.methods.runStorageAction.mock.calls.slice(-1)[0][2]).not.toHaveProperty('confirmanysource')
  })
  test('close and re-enter create/edit uses the parent modal discriminator each time', async () => {
    mountParent()
    for (const type of ['smbShare', 'editSmbShare', 'smbShare']) {
      wrapper.vm.openActionModal(type, type === 'editSmbShare' ? { raw } : null)
      await wrapper.vm.$nextTick()
      expect(wrapper.findComponent(SmbClientAccessFields).exists()).toBe(type === 'smbShare')
      wrapper.vm.closeActionModal()
      await wrapper.vm.$nextTick()
      expect(wrapper.find('.modal-body').exists()).toBe(false)
    }
  })
})
