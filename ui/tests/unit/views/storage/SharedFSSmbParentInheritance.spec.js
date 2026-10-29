// Licensed to the Apache Software Foundation (ASF) under one or more
// contributor license agreements. See NOTICE. Apache License, Version 2.0.
// http://www.apache.org/licenses/LICENSE-2.0
import { shallowMount, flushPromises } from '@vue/test-utils'
import Setgid from '@/components/storage/StorageDirectorySetgidDialog'
import Fields from '@/components/storage/SmbParentInheritanceFields'
import SharedFSTab from '@/views/storage/SharedFSTab'
import { postAPI } from '@/api'
import { inheritOwnerParameters, inheritanceIntent, setgidParameters, setgidIntent } from '@/utils/smbParentInheritance'
import { configurationModalIntent } from '@/utils/storageConfigPreflight'
import wire from '../../../fixtures/storage-1453-inheritance-wire.json'
jest.mock('@/api', () => ({ getAPI: jest.fn(), postAPI: jest.fn() }))
const clone = x => JSON.parse(JSON.stringify(x))
const row = () => clone(wire.list.liststoragedirectorypoliciesresponse.directorypolicy[0])
describe('SMB inheritance actual API wire and component request contracts', () => {
  let wrapper
  beforeEach(() => {
    postAPI.mockReset()
    const report = clone(wire.preflight)
    report.preflightstorageserviceconfigurationresponse.configurationpreflight.expiresat = new Date(Date.now() + 30000).toISOString()
    postAPI.mockResolvedValue(report)
  })
  afterEach(() => { if (wrapper) wrapper.unmount(); wrapper = null })
  test('unchanged legacy boolean is omitted, explicit disable remains false', () => {
    expect(inheritOwnerParameters({ inheritowner: false, inheritownerbaseline: false })).toEqual({})
    expect(inheritanceIntent({ inheritowner: false, inheritownerbaseline: false })).toBe(null)
    expect(inheritOwnerParameters({ inheritowner: false, inheritownerbaseline: true })).toEqual({ inheritowner: false })
  })
  test('mounted readonly parent uses real serializer types and does not clamp high numeric identity', () => {
    const policy = row()
    wrapper = shallowMount(Fields, { props: { value: false, policy, report: wire.preflight.preflightstorageserviceconfigurationresponse.configurationpreflight }, global: { mocks: { $t: x => x } } })
    expect(wrapper.vm.parent.owneruid).toBe(100001)
    expect(wrapper.vm.parentReady).toBe(true)
    expect(wrapper.vm.group).toBe(false)
    expect(wrapper.findAll('a-input-number-stub')).toHaveLength(0)
  })
  test('setgid request preserves sticky/rwx and omits owner, GID and recursion', () => {
    expect(setgidParameters(row(), true)).toEqual({ id: 'policy', expectedpolicyrevision: 4, mode: '3770' })
    expect(setgidIntent(row(), true).posixchanges).toEqual({ mode: '3770' })
  })
  test('actual setgid component preview and apply submit the same mode-only contract', async () => {
    const execute = jest.fn().mockResolvedValue({ state: 'COMMITTED' })
    wrapper = shallowMount(Setgid, { props: { row: row(), instance: { id: 'instance', configurationrevision: 20 }, execute }, global: { mocks: { $t: x => x } } })
    expect(wrapper.vm.dirty).toBe(false)
    await wrapper.vm.check()
    expect(postAPI).not.toHaveBeenCalled()
    wrapper.vm.enabled = true; await flushPromises()
    await wrapper.vm.check()
    expect(JSON.parse(postAPI.mock.calls[0][1].intent).posixchanges).toEqual({ mode: '3770' })
    expect(wrapper.vm.applicable).toBe(false)
    wrapper.vm.ack = true; await flushPromises(); await wrapper.vm.apply()
    expect(execute).toHaveBeenCalledWith('updateStorageDirectoryPolicy', { instanceid: 'instance', id: 'policy', expectedpolicyrevision: 4, mode: '3770' }, expect.anything())
    expect(wrapper.emitted('done')).toHaveLength(1)
  })
  test('late read after close/unmount cannot update the component', async () => {
    let finish
    postAPI.mockReturnValueOnce(new Promise(resolve => { finish = resolve }))
    wrapper = shallowMount(Setgid, { props: { row: row(), instance: { id: 'instance' }, execute: jest.fn() }, global: { mocks: { $t: x => x } } })
    wrapper.vm.enabled = true; await flushPromises()
    const pending = wrapper.vm.check()
    wrapper.unmount(); wrapper = null
    finish(clone(wire.preflight)); await pending
  })
  test('server baseline/feature rejection is delivered as an actionable UI reason', () => {
    wrapper = shallowMount(Fields, { props: { report: { checks: [{ code: 'SMB_PARENT_INHERITANCE_PROTECTION_REQUIRED', blocking: true }] } }, global: { mocks: { $t: x => x } } })
    expect(wrapper.vm.failure).toBe('message.storage.inheritance.protection')
  })
  test('public SMB preflight uses effective bool while actual unchanged edit omits only the write parameter', async () => {
    const form = { inheritowner: false, inheritownerbaseline: false, directorypolicyid: 'policy', id: 'share', volumemode: 'CURRENT', volumeid: 'volume' }
    const intent = configurationModalIntent('editSmbShare', { smbShare: form }, { raw: { id: 'share' } })
    expect(intent.smbinheritance).toEqual({ inheritOwner: false, directoryPolicyId: 'policy' })
    const runStorageAction = jest.fn().mockResolvedValue()
    const ctx = { forms: { smbShare: form }, actionModal: { context: { raw: { id: 'share' } } }, runStorageAction, smbShareImportMode: () => 'MOUNT_EXISTING', toCapacityBytes: () => undefined, $t: x => x }
    await SharedFSTab.methods.updateSmbShare.call(ctx)
    const params = runStorageAction.mock.calls[0][2]
    expect(params.directorypolicyid).toBe('policy')
    expect(params).not.toHaveProperty('inheritowner')
    expect(params.directorymode).toBeUndefined()
  })
  test('protection prevents a second component write and drift refuses mode calculation', async () => {
    const execute = jest.fn()
    wrapper = shallowMount(Setgid, { props: { row: row(), instance: { id: 'instance' }, execute, guarded: true }, global: { mocks: { $t: x => x } } })
    wrapper.vm.enabled = true; await flushPromises(); await wrapper.vm.check(); await wrapper.vm.apply()
    expect(postAPI).not.toHaveBeenCalled(); expect(execute).not.toHaveBeenCalled()
    const drift = row(); drift.observationstatus = 'DRIFT'
    expect(() => setgidParameters(drift, true)).toThrow('DIRECTORY_POLICY_STALE')
  })
})
