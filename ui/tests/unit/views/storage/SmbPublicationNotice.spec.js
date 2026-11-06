// Licensed to the Apache Software Foundation (ASF) under one or more
// contributor license agreements. See NOTICE. Apache License, Version 2.0.
// http://www.apache.org/licenses/LICENSE-2.0
import { shallowMount } from '@vue/test-utils'
import Notice from '@/components/storage/SmbPublicationNotice'
import wire from '../../../fixtures/storage-1455-publication-wire.json'
import { preflightApplicable } from '@/utils/storageConfigPreflight'
describe('SMB setgid publication admission from actual serializer output', () => {
  const report = () => {
    const value = JSON.parse(JSON.stringify(wire.preflightstorageserviceconfigurationresponse.configurationpreflight))
    value.expiresat = new Date(Date.now() + 30000).toISOString()
    return value
  }
  test('server-proven unpublished state guides ACL setup without creating an action', () => {
    const value = report()
    const before = JSON.stringify(value)
    const wrapper = shallowMount(Notice, { props: { report: value }, global: { mocks: { $t: x => x } } })
    expect(typeof value.directorypreview.smbpublication[0].listeners).toBe('number')
    expect(value.directorypreview.after.mode).toBe('2775')
    expect(wrapper.find('a-alert-stub').attributes('message')).toBe('label.storage.smb.waiting.acl')
    expect(wrapper.find('a-alert-stub').attributes('description')).toContain('s1455-child')
    expect(wrapper.find('a-alert-stub').attributes('type')).toBe('info')
    expect(preflightApplicable(value, false)).toBe(false)
    expect(preflightApplicable(value, true)).toBe(true)
    expect(wrapper.emitted()).toEqual({})
    expect(JSON.stringify(value)).toBe(before)
    wrapper.unmount()
  })
  test.each([
    ['SMB_PUBLISHED_SECTION_MISSING', 'message.storage.smb.published.section.missing'],
    ['SMB_PUBLICATION_STATE_MISMATCH', 'message.storage.smb.publication.mismatch'],
    ['SMB_PUBLICATION_OBSERVATION_UNAVAILABLE', 'message.storage.smb.publication.unavailable']
  ])('published/configuration protection %s remains blocking', (code, message) => {
    const value = { ...report(), status: 'BLOCKED', checks: [{ code, blocking: true }] }
    const wrapper = shallowMount(Notice, { props: { report: value }, global: { mocks: { $t: x => x } } })
    expect(wrapper.find('a-alert-stub').attributes('type')).toBe('error')
    expect(wrapper.find('a-alert-stub').attributes('message')).toBe(message)
    expect(preflightApplicable(value, true)).toBe(false)
    wrapper.unmount()
  })
  test('missing/legacy observations are not inferred to be safely unpublished', () => {
    for (const value of [null, {}, { directorypreview: { smbpublication: [{ name: 'active', state: 'PUBLISHED' }] } }]) {
      const wrapper = shallowMount(Notice, { props: { report: value }, global: { mocks: { $t: x => x } } })
      expect(wrapper.find('a-alert-stub').exists()).toBe(false)
      wrapper.unmount()
    }
  })
})
