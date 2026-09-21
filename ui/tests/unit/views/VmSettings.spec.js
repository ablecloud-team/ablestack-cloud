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

import { settingRestriction, settingRows, settingsParams, settingsFingerprint } from '@/utils/vmSettings'
import VmSettingsTab from '@/views/compute/VmSettingsTab.vue'
import { getAPI, postAPI } from '@/api'
jest.mock('@/api', () => ({ getAPI: jest.fn(), postAPI: jest.fn() }))
const vm = (details = {}) => ({ id: 'vm', state: 'Stopped', hypervisor: 'KVM', details })
describe('VM settings payload protection', () => {
  it('preserves unrelated values and never mutates the source', () => {
    const source = vm({ a: '1', b: '2' })
    expect(settingsParams(source, null, 'edit', 'a', '3', 1, true)).toEqual({ id: 'vm', 'details[0].a': '3', 'details[0].b': '2' })
    expect(source.details.a).toBe('1')
    expect(settingsParams(source, null, 'delete', 'a', '', 1, true)).toEqual({ id: 'vm', 'details[0].b': '2' })
  })
  it('keeps protected TPM when deleting another setting', () => {
    const source = vm({ a: '1', 'virtual.tpm.model': 'tpm-crb', 'virtual.tpm.version': '2.0' })
    expect(settingsParams(source, null, 'delete', 'a', '', 1, true)['details[0].virtual.tpm.model']).toBe('tpm-crb')
    expect(() => settingsParams(source, null, 'delete', 'virtual.tpm.model', '', 1, true)).toThrow('tpm')
    expect(settingRows(source).find(r => r.name === 'TPM').value).toBe('tpm-crb / 2.0')
  })
  it('does not silently remove extraconfig from a replacement map', () => {
    expect(settingsParams(vm({ a: '1', extraconfig: 'protected' }), null, 'edit', 'a', '2', 1, true)['details[0].extraconfig']).toBe('protected')
  })
  it('only uses cleanup for the final unprotected setting', () => {
    expect(settingsParams(vm({ a: '1' }), null, 'delete', 'a', '', 1, true)).toEqual({ id: 'vm', cleanupdetails: true })
    expect(() => settingsParams({ ...vm({ a: '1', b: '2' }), readonlydetails: 'b' }, null, 'delete', 'a', '', 1, false)).toThrow('cleanupBlocked')
  })
  it('enforces readonly, template and duplicate restrictions', () => {
    expect(settingRestriction({ ...vm(), readonlydetails: 'a, b' }, null, 'b')).toBe('readonly')
    expect(settingRestriction({ ...vm(), alloweddetails: 'a' }, { deployasis: true }, 'b')).toBe('template')
    expect(() => settingsParams(vm({ a: '1' }), null, 'add', 'a', '2', 1, true)).toThrow('duplicate')
  })
  it('validates all replaced video keys and leaves unrelated values intact', () => {
    const source = vm({ 'video.hardware': 'qxl', 'video.ram': '99', a: '1' })
    const result = settingsParams(source, null, 'add', 'video.hardware', 'virtio', 2, true)
    expect(result['details[0].video.hardware2']).toBe('virtio')
    expect(result['details[0].video.ram2']).toBe('16384')
    expect(result['details[0].a']).toBe('1')
    expect(() => settingsParams({ ...source, readonlydetails: 'video.ram' }, null, 'add', 'video.hardware', 'virtio', 2, true)).toThrow('protected')
    expect(() => settingsParams(source, null, 'add', 'video.hardware', 'virtio', 5, true)).toThrow('count')
  })
  it('compares settings independent of response property order', () => {
    expect(settingsFingerprint(vm({ a: 1, b: 2 }))).toBe(settingsFingerprint(vm({ b: 2, a: 1 })))
  })
})
function context () {
  const value = { ...VmSettingsTab.data(), ...VmSettingsTab.methods, resource: { id: 'vm' }, $store: { getters: { userInfo: { roletype: 'Admin' }, apis: { updateVirtualMachine: {} } } }, $t: k => k, $message: { success: jest.fn() } }
  Object.entries(VmSettingsTab.computed).forEach(([key, get]) => Object.defineProperty(value, key, { get: () => get.call(value) }))
  value.vm = vm({ a: '1' }); value.loaded = true; value.open('edit', { name: 'a', value: '1' }); value.draftValue = '2'
  return value
}
describe('VM settings submission lifecycle', () => {
  beforeEach(() => jest.clearAllMocks())
  it('blocks state changes discovered just before submission', async () => {
    const value = context(); value.fetchState = jest.fn().mockResolvedValue({ vm: { ...vm({ a: '1' }), state: 'Running' }, options: {}, template: null })
    await value.submit(); expect(postAPI).not.toHaveBeenCalled(); expect(value.dialogError).toContain('stopped')
  })
  it('blocks stale edits and preserves the draft', async () => {
    const value = context(); value.fetchState = jest.fn().mockResolvedValue({ vm: vm({ a: 'new' }), options: {}, template: null })
    await value.submit(); expect(postAPI).not.toHaveBeenCalled(); expect(value.dialogError).toContain('changed'); expect(value.draftValue).toBe('2')
  })
  it('retains original values and dialog on API failure', async () => {
    const value = context(); value.fetchState = jest.fn().mockResolvedValue({ vm: vm({ a: '1' }), options: {}, template: null }); postAPI.mockRejectedValue(new Error('network'))
    await value.submit(); expect(value.vm.details.a).toBe('1'); expect(value.dialog).toBe('edit'); expect(value.draftValue).toBe('2'); expect(value.submitting).toBe(false)
  })
  it('ignores a response belonging to a previous VM', async () => {
    const value = context(); value.fetchState = jest.fn().mockImplementation(async () => { value.resource.id = 'other'; value.revision++; return { vm: vm({ a: '1' }) } })
    await value.submit(); expect(postAPI).not.toHaveBeenCalled()
  })
  it('skips template lookup for ISO', async () => {
    const value = context()
    getAPI.mockImplementation(async name => name === 'listVirtualMachines' ? { listvirtualmachinesresponse: { virtualmachine: [{ ...vm(), templateid: 'iso', templateformat: 'ISO' }] } } : { listdetailoptionsresponse: { detailoptions: { details: {} } } })
    await value.fetchState('vm'); expect(getAPI).not.toHaveBeenCalledWith('listTemplates', expect.anything())
  })
  it('fails closed when a disk template lookup is empty', async () => {
    const value = context()
    getAPI.mockImplementation(async name => name === 'listVirtualMachines' ? { listvirtualmachinesresponse: { virtualmachine: [{ ...vm(), templateid: 'image', templateformat: 'QCOW2' }] } } : name === 'listDetailOptions' ? { listdetailoptionsresponse: { detailoptions: { details: {} } } } : { listtemplatesresponse: {} })
    await expect(value.fetchState('vm')).rejects.toThrow('loadFailed')
  })
  it('retains the old list and blocks edits when refresh fails', async () => {
    const value = context(); value.fetchState = jest.fn().mockRejectedValue(new Error('network'))
    await value.refresh(); expect(value.vm.details.a).toBe('1'); expect(value.blockReason).toContain('verify')
  })
  it('refreshes after a successful save and prevents a second concurrent submit', async () => {
    const value = context(); value.fetchState = jest.fn().mockResolvedValue({ vm: vm({ a: '1' }), template: null, options: {} }); value.refresh = jest.fn().mockResolvedValue()
    postAPI.mockResolvedValue({ updatevirtualmachineresponse: { virtualmachine: vm({ a: '2' }) } })
    const first = value.submit(); await value.submit(); await first
    expect(postAPI).toHaveBeenCalledTimes(1); expect(value.refresh).toHaveBeenCalledTimes(1); expect(value.dialog).toBe('')
  })
})
