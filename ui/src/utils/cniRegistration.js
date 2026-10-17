// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements. See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership. The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License. You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied. See the License for the
// specific language governing permissions and limitations
// under the License.

import { canonicalUserData, userDataName, userDataParams } from './userDataRegistration'

// NetworkModel.metadataFileNames. AS_NUMBER remains a supported automatic CNI
// placeholder; the Kubernetes consumer does not submit a user-supplied value.
export const cniReservedParams = ['service-offering', 'availability-zone', 'local-hostname', 'local-ipv4',
  'public-hostname', 'public-ipv4', 'instance-id', 'vm-id', 'public-keys', 'cloud-identifier', 'cloud-name', 'hypervisor-host-name']
const strip = value => (value || '').replace(/^[\s\u0085]+|[\s\u0085]+$/g, '')
const fail = key => { throw new Error('message.cni.validation.' + key) }
const validate = fn => {
  try { return fn() } catch (error) { throw new Error(error.message.replace('message.userdata.validation.', 'message.cni.validation.')) }
}
export const cniName = value => validate(() => userDataName(strip(value)))
export function cniParams (params) {
  const keys = (params || []).map(strip)
  if (keys.some(key => cniReservedParams.includes(key))) fail('reserved')
  return validate(() => userDataParams(keys))
}
export function canonicalCni (value, isBase64, maxLength = 1048576) {
  const encoded = validate(() => canonicalUserData(value, isBase64, maxLength))
  let text
  try { text = decodeURIComponent(escape(atob(encoded))) } catch (error) { /* Opaque binary remains supported. */ }
  if (text !== undefined && !strip(text)) fail('empty')
  return encoded
}
