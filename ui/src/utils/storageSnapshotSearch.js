// Licensed to the Apache Software Foundation (ASF) under one or more
// contributor license agreements. See the NOTICE file for copyright ownership.
// Licensed under the Apache License, Version 2.0 (the "License"); you may obtain
// a copy at http://www.apache.org/licenses/LICENSE-2.0 . Distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND.
export const snapshotSearchKeys = ['keyword', 'q', 'name', 'volumeid', 'snapshottype', 'intervaltype', 'showunique', 'locationtype', 'zoneid', 'domainid', 'account', 'isrecursive', 'storageid', 'imagestoreid']
export const snapshotIntervals = ['HOURLY', 'DAILY', 'WEEKLY', 'MONTHLY']
const tagKey = /^tags\[(\d+)\]\.(key|value)$/
export const snapshotListRoute = route => route?.name === 'snapshot' && !route.params?.id
export const snapshotCopyList = route => snapshotListRoute(route) && String(route.query?.showunique).toLowerCase() === 'false'

export function snapshotSearchPermissionError (query, getters) {
  for (const [key, api] of [['storageid', 'listStoragePools'], ['imagestoreid', 'listImageStores']]) {
    if (query[key] && (!['Admin', 'DomainAdmin'].includes(getters.userInfo?.roletype) || !(api in (getters.apis || {})))) return 'storage.permission'
  }
  return ''
}

export function normalizeSnapshotSearch (query) {
  const clean = { ...query }
  for (const key of ['snapshottype', 'intervaltype', 'locationtype', 'showunique', 'isrecursive']) {
    if (typeof clean[key] === 'string') clean[key] = ['snapshottype', 'intervaltype'].includes(key) ? clean[key].toUpperCase() : clean[key].toLowerCase()
  }
  const keyword = clean.keyword ?? clean.q
  if (typeof keyword === 'string' && keyword !== '') { clean.keyword = keyword; clean.q = keyword } else { delete clean.q; if (keyword === '') delete clean.keyword }
  return clean
}

export function snapshotSearchError (query) {
  for (const key of [...snapshotSearchKeys, 'projectid']) if (query[key] !== undefined && (typeof query[key] !== 'string' || query[key] === '')) return 'invalid'
  if (query.state !== undefined) return 'unsupported.state'
  if (query.snapshottype && !['MANUAL', 'RECURRING'].includes(query.snapshottype)) return 'invalid'
  if (query.intervaltype && !snapshotIntervals.includes(query.intervaltype)) return 'invalid'
  if (query.intervaltype && query.snapshottype === 'MANUAL') return 'manual.interval'
  if (query.showunique && !['true', 'false'].includes(query.showunique)) return 'invalid'
  if (query.isrecursive && !['true', 'false'].includes(query.isrecursive)) return 'invalid'
  if (query.locationtype && !['primary', 'secondary'].includes(query.locationtype)) return 'invalid'
  if (query.locationtype && query.showunique !== 'false') return 'location.unit'
  if (query.storageid && query.imagestoreid) return 'stores'
  if ((query.locationtype === 'primary' && query.imagestoreid) || (query.locationtype === 'secondary' && query.storageid)) return 'location.store'
  if (query.account && query.projectid && query.projectid !== '0') return 'owner.project'
  if (Object.keys(query).some(key => key.startsWith('tags[') && !tagKey.test(key))) return 'tags'
  const tags = snapshotSearchTags(query)
  if (tags.some(tag => !tag.key || !tag.value) || new Set(tags.map(tag => tag.key)).size !== tags.length) return 'tags'
  return ''
}

export function snapshotSearchTags (query) {
  const tags = new Map()
  for (const [key, value] of Object.entries(query)) {
    const match = key.match(tagKey)
    if (!match) continue
    const index = Number(match[1]); const tag = tags.get(index) || {}
    tag[match[2]] = typeof value === 'string' ? value : ''; tags.set(index, tag)
  }
  return [...tags.entries()].sort((a, b) => a[0] - b[0]).map(x => x[1])
}

export function snapshotSearchForm (query) {
  const clean = normalizeSnapshotSearch(query)
  const form = Object.fromEntries(snapshotSearchKeys.filter(key => typeof clean[key] === 'string').map(key => [key, clean[key]]))
  form.showunique = clean.showunique || 'true'; form.tags = snapshotSearchTags(clean)
  return form
}

export function snapshotSearchQuery (current, form, pageSize) {
  const query = { ...current }
  for (const key of Object.keys(query)) if (snapshotSearchKeys.includes(key) || key.startsWith('tags[') || key === 'state') delete query[key]
  for (const key of snapshotSearchKeys) if (key !== 'q' && typeof form[key] === 'string' && form[key] !== '') query[key] = form[key]
  for (const [index, tag] of (form.tags || []).entries()) if (tag.key || tag.value) { query[`tags[${index}].key`] = tag.key || ''; query[`tags[${index}].value`] = tag.value || '' }
  query.page = '1'; query.pagesize = String(pageSize)
  return normalizeSnapshotSearch(query)
}

export function snapshotPickerScope (form, projectId) {
  if (projectId && projectId !== '0') return { projectid: projectId }
  return { ...Object.fromEntries(['account', 'domainid', 'isrecursive'].filter(key => form[key]).map(key => [key, form[key]])), ...(projectId === '0' ? { ignoreproject: true } : {}) }
}

export function readSnapshotCandidatePage (response, command, key, params) {
  const envelope = response?.[command.toLowerCase() + 'response']
  if (!envelope || typeof envelope !== 'object') throw new Error('contract')
  const rows = envelope[key] || []; const count = envelope.count ?? 0
  if (!Array.isArray(rows) || !Number.isSafeInteger(count) || count < 0 || rows.length > 20 || rows.length > count || (params.page === 1 && count > 0 && !rows.length)) throw new Error('contract')
  const ids = new Set()
  for (const row of rows) {
    if (!row || typeof row.id !== 'string' || !row.id || typeof row.name !== 'string' || ids.has(row.id)) throw new Error('contract')
    ids.add(row.id)
    if (command === 'listVolumes') {
      if (params.projectid && params.projectid !== '-1' && row.projectid !== params.projectid) throw new Error('scope')
      if (params.account && row.account !== params.account) throw new Error('scope')
      if (params.domainid && params.isrecursive !== 'true' && row.domainid !== params.domainid) throw new Error('scope')
      if (params.zoneid && row.zoneid !== params.zoneid) throw new Error('scope')
    }
    if (command === 'listAccounts' && params.domainid && row.domainid !== params.domainid) throw new Error('scope')
  }
  return { rows, count }
}

export function snapshotSearchLabel (key) {
  return { volumeid: 'label.volume', snapshottype: 'label.snapshot.search.type', intervaltype: 'label.intervaltype', showunique: 'label.snapshot.search.unit', locationtype: 'label.snapshot.search.location', storageid: 'label.primary.storage', imagestoreid: 'label.secondary.storage' }[key] || 'label.' + key
}

export function snapshotSearchValue (key, value) {
  if (key === 'showunique') return value === 'false' ? 'label.snapshot.search.copies' : 'label.snapshot.search.logical'
  if (key === 'snapshottype') return { MANUAL: 'label.snapshot.search.manual', RECURRING: 'label.snapshot.search.recurring' }[value]
  if (key === 'intervaltype') return 'label.snapshot.search.' + String(value).toLowerCase()
  if (key === 'locationtype') return value === 'primary' ? 'label.primary.storage' : 'label.secondary.storage'
  return null
}
