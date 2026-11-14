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

const option = (id, reason = '') => ({ id, name: id, description: id, disabled: !!reason, reason })
export function sharedFsRegistrationReason (context) {
  const { user = {}, apis = {}, project = {}, hypervisor, zones = [], allZoneValue, apiParams } = context
  if (user.roletype !== 'Admin' || !('getSharedFileSystemComputeStatus' in apis)) return 'message.template.type.sharedfs.root'
  if (!('registerTemplate' in apis)) return 'message.template.type.sharedfs.unsupported'
  if (project.id) return 'message.template.type.sharedfs.personal'
  if ((context.ownerDomainId && context.ownerDomainId !== user.domainid) || (context.ownerAccount && context.ownerAccount !== user.account)) return 'message.template.type.sharedfs.owner'
  if (apiParams && ['templatetype', 'arch', 'format', 'hypervisor', 'isdynamicallyscalable', 'zoneids'].some(k => !apiParams[k])) return 'message.template.type.sharedfs.unsupported'
  if (!zones.length || zones.some(id => id === allZoneValue || String(id) === '-1')) return 'message.template.type.sharedfs.zone'
  if (hypervisor !== 'KVM') return 'message.template.type.sharedfs.kvm'
  return ''
}
export function templateTypeOptions (context = {}) {
  const { hypervisor, user = {}, apis = {}, operation, sourceType } = context
  if (operation === 'update' && sourceType === 'SHAREDFS') return [option('SHAREDFS')]
  const types = hypervisor === 'External' ? ['USER'] : ['USER', 'VNF']
  if (user.roletype === 'Admin' && hypervisor !== 'External')types.push('SYSTEM', 'BUILTIN', 'ROUTING')
  const result = types.map(id => option(id))
  const shared = user.roletype === 'Admin' && 'getSharedFileSystemComputeStatus' in apis
  if (!shared) return result
  // Existing generic/search consumers retain the legacy hypervisor-based list.
  if (!operation) { if (hypervisor === 'KVM')result.push(option('SHAREDFS')); return result }
  if (operation === 'register')result.push(option('SHAREDFS', sharedFsRegistrationReason(context)))
  else if (operation === 'upload')result.push(option('SHAREDFS', 'message.template.type.sharedfs.upload'))
  else if (operation === 'update')result.push(option('SHAREDFS', 'message.template.type.sharedfs.new.registration'))
  return result
}
export function templateTypeError (context, selected) {
  const api = { register: 'registerTemplate', upload: 'getUploadParamsForTemplate', update: 'updateTemplate' }[context.operation]
  if (api && !(api in (context.apis || {}))) return 'message.no.access'
  if (context.operation === 'update' && context.sourceType === 'SHAREDFS') return selected && selected !== 'SHAREDFS' ? 'message.template.type.sharedfs.immutable' : ''
  if (!selected) return ''
  const item = templateTypeOptions(context).find(o => o.id === selected)
  return !item ? 'message.template.type.invalid' : item.reason
}
export function sharedFsRegistrationParams (params, apiParams) {
  const result = { ...params, templatetype: 'SHAREDFS', hypervisor: 'KVM', arch: 'x86_64', format: 'QCOW2', isdynamicallyscalable: true }
  for (const key of ['ispublic', 'isfeatured', 'isextractable', 'directdownload']) {
    if (!apiParams || apiParams[key])result[key] = false
    else delete result[key]
  }
  // The UI explicitly displays the calling Root account as the fixed owner.
  delete result.account; delete result.domainid
  return result
}
