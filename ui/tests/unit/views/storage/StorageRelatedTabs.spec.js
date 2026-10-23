// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements. See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership. The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License. You may obtain a copy of the License at
// http://www.apache.org/licenses/LICENSE-2.0
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied. See the License for the
// specific language governing permissions and limitations
// under the License.
import Events from '@/components/view/EventsTab.vue'
import Notes from '@/components/view/AnnotationsTab.vue'
import Stats from '@/components/view/StatsTab.vue'
import Recurring from '@/views/storage/RecurringSnapshotVolume.vue'
import storage from '@/config/section/storage'
import store from '@/store'
import { getAPI } from '@/api'
jest.mock('@/api', () => ({ getAPI: jest.fn(), postAPI: jest.fn() }))
jest.mock('@/store', () => ({ getters: { features: { instancesdisksstatsretentionenabled: true }, apis: {}, userInfo: {} } }))
beforeEach(() => getAPI.mockReset())

test('volume events preserve the authenticated session and existing rows on read failure', async () => {
  getAPI.mockRejectedValueOnce(new Error('Network Error'))
  const token = { loaded: true }
  const vm = { resource: { id: 'volume' }, resourceType: 'Volume', page: 2, pageSize: 20, events: ['keep'], listRequestToken: () => token, isListRequestCurrent: () => true, $notifyError: jest.fn() }
  await Events.methods.fetchEvents.call(vm)
  expect(getAPI).toHaveBeenCalledWith('listEvents', { resourceid: 'volume', resourcetype: 'Volume', page: 2, pagesize: 20, listall: true }, { preserveOnFailure: true })
  expect(vm.events).toEqual(['keep']); expect(vm.listRefreshFailed).toBe(true); expect(vm.$notifyError).not.toHaveBeenCalled()
})
test('volume comments preserve rows and count on a failed page read', async () => {
  getAPI.mockRejectedValueOnce(new Error('503'))
  const token = { loaded: true }
  const vm = { resource: { id: 'volume' }, annotationType: 'VOLUME', page: 2, pageSize: 10, notes: ['keep'], itemCount: 12, $store: { getters: { apis: { listAnnotations: {} } } }, listRequestToken: () => token, isListRequestCurrent: () => true }
  await Notes.methods.getAnnotations.call(vm)
  expect(getAPI.mock.calls[0][1]).toMatchObject({ entityid: 'volume', entitytype: 'VOLUME', page: 2, pagesize: 10 })
  expect(getAPI.mock.calls[0][2]).toEqual({ preserveOnFailure: true }); expect(vm.notes).toEqual(['keep']); expect(vm.itemCount).toBe(12)
})
test('usage history requires feature and API permission; comments require list permission', () => {
  const tabs = storage.children.find(c => c.name === 'volume').tabs
  store.getters.apis = {}; expect(tabs.find(t => t.name === 'metrics').show()).toBe(false); expect(tabs.find(t => t.name === 'comments').show()).toBe(false)
  store.getters.apis = { listVolumesUsageHistory: {}, listAnnotations: {} }; expect(tabs.find(t => t.name === 'metrics').show()).toBe(true); expect(tabs.find(t => t.name === 'comments').show()).toBe(true)
  store.getters.features.instancesdisksstatsretentionenabled = false; expect(tabs.find(t => t.name === 'metrics').show()).toBe(false)
})
test('a late statistics response cannot populate a different login or project context', async () => {
  let release
  getAPI.mockReturnValueOnce(new Promise(resolve => { release = resolve })).mockResolvedValueOnce({ listvolumesusagehistoryresponse: {} })
  const vm = { resource: { id: 'same-volume' }, resourceType: 'Volume', resourceStatsApi: 'listVolumesUsageHistory', statsRequestId: 0, statsResourceId: null, formatPeriod: jest.fn(), resetData: jest.fn(), handleStatsResponse: jest.fn(), $store: { state: { user: { token: 'first' } }, getters: { userInfo: { id: 'first' }, project: { id: 'A' } } } }
  vm.fetchData = () => Stats.methods.fetchData.call(vm)
  const oldContext = Stats.computed.statsContext.call(vm); const old = vm.fetchData()
  vm.$store.getters.project.id = 'B'; vm.$store.state.user.token = 'second'
  expect(Stats.computed.statsContext.call(vm)).not.toBe(oldContext)
  Stats.watch.statsContext.call(vm); await Promise.resolve(); release({ listvolumesusagehistoryresponse: { privateOldValues: true } }); await old
  expect(vm.handleStatsResponse).toHaveBeenCalledTimes(1)
  expect(vm.handleStatsResponse).not.toHaveBeenCalledWith(expect.objectContaining({ listvolumesusagehistoryresponse: { privateOldValues: true } }))
  expect(getAPI.mock.calls[0][2]).toEqual({ preserveOnFailure: true })
})
test('recurring snapshot volume choices read every API page', async () => {
  const row = id => ({ id, state: 'Ready', hypervisor: 'KVM', vmstate: 'Stopped' })
  getAPI.mockResolvedValueOnce({ listvolumesresponse: { count: 2, volume: [row('first')] } }).mockResolvedValueOnce({ listvolumesresponse: { count: 2, volume: [row('later')] } })
  const vm = { ...Recurring.methods, volumeVersion: 0, ownerContext: 'same', $store: { getters: { features: {} } } }
  await Recurring.methods.fetchVolumes.call(vm)
  expect(vm.volumes.map(v => v.id)).toEqual(['first', 'later']); expect(getAPI.mock.calls[1][1].page).toBe(2)
  expect(getAPI.mock.calls[1][2]).toEqual({ preserveOnFailure: true })
})
test('snapshot policy read failure preserves rows and exposes retry; empty successful response clears them', async () => {
  const vm = { ...Recurring.methods, currentVolumeResource: { id: 'v' }, ownerContext: 'same', policyVersion: 0, dataSource: ['keep'] }
  getAPI.mockRejectedValueOnce(new Error('Network Error')); await Recurring.methods.fetchData.call(vm)
  expect(vm.dataSource).toEqual(['keep']); expect(vm.policyError).toBe('Network Error')
  getAPI.mockResolvedValueOnce({ listsnapshotpoliciesresponse: { count: 0 } }); await Recurring.methods.fetchData.call(vm)
  expect(vm.dataSource).toEqual([]); expect(vm.policyError).toBe('')
})
test('snapshot policy response from a previous project cannot restore private rows or selections', async () => {
  let release
  getAPI.mockReturnValueOnce(new Promise(resolve => { release = resolve }))
  const vm = { ...Recurring.methods, currentVolumeResource: { id: 'v' }, ownerContext: 'first', policyVersion: 0, volumeVersion: 0, dataSource: ['old'], isVolumeResource: true }
  const pending = Recurring.methods.fetchData.call(vm)
  vm.ownerContext = 'second'; vm.fetchData = jest.fn(); Recurring.watch.ownerContext.call(vm)
  release({ listsnapshotpoliciesresponse: { count: 1, snapshotpolicy: [{ id: 'private-old' }] } }); await pending
  expect(vm.dataSource).toEqual([]); expect(vm.selectedVolumeId).toBe(null); expect(vm.loading).toBe(false)
})
