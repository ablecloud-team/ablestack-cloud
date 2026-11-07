// Licensed to the Apache Software Foundation (ASF) under one or more
// contributor license agreements. See the NOTICE file for ownership information.
// Licensed under the Apache License, Version 2.0; http://www.apache.org/licenses/LICENSE-2.0
// Distributed on an AS IS BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND.

import { hasVpc } from './networkTabs'

export function canReadNetworkPermissions (network, getters, user = getters.userInfo || {}) {
  if (!network?.id || network.acltype !== 'Account' || network.projectid || hasVpc(network) ||
    !['Isolated', 'L2', 'Shared'].includes(network.type) || !('listNetworkPermissions' in (getters.apis || {}))) return false
  if (user.roletype === 'Admin') return true
  if (network.type === 'Shared' && network.specifyvlan) return false
  if (!network.domainid || !user.domainid) return false
  if (network.domainid === user.domainid) {
    return user.roletype === 'DomainAdmin' || network.account === user.account
  }
  if (user.roletype !== 'DomainAdmin') return false
  const normalize = path => String(path || '').replace(/^\/+|\/+$/g, '')
  const parent = normalize(user.domainpath)
  const child = normalize(network.domainpath)
  // listNetworks already limits Account networks to the caller's domain tree.
  // The permission read still checks OperateEntry when listUsers omits the path.
  return !parent || child.startsWith(parent + '/')
}

export function permissionRows (json, network) {
  const body = json?.listnetworkpermissionsresponse
  const rows = body?.networkpermission ?? []
  if (!body || body.errorcode || !Array.isArray(rows) ||
    Number(body.count ?? 0) !== rows.length) throw new Error('message.detail.tab.incomplete')
  const keys = new Set()
  for (const row of rows) {
    const key = row.projectid || row.accountid
    if (!key || !row.accountid || row.networkid !== network.id ||
      row.domainid !== network.domainid || keys.has(key)) throw new Error('message.detail.tab.incomplete')
    keys.add(key)
  }
  return rows
}

export function permissionGrant (draft, network, rows) {
  const split = value => [...new Set(String(value || '').split(',').map(s => s.trim()).filter(Boolean))]
  const names = split(draft.accounts).filter(name => name !== network.account &&
    !rows.some(row => !row.projectid && row.account === name))
  const accounts = [...new Set(draft.accountids || [])].filter(id =>
    !rows.some(row => row.accountid === id))
  const projects = [...new Set(draft.projectids || [])].filter(id =>
    !rows.some(row => row.projectid === id))
  return {
    ...(names.length ? { accounts: names.join(',') } : {}),
    ...(accounts.length ? { accountids: accounts.join(',') } : {}),
    ...(projects.length ? { projectids: projects.join(',') } : {})
  }
}
