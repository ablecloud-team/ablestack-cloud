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
import { randomUUID } from 'node:crypto'
import { api, job, login, logout, root, prefix, cluster, registryFile } from './api.mjs'
if (process.argv[3] !== 'full') throw new Error('Expected full fixture cleanup')
const reg = JSON.parse(fs.readFileSync(registryFile))
const resultFile = `${root}/${cluster}-cleanup-full.json`
const results = fs.existsSync(resultFile) ? JSON.parse(fs.readFileSync(resultFile)) : []
const record = (type, id, result) => { results.push({ at: new Date().toISOString(), type, id, result }); fs.writeFileSync(resultFile, JSON.stringify(results, null, 2)) }
const guard = x => { if (!x.name?.startsWith(prefix + '-') && !(x.scenario === 'vm-root' && x.virtualmachineid === reg.vm.id)) throw new Error('Non-fixture: ' + x.id) }
async function deleteVolume (v) {
  guard(v)
  const current = (await api('listVolumes', { id: v.id, listall: true })).volume?.[0]
  if (!current) return
  if (current.virtualmachineid) throw new Error('Still attached: ' + v.id)
  record('volume', v.id, await job('deleteVolume', { id: v.id }))
}
await login()
try {
  guard(reg.vm)
  if ((await api('listVirtualMachines', { id: reg.vm.id, listall: true })).virtualmachine?.length) record('vm', reg.vm.id, await job('destroyVirtualMachine', { id: reg.vm.id, expunge: true }))
  // Release newly created copies before their snapshot and original-volume dependencies.
  for (const v of reg.volumes.filter(v => v.scenario.endsWith('-ui-volume'))) await deleteVolume(v)
  const db = JSON.parse(fs.readFileSync(`${root}/${cluster}-pre-cleanup-db.json`))
  const snapshots = db.snapshots.filter(x => !x.removed).sort((a,b) => b.id-a.id)
  const seen = new Set()
  for (const s of snapshots) {
    if (seen.has(s.uuid)) continue
    seen.add(s.uuid); guard(s)
    let preview = await job('deleteSnapshot', { id: s.uuid, dryrun: true })
    if (preview.dbcleanupcomplete && preview.snapshotremoved) continue
    try { record('snapshot', s.uuid, await job('deleteSnapshot', { id: s.uuid, expectedscopehash: preview.scopehash, customjobid: randomUUID() })) }
    catch (error) {
      record('normal-delete-failure', s.uuid, error.message)
      preview = await job('deleteSnapshot', { id: s.uuid, force: true, dryrun: true })
      if (!preview.force || !preview.scopehash) throw new Error('Missing force contract')
      const forced = await job('deleteSnapshot', { id: s.uuid, force: true, expectedscopehash: preview.scopehash, customjobid: randomUUID() })
      if (forced.dbcleanupcomplete !== true) throw new Error('DB cleanup incomplete')
      record('forced-snapshot', s.uuid, forced)
    }
  }
  for (const v of reg.volumes.filter(v => v.scenario !== 'vm-root' && !v.scenario.endsWith('-ui-volume'))) await deleteVolume(v)
  console.log(cluster, 'cleanup full PASS', results.length)
} finally { await logout() }
