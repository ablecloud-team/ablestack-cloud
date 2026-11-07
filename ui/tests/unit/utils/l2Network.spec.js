// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements. See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership. The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License. You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied. See the License for the
// specific language governing permissions and limitations
// under the License.
import { l2PvlanDetails, l2TagFields } from '@/utils/l2Network'

const tagged = { specifyVlan: true, canBypass: true, canUsePvlan: true, isolation: 'vlan' }
const form = { vlanid: '772', isolatedpvlantype: 'community', isolatedpvlanid: '773', bypassvlanoverlapcheck: true }

test('sends a secondary VLAN only for Community or Isolated', () => {
  expect(l2TagFields(form, tagged).params).toEqual({ vlan: '772', isolatedpvlantype: 'community', isolatedpvlan: '773', bypassvlanoverlapcheck: true })
  expect(l2TagFields({ ...form, isolatedpvlantype: 'promiscuous' }, tagged).params).not.toHaveProperty('isolatedpvlan')
  expect(l2TagFields({ ...form, isolatedpvlantype: 'none' }, tagged).params).not.toHaveProperty('isolatedpvlantype')
  expect(l2TagFields(form, { ...tagged, specifyVlan: false, canBypass: false }).params).toEqual({})
})

test('requires a distinct, in-range secondary VLAN and normalizes equivalent IDs', () => {
  expect(l2TagFields({ ...form, isolatedpvlanid: '' }, tagged).error).toBe('pvlan.secondary')
  expect(l2TagFields({ ...form, isolatedpvlanid: '0772' }, tagged).error).toBe('pvlan.same')
  expect(l2TagFields({ ...form, vlanid: '4095' }, tagged).error).toBe('pvlan.primary')
  expect(l2TagFields({ ...form, vlanid: 'untagged' }, tagged).error).toBe('pvlan.primary')
})

test('accepts a VXLAN VNI and strips unsupported PVLAN and bypass fields', () => {
  expect(l2TagFields({ ...form, vlanid: '16777215' }, { ...tagged, isolation: 'vxlan', canBypass: false, canUsePvlan: false }).params).toEqual({ vlan: '16777215' })
  expect(l2TagFields({ ...form, vlanid: 'untagged' }, { ...tagged, canUsePvlan: false }).params.vlan).toBe('untagged')
})

test('reads all three PVLAN URI forms and leaves non-PVLAN networks alone', () => {
  expect(l2PvlanDetails('pvlan://772-c773')).toEqual({ primary: '772', type: 'community', secondary: '773' })
  expect(l2PvlanDetails('pvlan://772-i773').type).toBe('isolated')
  expect(l2PvlanDetails('pvlan://772-p772').type).toBe('promiscuous')
  expect(l2PvlanDetails('vlan://772')).toBeNull()
})
