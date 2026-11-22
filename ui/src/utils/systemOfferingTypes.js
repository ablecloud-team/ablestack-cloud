// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements. See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership. The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License. You may obtain a copy of the License at
// http://www.apache.org/licenses/LICENSE-2.0
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied. See the License for the
// specific language governing permissions and limitations
// under the License.

export const systemOfferingTypes = new Map([
  ['domainrouter', 'label.domain.router'],
  ['consoleproxy', 'label.console.proxy'],
  ['secondarystoragevm', 'label.secondary.storage.vm'],
  ['internalloadbalancervm', 'label.internallbvm']
])

export const systemOfferingFilterTypes = new Map([
  ...systemOfferingTypes,
  ['elasticloadbalancervm', 'label.service.lb.elasticlbcheckbox']
])

export const systemOfferingStorageLabels = new Map([
  ['shared', 'label.shared'],
  ['local', 'label.local.storage']
])

export const systemOfferingDetailLabels = new Map([
  ['state', 'label.status'],
  ['systemvmtype', 'label.systemvmtype'],
  ['defaultuse', 'label.system.offering.default'],
  ['systemofferingvmcount', 'label.system.offering.vm.count'],
  ['storagetype', 'label.storagetype'],
  ['domain', 'label.domain'],
  ['zone', 'label.zone'],
  ['cpunumber', 'label.cpunumber'],
  ['cpuspeed', 'label.cpuspeed'],
  ['memory', 'label.memory'],
  ['networkrate', 'label.networkrate'],
  ['rootdisksize', 'label.rootdisksize'],
  ['diskofferingname', 'label.diskofferingid'],
  ['offerha', 'label.offerha'],
  ['iscustomized', 'label.iscustomized'],
  ['limitcpuuse', 'label.limitcpuuse'],
  ['dynamicscalingenabled', 'label.dynamicscalingenabled'],
  ['diskofferingstrictness', 'label.diskofferingstrictness']
])

export function systemOfferingStateLabel (state) {
  return state === 'Active' ? 'label.active' : state === 'Inactive' ? 'label.inactive' : 'label.unknown'
}

export function systemOfferingValue (field, record, translate) {
  const value = record[field]
  if (field === 'state') return translate(systemOfferingStateLabel(value))
  if (field === 'systemvmtype') return translate(systemOfferingFilterTypes.get(value) || 'label.unknown')
  if (field === 'storagetype') return translate(systemOfferingStorageLabels.get(value) || 'label.unknown')
  if (field === 'zone') return value || record.zoneid || translate('label.all.zone')
  if (field === 'domain') return value || record.domainid || translate('label.ispublic')
  if (field === 'networkrate' && record.systemvmtype !== 'domainrouter') return translate('label.na')
  if (typeof value === 'boolean' || value === 'true' || value === 'false') {
    return translate(String(value) === 'true' ? 'label.yes' : 'label.no')
  }
  if (value == null || value === '') return '—'
  const units = { cpuspeed: 'MHz', memory: 'MB', rootdisksize: 'GB', networkrate: 'Mb/s' }
  return units[field] ? value + ' ' + units[field] : String(value)
}

const listQueryKeys = ['keyword', 'name', 'zoneid', 'domainid', 'cpunumber', 'cpuspeed', 'memory', 'systemvmtype', 'storagetype', 'state', 'filter', 'page', 'pagesize']

export function systemOfferingListQuery (query) {
  return Object.fromEntries(listQueryKeys.filter(key => typeof query[key] === 'string' && query[key]).map(key => [key, query[key]]))
}
