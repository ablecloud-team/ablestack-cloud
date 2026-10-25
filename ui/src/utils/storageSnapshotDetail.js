// Licensed to the Apache Software Foundation (ASF) under one or more
// contributor license agreements. See the NOTICE file for copyright ownership.
// Licensed under the Apache License, Version 2.0 (the "License"); you may obtain
// a copy at http://www.apache.org/licenses/LICENSE-2.0 . Distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND.
import { readSnapshotCopyPage } from '@/utils/snapshotCopies'
export const snapshotDetailRoute = route => (route?.meta?.name || route?.name) === 'snapshot' && !!route.params?.id
const uuid = value => typeof value === 'string' && /^[a-f0-9]{8}-(?:[a-f0-9]{4}-){3}[a-f0-9]{12}$/i.test(value)
const fail = (key = 'message.snapshot.detail.incomplete', status) => { const error = new Error(key); if (status) error.response = { status }; throw error }
export const snapshotDetailPermissions = getters => ['listSnapshots', 'listVolumes', 'listVirtualMachines', 'listAccounts', 'listDomains', 'listProjects', 'listZones', 'listStoragePools', 'listImageStores', 'createTags', 'deleteTags'].map(api => api in (getters.apis || {})).concat(getters.features?.snapshotshowchainsize === true)
export function snapshotDetailParams (route, project) {
  const id = route.params.id
  if (!uuid(id)) fail('message.snapshot.detail.missing', 404)
  const projectId = route.query?.projectid || project?.id
  if (projectId && !['0', '-1'].includes(projectId) && !uuid(projectId)) fail('message.snapshot.detail.incomplete', 400)
  return { id, listall: true, page: 1, pagesize: 100, showunique: true, includesnapshotcontext: true, ...(projectId && projectId !== '0' ? { projectid: projectId } : {}) }
}
function records (json, command, key) {
  const data = json?.[command.toLowerCase() + 'response']; const rows = data?.[key] || []
  if (!data || !Array.isArray(rows) || Number(data.count ?? rows.length) !== rows.length) fail()
  return rows
}
async function related (request, command, key, id, scope) {
  try {
    const rows = records(await request(command, { id, listall: true, page: 1, pagesize: 100, ...scope }), command, key)
    if (rows.length > 1 || rows.some(row => row.id !== id)) fail()
    return rows[0] || null
  } catch (error) {
    if ([403, 404, 431, 432].includes(Number(error?.response?.data?.errorresponse?.errorcode || error?.response?.status))) return null
    throw error
  }
}
// Read one logical identity and all its references atomically. No action eligibility is inferred from a copy alone.
export async function readSnapshotDetail (getAPI, params, getters, current = () => true) {
  const request = (command, args) => { if (!current()) fail('message.snapshot.action.context.changed'); return getAPI(command, args, { preserveOnFailure: true, timeout: 20000 }) }
  const rows = records(await request('listSnapshots', { ...params, listall: true, showunique: true, includesnapshotcontext: true, page: 1, pagesize: 100 }), 'listSnapshots', 'snapshot')
  if (!rows.length) fail('message.snapshot.detail.missing', 404)
  if (rows.length !== 1 || typeof rows[0].id !== 'string' || rows[0].id.toLowerCase() !== params.id.toLowerCase()) fail()
  const record = rows[0]; const context = record.snapshotcontext
  if (params.projectid && params.projectid !== '-1' && record.projectid !== params.projectid) fail()
  if (context?.version !== 1 || typeof context.sourceavailable !== 'boolean' || typeof context.revertreason !== 'string' || !uuid(record.volumeid) || (context.sourceavailable && context.volumeid !== record.volumeid)) fail()
  const scope = record.projectid ? { projectid: record.projectid } : {}
  const copies = []; let total = null
  for (let page = 1; page <= 100; page++) {
    const data = readSnapshotCopyPage(await request('listSnapshots', { id: record.id, listall: true, showunique: false, page, pagesize: 100, ...scope }), record.id, 100, page)
    if (total !== null && data.count !== total) fail()
    total = data.count
    if (data.rows.length !== Math.min(100, Math.max(0, total - (page - 1) * 100))) fail()
    copies.push(...data.rows)
    if (copies.length === total) break
    if (page === 100) fail()
  }
  if (copies.some(row => (row.zoneid && !uuid(row.zoneid)) || (row.datastoreid && !uuid(row.datastoreid))) || new Set(copies.map(row => row.copy.id)).size !== copies.length) fail()
  let volume = null; let vm = null; let parent = null
  if (context.sourceavailable && 'listVolumes' in getters.apis) volume = await related(request, 'listVolumes', 'volume', record.volumeid, scope)
  if (volume?.virtualmachineid && 'listVirtualMachines' in getters.apis) vm = await related(request, 'listVirtualMachines', 'virtualmachine', volume.virtualmachineid, scope)
  if (getters.features?.snapshotshowchainsize === true && record.parent) {
    if (!uuid(record.parent) || record.parent === record.id) fail()
    parent = await related(request, 'listSnapshots', 'snapshot', record.parent, { ...scope, showunique: true })
    if (parent && (parent.volumeid !== record.volumeid || (parent.projectid || '') !== (record.projectid || '') || parent.account !== record.account || parent.domainid !== record.domainid)) fail()
  }
  if (!current()) fail('message.snapshot.action.context.changed')
  return { listsnapshotsresponse: { count: 1, snapshot: [{ ...record, snapshotdetail: { copies, volume, vm, parent } }] } }
}
export function snapshotDetailType (record) {
  if (record.snapshottype === 'MANUAL' || record.intervaltype === 'MANUAL') return 'label.snapshot.detail.manual'
  if (record.snapshottype === 'RECURRING' || ['HOURLY', 'DAILY', 'WEEKLY', 'MONTHLY'].includes(record.intervaltype)) return 'label.snapshot.detail.recurring'
  return 'label.snapshot.list.unknown'
}
export function snapshotDetailRevertReason (record) {
  if (record.state !== 'BackedUp' || record.revertable !== true) return 'SNAPSHOT_NOT_REVERTABLE'
  const context = record.snapshotcontext
  if (!context?.sourceavailable) return 'SOURCE_VOLUME_UNAVAILABLE'
  const volume = record.snapshotdetail?.volume; const vm = record.snapshotdetail?.vm
  if (!volume) return 'SOURCE_VOLUME_UNAVAILABLE'
  if (volume.virtualmachineid && !vm) return 'SOURCE_VM_UNAVAILABLE'
  if (volume && volume.state !== 'Ready') return 'VOLUME_NOT_READY'
  if (vm && !['Stopped', 'Shutdown'].includes(vm.state)) return 'VM_MUST_BE_STOPPED'
  return context.revertreason
}
export function snapshotDetailStorePath (row, apis) {
  if (!uuid(row.datastoreid)) return null
  if (row.copy.role === 'Primary' && 'listStoragePools' in apis) return '/storagepool/' + row.datastoreid
  if (row.copy.role === 'Image' && 'listImageStores' in apis) return '/imagestore/' + row.datastoreid
  return null
}

export function snapshotDetailRouteAllowed (router, path) {
  const matched = router?.resolve?.(path)?.matched || []
  return matched.length > 0 && !matched.some(route => route.redirect === '/exception/404')
}
