// Licensed to the Apache Software Foundation (ASF) under one or more
// contributor license agreements. See NOTICE. Apache License, Version 2.0.
// http://www.apache.org/licenses/LICENSE-2.0
import { shallowMount } from '@vue/test-utils'
import Notice from '@/components/storage/NfsLocalProbeNotice'
import wire from '../../../fixtures/storage-1455-local-probe-wire.json'
import policyWire from '../../../fixtures/storage-1460-client-policy-wire.json'
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

// #1460 consumes actual ApiResponseSerializer output, not a hand-written DTO.
describe('process-bound policy observation contract', () => {
  const shares = () => JSON.parse(JSON.stringify(policyWire.liststoragenfsexportsresponse.storagenfsexport))
  function show (extra = {}) {
    return shallowMount(Notice, { props: { shares: shares(), ...extra }, global: { mocks: { $t: key => key } } })
  }
  test('Ready service does not make external-only or stale observations verified', () => {
    const original = shares()
    const wrapper = show({ shares: original })
    expect(original[0].state).toBe('Ready')
    expect(wrapper.vm.rows.map(row => row.port)).toEqual([2049, 2050])
    expect(wrapper.vm.configurationLabel(wrapper.vm.rows[0])).toBe('label.storage.nfs.probe.applied')
    expect(wrapper.vm.localLabel(wrapper.vm.rows[0].status)).toBe('label.storage.nfs.probe.excluded')
    expect(wrapper.vm.configurationLabel(wrapper.vm.rows[1])).toBe('label.storage.nfs.probe.unknown')
    expect(wrapper.vm.localLabel(wrapper.vm.rows[1].status)).toBe('label.storage.nfs.probe.unknown')
    expect(wrapper.vm.rows[0].operationid).toBe('00000000-0000-0000-0000-000000001460')
    expect(JSON.stringify(original)).toBe(JSON.stringify(shares()))
    wrapper.unmount()
  })
  test('communication loss retains timestamps but cannot leave a success diagnostic open', async () => {
    const wrapper = show()
    await wrapper.setData({ selected: wrapper.vm.rows[0] })
    expect(wrapper.vm.selected).not.toBeNull()
    const oldTime = wrapper.vm.rows[0].observedAt
    await wrapper.setProps({ unavailable: true })
    expect(wrapper.vm.selected).toBeNull()
    expect(wrapper.vm.rows.every(row => row.status === 'UNKNOWN')).toBe(true)
    expect(wrapper.vm.rows[0].observedAt).toBe(oldTime)
    expect(wrapper.find('a-alert-stub').attributes('message')).toBe('label.storage.nfs.probe.unknown')
    expect(wrapper.vm.configurationLabel(wrapper.vm.rows[0])).toBe('label.storage.nfs.probe.unknown')
    expect(wrapper.emitted()).toEqual({})
    wrapper.unmount()
  })
  test('new source data closes the previous row diagnostic without mutating API data', async () => {
    const wrapper = show()
    await wrapper.setData({ selected: wrapper.vm.rows[0] })
    await wrapper.setProps({ shares: shares() })
    expect(wrapper.vm.selected).toBeNull()
    expect(wrapper.vm.observed(undefined)).toBe('—')
    expect(wrapper.vm.observed('invalid-date')).toBe('—')
    wrapper.unmount()
  })
})
