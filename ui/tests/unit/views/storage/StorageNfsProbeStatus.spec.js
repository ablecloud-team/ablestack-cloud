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

import { mount } from '@vue/test-utils'
import Widget from '@/views/storage/StorageNfsProbeStatus'
import { storageNfsProbeRows } from '@/utils/storageNfsProbe'

const uuid = 'ee35189f-4914-4e9c-b4e6-64b86164f031'
const normal = (protocolMode = 'V4_ONLY') => ({
  exportUuid: uuid,
  exportName: 'restricted-share',
  protocolMode,
  success: true,
  clients: ['192.168.3.11/32'],
  probes: (protocolMode === 'V3V4_DUAL' ? ['NFSv4', 'NFSv3'] : ['NFSv4'])
    .map(mode => ({ mode, success: true, cleanupComplete: true }))
})
const endpoint = probe => ({
  listenIp: '0.0.0.0',
  port: 2049,
  processRunning: true,
  listening: true,
  listenerOwned: true,
  exportInventoryReady: true,
  probeObservationVerified: true,
  probeResults: [probe]
})
const health = probe => ({ nfsGanesha: { status: 'ok', endpoints: [endpoint(probe)] } })
const skipped = (reason = 'ACL_RESTRICTED_NO_LOCAL_SOURCE') => ({
  ...normal(),
  status: reason === 'ACL_RESTRICTED_NO_LOCAL_SOURCE' ? 'SKIPPED_ACL' : 'SKIPPED_DYNAMIC',
  probeSkipped: true,
  skipReason: reason,
  probes: []
})
const render = (value = health(normal()), props = {}) => mount(Widget, {
  props: { health: value, exportUuid: uuid, ...props },
  global: { mocks: { $t: key => key } }
})
const state = (value, options) => storageNfsProbeRows(value, uuid, options)[0].state

describe('#918 NFS local verification and exact applied ACL', () => {
  it.each(['V4_ONLY', 'V3V4_DUAL'])('requires all %s local mounts and their cleanup before showing verified', protocolMode => {
    const value = health(normal(protocolMode))
    const wrapper = render(value)
    try {
      expect(wrapper.get('[role="status"]').classes()).toContain('ant-tag-green')
      expect(wrapper.text()).toContain('label.storage.service.nfs.probe.VERIFIED')
      expect(wrapper.text()).toContain('label.storage.service.nfs.applied.clients: 192.168.3.11/32')
      expect(wrapper.text()).not.toContain('external.separate')
    } finally { wrapper.unmount() }
    value.nfsGanesha.endpoints[0].probeResults[0].probes[0].cleanupComplete = false
    expect(state(value)).toBe('UNKNOWN')
  })

  it.each([
    ['ACL_RESTRICTED_NO_LOCAL_SOURCE', 'SKIPPED_ACL'],
    ['DBUS_DYNAMIC_UPDATE_VERIFIED', 'SKIPPED_DYNAMIC']
  ])('shows %s as a separate non-error result without claiming a local mount', (reason, resultState) => {
    const value = health(skipped(reason))
    const wrapper = render(value)
    try {
      expect(wrapper.get('[role="status"]').classes()).toContain('ant-tag-blue')
      expect(wrapper.text()).toContain('label.storage.service.nfs.probe.' + resultState)
      expect(wrapper.text()).toContain('message.storage.service.nfs.probe.' + reason)
      expect(wrapper.text()).toContain('message.storage.service.nfs.probe.external.separate')
      expect(wrapper.text()).toContain('192.168.3.11/32')
      expect(wrapper.text()).not.toContain('192.168.3.14')
      expect(wrapper.find('.ant-tag-green').exists()).toBe(false)
      expect(wrapper.find('.ant-tag-red').exists()).toBe(false)
      expect(value.nfsGanesha.endpoints[0].probeResults[0].clients).toEqual(['192.168.3.11/32'])
    } finally { wrapper.unmount() }
  })

  it('preserves multiple CIDRs and wildcard values from the verified runtime without rewriting the ACL', () => {
    const probe = skipped()
    probe.clients = ['192.168.3.11/32', '192.168.5.0/24', '*']
    const before = JSON.stringify(probe)
    const rows = storageNfsProbeRows(health(probe), uuid)
    expect(rows[0].clients).toEqual(probe.clients)
    expect(rows[0].clients).not.toBe(probe.clients)
    expect(JSON.stringify(probe)).toBe(before)
  })

  it('keeps a failed local mount red even beside a stale skip claim', () => {
    const probe = { ...skipped(), success: false, status: 'FAILED' }
    const wrapper = render(health(probe))
    try {
      expect(wrapper.get('[role="status"]').classes()).toContain('ant-tag-red')
      expect(wrapper.text()).toContain('label.storage.service.nfs.probe.FAILED')
      expect(wrapper.text()).not.toContain('message.storage.service.nfs.probe.ACL_RESTRICTED')
      expect(wrapper.text()).not.toContain('label.storage.service.nfs.applied.clients')
    } finally { wrapper.unmount() }
  })

  it.each([
    { stale: true }, { readFailed: true }, { stale: true, readFailed: true }
  ])('clears success and applied ACL on stale or failed reads: %j', options => {
    expect(storageNfsProbeRows(health(skipped()), uuid, options)).toEqual([{ endpoint: '—', state: 'UNKNOWN', reason: '', clients: [] }])
    const wrapper = render(health(skipped()), options)
    try {
      expect(wrapper.find('.ant-tag-blue').exists()).toBe(false)
      expect(wrapper.text()).not.toContain('192.168.3.11')
    } finally { wrapper.unmount() }
  })

  it.each([
    null, {}, { nfsGanesha: { status: 'ok', endpoints: [] } },
    { nfsGanesha: { status: 'ok', endpoints: [endpoint({ ...normal(), exportUuid: 'another-export' })] } }
  ])('does not promote DB Ready or absent/other export observations to verified', value => {
    expect(state(value)).toBe('UNKNOWN')
  })

  it.each(['probeObservationVerified', 'processRunning', 'listening', 'listenerOwned', 'exportInventoryReady'])('requires fresh authoritative %s before accepting a skip', field => {
    const value = health(skipped())
    value.nfsGanesha.endpoints[0][field] = false
    expect(state(value)).toBe('UNKNOWN')
    value.nfsGanesha.endpoints[0][field] = 'true'
    expect(state(value)).toBe('UNKNOWN')
  })

  it.each([
    { success: 'true' }, { probeSkipped: 'true' }, { skipReason: 'FUTURE_REASON' },
    { status: 'SKIPPED_DYNAMIC' }, { probes: normal().probes }, { clients: [] }, { clients: [''] },
    { clients: ['bad\naddress'] }, { clients: [123] }
  ])('keeps malformed or contradictory skipped results unknown: %j', patch => {
    const rows = storageNfsProbeRows(health({ ...skipped(), ...patch }), uuid)
    expect(rows[0].state).toBe('UNKNOWN')
    expect(rows[0].clients).toEqual([])
  })

  it('requires both distinct Dual protocol mounts without accepting a duplicate NFSv4 observation', () => {
    const probe = normal('V3V4_DUAL')
    probe.probes[1] = { ...probe.probes[0] }
    expect(state(health(probe))).toBe('UNKNOWN')
    probe.probes.pop()
    expect(state(health(probe))).toBe('UNKNOWN')
    probe.probes = [{ ...normal().probes[0], success: false }]
    expect(state(health(probe))).toBe('FAILED')
  })

  it('does not let another failed export hide a verified restricted export on the same endpoint', () => {
    const value = health(skipped())
    value.nfsGanesha.status = 'degraded'
    value.nfsGanesha.endpoints[0].probeSuccess = false
    value.nfsGanesha.endpoints[0].probeResults.push({ ...normal(), exportUuid: 'another-export', success: false, status: 'FAILED' })
    expect(state(value)).toBe('SKIPPED_ACL')
  })

  it('renders separately verified endpoint observations and rejects ambiguous duplicate observations', () => {
    const value = health(normal())
    value.nfsGanesha.endpoints.push({ ...endpoint(skipped()), port: 20490 })
    expect(storageNfsProbeRows(value, uuid).map(row => row.state)).toEqual(['VERIFIED', 'SKIPPED_ACL'])
    value.nfsGanesha.endpoints[0].probeResults.push(normal())
    expect(storageNfsProbeRows(value, uuid)[0].state).toBe('UNKNOWN')
    value.nfsGanesha.endpoints.push(endpoint(normal()))
    expect(storageNfsProbeRows(value, uuid)).toEqual([{ endpoint: '—', state: 'UNKNOWN', reason: '', clients: [] }])
  })

  it('escapes runtime principal text instead of interpreting it as HTML', () => {
    const probe = skipped()
    probe.clients = ['<img src=x onerror=alert(1)>']
    const wrapper = render(health(probe))
    try {
      expect(wrapper.find('img').exists()).toBe(false)
      expect(wrapper.text()).toContain('<img src=x onerror=alert(1)>')
    } finally { wrapper.unmount() }
  })
})
