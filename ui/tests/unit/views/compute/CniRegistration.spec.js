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

import RegisterUserData from '@/views/compute/RegisterUserData'
import { canonicalCni, cniName, cniParams, cniReservedParams } from '@/utils/cniRegistration'
import { getAPI, postAPI } from '@/api'
jest.mock('@/api', () => ({ getAPI: jest.fn(), postAPI: jest.fn() }))
const flush = async () => { for (let i = 0; i < 12; i++) await Promise.resolve() }
const encoded = value => btoa(unescape(encodeURIComponent(value)))
const success = { registercniconfigurationresponse: { cniconfig: { id: 'cni-1', name: 'name' } } }
function context () {
  const ctx = { $route: { name: 'cniconfiguration', path: '/cniconfiguration' }, $t: key => key, $store: { getters: { project: null, userInfo: { id: 'u1', roletype: 'Admin' }, apis: { registerCniConfiguration: {} } } }, $refs: {}, $nextTick: callback => Promise.resolve().then(callback), $emit: jest.fn(), $message: { success: jest.fn() } }
  Object.assign(ctx, RegisterUserData.data.call(ctx))
  Object.entries(RegisterUserData.computed).forEach(([key, getter]) => Object.defineProperty(ctx, key, { get: () => getter.call(ctx) }))
  Object.entries(RegisterUserData.methods).forEach(([key, method]) => { ctx[key] = method.bind(ctx) })
  ctx.initForm()
  Object.assign(ctx.form, { name: '  name  ', cniconfig: '한글\n$<>"& eA==\n', isbase64: false, params: [' USER ', 'AS_NUMBER'] })
  ctx.formRef = { value: { validate: jest.fn().mockResolvedValue(), scrollToField: jest.fn() } }
  return ctx
}
beforeEach(() => jest.resetAllMocks())
test.each(['', ' ', '\t\n', '\u0085', '\uFEFF\u3000\u00a0'])('rejects empty Unicode content %j in both modes', text => {
  expect(() => canonicalCni(text, false)).toThrow('message.cni.validation.empty')
  expect(() => canonicalCni(encoded(text), true)).toThrow('message.cni.validation.')
})
test.each(['eA', 'eA==', 'eA%3D%3D', 'eA\n'])('normalizes Base64 %s', value => expect(canonicalCni(value, true)).toBe('eA=='))
test.each(['%', '%GG', 'A', 'eA=', 'eA===', 'eA==Yg==', '!!!!'])('rejects malformed Base64 %s', value => expect(() => canonicalCni(value, true)).toThrow('message.cni.validation.base64'))
test('preserves UTF-8, newlines, Base64-looking text, and binary bytes', () => {
  const text = '한글\n$<>"& eA==\n'
  expect(canonicalCni(text, false)).toBe(encoded(text))
  expect(canonicalCni('eA==', false)).toBe('ZUE9PQ==')
  expect(canonicalCni('_wAB', true)).toBe('/wAB')
  expect(() => canonicalCni('\uD800', false)).toThrow('message.cni.validation.text')
})
test.each([4096, 32768, 1048576])('canonical byte boundary includes padding at %i', max => {
  expect(canonicalCni('x'.repeat(max / 4 * 3), false, max).length).toBe(max)
  expect(() => canonicalCni('x'.repeat(max / 4 * 3 + 1), false, max)).toThrow('message.cni.validation.size')
})
test('name and variables match server normalization and permit AS_NUMBER', () => {
  expect(cniName('\u0085 이름 \uFEFF')).toBe('이름')
  expect(cniName('x'.repeat(255))).toHaveLength(255)
  for (const value of [' ', '\u0085', 'x'.repeat(256)]) expect(() => cniName(value)).toThrow('message.cni.validation.name')
  expect(cniParams([' USER ', 'user', 'env.name', 'test-key', 'AS_NUMBER'])).toBe('USER,user,env.name,test-key,AS_NUMBER')
  for (const params of [['x', ' x '], [' '], ['1x'], ['x=y'], ['a b']]) expect(() => cniParams(params)).toThrow('message.cni.validation.params')
  for (const key of cniReservedParams) expect(() => cniParams([' ' + key + ' '])).toThrow('message.cni.validation.reserved')
})
test('CNI validators are installed on the actual form rules', async () => {
  const ctx = context(); ctx.form.cniconfig = ' '; ctx.form.name = ' '; ctx.form.params = ['public-ipv4']
  await expect(ctx.rules.cniconfig[1].validator()).rejects.toThrow('message.cni.validation.empty')
  await expect(ctx.rules.name[1].validator()).rejects.toThrow('message.cni.validation.name')
  await expect(ctx.rules.params[0].validator()).rejects.toThrow('message.cni.validation.reserved')
})
test('capability sets the effective CNI limit; failure retains server-authoritative fallback', async () => {
  const ctx = context(); ctx.isAdminOrDomainAdmin = () => false
  getAPI.mockResolvedValueOnce({ listcapabilitiesresponse: { capability: { userdatamaxlength: 32768 } } })
  ctx.fetchData(); await flush(); expect(ctx.maxEncodedLength).toBe(32768); expect(ctx.limitLoaded).toBe(true)
  const failed = context(); failed.isAdminOrDomainAdmin = () => false; getAPI.mockRejectedValueOnce(new Error('network'))
  failed.fetchData(); await flush(); expect(failed.maxEncodedLength).toBe(1048576); expect(failed.limitLoaded).toBe(false)
})
test.each([false, true])('explicit ownership and canonical content, project=%s', async project => {
  const ctx = context(); ctx.domains = [{ id: null }, { id: 'd1' }]; ctx.form.domainid = 1; ctx.form.account = 'owner'
  if (project) ctx.$store.getters.project = { id: 'p1', name: 'project' }
  postAPI.mockResolvedValueOnce(success); await ctx.handleSubmit()
  expect(postAPI).toHaveBeenCalledWith('registerCniConfiguration', { name: 'name', cniconfig: encoded(ctx.form.cniconfig), params: 'USER,AS_NUMBER', ignoreproject: true, ...(project ? { projectid: 'p1' } : { domainid: 'd1', account: 'owner' }) }, { preserveOnFailure: true })
  expect(ctx.$emit.mock.calls).toEqual([['refresh-data'], ['close-action']])
})
test.each(['duplicate name', 'HTTP 503', 'Network Error'])('%s retains draft, ownership, focus and scroll; retry works', async reason => {
  const ctx = context(); ctx.$store.getters.project = { id: 'p1' }; ctx.form.isbase64 = true; ctx.form.cniconfig = 'eA=='
  const input = document.createElement('input'); document.body.appendChild(input); input.focus()
  const body = { scrollTop: 137 }; ctx.$refs.content = { closest: () => body }; const before = JSON.stringify(ctx.form)
  postAPI.mockImplementationOnce(async () => { body.scrollTop = 0; input.blur(); throw new Error(reason) })
  await ctx.handleSubmit(); await flush()
  expect(ctx.error).toBe(reason); expect(JSON.stringify(ctx.form)).toBe(before); expect(ctx.cniProject.id).toBe('p1')
  expect(body.scrollTop).toBe(137); expect(document.activeElement).toBe(input); expect(ctx.$emit).not.toHaveBeenCalled()
  postAPI.mockResolvedValueOnce(success); await ctx.handleSubmit(); expect(ctx.$emit).toHaveBeenCalledWith('close-action'); input.remove()
})
test('double submit and close are blocked synchronously during validation and POST', async () => {
  const ctx = context(); let validate, complete
  ctx.formRef.value.validate.mockReturnValue(new Promise(resolve => { validate = resolve }))
  postAPI.mockReturnValue(new Promise(resolve => { complete = resolve }))
  const pending = ctx.handleSubmit(); ctx.handleSubmit(); ctx.closeAction()
  expect(ctx.formRef.value.validate).toHaveBeenCalledTimes(1); expect(ctx.blocksNavigation).toBe(true); expect(ctx.$emit).not.toHaveBeenCalled()
  validate(); await flush(); ctx.handleSubmit(); ctx.closeAction(); expect(postAPI).toHaveBeenCalledTimes(1)
  complete(success); await pending; expect(ctx.busy).toBe(false); expect(ctx.$emit).toHaveBeenCalledWith('close-action')
})
test.each(['project', 'account', 'permission'])('scope mutation during validation blocks POST: %s', async kind => {
  const ctx = context(); let complete; ctx.formRef.value.validate.mockReturnValue(new Promise(resolve => { complete = resolve }))
  const pending = ctx.handleSubmit()
  if (kind === 'project') ctx.$store.getters.project = { id: 'other' }
  if (kind === 'account') ctx.form.account = 'other'
  if (kind === 'permission') ctx.$store.getters.apis = {}
  complete(); await pending; expect(postAPI).not.toHaveBeenCalled(); expect(ctx.$emit).not.toHaveBeenCalled(); expect(ctx.busy).toBe(false)
})
test('all-projects view cannot register into an ambiguous owner', async () => {
  const ctx = context(); ctx.$store.getters.listAllProjects = true; await ctx.handleSubmit()
  expect(ctx.error).toBe('message.cni.scope.changed'); expect(postAPI).not.toHaveBeenCalled()
})
test('late result after owner change is surfaced without closing another scope', async () => {
  const ctx = context(); let complete; postAPI.mockReturnValue(new Promise(resolve => { complete = resolve }))
  const pending = ctx.handleSubmit(); await flush(); ctx.$store.getters.project = { id: 'p2' }; complete(success); await pending
  expect(ctx.error).toBe('message.cni.scope.result.changed'); expect(ctx.$emit).not.toHaveBeenCalled()
})
test.each([{}, { registercniconfigurationresponse: { cniconfig: { id: 'i', name: 'other' } } }])('malformed success retains the draft and advises checking list', async response => {
  const ctx = context(); const before = JSON.stringify(ctx.form); postAPI.mockResolvedValue(response); await ctx.handleSubmit()
  expect(ctx.error).toBe('message.cni.response.invalid'); expect(JSON.stringify(ctx.form)).toBe(before); expect(ctx.$emit).not.toHaveBeenCalled()
})
