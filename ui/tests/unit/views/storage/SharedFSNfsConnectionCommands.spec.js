// Licensed to the Apache Software Foundation (ASF) under one or more
// contributor license agreements. See NOTICE. Apache License, Version 2.0.
import { shallowMount, flushPromises } from '@vue/test-utils'
import SharedFSTab from '@/views/storage/SharedFSTab'
import { getAPI } from '@/api'
import wire from '../../../fixtures/storage-1459-dual-connection-wire.json'
import en from '../../../../public/locales/en.json'
import ko from '../../../../public/locales/ko_KR.json'

jest.mock('@/api', () => ({ getAPI: jest.fn(), postAPI: jest.fn() }))
const clone = x => JSON.parse(JSON.stringify(x))
const labels = {
  'label.storage.service.export.name.placeholder': 'EXPORT_NAME',
  'label.storage.service.local.mount.path.placeholder': 'MOUNT_PATH',
  'label.storage.service.endpoint.ip.placeholder': 'ENDPOINT_IP'
}
let wrapper
async function render (protocols = wire.listStorageServiceProtocols) {
  for (const method of ['fetchData', 'fetchStorageServiceData', 'fetchDiskOfferings', 'fetchStoragePools']) jest.spyOn(SharedFSTab.methods, method).mockResolvedValue()
  getAPI.mockImplementation(async api => api === 'listStorageServiceProtocols' ? clone(protocols) : api === 'listStorageNfsExports' ? clone(wire.listStorageNfsExports) : {})
  const source = wire.listStorageServiceProtocols.liststorageserviceprotocolsresponse.storageserviceprotocol[0]
  wrapper = shallowMount(SharedFSTab, {
    props: { resource: { id: 'sharedfs', ipaddress: source.primaryip } },
    global: {
      provide: { parentFetchData: jest.fn() },
      mocks: {
        $t: key => labels[key] || key,
        $route: { fullPath: '/sharedfs/sharedfs?tab=nfs', query: { tab: 'nfs' }, params: { id: 'sharedfs' } },
        $router: { push: jest.fn(), replace: jest.fn() },
        $store: { state: { app: { device: 'desktop' } }, getters: { apis: {}, userInfo: { id: 'user' } } },
        $message: { error: jest.fn() },
        $notifyError: jest.fn()
      }
    }
  })
  wrapper.vm.storageService.instance = { id: source.instanceid, ipaddress: source.primaryip }
  await wrapper.vm.fetchCollection('listStorageServiceProtocols', 'protocols', 'storageserviceprotocol')
  await wrapper.vm.fetchCollection('listStorageNfsExports', 'nfsExports', 'storagenfsexport')
  await flushPromises()
  return wrapper
}
afterEach(() => { if (wrapper) wrapper.unmount(); wrapper = null; jest.restoreAllMocks(); jest.clearAllMocks() })

test('actual DUAL API wire renders the same published pseudo root for v3 and v4, never the physical export path', async () => {
  await render()
  const commands = wrapper.vm.nfsConnectionCommands
  expect(commands).toEqual([
    'mount -t nfs4 -o vers=4.1,proto=tcp,port=2049 10.10.13.181:/<EXPORT_NAME> <MOUNT_PATH>',
    'mount -t nfs -o vers=3,proto=tcp,port=2049 10.10.13.181:/<EXPORT_NAME> <MOUNT_PATH>',
    'showmount -e 10.10.13.181'
  ])
  const share = wrapper.vm.storageService.nfsExports[0]
  expect(share.path).toBe('/export/dual001')
  for (const command of commands.slice(0, 2)) {
    const resolved = command.replace('<EXPORT_NAME>', share.name)
    expect(resolved).toContain('10.10.13.181:/dual001 ')
    expect(resolved).not.toContain(':/export/')
    expect(resolved).not.toContain(share.volumemountpath)
  }
})

test('V4_ONLY retains an explicit listener port and does not advertise v3 or showmount', async () => {
  const value = clone(wire.listStorageServiceProtocols)
  const row = value.liststorageserviceprotocolsresponse.storageserviceprotocol[0]
  row.protocolmode = 'V4_ONLY'
  row.port = 3049
  row.effectiveendpoints = ['10.10.13.181:3049']
  row.config = JSON.stringify({ protocolMode: 'V4_ONLY', listenerGroups: [{ port: 3049, state: 'Ready' }] })
  await render(value)
  expect(wrapper.vm.nfsConnectionCommands).toEqual([
    'mount -t nfs4 -o vers=4.1,proto=tcp,port=3049 10.10.13.181:/<EXPORT_NAME> <MOUNT_PATH>'
  ])
})

test.each([['ko', ko], ['en', en]])('%s description documents the published path and discovery without advertising the backing path', (lang, messages) => {
  const text = messages['message.storage.service.nfs.connection.generic']
  expect(text).toContain('/<name>')
  expect(text).toContain('showmount -e')
  expect(text).not.toContain('/export/')
})

test('last ACL deletion warns about default allow-all while preserving typed confirmation', async () => {
  await render()
  const rule = { id: 'acl-1', resourceid: 'share-1', principal: '192.0.2.12/32' }
  wrapper.vm.storageService.nfsAcls = [rule]
  wrapper.vm.openDeleteModal('nfsAcl', rule)
  await flushPromises()
  expect(wrapper.vm.lastNfsAclDeletion).toBe(true)
  expect(wrapper.vm.forms.deleteConfirm.confirmation).toBe('')
  expect(wrapper.vm.forms.deleteConfirm.command).toBe('deleteStorageNfsAcl')
  wrapper.vm.storageService.nfsAcls.push({ id: 'acl-2', resourceid: 'share-1', principal: '192.0.2.11/32' })
  await flushPromises()
  expect(wrapper.vm.lastNfsAclDeletion).toBe(false)
  expect(wrapper.vm.forms.deleteConfirm.confirmation).toBe('')
  expect(ko['message.storage.nfs.acl.last.delete']).toContain('전체 허용')
  expect(en['message.storage.nfs.acl.last.delete']).toContain('allow-all')
})
