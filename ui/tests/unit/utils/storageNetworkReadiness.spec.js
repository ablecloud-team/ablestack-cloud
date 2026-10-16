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

import { storageNetworkObservation, storageNetworkStatus, storageNetworkObservedTime, networkMismatchCodes } from '@/utils/storageNetworkReadiness'
export const networkReadiness = () => ({
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

describe('#913 typed native network readiness', () => {
  it('accepts the native public STATIC observation without deriving another route authority', () => {
    const value = networkReadiness()
    expect(storageNetworkObservation(value)).toBe(value)
    expect(storageNetworkStatus(value)).toBe('OK')
    expect(storageNetworkObservedTime(value)).toBe('2026-10-16T04:30:00.000Z')
  })
  it.each(networkMismatchCodes)('preserves confirmed %s as ERROR', code => {
    const value = networkReadiness()
    Object.assign(value, { success: false, status: 'ERROR', mismatches: [code] })
    expect(storageNetworkObservation(value)).toBe(value)
    expect(storageNetworkStatus(value)).toBe('ERROR')
  })
  it('keeps invalid saved configuration strings visible under the native INVALID_STATE error', () => {
    const value = networkReadiness()
    Object.assign(value, { success: false, status: 'ERROR', mismatches: ['INVALID_STATE'] })
    value.expected.gateway = 'invalid saved gateway'
    expect(storageNetworkStatus(value)).toBe('ERROR')
    expect(storageNetworkObservation(value).expected.gateway).toBe('invalid saved gateway')
  })
  it.each([
    value => { value.schemaVersion = '1' },
    value => { value.kind = 'other' },
    value => { value.success = 'true' },
    value => { value.success = false },
    value => { value.status = 'ok' },
    value => { value.mode = 'UNKNOWN' },
    value => { value.mismatches = ['GATEWAY_MISMATCH'] },
    value => { value.observedEpoch = '1792125000' },
    value => { value.observedEpoch = 0 },
    value => { value.observedEpoch = Infinity },
    value => { value.expected = [] },
    value => { delete value.expected.gateway },
    value => { value.expected.dns = '10.10.13.10' },
    value => { value.actual.addresses[0].prefixLength = '16' },
    value => { value.actual.addresses[0].prefixLength = 33 },
    value => { value.actual.defaultRoutes[0].interfaceName = null },
    value => { value.actual.dhcpUnit.active = 'false' },
    value => { value.actual.dhcpProcessCount = false },
    value => { value.actual.dhcpProcessCount = null },
    value => { value.actual.interfaceName = null },
    value => { value.actual.mode = 'UNKNOWN' },
    value => { value.actual.mode = 'DHCP' }
  ])('never promotes malformed or unobserved success to OK', mutate => {
    const value = networkReadiness(); mutate(value)
    expect(storageNetworkStatus(value)).toBe('UNKNOWN')
  })
  it.each([null, undefined, [], true, 'OK', {}])('keeps missing/legacy observation %p unverified', value => {
    expect(storageNetworkObservation(value)).toBeNull()
    expect(storageNetworkStatus(value)).toBe('UNKNOWN')
    expect(storageNetworkObservedTime(value)).toBe('—')
  })
  it('keeps stale, failed-read or unsupported status codes unverified', () => {
    const value = networkReadiness()
    expect(storageNetworkStatus(value, { stale: true })).toBe('UNKNOWN')
    expect(storageNetworkStatus(value, { readFailed: true })).toBe('UNKNOWN')
    Object.assign(value, { status: 'ERROR', success: false, mismatches: ['NEW_FUTURE_REASON'] })
    expect(storageNetworkStatus(value)).toBe('UNKNOWN')
  })
})
