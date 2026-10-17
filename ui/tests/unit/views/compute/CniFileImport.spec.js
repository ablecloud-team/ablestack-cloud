// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements. See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership. The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License. You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied. See the License for the
// specific language governing permissions and limitations
// under the License.

import Import from '@/views/compute/CniFileImport'
import Content from '@/views/compute/UserDataContent'
import Register from '@/views/compute/RegisterUserData'
import { readCniFile } from '@/utils/cniFileImport'
import { getAPI } from '@/api'
jest.mock('@/utils/cniFileImport', () => ({ ...jest.requireActual('@/utils/cniFileImport'), readCniFile: jest.fn() }))
jest.mock('@/api', () => ({ getAPI: jest.fn() }))
function ctx () {
  const c = { disabled: false, maxLength: 32768, context: 'owner', $emit: jest.fn(), $refs: { file: { value: 'previous', click: jest.fn() } } }
  Object.assign(c, Import.data.call(c)); for (const [k, v] of Object.entries(Import.methods)) c[k] = v.bind(c); return c
}
const event = { target: { files: [{ name: 'same.yaml' }] } }
const draft = { name: 'same.yaml', encoded: 'eA==', bytes: 1, mode: 'text' }
beforeEach(() => jest.resetAllMocks())
test('file picker permits same-file reselection and cancel never applies a draft', async () => {
  const c = ctx(); c.choose('base64'); expect(c.mode).toBe('base64'); expect(c.$refs.file.value).toBe(''); expect(c.$refs.file.click).toHaveBeenCalled()
  await c.selected({ target: { files: [] } }); expect(readCniFile).not.toHaveBeenCalled(); expect(c.$emit).not.toHaveBeenCalled()
})
test('read publishes preview only, explicit apply publishes normalized content', async () => {
  const c = ctx(); readCniFile.mockResolvedValue(draft); await c.selected(event)
  expect(c.candidate).toBe(draft); expect(c.$emit).not.toHaveBeenCalledWith('apply', expect.anything()); c.apply(); expect(c.$emit).toHaveBeenCalledWith('apply', 'eA=='); expect(c.candidate).toBeNull()
})
test.each(['cancel', 'context', 'unmount'])('late read after %s cannot replace content', async reason => {
  const c = ctx(); let complete; readCniFile.mockReturnValue(new Promise(resolve => { complete = resolve })); const pending = c.selected(event)
  if (reason === 'cancel') c.cancel(); if (reason === 'context') { c.context = 'other'; Import.watch.context.call(c) } if (reason === 'unmount') Import.beforeUnmount.call(c)
  complete(draft); await pending; expect(c.candidate).toBeNull(); expect(c.$emit).not.toHaveBeenCalledWith('apply', expect.anything())
})
test('error keeps caller input untouched; valid reselection clears error', async () => {
  const c = ctx(); readCniFile.mockRejectedValueOnce(new Error('message.cni.file.read')); await c.selected(event); expect(c.error).toBe('message.cni.file.read'); expect(c.$emit).not.toHaveBeenCalledWith('apply', expect.anything())
  readCniFile.mockResolvedValue(draft); await c.selected(event); expect(c.error).toBe(''); c.cancel(); expect(c.candidate).toBeNull()
})
test('pending/disabled controls block duplicate reads and apply; current limit is checked again', async () => {
  const c = ctx(); c.disabled = true; await c.selected(event); c.choose('text'); expect(readCniFile).not.toHaveBeenCalled(); expect(c.$refs.file.click).not.toHaveBeenCalled()
  c.candidate = draft; c.apply(); expect(c.$emit).not.toHaveBeenCalledWith('apply', expect.anything()); c.disabled = false; c.maxLength = 3; c.apply(); expect(c.error).toBe('message.cni.validation.size')
})
test('CNI preview outside a detail route is local and independent of API/readiness', async () => {
  const c = { sourceType: 'cni', previewEncoded: 'eA==', resource: { id: 'preview' }, tab: 'cni.content', loading: false, resourceActionReady: () => false, $route: { path: '/cniconfiguration' }, $store: { getters: { apis: {}, userInfo: { id: 'user' } }, state: { user: {} } } }
  Object.assign(c, Content.data.call(c)); for (const [k, v] of Object.entries(Content.methods)) c[k] = v.bind(c); for (const [k, v] of Object.entries(Content.computed)) Object.defineProperty(c, k, { get: () => v.call(c) })
  await c.fetchData(); expect(c.active).toBe(true); expect(c.parentReadReady).toBe(true); expect(c.display).toBe('x'); expect(getAPI).not.toHaveBeenCalled()
})
test('apply stores canonical Base64 without changing name/variables and is guarded for ordinary UserData', () => {
  const c = { isUserData: false, busy: false, allowedApi: true, maxEncodedLength: 32768, form: { name: 'name', params: ['USER'] }, formRef: { value: {} }, $refs: {}, $nextTick: fn => fn() }
  Register.methods.applyCniFile.call(c, 'eA=='); expect(c.form).toEqual({ name: 'name', params: ['USER'], cniconfig: 'eA==', isbase64: true }); c.isUserData = true; Register.methods.applyCniFile.call(c, 'eQ=='); expect(c.form.cniconfig).toBe('eA==')
})

test('unmount always releases the parent read lock', () => {
  const c = ctx(); c.pending = true; Import.beforeUnmount.call(c)
  expect(c.disposed).toBe(true); expect(c.$emit).toHaveBeenCalledWith('reading', false)
})
