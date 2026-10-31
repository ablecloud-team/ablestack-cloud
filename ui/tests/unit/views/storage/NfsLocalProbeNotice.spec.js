// Licensed to the Apache Software Foundation (ASF) under one or more
// contributor license agreements. See NOTICE. Apache License, Version 2.0.
// http://www.apache.org/licenses/LICENSE-2.0
import { shallowMount } from '@vue/test-utils'
import Notice from '@/components/storage/NfsLocalProbeNotice'
import wire from '../../../fixtures/storage-1455-local-probe-wire.json'
describe('actual API local NFS probe observation', () => {
  const rows = () => JSON.parse(JSON.stringify(wire.liststoragenfsexportsresponse.storagenfsexport))
  test('typed external-client status is distinct from Ready service and unchanged policy JSON', () => {
    const shares = rows()
    const before = JSON.stringify(shares)
    const wrapper = shallowMount(Notice, { props: { shares }, global: { mocks: { $t: key => key } } })
    expect(shares[0].state).toBe('Ready')
    expect(typeof shares[0].config).toBe('string')
    expect(shares[0].localprobe.externalclientrequired).toBe(true)
    expect(typeof shares[0].localprobe.ports[0].port).toBe('number')
    expect(wrapper.find('a-alert-stub').attributes('description')).toContain('nested (2049)')
    expect(wrapper.find('a-alert-stub').attributes('message')).toBe('label.storage.nfs.external.check')
    expect(wrapper.emitted()).toEqual({})
    expect(JSON.stringify(shares)).toBe(before)
    wrapper.unmount()
  })
  test('a verified local mount or missing old-runtime observation emits no pending notice', () => {
    for (const shares of [[], [{ name: 'legacy' }], [{ name: 'checked', localprobe: { externalclientrequired: false, ports: [{ port: 2049, status: 'MOUNT_VERIFIED' }] } }]]) {
      const wrapper = shallowMount(Notice, { props: { shares }, global: { mocks: { $t: key => key } } })
      expect(wrapper.find('a-alert-stub').exists()).toBe(false)
      wrapper.unmount()
    }
  })
})
