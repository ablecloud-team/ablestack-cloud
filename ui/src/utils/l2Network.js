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

const pvlanTypes = { c: 'community', i: 'isolated', p: 'promiscuous' }
const numericId = (value, maximum) => /^\d+$/.test(value) && Number(value) >= 1 && Number(value) <= maximum

export function l2TagFields (form, { specifyVlan, canBypass, canUsePvlan, isolation }) {
  if (!specifyVlan) return { params: {} }
  const primary = String(form.vlanid ?? '').trim().toLowerCase()
  const type = canUsePvlan ? form.isolatedpvlantype : 'none'
  const vxlan = isolation === 'vxlan'
  const maxId = isolation && !vxlan ? 4094 : 16777215
  const params = {}
  if (type && type !== 'none') {
    if (!numericId(primary, 4094)) return { error: 'pvlan.primary' }
    params.isolatedpvlantype = type
    if (type === 'community' || type === 'isolated') {
      const secondary = String(form.isolatedpvlanid ?? '').trim()
      if (!numericId(secondary, 4094)) return { error: 'pvlan.secondary' }
      if (Number(primary) === Number(secondary)) return { error: 'pvlan.same' }
      params.isolatedpvlan = String(Number(secondary))
    }
  } else if (primary === 'untagged' && !vxlan) {
    // The server encodes untagged VLANs as native networks.
  } else if (!numericId(primary, maxId)) {
    return { error: vxlan ? 'vni' : isolation ? 'vlan' : 'tag' }
  }
  params.vlan = primary === 'untagged' ? primary : String(Number(primary))
  if (canBypass && form.bypassvlanoverlapcheck === true) params.bypassvlanoverlapcheck = true
  return { params }
}

export function l2PvlanDetails (broadcastUri) {
  const match = /^pvlan:\/\/(\d+)-([cip])(\d+)$/i.exec(broadcastUri || '')
  return match ? { primary: match[1], type: pvlanTypes[match[2].toLowerCase()], secondary: match[3] } : null
}

export function l2ConfigDrive (network) {
  return network.service?.some(service => service.name === 'UserData' &&
    service.provider?.some(provider => provider.name === 'ConfigDrive'))
}
