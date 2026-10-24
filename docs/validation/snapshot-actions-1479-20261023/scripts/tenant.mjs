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
const phase = process.argv[3], reg=JSON.parse(fs.readFileSync(registryFile))
const save=()=>fs.writeFileSync(registryFile,JSON.stringify(reg,null,2))
const signed = await login()
try {
 if (phase === 'create') {
  if (reg.tenant) throw new Error('Tenant exists')
  const role=(await api('listRoles',{type:'User'})).role.find(x=>x.name==='User')
  reg.role=(await api('createRole',{name:`${prefix}-role`,roleid:role.id,description:'Disposable snapshot1479 permission fixture',ispublic:false})).role;save()
  const name=prefix.toLowerCase()+'-tenant'
  const a=(await api('createAccount',{username:name,account:name,password:process.env.MOLD_TEST_PASSWORD,firstname:'Snapshot',lastname:'1479',email:'snapshot1479@example.test',accounttype:0,roleid:reg.role.id,domainid:signed.domainid})).account
  reg.tenant={id:a.id,name:a.name,domainid:a.domainid};save()
  const inventory=JSON.parse(fs.readFileSync(`${root}/${cluster}-existing-before.json`))
  const pool=inventory.listStoragePools.storagepool[0],disk=inventory.listDiskOfferings.diskoffering.find(x=>x.name===(cluster==='31'?'Custom-GFS-Sparse':'Custom'))
  const v=(await job('createVolume',{name:`${prefix}-tenant-source`,zoneid:pool.zoneid,storageid:pool.id,diskofferingid:disk.id,size:1,account:name,domainid:a.domainid})).volume
  reg.volumes.push({...v,scenario:'tenant'});save()
  const snapshot=(await job('createSnapshot',{volumeid:v.id,name:`${prefix}-tenant-snapshot`,asyncbackup:false})).snapshot
  reg.snapshots.push({...snapshot,scenario:'tenant'});save()
 } else if (['deny','allow'].includes(phase)) {
  if (!reg.role.name.startsWith(prefix)) throw new Error('Unsafe role')
  const rules=(await api('listRolePermissions',{roleid:reg.role.id})).rolepermission
  const rule=rules.find(x=>x.rule==='createVolume')
  if (!rule) throw new Error('An exact cloned createVolume rule is required')
  await api('updateRolePermission',{roleid:reg.role.id,ruleid:rule.id,permission:phase});save()
 } else if (phase==='verify') {
  if (signed.account!==reg.tenant.name || Number(signed.type)!==0) throw new Error('Incorrect tenant session')
  const own=reg.snapshots.find(x=>x.scenario==='tenant'),foreign=reg.snapshots.find(x=>x.scenario==='source')
  const allowed=await api('listSnapshots',{id:own.id,includesnapshotcontext:true,listall:true})
  if (allowed.snapshot?.length!==1 || !allowed.snapshot[0].snapshotcontext?.sourceavailable) throw new Error('Owner context unavailable')
  const hidden=await api('listSnapshots',{id:foreign.id,includesnapshotcontext:true,listall:true})
  if (hidden.snapshot?.length) throw new Error('Foreign snapshot visible')
  let rejected
  try {await job('createVolume',{snapshotid:foreign.id,name:`${prefix}-foreign-must-not-exist`,zoneid:own.zoneid})}catch(error){rejected=error.message}
  if (!rejected || !/denied|permission|access|does not exist|find|unable|not found/i.test(rejected)) throw new Error('Foreign create accepted')
  fs.writeFileSync(`${root}/${cluster}-owner-acl.json`,JSON.stringify({own:allowed,foreign:hidden,rejected:JSON.parse(rejected)},null,2))
 } else if (phase==='source-owner') {
  if (signed.account!==reg.tenant.name) throw new Error('Incorrect caller')
  const snapshot=reg.snapshots.find(x=>x.scenario==='tenant'),source=reg.volumes.find(x=>x.scenario==='tenant')
  const context=(await api('listSnapshots',{id:snapshot.id,includesnapshotcontext:true})).snapshot[0]
  if(context.snapshotcontext.sourceavailable!==false)throw new Error('Foreign source leaked')
  const rejected=[]
  for(const [command,args] of [['revertSnapshot',{id:snapshot.id}],['createSnapshot',{volumeid:source.id,name:`${prefix}-foreign-source-must-not-exist`}]]) {
   let failure
   try{await job(command,args)}catch(error){failure=JSON.parse(error.message)}
   if(!failure || !/access|permission|denied|belong|cannot|allowed|authorized|unable/i.test(failure.errortext||failure.jobresult?.errortext||''))throw new Error('Foreign source mutation was not rejected: '+JSON.stringify(failure))
   rejected.push({command,failure})
  }
  const restored=(await job('createVolume',{snapshotid:snapshot.id,name:`${prefix}-owner-recovery`,zoneid:snapshot.zoneid})).volume
  reg.volumes.push({...restored,scenario:'owner-recovery'});save()
  fs.writeFileSync(`${root}/${cluster}-owner-fault-api.json`,JSON.stringify({context,rejected,restored,status:'PASS'},null,2))
 } else if (phase==='verify-deny') {
  let permission
  try { permission=await api('listApis',{name:'createVolume'}) } catch (error) { if (!/Api Discovery.*unable to find an api/.test(error.message)) throw error; permission=JSON.parse(error.message) }
  if (permission.api?.some(x=>x.name==='createVolume')) throw new Error('Permission still available')
  let rejected
  try {await job('createVolume',{snapshotid:reg.snapshots.find(x=>x.scenario==='tenant').id,name:`${prefix}-denied-must-not-exist`})}catch(error){rejected=error.message}
  if (!rejected || !/denied|permission|access|does not exist|find|unable|not found|not allowed|api|unauthorized/i.test(rejected)) throw new Error('Revoked API accepted')
  fs.writeFileSync(`${root}/${cluster}-permission-revoked.json`,JSON.stringify({permission,rejected:JSON.parse(rejected)},null,2))
 } else throw new Error('Unknown phase')
 console.log(cluster,phase,'PASS')
} finally {await logout()}
