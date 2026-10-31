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

import { validBucketQuota, MAX_BUCKET_QUOTA_GIB, pendingBucketQuota, bucketChanges, quotaErrorField } from '@/utils/bucketQuota'
import { mount, flushPromises } from '@vue/test-utils'
import Context from '@/components/view/BucketQuotaContext'
import { getAPI } from '@/api'
jest.mock('@/api', () => ({ getAPI: jest.fn() }))

describe('bucket quota boundary and request contracts', () => {
  test.each([-1, 0, '', ' ', '1.5', '1e3', '0x10', 'NaN', null, undefined, '2147483648', '999999999999999999999'])('rejects %p', value => {
    expect(validBucketQuota(value)).toBe(false)
  })
  test.each([1, '2', MAX_BUCKET_QUOTA_GIB, '0001'])('accepts positive integer %p', value => expect(validBucketQuota(value)).toBe(true))
  const before = { quota: 1, encryption: false, versioning: false, policy: 'Private' }
  it('retains explicit false values and sends only actual changes', () => {
    expect(bucketChanges({ ...before, quota: '2' }, before)).toEqual({ quota: 2 })
    expect(bucketChanges({ ...before, encryption: false }, { ...before, encryption: true })).toEqual({ encryption: false })
  })
  it('does not resend legacy zero or negative quota during unrelated changes', () => {
    expect(bucketChanges({ ...before, quota: -1, policy: 'Public' }, { ...before, quota: -1 })).toEqual({ policy: 'Public' })
  })
  it('submits explicitly touched quota for backend validation', () => {
    expect(bucketChanges(before, before, true)).toEqual({ quota: 1 })
  })
  test.each(['Prepared', 'Applying', 'Committing', 'Compensating', 'NeedsReconcile'])('blocks edits during %s', state => {
    expect(pendingBucketQuota({ quotaoperationid: 'op', quotaoperationstate: state })).toBe(true)
  })
  test.each(['Succeeded', 'Failed'])('allows edits after terminal %s', state => {
    expect(pendingBucketQuota({ quotaoperationid: 'op', quotaoperationstate: state })).toBe(false)
  })
  it('maps only capacity/usage validation failures to quota field', () => {
    expect(quotaErrorField({ response: { data: { updatebucketresponse: { errorcode: 431, errortext: 'quota below live usage' } } } })).toBe(true)
    expect(quotaErrorField({ response: { data: { updatebucketresponse: { errorcode: 431, errortext: 'unknown bucket' } } } })).toBe(false)
  })
})
describe('capacity snapshot lifecycle', () => {
  const mountContext = storeId => mount(Context, { props: { storeId }, global: { mocks: { $t: key => key, $toLocaleDate: value => value }, stubs: { 'a-alert': { template: '<div><slot name="message"/><slot name="description"/></div>' } } } })
  it('subtracts committed allocations and durable pending reservations', async () => {
    getAPI.mockResolvedValue({ listobjectstoragepoolsresponse: { objectstore: [{ id: 'A', storagetotal: 10 * 1073741824, storageallocated: 3 * 1073741824, storagereserved: 2 * 1073741824 }] } })
    const view = mountContext('A'); await flushPromises()
    expect(view.vm.available).toBe(5); expect(view.vm.reserved).toBe(2); expect(view.vm.checked).toBeTruthy(); view.unmount()
  })
  it('discards a late capacity response after the selected store changes', async () => {
    let first
    getAPI.mockImplementationOnce(() => new Promise(resolve => { first = resolve }))
    getAPI.mockResolvedValueOnce({ listobjectstoragepoolsresponse: { objectstore: [{ id: 'B', storagetotal: 0 }] } })
    const view = mountContext('A'); await view.setProps({ storeId: 'B' }); await flushPromises()
    first({ listobjectstoragepoolsresponse: { objectstore: [{ id: 'A', storagetotal: 100 }] } }); await flushPromises()
    expect(view.vm.snapshot.id).toBe('B'); view.unmount()
  })
  it('shows unknown capacity when lookup fails without claiming availability', async () => {
    getAPI.mockRejectedValue(new Error('offline')); const view = mountContext('A'); await flushPromises()
    expect(view.vm.snapshot).toBeNull(); expect(view.vm.failed).toBe(true); view.unmount()
  })
})
