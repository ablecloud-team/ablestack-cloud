// Licensed to the Apache Software Foundation (ASF) under one or more
// contributor license agreements. See NOTICE. Apache License, Version 2.0.
// http://www.apache.org/licenses/LICENSE-2.0
import { shallowMount, flushPromises } from '@vue/test-utils'
import Dialog from '@/components/storage/SmbCreationPolicyDialog'
import SharedFSTab from '@/views/storage/SharedFSTab'
import { postAPI } from '@/api'
import { smbModeDefaults, smbCreationModes, smbCreationDelta, smbSimpleOptionPatch } from '@/utils/smbCreationPolicy'
import { configurationModalIntent } from '@/utils/storageConfigPreflight'
import wire from '../../../fixtures/storage-1454-creation-wire.json'
jest.mock('@/api', () => ({ getAPI: jest.fn(), postAPI: jest.fn() }))
const clone = x => JSON.parse(JSON.stringify(x))
const row = () => clone(wire.list.liststoragesmbsharesresponse.storagesmbshare[0])
describe('SMB creation policy actual wire and component contracts', () => {
  let wrapper
  const report = () => {
    const value = clone(wire.preflight)
    value.preflightstorageserviceconfigurationresponse.configurationpreflight.expiresat = new Date(Date.now() + 30000).toISOString()
    return value
  }
  const mount = (props = {}) => {
    wrapper = shallowMount(Dialog, { props: { row: row(), instance: { id: 'instance', configurationrevision: 20 }, execute: jest.fn().mockResolvedValue({ state: 'COMMITTED' }), ...props }, global: { mocks: { $t: x => x } } })
    return wrapper
  }
  beforeEach(() => { postAPI.mockReset(); postAPI.mockResolvedValue(report()) })
  afterEach(() => { if (wrapper) wrapper.unmount(); wrapper = null; jest.useRealTimers() })
  test('actual config remains a string; missing fields use compatible defaults without raw mutation', () => {
    expect(typeof row().config).toBe('string')
    expect(smbCreationModes(row().config)).toEqual(smbModeDefaults)
    expect(smbCreationDelta(row().config, { ...smbModeDefaults, createMask: '660' })).toEqual({})
    expect(JSON.parse(row().config)).not.toHaveProperty('createMask')
  })
  test.each(['0888', '1777', '00660', '', 660])('rejects invalid mode %p', value => {
    expect(() => smbCreationModes({ createMask: value })).toThrow('SMB_CREATION_MODE_INVALID')
  })
  test('unchanged and force-outside-mask inputs never submit a request', async () => {
    mount(); await wrapper.vm.check(); expect(postAPI).not.toHaveBeenCalled()
    wrapper.vm.form.createMask = '0600'; wrapper.vm.form.forceCreateMode = '0040'; await flushPromises()
    expect(wrapper.vm.validationError).toBe('SMB_FORCE_MODE_OUTSIDE_MASK')
    await wrapper.vm.check(); await wrapper.vm.apply(); expect(postAPI).not.toHaveBeenCalled()
  })
  test('preflight and apply use the same changed field, no POSIX/backing/account values', async () => {
    const execute = jest.fn().mockResolvedValue({ state: 'COMMITTED' })
    mount({ execute }); wrapper.vm.form.createMask = '0640'; await flushPromises()
    await wrapper.vm.check()
    const call = postAPI.mock.calls[0]
    expect(call[0]).toBe('preflightStorageServiceConfiguration')
    expect(JSON.parse(call[1].intent)).toEqual({ api: 'updateStorageSmbShare', resourceid: 'share', smbcreation: { createMask: '0640' } })
    expect(wrapper.vm.preview).toBeTruthy()
    expect(wrapper.vm.applicable).toBe(false)
    wrapper.vm.ack = true; await wrapper.vm.apply()
    expect(execute).toHaveBeenCalledWith('updateStorageSmbShare', { id: 'share', createmask: '0640' }, expect.objectContaining({ fingerprint: 'creation-confirmed' }))
    expect(wrapper.emitted('done')).toHaveLength(1)
  })
  test('instance protection survives modal re-entry and prevents a second write', async () => {
    const execute = jest.fn()
    for (let i = 0; i < 2; i++) {
      mount({ guarded: true, execute }); wrapper.vm.form.createMask = '0640'; await flushPromises()
      await wrapper.vm.check(); await wrapper.vm.apply()
      wrapper.unmount(); wrapper = null
    }
    expect(postAPI).not.toHaveBeenCalled(); expect(execute).not.toHaveBeenCalled()
  })
  test('late preflight cannot repopulate an unmounted dialog', async () => {
    let finish
    postAPI.mockReturnValueOnce(new Promise(resolve => { finish = resolve }))
    mount(); wrapper.vm.form.createMask = '0640'; await flushPromises()
    const pending = wrapper.vm.check()
    wrapper.unmount(); wrapper = null; finish(report()); await pending
  })
  test('blocked baseline diagnostic remains visible without clearing the guard on expiry', async () => {
    jest.useFakeTimers()
    const blocked = report(); const body = blocked.preflightstorageserviceconfigurationresponse.configurationpreflight
    body.status = 'BLOCKED'; body.checks = [{ code: 'SMB_CREATION_POLICY_PROTECTION_REQUIRED', blocking: true }]
    postAPI.mockResolvedValueOnce(blocked)
    mount(); wrapper.vm.form.createMask = '0640'; await flushPromises(); await wrapper.vm.check()
    jest.advanceTimersByTime(60000); await wrapper.vm.$nextTick()
    expect(wrapper.vm.report.status).toBe('BLOCKED'); expect(wrapper.vm.applicable).toBe(false)
    expect(wrapper.vm.error).toBe('message.storage.creation.SMB_CREATION_POLICY_PROTECTION_REQUIRED')
  })
  test('rollback diagnostic is retained and never emits a successful close', async () => {
    mount({ execute: jest.fn().mockResolvedValue({ state: 'ROLLED_BACK', originalfailure: { code: 'SMB_OPTIONS_RELOAD_FAILED' } }) })
    wrapper.vm.form.createMask = '0640'; await flushPromises(); await wrapper.vm.check(); wrapper.vm.ack = true
    await wrapper.vm.apply()
    expect(wrapper.emitted('done')).toBeUndefined(); expect(wrapper.vm.error).toBe('SMB_OPTIONS_RELOAD_FAILED')
  })
  test('general edit only sends a changed safe option and skips backing preparation', async () => {
    const baseline = { readonly: false, browseable: true, inheritowner: false, name: 'team', volumeid: 'volume' }
    const form = { ...baseline, browseable: false, baseline }
    expect(smbSimpleOptionPatch(baseline, form)).toEqual({ browseable: false })
    const runStorageAction = jest.fn()
    await SharedFSTab.methods.updateSmbShare.call({ forms: { smbShare: form }, actionModal: { context: { raw: { id: 'share' } } }, runStorageAction, $t: x => x })
    expect(runStorageAction.mock.calls[0][2]).toEqual({ id: 'share', browseable: false })
    expect(configurationModalIntent('editSmbShare', { smbShare: form }, { raw: { id: 'share' } })).toEqual({ api: 'updateStorageSmbShare', protocol: 'SMB', resourceid: 'share', smboptions: { browseable: false } })
    expect(smbSimpleOptionPatch(baseline, { ...form, name: 'different' })).toBe(null)
  })
})
