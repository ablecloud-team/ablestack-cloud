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

import TemplateZones from '@/views/image/TemplateZones.vue'
import ResourceView from '@/components/view/ResourceView.vue'
import { getAPI } from '@/api'
import { templateObservationContext } from '@/utils/templateAvailability'
jest.mock('@/api', () => ({ getAPI: jest.fn(), postAPI: jest.fn() }))
const id = '11111111-1111-4111-8111-111111111111'; const other = '22222222-2222-4222-8222-222222222222'
const row = { id, zoneid: other, isready: false, status: 'Connection refused', downloaddetails: [{ downloadState: 'DOWNLOAD_ERROR' }] }
const result = { listtemplatesresponse: { count: 1, template: [row] } }
function context () {
  const vm = { resource: { ...row }, $route: { path: '/template/' + id, query: { tab: 'zones' }, meta: { name: 'template' } }, $store: { getters: { userInfo: { id: 'u', roletype: 'Admin' }, project: {}, apis: {} }, state: { user: { token: 'test-session' } } }, page: 1, pageSize: 10, dataSource: [], itemCount: 0, $emit: jest.fn(), $notifyError: jest.fn(), fetchZoneData: jest.fn(), fetchOsCategoryId: jest.fn(), updateImageZones: jest.fn() }
  Object.defineProperty(vm, 'templateScope', { get: () => TemplateZones.computed.templateScope.call(vm) })
  vm.token = { loaded: false }; vm.listRequestToken = () => vm.token; vm.isListRequestCurrent = (_, t) => t === vm.token
  return vm
}
beforeEach(() => { jest.clearAllMocks(); getAPI.mockResolvedValue(result) })
test('current Zone read emits raw availability only after successful observation', async () => {
  const vm = context(); await TemplateZones.methods.fetchData.call(vm)
  expect(vm.$emit).toHaveBeenCalledWith('availability-observed', expect.objectContaining({ templateid: id, rows: [row], count: 1, coverage: 'complete' }))
  expect(vm.fetchLoading).toBe(false)
})
test.each(['target', 'project', 'role', 'session'])('late %s reply cannot paint rows or a parent summary', async change => {
  const vm = context(); let done; getAPI.mockImplementationOnce(() => new Promise(resolve => { done = resolve }))
  const old = TemplateZones.methods.fetchData.call(vm)
  if (change === 'target') vm.resource = { ...row, id: other }
  if (change === 'project') vm.$store.getters.project = { id: other }
  if (change === 'role') vm.$store.getters.userInfo.roletype = 'User'
  if (change === 'session') vm.$store.state.user.token = 'next-session'
  done(result); await old
  expect(vm.dataSource).toEqual([]); expect(vm.$emit).not.toHaveBeenCalled(); expect(vm.fetchLoading).toBe(false)
})
test('old finally cannot clear a newer request spinner', async () => {
  const vm = context(); let done; getAPI.mockImplementationOnce(() => new Promise(resolve => { done = resolve }))
  const old = TemplateZones.methods.fetchData.call(vm); const next = { loaded: false }; vm.token = next; vm.fetchLoadingRequest = next; vm.fetchLoading = true
  done(result); await old; expect(vm.fetchLoading).toBe(true)
})
test('refresh failure preserves previous observation and never invents new resource failure', async () => {
  const vm = context(); vm.token.loaded = true; vm.dataSource = [row]; getAPI.mockRejectedValue(new Error('offline'))
  await TemplateZones.methods.fetchData.call(vm)
  expect(vm.dataSource).toEqual([row]); expect(vm.listRefreshFailed).toBe(true); expect(vm.$emit).not.toHaveBeenCalled()
})
test('missing template never sends an unscoped list query', async () => {
  const vm = context(); vm.resource = {}; await TemplateZones.methods.fetchData.call(vm)
  expect(getAPI).not.toHaveBeenCalled(); expect(vm.dataSource).toEqual([])
})
test('parent accepts only the current authorized read context', () => {
  const vm = context(); vm.templateZoneObservation = null
  Object.defineProperty(vm, 'templateContext', { get: () => templateObservationContext(vm.$route, vm.resource, vm.$store.getters, vm.$store.state.user.token) })
  const observation = { templateid: id, context: vm.templateContext, rows: [row] }
  ResourceView.methods.acceptTemplateObservation.call(vm, observation); expect(vm.templateZoneObservation).toBe(observation)
  vm.$store.getters.project = { id: other }; vm.templateZoneObservation = null
  ResourceView.methods.acceptTemplateObservation.call(vm, observation); expect(vm.templateZoneObservation).toBeNull()
})

test('normal recipient Zone read falls back to real shared scope while preserving project and paging', async () => {
  const vm = context(); vm.$store.getters.userInfo.roletype = 'User'; vm.$store.getters.project = { id: other }
  getAPI.mockResolvedValueOnce({ listtemplatesresponse: {} }).mockResolvedValueOnce(result)
  await TemplateZones.methods.fetchData.call(vm)
  expect(getAPI.mock.calls.map(call => call[1])).toEqual([
    { id, templatefilter: 'executable', listall: true, page: 1, pagesize: 10, projectid: other },
    { id, templatefilter: 'shared', listall: true, page: 1, pagesize: 10, projectid: other }
  ])
  expect(vm.dataSource).toHaveLength(1)
})
