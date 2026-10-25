// Licensed under the Apache License, Version 2.0. See the NOTICE file.
import { shallowMount, flushPromises } from '@vue/test-utils'
import Autogen from '@/views/AutogenView'
import store from '@/store'
import { getAPI, callAPI } from '@/api'
import tools from '@/config/section/tools'
jest.mock('@/api', () => ({ getAPI: jest.fn(), postAPI: jest.fn(), callAPI: jest.fn() }))
jest.mock('@/store', () => ({ getters: {}, state: { app: { device: 'desktop' }, user: { token: 'session' } } }))
const row = { id: '11111111-1111-4111-8111-111111111111', name: 'signed-runtime', version: 'signed-runtime', state: 'DRAFT', validationstatus: 'UNKNOWN' }
const container = { template: '<div><slot /></div>' }
const button = { props: ['loading', 'type'], emits: ['click'], template: '<button :data-type="type" :disabled="loading" @click="$emit(\'click\')"><slot name="icon" /><slot /></button>' }
let wrapper, fetch, route
function render (detail, device, name = 'runtimebundle') {
  const section = tools.children.find(x => x.name === 'runtimebundle')
  store.state.app.device = device
  store.getters = {
    userInfo: { id: 'admin', roletype: 'Admin' },
    apis: { listStorageServiceRuntimeBundles: { params: [], response: [] }, registerStorageServiceRuntimeBundle: {}, validateStorageServiceRuntimeBundle: {}, transitionStorageServiceRuntimeBundle: {} },
    project: {},
    customColumns: {},
    defaultListViewPageSize: 20,
    metrics: false,
    listAllProjects: false
  }
  route = { name, path: '/' + name + (detail ? '/' + row.id : ''), fullPath: '/' + name, params: detail ? { id: row.id } : {}, query: {}, meta: { ...section, name } }
  // Keep the real compiled AutogenView template and methods; omit only unrelated page startup/listeners.
  fetch = jest.fn()
  const View = { ...Autogen, created () {}, mounted () {}, beforeUnmount () {}, methods: { ...Autogen.methods, fetchData: fetch } }
  wrapper = shallowMount(View, {
    global: {
      mocks: { $store: store, $route: route, $router: { push: jest.fn(), replace: jest.fn() }, $t: key => key, $config: { theme: {}, docBase: '' }, $showIcon: () => false, $i18n: { locale: 'ko_KR' }, $notifyError: jest.fn(), $message: { info: jest.fn() } },
      stubs: {
        AAffix: container,
        ACard: container,
        ARow: container,
        ACol: container,
        AButton: button,
        Breadcrumb: { template: '<nav><slot name="end" /></nav>' },
        ActionButton: { template: '<button data-primary="true">primary action</button>' },
        ADropdown: { template: '<div><slot /></div>' },
        ReloadOutlined: { template: '<span data-icon="reload" />' },
        ResourceView: true,
        ListView: true,
        SearchView: { template: '<div data-testid="search" />' }
      }
    }
  })
  return wrapper.setData({ dataView: detail, actions: detail ? [{ api: 'validateStorageServiceRuntimeBundle', dataView: true }] : [{ api: 'registerStorageServiceRuntimeBundle', listView: true }], resource: detail ? { ...row } : {}, apiName: 'listStorageServiceRuntimeBundles', columnKeys: ['name'], columns: [{ key: 'name', dataIndex: 'name' }], loading: false })
}
beforeEach(() => { jest.clearAllMocks(); getAPI.mockResolvedValue({ liststorageserviceruntimebundlesresponse: { count: 1, storageserviceruntimebundle: [{ ...row }] } }); callAPI.mockImplementation(getAPI) })
afterEach(() => wrapper?.unmount())
test.each([[false, 'desktop'], [true, 'desktop'], [false, 'mobile'], [true, 'mobile']])('actual AutogenView %s/%s renders one update after the primary action and refreshes the current scope', async (detail, device) => {
  await render(detail, device)
  await flushPromises()
  const refresh = wrapper.findAll('[data-testid="runtime-catalog-refresh"]')
  expect(refresh).toHaveLength(1)
  expect(refresh[0].text()).toContain('label.refresh')
  expect(refresh[0].find('[data-icon="reload"]').exists()).toBe(true)
  const toolbar = wrapper.find('.autogen-toolbar-actions')
  expect(toolbar.html().indexOf('data-testid="runtime-catalog-refresh"')).toBeGreaterThan(toolbar.html().indexOf(detail ? 'autogen-action-dropdown__trigger' : 'data-primary'))
  if (!detail) expect(toolbar.html().indexOf('data-testid="search"')).toBeGreaterThan(toolbar.html().indexOf('runtime-catalog-refresh'))
  let clickParams
  fetch.mockImplementation(params => { clickParams = { ...params }; return Autogen.methods.fetchData.call(wrapper.vm, params) })
  await refresh[0].trigger('click')
  await flushPromises()
  expect(fetch).toHaveBeenCalledTimes(1)
  expect(clickParams).toEqual({ irefresh: true })
  expect(callAPI).toHaveBeenCalledWith('listStorageServiceRuntimeBundles', expect.objectContaining(detail ? { id: row.id } : { purpose: 'catalog' }))
  if (detail) expect(wrapper.vm.resource.id).toBe(row.id)
  else expect(wrapper.vm.items[0].id).toBe(row.id)
})
test('VM keeps its existing breadcrumb update and does not render the catalogue-only button', async () => {
  await render(false, 'desktop', 'vm')
  expect(wrapper.find('[data-testid="runtime-catalog-refresh"]').exists()).toBe(false)
  const existing = wrapper.find('nav button')
  expect(existing.exists()).toBe(true)
  await existing.trigger('click')
  expect(fetch).toHaveBeenCalledWith({ irefresh: true })
})
