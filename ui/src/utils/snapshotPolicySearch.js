// Licensed to the Apache Software Foundation (ASF) under one or more
// contributor license agreements. See NOTICE for copyright ownership.
// Licensed under the Apache License, Version 2.0 (the "License");
// http://www.apache.org/licenses/LICENSE-2.0 . Distributed AS IS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND.
export const policySearchKeys = ['keyword', 'q', 'id', 'volumeid', 'domainid', 'account', 'isrecursive', 'fordisplay']
const routeKeys = [...policySearchKeys, 'projectid', 'page', 'pagesize', 'sortkey', 'sortorder']
const uuid = value => typeof value === 'string' && /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(value)
export const policyProjectScope = (route, getters) => route.query?.projectid || getters.project?.id || (getters.listAllProjects ? '-1' : '0')
export function normalizePolicySearch (query) {
  const clean = { ...query }
  for (const key of ['fordisplay', 'isrecursive']) if (typeof clean[key] === 'string') clean[key] = clean[key].toLowerCase()
  const keyword = clean.keyword ?? clean.q
  if (typeof keyword === 'string' && keyword) { clean.keyword = keyword; clean.q = keyword } else if (keyword === undefined || keyword === '') { delete clean.keyword; delete clean.q } else { clean.keyword = keyword; delete clean.q }
  return clean
}
export function policySearchError (query, getters = {}) {
  if (Object.keys(query).some(key => !routeKeys.includes(key))) return 'unsupported'
  if (Object.entries(query).some(([key, value]) => !['page', 'pagesize'].includes(key) && (typeof value !== 'string' || !value))) return 'invalid'
  if (['id', 'volumeid', 'domainid'].some(key => query[key] && !uuid(query[key]))) return 'invalid'
  if (query.projectid && !['0', '-1'].includes(query.projectid) && !uuid(query.projectid)) return 'invalid'
  if (['fordisplay', 'isrecursive'].some(key => query[key] && !['true', 'false'].includes(query[key]))) return 'invalid'
  if (query.account && !query.domainid) return 'account.domain'
  if (query.projectid && query.projectid !== '0' && ['account', 'domainid', 'isrecursive'].some(key => query[key])) return 'project.owner'
  if (['account', 'domainid', 'isrecursive'].some(key => query[key]) && !['Admin', 'DomainAdmin'].includes(getters.userInfo?.roletype)) return 'permission'
  if (query.fordisplay && getters.userInfo?.roletype !== 'Admin') return 'permission'
  return ''
}
export function policySearchForm (query) {
  const clean = normalizePolicySearch(query)
  return Object.fromEntries(policySearchKeys.filter(key => key !== 'q' && typeof clean[key] === 'string').map(key => [key, clean[key]]))
}
export function policySearchQuery (current, form, pageSize) {
  const query = Object.fromEntries(Object.entries(current).filter(([key]) => ['projectid', 'sortkey', 'sortorder'].includes(key)))
  for (const key of policySearchKeys) if (key !== 'q' && typeof form[key] === 'string' && form[key] !== '') query[key] = form[key]
  return normalizePolicySearch({ ...query, page: '1', pagesize: String(pageSize) })
}
export function policySearchParams (params) {
  const result = { ...params }
  delete result.q
  if (result.projectid === '0') { delete result.projectid; result.ignoreproject = true }
  return result
}
export function policyListPage (response, params) {
  const body = response?.listsnapshotpoliciesresponse
  const rows = body?.snapshotpolicy || []; const count = body?.count ?? 0
  if (!body || !Array.isArray(rows) || !Number.isSafeInteger(count) || count < 0 || rows.length > Number(params.pagesize) || rows.length > count || (Number(params.page) <= Math.ceil(count / Number(params.pagesize)) && count > 0 && !rows.length)) throw new Error('Incomplete snapshot policy response')
  const ids = new Set()
  for (const row of rows) {
    if (!uuid(row.id) || !uuid(row.volumeid) || ids.has(row.id)) throw new Error('Invalid snapshot policy response')
    ids.add(row.id)
    if ((params.id && row.id !== params.id) || (params.volumeid && row.volumeid !== params.volumeid) || (params.account && row.account !== params.account) || (params.domainid && String(params.isrecursive) !== 'true' && row.domainid !== params.domainid) || (params.ignoreproject && row.projectid) || (params.projectid && params.projectid !== '-1' && row.projectid !== params.projectid) || (params.fordisplay !== undefined && row.schedulingenabled !== (String(params.fordisplay) === 'true'))) throw new Error('Snapshot policy scope mismatch')
  }
  return { rows, count }
}
