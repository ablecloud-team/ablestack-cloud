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

import Template from '@/views/image/UpdateTemplate.vue'
import ISO from '@/views/image/UpdateISO.vue'
import { postAPI } from '@/api'
jest.mock('@/api', () => ({ getAPI: jest.fn(), postAPI: jest.fn() }))
const deferred = () => { let release; const promise = new Promise(resolve => { release = resolve }); return { promise, resolve: release } }
const context = C => {
  const c = { resource: { id: 'image-uuid', name: 'image', account: 'admin', domainid: 'domain', userdataid: 'old', userdatapolicy: 'ALLOWOVERRIDE' }, form: { name: 'retained' }, formRef: { value: { validate: jest.fn(() => Promise.resolve()), scrollToField: jest.fn() } }, detailsFields: [], emptyAllowedFields: [], canLinkUserData: true, userDataSelectionReady: true, loading: false, userdataid: 'new', userdatapolicy: 'APPEND', $store: { getters: { userInfo: { id: 'admin', roletype: 'Admin', account: 'admin', domainid: 'domain' }, project: {}, apis: { updateTemplate: {} } } }, $refs: {}, $nextTick: fn => fn(), $emit: jest.fn(), $t: key => key, $message: { success: jest.fn() }, isValidValueForKey: () => true }
  Object.entries(C.methods).forEach(([key, fn]) => { c[key] = fn.bind(c) }); if (C.computed?.typeContext)Object.defineProperty(c, 'typeContext', { get: () => C.computed.typeContext.call(c) }); return c
}
beforeEach(() => { jest.clearAllMocks(); postAPI.mockReset() })
describe.each([[Template, 'updateTemplate'], [ISO, 'updateIso']])('%s user-data linkage', (C, api) => {
  test('waits for the link with templateid for both template and ISO, then closes', async () => {
    const c = context(C); const wait = deferred(); const linkStarted = deferred()
    postAPI.mockImplementation(command => { if (command === api) return Promise.resolve({}); linkStarted.resolve(); return wait.promise })
    const pending = c.handleSubmit(); await linkStarted.promise
    expect(postAPI).toHaveBeenNthCalledWith(1, api, expect.objectContaining({ id: 'image-uuid', name: 'retained' }), { preserveOnFailure: true })
    expect(postAPI).toHaveBeenNthCalledWith(2, 'linkUserDataToTemplate', expect.objectContaining({ templateid: 'image-uuid', userdataid: 'new', userdatapolicy: 'APPEND', ignoreproject: true }), { preserveOnFailure: true })
    expect(c.$emit).not.toHaveBeenCalledWith('close-action'); expect(c.loading).toBe(true)
    c.closeAction(); expect(c.$emit).not.toHaveBeenCalledWith('close-action'); wait.resolve({}); await pending
    expect(c.$emit).toHaveBeenCalledWith('close-action'); expect(c.$emit).toHaveBeenCalledWith('refresh-data'); expect(c.loading).toBe(false)
  })
  test('partial link failure keeps draft and explains saved metadata, no false success', async () => {
    const c = context(C); c.$refs.error = { $el: { scrollIntoView: jest.fn(), focus: jest.fn() } }; postAPI.mockResolvedValueOnce({}).mockRejectedValueOnce(new Error('link 503'))
    await c.handleSubmit(); expect(c.submitError).toContain('message.userdata.image.partial'); expect(c.form.name).toBe('retained'); expect(c.$emit).not.toHaveBeenCalledWith('close-action'); expect(c.$message.success).not.toHaveBeenCalled(); expect(c.loading).toBe(false); expect(c.$refs.error.$el.scrollIntoView).toHaveBeenCalledWith({ block: 'nearest' }); expect(c.$refs.error.$el.focus).toHaveBeenCalled()
  })
  test('validation and click/Enter share one lock; invalid selection never submits', async () => {
    const c = context(C); const wait = deferred(); c.formRef.value.validate.mockReturnValue(wait.promise); postAPI.mockResolvedValue({})
    const pending = c.handleSubmit(); await c.handleSubmit(); expect(c.formRef.value.validate).toHaveBeenCalledTimes(1); wait.resolve(); await pending; expect(postAPI).toHaveBeenCalledTimes(2)
    c.userDataSelectionReady = false; postAPI.mockClear(); await c.handleSubmit(); expect(postAPI).not.toHaveBeenCalled()
  })
  test('unchanged link and unavailable link APIs never rewrite the link', async () => {
    const c = context(C); c.userdataid = 'old'; c.userdatapolicy = 'ALLOWOVERRIDE'; postAPI.mockResolvedValue({}); await c.handleSubmit(); expect(postAPI).toHaveBeenCalledTimes(1)
    postAPI.mockClear(); c.canLinkUserData = false; c.userDataSelectionReady = false; c.userdataid = 'other'; await c.handleSubmit(); expect(postAPI).toHaveBeenCalledTimes(1)
  })
  test('explicit none omits userdataid/policy while fixing project and image UUID', async () => {
    const c = context(C); c.userdataid = ''; c.resource.projectid = 'project'; postAPI.mockResolvedValue({}); await c.handleSubmit()
    const params = postAPI.mock.calls[1][1]; expect(params).toEqual({ templateid: 'image-uuid', ignoreproject: true, projectid: 'project' })
  })
})
