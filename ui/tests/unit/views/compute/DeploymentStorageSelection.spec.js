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

/** @jest-environment jsdom */
import { shallowMount, flushPromises } from '@vue/test-utils'
import { nextTick } from 'vue'
import DiskOfferingSelection from '@/views/compute/wizard/DiskOfferingSelection.vue'
import Selection from '@/views/compute/wizard/DeploymentStorageSelection.vue'
import { getAPI } from '@/api'

jest.mock('@/api', () => ({ getAPI: jest.fn() }))

const pool = { id: 'pool', name: 'Primary', suitable: true, requiredbytes: 1073741824 }
const query = { zoneid: 'zone', templateid: 'image', serviceofferingid: 'compute', hypervisor: 'KVM', size: 17 }

beforeEach(() => {
  jest.useFakeTimers()
  getAPI.mockReset()
  getAPI.mockResolvedValue({ listdeploymentstoragepoolsresponse: { deploymentstoragepool: [pool] } })
})
afterEach(() => jest.useRealTimers())

function mount (value = {}) {
  return shallowMount(Selection, {
    props: { title: 'Storage', query, value },
    global: { mocks: { $t: key => key }, stubs: { 'a-button': true, 'a-input-search': true, 'a-radio': true, 'a-table': true, 'a-alert': true } }
  })
}

test('equivalent query replacements do not refetch or invalidate the selected pool', async () => {
  const wrapper = mount({ id: 'pool', name: 'Primary', valid: true })
  jest.advanceTimersByTime(200)
  await flushPromises()
  expect(getAPI).toHaveBeenCalledTimes(1)
  await wrapper.setProps({ query: { ...query } })
  await nextTick()
  jest.advanceTimersByTime(1000)
  await flushPromises()
  expect(getAPI).toHaveBeenCalledTimes(1)
  expect(wrapper.vm.valid).toBe(true)
  wrapper.unmount()
})

test('size changes keep table rows and selected identity while revalidating', async () => {
  const wrapper = mount({ id: 'pool', name: 'Primary', valid: true })
  jest.advanceTimersByTime(200)
  await flushPromises()
  let finish
  getAPI.mockImplementationOnce(() => new Promise(resolve => { finish = resolve }))
  await wrapper.setProps({ query: { ...query, size: 23 } })
  jest.advanceTimersByTime(200)
  await nextTick()
  expect(wrapper.vm.pools).toEqual([pool])
  expect(wrapper.props('value').id).toBe('pool')
  finish({ listdeploymentstoragepoolsresponse: { deploymentstoragepool: [{ ...pool, suitable: false }] } })
  await flushPromises()
  expect(wrapper.vm.valid).toBe(false)
  expect(wrapper.emitted('update:value').slice(-1)[0][0]).toMatchObject({ id: 'pool', valid: false })
  wrapper.unmount()
})

test('pending disk size keeps the selection without a misleading reselect warning', async () => {
  getAPI.mockResolvedValue({ listdeploymentstoragepoolsresponse: { deploymentstoragepool: [{ ...pool, requiredbytes: null, suitable: false }] } })
  const wrapper = mount({ id: 'pool', name: 'Primary', valid: false })
  jest.advanceTimersByTime(200)
  await flushPromises()
  expect(wrapper.vm.selectedSizePending).toBe(true)
  expect(wrapper.find('a-alert-stub[type="warning"]').exists()).toBe(false)
  wrapper.unmount()
})

test('root override mounts with the already loaded offerings', () => {
  const wrapper = shallowMount(DiskOfferingSelection, {
    props: {
      isRootDiskOffering: true,
      items: [{ id: 'custom', name: 'Custom', iscustomized: true }, { id: 'fixed', name: 'Fixed', disksize: 17 }]
    },
    global: { mocks: { $t: key => key }, stubs: { 'a-input-search': true, 'a-table': true, 'a-pagination': true } }
  })
  expect(wrapper.vm.tableSource.map(item => item.key)).toEqual(['custom', 'fixed'])
  expect(wrapper.vm.rowCountNum).toBe(2)
  wrapper.unmount()
})

test('mounted offering selector retains the selected root offering across filtering', async () => {
  const custom = { id: 'custom', name: 'Custom', iscustomized: true }
  const fixed = { id: 'fixed', name: 'Fixed', disksize: 200 }
  const wrapper = shallowMount(DiskOfferingSelection, {
    props: { zoneId: 'zone', isRootDiskOffering: true, items: [custom, fixed], value: 'custom' },
    global: { mocks: { $t: key => key }, stubs: { 'a-input-search': true, 'a-table': true, 'a-pagination': true } }
  })
  expect(wrapper.vm.rowSelection.selectedRowKeys).toEqual(['custom'])
  await wrapper.setProps({ loading: true })
  await wrapper.setProps({ items: [custom], loading: false })
  expect(wrapper.vm.rowSelection.selectedRowKeys).toEqual(['custom'])
  expect(wrapper.emitted('select-disk-offering-item').flat()).not.toContain('0')
  await wrapper.setProps({ value: 'fixed', items: [custom, fixed] })
  expect(wrapper.vm.rowSelection.selectedRowKeys).toEqual(['fixed'])
  expect(wrapper.emitted('on-selected-root-disk-size').slice(-1)[0][0]).toEqual(fixed)
  await wrapper.setProps({ value: '' })
  expect(wrapper.vm.rowSelection.selectedRowKeys).toEqual(['0'])
  wrapper.unmount()
})

test('automatic placement becomes valid only after a suitable pool is returned', async () => {
  const wrapper = mount()
  expect(wrapper.emitted('update:value').slice(-1)[0][0]).toEqual({ valid: false })
  jest.advanceTimersByTime(200)
  await flushPromises()
  expect(wrapper.emitted('update:value').slice(-1)[0][0]).toEqual({ valid: true })
  wrapper.unmount()
})

test('no eligible pools blocks automatic placement and exposes a recovery notice', async () => {
  getAPI.mockResolvedValue({ listdeploymentstoragepoolsresponse: {} })
  const wrapper = mount()
  jest.advanceTimersByTime(200)
  await flushPromises()
  expect(wrapper.emitted('update:value').slice(-1)[0][0]).toEqual({ valid: false })
  expect(wrapper.vm.automaticUnavailable).toBe(true)
  expect(wrapper.find('a-alert-stub[type="warning"]').attributes('message')).toBe('message.vm.storage.auto.unavailable')
  wrapper.unmount()
})

test('known capacity or IOPS shortage blocks automatic placement', async () => {
  getAPI.mockResolvedValue({ listdeploymentstoragepoolsresponse: { deploymentstoragepool: [{ ...pool, suitable: false, unsuitablereason: 'iops' }] } })
  const wrapper = mount()
  jest.advanceTimersByTime(200)
  await flushPromises()
  wrapper.vm.select(null)
  expect(wrapper.emitted('update:value').slice(-1)[0][0]).toEqual({ valid: false })
  expect(wrapper.vm.automaticUnavailable).toBe(true)
  wrapper.unmount()
})

test('unknown required capacity remains pending rather than declaring no capacity', async () => {
  getAPI.mockResolvedValue({ listdeploymentstoragepoolsresponse: { deploymentstoragepool: [{ ...pool, suitable: false, requiredbytes: null }] } })
  const wrapper = mount()
  jest.advanceTimersByTime(200)
  await flushPromises()
  expect(wrapper.emitted('update:value').slice(-1)[0][0]).toEqual({ valid: false })
  expect(wrapper.vm.automaticUnavailable).toBe(false)
  wrapper.unmount()
})

test('failed storage lookup blocks automatic placement and a refresh recovers it', async () => {
  getAPI.mockRejectedValueOnce(new Error('storage lookup failed'))
  const wrapper = mount()
  jest.advanceTimersByTime(200)
  await flushPromises()
  expect(wrapper.emitted('update:value').slice(-1)[0][0]).toEqual({ valid: false })
  expect(wrapper.find('a-alert-stub[type="error"]').exists()).toBe(true)
  await wrapper.vm.fetchPools()
  expect(wrapper.emitted('update:value').slice(-1)[0][0]).toEqual({ valid: true })
  expect(wrapper.vm.error).toBe(false)
  wrapper.unmount()
})

test('changed conditions invalidate automatic placement until the new lookup finishes', async () => {
  const wrapper = mount()
  jest.advanceTimersByTime(200)
  await flushPromises()
  let finish
  getAPI.mockImplementationOnce(() => new Promise(resolve => { finish = resolve }))
  await wrapper.setProps({ query: { ...query, size: 23 } })
  expect(wrapper.emitted('update:value').slice(-1)[0][0]).toEqual({ valid: false })
  jest.advanceTimersByTime(200)
  await nextTick()
  expect(wrapper.vm.pools).toEqual([pool])
  finish({ listdeploymentstoragepoolsresponse: { deploymentstoragepool: [pool] } })
  await flushPromises()
  expect(wrapper.emitted('update:value').slice(-1)[0][0]).toEqual({ valid: true })
  wrapper.unmount()
})
