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
import { api, job, login, logout, root, cluster, registryFile, prefix } from './api.mjs'
const phase = process.argv[3], reg = JSON.parse(fs.readFileSync(registryFile)), results = []
const source = reg.snapshots.find(x => x.scenario === 'source'), volume = reg.volumes.find(x => x.scenario === 'source')
function pass (name, details) { results.push({ name, status: 'PASS', details }) }
async function denied (name, command, args, reason) {
 let error
 try { await job(command, args) } catch (failure) { error = failure.message }
 if (!error || !reason.test(error)) throw new Error(name + ': expected rejection, received '+error)
 pass(name, JSON.parse(error))
}
await login()
try {
 const context = (await api('listSnapshots', { id: source.id, includesnapshotcontext: true })).snapshot?.[0]?.snapshotcontext
 if (phase === 'running') {
  if (context?.virtualmachinestate !== 'Running' || context.revertreason !== 'VM_MUST_BE_STOPPED' || context.createreason !== '') throw new Error('Incorrect running context')
  pass('Running source permits creation and blocks revert', context)
  await denied('Backend rejects Running-source revert', 'revertSnapshot', { id: source.id }, /VM_MUST_BE_STOPPED|stopped/i)
  const snapshot = (await job('createSnapshot', { volumeid: volume.id, name: `${prefix}-running-snapshot`, asyncbackup: false })).snapshot
  reg.snapshots.push({ ...snapshot, scenario: 'running' }); fs.writeFileSync(registryFile, JSON.stringify(reg, null, 2))
  pass('Running attached volume creates a real snapshot', { id: snapshot.id })
  const ordinary = (await api('listSnapshots', { id: source.id })).snapshot[0]
  if ('snapshotcontext' in ordinary) throw new Error('Opt-in facts leaked into normal list')
  pass('Existing list contract remains unchanged without opt-in')
 } else if (phase === 'stopped') {
  if (context?.virtualmachinestate !== 'Stopped' || context.revertreason !== '') throw new Error('Incorrect stopped context')
  const result = await job('revertSnapshot', { id: source.id }); pass('Stopped attached source reverts', result)
 } else if (phase === 'conflict-vm') {
  if (context?.revertreason !== 'VM_SNAPSHOTS_EXIST') throw new Error('Missing VM snapshot conflict')
  await denied('Backend rejects retained VM snapshot conflict', 'revertSnapshot', { id: source.id }, /VM_SNAPSHOTS_EXIST|VM snapshot/i)
  pass('Current context exposes VM snapshot conflict', context)
 } else if (phase === 'conflict-backup') {
  if (context?.revertreason !== 'BACKUP_RESTORE_IN_PROGRESS' || context.createreason !== 'BACKUP_RESTORE_IN_PROGRESS') throw new Error('Missing restore conflict')
  await denied('Backend rejects restore conflict on revert', 'revertSnapshot', { id: source.id }, /backup restore.*progress/i)
  await denied('Backend rejects restore conflict on snapshot creation', 'createSnapshot', { volumeid: volume.id, name: `${prefix}-must-not-exist`, asyncbackup: false }, /backup restore.*progress/i)
  pass('Current context exposes NetBackup restoring conflict', context)
 } else throw new Error('Unknown phase')
 fs.writeFileSync(`${root}/${cluster}-checks-${phase}.json`, JSON.stringify(results,null,2))
 console.log(cluster, phase, 'PASS',results.length)
} finally { await logout() }
