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

export function allocationIntent (resource) {
  const a = resource?.allocation
  return a?.contractversion === '1' && ['raw', 'qcow2'].includes(a.requestedformat)
    ? { policy: a.requestedpolicy, format: a.requestedformat } : null
}
export function allocationForm (resource) {
  const i = allocationIntent(resource)
  return i ? { allocationmode: 'explicit', allocationpolicy: i.policy, allocationformat: i.format, allocationoriginal: JSON.stringify(i) }
    : { allocationmode: 'legacy', provisioningtype: resource?.provisioningtype || 'thin', allocationoriginal: '' }
}
export function allocationParams (values, linked = false) {
  if (linked) return {}
  if (values.allocationmode === 'explicit') {
    if (!values.allocationpolicy || !values.allocationformat) throw new Error('message.allocation.select')
    return { allocationpolicy: values.allocationpolicy, allocationformat: values.allocationformat }
  }
  return { provisioningtype: values.provisioningtype || 'thin' }
}
export function allocationLabel (resource, t) {
  const i = allocationIntent(resource)
  return i ? t('label.allocation.policy.' + i.policy) + ' · ' + i.format.toUpperCase()
    : t('label.allocation.legacy') + ': ' + (resource?.provisioningtype || t('label.allocation.unknown'))
}
export function allocationDetailFields (resource, t, bytes, { volume = false, inheritance = false } = {}) {
  const a = resource?.allocation || {}
  const verified = a.evidence === 'verified'
  const unknown = t('label.allocation.unknown')
  const policy = value => value && value !== 'unknown' ? t('label.allocation.policy.' + value) : unknown
  const fields = [
    ['requested', allocationLabel(resource, t), !volume ? t('message.allocation.offering.conditions') + (inheritance ? ' ' + t('message.allocation.inheritance') : '') : undefined],
    ['fixed', verified ? policy(a.fixedpolicy) : unknown],
    ['applied', verified ? policy(a.effectivepolicy) : unknown],
    ['method', verified ? a.appliedmethod : unknown],
    ['actual.format', a.actualformat && a.actualformat !== 'unknown' ? a.actualformat.toUpperCase() : unknown],
    ...(volume ? [['logical', resource.size == null ? unknown : bytes(resource.size)]] : []),
    ['observed', verified && a.observedallocatedbytes != null ? bytes(Number(a.observedallocatedbytes)) : unknown, volume ? t('message.allocation.observation') : undefined],
    ['observed.at', verified ? a.observedat || unknown : unknown],
    ['evidence', t('label.allocation.evidence.' + (a.evidence || 'unverified'))]
  ]
  return Object.fromEntries(fields.map(([key, value, help]) => ['allocation.' + key, { label: 'label.allocation.' + key, value, help }]))
}
export function allocationOfferingReason (volume, offering) {
  if (!offering) return 'message.allocation.select'
  const a = allocationIntent(volume); const b = allocationIntent(offering)
  if (JSON.stringify(a) !== JSON.stringify(b) || (!a && volume.provisioningtype !== offering.provisioningtype)) return 'message.allocation.conversion.unsupported'
  return ''
}
export function poolAcceptsIntent (pool, resource) {
  const i = allocationIntent(resource)
  if (!i) return pool.type !== 'RBD' || !resource?.provisioningtype || resource.provisioningtype === 'thin'
  if (pool.type === 'RBD') return i.format === 'raw' && ['off', 'full'].includes(i.policy)
  return pool.type === 'SharedMountPoint'
}
export async function readAllocationList (api, command, key, params = {}, current = () => true) {
  const all = []; const seen = new Set()
  let page = 1; let count = 0
  do {
    const response = await api(command, { ...params, page: page++, pagesize: 100 }, { preserveOnFailure: true })
    if (!current()) return null
    const body = response[command.toLowerCase() + 'response']
    if (!body) throw new Error('Missing ' + command + ' response')
    const fresh = (body[key] || []).filter(v => !seen.has(v.id))
    count = Number(body.count || 0)
    if (count > all.length && !fresh.length) throw new Error('Incomplete ' + command + ' response')
    fresh.forEach(v => seen.add(v.id)); all.push(...fresh)
  } while (all.length < count)
  return all
}
export async function readAllocationCapability (api, pool, format, operation = 'create', current = () => true) {
  const hosts = await readAllocationList(api, 'listHosts', 'host',
    { zoneid: pool.zoneid, ...(pool.clusterid ? { clusterid: pool.clusterid } : {}), type: 'Routing', state: 'Up' }, current)
  if (!hosts || !current()) return null
  const eligible = hosts.filter(h => h.state === 'Up' && h.hypervisor === 'KVM')
  const results = await Promise.allSettled(eligible.map(host => api('getVolumeAllocationCapabilities',
    { storageid: pool.id, hostid: host.id, allocationformat: format, operation }, { preserveOnFailure: true })))
  if (!current()) return null
  const caps = results.map((r, index) => r.status === 'fulfilled' && r.value.getvolumeallocationcapabilitiesresponse?.volumeallocationcapability?.hostid === eligible[index].id ? r.value.getvolumeallocationcapabilitiesresponse.volumeallocationcapability : null)
    .filter(c => c?.contractversion === 1 && c.storageid === pool.id && c.allocationformat === format && c.operation === operation &&
      c.supported === true && (pool.type === 'RBD' ? c.backend === 'RBD' : c.backend === 'GFS2'))
  const policies = {}
  caps.forEach(c => Object.assign(policies, c.agentpolicies || {}))
  if (pool.type === 'RBD') Object.keys(policies).filter(p => format !== 'raw' || !['off', 'full'].includes(p)).forEach(p => delete policies[p])
  if (format === 'raw') delete policies.metadata
  if (format === 'qcow2') delete policies.off
  if (!Object.keys(policies).length) throw new Error('message.allocation.capability.failed')
  return { policies, backend: caps[0].backend }
}
