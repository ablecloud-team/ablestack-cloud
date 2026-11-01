// Licensed to the Apache Software Foundation (ASF) under one or more
// contributor license agreements. See NOTICE. Apache License, Version 2.0.
// http://www.apache.org/licenses/LICENSE-2.0
import { shallowMount } from '@vue/test-utils'
import Fields from '@/components/storage/SmbFileIdentityFields'
import Dialog from '@/components/storage/SmbCreationPolicyDialog'
import { smbFileIdentity, smbIdentityParameters, smbIdentityIntent } from '@/utils/smbFileIdentity'
import { postAPI } from '@/api'
import wire from '../../../fixtures/storage-1456-file-identity-wire.json'
jest.mock('@/api', () => ({ postAPI: jest.fn() }))
const ordinary = { mode: 'AUTHENTICATED', uid: null, gid: null }
const fixed = { mode: 'FIXED', uid: 7001, gid: 7002 }
const report = () => {
  const value = JSON.parse(JSON.stringify(wire))
  value.preflightstorageserviceconfigurationresponse.configurationpreflight.expiresat = new Date(Date.now() + 30000).toISOString()
  return value
}
const options = {
  global: {
    mocks: { $t: x => x },
    stubs: { MoldDialog: { template: '<div><slot/></div>' }, MoldDialogActions: { template: '<div><slot/></div>' } }
  }
}
describe('SMB fixed file identity actual component/API contract', () => {
  beforeEach(() => { postAPI.mockReset(); postAPI.mockResolvedValue(report()) })
  test('legacy and unchanged fields are never submitted', () => {
    expect(smbFileIdentity('{}')).toEqual(ordinary)
    expect(smbIdentityParameters(ordinary, ordinary)).toEqual({})
    expect(smbIdentityParameters(fixed, fixed)).toEqual({})
    expect(smbIdentityParameters(fixed, { ...fixed, uid: 7003 })).toEqual({ fixeduid: 7003 })
    expect(smbIdentityParameters(fixed, ordinary)).toEqual({ fileidentitymode: 'AUTHENTICATED' })
  })
  test.each([0, -1, 65534, 61200, 1.5, null, '7001'])('protected/invalid UID %s is blocked', uid => {
    expect(() => smbIdentityParameters(ordinary, { ...fixed, uid })).toThrow()
  })
  test('actual field emits a typed draft and shows explicit inheritance/guest blockers', () => {
    const wrapper = shallowMount(Fields, { ...options, props: { value: fixed, inheritOwner: true } })
    expect(wrapper.find('a-alert-stub[type="error"]').attributes('message')).toContain('SMB_PARENT_IDENTITY_CONFLICT')
    wrapper.vm.change('uid', 7003)
    expect(wrapper.emitted('update:value')[0][0]).toEqual({ ...fixed, uid: 7003 })
    wrapper.unmount()
  })
  test('real dialog uses serializer preview, same intent/apply delta and ACK guard', async () => {
    const execute = jest.fn().mockResolvedValue({ state: 'COMMITTED' })
    const wrapper = shallowMount(Dialog, { ...options, props: { row: { id: 'share', name: 'team', config: '{}' }, instance: { id: 'instance' }, execute } })
    await wrapper.setData({ identityForm: fixed })
    await wrapper.vm.check()
    const intent = JSON.parse(postAPI.mock.calls[0][1].intent)
    expect(intent.smbfileidentity).toEqual(smbIdentityIntent(ordinary, fixed))
    expect(intent.smbfullapply).toBe(true)
    expect(typeof wrapper.vm.report.fileidentitypreview.uid).toBe('number')
    await wrapper.vm.apply()
    expect(execute).not.toHaveBeenCalled()
    await wrapper.setData({ ack: true })
    await wrapper.vm.apply()
    expect(execute.mock.calls[0][0]).toBe('updateStorageSmbShare')
    expect(execute.mock.calls[0][1]).toEqual({ id: 'share', fileidentitymode: 'FIXED', fixeduid: 7001, fixedgid: 7002 })
    expect(wrapper.emitted('done')).toHaveLength(1)
    wrapper.unmount()
  })
  test('pure mask editing preserves existing fixed identity without resubmission', async () => {
    const execute = jest.fn().mockResolvedValue({ state: 'COMMITTED' })
    const config = JSON.stringify({ fileIdentity: { version: 1, ...fixed } })
    const wrapper = shallowMount(Dialog, { ...options, props: { row: { id: 'share', name: 'team', config }, instance: { id: 'instance' }, execute } })
    await wrapper.setData({ form: { ...wrapper.vm.form, createMask: '0640' } })
    await wrapper.vm.check()
    expect(JSON.parse(postAPI.mock.calls[0][1].intent).smbfileidentity).toBeUndefined()
    await wrapper.setData({ ack: true })
    await wrapper.vm.apply()
    expect(execute.mock.calls[0][1]).toEqual({ id: 'share', createmask: '0640' })
    wrapper.unmount()
  })
})
