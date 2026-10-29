// Licensed to the Apache Software Foundation (ASF) under one or more
// contributor license agreements. See the NOTICE file distributed with this
// work for additional information regarding copyright ownership. The ASF
// licenses this file to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance with the License.
// You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0 .
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
// WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
// License for the specific language governing permissions and limitations.
import { shallowMount, flushPromises } from '@vue/test-utils'
import Schedule from '@/views/storage/FormSchedule'
import TakeSnapshot from '@/views/storage/TakeSnapshot'
import { getAPI, postAPI } from '@/api'

jest.mock('@/api', () => ({ getAPI: jest.fn(), postAPI: jest.fn() }))
jest.mock('lodash/debounce', () => fn => fn)
jest.mock('@/utils/storageSnapshotContext', () => ({ ...jest.requireActual('@/utils/storageSnapshotContext'), freezeSnapshotTarget: jest.fn(() => null), freshStorageSnapshotContext: jest.fn(), snapshotSecurityScope: jest.fn(() => 'test-scope') }))
jest.mock('@/utils/timezone', () => ({ timeZone: () => Promise.resolve([]) }))

const select = { props: ['loading', 'disabled'], template: '<div :class="{ loading }" :data-disabled="disabled"><slot /></div>' }
const mounts = []
function dialog (component, extra = {}) {
  const wrapper = shallowMount(component, {
    props: { resource: { id: 'volume', zoneid: 'native', zonename: 'Native' }, ...(component === Schedule ? { dataSource: [], resourceType: 'Volume' } : {}), ...extra },
    global: {
      renderStubDefaultSlot: true,
      stubs: { 'a-select': select },
      mocks: {
        $t: k => k,
        $route: { path: '/volume/volume' },
        $store: { getters: { userInfo: { id: 'user' }, project: {}, apis: {} }, state: { user: {} } },
        $getApiParams: () => ({ asyncbackup: { description: 'Async backup' } })
      }
    }
  })
  mounts.push(wrapper)
  return wrapper
}
beforeEach(() => { getAPI.mockReset(); postAPI.mockReset() })
afterEach(() => { mounts.splice(0).forEach(w => w.unmount()) })

describe.each([Schedule, TakeSnapshot])('$name additional zones', component => {
  test('loading changes reactively after a delayed empty response, including when no other state changes', async () => {
    let complete
    getAPI.mockReturnValueOnce(new Promise(resolve => { complete = resolve }))
    const wrapper = dialog(component)
    await flushPromises()
    expect(wrapper.find('#zone-selection').classes()).toContain('loading')
    complete({ listzonesresponse: { count: 0 } })
    await flushPromises()
    expect(wrapper.find('#zone-selection').classes()).not.toContain('loading')
    expect(wrapper.vm.zoneLoading).toBe(false)
    expect(wrapper.vm.zoneError).toBe(false)
    expect(wrapper.vm.zones).toEqual([])
  })

  test('all pages are read and the native and Edge zones are excluded', async () => {
    getAPI.mockResolvedValueOnce({ listzonesresponse: { count: 4, zone: [{ id: 'native' }, { id: 'edge', type: 'Edge' }] } })
      .mockResolvedValueOnce({ listzonesresponse: { count: 4, zone: [{ id: 'other' }, { id: 'last' }] } })
    const wrapper = dialog(component); await flushPromises()
    expect(wrapper.vm.zones.map(row => row.id)).toEqual(['other', 'last'])
    expect(getAPI.mock.calls[1]).toEqual(['listZones', { showicon: true, page: 2, pagesize: 100 }, { preserveOnFailure: true, timeout: 20000 }])
    expect(wrapper.vm.zoneLoading).toBe(false)
    expect(getAPI.mock.calls.every(([command]) => command === 'listZones')).toBe(true)
  })

  test.each([new Error('503'), new Error('timeout of 20000ms exceeded'), { listzonesresponse: null }])('failed or malformed response stops loading and retry recovers', async failure => {
    if (failure instanceof Error) getAPI.mockRejectedValueOnce(failure)
    else getAPI.mockResolvedValueOnce(failure)
    const wrapper = dialog(component); await flushPromises()
    expect(wrapper.vm.zoneLoading).toBe(false); expect(wrapper.vm.zoneError).toBe(true)
    expect(wrapper.find('#zone-selection').attributes('data-disabled')).toBe('true')
    getAPI.mockResolvedValueOnce({ listzonesresponse: { zone: [{ id: 'retry' }] } })
    await wrapper.vm.fetchZoneData(); await flushPromises()
    expect(wrapper.vm.zoneError).toBe(false); expect(wrapper.vm.zoneLoading).toBe(false)
    expect(wrapper.vm.zones).toEqual([{ id: 'retry' }])
  })

  test('a late reply cannot repopulate another volume or a closed dialog', async () => {
    let complete
    getAPI.mockReturnValueOnce(new Promise(resolve => { complete = resolve }))
    const wrapper = dialog(component)
    await wrapper.setProps({ resource: { id: 'second', zoneid: 'different' } })
    complete({ listzonesresponse: { zone: [{ id: 'old-private' }] } }); await flushPromises()
    expect(wrapper.vm.zones).toEqual([]); expect(wrapper.vm.zoneLoading).toBe(false)
    let release
    getAPI.mockReturnValueOnce(new Promise(resolve => { release = resolve }))
    const pending = wrapper.vm.fetchZoneData(); wrapper.unmount()
    release({ listzonesresponse: { zone: [{ id: 'closed' }] } }); await pending
    expect(wrapper.vm.zones).toEqual([])
  })

  test('unsupported replication and pool controls are absent', async () => {
    getAPI.mockResolvedValueOnce({ listzonesresponse: {} })
    const wrapper = dialog(component); await flushPromises()
    expect(wrapper.find('[name="useStorageReplication"]').exists()).toBe(false)
    expect(wrapper.find('#storagepool-selection').exists()).toBe(false)
    expect(getAPI.mock.calls.some(([command]) => command === 'listStoragePools')).toBe(false)
  })

  test('submission explicitly disables replication and ignores obsolete pool/replication values', async () => {
    postAPI.mockReturnValue(new Promise(() => {}))
    const vm = {
      actionLoading: false,
      disabled: false,
      dialogCurrent: () => true,
      formRef: { value: { validate: () => Promise.resolve() } },
      form: { intervaltype: 'hourly', time: 55, timezone: 'UTC', maxsnaps: 2, name: 'own-snapshot', zoneids: ['additional'], useStorageReplication: true, storageids: ['unsupported'] },
      handleRemoveFields: values => ({ ...values }),
      volumeId: 'volume',
      resource: { id: 'volume' },
      tags: [],
      $store: { getters: { apis: { createSnapshotPolicy: {}, createSnapshot: {} } } },
      $t: k => k
    }
    component.methods.handleSubmit.call(vm); await flushPromises()
    expect(postAPI).toHaveBeenCalledTimes(1)
    expect(postAPI.mock.calls[0][1]).toMatchObject({ useStorageReplication: false, zoneids: 'additional' })
    expect(postAPI.mock.calls[0][1]).not.toHaveProperty('storageids')
  })
})

test('non-volume schedules do not query zones', async () => {
  dialog(Schedule, { resourceType: 'VM' }); await flushPromises()
  expect(getAPI).not.toHaveBeenCalled()
})

test('snapshot creation disables its submit control during unsafe source validation', async () => {
  getAPI.mockResolvedValue({ listzonesresponse: {} })
  const wrapper = dialog(TakeSnapshot); await flushPromises()
  await wrapper.setData({ snapshotSafetyTarget: { id: 'volume', kind: 'volume', record: {} }, snapshotSafetyLoading: true })
  expect(wrapper.findComponent({ ref: 'submit' }).props('disabled')).toBe(true)
})
