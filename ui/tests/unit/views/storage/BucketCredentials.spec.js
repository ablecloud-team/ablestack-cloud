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

import { mount, flushPromises } from '@vue/test-utils'
import { reactive } from 'vue'
import Credentials from '@/views/storage/BucketCredentials'
import Replace from '@/views/infra/ReplaceObjectStorageCredentials'
import { postAPI } from '@/api'
import { clearBucketS3Sessions } from '@/utils/bucketCredentialMemory'
jest.mock('@/api', () => ({ postAPI: jest.fn() }))
const meta = (extra = {}) => ({ id: 'bucket', url: 'http://store/bucket', accesskeyhint: '********CESS', credentialrevision: 1, credentialstate: 'Verified', affectedbuckets: [{ id: 'bucket', name: 'bucket' }], ...extra })
const reply = extra => ({ getbucketcredentialsresponse: { bucketcredential: meta(extra) } })
let view, store
const open = async (component = Credentials) => {
  store = reactive({ getters: { apis: { rotateBucketCredentials: {} }, project: { id: 'project' }, userInfo: { id: 'user' } }, state: { user: { token: 'session' } } })
  view = mount(component, { props: { resource: { id: 'bucket', provider: 'Glue', credentialrevision: 1 } }, global: { mocks: { $notification: { success: jest.fn() }, $t: key => key, $toLocaleDate: String, $route: { path: '/buckets/bucket' }, $store: store } } })
  await flushPromises(); return view
}
beforeEach(() => { postAPI.mockReset(); postAPI.mockResolvedValue(reply()) })
afterEach(() => { if (view) view.unmount(); view = null; jest.useRealTimers() })
it('opens masked metadata without requesting or rendering real keys or rotating', async () => {
  await open()
  expect(postAPI).toHaveBeenCalledWith('getBucketCredentials', { id: 'bucket', reveal: false }, { preserveOnFailure: true })
  expect(view.vm.keys).toBeNull()
  expect(view.findAll('input').every(input => input.attributes('type') !== 'text' || input.attributes('role') === 'spinbutton')).toBe(true)
  expect(postAPI.mock.calls.some(call => call[0] === 'rotateBucketCredentials')).toBe(false)
})
it('explicit retrieval keeps keys out of ordinary metadata and hides them after 30 seconds', async () => {
  jest.useFakeTimers(); await open()
  postAPI.mockResolvedValueOnce(reply({ accesskey: 'PRIVATE_ACCESS', usersecretkey: 'PRIVATE_SECRET' }))
  await view.vm.load(true); await flushPromises()
  expect(view.vm.keys.accesskey).toBe('PRIVATE_ACCESS')
  expect(JSON.stringify(view.vm.metadata)).not.toContain('PRIVATE_')
  jest.advanceTimersByTime(30001); expect(view.vm.keys).toBeNull()
})
it('discards a reveal response arriving after account/project context change', async () => {
  await open(); let finish
  postAPI.mockImplementationOnce(() => new Promise(resolve => { finish = resolve }))
  const pending = view.vm.load(true); store.getters.project.id = 'other'; await flushPromises()
  finish(reply({ accesskey: 'PRIVATE_ACCESS', usersecretkey: 'PRIVATE_SECRET' })); await pending; await flushPromises()
  expect(view.vm.keys).toBeNull(); expect(view.vm.metadata).toBeNull(); expect(view.emitted('close-action')).toBeTruthy()
})
it('discards revealed keys when credential revision changes', async () => {
  await open(); postAPI.mockResolvedValueOnce(reply({ accesskey: 'PRIVATE_ACCESS', usersecretkey: 'PRIVATE_SECRET' }))
  await view.vm.load(true); postAPI.mockResolvedValueOnce(reply({ credentialrevision: 2 }))
  await view.setProps({ resource: { id: 'bucket', provider: 'Glue', credentialrevision: 2 } }); await flushPromises()
  expect(view.vm.keys).toBeNull(); expect(view.vm.metadata.credentialrevision).toBe(2)
})
it('requires affected-scope acknowledgement and validates grace before rotation', async () => {
  await open(); await view.vm.rotate('rotate')
  expect(postAPI).toHaveBeenCalledTimes(1)
  view.vm.acknowledged = true; view.vm.grace = 0; await view.vm.rotate('rotate')
  expect(postAPI).toHaveBeenCalledTimes(1)
  view.vm.grace = 5; await view.vm.rotate('rotate')
  expect(postAPI).toHaveBeenCalledWith('rotateBucketCredentials', expect.objectContaining({ id: 'bucket', expectedrevision: 1, graceperiod: 5, action: 'rotate' }), { preserveOnFailure: true })
})
it('recovers using the same operation ID and never sends key material', async () => {
  postAPI.mockResolvedValueOnce(reply({ rotationstate: 'Prepared', rotationid: 'op', recoveryrequired: true }))
  await open(); await view.vm.rotate('retry')
  const call = postAPI.mock.calls.find(call => call[0] === 'rotateBucketCredentials')
  expect(call[1]).toEqual({ id: 'bucket', action: 'retry', expectedrevision: 1, rotationid: 'op', graceperiod: 300 })
  expect(JSON.stringify(call)).not.toMatch(/accesskey|secretkey/)
})
it('administrator replacement submits only POST details and wipes inputs after failure', async () => {
  await open(Replace); postAPI.mockRejectedValue(new Error('PRIVATE_SECRET'))
  view.vm.access = 'PRIVATE_ACCESS'; view.vm.secret = 'PRIVATE_SECRET'; view.vm.acknowledged = true
  await view.vm.submit()
  expect(postAPI).toHaveBeenCalledWith('updateObjectStoragePool', { id: 'bucket', 'details[0].key': 'accesskey', 'details[0].value': 'PRIVATE_ACCESS', 'details[1].key': 'secretkey', 'details[1].value': 'PRIVATE_SECRET' }, { preserveOnFailure: true })
  expect(view.vm.access).toBe(''); expect(view.vm.secret).toBe('')
  expect(view.vm.errorText).toBe('message.objectstore.credentials.failed'); expect(view.emitted('close-action')).toBeFalsy()
})

it('revokes preview and a late response immediately when logout starts', async () => {
  await open(); postAPI.mockResolvedValueOnce(reply({ accesskey: 'PRIVATE_ACCESS', usersecretkey: 'PRIVATE_SECRET' }))
  await view.vm.load(true); expect(view.vm.keys).not.toBeNull()
  clearBucketS3Sessions(); expect(view.vm.keys).toBeNull()
})
it('clears pending administrator input on cross-tab identity changes', async () => {
  await open(Replace); view.vm.access = 'PRIVATE_ACCESS'; view.vm.secret = 'PRIVATE_SECRET'
  window.dispatchEvent(new StorageEvent('storage', { key: 'ls__Access-Token' }))
  expect(view.vm.access).toBe(''); expect(view.vm.secret).toBe('')
})

it.each([Credentials, Replace])('associates credential input labels with actual password controls for %p', async component => {
  await open(component)
  const inputs = view.findAll('input[type="password"]')
  expect(inputs).toHaveLength(2)
  for (const input of inputs) {
    expect(input.attributes('id')).toBeTruthy()
    expect(view.find('label[for="' + input.attributes('id') + '"]').exists()).toBe(true)
  }
})
it('offers an explicit first-load retry and clears stale error after successful retrieval', async () => {
  postAPI.mockRejectedValueOnce(new Error('unavailable'))
  await open()
  expect(view.vm.metadata).toBeNull()
  expect(view.vm.errorText).toBe('message.bucket.credentials.fetch.failed')
  const retry = view.findAll('button').find(button => button.text() === 'label.retry')
  expect(retry).toBeDefined()
  await retry.trigger('click'); await flushPromises()
  expect(view.vm.metadata.id).toBe('bucket'); expect(view.vm.errorText).toBe('')
})
