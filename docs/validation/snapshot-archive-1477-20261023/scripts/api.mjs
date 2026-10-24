// Licensed to the Apache Software Foundation (ASF) under one or more
// contributor license agreements. See the NOTICE file distributed with this
// work for additional information regarding copyright ownership. The ASF
// licenses this file to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance with the License.
// You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0 .
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
// WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
// License for the specific language governing permissions and limitations.
import fs from 'node:fs'
const cluster = process.argv[2]
const phase = process.argv[3]
const root = process.env.EVIDENCE_ROOT || '/home/ablecloud/work/snapshot-archive-1477-20261023'
const registryFile = `${root}/${cluster}-fixtures.json`
const prefix = `SNAP1477-${cluster}`
let cookies = '', session = '', deletionContractChecked = false, archiveContractChecked = false, forceContractChecked = false
const clean = x => JSON.parse(JSON.stringify(x, (k, v) => /password|passphrase|session|secret|api.?key|cookie|token/i.test(k) ? '[REDACTED]' : v))
async function api (command, args = {}) {
  if (command === 'deleteSnapshot' && args.force && !forceContractChecked) {
    const contract = await api('listApis', { name: 'deleteSnapshot' })
    if (!JSON.stringify(contract).includes('"name":"force"')) throw new Error('Refusing forced test: deployed API has no force contract')
    forceContractChecked = true
  }
  if (command === 'archiveSnapshot' && !archiveContractChecked) {
    const contract = await api('listApis', { name: 'archiveSnapshot' })
    if (!JSON.stringify(contract).includes('"name":"dryrun"')) throw new Error('Refusing archive test: deployed API has no dryrun contract')
    archiveContractChecked = true
  }
  if (command === 'deleteSnapshot' && !deletionContractChecked) {
    const contract = await api('listApis', { name: 'deleteSnapshot' })
    if (!JSON.stringify(contract).includes('"name":"dryrun"')) throw new Error('Refusing deletion test: deployed API has no dryrun contract')
    deletionContractChecked = true
  }
  const body = new URLSearchParams({ command, response: 'json', ...args, ...(session ? { sessionkey: session } : {}) })
  const response = await fetch(`http://10.10.${cluster}.10:8080/client/api`, { method: 'POST', headers: { 'Content-Type': 'application/x-www-form-urlencoded', ...(cookies ? { Cookie: cookies } : {}) }, body, signal: AbortSignal.timeout(60000) })
  const set = response.headers.getSetCookie()
  if (set.length) cookies = set.map(v => v.split(';')[0]).join('; ')
  const json = await response.json(), data = Object.values(json)[0]
  fs.appendFileSync(`${root}/${cluster}-api.jsonl`, JSON.stringify(clean({ at: new Date().toISOString(), command, args, status: response.status, data })) + '\n')
  if (!response.ok || data.errorcode) throw new Error(JSON.stringify(clean(data)))
  return data
}
async function job (command, args) {
  const response = await api(command, args)
  if (!response.jobid) return response
  console.log(cluster, command, response.jobid)
  for (let i = 0; i < 300; i++) {
    const result = await api('queryAsyncJobResult', { jobid: response.jobid })
    if (result.jobstatus === 1) return result.jobresult?.archivesnapshotresponse || result.jobresult?.deletesnapshotresponse || result.jobresult
    if (result.jobstatus === 2) throw new Error(JSON.stringify(result))
    await new Promise(resolve => setTimeout(resolve, 1000))
  }
  throw new Error(`Job result unknown: ${response.jobid}`)
}
const login = await api('login', { username: process.env.MOLD_TEST_USER || 'admin', password: process.env.MOLD_TEST_PASSWORD, domain: '/' })
session = login.sessionkey
try {
 const reg = fs.existsSync(registryFile) ? JSON.parse(fs.readFileSync(registryFile)) : { cluster, prefix, volumes: [], snapshots: [] }
 const save = () => fs.writeFileSync(registryFile, JSON.stringify(reg, null, 2))
 if (phase === 'create') {
  if (reg.volumes.length) throw new Error('Fixture already exists')
  const inventory = {}
  for (const command of ['listVolumes', 'listSnapshots', 'listStoragePools', 'listDiskOfferings']) inventory[command] = await api(command, { listall: true, page: 1, pagesize: 500, ...(command === 'listSnapshots' ? { showunique: false } : {}) })
  fs.writeFileSync(`${root}/${cluster}-existing-before.json`, JSON.stringify(inventory, null, 2))
  const pool = inventory.listStoragePools.storagepool[0]
  const disk = inventory.listDiskOfferings.diskoffering.find(x => x.name === (cluster === '31' ? 'Custom-GFS-Sparse' : 'Custom'))
  const result = await job('createVolume', { name: `${prefix}-source`, zoneid: pool.zoneid, storageid: pool.id, diskofferingid: disk.id, size: 1 })
  reg.volumes.push(result.volume); save()
  for (const label of ['normal', 'residue', 'partial', 'control']) {
    const r = await job('createSnapshot', { volumeid: reg.volumes[0].id, name: `${prefix}-${label}`, asyncbackup: false, usestoragereplication: false })
    reg.snapshots.push({ ...r.snapshot, scenario: label }); save()
  }
 } else if (phase === 'quota-create') {
  if (reg.snapshots.some(x => x.scenario === 'quota-final')) throw new Error('Quota fixture already exists')
  const baseline = {}
  for (const resourcetype of [3, 11]) baseline[resourcetype] = await api('updateResourceCount', { account: reg.volumes[0].account, domainid: reg.volumes[0].domainid, resourcetype })
  fs.writeFileSync(`${root}/${cluster}-quota-baseline.json`, JSON.stringify(baseline, null, 2))
  const snapshot = (await job('createSnapshot', { volumeid: reg.volumes[0].id, name: `${prefix}-quota-final`, asyncbackup: false, usestoragereplication: false })).snapshot
  reg.snapshots.push({ ...snapshot, scenario: 'quota-final' }); save()
 } else if (phase === 'quota-concurrent') {
  const snapshot = reg.snapshots.find(x => x.scenario === 'quota-final')
  const results = await Promise.allSettled([job('deleteSnapshot', { id: snapshot.id }), job('deleteSnapshot', { id: snapshot.id })])
  if (results.some(x => x.status !== 'fulfilled')) throw new Error(JSON.stringify(results))
  const after = await job('deleteSnapshot', { id: snapshot.id, dryrun: true })
  if (after.state !== 'Destroyed' || (after.reference || []).length) throw new Error(JSON.stringify(after))
  fs.writeFileSync(`${root}/${cluster}-quota-concurrent.json`, JSON.stringify({ results, after }, null, 2))
  console.log(cluster, 'concurrent final-backend deletion PASS; compare raw DB quota next')
 } else if (phase === 'create-missing') {
  if (reg.snapshots.some(x => x.scenario === 'missing')) throw new Error('Missing-file fixture already exists')
  const snapshot = (await job('createSnapshot', { volumeid: reg.volumes[0].id, name: `${prefix}-missing`, asyncbackup: false, usestoragereplication: false })).snapshot
  reg.snapshots.push({ ...snapshot, scenario: 'missing' }); save()
 } else if (phase === 'missing-concurrent') {
  const snapshot = reg.snapshots.find(x => x.scenario === 'missing')
  const results = await Promise.allSettled([job('deleteSnapshot', { id: snapshot.id }), job('deleteSnapshot', { id: snapshot.id })])
  if (results.some(x => x.status !== 'fulfilled')) throw new Error(JSON.stringify(results))
  const after = await job('deleteSnapshot', { id: snapshot.id, dryrun: true })
  if (after.state !== 'Destroyed' || (after.reference || []).length) throw new Error(JSON.stringify(after))
  fs.writeFileSync(`${root}/${cluster}-missing-concurrent.json`, JSON.stringify({ results, after }, null, 2))
  console.log(cluster, 'absent-file concurrent deletion PASS')
 } else if (phase === 'tenant-create') {
  if (reg.tenant) throw new Error('Tenant fixture already exists')
  const name = `${prefix.toLowerCase()}-tenant`
  const result = await api('createAccount', { username: name, account: name, password: process.env.MOLD_TEST_PASSWORD, firstname: 'Snapshot', lastname: '1477', email: 'snapshot1477@example.test', accounttype: 0, domainid: login.domainid })
  reg.tenant = { id: result.account.id, name: result.account.name, domainid: result.account.domainid }; save()
  const original = reg.volumes[0]
  const pools = await api('listStoragePools', { id: original.storageid || original.storage, listall: true })
  const disk = (await api('listDiskOfferings', { listall: true })).diskoffering.find(x => x.name === (cluster === '31' ? 'Custom-GFS-Sparse' : 'Custom'))
  const pool = pools.storagepool[0]
  const volume = (await job('createVolume', { name: `${prefix}-tenant-source`, zoneid: pool.zoneid, storageid: pool.id, diskofferingid: disk.id, size: 1, account: name, domainid: reg.tenant.domainid })).volume
  reg.volumes.push(volume); save()
  const snapshot = (await job('createSnapshot', { volumeid: volume.id, name: `${prefix}-tenant`, asyncbackup: false, usestoragereplication: false })).snapshot
  reg.snapshots.push({ ...snapshot, scenario: 'tenant' }); save()
 } else if (phase === 'tenant-verify') {
  if (login.account !== reg.tenant.name || Number(login.type) !== 0) throw new Error('Owner test did not log in as the normal tenant')
  const own = reg.snapshots.find(x => x.scenario === 'tenant'), foreign = reg.snapshots.find(x => x.scenario === 'control')
  const results = []
  const visible = await api('listSnapshots', { id: foreign.id, listall: true })
  if (visible.snapshot?.length) throw new Error('Another owner snapshot is visible')
  for (const dryrun of [true, false]) {
    let rejected = false
    try { await job('deleteSnapshot', { id: foreign.id, ...(dryrun ? { dryrun: true } : {}) }) }
    catch (error) { if (/errorcode/.test(error.message) && /access|denied|permission|does not exist|unable to find|not found|not allowed|belong/i.test(error.message)) rejected = true; else throw error }
    if (!rejected) throw new Error('Foreign deletion or diagnosis unexpectedly accepted')
    results.push({ name: dryrun ? 'foreign diagnosis denied' : 'foreign deletion denied', status: 'PASS' })
  }
  const before = await job('deleteSnapshot', { id: own.id, dryrun: true })
  if (before.state !== 'Destroyed' || !(before.reference || []).length) throw new Error('Owner recovery fixture is not inconsistent')
  await job('deleteSnapshot', { id: own.id, expectedscopehash: before.scopehash })
  const after = await job('deleteSnapshot', { id: own.id, dryrun: true })
  if (after.state !== 'Destroyed' || (after.reference || []).length) throw new Error('Owner recovery did not settle references')
  await job('deleteSnapshot', { id: own.id })
  results.push({ name: 'normal owner repairs Destroyed residuals and repeats safely', status: 'PASS', before, after })
  fs.writeFileSync(`${root}/${cluster}-owner-validation.json`, JSON.stringify(results, null, 2)); console.log(cluster, 'normal-owner recovery and foreign-owner rejection PASS')
 } else if (phase === 'fault-failure') {
  const snapshot = reg.snapshots.find(x => x.scenario === 'partial')
  for (const resourcetype of [3, 11]) await api('updateResourceCount', { account: reg.volumes[0].account, domainid: reg.volumes[0].domainid, resourcetype })
  const before = await job('deleteSnapshot', { id: snapshot.id, dryrun: true })
  let failure
  try { await job('deleteSnapshot', { id: snapshot.id, expectedscopehash: before.scopehash }) }
  catch (error) { failure = error.message }
  if (!failure) throw new Error('Deletion unexpectedly succeeded under injected storage failure')
  const after = await job('deleteSnapshot', { id: snapshot.id, dryrun: true })
  if (!(after.reference || []).length) throw new Error('Failed deletion discarded all residual references')
  fs.writeFileSync(`${root}/${cluster}-fault-failure.json`, JSON.stringify({ before, failure, after }, null, 2))
  console.log(cluster, 'injected storage failure correctly reported')
 } else if (phase === 'fault-retry') {
  const snapshot = reg.snapshots.find(x => x.scenario === 'partial')
  const before = await job('deleteSnapshot', { id: snapshot.id, dryrun: true })
  await job('deleteSnapshot', { id: snapshot.id, expectedscopehash: before.scopehash })
  const after = await job('deleteSnapshot', { id: snapshot.id, dryrun: true })
  if (after.state !== 'Destroyed' || (after.reference || []).length) throw new Error(JSON.stringify(after))
  await job('deleteSnapshot', { id: snapshot.id })
  fs.writeFileSync(`${root}/${cluster}-fault-retry.json`, JSON.stringify({ before, after, repeated: true }, null, 2))
  console.log(cluster, 'storage failure recovery and repeated deletion PASS')
 } else if (phase === 'validate-api') {
  const results = []
  const check = async (name, run) => {
    try { const detail = await run(); results.push({ name, status: 'PASS', detail }); console.log(cluster, name, 'PASS') }
    catch (error) { results.push({ name, status: 'FAIL', error: error.message }); throw error }
    finally { fs.writeFileSync(`${root}/${cluster}-api-validation.json`, JSON.stringify(results, null, 2)) }
  }
  const snapshot = reg.snapshots.find(x => x.scenario === 'normal')
  let preview
  await check('diagnosis includes exact reference scope', async () => {
    preview = await job('deleteSnapshot', { id: snapshot.id, dryrun: true })
    if (preview.snapshotid !== snapshot.id || !preview.scopehash || !preview.reference?.length) throw new Error(JSON.stringify(preview))
    return preview
  })
  await check('stale diagnosis rejected without deletion', async () => {
    let rejected = false
    try { await job('deleteSnapshot', { id: snapshot.id, expectedscopehash: 'stale' }) } catch (error) { if (/scope changed/.test(error.message)) rejected = true; else throw error }
    if (!rejected) throw new Error('Stale revision was accepted')
    const after = await job('deleteSnapshot', { id: snapshot.id, dryrun: true })
    if (preview.scopehash !== after.scopehash) throw new Error('Rejected deletion changed its target')
    return { rejected: true, unchanged: true }
  })
  await check('global deletion settles all references', async () => {
    await job('deleteSnapshot', { id: snapshot.id, expectedscopehash: preview.scopehash })
    const after = await job('deleteSnapshot', { id: snapshot.id, dryrun: true })
    if (after.state !== 'Destroyed' || (after.reference || []).length) throw new Error(JSON.stringify(after))
    return after
  })
  await check('repeated deletion is an idempotent success', async () => {
    await job('deleteSnapshot', { id: snapshot.id })
    return await job('deleteSnapshot', { id: snapshot.id, dryrun: true })
  })
  await check('control snapshot remains available', async () => {
    const control = reg.snapshots.find(x => x.scenario === 'control')
    const response = await api('listSnapshots', { id: control.id, showunique: false, listall: true })
    if (!response.snapshot?.length || response.snapshot.some(x => x.state !== 'BackedUp')) throw new Error(JSON.stringify(response))
    return { id: control.id, copies: response.snapshot.length }
  })
 } else if (phase === 'create-remnants') {
  if (cluster !== '32') throw new Error('RBD remnants scenario only')
  for (const scenario of ['force-secondary-missing', 'force-primary-only']) {
    if (reg.snapshots.some(x => x.scenario === scenario)) throw new Error('Fixture already exists')
    const r = await job('createSnapshot', { volumeid: reg.volumes[0].id, name: `${prefix}-${scenario}`, asyncbackup: false, usestoragereplication: false })
    reg.snapshots.push({ ...r.snapshot, scenario }); save()
  }
 } else if (phase === 'create-extra') {
  for (const scenario of ['force-path', 'force-busy', 'force-no-native', 'force-no-mapping']) {
    if (reg.snapshots.some(x => x.scenario === scenario)) continue
    const snapshot = (await job('createSnapshot', { volumeid: reg.volumes[0].id, name: `${prefix}-${scenario}`, asyncbackup: false, usestoragereplication: false })).snapshot
    reg.snapshots.push({ ...snapshot, scenario }); save()
  }
 } else if (phase === 'normal-missing-metadata-reject') {
  const snapshot = reg.snapshots.find(x => x.scenario === 'force-path')
  const before = await job('deleteSnapshot', { id: snapshot.id, dryrun: true })
  let failure
  try { await job('deleteSnapshot', { id: snapshot.id, expectedscopehash: before.scopehash }) } catch (error) { failure = error.message }
  if (!failure?.includes('INCOMPLETE_STORE_REFERENCE')) throw new Error('Missing metadata was not safely rejected: ' + failure)
  const after = await job('deleteSnapshot', { id: snapshot.id, dryrun: true })
  if (before.scopehash !== after.scopehash) throw new Error('Rejected deletion changed target state')
  fs.writeFileSync(`${root}/${cluster}-normal-missing-metadata.json`, JSON.stringify({ before, failure, after }, null, 2))
  console.log(cluster, 'normal deletion rejects missing address without mutation PASS')
 } else if (phase === 'archive-ambiguous-reject') {
  const snapshot = reg.snapshots.find(x => x.scenario === 'residue')
  const before = await job('archiveSnapshot', { id: snapshot.id, dryrun: true })
  if (before.eligible || before.reason !== 'AMBIGUOUS_REFERENCES') throw new Error(JSON.stringify(before))
  let failure
  try { await job('archiveSnapshot', { id: snapshot.id }) } catch (error) { failure = error.message }
  if (!failure?.includes('AMBIGUOUS_REFERENCES')) throw new Error('Ambiguous archive was accepted')
  const after = await job('archiveSnapshot', { id: snapshot.id, dryrun: true })
  if (before.scopehash !== after.scopehash) throw new Error('Rejected archive changed target state')
  fs.writeFileSync(`${root}/${cluster}-archive-ambiguous.json`, JSON.stringify({ before, failure, after }, null, 2))
  console.log(cluster, 'duplicate archive references safely rejected PASS')
 } else if (phase === 'force' || phase === 'force-retry' || phase === 'force-repeat') {
  const scenario = process.argv[4], snapshot = reg.snapshots.find(x => x.scenario === scenario)
  const before = await job('deleteSnapshot', { id: snapshot.id, force: true, dryrun: true })
  if (!before.force || !before.scopehash) throw new Error(JSON.stringify(before))
  const result = await job('deleteSnapshot', { id: snapshot.id, force: true, expectedscopehash: before.scopehash })
  if (!result.force || result.dbcleanupcomplete !== true || result.dryrun !== false) throw new Error(JSON.stringify(result))
  if (phase === 'force') {
    const physical = ['force-no-native', 'force-secondary-missing', 'force-primary-only'].includes(scenario)
    if (result.physicalcleanupcomplete !== physical) throw new Error(JSON.stringify(result))
  }
  if (phase === 'force-retry' && (result.physicalcleanupcomplete !== true || result.pendingcleanup !== 0)) throw new Error(JSON.stringify(result))
  const after = await job('deleteSnapshot', { id: snapshot.id, force: true, dryrun: true })
  if (after.state !== 'Destroyed') throw new Error(JSON.stringify(after))
  fs.writeFileSync(`${root}/${cluster}-${scenario}-${phase}.json`, JSON.stringify({ before, result, after }, null, 2))
  console.log(cluster, scenario, phase, 'explicit cleanup result PASS', JSON.stringify({ physical: result.physicalcleanupcomplete, pending: result.pendingcleanup }))
 } else if (phase === 'archive-normal') {
  const snapshot = reg.snapshots.find(x => x.scenario === 'normal')
  const before = await job('archiveSnapshot', { id: snapshot.id, dryrun: true })
  const expected = cluster === '31' ? 'ALREADY_ARCHIVED' : 'REUSE_SECONDARY'
  if (!before.eligible || before.reason !== expected) throw new Error(JSON.stringify(before))
  let staleRejected = false
  try { await job('archiveSnapshot', { id: snapshot.id, expectedscopehash: 'stale' }) }
  catch (error) { if (/scope changed/.test(error.message)) staleRejected = true; else throw error }
  if (!staleRejected) throw new Error('Stale archive scope was accepted')
  await job('archiveSnapshot', { id: snapshot.id, expectedscopehash: before.scopehash })
  const results = await Promise.allSettled([job('archiveSnapshot', { id: snapshot.id }), job('archiveSnapshot', { id: snapshot.id })])
  if (results.some(x => x.status !== 'fulfilled')) throw new Error(JSON.stringify(results))
  const after = await job('archiveSnapshot', { id: snapshot.id, dryrun: true })
  if (!after.eligible || after.reason !== 'ALREADY_ARCHIVED' || after.primaryreferences !== 0 || after.secondaryreferences !== 1) throw new Error(JSON.stringify(after))
  fs.writeFileSync(`${root}/${cluster}-archive-normal.json`, JSON.stringify({ before, staleRejected, results, after }, null, 2))
  console.log(cluster, 'archive reuse/no-op, stale target rejection, repeat and concurrent PASS')
 } else if (phase === 'archive-verification-fail') {
  const snapshot = reg.snapshots.find(x => x.scenario === (cluster === '31' ? 'normal' : 'partial'))
  const before = await job('archiveSnapshot', { id: snapshot.id, dryrun: true })
  let failure
  try { await job('archiveSnapshot', { id: snapshot.id }) } catch (error) { failure = error.message }
  if (!failure || !/SECONDARY_VERIFICATION_FAILED/.test(failure)) throw new Error(failure || 'Missing backup unexpectedly accepted')
  const after = await job('archiveSnapshot', { id: snapshot.id, dryrun: true })
  if (after.scopehash !== before.scopehash) throw new Error('Failed verification changed references')
  fs.writeFileSync(`${root}/${cluster}-archive-verification-fail.json`, JSON.stringify({ before, failure, after }, null, 2))
  console.log(cluster, 'missing exact Secondary rejected; references unchanged PASS')
 } else if (phase === 'archive-failure') {
  const scenario = process.argv[4], snapshot = reg.snapshots.find(x => x.scenario === scenario)
  const baseline = {}
  for (const resourcetype of [3, 11]) baseline[resourcetype] = await api('updateResourceCount', { account: reg.volumes[0].account, domainid: reg.volumes[0].domainid, resourcetype })
  const before = await job('archiveSnapshot', { id: snapshot.id, dryrun: true })
  if (!before.eligible) throw new Error(JSON.stringify(before))
  let failure
  try { await job('archiveSnapshot', { id: snapshot.id }) } catch (error) { failure = error.message }
  const code = scenario === 'residue' ? 'ARCHIVE_BACKUP_FAILED' : 'ARCHIVE_PRIMARY_CLEANUP_FAILED'
  if (!failure || !failure.includes(code)) throw new Error(failure || 'Storage fault unexpectedly succeeded')
  const after = await job('archiveSnapshot', { id: snapshot.id, dryrun: true })
  const expected = scenario === 'residue' ? 'RETRY_BACKUP' : 'RETRY_PRIMARY_CLEANUP'
  if (!after.eligible || after.reason !== expected) throw new Error(JSON.stringify(after))
  fs.writeFileSync(`${root}/${cluster}-${scenario}-archive-failure.json`, JSON.stringify({ baseline, before, failure, after }, null, 2))
  console.log(cluster, scenario, 'archive partial failure and resumable plan PASS')
 } else if (phase === 'archive-retry') {
  const scenario = process.argv[4], snapshot = reg.snapshots.find(x => x.scenario === scenario)
  const before = await job('archiveSnapshot', { id: snapshot.id, dryrun: true })
  const results = await Promise.allSettled([job('archiveSnapshot', { id: snapshot.id }), job('archiveSnapshot', { id: snapshot.id })])
  if (results.some(x => x.status !== 'fulfilled')) throw new Error(JSON.stringify(results))
  const after = await job('archiveSnapshot', { id: snapshot.id, dryrun: true })
  if (!after.eligible || after.reason !== 'ALREADY_ARCHIVED' || after.primaryreferences !== 0 || after.secondaryreferences !== 1) throw new Error(JSON.stringify(after))
  fs.writeFileSync(`${root}/${cluster}-${scenario}-archive-retry.json`, JSON.stringify({ before, results, after }, null, 2))
  console.log(cluster, scenario, 'archive concurrent retry/no duplicate PASS')
 } else if (phase === 'tenant-role') {
  const role = (await api('listRoles')).role.find(x => x.name === 'User')
  if (!role || reg.tenant.role) throw new Error('Invalid role fixture')
  reg.tenant.role = (await api('createRole', { roleid: role.id, name: `${prefix}-archive-role`, ispublic: false, description: 'Disposable snapshot archive ACL qualification' })).role.id; save()
  await api('createRolePermission', { roleid: reg.tenant.role, rule: 'archiveSnapshot', permission: 'allow' })
  await api('updateAccount', { id: reg.tenant.id, roleid: reg.tenant.role })
 } else if (phase === 'tenant-force-denied') {
  if (login.account !== reg.tenant.name || Number(login.type) !== 0) throw new Error('Not normal fixture owner')
  const own = reg.snapshots.find(x => x.scenario === 'tenant')
  const before = await job('deleteSnapshot', { id: own.id, dryrun: true })
  forceContractChecked = true // Intentionally send forbidden parameter to server, bypass only helper contract discovery.
  const results = []
  for (const dryrun of [true, false]) {
    let failure
    try { await job('deleteSnapshot', { id: own.id, force: true, dryrun, expectedscopehash: before.scopehash }) } catch (error) { failure = error.message }
    if (!failure || !/access|denied|permission|authorized|admin|invalid|allowed/i.test(failure)) throw new Error('Non-admin force was not rejected: ' + failure)
    results.push({ dryrun, denied: true, failure })
  }
  const after = await job('deleteSnapshot', { id: own.id, dryrun: true })
  if (before.scopehash !== after.scopehash) throw new Error('Denied force changed owner snapshot')
  fs.writeFileSync(`${root}/${cluster}-tenant-force-denied.json`, JSON.stringify({ before, results, after }, null, 2))
  console.log(cluster, 'normal owner force diagnosis/execution denied without mutation PASS')
 } else if (phase === 'tenant-archive') {
  if (login.account !== reg.tenant.name || Number(login.type) !== 0) throw new Error('Not a normal owner')
  const own = reg.snapshots.find(x => x.scenario === 'tenant'), foreign = reg.snapshots.find(x => x.scenario === 'control')
  const results = []
  for (const dryrun of [true, false]) {
    let denied = false
    try { await job('archiveSnapshot', { id: foreign.id, dryrun }) } catch (error) { if (/access|denied|permission|does not exist|unable to find|not found|not allowed|belong/i.test(error.message)) denied = true; else throw error }
    if (!denied) throw new Error('Foreign archive/diagnosis allowed')
    results.push({ foreign: true, dryrun, denied: true })
  }
  const before = await job('archiveSnapshot', { id: own.id, dryrun: true })
  if (!before.eligible) throw new Error(JSON.stringify(before))
  await job('archiveSnapshot', { id: own.id, expectedscopehash: before.scopehash })
  await job('archiveSnapshot', { id: own.id })
  const after = await job('archiveSnapshot', { id: own.id, dryrun: true })
  if (after.reason !== 'ALREADY_ARCHIVED') throw new Error(JSON.stringify(after))
  fs.writeFileSync(`${root}/${cluster}-tenant-archive.json`, JSON.stringify({ results, before, after }, null, 2))
  console.log(cluster, 'owner archive/repeat and foreign rejection PASS')
 } else if (phase === 'archive-baseline') {
  const s = reg.snapshots.find(x => x.scenario === 'residue')
  const result = await job('archiveSnapshot', { id: s.id }); fs.writeFileSync(`${root}/${cluster}-archive-baseline.json`, JSON.stringify(result, null, 2))
 } else if (phase === 'verify-ui-jobs') {
  const ids = JSON.parse(process.argv[4]), results = []
  for (let i = 0; i < ids.length; i++) {
    const job = await api('queryAsyncJobResult', { jobid: ids[i] })
    const result = job.jobresult?.deletesnapshotresponse
    if (job.jobstatus !== 1 || !result?.force || !result.dbcleanupcomplete || result.physicalcleanupcomplete !== (i === 1) || result.pendingcleanup !== (i === 1 ? 0 : 1)) throw new Error(JSON.stringify(job))
    results.push(job)
  }
  fs.writeFileSync(`${root}/${cluster}-ui-force-jobs.json`, JSON.stringify(results, null, 2))
  console.log(cluster, 'UI force database-only then physical-retry async result contract PASS')
 } else if (phase === 'call') {
  const command = process.argv[4], args = JSON.parse(process.argv[5] || '{}')
  const result = await job(command, args); console.log(JSON.stringify(clean(result)))
 } else if (phase === 'cleanup') {
  const results = []
  for (const snapshot of reg.snapshots) {
    if (!snapshot.name.startsWith(prefix + '-')) throw new Error('Refusing non-fixture cleanup')
    if (snapshot.scenario === 'quota-creation-failed') continue // Already removed through API; DB audit checks its removed timestamp.
    results.push({ type: 'snapshot', id: snapshot.id, result: await job('deleteSnapshot', { id: snapshot.id }) })
  }
  for (const volume of reg.volumes) {
    if (!volume.name.startsWith(prefix + '-')) throw new Error('Refusing non-fixture cleanup')
    results.push({ type: 'volume', id: volume.id, result: await job('deleteVolume', { id: volume.id }) })
  }
  if (reg.tenant) results.push({ type: 'account', id: reg.tenant.id, result: await job('deleteAccount', { id: reg.tenant.id }) })
  if (reg.tenant?.role) results.push({ type: 'role', id: reg.tenant.role, result: await api('deleteRole', { id: reg.tenant.role }) })
  fs.writeFileSync(`${root}/${cluster}-cleanup.json`, JSON.stringify(results, null, 2))
  const inventory = {}
  for (const command of ['listVolumes', 'listSnapshots']) inventory[command] = await api(command, { listall: true, page: 1, pagesize: 500, ...(command === 'listSnapshots' ? { showunique: false } : {}) })
  fs.writeFileSync(`${root}/${cluster}-existing-after.json`, JSON.stringify(inventory, null, 2))
  for (const [command, key] of [['listVolumes', 'volume'], ['listSnapshots', 'snapshot']]) {
    if (inventory[command][key]?.some(x => x.name?.startsWith(prefix))) throw new Error('Active fixtures remain after cleanup')
  }
  console.log(cluster, 'API fixture cleanup PASS')
 } else if (phase === 'collect') {
  const result = await api('listSnapshots', { keyword: prefix, listall: true, showunique: false, page: 1, pagesize: 500 })
  fs.writeFileSync(`${root}/${cluster}-current.json`, JSON.stringify(result, null, 2)); console.log(JSON.stringify(clean(result)))
 }
 console.log(cluster, phase, 'complete')
} finally { await api('logout').catch(() => {}) }
