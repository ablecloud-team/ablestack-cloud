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
import { discoverOptional, hasDiscoveryApi } from '@/utils/optionalDiscovery'
import user from '@/store/modules/user'
import store from '@/store'
import { vueProps } from '@/vue-app'
import UserMenu from '@/components/header/UserMenu.vue'
import GlobalLayout from '@/components/page/GlobalLayout.vue'

jest.mock('@/api', () => { const request = jest.fn(); return { getAPI: request, api: request, logout: jest.fn(() => Promise.resolve({})) } })
jest.mock('@/vue-app', () => ({ vueProps: { $localStorage: { get: jest.fn((key, fallback) => fallback), set: jest.fn(), remove: jest.fn() } } }))
jest.mock('@/store', () => ({ getters: { loginFlag: true, addRouters: [] }, dispatch: jest.fn(() => Promise.resolve()) }))
jest.mock('@/router', () => ({ addRoute: jest.fn() }))
jest.mock('@/locales', () => ({ i18n: { global: { t: x => x } } }))
jest.mock('@/utils/request', () => ({ axios: {} }))
jest.mock('ant-design-vue/es/message', () => ({ loading: () => jest.fn(), success: jest.fn() }))
jest.mock('ant-design-vue/es/notification', () => ({ destroy: jest.fn() }))

const flush = async () => { for (let i = 0; i < 20; i++) await Promise.resolve() }
const context = () => {
  const state = { ...user.state, apis: {}, features: {}, discoveryGeneration: 0 }
  const commit = (name, payload) => user.mutations[name](state, payload)
  return { state, commit }
}
const menu = (apis = {}) => {
  const vm = { ...UserMenu.data(), $store: { getters: { apis, features: {} } } }
  for (const [name, method] of Object.entries(UserMenu.methods)) vm[name] = method.bind(vm)
  return vm
}
const baseApis = ['listApis', 'listUsers', 'listZones', 'listCapabilities']
const optionalApis = ['listNetworkServiceProviders', 'cloudianIsEnabled']
const reply = (apis) => name => {
  if (name === 'listApis') return Promise.resolve({ listapisresponse: { api: apis.map(name => ({ name })) } })
  if (name === 'listUsers') return Promise.resolve({ listusersresponse: { user: [{ id: 'u1', firstname: 'Test', lastname: 'User' }] } })
  if (name === 'listZones') return Promise.resolve({ listzonesresponse: { zone: [] } })
  if (name === 'listCapabilities') return Promise.resolve({ listcapabilitiesresponse: { capability: { defaultuipagesize: 50, securitygroupsenabled: true } } })
  return Promise.reject(new Error('432 forbidden'))
}

beforeEach(() => {
  jest.clearAllMocks()
  vueProps.$localStorage.get.mockImplementation((key, fallback) => fallback)
  store.getters.loginFlag = true
})
afterEach(() => { jest.useRealTimers() })

test.each([
  ['ordinary user', baseApis],
  ['domain administrator', [...baseApis, 'listLdapConfigurations']],
  ['root administrator', [...baseApis, ...optionalApis]]
])('%s discovers only granted optional APIs and login survives 432', async (_, apis) => {
  getAPI.mockImplementation(reply(apis))
  const ctx = context()
  const result = await user.actions.GetInfo(ctx)
  expect(Object.keys(result)).toEqual(apis)
  for (const name of optionalApis) {
    expect(getAPI.mock.calls.some(call => call[0] === name)).toBe(apis.includes(name))
  }
  expect(ctx.state.defaultListViewPageSize).toBe(50)
  expect(ctx.state.showSecurityGroups).toBe(true)
})

test('unresolved and inherited permissions never authorize a request', async () => {
  expect(hasDiscoveryApi(Object.create({ listConfigurations: {} }), 'listConfigurations')).toBe(false)
  await expect(discoverOptional(undefined, 'listConfigurations')).resolves.toBeUndefined()
  expect(getAPI).not.toHaveBeenCalled()
})

test('allowed optional transport failures settle with a fallback', async () => {
  getAPI.mockRejectedValue(new Error('network failure'))
  await expect(discoverOptional({ listConfigurations: {} }, 'listConfigurations')).resolves.toBeUndefined()
})

test('late discovery after reset cannot restore APIs, features or routes', async () => {
  let finish
  const apis = [...baseApis, ...optionalApis]
  getAPI.mockImplementation(name => name === 'listCapabilities' ? new Promise(resolve => { finish = resolve }) : reply(apis)(name))
  const ctx = context()
  const pending = user.actions.GetInfo(ctx)
  await flush()
  ctx.commit('RESET_DISCOVERY')
  finish({ listcapabilitiesresponse: { capability: { defaultuipagesize: 99 } } })
  await expect(pending).resolves.toEqual({})
  expect(ctx.state.apis).toEqual({})
  expect(ctx.state.features).toEqual({})
  expect(store.dispatch).not.toHaveBeenCalledWith('GenerateRoutes', expect.anything())
})

test.each([{}, { listconfigurationsresponse: { configuration: [] } }])('empty configuration settles and preserves defaults', async json => {
  getAPI.mockResolvedValue(json)
  const vm = menu({ listConfigurations: {} })
  await vm.fetchConfigurationSwitch()
  expect(vm.faviconStateInterval).toBe(60000)
  expect(vm.hostStateTimer).toBeNull()
})

test('ordinary header issues no administrator discovery calls', async () => {
  const vm = menu()
  await vm.fetchConfigurationSwitch()
  await vm.wallPortalLink()
  expect(getAPI).not.toHaveBeenCalled()
})

test('permission transition removes the owned host timer and ignores late settings', async () => {
  jest.useFakeTimers()
  const vm = menu({ listConfigurations: {}, listHostsMetrics: {} })
  getAPI.mockResolvedValue({})
  await vm.fetchConfigurationSwitch()
  expect(jest.getTimerCount()).toBe(1)
  let finish
  getAPI.mockImplementation(() => new Promise(resolve => { finish = resolve }))
  const pending = vm.fetchFaviconStateInterval()
  vm.$store.getters.apis = {}
  await vm.fetchConfigurationSwitch()
  finish({ listconfigurationsresponse: { configuration: [{ value: 1 }] } })
  await pending
  expect(vm.faviconStateInterval).toBe(60000)
  expect(jest.getTimerCount()).toBe(0)
})

test('feature refresh settles when optional configuration fails', async () => {
  const ctx = context()
  ctx.state.apis = { listCapabilities: {}, listConfigurations: {} }
  getAPI.mockImplementation(reply(baseApis))
  await user.actions.RefreshFeatures(ctx)
  expect(ctx.state.customHypervisorName).toBe('Custom')
})

test('a pending optional integration does not delay login completion', async () => {
  const apis = [...baseApis, 'cloudianIsEnabled']
  getAPI.mockImplementation(name => name === 'cloudianIsEnabled' ? new Promise(() => {}) : reply(apis)(name))
  await expect(user.actions.GetInfo(context())).resolves.toHaveProperty('listUsers')
})

test('granted integration results still populate optional features', async () => {
  const apis = [...baseApis, ...optionalApis]
  const replies = {
    listNetworkServiceProviders: { listnetworkserviceprovidersresponse: { count: 1 } },
    listLdapConfigurations: { ldapconfigurationresponse: { count: 1 } },
    cloudianIsEnabled: { cloudianisenabledresponse: { cloudianisenabled: { enabled: true } } },
    listHSMProfiles: { listhsmprofilesresponse: { count: 1 } }
  }
  getAPI.mockImplementation(name => replies[name] ? Promise.resolve(replies[name]) : reply(apis)(name))
  const ctx = context()
  await user.actions.GetInfo(ctx)
  await flush()
  expect(ctx.state.isLdapEnabled).toBe(false)
  expect(ctx.state.features.hashsmprofiles).toBeUndefined()
  expect(ctx.state.cloudian.enabled).toBe(true)
})

test('allowed favicon values are applied and only one timer is owned', async () => {
  jest.useFakeTimers()
  getAPI.mockImplementation((name, params) => Promise.resolve({ listconfigurationsresponse: { configuration: [{ value: params.name === 'favicon.state.interval' ? '30' : '0.8' }] } }))
  const vm = menu({ listConfigurations: {}, listHostsMetrics: {} })
  await vm.fetchConfigurationSwitch()
  await vm.fetchConfigurationSwitch()
  expect(vm.faviconStateInterval).toBe(30000)
  expect(vm.faviconStateYellowCapacity).toBe(0.8)
  expect(jest.getTimerCount()).toBe(1)
  UserMenu.beforeUnmount.call(vm)
  expect(jest.getTimerCount()).toBe(0)
})

test('Diplo does not reactivate login LDAP or HSM discovery even when granted', async () => {
  const apis = [...baseApis, 'listLdapConfigurations', 'listHSMProfiles']
  getAPI.mockImplementation(reply(apis))
  await user.actions.GetInfo(context())
  expect(getAPI.mock.calls.some(([name]) => ['listLdapConfigurations', 'listHSMProfiles'].includes(name))).toBe(false)
})

test('explicit LDAP refresh settles and ignores a late previous-session response', async () => {
  const ctx = context()
  ctx.state.apis = { listLdapConfigurations: {} }
  let finish
  getAPI.mockImplementation(() => new Promise(resolve => { finish = resolve }))
  const pending = user.actions.UpdateConfiguration(ctx)
  ctx.commit('RESET_DISCOVERY')
  finish({ ldapconfigurationresponse: { count: 1 } })
  await pending
  expect(ctx.state.isLdapEnabled).toBe(false)
})

test('shutdown notice discovery ignores an old session and permission denial', async () => {
  const state = { discoveryGeneration: 0 }
  const dispatch = jest.fn()
  const vm = { $store: { state: { user: state }, getters: { apis: {}, features: {} }, dispatch } }
  await GlobalLayout.methods.checkShutdown.call(vm)
  expect(getAPI).not.toHaveBeenCalled()
  vm.$store.getters.apis = { readyForShutdown: {} }
  let finish
  getAPI.mockImplementation(() => new Promise(resolve => { finish = resolve }))
  const pending = GlobalLayout.methods.checkShutdown.call(vm)
  state.discoveryGeneration++
  finish({ readyforshutdownresponse: { readyforshutdown: { shutdowntriggered: true } } })
  await pending
  expect(dispatch).not.toHaveBeenCalled()
})
