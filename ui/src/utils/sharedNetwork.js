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

import { buildRangeParams, ipv4, ipv6, rangeFields } from '@/utils/guestIpRanges'

export function sharedScopeParams ({ scope, domainId, account, project, manageOwner, fixed, subdomainAccess }) {
  if (fixed) {
    if (fixed.projectid) return { acltype: 'account', projectid: fixed.projectid, ...(fixed.domainid ? { domainid: fixed.domainid } : {}) }
    if (fixed.account && fixed.domainid) return { acltype: 'account', account: fixed.account, domainid: fixed.domainid }
    return scope === 'all' ? { acltype: 'domain' } : { acltype: 'account' }
  }
  if (scope === 'all') return { acltype: 'domain' }
  if (scope === 'domain') return domainId ? { acltype: 'domain', domainid: domainId, subdomainaccess: !!subdomainAccess } : null
  if (scope === 'project') {
    if (!project?.id || (manageOwner && (!domainId || project.domainid !== domainId))) return null
    return { acltype: 'account', projectid: project.id, ...(project.domainid ? { domainid: project.domainid } : {}) }
  }
  if (scope !== 'account') return null
  if (!manageOwner) return { acltype: 'account' }
  if (!account?.name || !domainId || account.domainid !== domainId) return null
  return { acltype: 'account', domainid: domainId, account: account.name }
}

const text = value => String(value ?? '').trim()

export function sharedAddressParams (form, { router, dns, zone = {}, api = {} }) {
  if (router && form.family === 'ipv6') throw new Error('message.shared.ipv6.router')
  const values = buildRangeParams(form, {})
  const params = Object.fromEntries(rangeFields.filter(key => key in values).map(key => [key, values[key]]))
  for (const { enabled, parse, routerKey, start, end, dns1, dns2 } of [
    { enabled: form.family !== 'ipv6', parse: ipv4, routerKey: 'routerip', start: 'startip', end: 'endip', dns1: 'dns1', dns2: 'dns2' },
    { enabled: form.family !== 'ipv4', parse: ipv6, routerKey: 'routeripv6', start: 'startipv6', end: 'endipv6', dns1: 'ip6dns1', dns2: 'ip6dns2' }
  ]) {
    if (!enabled) continue
    const routerIp = router && api[routerKey] && text(form[routerKey])
    if (routerIp) {
      const ip = parse(routerIp)
      if (ip === null || ip < parse(params[start]) || ip > parse(params[end])) throw new Error('message.shared.router.range')
      params[routerKey] = routerIp
    }
    if (dns) {
      const primary = api[dns1] && text(form[dns1])
      const secondary = api[dns2] && text(form[dns2])
      if (secondary && !primary) throw new Error('message.shared.dns.primary')
      for (const [key, value] of [[dns1, primary], [dns2, secondary]]) {
        if (!value) continue
        if (parse(value) === null) throw new Error('message.guest.range.invalid.address')
        params[key] = value
      }
    }
  }
  if (form.family !== 'ipv4' && !params.ip6dns1 && !zone.ip6dns1 && !zone.ip6dns2) throw new Error('message.shared.ipv6.dns')
  if (dns && api.networkdomain && text(form.networkdomain)) params.networkdomain = text(form.networkdomain)
  return params
}

export function sharedScopeLabel (record) {
  if (record.projectid || record.project) return 'label.project'
  if (String(record.acltype).toLowerCase() === 'domain') {
    // Mold stores the all-scope network as ROOT with subdomain access.
    const domainPath = record.domainpath || record.domain
    const rootWide = domainPath === 'ROOT' && record.subdomainaccess === true
    return !record.domainid || rootWide ? 'label.all' : 'label.domain'
  }
  return String(record.acltype).toLowerCase() === 'account' ? 'label.account' : null
}
