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

import Widget from '@/views/storage/StorageNetworkStatus'
import { mount } from '@vue/test-utils'
import { getAPI, postAPI } from '@/api'
jest.mock('@/api', () => ({ getAPI: jest.fn(), postAPI: jest.fn() }))
const networkReadiness = () => ({
  schemaVersion: 1,
  kind: 'SHAREDFS_NETWORK_READINESS',
  success: true,
  status: 'OK',
  mode: 'STATIC',
  observedEpoch: 1792125000,
  bootId: '11111111-1111-4111-8111-111111111111',
  expected: { mode: 'STATIC', macAddress: '02:01:00:cc:00:17', ipAddress: '10.10.13.236', cidr: '10.10.0.0/16', gateway: '10.10.0.1', dns: ['10.10.13.10'] },
  actual: {
    mode: 'STATIC',
    interfaceName: 'ens3',
    macAddress: '02:01:00:cc:00:17',
    addresses: [{ ipAddress: '10.10.13.236', prefixLength: 16 }],
    defaultRoutes: [{ gateway: '10.10.0.1', interfaceName: 'ens3' }],
    dns: ['10.10.13.10'],
    dhcpUnit: { name: 'cloud-dhclient@ens3.service', active: false, masked: true },
    dhcpProcessCount: 0
  },
  mismatches: []
})

const render = (readiness = networkReadiness(), props = {}) => mount(Widget, { props: { readiness, ...props }, global: { mocks: { $t: key => key } } })
const row = (wrapper, key) => wrapper.findAll('tr').find(item => item.text().includes('label.storage.service.network.' + key))

describe('#913 network readiness UI', () => {
  beforeEach(() => { getAPI.mockClear(); postAPI.mockClear() })
  it('renders real AntD success and expected/observed route fields', () => {
    const wrapper = render()
    try {
      expect(wrapper.get('[role="status"]').classes()).toContain('ant-tag-green')
      expect(row(wrapper, 'expected.gateway').text()).toContain('10.10.0.1')
      expect(row(wrapper, 'actual.routes').text()).toContain('default via 10.10.0.1 dev ens3')
      expect(row(wrapper, 'dhcp.clients').text()).toContain('0')
      expect(row(wrapper, 'dhcp.masked').text()).toContain('label.yes')
      expect(wrapper.text()).toContain('2026-10-16T04:30:00.000Z')
    } finally { wrapper.unmount() }
  })
  it('shows a missing default route as ERROR beside the configured gateway', () => {
    const value = networkReadiness()
    Object.assign(value, { status: 'ERROR', success: false, mismatches: ['GATEWAY_MISMATCH'] })
    value.actual.defaultRoutes = []
    const wrapper = render(value)
    try {
      expect(wrapper.get('[role="status"]').classes()).toContain('ant-tag-red')
      expect(wrapper.text()).toContain('message.storage.service.network.reason.GATEWAY_MISMATCH')
      expect(row(wrapper, 'expected.gateway').text()).toContain('10.10.0.1')
      expect(row(wrapper, 'actual.routes').text()).toContain('label.none')
    } finally { wrapper.unmount() }
  })
  it('distinguishes deliberate no-gateway from unavailable observations', () => {
    const value = networkReadiness()
    value.expected.gateway = null; value.actual.defaultRoutes = []
    const wrapper = render(value)
    const unknown = render(null)
    try {
      expect(wrapper.get('[role="status"]').classes()).toContain('ant-tag-green')
      expect(row(wrapper, 'expected.gateway').text()).toContain('label.none')
      expect(row(wrapper, 'actual.routes').text()).toContain('label.none')
      expect(row(unknown, 'actual.routes').text()).toContain('—')
      expect(unknown.find('.ant-tag-green').exists()).toBe(false)
    } finally { wrapper.unmount(); unknown.unmount() }
  })
  it('renders DHCP mode, learned routes and a real zero process count without inventing STATIC configuration', () => {
    const value = networkReadiness()
    value.mode = value.expected.mode = value.actual.mode = 'DHCP'
    Object.assign(value.expected, { ipAddress: null, cidr: null, gateway: null, macAddress: null, dns: [] })
    Object.assign(value.actual.dhcpUnit, { active: true, masked: false })
    const wrapper = render(value)
    try {
      expect(row(wrapper, 'actual.mode').text()).toContain('DHCP')
      expect(row(wrapper, 'expected.gateway').text()).toContain('label.none')
      expect(row(wrapper, 'actual.routes').text()).toContain('default via 10.10.0.1 dev ens3')
      expect(wrapper.get('[role="status"]').classes()).toContain('ant-tag-green')
    } finally { wrapper.unmount() }
  })
  it('does not describe a direct default route as an invented gateway', () => {
    const value = networkReadiness()
    Object.assign(value, { status: 'ERROR', success: false, mismatches: ['UNEXPECTED_DEFAULT_ROUTE'] })
    value.expected.gateway = null; value.actual.defaultRoutes[0].gateway = null
    const wrapper = render(value)
    try {
      expect(row(wrapper, 'actual.routes').text()).toContain('default dev ens3')
      expect(row(wrapper, 'actual.routes').text()).not.toContain('via null')
    } finally { wrapper.unmount() }
  })
  it('demotes a retained successful observation when its read failed and permits only a refresh event', async () => {
    const wrapper = render(networkReadiness(), { readFailed: true })
    try {
      expect(wrapper.find('.ant-tag-green').exists()).toBe(false)
      expect(wrapper.text()).toContain('message.storage.service.network.read.failed')
      expect(row(wrapper, 'actual.routes').text()).toContain('—')
      await wrapper.get('button').trigger('click')
      expect(wrapper.emitted('refresh')).toHaveLength(1)
      expect(getAPI).not.toHaveBeenCalled()
      expect(postAPI).not.toHaveBeenCalled()
    } finally { wrapper.unmount() }
  })
  it('marks a stale snapshot unverified and updates fields when the parent publishes a new scope', async () => {
    const wrapper = render(networkReadiness(), { stale: true })
    try {
      expect(wrapper.find('.ant-tag-green').exists()).toBe(false)
      expect(wrapper.text()).toContain('message.storage.service.network.stale')
      const next = networkReadiness()
      next.expected.ipAddress = '10.10.14.236'; next.actual.addresses[0].ipAddress = '10.10.14.236'
      await wrapper.setProps({ readiness: next, stale: false })
      expect(wrapper.text()).toContain('10.10.14.236')
      expect(wrapper.text()).not.toContain('10.10.13.236')
      expect(wrapper.get('[role="status"]').classes()).toContain('ant-tag-green')
    } finally { wrapper.unmount() }
  })
  it('keeps available route and DHCP evidence when the mode classification is unknown', () => {
    const value = networkReadiness()
    Object.assign(value, { status: 'ERROR', success: false, mismatches: ['DHCP_UNIT_NOT_MASKED'] })
    value.actual.mode = 'UNKNOWN'
    value.actual.dhcpUnit.masked = false
    const wrapper = render(value)
    try {
      expect(wrapper.get('[role="status"]').classes()).toContain('ant-tag-red')
      expect(row(wrapper, 'actual.mode').text()).toContain('—')
      expect(row(wrapper, 'actual.addresses').text()).toContain('10.10.13.236/16')
      expect(row(wrapper, 'actual.routes').text()).toContain('default via 10.10.0.1 dev ens3')
      expect(row(wrapper, 'dhcp.masked').text()).toContain('label.no')
    } finally { wrapper.unmount() }
  })
  it('does not leak a raw future diagnostic as a user-facing mismatch reason', () => {
    const value = networkReadiness()
    Object.assign(value, { status: 'ERROR', success: false, mismatches: ['Traceback: unrecognized private detail'] })
    const wrapper = render(value)
    try {
      expect(wrapper.text()).not.toContain('Traceback')
      expect(wrapper.find('.ant-tag-green').exists()).toBe(false)
      expect(wrapper.text()).toContain('message.storage.service.network.reason.UNKNOWN')
    } finally { wrapper.unmount() }
  })
})
