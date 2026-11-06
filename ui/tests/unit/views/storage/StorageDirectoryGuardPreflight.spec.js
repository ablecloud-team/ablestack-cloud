// Licensed to the Apache Software Foundation (ASF) under one or more
// contributor license agreements. See NOTICE. Apache License, Version 2.0.
// http://www.apache.org/licenses/LICENSE-2.0
import { shallowMount, flushPromises } from '@vue/test-utils'
import Setgid from '@/components/storage/StorageDirectorySetgidDialog'
import Panel from '@/components/storage/StorageConfigurationPreflightPanel'
import { postAPI } from '@/api'
import wire from '../../../fixtures/storage-1456-directory-guards-wire.json'
import ko from '@/../public/locales/ko_KR.json'
jest.mock('@/api', () => ({ postAPI: jest.fn() }))
const row = { id: 'policy', path: '/epic1456/projects/team-a', policyrevision: 1, mode: '0770', observationstatus: 'APPLIED', observation: { status: 'OBSERVED', owneruid: 7001, ownergid: 7002, mode: '0770', inode: 42 } }
describe('actual directory RPC error wire in the real setgid dialog', () => {
  let wrapper
  afterEach(() => { if (wrapper) wrapper.unmount(); jest.useRealTimers(); postAPI.mockReset() })
  test.each(['SMB_PARENT_IDENTITY_CONFLICT', 'SMB_FIXED_IDENTITY_PERMISSION_DENIED', 'TRANSPORT', 'NULL_RESULT', 'NO_CODE', 'SMB_UNRECOGNIZED_GUARD'])('%s keeps its classification and cannot apply after ACK or expiry', async code => {
    const execute = jest.fn()
    postAPI.mockResolvedValueOnce(wire[code])
    wrapper = shallowMount(Setgid, {
      props: { row, instance: { id: 'instance', configurationrevision: 47 }, execute },
      global: {
        stubs: { AForm: { template: '<form><slot /></form>' }, ATag: { template: '<span><slot /></span>' }, StorageConfigurationPreflightPanel: false, MoldDialog: { template: '<section><slot /></section>' }, MoldDialogActions: { template: '<footer><slot /></footer>' } },
        mocks: { $t: key => ko[key] || key }
      }
    })
    wrapper.vm.enabled = true
    await flushPromises()
    await wrapper.vm.check()
    const expected = code.startsWith('SMB_') && code !== 'SMB_UNRECOGNIZED_GUARD' ? 'BLOCKED' : 'UNKNOWN'
    expect(wrapper.vm.report.status).toBe(expected)
    expect(postAPI.mock.calls[0][0]).toBe('preflightStorageServiceConfiguration')
    expect(JSON.parse(postAPI.mock.calls[0][1].intent).posixchanges).toEqual({ mode: '2770' })
    await flushPromises()
    const panel = wrapper.findComponent(Panel)
    if (expected === 'BLOCKED') {
      expect(panel.text()).toContain(ko['message.storage.file.identity.' + code])
      expect(panel.text()).toContain(code)
    } else {
      expect(panel.text()).toContain('DIRECTORY_OBSERVATION_UNAVAILABLE')
      expect(panel.text()).not.toContain(ko['message.storage.file.identity.SMB_PARENT_IDENTITY_CONFLICT'])
    }
    wrapper.vm.ack = true
    await flushPromises()
    expect(wrapper.vm.applicable).toBe(false)
    await wrapper.vm.apply()
    expect(execute).not.toHaveBeenCalled()
    jest.useFakeTimers()
    jest.advanceTimersByTime(60000)
    await wrapper.vm.$nextTick()
    expect(wrapper.vm.report.status).toBe(expected)
    expect(wrapper.vm.applicable).toBe(false)
  })
})
