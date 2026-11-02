// Licensed under the Apache License, Version 2.0. See NOTICE.
import { shallowMount, flushPromises } from '@vue/test-utils'
import Autogen from '@/views/AutogenView'
import store from '@/store'
import { getAPI, callAPI } from '@/api'
import wire from '../../../fixtures/sharedfs-1458-capacity-wire.json'
jest.mock('@/api', () => ({ getAPI: jest.fn(), postAPI: jest.fn(), callAPI: jest.fn() }))
jest.mock('@/store', () => ({ getters: {}, state: { app: { device: 'desktop' }, user: { token: 'session' } } }))
let wrapper
const box = { template: '<div><slot /></div>' }
async function render (detail, name) {
  const id = '11111111-1111-4111-8111-111111111111'
  store.getters = { userInfo: { id: 'admin', roletype: 'Admin' }, apis: { listSharedFileSystems: { params: [], response: [] } }, project: {}, customColumns: {}, defaultListViewPageSize: 20, metrics: false, listAllProjects: false }
  const route = { name, path: '/' + name + (detail ? '/' + id : ''), fullPath: '/' + name, params: detail ? { id } : {}, query: {}, meta: { name, permission: [name === 'sharedfs' ? 'listSharedFileSystems' : 'listVirtualMachines'], columns: () => ['name', 'capacity'] } }
  const View = { ...Autogen, created () {}, mounted () {}, beforeUnmount () {}, methods: { ...Autogen.methods, fetchData: jest.fn() } }
  wrapper = shallowMount(View, {
    global: {
      mocks: { $store: store, $route: route, $router: { push: jest.fn(), replace: jest.fn() }, $t: x => x, $config: { theme: {}, docBase: '' }, $showIcon: () => false, $i18n: { locale: 'ko_KR' }, $notifyError: jest.fn(), $message: { info: jest.fn() } },
      stubs: { AAffix: { props: ['offsetTop'], ...box }, ACard: box, ARow: box, ACol: box, Breadcrumb: box, ListView: true, ResourceView: true, SearchView: true, ActionButton: true }
    }
  })
  await wrapper.setData({ dataView: detail, actions: [], resource: {}, apiName: name === 'sharedfs' ? 'listSharedFileSystems' : 'listVirtualMachines', columnKeys: ['name', 'capacity'], columns: [{ key: 'name', dataIndex: 'name' }], loading: false })
}
beforeEach(() => { jest.clearAllMocks(); getAPI.mockResolvedValue(wire); callAPI.mockImplementation(getAPI) })
afterEach(() => { if (wrapper) wrapper.unmount() })
test.each([true, false])('actual AutogenView sends bounded SharedFS capacity details only for detail=%s', async detail => {
  await render(detail, 'sharedfs')
  await Autogen.methods.fetchData.call(wrapper.vm, { irefresh: true })
  await flushPromises()
  const params = callAPI.mock.calls.find(x => x[0] === 'listSharedFileSystems')[1]
  if (detail) expect(params).toEqual(expect.objectContaining({ id: expect.any(String), includecapacitydetails: true }))
  else expect(params).not.toHaveProperty('includecapacitydetails')
})
test('VM requests never receive the SharedFS-only parameter', async () => {
  await render(true, 'vm')
  getAPI.mockResolvedValue({ listvirtualmachinesresponse: { virtualmachine: [] } })
  await Autogen.methods.fetchData.call(wrapper.vm, { irefresh: true })
  expect(callAPI.mock.calls.find(x => x[0] === 'listVirtualMachines')[1]).not.toHaveProperty('includecapacitydetails')
})
