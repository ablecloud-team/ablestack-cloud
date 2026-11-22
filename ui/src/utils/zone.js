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

import store from '@/store'

export function isZoneCreated () {
  if (!store.getters.zones || store.getters.zones.length === 0) {
    return false
  }
  return true
}

const zoneFilterKeys = new Set(['name', 'domainid', 'networktype', 'storageaccessgroup'])

function isZoneFilter (key) {
  return zoneFilterKeys.has(key) || /^tags\[\d+\]\.(key|value)$/.test(key)
}

export function zoneListQuery (query = {}) {
  return Object.fromEntries(Object.entries(query).filter(([key, value]) => {
    return (isZoneFilter(key) || ['keyword', 'q', 'sortkey', 'sortorder', 'page', 'pagesize'].includes(key)) &&
      (typeof value === 'number' || (typeof value === 'string' && value.trim()))
  }))
}

export function zoneSearchQuery (current, options, pageSize) {
  const query = { ...zoneListQuery(current), page: '1', pagesize: String(pageSize) }
  if (Object.hasOwn(options, 'searchQuery')) {
    delete query.name
    delete query.keyword
    delete query.q
    const keyword = options.searchQuery?.trim()
    if (keyword) query.keyword = query.q = keyword
  } else {
    Object.keys(query).filter(isZoneFilter).forEach(key => delete query[key])
    Object.assign(query, zoneListQuery(options))
    if (query.name || options.zoneReset) {
      delete query.keyword
      delete query.q
    }
  }
  return query
}

export function zoneReadParams (query, { id, page, pagesize, showIcon }) {
  if (id) return { id, showIcon }
  const filters = Object.fromEntries(Object.entries(zoneListQuery(query))
    .filter(([key]) => isZoneFilter(key) || key === 'keyword'))
  return { ...filters, page, pagesize, showIcon }
}

export function zoneDnsSearchOrder (zone) {
  return parseZoneDnsSearchOrder(zone.resourcedetails?.['network.guestnetwork.dns.search.order'])
}

export function parseZoneDnsSearchOrder (value) {
  return (value || '').split(/[\n,]/).map(suffix => suffix.trim()).filter(Boolean)
}

export function validZoneDnsSuffix (suffix) {
  return suffix.length <= 190 && suffix.split('.').every(label => /^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$/i.test(label))
}

export function physicalNetworkZoneLocation (route, zoneId) {
  return { path: '/zone/' + zoneId, query: { ...zoneListQuery(route.query), tab: 'physical.network' } }
}

export function zoneFeatureState (value) {
  if (value === true || value === 'true') return 'Enabled'
  if (value === false || value === 'false') return 'Disabled'
  return ''
}

export function zoneMetricText (value) {
  if (value == null || value === '' || !Number.isFinite(Number.parseFloat(value))) return ''
  return String(value).replace(/\bghz\b/i, 'GHz').replace(/\bmhz\b/i, 'MHz')
}

export function zoneFeatureLabel (value) {
  return { Enabled: 'label.enabled', Disabled: 'label.disabled' }[zoneFeatureState(value)] || 'label.unknown'
}
