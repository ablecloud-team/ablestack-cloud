// Licensed to the Apache Software Foundation (ASF) under one or more
// contributor license agreements. See NOTICE for copyright ownership.
// Licensed under the Apache License, Version 2.0 (the "License");
// http://www.apache.org/licenses/LICENSE-2.0 . Distributed AS IS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND.
import { mount, flushPromises } from '@vue/test-utils'
import dayjs from 'dayjs'
import Form from '@/views/storage/FormSchedule'
import { getAPI, postAPI } from '@/api'
jest.mock('@/api', () => ({ getAPI: jest.fn(), postAPI: jest.fn() }))
jest.mock('lodash/debounce', () => fn => fn)
jest.mock('@/utils/timezone', () => ({ timeZone: () => Promise.resolve([{ id: 'UTC', name: 'UTC' }]) }))
let wrappers
async function render () {
  const w = mount(Form, {
    attachTo: document.body,
    props: { resource: { id: 'volume', account: 'admin', domainid: 'domain' }, dataSource: [] },
    global: { mocks: { $store: { getters: { userInfo: { id: 'owner', roletype: 'Admin' }, project: {}, apis: { createSnapshotPolicy: {}, listCapabilities: {} } }, state: { user: { token: 'session' } } }, $route: { path: '/snapshotpolicy' }, $t: k => k, $getApiParams: () => [], $notifyError: jest.fn(), $notification: { success: jest.fn() } } }
  })
  wrappers.push(w); await flushPromises()
  Object.assign(w.vm.form, { intervaltype: 'monthly', timeSelect: dayjs('2026-10-27T23:59:00'), maxsnaps: 1, timezone: 'UTC' })
  w.vm.handleChangeIntervalType(); await flushPromises(); return w
}
async function search (w, text) {
  const input = w.find('input[id="form_item_day-of-month"]')
  await input.trigger('mousedown'); await input.setValue(text); await flushPromises()
  return document.querySelector('.ant-select-dropdown:not(.ant-select-dropdown-hidden)')
}
beforeEach(() => {
  wrappers = []; getAPI.mockReset(); postAPI.mockReset(); postAPI.mockResolvedValue({})
  getAPI.mockResolvedValue({ listcapabilitiesresponse: { capability: { snapshotpolicymax: { hourly: 8, daily: 8, weekly: 8, monthly: 8 } } } })
})
afterEach(async () => { wrappers.forEach(w => w.unmount()); await flushPromises(); document.body.innerHTML = '' })

test.each(['1', '9', '10', '28'])('the real numeric select searches %s without throwing', async term => {
  const w = await render(); const dropdown = await search(w, term)
  expect(dropdown).not.toBeNull()
  expect([...dropdown.querySelectorAll('.ant-select-item-option-content')].some(e => e.textContent === term)).toBe(true)
  expect(postAPI).not.toHaveBeenCalled()
})
test.each(['29', 'missing'])('empty search result %s recovers after clearing', async term => {
  const w = await render(); const dropdown = await search(w, term)
  expect(dropdown.querySelectorAll('.ant-select-item-option')).toHaveLength(0)
  await search(w, ''); const recovered = await search(w, '28')
  expect([...recovered.querySelectorAll('.ant-select-item-option-content')].some(e => e.textContent === '28')).toBe(true)
  expect(w.vm.form['day-of-month']).toBeUndefined(); expect(postAPI).not.toHaveBeenCalled()
})
test.each([1, 28])('selecting day %s preserves its numeric value and schedule encoding', async day => {
  const w = await render(); const dropdown = await search(w, String(day))
  const option = [...dropdown.querySelectorAll('.ant-select-item-option-content')].find(e => e.textContent === String(day))
  option.closest('.ant-select-item-option').click(); await flushPromises()
  expect(w.vm.form['day-of-month']).toBe(day); await w.vm.handleSubmit()
  expect(postAPI).toHaveBeenCalledWith('createSnapshotPolicy', expect.objectContaining({ volumeid: 'volume', intervaltype: 'monthly', schedule: '59:23:' + day }), { preserveOnFailure: true })
})
test('a different display label cannot replace the selected numeric day', async () => {
  const w = await render(); w.vm.dayOfMonth[27].name = '28일'; await flushPromises(); const dropdown = await search(w, '28')
  const option = [...dropdown.querySelectorAll('.ant-select-item-option-content')].find(e => e.textContent === '28일')
  option.closest('.ant-select-item-option').click(); await flushPromises(); expect(w.vm.form['day-of-month']).toBe(28)
  await w.vm.handleSubmit(); expect(postAPI.mock.calls[0][1].schedule).toBe('59:23:28')
})
test('numeric/string option values and absent options are handled without changing them', () => {
  for (const value of [1, '1', 28, '28']) { const option = { value, label: 123 }; expect(Form.methods.filterDayOfMonth(String(value), option)).toBe(true); expect(option.value).toBe(value) }
  for (const option of [null, undefined, {}, { value: null }]) expect(Form.methods.filterDayOfMonth('28', option)).toBe(false)
  expect(Form.methods.filterDayOfMonth(null, { value: 28 })).toBe(true)
})
