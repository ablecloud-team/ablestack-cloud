// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

import { getAPI } from '@/api'
import { vueProps } from '@/vue-app'
import store from '@/store'
import user from '@/store/modules/user'
import { APIS } from '@/store/mutation-types'
import { toApiMetadata } from '@/utils/apiMetadata'
import ProjectMenu from '@/components/header/ProjectMenu.vue'

jest.mock('@/api', () => ({ getAPI: jest.fn() }))
jest.mock('@/vue-app', () => ({ vueProps: { $localStorage: { get: jest.fn((key, fallback) => fallback), set: jest.fn(), remove: jest.fn() } } }))
jest.mock('@/store', () => ({ getters: { addRouters: [] }, dispatch: jest.fn(() => Promise.resolve()) }))
jest.mock('@/router', () => ({ addRoute: jest.fn() }))
jest.mock('@/locales', () => ({ i18n: { global: { t: x => x } } }))
jest.mock('@/utils/request', () => ({ sourceToken: { isCancel: () => false } }))
jest.mock('@/utils/guiTheme', () => ({ applyCustomGuiTheme: jest.fn(() => Promise.resolve()) }))
jest.mock('ant-design-vue/es/message', () => ({ loading: () => jest.fn(), success: jest.fn() }))
jest.mock('ant-design-vue/es/notification', () => ({ destroy: jest.fn() }))
jest.mock('@/components/widgets/InfiniteScrollSelect', () => ({}))

const context = () => {
  const state = { ...user.state, apis: { oldScopeOnly: {} } }
  return { state, commit: (name, payload) => user.mutations[name](state, payload) }
}
const rows = () => Array.from({ length: 1110 }, (_, i) => ({
  name: 'api' + i,
  params: [{ name: 'id', required: true }],
  response: [{ name: 'value', description: 'x'.repeat(5000) }],
  isasync: i === 0,
  since: '4.22',
  description: 'API ' + i
}))
beforeEach(() => {
  jest.clearAllMocks()
  vueProps.$localStorage.set.mockReset()
  vueProps.$localStorage.remove.mockReset()
  store.dispatch.mockImplementation(() => Promise.resolve())
})

test('1110 API project discovery retains schemas in memory and only caches compact metadata', async () => {
  const list = rows()
  getAPI.mockResolvedValue({ listapisresponse: { api: list } })
  const ctx = context()
  await user.actions.ProjectView(ctx, 'p1')
  expect(getAPI).toHaveBeenCalledWith('listApis', { projectid: 'p1', ignoreproject: true })
  const cache = vueProps.$localStorage.set.mock.calls.find(([key]) => key === APIS)[1]
  expect(Object.keys(cache)).toHaveLength(1110)
  expect(JSON.stringify(cache).length).toBeLessThan(JSON.stringify(list).length / 10)
  expect(cache.api0).toEqual({ params: list[0].params, isasync: true, since: '4.22', description: 'API 0' })
  expect(ctx.state.apis.api0.response).toEqual(list[0].response)
  expect(ctx.state.apis.oldScopeOnly).toBeUndefined()
})

test('cache quota failure evicts old scope and uses only fresh server permissions', async () => {
  vueProps.$localStorage.set.mockImplementation(() => { throw new DOMException('full', 'QuotaExceededError') })
  getAPI.mockResolvedValue({ listapisresponse: { api: [{ name: 'listNetworks' }] } })
  const ctx = context()
  await user.actions.ProjectView(ctx, 'p1')
  expect(vueProps.$localStorage.remove).toHaveBeenCalledWith(APIS)
  expect(Object.keys(ctx.state.apis)).toEqual(['listNetworks'])
})

test('API rejection preserves previous scope and generates no new routes', async () => {
  const ctx = context(); const old = ctx.state.apis
  getAPI.mockRejectedValue(new Error('531 forbidden'))
  await expect(user.actions.ProjectView(ctx, 'p1')).rejects.toThrow('531')
  expect(ctx.state.apis).toBe(old)
  expect(store.dispatch).not.toHaveBeenCalled()
})

test('returning to default view explicitly suppresses the previous project injection', async () => {
  getAPI.mockResolvedValue({ listapisresponse: { api: [] } })
  await user.actions.ProjectView(context())
  expect(getAPI).toHaveBeenCalledWith('listApis', { projectid: undefined, ignoreproject: true })
})

test('project lookup cannot commit after logout invalidates discovery', async () => {
  let finish
  getAPI.mockImplementation(() => new Promise(resolve => { finish = resolve }))
  const ctx = context()
  const pending = user.actions.ProjectView(ctx, 'p1')
  ctx.commit('RESET_DISCOVERY')
  finish({ listapisresponse: { api: [{ name: 'adminOnly' }] } })
  await expect(pending).rejects.toThrow('scope changed')
  expect(ctx.state.apis).toEqual({})
  expect(store.dispatch).not.toHaveBeenCalled()
})

test('project switch waits for route generation', async () => {
  let finish
  store.dispatch.mockImplementation(() => new Promise(resolve => { finish = resolve }))
  getAPI.mockResolvedValue({ listapisresponse: { api: [] } })
  let done = false
  const pending = user.actions.ProjectView(context(), 'p1').then(() => { done = true })
  for (let i = 0; i < 20; i++) await Promise.resolve()
  expect(done).toBe(false)
  finish()
  await pending
  expect(done).toBe(true)
})

test('reload revalidates a legacy cached broad API set and preserves live response schemas', async () => {
  vueProps.$localStorage.get.mockImplementation((key, fallback) => key === APIS ? { adminOnly: { response: [{}] } } : fallback)
  getAPI.mockImplementation(name => {
    if (name === 'listApis') return Promise.resolve({ listapisresponse: { api: [{ name: 'listUsers', response: [{ name: 'id' }] }] } })
    if (name === 'listUsers') return Promise.resolve({ listusersresponse: { user: [{ firstname: 'Test', lastname: 'User' }] } })
    return Promise.resolve({})
  })
  const ctx = context()
  const result = await user.actions.GetInfo(ctx)
  expect(Object.keys(result)).toEqual(['listUsers'])
  expect(ctx.state.apis.adminOnly).toBeUndefined()
  expect(result.listUsers.response).toEqual([{ name: 'id' }])
  vueProps.$localStorage.get.mockImplementation((key, fallback) => fallback)
})

const menu = () => {
  const ctx = context()
  const getters = { project: { id: 'previous' }, apis: ctx.state.apis }
  return {
    loading: false,
    defaultOption: { id: 0 },
    selectedProjectId: 'target',
    $store: { getters, state: { user: ctx.state }, commit: jest.fn((name, value) => { ctx.commit(name, value); getters.apis = ctx.state.apis }), dispatch: jest.fn() },
    $router: { push: jest.fn(() => Promise.resolve()) },
    $route: { name: 'guestnetwork' },
    $message: { success: jest.fn() },
    $notifyError: jest.fn(),
    $t: x => x
  }
}
test('project persistence failure restores old permissions, selection and menu', async () => {
  const vm = menu(); const old = vm.$store.getters.apis
  vm.$store.dispatch.mockImplementation(name => {
    if (name === 'ProjectView') vm.$store.getters.apis = toApiMetadata([{ name: 'targetOnly' }])
    if (name === 'SetProject') return Promise.reject(new Error('storage failure'))
    return Promise.resolve()
  })
  await ProjectMenu.methods.changeProject.call(vm, { id: 'target' })
  expect(vm.selectedProjectId).toBe('previous')
  expect(vm.$store.getters.apis).toBe(old)
  expect(vm.$store.dispatch).toHaveBeenCalledWith('GenerateRoutes', { apis: old })
  expect(vm.$router.push).not.toHaveBeenCalled()
  expect(vm.loading).toBe(false)
})
test('navigation failure keeps the committed scope visible', async () => {
  const vm = menu()
  vm.$store.dispatch.mockImplementation((name, value) => {
    if (name === 'SetProject') vm.$store.getters.project = value
    return Promise.resolve()
  })
  vm.$router.push.mockRejectedValue(new Error('navigation failed'))
  await ProjectMenu.methods.changeProject.call(vm, { id: 'target' })
  expect(vm.selectedProjectId).toBe('target')
  expect(vm.$store.commit).not.toHaveBeenCalled()
  expect(vm.$message.success).not.toHaveBeenCalled()
})
