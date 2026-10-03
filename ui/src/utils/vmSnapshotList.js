// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements. See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership. The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License. You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied. See the License for the
// specific language governing permissions and limitations
// under the License.
export async function loadVmSnapshotRelations (get, vmId, limit = 2000) {
  const rows = new Map()
  let total = 0
  let partial = false
  for (let page = 1; rows.size < limit; page++) {
    const result = (await get('listVMSnapshot', { virtualmachineid: vmId, includehidden: true, listall: true, page, pagesize: 100, sortkey: 'created', sortorder: 'asc' })).listvmsnapshotresponse
    if (page > 1 && total !== (result.count || 0)) partial = true
    total = result.count || 0
    const batch = result.vmSnapshot || []
    const previous = rows.size
    batch.filter(row => row.virtualmachineid === vmId && !rows.has(row.id)).slice(0, limit - rows.size).forEach(row => rows.set(row.id, row))
    if (rows.size >= total) break
    if (!batch.length || rows.size === previous) { partial = true; break }
  }
  return { rows: [...rows.values()], total, partial: partial || rows.size < total }
}

export function snapshotRelationTree (rows) {
  const ids = new Map(rows.map(row => [row.id, row]))
  const nodes = new Map(rows.map(row => [row.id, { key: row.id, row, title: row.displayname || row.name, children: [] }]))
  const roots = []
  const warnings = []
  for (const row of rows) {
    const node = nodes.get(row.id)
    if (row.parentmissing) { warnings.push({ id: row.id, reason: 'missing' }); roots.push(node); continue }
    if (!row.parent) { roots.push(node); continue }
    if (!ids.has(row.parent)) { warnings.push({ id: row.id, reason: 'missing' }); roots.push(node); continue }
    const seen = new Set([row.id])
    let parent = row.parent
    let cycle = false
    while (parent && ids.has(parent)) {
      if (seen.has(parent)) { cycle = true; break }
      seen.add(parent); parent = ids.get(parent).parent
    }
    if (cycle) { warnings.push({ id: row.id, reason: 'cycle' }); roots.push(node) } else nodes.get(row.parent).children.push(node)
  }
  return { roots, warnings }
}

export async function freshSnapshotContext (get, targets, checkBackups = false) {
  const ids = targets.filter(row => row.id).map(row => row.id)
  const snapshots = ids.length ? (await get('listVMSnapshot', { vmsnapshotids: ids.join(','), listall: true, page: 1, pagesize: ids.length })).listvmsnapshotresponse.vmSnapshot || [] : []
  const vmIds = [...new Set(targets.map(row => row.virtualmachineid))]
  const vms = (await get('listVirtualMachines', { ids: vmIds.join(','), listall: true, page: 1, pagesize: vmIds.length }, { preserveOnFailure: true })).listvirtualmachinesresponse.virtualmachine || []
  // Fetch VM-wide transitional snapshots as well, independently of list filters.
  const transitions = await Promise.all(['Allocated', 'Creating', 'Reverting', 'Expunging'].map(state => get('listVMSnapshot', { virtualmachineids: vmIds.join(','), listall: true, page: 1, pagesize: 100, state })))
  const incomplete = transitions.some(result => (result.listvmsnapshotresponse.count || 0) > (result.listvmsnapshotresponse.vmSnapshot || []).length)
  const busyRows = transitions.flatMap(result => result.listvmsnapshotresponse.vmSnapshot || [])
  const backupResponses = checkBackups ? await Promise.all(['BackingUp', 'Restoring'].map(status => get('listBackups', { listall: true, status, page: 1, pagesize: 100 }, { preserveOnFailure: true }))) : []
  const backups = backupResponses.flatMap(result => result.listbackupsresponse.backup || [])
  const incompleteBackups = backupResponses.some(result => (result.listbackupsresponse.count || 0) > (result.listbackupsresponse.backup || []).length)
  return targets.map(target => ({ target, snapshot: snapshots.find(row => row.id === target.id && row.virtualmachineid === target.virtualmachineid), vm: vms.find(vm => vm.id === target.virtualmachineid), busy: incomplete || incompleteBackups || busyRows.some(row => row.virtualmachineid === target.virtualmachineid && row.id !== target.id) || backups.some(backup => backup.virtualmachineid === target.virtualmachineid) }))
}

export async function runSnapshotDeleteBatch (targets, execute, concurrency = 2) {
  const groups = [...new Set(targets.map(row => row.virtualmachineid))].map(id => targets.filter(row => row.virtualmachineid === id))
  const results = targets.map(row => ({ ...row, outcome: 'notrun' }))
  let cursor = 0
  await Promise.all(Array.from({ length: Math.min(concurrency, groups.length) }, async () => {
    while (cursor < groups.length) {
      const group = groups[cursor++]
      for (const row of group) {
        const result = results.find(item => item.id === row.id)
        try {
          const job = await execute(row)
          result.jobid = job.jobid
          result.error = job.jobstatus === 2 ? (job.jobresult?.errortext || job.error || '') : (job.error || '')
          result.outcome = job.jobstatus === 1 ? 'success' : job.jobstatus === 2 ? 'failed' : 'unknown'
        } catch (error) { result.outcome = 'failed'; result.error = error.message }
        if (result.outcome !== 'success') break
      }
    }
  }))
  return results
}
