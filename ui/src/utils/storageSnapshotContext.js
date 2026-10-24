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
import { getAPI, postAPI } from '@/api'
import { readSnapshotCopyTargets } from '@/utils/storageSnapshotTransfer'

export const snapshotOperationResponse = (api, json) => json?.[api.toLowerCase() + 'response'] || (api === 'archiveSnapshot' ? json?.createsnapshotresponse : undefined)
export const storageSnapshotApis = ['createSnapshot', 'createVolume', 'createTemplate', 'revertSnapshot', 'archiveSnapshot', 'copySnapshot', 'deleteSnapshot', 'extractSnapshot']
export const copyTarget = value => JSON.parse(JSON.stringify(value || {}))
export function snapshotSecurityScope (store) {
  const g = store.getters
  return JSON.stringify([g.userInfo?.id, g.userInfo?.roletype, g.userInfo?.account, g.userInfo?.domainid, g.project?.id, store.state?.user?.token])
}
export function freezeSnapshotTarget (record, { kind = 'snapshot', zoneId = 'all' } = {}) {
  return Object.freeze({ kind, record: Object.freeze(copyTarget(record)), id: record?.id, zoneId })
}
function fail (reason) { throw new Error(reason) }
function rows (json, response, key) {
  const data = json?.[response]
  if (!data || !Array.isArray(data[key]) || Number(data.count ?? data[key].length) !== data[key].length) fail('message.snapshot.action.incomplete')
  return data[key]
}
export async function readSnapshotApiPermission (api) {
  try {
    const permission = (await getAPI('listApis', { name: api }, { preserveOnFailure: true, timeout: 15000 }))?.listapisresponse
    if (!permission?.api?.some(item => item.name === api)) fail('message.snapshot.action.permission')
    return permission
  } catch (error) {
    const message = Object.values(error.response?.data || {}).find(value => value?.errortext)?.errortext || ''
    if ([401, 403].includes(error.response?.status) || /Api Discovery.*unable to find an api/i.test(message)) fail('message.snapshot.action.permission')
    throw error
  }
}
export async function readSnapshotDiagnosis (api, params, current = () => true) {
  if (!current()) fail('message.snapshot.action.context.changed')
  let data = snapshotOperationResponse(api, await postAPI(api, { ...params, dryrun: true }, { preserveOnFailure: true }))
  if (!data) fail('message.snapshot.action.incomplete')
  if (data.jobid) {
    const id = data.jobid
    for (let attempt = 0; attempt < 30; attempt++) {
      if (!current()) fail('message.snapshot.action.context.changed')
      const result = (await getAPI('queryAsyncJobResult', { jobid: id }, { preserveOnFailure: true, timeout: 15000 })).queryasyncjobresultresponse
      if (result?.jobstatus === 1) { data = snapshotOperationResponse(api, result.jobresult) || result.jobresult; break }
      if (result?.jobstatus === 2) fail(result.jobresult?.errortext || 'message.snapshot.action.validation.failed')
      if (result?.jobstatus !== 0 || attempt === 29) fail('message.snapshot.action.incomplete')
      await new Promise(resolve => setTimeout(resolve, 500))
    }
  }
  if (!current() || !data?.scopehash) fail('message.snapshot.action.context.changed')
  return data
}
export async function freshStorageSnapshotContext (target, api, current = () => true, params = {}) {
  if (!target.id || !storageSnapshotApis.includes(api)) fail('message.snapshot.action.incomplete')
  if (!current()) fail('message.snapshot.action.context.changed')
  const request = (cmd, args) => { if (!current()) fail('message.snapshot.action.context.changed'); return getAPI(cmd, args, { preserveOnFailure: true, timeout: 15000 }) }
  await readSnapshotApiPermission(api)
  const volumeTarget = target.kind === 'volume'
  const json = await request(volumeTarget ? 'listVolumes' : 'listSnapshots', { id: target.id, listall: true, page: 1, pagesize: 500, includesnapshotcontext: true, ...(!volumeTarget ? { showunique: true } : {}) })
  const list = rows(json, volumeTarget ? 'listvolumesresponse' : 'listsnapshotsresponse', volumeTarget ? 'volume' : 'snapshot')
  if (list.length !== 1 || list[0].id !== target.id) fail('message.snapshot.action.unavailable')
  const record = list[0]; const source = record.snapshotcontext
  if (source?.version !== 1 || typeof source.sourceavailable !== 'boolean' || typeof source.revertreason !== 'string' || typeof source.createreason !== 'string') fail('message.snapshot.action.incomplete')
  const expected = target.record
  for (const key of ['account', 'domainid', 'projectid', ...(volumeTarget ? [] : ['volumeid'])]) {
    if ((expected[key] || '') !== (record[key] || '')) fail('message.snapshot.action.context.changed')
  }
  let copies = []; let diagnosis; let copyplan
  if (!volumeTarget) {
    const json = await request('listSnapshots', { id: target.id, listall: true, showunique: false, page: 1, pagesize: 500 })
    copies = rows(json, 'listsnapshotsresponse', 'snapshot')
    if (!copies.length || copies.some(item => item.id !== target.id)) fail('message.snapshot.action.incomplete')
  }
  let reason = ''
  if (api === 'createSnapshot') reason = source.createreason
  else if (api === 'revertSnapshot') reason = record.state !== 'BackedUp' || record.revertable !== true ? 'SNAPSHOT_NOT_REVERTABLE' : source.revertreason
  else if (['createVolume', 'createTemplate', 'copySnapshot', 'extractSnapshot'].includes(api) && record.state !== 'BackedUp') reason = 'SNAPSHOT_NOT_READY'
  else if (api === 'deleteSnapshot' && !['BackedUp', 'CreatedOnPrimary', 'Destroying', 'Error'].includes(record.state)) reason = 'SNAPSHOT_NOT_READY'
  if (target.zoneId === null) reason = 'UNKNOWN_REFERENCE_ZONE'
  if (api === 'copySnapshot' && target.zoneId === 'all') reason = 'UNKNOWN_REFERENCE_ZONE'
  if (api === 'copySnapshot' && target.zoneId && target.zoneId !== 'all') {
    copyplan = readSnapshotCopyTargets(await request('listSnapshotCopyTargets', { id: target.id, sourcezoneid: target.zoneId }), target)
    if (!copyplan.eligible) reason = copyplan.reason
  }
  if (['deleteSnapshot', 'archiveSnapshot'].includes(api)) {
    diagnosis = await readSnapshotDiagnosis(api, { id: target.id, ...(target.zoneId !== 'all' && target.zoneId ? { zoneid: target.zoneId } : {}), ...(params.force ? { force: true } : {}) }, current)
    if (diagnosis.snapshotid !== target.id) fail('message.snapshot.action.incomplete')
    if (api === 'archiveSnapshot' && !diagnosis.eligible) reason = diagnosis.reason || 'SNAPSHOT_NOT_READY'
  }
  if (!current()) fail('message.snapshot.action.context.changed')
  const identity = Object.fromEntries(['id', 'name', 'state', 'volumeid', 'volumename', 'account', 'domainid', 'projectid', 'zoneid'].map(k => [k, record[k] || '']))
  const references = copies.map(row => Object.fromEntries(['id', 'zoneid', 'datastoreid', 'datastoretype', 'datastorestate', 'locationtype'].map(k => [k, row[k] || '']))).sort((a, b) => JSON.stringify(a).localeCompare(JSON.stringify(b)))
  return { target, record, source, copies, reason, diagnosis, copyplan, fingerprint: JSON.stringify([identity, source, references, diagnosis?.scopehash, copyplan?.scopehash]) }
}
export function snapshotActionParams (target, api, values, context) {
  const params = copyTarget(values)
  if (api === 'createSnapshot') { delete params.volumeId; params.volumeid = target.id } else if (['createVolume', 'createTemplate'].includes(api)) params.snapshotid = target.id
  else params.id = target.id
  if (api === 'extractSnapshot') params.zoneid = context.record.zoneid
  if (api === 'deleteSnapshot') {
    delete params.zoneid
    if (target.zoneId && target.zoneId !== 'all') params.zoneid = target.zoneId
  }
  if (api === 'copySnapshot') { params.sourcezoneid = target.zoneId; params.expectedscopehash = context.copyplan.scopehash }
  if (context?.diagnosis?.scopehash) params.expectedscopehash = context.diagnosis.scopehash
  return params
}
export function snapshotResourceKeys (target, context) {
  return [...new Set([target.kind + ':' + target.id, 'volume:' + (target.kind === 'volume' ? target.id : context?.record.volumeid || target.record.volumeid || ''), context?.source.virtualmachineid ? 'vm:' + context.source.virtualmachineid : ''].filter(x => x && x !== 'volume:'))]
}
