// Licensed to the Apache Software Foundation (ASF) under one or more
// contributor license agreements. See the NOTICE file distributed with this
// work for additional information regarding copyright ownership. The ASF
// licenses this file to You under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance with the License.
// You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0 .
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
// WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
// License for the specific language governing permissions and limitations.
import fs from 'node:fs'
import { api, job, login, logout, root, prefix, cluster, registryFile } from './api.mjs'
const phase = process.argv[3]
const reg = JSON.parse(fs.readFileSync(registryFile))
const source = reg.volumes.find(x => x.scenario === 'source')
const snapshot = reg.snapshots.find(x => x.scenario === 'source')
if (![source.name, snapshot.name, reg.vm.name].every(name => name.startsWith(prefix + '-'))) throw new Error('Unsafe test target')
await login()
try {
  if (phase === 'long-name') {
    if(reg.snapshots.some(x=>x.scenario==='long-name'))throw new Error('Long-name fixture already exists')
    const volume=reg.volumes.find(x=>x.scenario==='other')
    const name=prefix+'-long-snapshot-name-for-dialog-line-wrapping-and-target-review-'+('long-name-'.repeat(5))
    const row=(await job('createSnapshot',{volumeid:volume.id,name,asyncbackup:false,usestoragereplication:false})).snapshot
    reg.snapshots.push({...row,scenario:'long-name'});fs.writeFileSync(registryFile,JSON.stringify(reg,null,2));console.log(cluster,'long-name snapshot',row.id)
  } else if (phase === 'detach-source') {
    await job('detachVolume', { id: source.id })
  } else if (phase === 'deny-running') {
    const context = (await api('listSnapshots', { id: snapshot.id, includesnapshotcontext: true })).snapshot[0].snapshotcontext
    if (context.virtualmachinestate !== 'Running' || context.revertreason !== 'VM_MUST_BE_STOPPED') throw new Error('Running precondition missing')
    let failure
    try { await job('revertSnapshot', { id: snapshot.id }) } catch (error) { failure = error.message }
    if (!/VM_MUST_BE_STOPPED|stopped/i.test(failure || '')) throw new Error('Expected running-source rejection: ' + failure)
    fs.writeFileSync(`${root}/${cluster}-running-api-rejection.json`, JSON.stringify({ context, failure, status: 'PASS' }, null, 2))
  } else if (phase === 'discover') {
    for (const [command, key, bucket] of [['listVolumes', 'volume', 'volumes'], ['listSnapshots', 'snapshot', 'snapshots']]) {
      const rows = (await api(command, { listall: true, page: 1, pagesize: 500, keyword: prefix }))[key] || []
      for (const row of rows) if (row.name.startsWith(prefix + '-') && !reg[bucket].some(item => item.id === row.id)) reg[bucket].push({ ...row, scenario: row.name.slice(prefix.length + 1) })
    }
    fs.writeFileSync(registryFile, JSON.stringify(reg, null, 2))
  } else if (phase === 'context') {
    const data = {}
    for (const row of reg.snapshots) data[row.scenario] = await api('listSnapshots', { id: row.id, includesnapshotcontext: true })
    fs.writeFileSync(`${root}/${cluster}-context-${process.argv[4] || 'current'}.json`, JSON.stringify(data, null, 2))
  } else throw new Error('Unknown phase')
  console.log(cluster, phase, 'PASS')
} finally { await logout() }
