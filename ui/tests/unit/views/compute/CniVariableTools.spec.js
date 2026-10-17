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

import Tools from '@/views/compute/CniVariableTools'
import Create from '@/views/compute/CreateKubernetesCluster'
import { cniPreviewRequest, mergeCniVariables } from '@/utils/cniVariables'
import { postAPI } from '@/api'
jest.mock('@/api', () => ({ getAPI: jest.fn(), postAPI: jest.fn() }))
const payload = { userdata: 'eA==', format: 'plain', candidates: ['NEW'], params: ['OLD'], values: { OLD: '' }, missing: [], extra: [], unresolved: [], unsupported: [] }
const reply = data => ({ previewcniconfigurationresponse: { cnipreview: data } })
function ctx (mode = 'extract') {
  const c = { mode, encoded: 'eA==', resource: mode === 'extract' ? {} : { id: 'id', name: 'cni' }, params: ['OLD'], values: {}, networkId: '', asNumber: undefined, scope: 'owner', disabled: false, $store: { getters: { apis: { previewCniConfiguration: {} } }, state: { user: { token: 'token' } } }, $emit: jest.fn(), $t: key => key }
  Object.assign(c, Tools.data.call(c)); for (const [key, value] of Object.entries(Tools.methods)) c[key] = value.bind(c)
  for (const [key, value] of Object.entries(Tools.computed)) Object.defineProperty(c, key, { get: () => value.call(c) })
  return c
}
beforeEach(() => jest.resetAllMocks())
test('extraction previews without changing declarations and requires explicit apply', async () => {
  const c = ctx(); postAPI.mockResolvedValue(reply(payload)); await c.open()
  expect(c.params).toEqual(['OLD']); expect(c.$emit).not.toHaveBeenCalledWith('apply', expect.anything()); expect(c.selected).toEqual(['NEW'])
  c.apply(); expect(c.$emit).toHaveBeenCalledWith('apply', ['OLD', 'NEW']); expect(c.visible).toBe(false)
})
test.each(['scope', 'values', 'source', 'permission', 'cancel', 'unmount'])('late preview after %s cannot publish results', async reason => {
  const c = ctx(); let finish; postAPI.mockReturnValue(new Promise(resolve => { finish = resolve })); const pending = c.open()
  if (reason === 'scope') c.scope = 'other'; if (reason === 'values') c.values = { OLD: 'new' }; if (reason === 'source') c.encoded = 'eQ=='; if (reason === 'permission') c.$store.getters.apis = {}
  if (reason === 'cancel') c.reset(); else if (reason === 'unmount') Tools.beforeUnmount.call(c); else Tools.watch.snapshot.call(c)
  finish(reply(payload)); await pending; expect(c.result).toBeNull(); expect(c.visible).toBe(false); expect(c.$emit).toHaveBeenLastCalledWith('reading', false)
})
test('pending, disabled and missing capability cannot submit duplicate previews', async () => {
  const c = ctx(); c.pending = true; await c.open(); c.pending = false; c.disabled = true; await c.open(); c.disabled = false; c.$store.getters.apis = {}; await c.open(); expect(postAPI).not.toHaveBeenCalled()
})
test('read failure preserves caller input and can retry; identity mismatch is not displayed', async () => {
  const c = ctx('preview'); postAPI.mockRejectedValueOnce(new Error('503')); await c.open(); expect(c.error).toBe('503'); expect(c.params).toEqual(['OLD'])
  postAPI.mockResolvedValueOnce(reply({ ...payload, id: 'wrong' })); await c.open(); expect(c.result).toBeNull(); expect(c.error).toBe('message.cni.variables.failed')
  postAPI.mockResolvedValueOnce(reply({ ...payload, id: 'id' })); await c.open(); expect(c.result.id).toBe('id'); expect(c.rows).toEqual([{ key: 'OLD', value: '' }])
})
test('preview request preserves all value characters, empty values and automatic zero while skipping unset values', () => {
  const text = '한글, eq=x\n$1\\path'
  expect(cniPreviewRequest({ mode: 'preview', resource: { id: 'id' }, values: { VALUE: text, EMPTY: '', UNSET: undefined }, networkId: 'network', asNumber: 0 })).toEqual({ id: 'id', 'cniconfigdetails[0].VALUE': text, 'cniconfigdetails[1].EMPTY': '', networkid: 'network', asnumber: 0 })
})
test('selected names merge once, reserved or invalid names cannot be applied', () => {
  expect(mergeCniVariables(['A'], ['A', 'B'])).toEqual(['A', 'B']); expect(mergeCniVariables([' A '], ['A', ' B '])).toEqual(['A', 'B']); expect(() => mergeCniVariables([], ['local-ipv4'])).toThrow(); expect(() => mergeCniVariables([], ['bad key'])).toThrow()
})
test('create validates missing declared values but accepts explicitly empty strings and automatic AS', () => {
  const c = { form: { advancedmode: true, cniconfigurationid: 'id' }, cniConfigParams: [{ key: 'VALUE' }, { key: 'AS_NUMBER' }], cniConfigValues: {}, $message: { error: jest.fn() }, $t: key => key }
  Object.defineProperty(c, 'cniMissingValues', { get: () => Create.computed.cniMissingValues.call(c) })
  expect(Create.methods.validateCniValues.call(c)).toBe(false); expect(c.cniVariableErrors).toBe(true); c.cniConfigValues.VALUE = ''; expect(Create.methods.validateCniValues.call(c)).toBe(true)
  Create.methods.updateCniConfig.call(c, '0'); expect(c.cniConfigValues).toEqual({}); expect(c.cniConfigParams).toEqual([])
})

test('local declaration validation uses translated UI errors', async () => { const c = ctx(); c.$t = key => 'translated:' + key; c.params = ['local-ipv4']; await c.open(); expect(c.error).toBe('translated:message.cni.validation.reserved'); expect(postAPI).not.toHaveBeenCalled() })
