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

import { flushPromises } from '@vue/test-utils'
import Settings from '@/views/image/TemplateSettingsTab.vue'
import { getAPI, postAPI } from '@/api'
import { templateSettingsParams, templateSettingsCanWrite } from '@/utils/templateSettings'
jest.mock('@/api', () => ({ getAPI: jest.fn(), postAPI: jest.fn() }))
const resource = { id: 'template', name: 'Test', account: 'owner', domainid: 'domain', details: { first: 'one', protected: 'keep' }, readonlydetails: 'protected' }
const copy = () => JSON.parse(JSON.stringify(resource))
function context () {
  const c = { ...Settings.data(), resource: copy(), source: copy(), loaded: true, $store: { getters: { userInfo: { id: 'user', account: 'owner', domainid: 'domain', roletype: 'User' }, project: {}, apis: { listTemplates: {}, updateTemplate: {}, listDetailOptions: {} } }, state: { user: { token: 'runtime' } } }, $emit: jest.fn(), $nextTick: fn => fn(), $t: k => k }
  Object.entries(Settings.methods).forEach(([k, v]) => { c[k] = v.bind(c) })
  Object.entries(Settings.computed).forEach(([k, v]) => Object.defineProperty(c, k, { get: () => v.call(c) }))
  return c
}
beforeEach(() => { jest.clearAllMocks(); postAPI.mockReset(); getAPI.mockReset(); getAPI.mockImplementation(api => Promise.resolve(api === 'listTemplates' ? { listtemplatesresponse: { template: [copy()] } } : { listdetailoptionsresponse: { detailoptions: { details: { first: [], custom: [] } } } })) })
test('replacement-map writes preserve untouched and read-only values and never mutate the resource', () => {
  const original = copy(); expect(templateSettingsParams(original, 'edit', 'first', 'two')).toEqual({ id: 'template', 'details[0].first': 'two', 'details[0].protected': 'keep' }); expect(original).toEqual(resource)
  expect(templateSettingsParams(original, 'delete', 'first')).toEqual({ id: 'template', 'details[0].protected': 'keep' })
  expect(() => templateSettingsParams(original, 'edit', 'protected', 'changed')).toThrow('readonly')
  expect(templateSettingsParams({ ...original, details: { first: 'one' } }, 'delete', 'first')).toEqual({ id: 'template', cleanupdetails: true })
})
test('owner and selected project can write but shared recipients, foreign DomainAdmin and withdrawn API cannot', () => {
  const c = context(); expect(c.canWrite).toBe(true); c.$store.getters.userInfo.account = 'recipient'; expect(c.canWrite).toBe(false)
  c.$store.getters.userInfo.roletype = 'DomainAdmin'; expect(c.canWrite).toBe(false)
  expect(templateSettingsCanWrite({ ...resource, projectid: 'p' }, { ...c.$store.getters, project: { id: 'p' } })).toBe(true)
  c.$store.getters.userInfo.roletype = 'Admin'; c.$store.getters.apis = {}; expect(c.canWrite).toBe(false)
})
test.each([['add', 'first', 'duplicate'], ['add', '', 'required'], ['edit', 'missing', 'changed'], ['edit', 'extraconfig.test', 'readonly']])('invalid %s draft %s sends no write', async (kind, name, error) => {
  const c = context(); c.open(kind, kind === 'add' ? null : { name, value: 'old' }); if (!c.dialog) c.dialog = kind; c.draftName = name; c.draftValue = 'new'; await c.save(); expect(postAPI).not.toHaveBeenCalled(); expect(c.dialogError).toBe('message.template.settings.' + error)
})
test('known server refusal keeps baseline and detached draft; a manual retry commits once', async () => {
  const c = context(); c.open('edit', { name: 'first', value: 'one' }); c.draftValue = 'two'; postAPI.mockRejectedValueOnce({ response: { status: 431 } }); await c.save(); expect(c.source).toEqual(resource); expect(c.draftValue).toBe('two'); expect(c.dialog).toBe('edit')
  postAPI.mockResolvedValueOnce({ updatetemplateresponse: { template: { ...copy(), details: { first: 'two', protected: 'keep' } } } }); await c.save(); expect(postAPI).toHaveBeenCalledTimes(2); expect(c.source.details.first).toBe('two'); expect(c.dialog).toBe(''); expect(c.$emit).toHaveBeenCalledWith('refresh')
})
test('concurrent external changes are detected before sending a replacement map', async () => {
  const c = context(); c.open('edit', { name: 'first', value: 'one' }); c.draftValue = 'two'; getAPI.mockResolvedValueOnce({ listtemplatesresponse: { template: [{ ...copy(), details: { first: 'third-party', protected: 'keep' } }] } }); await c.save(); expect(postAPI).not.toHaveBeenCalled(); expect(c.draftValue).toBe('two'); expect(c.dialogError).toContain('changed')
})
test('double submit and closing while pending cannot duplicate or abandon a write', async () => {
  const c = context(); c.open('edit', { name: 'first', value: 'one' }); c.draftValue = 'two'; let done; postAPI.mockImplementationOnce(() => new Promise(resolve => { done = resolve })); const first = c.save(); await flushPromises(); await c.save(); c.close(); expect(c.dialog).toBe('edit'); expect(postAPI).toHaveBeenCalledTimes(1); done({ updatetemplateresponse: { template: copy() } }); await first; expect(c.dialog).toBe('')
})
test('ambiguous outcome blocks POST replay and result-only observation recognizes applied data', async () => {
  const c = context(); c.open('edit', { name: 'first', value: 'one' }); c.draftValue = 'two'; postAPI.mockRejectedValueOnce(new Error('timeout')); await c.save(); expect(c.unknownOutcome).toBe(true); await c.save(); expect(postAPI).toHaveBeenCalledTimes(1)
  getAPI.mockResolvedValueOnce({ listtemplatesresponse: { template: [{ ...copy(), details: { first: 'two', protected: 'keep' } }] } }); await c.checkOutcome(); expect(c.dialog).toBe(''); expect(postAPI).toHaveBeenCalledTimes(1)
})
test('a late reply after a project or login scope change never populates the new view', async () => {
  const c = context(); c.open('edit', { name: 'first', value: 'one' }); let done; postAPI.mockImplementationOnce(() => new Promise(resolve => { done = resolve })); const pending = c.save(); await flushPromises(); getAPI.mockRejectedValueOnce({ response: { status: 531 } }); c.$store.getters.project = { id: 'other' }; Settings.watch.scope.call(c); done({ updatetemplateresponse: { template: copy() } }); await pending; await flushPromises(); expect(c.source).toEqual({}); expect(c.dialog).toBe(''); expect(c.$emit).not.toHaveBeenCalledWith('refresh')
})
test('read failure preserves existing rows while option failure disables mutation until refreshed', async () => {
  const c = context(); getAPI.mockRejectedValueOnce({ response: { status: 503 } }); await c.refresh(); expect(c.source.details).toEqual(resource.details); expect(c.readError).toBeTruthy()
  getAPI.mockResolvedValueOnce({ listtemplatesresponse: { template: [copy()] } }).mockRejectedValueOnce({ response: { status: 503 } }); await c.refresh(); expect(c.optionsFailed).toBe(true); c.open('add'); expect(c.dialog).toBe('')
})
test('reactivating a kept-alive settings tab reloads after deactivation cleared its scope', async () => {
  const c = context(); Settings.deactivated.call(c); expect(c.loaded).toBe(false); Settings.activated.call(c); await new Promise(resolve => setTimeout(resolve, 0)); expect(c.loaded).toBe(true)
})

test.each([undefined, '', ' , ', 'protected'])('add opens without treating an empty draft name as a readonly setting (%s)', readonlydetails => {
  const c = context(); c.source.readonlydetails = readonlydetails; c.open('add', null)
  expect(c.dialog).toBe('add'); expect(c.draftName).toBe(''); expect(postAPI).not.toHaveBeenCalled()
})

test('deleting the final row of a later page returns to the last populated page', () => {
  const c = context(); c.page = 2; c.source.details = Object.fromEntries(Array.from({ length: 10 }, (_, i) => ['key' + i, 'value']))
  Settings.watch['rows.length'].call(c)
  expect(c.page).toBe(1); expect(c.visibleRows).toHaveLength(10)
  c.source.details = {}; Settings.watch['rows.length'].call(c); expect(c.page).toBe(1); expect(c.visibleRows).toEqual([])
})
test('multi-video replacement retains all device RAM pairs and unrelated metadata, refuses protected replacements', () => {
  const original = { ...copy(), details: { ...resource.details, 'video.hardware': 'vga', 'video.ram': '8192', 'video.hardware4': 'vga', 'video.ram4': '8192' } }
  const params = templateSettingsParams(original, 'add', 'video.hardware', 'virtio', 2)
  expect(params).toEqual({ id: 'template', 'details[0].first': 'one', 'details[0].protected': 'keep', 'details[0].video.hardware': 'virtio', 'details[0].video.ram': '16384', 'details[0].video.hardware2': 'virtio', 'details[0].video.ram2': '16384' })
  expect(original.details['video.hardware4']).toBe('vga')
  expect(() => templateSettingsParams({ ...original, readonlydetails: 'video.ram4' }, 'add', 'video.hardware', 'virtio', 2)).toThrow('readonly')
  for (const count of [0, 5, 1.5, null]) expect(() => templateSettingsParams(original, 'add', 'video.hardware', 'virtio', count)).toThrow('count')
})
