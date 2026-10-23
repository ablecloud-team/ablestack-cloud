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
import { getFilteredExternalDetails } from '@/utils/extension'
import { trueValue } from '@/utils/offeringQos'

export const scopeIds = value => [...new Set(String(value || '').split(',').filter(Boolean))].sort()
export const sameIds = (a, b) => JSON.stringify([...a].sort()) === JSON.stringify([...b].sort())
export const canManageStorageOffering = (record, role) => role === 'Admin' || (role === 'DomainAdmin' && scopeIds(record?.domainid).length > 0)
const sortedObject = value => Object.fromEntries(Object.entries(value || {}).sort(([a], [b]) => a.localeCompare(b)))
export function offeringSnapshot (record) {
  return JSON.stringify({
    ...Object.fromEntries(['id', 'name', 'displaytext', 'state', 'tags', 'storagetags', 'hosttags', 'diskofferingid'].map(k => [k, record?.[k] ?? ''])),
    domains: scopeIds(record?.domainid),
    zones: scopeIds(record?.zoneid),
    purge: trueValue(record?.purgeresources),
    external: sortedObject(getFilteredExternalDetails(record?.serviceofferingdetails))
  })
}
export function computeEditParams (record, form, supported, tagsAllowed) {
  const params = { id: record.id }
  for (const key of ['name', 'displaytext', ...(tagsAllowed ? ['storagetags', 'hosttags'] : [])]) {
    if (key in supported && String(form[key] ?? '') !== String(record[key] ?? '')) params[key] = form[key] || ''
  }
  if ('purgeresources' in supported && form.purgeresources !== trueValue(record.purgeresources)) params.purgeresources = form.purgeresources
  if (form.externalMode === 'clear') {
    if (!('cleanupexternaldetails' in supported) || !form.confirmClear) throw new Error('message.storage.management.clear.confirm')
    params.cleanupexternaldetails = true
  } else if (form.externalMode === 'edit') {
    const entries = Object.entries(form.external || {})
    if (!('externaldetails' in supported) || !entries.length || entries.some(([key]) => !key.trim())) throw new Error('message.storage.management.external.empty')
    if (JSON.stringify(sortedObject(form.external)) !== JSON.stringify(sortedObject(getFilteredExternalDetails(record.serviceofferingdetails)))) {
      for (const [key, value] of entries) params['externaldetails[0].' + key] = value
    }
  }
  return params
}
export function accessParams (record, form, { admin, managedDomainIds, supported }) {
  const params = { id: record.id }
  const oldDomains = scopeIds(record.domainid)
  const oldManaged = oldDomains.filter(id => managedDomainIds.includes(id))
  if (!admin && (form.ispublic || !oldDomains.length || !form.domainid.length)) throw new Error('message.storage.management.domain.required')
  const selected = [...new Set(form.domainid)].sort()
  if (selected.some(id => !managedDomainIds.includes(id))) throw new Error('message.storage.management.selection.changed')
  if ('domainid' in supported && (admin ? form.ispublic !== !oldDomains.length || (!form.ispublic && !sameIds(selected, oldDomains)) : !sameIds(selected, oldManaged))) {
    if (!form.ispublic && !selected.length) throw new Error('message.storage.management.domain.required')
    params.domainid = form.ispublic ? 'public' : selected.join(',')
  }
  if (admin && 'zoneid' in supported && !sameIds(form.zoneid, scopeIds(record.zoneid))) params.zoneid = form.zoneid.length ? [...form.zoneid].sort().join(',') : 'all'
  return params
}
export function assignmentReason (volume, role) {
  if (!['Admin', 'DomainAdmin'].includes(role)) return 'message.storage.management.admin.only'
  if (!volume || volume.type !== 'DATADISK' || volume.state !== 'Ready' || volume.virtualmachineid) return 'message.volume.assign.detached'
  if (typeof volume.volumemutationblockedreason !== 'string' || volume.volumemutationblockedreason || ['pending', 'running'].includes(String(volume.clonefastflattenstatus || volume.details?.['clone.fast.flatten.status'] || '').toLowerCase())) return 'message.volume.assign.blocked'
  return ''
}
export function assignmentSnapshot (volume) {
  return JSON.stringify(Object.fromEntries(['id', 'account', 'domainid', 'projectid', 'state', 'type', 'virtualmachineid', 'storageid', 'path', 'size', 'diskofferingid', 'volumemutationblockedreason'].map(k => [k, volume?.[k] ?? ''])))
}
export function assignmentParams (volume, type, target) {
  if (!target || !['account', 'project'].includes(type)) throw new Error('message.storage.management.selection.changed')
  if (type === 'account' && (target.state !== 'enabled' || target.accounttype === 5 || (target.name === volume.account && target.domainid === volume.domainid))) throw new Error('message.volume.assign.same')
  if (type === 'project' && (target.state !== 'Active' || target.id === volume.projectid)) throw new Error('message.volume.assign.same')
  return { volumeid: volume.id, ignoreproject: true, [type === 'account' ? 'accountid' : 'projectid']: target.id }
}
