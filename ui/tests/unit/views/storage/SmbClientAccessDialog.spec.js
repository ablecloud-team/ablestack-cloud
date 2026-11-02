// Licensed to the Apache Software Foundation (ASF) under one or more
// contributor license agreements. See NOTICE. Apache License, Version 2.0.
// http://www.apache.org/licenses/LICENSE-2.0
import { shallowMount, flushPromises } from '@vue/test-utils'
import Dialog from '@/components/storage/SmbClientAccessDialog'
import { postAPI } from '@/api'
import actualWire from '../../../fixtures/storage-1457-client-preflight-wire.json'
import ko from '@/../public/locales/ko_KR.json'
jest.mock('@/api', () => ({ postAPI: jest.fn() }))
const ready = () => {
  const wire = JSON.parse(JSON.stringify(actualWire))
  wire.preflightstorageserviceconfigurationresponse.configurationpreflight.expiresat = new Date(Date.now() + 30000).toISOString()
  return wire
}
function mountDialog (config = '{}', guarded = false) {
  return shallowMount(Dialog, {
    props: { row: { id: 'share', name: 'team', config }, instance: { id: 'instance', configurationrevision: 47 }, guarded, execute: jest.fn().mockResolvedValue({ state: 'COMMITTED' }) },
    global: { stubs: { AForm: { template: '<form><slot /></form>' }, MoldDialog: { template: '<section><slot /></section>' }, MoldDialogActions: { template: '<footer><slot /></footer>' } }, mocks: { $t: key => ko[key] || key } }
  })
}
describe('real client source dialog request lifecycle', () => {
  let wrapper
  afterEach(() => { if (wrapper) wrapper.unmount(); postAPI.mockReset() })
  test('actual serialized preflight and submit share one canonical source patch; duplicate apply is suppressed', async () => {
    wrapper = mountDialog()
    wrapper.vm.form = { mode: 'RESTRICTED', input: '10.10.13.27/24', confirmed: false }
    await flushPromises()
    postAPI.mockResolvedValueOnce(ready())
    await wrapper.vm.check()
    const preview = JSON.parse(postAPI.mock.calls[0][1].intent)
    expect(preview).toEqual({ api: 'updateStorageSmbShare', protocol: 'SMB', resourceid: 'share', smbclientaccess: { cidrs: ['10.10.13.0/24'] } })
    expect(wrapper.vm.report.clientaccesspreview.current.status).toBe('CONFIG_VERIFIED')
    expect(wrapper.vm.profileRows.map(p => p.port)).toEqual(['445', '1445'])
    expect(wrapper.vm.applicable).toBe(false)
    wrapper.vm.ack = true
    await wrapper.vm.$nextTick()
    const first = wrapper.vm.apply(); const second = wrapper.vm.apply()
    await Promise.all([first, second])
    expect(wrapper.props('execute')).toHaveBeenCalledTimes(1)
    expect(wrapper.props('execute').mock.calls[0][1]).toEqual({ id: 'share', clientcidrs: '["10.10.13.0/24"]' })
    expect(wrapper.emitted('done')).toHaveLength(1)
  })
  test('clear requires explicit confirmation and sends the same flag in preview and submit', async () => {
    wrapper = mountDialog('{"clientAccess":{"version":1,"cidrs":["10.0.0.1/32"]}}')
    wrapper.vm.form = { mode: 'ANY_SOURCE', input: '', confirmed: false }
    await flushPromises(); await wrapper.vm.check()
    expect(postAPI).not.toHaveBeenCalled()
    wrapper.vm.form.confirmed = true
    await flushPromises()
    postAPI.mockResolvedValueOnce(ready()); await wrapper.vm.check()
    expect(JSON.parse(postAPI.mock.calls[0][1].intent).smbclientaccess).toEqual({ cidrs: [], confirmAnySource: true })
    wrapper.vm.ack = true; await wrapper.vm.$nextTick(); await wrapper.vm.apply()
    expect(wrapper.props('execute').mock.calls[0][1]).toEqual({ id: 'share', clientcidrs: '[]', confirmanysource: true })
  })
  test.each(['BLOCKED', 'UNKNOWN'])('%s remains blocked even after impact acknowledgement', async status => {
    wrapper = mountDialog(); wrapper.vm.form = { mode: 'RESTRICTED', input: '10.0.0.1', confirmed: false }
    await flushPromises()
    const wire = ready(); const report = wire.preflightstorageserviceconfigurationresponse.configurationpreflight
    report.status = status; report.checks = [{ code: 'SMB_CLIENT_ACCESS_DRIFT', blocking: true, severity: 'ERROR' }]
    postAPI.mockResolvedValueOnce(wire); await wrapper.vm.check()
    wrapper.vm.ack = true; await wrapper.vm.$nextTick(); await wrapper.vm.apply()
    expect(wrapper.vm.applicable).toBe(false)
    expect(wrapper.props('execute')).not.toHaveBeenCalled()
    expect(wrapper.vm.error).toBe(ko['message.storage.client.SMB_CLIENT_ACCESS_DRIFT'])
  })
  test('editing input invalidates the report and server protection prevents modal reentry submission', async () => {
    wrapper = mountDialog(); wrapper.vm.form = { mode: 'RESTRICTED', input: '10.0.0.1', confirmed: false }
    await flushPromises(); postAPI.mockResolvedValueOnce(ready()); await wrapper.vm.check()
    wrapper.vm.form.input = '10.0.0.2'; await flushPromises()
    expect(wrapper.vm.report).toBeNull(); expect(wrapper.vm.ack).toBe(false)
    await wrapper.setProps({ guarded: true }); await wrapper.vm.check(); await wrapper.vm.apply()
    expect(postAPI).toHaveBeenCalledTimes(1); expect(wrapper.props('execute')).not.toHaveBeenCalled()
  })
  test('late responses after closing do not restore an old acknowledgement', async () => {
    let complete
    wrapper = mountDialog(); wrapper.vm.form = { mode: 'RESTRICTED', input: '10.0.0.1', confirmed: false }
    await flushPromises(); postAPI.mockReturnValueOnce(new Promise(resolve => { complete = resolve }))
    const checking = wrapper.vm.check(); const vm = wrapper.vm
    wrapper.unmount(); wrapper = null
    complete(ready()); await checking
    expect(vm.report).toBeNull()
  })
})
