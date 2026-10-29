// Licensed to the Apache Software Foundation (ASF) under one or more
// contributor license agreements. See NOTICE for ownership information.
// Licensed under the Apache License, Version 2.0 (the "License");
// http://www.apache.org/licenses/LICENSE-2.0 . Distributed AS IS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND.
import { mount, flushPromises } from '@vue/test-utils'
import { reactive } from 'vue'
import Recurring from '@/views/storage/RecurringSnapshotVolume'
import FormSchedule from '@/views/storage/FormSchedule'
import { getAPI, postAPI } from '@/api'
jest.mock('@/api', () => ({ getAPI: jest.fn(), postAPI: jest.fn() }))
jest.mock('lodash/debounce', () => fn => fn)
jest.mock('@/utils/timezone', () => ({ timeZone: () => Promise.resolve([{ id: 'UTC', name: 'UTC' }]) }))
let w; let store
const volume = id => ({ id, name: id, type: 'DATADISK', state: 'Ready', hypervisor: 'KVM', zoneid: 'zone', zonename: 'Zone', account: 'owner', domainid: 'domain' })
beforeEach(() => {
  store = reactive({ getters: { userInfo: { id: 'user', roletype: 'Admin' }, features: { kvmsnapshotenabled: true }, apis: { createSnapshotPolicy: {}, listVolumes: {}, listSnapshotPolicies: {}, listZones: {} }, project: {} }, state: { user: { token: 'session' } } })
  getAPI.mockReset(); postAPI.mockReset(); postAPI.mockResolvedValue({ createsnapshotpolicyresponse: { snapshotpolicy: { id: 'created' } } })
  getAPI.mockImplementation(command => Promise.resolve(command === 'listVolumes' ? { listvolumesresponse: { volume: [volume('a'), volume('b')], count: 2 } } : command === 'listZones' ? { listzonesresponse: { zone: [{ id: 'zone', name: 'Zone' }], count: 1 } } : { listsnapshotpoliciesresponse: { count: 0 } }))
  w = mount(Recurring, { attachTo: document.body, global: { provide: { parentFetchData: jest.fn() }, mocks: { $store: store, $route: { path: '/snapshotpolicy' }, $t: key => key, $getApiParams: () => [], $notifyError: jest.fn(), $notification: { success: jest.fn() } } } })
})
afterEach(async () => { w.unmount(); await flushPromises(); document.body.innerHTML = '' })
test('one form shows the volume and all schedule fields before selection and cannot submit without a volume', async () => {
  await flushPromises()
  expect(w.findAll('form')).toHaveLength(1)
  expect(w.find('#policy-create-volume').exists()).toBe(true)
  expect(w.findAll('.ant-radio-button-wrapper')).toHaveLength(4)
  expect(w.findAll('.ant-input-number')).toHaveLength(2)
  expect(w.find('[data-testid="policy-retention-help"]').exists()).toBe(false)
  const form = w.findComponent(FormSchedule)
  Object.assign(form.vm.form, { time: 0, maxsnaps: 1, timezone: 'UTC' }); await form.vm.handleSubmit()
  expect(postAPI).not.toHaveBeenCalled()
  expect(getAPI.mock.calls.every(call => call[0] === 'listVolumes')).toBe(true)
})
test('selection submits directly through the same form with the selected UUID and normal policy contract', async () => {
  await flushPromises(); w.vm.onVolumeChange('a'); await flushPromises()
  expect(w.findAll('form')).toHaveLength(1)
  const form = w.findComponent(FormSchedule)
  Object.assign(form.vm.form, { time: 59, maxsnaps: 1, timezone: 'UTC' }); await form.vm.handleSubmit()
  expect(postAPI).toHaveBeenCalledWith('createSnapshotPolicy', expect.objectContaining({ volumeid: 'a', schedule: 59, intervaltype: 'hourly' }), { preserveOnFailure: true })
})
test('changing the volume clears its draft and rejects an old policy response', async () => {
  await flushPromises(); let release
  getAPI.mockImplementationOnce(() => new Promise(resolve => { release = resolve }))
  w.vm.onVolumeChange('a'); await flushPromises()
  const old = w.findComponent(FormSchedule)
  old.vm.form.maxsnaps = 7; old.vm.tags = [{ key: 'private', value: 'a' }]
  w.vm.onVolumeChange('b'); await flushPromises()
  release({ listsnapshotpoliciesresponse: { count: 1, snapshotpolicy: [{ id: 'old', intervaltype: 0 }] } }); await flushPromises()
  const next = w.findComponent(FormSchedule)
  expect(next.props('resource').id).toBe('b'); expect(next.vm.form.maxsnaps).toBeUndefined(); expect(next.vm.tags).toEqual([])
  expect(w.vm.dataSource).toEqual([])
})
test('a pending create locks the volume selector and emits only one request', async () => {
  await flushPromises(); w.vm.onVolumeChange('a'); await flushPromises(); let release
  postAPI.mockImplementationOnce(() => new Promise(resolve => { release = resolve }))
  const form = w.findComponent(FormSchedule)
  Object.assign(form.vm.form, { time: 0, maxsnaps: 1, timezone: 'UTC' })
  const pending = form.vm.handleSubmit(); await flushPromises()
  w.vm.onVolumeChange('b'); await form.vm.handleSubmit()
  expect(w.vm.currentVolumeResource.id).toBe('a'); expect(postAPI).toHaveBeenCalledTimes(1)
  expect(w.find('#policy-create-volume').element.disabled).toBe(true)
  release({ createsnapshotpolicyresponse: { snapshotpolicy: { id: 'created' } } }); await pending
})
