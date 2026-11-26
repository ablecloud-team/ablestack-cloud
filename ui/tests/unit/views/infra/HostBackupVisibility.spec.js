// Licensed to the Apache Software Foundation (ASF) under one or more
// contributor license agreements. See the NOTICE file distributed with this
// work for additional information regarding copyright ownership. The ASF
// licenses this file to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance with the License.
// You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0 .
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
// WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
// License for the specific language governing permissions and limitations.
import { mount, flushPromises } from '@vue/test-utils'
import { reactive } from 'vue'
import ResourceView from '@/components/view/ResourceView'
import hosts from '@/config/section/infra/hosts'
import store from '@/store'
import { getAPI } from '@/api'

jest.mock('@/api', () => ({ getAPI: jest.fn(), postAPI: jest.fn() }))
jest.mock('@/store', () => ({ getters: {}, state: { user: { token: 'test-session' }, app: { device: 'desktop' } } }))

const enabled = 'backup.framework.enabled'
const provider = 'backup.framework.provider.plugin'
const response = values => ({ listconfigurationsresponse: { configuration: Object.entries(values).map(([name, value]) => ({ name, value })), count: Object.keys(values).length } })
const config = providers => response({ [enabled]: 'true', [provider]: providers })
const host = (id = 'host-1', zoneid = 'zone-1') => ({ id, zoneid, hypervisor: 'KVM', type: 'Routing' })
const content = { props: ['resource'], template: '<div data-backup-pane>{{ resource.backupintegrationproviders.join(",") }}</div>' }
let wrapper
beforeEach(() => {
  jest.clearAllMocks()
  store.getters = reactive({ userInfo: { id: 'admin', roletype: 'Admin' }, apis: { listConfigurations: {}, getNetBackupHostConfiguration: {} }, project: {}, features: {} })
})
afterEach(() => wrapper?.unmount())

function open (record = host(), tab) {
  const tabs = [
    { name: 'details', component: { template: '<div>Host details</div>' } },
    { ...hosts.tabs.find(tab => tab.name === 'backup.integration'), component: content }
  ]
  const route = reactive({ meta: { name: 'host', tabs }, path: '/host/' + record.id, params: { id: record.id }, query: tab ? { tab } : {} })
  const router = { replace: jest.fn() }
  wrapper = mount(ResourceView, {
    props: { resource: record, tabs },
    global: {
      stubs: { ResourceLayout: { template: '<main><slot name="right" /></main>' }, InfoCard: true },
      mocks: { $store: store, $route: route, $router: router, $t: key => key }
    }
  })
  return { wrapper, route, router }
}

test('the parent Host tab waits for configuration and preserves a valid direct link while loading', async () => {
  const resolvers = []
  getAPI.mockImplementation(() => new Promise(resolve => resolvers.push(resolve)))
  const { wrapper: w, router } = open(host(), 'backup.integration')
  expect(w.findAll('[role="tab"]').map(tab => tab.text())).toEqual(['label.details'])
  expect(router.replace).not.toHaveBeenCalled()
  resolvers.forEach(resolve => resolve(config('ablestack-netbackup')))
  await flushPromises()
  expect(w.findAll('[role="tab"]').map(tab => tab.text())).toEqual(['label.details', 'label.backup.integration'])
  expect(w.vm.activeTab).toBe('backup.integration')
  expect(w.find('[data-backup-pane]').text()).toBe('ablestack-netbackup')
  expect(getAPI.mock.calls.every(([command]) => command === 'listConfigurations')).toBe(true)
})

test('disabled configuration hides a direct-linked integration tab and falls back to details', async () => {
  getAPI.mockResolvedValue(response({ [enabled]: 'false', [provider]: 'ablestack-netbackup' }))
  const { wrapper: w, router } = open(host(), 'backup.integration')
  await flushPromises()
  expect(w.findAll('[role="tab"]').map(tab => tab.text())).toEqual(['label.details'])
  expect(w.vm.activeTab).toBe('details')
  expect(w.find('[data-backup-pane]').exists()).toBe(false)
  expect(router.replace).toHaveBeenCalledWith({ path: '/host/host-1', query: { tab: 'details' } })
})

test('a response from the previous Host Zone cannot enable integrations for the next Host', async () => {
  const resolvers = []
  getAPI.mockImplementation(() => new Promise(resolve => resolvers.push(resolve)))
  const { wrapper: w, route } = open()
  getAPI.mockResolvedValue(config('ablestack-veeam'))
  route.path = '/host/host-2'
  route.params.id = 'host-2'
  await w.setProps({ resource: host('host-2', 'zone-2') })
  await flushPromises()
  expect(w.vm.hostBackupProviders).toEqual(['ablestack-veeam'])
  resolvers.forEach(resolve => resolve(config('ablestack-netbackup')))
  await flushPromises()
  expect(w.vm.hostBackupProviders).toEqual(['ablestack-veeam'])
})

test('refresh reevaluates settings without clearing an already visible tab while the query is pending', async () => {
  getAPI.mockResolvedValue(config('ablestack-netbackup'))
  const { wrapper: w } = open()
  await flushPromises()
  await w.setProps({ readPending: true })
  const resolvers = []
  getAPI.mockImplementation(() => new Promise(resolve => resolvers.push(resolve)))
  await w.setProps({ readPending: false })
  expect(w.vm.hostBackupProviders).toEqual(['ablestack-netbackup'])
  resolvers.forEach(resolve => resolve(config('ablestack-nas')))
  await flushPromises()
  expect(w.findAll('[role="tab"]').map(tab => tab.text())).toEqual(['label.details'])
})

test('configuration failure hides the integration instead of mounting an unverified form', async () => {
  getAPI.mockRejectedValue(new Error('offline'))
  const { wrapper: w } = open()
  await flushPromises()
  expect(w.findAll('[role="tab"]').map(tab => tab.text())).toEqual(['label.details'])
  expect(w.find('[data-backup-pane]').exists()).toBe(false)
})

test('non-admins and non-KVM Hosts do not query Global configuration', async () => {
  store.getters.userInfo.roletype = 'DomainAdmin'
  open(); await flushPromises()
  expect(getAPI).not.toHaveBeenCalled()
  wrapper.unmount()
  store.getters.userInfo.roletype = 'Admin'
  open({ ...host(), hypervisor: 'VMware' }); await flushPromises()
  expect(getAPI).not.toHaveBeenCalled()
})
