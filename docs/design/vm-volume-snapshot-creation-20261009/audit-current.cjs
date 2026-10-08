/* Licensed under the Apache License, Version 2.0. */
// Execute current list methods with local fake API responses. No Cloud requests.
const fs = require('fs')
const path = require('path')
const assert = require('assert/strict')
const source = fs.readFileSync(path.resolve(__dirname, '../../../ui/src/views/compute/DeployVM.vue'), 'utf8')
function method (name, next, getAPI) {
  const start = source.indexOf('    ' + name + ' (')
  const end = source.indexOf('    ' + next + ' (', start)
  const code = source.slice(start, end).trim().replace(/},$/, '}').replace(name + ' (', 'function (')
  return new Function('getAPI', 'store', '_', 'return (' + code + ')')(getAPI, { getters: { project: null } }, { get: (obj, key) => obj?.[key] })
}
const context = { zone: { id: 'zone-fixture' }, owner: { account: 'fixture', domainid: 'domain-fixture' }, queryVolumeId: null, querySnapshotId: 'requested-snapshot', isZoneSelectedMultiArch: false }
async function run () {
  const observations = []
  let args
  const volumes = Array.from({ length: 25 }, (_, i) => ({ id: 'v' + i, name: 'volume-' + i, type: 'DATADISK' }))
  const listV = method('fetchUnattachedVolumes', 'fetchRootSnapshots', async (api, request) => { args = request; return { listvolumesresponse: { volume: volumes } } })
  const page = await listV.call(context, 'all', { page: 2, pageSize: 10 })
  assert.equal(page.listvolumesresponse.volume.length, 15)
  observations.push({ id: 'pagination', expectedPageSize: 10, observedPageSize: 15, source: 'DeployVM.vue:3484-3504' })
  observations.push({ id: 'volume-type-filter', observedDataDiskCandidates: page.listvolumesresponse.volume.length, note: 'No boot provenance or template required by the UI filter.' })
  const emptyV = method('fetchUnattachedVolumes', 'fetchRootSnapshots', async () => ({ listvolumesresponse: { count: 0 } }))
  try { await emptyV.call(context, 'all', {}); assert.fail('Expected current method to reject') } catch (error) {
    assert.match(error.message, /forEach/)
    observations.push({ id: 'empty-volume-array', observedError: error.message })
  }
  const listS = method('fetchRootSnapshots', 'fetchTemplates', async (api, request) => { args = request; return { listsnapshotsresponse: { snapshot: [{ id: 'other-snapshot', name: 'pending-root', volumetype: 'ROOT', state: 'Creating' }] } } })
  const snapshots = await listS.call(context, 'all', {})
  assert.equal(snapshots.listsnapshotsresponse.snapshot.length, 1)
  assert.equal(args.id, undefined)
  observations.push({ id: 'snapshot-state-filter', observedCreatingCandidate: true })
  observations.push({ id: 'snapshot-route-filter', routeSnapshotId: context.querySnapshotId, observedApiId: args.id ?? null })
  const emptyS = method('fetchRootSnapshots', 'fetchTemplates', async () => ({ listsnapshotsresponse: { count: 0 } }))
  try { await emptyS.call(context, 'all', {}); assert.fail('Expected current method to reject') } catch (error) {
    assert.match(error.message, /forEach/)
    observations.push({ id: 'empty-snapshot-array', observedError: error.message })
  }
  const result = { scope: 'Current source methods plus fake API responses; not a deployed VM creation test', sourceCommit: '2871963cd43aeb592f619c1b67d8a5974846409d', observations }
  fs.writeFileSync(path.join(__dirname, 'evidence/source-observations.json'), JSON.stringify(result, null, 2) + '\n')
  console.log(JSON.stringify(result, null, 2))
}
run().catch(error => { console.error(error); process.exitCode = 1 })
