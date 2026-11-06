// Licensed under the Apache License, Version 2.0. See the NOTICE file.
import { shallowMount, flushPromises } from '@vue/test-utils'
import Autogen from '@/views/AutogenView'
import ActionButton from '@/components/view/ActionButton'
import History from '@/views/storage/SharedFSDeletionHistory'
import storage from '@/config/section/storage'
import store from '@/store'
import { getAPI, postAPI } from '@/api'
import listWire from '../../fixtures/sharedfs-1461-operation-list-wire.json'

jest.mock('@/api', () => ({ getAPI: jest.fn(), postAPI: jest.fn(), callAPI: jest.fn() }))
jest.mock('@/store', () => ({ getters: {}, state: { app: { device: 'desktop' }, user: { token: 'session' } } }))

const api = 'listSharedFileSystemDeletionOperations'
const label = 'label.sharedfs.delete.history'
const section = storage.children.find(x => x.name === 'sharedfs')
const container = { template: '<div><slot /><slot name="footer" /></div>' }
const button = { props: ['loading', 'type'], emits: ['click'], template: '<button :disabled="loading" @click="$emit(\'click\')"><slot name="icon" /><slot /></button>' }
let wrapper

async function render (device, selected = [], allow = true, removeListFlag = false) {
  store.state.app.device = device
  store.getters = {
    userInfo: { id: 'admin', roletype: 'Admin' },
    project: {},
    customColumns: {},
    defaultListViewPageSize: 20,
    metrics: false,
    listAllProjects: false,
    apis: { listSharedFileSystems: { params: [], response: [] }, createSharedFileSystem: { params: [] }, ...(allow ? { [api]: { params: [], response: [] } } : {}) }
  }
  const route = { name: 'sharedfs', path: '/sharedfs', fullPath: '/sharedfs', params: {}, query: {}, meta: { ...section, name: 'sharedfs' } }
  const View = { ...Autogen, created () {}, mounted () {}, beforeUnmount () {}, methods: { ...Autogen.methods, fetchData: jest.fn() } }
  wrapper = shallowMount(View, {
    global: {
      mocks: { $store: store, $route: route, $router: { push: jest.fn(), replace: jest.fn() }, $t: key => key, $config: { theme: {}, docBase: '' }, $i18n: { locale: 'ko_KR' }, $showIcon: () => false, $applyDocHelpMappings: x => x, $notifyError: jest.fn(), $message: { info: jest.fn(), error: jest.fn() } },
      stubs: {
        AAffix: container,
        ACard: container,
        ARow: container,
        ACol: container,
        ATooltip: container,
        AButton: button,
        Breadcrumb: { template: '<nav><slot name="end" /></nav>' },
        KeepAlive: container,
        AsyncComponentWrapper: false,
        ActionButton: false,
        SharedFSDeletionHistory: false,
        MoldDialog: container,
        AModal: container,
        MoldDialogActions: container,
        ADropdown: container,
        ResourceView: true,
        ListView: true,
        SearchView: { template: '<div />' }
      }
    }
  })
  const actions = section.actions.map(x => ({ ...x }))
  if (removeListFlag) delete actions.find(x => x.api === api).listView
  await wrapper.setData({ dataView: false, actions, resource: {}, apiName: 'listSharedFileSystems', columnKeys: ['name'], columns: [{ key: 'name', dataIndex: 'name' }], loading: false, selectedRowKeys: selected, selectedItems: selected.map(id => ({ id, state: 'Ready', name: 'unrelated' })) })
  await flushPromises()
}

beforeEach(() => { jest.clearAllMocks(); getAPI.mockResolvedValue(JSON.parse(JSON.stringify(listWire))) })
afterEach(() => { if (wrapper) wrapper.unmount() })

test.each([['desktop', []], ['desktop', ['unrelated']], ['mobile', []], ['mobile', ['unrelated']]])('actual AutogenView + production actions + ActionButton opens deletion history at %s / %j', async (device, selected) => {
  await render(device, selected)
  expect(wrapper.findComponent(ActionButton).exists()).toBe(true)
  const historyButton = wrapper.find('button[aria-label="' + label + '"]')
  expect(historyButton.exists()).toBe(true)
  await historyButton.trigger('click')
  await flushPromises()
  expect(wrapper.vm.showAction).toBe(true)
  expect(wrapper.vm.currentAction.api).toBe(api)
  expect(wrapper.vm.currentAction.invokedAsGroupAction).toBe(false)
  const history = wrapper.findComponent(History)
  expect(history.exists()).toBe(true)
  expect(getAPI).toHaveBeenCalledWith(api, { page: 1, pagesize: 20, listall: true, projectid: undefined }, { preserveOnFailure: true })
  expect(history.vm.rows[0].operationid).toBe(listWire.listsharedfilesystemdeletionoperationsresponse.deletionoperation[0].result.operationid)
  expect(postAPI).not.toHaveBeenCalled()
})
test('API permissions still hide history without exposing or sending requests', async () => {
  await render('desktop', [], false)
  expect(wrapper.find('button[aria-label="' + label + '"]').exists()).toBe(false)
  expect(getAPI).not.toHaveBeenCalled()
})
test('regression reproduces the missing-listView production path rather than only rendering the child', async () => {
  await render('desktop', [], true, true)
  expect(wrapper.find('button[aria-label="' + label + '"]').exists()).toBe(false)
  expect(wrapper.findComponent(History).exists()).toBe(false)
  expect(getAPI).not.toHaveBeenCalled()
})
