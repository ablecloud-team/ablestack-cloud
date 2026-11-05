// Licensed under the Apache License, Version 2.0. See NOTICE.
import { shallowMount, flushPromises } from '@vue/test-utils'
import History from '@/views/storage/SharedFSDeletionHistory'
import Deletion from '@/views/storage/SharedFSDeletion'
import listWire from '../../../fixtures/sharedfs-1461-operation-list-wire.json'
import opWire from '../../../fixtures/sharedfs-1461-operation-wire.json'
import { getAPI, postAPI } from '@/api'
jest.mock('@/api', () => ({ getAPI: jest.fn(), postAPI: jest.fn() }))
describe('actual removal history response and independent deleted-service lookup', () => {
  let wrapper, router
  beforeEach(async () => {
    getAPI.mockReset(); postAPI.mockReset(); router = { push: jest.fn() }
    getAPI.mockImplementation(async command => JSON.parse(JSON.stringify(command === 'listSharedFileSystemDeletionOperations' ? listWire : opWire)))
    wrapper = shallowMount(History, { global: { mocks: { $t: key => key, $router: router, $store: { getters: {} } }, stubs: { SharedFSDeletion: false, MoldDialogActions: { template: '<footer><slot /></footer>' } } } })
    await flushPromises()
  })
  afterEach(() => wrapper.unmount())
  test('every list request includes page and pagesize through the real history component', async () => {
    expect(getAPI).toHaveBeenCalledWith('listSharedFileSystemDeletionOperations', expect.objectContaining({ page: 1, pagesize: 20 }), { preserveOnFailure: true })
    wrapper.vm.changePage(2); await flushPromises()
    expect(getAPI).toHaveBeenLastCalledWith('listSharedFileSystemDeletionOperations', expect.objectContaining({ page: 2, pagesize: 20 }), { preserveOnFailure: true })
    expect(wrapper.vm.rows[0].operationid).toContain('1461')
  })
  test('opening a durable record queries its operation UUID without looking up an absent SharedFS', async () => {
    wrapper.vm.selected = wrapper.vm.rows[0]; await flushPromises()
    const child = wrapper.findComponent(Deletion); expect(child.exists()).toBe(true); expect(child.vm.historyMode).toBe(true)
    expect(getAPI.mock.calls.some(([api]) => api === 'getSharedFileSystemDeletionImpact')).toBe(false)
    expect(getAPI).toHaveBeenCalledWith('getSharedFileSystemDeletionOperation', { operationid: wrapper.vm.selected.operationid }, { preserveOnFailure: true })
    expect(child.vm.operation.originalfailure.code).toBe('DATA_DETACH_UNCONFIRMED')
    child.vm.close(); await flushPromises(); expect(wrapper.vm.selected).toBe(null); expect(router.push).not.toHaveBeenCalled(); expect(postAPI).not.toHaveBeenCalled()
  })
})
