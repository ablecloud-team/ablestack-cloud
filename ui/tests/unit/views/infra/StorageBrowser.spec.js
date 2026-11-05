// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements. See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership. The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License. You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied. See the License for the
// specific language governing permissions and limitations
// under the License.

import { mount, flushPromises } from '@vue/test-utils'
import { h, reactive } from 'vue'
import StorageBrowser from '@/views/infra/StorageBrowser.vue'
import { getAPI } from '@/api'

jest.mock('@/api', () => ({ getAPI: jest.fn(), postAPI: jest.fn() }))
jest.mock('@/components/view/InfoCard', () => ({ render: () => null }))
jest.mock('@/components/widgets/TooltipButton', () => ({ render: () => null }))
jest.mock('@/views/storage/MigrateImageStoreResource', () => ({ render: () => null }))
jest.mock('@/views/storage/CreateRbdImage', () => ({ render: () => null }))
jest.mock('@/views/storage/ImportStorageVolume', () => ({ render: () => null }))

const Harness = {
  ...StorageBrowser,
  render () { return h('div', this.dataSource.map(row => h('span', { key: row.name }, row.name))) }
}

describe.each(['ImageStore', 'PrimaryStorage'])('%s browser listing', resourceType => {
  let wrapper, route, requests, store
  const response = (rows, count = rows.length) => ({
    [resourceType === 'ImageStore' ? 'listimagestoreobjectsresponse' : 'liststoragepoolobjectsresponse']: {
      ...(rows.length ? { datastoreobject: rows } : {}), count
    }
  })
  const open = async () => {
    requests = []
    getAPI.mockReset()
    getAPI.mockImplementation(() => new Promise((resolve, reject) => requests.push({ resolve, reject })))
    route = reactive({ path: '/storage/store', fullPath: '/storage/store?tab=browser', query: { tab: 'browser' } })
    store = reactive({ getters: { userInfo: { id: 'admin' }, project: { id: 'project' } }, state: { user: { token: 'session' } } })
    const router = {
      replace: jest.fn(({ query }) => Promise.resolve().then(() => {
        route.query = query
        route.fullPath = route.path + '?' + new URLSearchParams(query)
      }))
    }
    wrapper = mount(Harness, {
      props: { resource: { id: 'store', type: 'NetworkFilesystem' }, resourceType },
      global: { mocks: { $route: route, $router: router, $store: store, $t: key => key, $notifyError: jest.fn() } }
    })
    await flushPromises()
  }
  afterEach(() => { if (wrapper) wrapper.unmount() })

  it('displays the first response after asynchronously normalizing its browser URL', async () => {
    await open()
    expect(route.query.browserPath).toBe('')
    requests[0].resolve(response([{ name: 'template', isdirectory: true }]))
    await flushPromises()
    expect(wrapper.find('span').text()).toBe('template')
    expect(wrapper.vm.total).toBe(1)
    expect(wrapper.vm.loading).toBe(false)
    expect(getAPI).toHaveBeenCalledTimes(1)
  })

  it('shows folder and page responses immediately, while ignoring a late old folder', async () => {
    await open()
    wrapper.vm.openDir('template/')
    await flushPromises()
    requests[1].resolve(response([{ name: 'new-folder' }], 50))
    await flushPromises()
    requests[0].resolve(response([{ name: 'old-root' }]))
    await flushPromises()
    expect(wrapper.vm.dataSource).toEqual([{ name: 'new-folder' }])
    expect(wrapper.vm.loading).toBe(false)
    wrapper.vm.changePage(2, 20)
    await flushPromises()
    expect(route.query.browserPageSize).toBe(20)
    expect(getAPI.mock.calls[2][1]).toMatchObject({ path: 'template/', page: 2, pagesize: 20 })
    requests[2].resolve(response([{ name: 'page-two' }], 50))
    await flushPromises()
    expect(wrapper.vm.dataSource).toEqual([{ name: 'page-two' }])
    expect(wrapper.vm.loading).toBe(false)
  })

  it('keeps existing rows after a failed refresh and accepts a successful empty listing', async () => {
    await open()
    requests[0].resolve(response([{ name: 'keep' }]))
    await flushPromises()
    wrapper.vm.fetchData()
    wrapper.vm.fetchData()
    expect(requests).toHaveLength(2)
    requests[1].reject(new Error('SSVM unavailable'))
    await flushPromises()
    expect(wrapper.vm.dataSource).toEqual([{ name: 'keep' }])
    expect(wrapper.vm.listRefreshFailed).toBe(true)
    expect(wrapper.vm.loading).toBe(false)
    wrapper.vm.fetchData()
    requests[2].resolve(response([]))
    await flushPromises()
    expect(wrapper.vm.dataSource).toEqual([])
    expect(wrapper.vm.total).toBe(0)
    expect(wrapper.vm.listRefreshFailed).toBe(false)
  })

  it.each(['user', 'project', 'session', 'route'])('ignores a response after changing %s context', async context => {
    await open()
    if (context === 'user') store.getters.userInfo.id = 'another-admin'
    if (context === 'project') store.getters.project.id = 'another-project'
    if (context === 'session') store.state.user.token = 'another-session'
    if (context === 'route') route.path = '/storage/another-store'
    requests[0].resolve(response([{ name: 'old-context' }]))
    await flushPromises()
    expect(wrapper.vm.dataSource).toEqual([])
  })
})
