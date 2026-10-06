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
    await value.fetchState('vm'); expect(getAPI.mock.calls.some(([name]) => name === 'listTemplates')).toBe(false)
  })
  it('blocks edits without failing VM reads when a disk template lookup is empty', async () => {
    const value = context()
    getAPI.mockImplementation(async name => name === 'listVirtualMachines' ? { listvirtualmachinesresponse: { virtualmachine: [{ ...vm(), templateid: 'image', templateformat: 'QCOW2' }] } } : name === 'listDetailOptions' ? { listdetailoptionsresponse: { detailoptions: { details: {} } } } : { listtemplatesresponse: {} })
    const state = await value.fetchState('vm')
    expect(state.policyError).toContain('templateUnavailable')
    Object.assign(value, state)
    expect(value.blockReason).toContain('templateUnavailable')
    expect(value.rows).toEqual([])
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

describe('VM settings supplementary metadata', () => {
  const source = { ...vm({ cpuNumber: '2', memory: '4096', 'io.policy': 'io_uring' }), templateid: 'removed', templateformat: 'RAW' }
  function responses (options = {}, template = { id: 'removed', deployasis: false }) {
    getAPI.mockImplementation(async name => {
      if (name === 'listVirtualMachines') return { listvirtualmachinesresponse: { virtualmachine: [source] } }
      if (name === 'listDetailOptions') return { listdetailoptionsresponse: { detailoptions: { details: options } } }
      return { listtemplatesresponse: { template: template ? [template] : [] } }
    })
  }
  beforeEach(() => jest.resetAllMocks())
  it('loads cloned RAW VM details and requests only its removed template', async () => {
    responses()
    const value = context()
    await value.refresh()
    expect(getAPI).toHaveBeenCalledWith('listTemplates', { templatefilter: 'all', id: 'removed', showremoved: true }, { optionalDiscovery: true })
    expect(getAPI).toHaveBeenCalledWith('listDetailOptions', { resourcetype: 'UserVm', resourceid: 'vm' }, { optionalDiscovery: true })
    expect(value.rows).toHaveLength(3)
    expect(value.vm.details).toEqual(source.details)
    expect(value.policyError).toBe('')
    expect(value.blockReason).toBe('')
  })
  it.each(['User', 'DomainAdmin'])('uses an accessible template filter for %s', async roletype => {
    responses()
    const value = context()
    value.$store.getters.userInfo.roletype = roletype
    await value.fetchState('vm')
    expect(getAPI).toHaveBeenCalledWith('listTemplates', { templatefilter: 'executable', id: 'removed', showremoved: true }, { optionalDiscovery: true })
  })
  it('displays settings on first entry despite an empty template response', async () => {
    responses({}, null)
    const value = context()
    value.vm = {}; value.loaded = false
    await value.refresh()
    expect(value.loaded).toBe(true)
    expect(value.error).toBe('')
    expect(value.rows).toHaveLength(3)
    value.search = 'memory'
    expect(value.rows).toEqual([{ name: 'memory', value: '4096' }])
    expect(value.blockReason).toContain('templateUnavailable')
    value.open('add')
    expect(value.dialog).not.toBe('add')
    value.open('details', value.rows[0])
    expect(value.dialog).toBe('details')
  })
  it('keeps current VM values when a template API rejects access', async () => {
    responses()
    const working = getAPI.getMockImplementation()
    getAPI.mockImplementation((name, params) => name === 'listTemplates' ? Promise.reject(new Error('permission')) : working(name, params))
    const value = context()
    await value.refresh()
    expect(value.vm.details.memory).toBe('4096')
    expect(value.error).toBe('')
    expect(value.policyError).toContain('templateUnavailable')
  })
  it.each([null, [], 'invalid'])('blocks changes but shows details on malformed options: %s', async options => {
    responses(options)
    const value = context()
    await value.refresh()
    expect(value.rows).toHaveLength(3)
    expect(value.options).toEqual({})
    expect(value.blockReason).toContain('optionsUnavailable')
  })
  it('keeps settings visible when the options request fails', async () => {
    responses()
    const working = getAPI.getMockImplementation()
    getAPI.mockImplementation((name, params) => name === 'listDetailOptions' ? Promise.reject(new Error('network')) : working(name, params))
    const value = context()
    await value.refresh()
    expect(value.rows).toHaveLength(3)
    expect(value.blockReason).toContain('optionsUnavailable')
  })
  it.each([{ id: 'removed' }, { id: 'other', deployasis: false }])('requires the correct template and explicit policy: %s', async template => {
    responses({}, template)
    const value = context()
    await value.refresh()
    expect(value.rows).toHaveLength(3)
    expect(value.blockReason).toContain('templateUnavailable')
  })
  it('preserves deploy-as-is restrictions after resolving a removed template', async () => {
    responses({}, { id: 'removed', deployasis: true })
    const value = context()
    await value.refresh()
    expect(value.reason('memory')).toContain('template')
    value.vm.alloweddetails = 'memory'
    expect(value.reason('memory')).toBe('')
    value.vm.readonlydetails = 'memory'
    expect(value.reason('memory')).toContain('readonly')
  })
  it('clears a metadata warning after a successful update', async () => {
    responses({}, null)
    const value = context()
    await value.refresh()
    expect(value.policyError).toContain('templateUnavailable')
    responses()
    await value.refresh()
    expect(value.policyError).toBe('')
    expect(value.blockReason).toBe('')
  })
  it('keeps the displayed rows while an update is pending', async () => {
    responses()
    const value = context()
    await value.refresh()
    let complete
    value.fetchState = jest.fn(() => new Promise(resolve => { complete = resolve }))
    const update = value.refresh()
    expect(value.loading).toBe(true)
    expect(value.loaded).toBe(true)
    expect(value.rows).toHaveLength(3)
    complete({ vm: source, options: {}, template: { id: 'removed', deployasis: false }, policyError: '' })
    await update
    expect(value.loading).toBe(false)
  })
  it('rejects edits if policy becomes unavailable during submission recheck', async () => {
    const value = context()
    value.fetchState = jest.fn().mockResolvedValue({ vm: vm({ a: '1' }), options: {}, template: null, policyError: 'templateUnavailable' })
    await value.submit()
    expect(postAPI).not.toHaveBeenCalled()
    expect(value.dialogError).toContain('templateUnavailable')
    expect(value.dialog).toBe('edit')
    expect(value.draftValue).toBe('2')
  })
  it('does not apply a delayed refresh to a different VM', async () => {
    const value = context()
    value.fetchState = jest.fn().mockImplementation(async () => {
      value.resource.id = 'other'; value.revision++
      return { vm: source, options: {}, template: null, policyError: 'templateUnavailable' }
    })
    await value.refresh()
    expect(value.vm.details).toEqual({ a: '1' })
    expect(value.policyError).toBe('')
  })
  it('clears details, metadata and warnings when the VM changes', () => {
    const value = context()
    value.template = { deployasis: true }; value.options = { old: [] }; value.policyError = 'templateUnavailable'; value.error = 'loadFailed'
    VmSettingsTab.watch['resource.id'].call(value)
    expect(value.vm).toEqual({})
    expect(value.template).toBe(null)
    expect(value.options).toEqual({})
    expect(value.policyError).toBe('')
    expect(value.error).toBe('')
    expect(value.loaded).toBe(false)
  })
})
