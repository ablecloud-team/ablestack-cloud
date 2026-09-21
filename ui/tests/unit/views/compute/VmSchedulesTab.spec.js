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
import VmSchedulesTab from '@/views/compute/VmSchedulesTab.vue'
import { getAPI, postAPI } from '@/api'
jest.mock('@/api', () => ({ getAPI: jest.fn(), postAPI: jest.fn() }))
jest.mock('@/utils/timezone', () => ({ timeZone: () => Promise.resolve([]) }))
const row = { id: 's1', action: 'STOP', enabled: false, description: 'test', schedule: '5 3 * * *', timezone: 'Asia/Seoul', startdate: '2026-01-01T00:00:00+0000' }
const response = (rows, count = rows.length) => ({ listresourcescheduleresponse: { resourceschedule: rows, count } })
const flush = async () => { for (let i = 0; i < 20; i++) await Promise.resolve() }
function mount (apis = { createResourceSchedule: {}, updateResourceSchedule: {}, deleteResourceSchedule: {} }) {
  return shallowMount(VmSchedulesTab, {
    props: { resource: { id: 'v1' } },
    global: { stubs: { AModal: { template: '<div><slot /></div>' } }, mocks: { $store: { getters: { apis, project: {}, userInfo: {} }, state: { user: {} } }, $route: {}, $i18n: { locale: 'ko_KR' }, $t: x => x, $toLocaleDate: x => x, $message: { success: jest.fn() }, $notifyError: jest.fn() } }
  })
}
beforeEach(() => { jest.useFakeTimers(); jest.clearAllMocks(); getAPI.mockResolvedValue(response([row])); postAPI.mockResolvedValue({}) })
afterEach(() => jest.useRealTimers())
test('search includes results from later API pages', async () => {
  getAPI.mockResolvedValueOnce(response([row], 2)).mockResolvedValueOnce(response([{ ...row, id: 's2', description: 'later' }], 2))
  const wrapper = mount(); await flush()
  wrapper.vm.search = 'later'
  expect(wrapper.vm.visibleRows.map(x => x.id)).toEqual(['s2'])
  expect(getAPI.mock.calls[1][1].page).toBe(2)
  wrapper.unmount()
})
test('failed refresh preserves rows and shows stale warning', async () => {
  const wrapper = mount(); await flush()
  getAPI.mockRejectedValue(new Error('offline'))
  await wrapper.vm.fetchSchedules()
  expect(wrapper.vm.rows).toHaveLength(1)
  expect(wrapper.vm.listRefreshFailed).toBe(true)
  wrapper.unmount()
})
test('ignores a response belonging to a previous VM', async () => {
  let resolveRequest
  getAPI.mockReturnValueOnce(new Promise(resolve => { resolveRequest = resolve }))
  const wrapper = mount()
  getAPI.mockResolvedValue(response([{ ...row, id: 's2' }]))
  await wrapper.setProps({ resource: { id: 'v2' } }); await flush()
  resolveRequest(response([row])); await flush()
  expect(wrapper.vm.rows[0].id).toBe('s2')
  wrapper.unmount()
})
test('read-only users cannot open or submit mutations', async () => {
  const wrapper = mount({}); await flush()
  wrapper.vm.open('delete', row); await wrapper.vm.submit()
  expect(wrapper.vm.mode).toBe('')
  expect(postAPI).not.toHaveBeenCalled()
  wrapper.unmount()
})
test('toggle submits only id and enabled, once', async () => {
  const wrapper = mount(); await flush()
  wrapper.vm.open('toggle', row)
  await Promise.all([wrapper.vm.submit(), wrapper.vm.submit()])
  expect(postAPI).toHaveBeenCalledTimes(1)
  expect(postAPI).toHaveBeenCalledWith('updateResourceSchedule', { id: 's1', enabled: true })
  wrapper.unmount()
})
test('editing preserves raw cron and displays dates in the schedule timezone', async () => {
  const wrapper = mount(); await flush()
  wrapper.vm.open('edit', row)
  expect(wrapper.vm.form.rawCron).toBe(true)
  expect(wrapper.vm.form.schedule).toBe(row.schedule)
  expect(wrapper.vm.form.startDate.format('YYYY-MM-DD HH:mm:ss')).toBe('2026-01-01 09:00:00')
  wrapper.vm.close(); wrapper.vm.open('create')
  expect(wrapper.vm.selected).toBeNull()
  expect(wrapper.vm.form.action).toBe('START')
  wrapper.unmount()
})
test('edit omits unchanged historical dates and retains the original cron', async () => {
  const wrapper = mount(); await flush()
  wrapper.vm.open('edit', row); await wrapper.vm.$nextTick()
  wrapper.vm.$refs.editor.validate = jest.fn().mockResolvedValue()
  await wrapper.vm.submit()
  expect(postAPI).toHaveBeenCalledWith('updateResourceSchedule', { id: 's1', description: 'test', schedule: '5 3 * * *', timezone: 'Asia/Seoul', enabled: false })
  wrapper.unmount()
})
test('failed save preserves the form for correction', async () => {
  const wrapper = mount(); await flush()
  wrapper.vm.open('edit', row); await wrapper.vm.$nextTick()
  wrapper.vm.$refs.editor.validate = jest.fn().mockResolvedValue()
  wrapper.vm.form.description = 'unsaved edit'
  postAPI.mockRejectedValue(new Error('server validation'))
  await wrapper.vm.submit()
  expect(wrapper.vm.mode).toBe('edit')
  expect(wrapper.vm.form.description).toBe('unsaved edit')
  expect(wrapper.vm.submitError).toBe('server validation')
  wrapper.unmount()
})

test('delete includes the resource type and VM scope required by the API', async () => {
  const wrapper = mount(); await flush()
  wrapper.vm.open('delete', row); await wrapper.vm.submit()
  expect(postAPI).toHaveBeenCalledWith('deleteResourceSchedule', { id: 's1', resourceid: 'v1', resourcetype: 'VirtualMachine' })
  wrapper.unmount()
})
