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
import { api, job, login, logout, root, prefix, cluster, registryFile } from './api.mjs'
const phase = process.argv[3]
const reg = fs.existsSync(registryFile) ? JSON.parse(fs.readFileSync(registryFile)) : { cluster, prefix, volumes: [], snapshots: [], templates: [] }
const save = () => fs.writeFileSync(registryFile, JSON.stringify(reg, null, 2))
const inventory = JSON.parse(fs.readFileSync(`${root}/${cluster}-existing-before.json`))
const pool = inventory.listStoragePools.storagepool[0]
const disk = inventory.listDiskOfferings.diskoffering.find(x => x.name === (cluster === '31' ? 'Custom-GFS-Sparse' : 'Custom'))
const template = inventory.listTemplates.template.find(x => x.name === 'nginx-lb-test.qcow2' && x.isready)
await login()
try {
 if (phase === 'project') {
  if (reg.project) throw new Error('Project already exists')
  const result=await job('createProject',{name:`${prefix}-project`,displaytext:`${prefix}-project`,account:reg.tenant.name,domainid:reg.tenant.domainid})
  reg.project=result.project; if(!reg.project?.id)throw new Error('Missing project');save()
 } else if (phase === 'discover-ui') {
  for (const [command, key, bucket] of [['listSnapshots','snapshot','snapshots'],['listVolumes','volume','volumes'],['listTemplates','template','templates']]) {
   const data = await api(command,{listall:true,page:1,pagesize:500,keyword:prefix,...(command==='listTemplates'?{templatefilter:'all'}:{})})
   for (const item of data[key] || []) if (item.name.startsWith(prefix) && !reg[bucket].some(x=>x.id===item.id)) reg[bucket].push({...item,scenario:item.name.slice(prefix.length+1)})
  }
  save()
 } else if (phase === 'seed') {
  if (reg.vm || reg.volumes.length) throw new Error('Fixture already exists')
  const offering = inventory.listServiceOfferings.serviceoffering.find(x => x.name === (cluster === '31' ? 'AS-PROD-31-AS01-2C2G-Sparse' : 'AS-PROD-32-AS01-2C2G'))
  const network = inventory.listNetworks.network.find(x => x.name === 'ADMIN-L2-Network')
  reg.vm = (await job('deployVirtualMachine', { name: `${prefix}-vm`, displayname: `${prefix}-vm`, zoneid: pool.zoneid, templateid: template.id, serviceofferingid: offering.id, networkids: network.id, startvm: false })).virtualmachine
  save()
  for (const label of ['source', 'other', 'orphan-source']) {
   const volume = (await job('createVolume', { name: `${prefix}-${label}`, zoneid: pool.zoneid, storageid: pool.id, diskofferingid: disk.id, size: 1 })).volume
   reg.volumes.push({ ...volume, scenario: label }); save()
  }
 } else if (phase === 'snapshots') {
  if (reg.snapshots.length) throw new Error('Snapshots already exist')
  for (const label of ['source', 'other', 'orphan-source']) {
   const volume = reg.volumes.find(x => x.scenario === label)
   const snapshot = (await job('createSnapshot', { volumeid: volume.id, name: `${prefix}-${label}-snapshot`, asyncbackup: false, usestoragereplication: false })).snapshot
   reg.snapshots.push({ ...snapshot, scenario: label }); save()
  }
 } else if (phase === 'start') {
  reg.vm = (await job('startVirtualMachine', { id: reg.vm.id })).virtualmachine; save()
 } else if (phase === 'attach') {
  await job('attachVolume', { id: reg.volumes.find(x => x.scenario === 'source').id, virtualmachineid: reg.vm.id })
 } else if (phase === 'stop') {
  reg.vm = (await job('stopVirtualMachine', { id: reg.vm.id })).virtualmachine; save()
 } else if (phase === 'orphan') {
  const source = reg.volumes.find(x => x.scenario === 'orphan-source')
  if (!source.name.startsWith(prefix)) throw new Error('Unsafe fixture')
  await job('deleteVolume', { id: source.id })
  const snapshot = reg.snapshots.find(x => x.scenario === 'orphan-source')
  const result = await api('listSnapshots', { id: snapshot.id, includesnapshotcontext: true })
  if (result.snapshot?.[0]?.snapshotcontext?.sourceavailable !== false) throw new Error('Source absence not exposed')
  fs.writeFileSync(`${root}/${cluster}-orphan-context.json`, JSON.stringify(result, null, 2))
 } else if (phase === 'context') {
  const result = {}
  for (const snapshot of reg.snapshots) result[snapshot.scenario] = await api('listSnapshots', { id: snapshot.id, includesnapshotcontext: true })
  fs.writeFileSync(`${root}/${cluster}-context-${process.argv[4] || 'current'}.json`, JSON.stringify(result, null, 2))
 } else if (phase === 'restore-api') {
  const snapshot = reg.snapshots.find(x => x.scenario === 'orphan-source')
  const volume = (await job('createVolume', { snapshotid: snapshot.id, name: `${prefix}-orphan-api-volume`, zoneid: pool.zoneid })).volume
  reg.volumes.push({ ...volume, scenario: 'orphan-api-volume' }); save()
  const t = (await job('createTemplate', { snapshotid: snapshot.id, name: `${prefix}-orphan-api-template`, displaytext: `${prefix}-orphan-api-template`, ostypeid: template.ostypeid })).template
  reg.templates.push(...(Array.isArray(t) ? t : [t])); save()
 } else if (phase === 'revert-api') {
  const snapshot = reg.snapshots.find(x => x.scenario === 'source')
  const result = await job('revertSnapshot', { id: snapshot.id })
  fs.writeFileSync(`${root}/${cluster}-revert-api-${process.argv[4] || 'current'}.json`, JSON.stringify(result, null, 2))
 } else throw new Error('Unknown phase')
 console.log(cluster, phase, 'PASS')
} finally { await logout() }
