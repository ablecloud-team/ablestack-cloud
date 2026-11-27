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

import { shallowMount } from '@vue/test-utils'
import { h } from 'vue'
import Autogen from '@/views/AutogenView'
import { kubernetesIsoListQuery } from '@/utils/kubernetesIsoSearch'
import Add from '@/views/image/AddKubernetesSupportedVersion'
import image from '@/config/section/image'
import store from '@/store'
import { getAPI, callAPI, postAPI } from '@/api'
jest.mock('@/api', () => ({ getAPI: jest.fn(), callAPI: jest.fn(), postAPI: jest.fn() }))
jest.mock('@/store', () => ({ getters: {}, state: { user: { token: 'session' } } }))
const id = '11111111-1111-4111-8111-111111111111'
const other = '22222222-2222-4222-8222-222222222222'
const reply = rows => ({ listkubernetessupportedversionsresponse: { count: rows.length, kubernetessupportedversion: rows } })
let ctx
beforeEach(() => {
  jest.clearAllMocks()
  store.state.user.token = 'session'
  store.getters = { defaultListViewPageSize: 20, userInfo: { id: 'actor', roletype: 'Admin', roleid: 'role' }, project: {}, apis: { listKubernetesSupportedVersions: {} }, zones: [{ id }], customColumns: {}, metrics: false, listAllProjects: false }
  ctx = { $store: store, $route: { name: 'kubernetesiso', path: '/kubernetesiso', fullPath: '/kubernetesiso', params: {}, query: {}, meta: { name: 'kubernetesiso', permission: ['listKubernetesSupportedVersions'], columns: ['name', 'state'], actions: [] } }, $router: { replace: jest.fn(), push: jest.fn() }, $refs: {}, $emit: jest.fn(), $t: key => key, $showIcon: () => false, $i18n: { locale: 'ko_KR' }, $message: { info: jest.fn(), error: jest.fn() }, $notifyError: jest.fn(), $nextTick: fn => fn() }
  ctx.$route.query = kubernetesIsoListQuery({}, 20, store.getters, store.state.user.token)
  ctx.$router.replace.mockImplementation(({ query }) => {
    ctx.$route.query = query; ctx.$route.fullPath = ctx.$route.path + '?' + new URLSearchParams(query)
    return ctx.fetchData()
  })
  Object.assign(ctx, Autogen.data.call(ctx))
  Object.entries(Autogen.methods).forEach(([key, fn]) => { ctx[key] = fn.bind(ctx) })
  for (const name of ['kubernetesIsoSecurityContext', 'kubernetesIsoScope']) {
    Object.defineProperty(ctx, name, { get: () => Autogen.computed[name].call(ctx) })
  }
  ctx.updateSelectedColumns = jest.fn()
  getAPI.mockResolvedValue(reply([{ id, name: 'Version', state: 'Enabled', directdownload: false }]))
})
const detail = target => Object.assign(ctx.$route, { path: '/kubernetesiso/' + target, fullPath: '/kubernetesiso/' + target, params: { id: target } })
test('catalog-only read uses its own API and never ISO ownership, project, or VM Metrics APIs', async () => {
  store.getters.userInfo.roletype = 'User'; store.getters.project = { id: other }; store.getters.metrics = true
  await ctx.fetchData()
  expect(getAPI).toHaveBeenCalledTimes(1)
  expect(getAPI.mock.calls[0][0]).toBe('listKubernetesSupportedVersions')
  expect(getAPI.mock.calls[0][1]).not.toHaveProperty('projectid')
  expect(getAPI.mock.calls[0][1]).not.toHaveProperty('isofilter')
  expect(callAPI).not.toHaveBeenCalled()
  expect(ctx.resource.directdownload).toBe(false)
})
test.each([{}])('malformed/denied resolved response is an inline read error', async json => {
  getAPI.mockResolvedValue(json); await ctx.fetchData()
  expect(ctx.kubernetesIsoReadError).toBe('invalid'); expect(ctx.items).toEqual([]); expect(ctx.$router.push).not.toHaveBeenCalled()
})
test.each([[401, 'expired'], [531, 'forbidden'], [503, 'failed']])('HTTP%s has distinct error state and clears previously read data', async (status, expected) => {
  await ctx.fetchData(); getAPI.mockRejectedValueOnce({ response: { status } }); await ctx.fetchData({ irefresh: true })
  expect(ctx.kubernetesIsoReadError).toBe(expected); expect(ctx.items).toEqual([]); expect(ctx.resource).toEqual({})
  expect(ctx.$router.push).not.toHaveBeenCalled(); expect(ctx.$notifyError).not.toHaveBeenCalled()
  await ctx.fetchData({ irefresh: true }); expect(ctx.resource.id).toBe(id); expect(ctx.kubernetesIsoReadError).toBe('')
})
test.each(['missing', 'mismatch', 'invalid'])('detail %s stays recoverable without a generic 404 redirect', async state => {
  detail(state === 'invalid' ? 'bad-id' : id)
  getAPI.mockResolvedValue(reply(state === 'mismatch' ? [{ id: other }] : []))
  await ctx.fetchData()
  expect(ctx.kubernetesIsoReadError).toBe(state === 'missing' ? 'missing' : 'invalid')
  expect(ctx.$router.push).not.toHaveBeenCalled(); expect(ctx.resource).toEqual({})
})
test('known empty catalog succeeds while missing read capability does not send a broad request', async () => {
  getAPI.mockResolvedValue(reply([])); await ctx.fetchData(); expect(ctx.kubernetesIsoReadError).toBe(''); expect(ctx.itemCount).toBe(0)
  delete store.getters.apis.listKubernetesSupportedVersions; getAPI.mockClear(); await ctx.fetchData()
  expect(getAPI).not.toHaveBeenCalled(); expect(ctx.kubernetesIsoReadError).toBe('forbidden')
})
test.each(['role', 'project', 'session', 'target', 'capability'])('delayed catalog response cannot cross %s boundary', async change => {
  detail(id); let finish; getAPI.mockImplementationOnce(() => new Promise(resolve => { finish = resolve }))
  const pending = ctx.fetchData()
  if (change === 'role') store.getters.userInfo.roleid = 'new-role'
  if (change === 'project') store.getters.project = { id: other }
  if (change === 'session') store.state.user.token = 'new-session'
  if (change === 'target') detail(other)
  if (change === 'capability') store.getters.apis.listZones = {}
  const target = change === 'target' ? other : id
  getAPI.mockResolvedValue(reply([{ id: target, name: 'Current' }]))
  await ctx.fetchData(); finish(reply([{ id, name: 'Late old' }])); await pending
  expect(ctx.resource.name).toBe('Current')
})
test('revoking catalog access while a read is in flight leaves no old row or stale action', async () => {
  let finish; getAPI.mockImplementationOnce(() => new Promise(resolve => { finish = resolve }))
  const pending = ctx.fetchData(); store.getters.apis = {}; await ctx.fetchData()
  finish(reply([{ id }])); await pending
  expect(ctx.resource).toEqual({}); expect(ctx.kubernetesIsoReadError).toBe('forbidden')
  ctx.execAction({ api: 'deleteKubernetesSupportedVersion' }, false); expect(ctx.showAction).toBe(false)
})
test('RootAdmin command grants are independent and User does not inherit a mutation from a malformed grant set', () => {
  const entry = image.children.find(c => c.name === 'kubernetesiso')
  const local = entry.actions.find(a => a.api === 'getUploadParamsForKubernetesSupportedVersion')
  const url = entry.actions.find(a => a.api === 'addKubernetesSupportedVersion')
  store.getters.apis.getUploadParamsForKubernetesSupportedVersion = {}
  expect(local.show({}, store.getters)).toBe(true); expect(url.show({}, store.getters)).toBe(false)
  store.getters.userInfo.roletype = 'User'; expect(local.show({}, store.getters)).toBe(false)
  expect(entry.details()).not.toContain('isourl')
})
test('the actual local-only form renders from its own metadata without requiring URL descriptions or advertising unsupported arch', () => {
  store.getters.apis = { getUploadParamsForKubernetesSupportedVersion: { params: [{ name: 'name', description: 'Local name' }, { name: 'semanticversion' }, { name: 'zoneid' }, { name: 'checksum' }, { name: 'mincpunumber' }, { name: 'minmemory' }] } }
  const getApiParams = jest.fn(() => { throw new Error('URL metadata must not be requested') })
  const w = shallowMount(Add, {
    props: { action: { currentAction: { api: 'getUploadParamsForKubernetesSupportedVersion', icon: 'cloud-upload-outlined' } } },
    global: {
      mocks: { $store: store, $getApiParams: getApiParams, $t: key => key, $fetchCpuArchitectureTypes: () => [] },
      stubs: { 'a-spin': { render () { return h('div', this.$slots.default?.()) } }, 'a-form': { render () { return h('form', this.$slots.default?.()) } }, 'a-form-item': { props: ['name'], render () { return h('div', { 'data-field': this.name }, [this.$slots.label?.(), this.$slots.default?.()]) } } }
    }
  })
  expect(w.vm.currentForm).toBe('Upload'); expect(w.vm.apiParams.name.description).toBe('Local name')
  expect(getApiParams).not.toHaveBeenCalled(); expect(w.find('[data-field="arch"]').exists()).toBe(false)
  expect(getAPI).not.toHaveBeenCalled(); w.unmount()
})
test('a denied standalone form never validates or submits a mutation', () => {
  const validate = jest.fn(); Add.methods.handleSubmit.call({ loading: false, registrationCapability: { available: false }, formRef: { value: { validate } } }, { preventDefault () {} })
  expect(validate).not.toHaveBeenCalled(); expect(postAPI).not.toHaveBeenCalled()
})

test('API531 carried by a resolved envelope still produces a forbidden read state', async () => {
  getAPI.mockResolvedValue({ listkubernetessupportedversionsresponse: { errorcode: 531 } })
  await ctx.fetchData()
  expect(ctx.kubernetesIsoReadError).toBe('forbidden'); expect(ctx.items).toEqual([])
})

test('a fresh KISO column selector is available even when the global VM Metrics preference is enabled', async () => {
  store.getters.metrics = true
  await ctx.fetchData()
  expect(ctx.updateSelectedColumns).toHaveBeenCalled()
  expect(getAPI.mock.calls[0][0]).toBe('listKubernetesSupportedVersions')
  expect(callAPI).not.toHaveBeenCalled()
})
