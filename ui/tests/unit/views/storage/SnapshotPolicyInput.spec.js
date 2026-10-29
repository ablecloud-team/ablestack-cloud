// Licensed to the Apache Software Foundation (ASF) under one or more
// contributor license agreements. See NOTICE for copyright ownership.
// Licensed under the Apache License, Version 2.0 (the "License");
// http://www.apache.org/licenses/LICENSE-2.0 . Distributed AS IS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND.
import { mount, flushPromises } from '@vue/test-utils'
import { reactive } from 'vue'
import FormSchedule from '@/views/storage/FormSchedule'
import { getAPI, postAPI } from '@/api'
jest.mock('@/api', () => ({ getAPI: jest.fn(), postAPI: jest.fn() }))
jest.mock('lodash/debounce', () => fn => fn)
jest.mock('@/utils/timezone', () => ({ timeZone: () => Promise.resolve([{ id: 'UTC', name: 'UTC' }]) }))
let wrappers; let store
function render () {
  const wrapper = mount(FormSchedule, {
    attachTo: document.body,
    props: { resource: { id: 'v', account: 'owner', domainid: 'domain' }, dataSource: [] },
    global: { mocks: { $store: store, $route: { path: '/snapshotpolicy' }, $t: (k, p) => k + (p?.max != null ? ` ${p.max}` : ''), $getApiParams: () => [], $notifyError: jest.fn(), $notification: { success: jest.fn() } } }
  })
  wrappers.push(wrapper); return wrapper
}
const fill = async w => { Object.assign(w.vm.form, { time: 0, maxsnaps: 1, timezone: 'UTC' }); await flushPromises() }
beforeEach(() => {
  wrappers = []
  store = reactive({ getters: { userInfo: { id: 'owner', roletype: 'User' }, project: {}, apis: { createSnapshotPolicy: {}, listCapabilities: {}, listAccounts: {}, listResourceLimits: {} } }, state: { user: { token: 'session' } } })
  getAPI.mockReset(); postAPI.mockReset(); postAPI.mockResolvedValue({})
  getAPI.mockImplementation((cmd, params) => Promise.resolve(cmd === 'listCapabilities'
    ? { listcapabilitiesresponse: { capability: { snapshotpolicymax: { hourly: 3, daily: 5, weekly: 11, monthly: 0 } } } }
    : cmd === 'listAccounts' ? { listaccountsresponse: { account: [{ name: 'owner', domainid: 'domain', accounttype: 0 }] } }
      : { listresourcelimitsresponse: { resourcelimit: [{ resourcetype: '3', domainid: 'domain', max: params.account ? 2 : 4, ...(params.account ? { account: 'owner' } : {}) }] } }))
})
afterEach(async () => { wrappers.forEach(w => w.unmount()); await flushPromises(); document.body.innerHTML = '' })

test('zero survives a real input blur and posts schedule=0', async () => {
  const w = render(); await fill(w)
  expect(w.findAll('.ant-form-item-required')).toHaveLength(3)
  await w.find('input.ant-input-number-input').setValue('0'); await w.find('input.ant-input-number-input').trigger('blur')
  expect(w.vm.form.time).toBe(0); await w.vm.handleSubmit()
  expect(postAPI).toHaveBeenCalledWith('createSnapshotPolicy', expect.objectContaining({ schedule: 0 }), { preserveOnFailure: true })
  expect(w.vm.policyMaximum).toBe(2); expect(w.find('[data-testid="policy-retention-help"]').text()).toContain('3')
})
test.each([-1, 60, 1.5])('invalid minute %s retains the entered value and makes no request', async time => {
  const w = render(); await fill(w); w.vm.form.time = time; await flushPromises(); await w.vm.handleSubmit()
  expect(w.vm.form.time).toBe(time); expect(postAPI).not.toHaveBeenCalled()
})
test.each([0, 1.5, 3])('invalid retention %s keeps the draft for correction', async maxsnaps => {
  const w = render(); await fill(w); w.vm.form.maxsnaps = maxsnaps; await flushPromises(); await w.vm.handleSubmit()
  expect(w.vm.form.maxsnaps).toBe(maxsnaps); expect(postAPI).not.toHaveBeenCalled()
  w.vm.form.maxsnaps = 2; await flushPromises(); await w.vm.handleSubmit(); expect(postAPI).toHaveBeenCalledTimes(1)
})
test('a server-side limit change preserves values and a corrected retry succeeds', async () => {
  const w = render(); await fill(w); w.vm.form.maxsnaps = 2
  postAPI.mockRejectedValueOnce(new Error('431 current limit 1'))
  await w.vm.handleSubmit(); expect(w.vm.form.time).toBe(0); expect(w.vm.form.maxsnaps).toBe(2); expect(w.vm.$notifyError).toHaveBeenCalled()
  w.vm.form.maxsnaps = 1; await flushPromises(); await w.vm.handleSubmit(); expect(postAPI).toHaveBeenCalledTimes(2)
})
test('permission loss or request failure shows unknown limits and still permits server validation', async () => {
  store.getters.apis = { createSnapshotPolicy: {} }; const w = render(); await fill(w)
  expect(w.vm.limitsIncomplete).toBe(true); expect(w.vm.policyMaximum).toBeNull(); expect(getAPI).not.toHaveBeenCalled()
  w.vm.form.maxsnaps = 9; await w.vm.handleSubmit(); expect(postAPI).toHaveBeenCalledTimes(1)
})
test('limits pending cannot submit and an old scope response cannot refill another resource', async () => {
  let release; getAPI.mockReturnValue(new Promise(resolve => { release = resolve }))
  const w = render(); Object.assign(w.vm.form, { time: 0, maxsnaps: 1, timezone: 'UTC' }); await w.vm.handleSubmit(); expect(postAPI).not.toHaveBeenCalled()
  await w.setProps({ resource: { id: 'another', account: 'other', domainid: 'other-domain' } }); release({ listcapabilitiesresponse: { capability: { snapshotpolicymax: { hourly: 1 } } } }); await flushPromises()
  expect(w.vm.policyLimits.maxima).toEqual({}); await w.vm.handleSubmit(); expect(postAPI).not.toHaveBeenCalled()
})
