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
import { shallowMount, flushPromises } from '@vue/test-utils'
import SearchView from '@/components/view/SearchView'
import SearchFilter from '@/components/view/SearchFilter'
import ListView from '@/components/view/ListView'
import storage from '@/config/section/storage'
import { getAPI } from '@/api'
import ko from '@public/locales/ko_KR.json'

jest.mock('@/store', () => ({ getters: { apis: {}, userInfo: {} } }))
jest.mock('@/api', () => ({ getAPI: jest.fn(), postAPI: jest.fn() }))

const vmId = '12345678-1234-1234-1234-123456789abc'
const schedule = storage.children.find(section => section.name === 'backupschedule')
const backup = storage.children.find(section => section.name === 'backup')
const translate = key => ko[key] || key

function context (apiName, query = {}) {
  const ctx = {
    apiName,
    searchFilters: apiName === 'listBackups' ? backup.searchFilters() : schedule.searchFilters,
    $store: { getters: { apis: { listVirtualMachines: {} }, userInfo: {}, project: {} } },
    $route: { name: apiName === 'listBackups' ? 'backup' : 'backupschedule', path: '/backupschedule', query, meta: {} },
    $emit: jest.fn(),
    $t: translate
  }
  Object.assign(ctx, SearchView.data.call(ctx))
  SearchView.created.call(ctx)
  Object.entries(SearchView.methods).forEach(([name, method]) => { ctx[name] = method.bind(ctx) })
  ctx.initFields()
  ctx.fetchStaticFieldData(ctx.searchFilters)
  ctx.fillFormFieldValues()
  ctx.formRef = { value: { validate: () => Promise.resolve(), resetFields: jest.fn() } }
  return ctx
}

beforeEach(() => jest.clearAllMocks())

test.each(['true', 'false'])('schedule search restores and submits the explicit quiesce choice %s', async choice => {
  const ctx = context('listBackupSchedule', { virtualmachineid: vmId, quiescevm: choice })
  const field = ctx.fields.find(field => field.name === 'quiescevm')
  expect(field.type).toBe('list')
  expect(field.opts.map(option => translate(option.name))).toEqual(['예', '아니오'])
  ctx.handleSubmit()
  await flushPromises()
  expect(ctx.$emit).toHaveBeenCalledWith('search', { virtualmachineid: vmId, quiescevm: choice })
})

test('clearing the schedule selector omits quiesce and resetting returns to all schedules', async () => {
  const ctx = context('listBackupSchedule', { virtualmachineid: vmId, quiescevm: 'false' })
  ctx.form.quiescevm = undefined
  ctx.handleSubmit()
  await flushPromises()
  expect(ctx.$emit).toHaveBeenLastCalledWith('search', { virtualmachineid: vmId })
  await ctx.onClear()
  expect(ctx.$emit).toHaveBeenLastCalledWith('search', {})
})

test.each(['HOURLY', 'DAILY', 'WEEKLY', 'MONTHLY'])('schedule interval %s combines with the VM and disabled quiesce choice', async intervaltype => {
  const ctx = context('listBackupSchedule', { virtualmachineid: vmId, intervaltype, quiescevm: 'false' })
  const field = ctx.fields.find(field => field.name === 'intervaltype')
  expect(field.type).toBe('list')
  expect(field.opts.map(option => translate(option.name))).toEqual(['매시간', '매일', '매주', '매월'])
  ctx.handleSubmit()
  await flushPromises()
  expect(ctx.$emit).toHaveBeenCalledWith('search', { virtualmachineid: vmId, intervaltype, quiescevm: 'false' })
  expect(SearchFilter.methods.getStaticFieldValue.call(ctx, 'intervaltype', intervaltype)).toBe(translate('label.' + intervaltype.toLowerCase()))
  ctx.form.intervaltype = undefined
  ctx.handleSubmit()
  await flushPromises()
  expect(ctx.$emit).toHaveBeenLastCalledWith('search', { virtualmachineid: vmId, quiescevm: 'false' })
})

test.each(['true', 'false'])('the quiesce %s filter tag has a readable label and value', value => {
  const ctx = { apiName: 'listBackupSchedule', $t: translate }
  expect(SearchFilter.methods.retrieveFieldLabel.call(ctx, 'quiescevm')).toBe('VM 일시정지')
  expect(SearchFilter.methods.getStaticFieldValue.call(ctx, 'quiescevm', value)).toBe(value === 'true' ? '예' : '아니오')
})

test('backup VM search reuses the existing VM selection list and submits its ID alongside other filters', async () => {
  const ctx = context('listBackups', { virtualmachineid: vmId, status: 'BackedUp', zoneid: 'zone' })
  expect(ctx.fields.find(field => field.name === 'virtualmachineid').type).toBe('list')
  expect(ctx.retrieveFieldLabel('virtualmachineid')).toBe('VM 이름')
  getAPI.mockResolvedValueOnce({ listvirtualmachinesresponse: { virtualmachine: [{ id: vmId, name: '업무 VM' }] } })
  const options = await ctx.fetchVirtualMachines()
  expect(options.data).toEqual([{ id: vmId, name: '업무 VM' }])
  ctx.handleSubmit()
  await flushPromises()
  expect(ctx.$emit).toHaveBeenCalledWith('search', { virtualmachineid: vmId, status: 'BackedUp', zoneid: 'zone' })
})

test('the selected backup VM is shown by name while retaining its identifier and current search scope', async () => {
  const ctx = { apiName: 'listBackups', $t: translate, $isValidUuid: () => true, $store: { getters: { apis: { listVirtualMachines: {} } } }, $route: { query: { projectid: 'project', zoneid: 'zone' } } }
  getAPI.mockResolvedValueOnce({ listvirtualmachinesresponse: { virtualmachine: [{ id: vmId, name: '업무 VM' }] } })
  expect(SearchFilter.methods.retrieveFieldLabel.call(ctx, 'virtualmachineid')).toBe('VM 이름')
  expect(await SearchFilter.methods.getDynamicFieldValue.call(ctx, 'virtualmachineid', vmId)).toBe('업무 VM')
  expect(getAPI).toHaveBeenCalledWith('listVirtualMachines', { id: vmId, listall: true, projectid: 'project', zoneid: 'zone' }, { preserveOnFailure: true })
})

test('an unavailable VM name keeps the selected identifier', async () => {
  const ctx = { apiName: 'listBackups', $isValidUuid: () => true, $store: { getters: { apis: { listVirtualMachines: {} } } }, $route: { query: {} } }
  getAPI.mockResolvedValueOnce({ listvirtualmachinesresponse: {} })
  expect(await SearchFilter.methods.getDynamicFieldValue.call(ctx, 'virtualmachineid', vmId)).toBe(vmId)
})

test('the schedule list displays quiesce choices, including older unset schedules', () => {
  expect(schedule.columns()).toContain('quiescevm')
  const table = { props: ['dataSource'], template: '<div><div v-for="record in dataSource" :key="record.id" class="schedule"><slot name="bodyCell" :column="{ key: \'quiescevm\' }" :record="record" :text="record.quiescevm" /></div></div>' }
  const wrapper = shallowMount(ListView, {
    props: { columns: [{ key: 'quiescevm', dataIndex: 'quiescevm' }], items: [{ id: 'enabled', quiescevm: true }, { id: 'disabled', quiescevm: false }, { id: 'legacy' }] },
    global: { provide: { parentFetchData: jest.fn(), parentToggleLoading: jest.fn() }, mocks: { $route: { name: 'backupschedule', path: '/backupschedule', query: {}, params: {}, meta: {} }, $store: { getters: { apis: {}, userInfo: {} } }, $t: translate }, stubs: { 'a-table': table } }
  })
  expect(wrapper.findAll('.schedule').map(row => row.text())).toEqual(['예', '아니오', '아니오'])
  wrapper.unmount()
})
