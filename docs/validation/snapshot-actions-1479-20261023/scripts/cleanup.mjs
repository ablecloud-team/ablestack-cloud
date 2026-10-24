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
import { randomUUID } from 'node:crypto'
const phase=process.argv[3], reg=JSON.parse(fs.readFileSync(registryFile))
if(!['partial','full','principals'].includes(phase))throw new Error('Unexpected phase')
const resultFile=`${root}/${cluster}-cleanup-${phase}.json`
const results=fs.existsSync(resultFile)?JSON.parse(fs.readFileSync(resultFile)):[]
const record=(type,id,result)=>{results.push({at:new Date().toISOString(),type,id,result});fs.writeFileSync(resultFile,JSON.stringify(results,null,2))}
const guarded=x=>{if(!x.name?.startsWith(prefix+'-') && !(x.scenario==='vm-root'&&x.virtualmachineid===reg.vm.id))throw new Error('Non-fixture: '+x.id)}
await login()
try {
 if(phase==='principals') {
  for(const [command,key,args] of [['listVolumes','volume',{}],['listSnapshots','snapshot',{showunique:false}],['listTemplates','template',{templatefilter:'all'}],['listVirtualMachines','virtualmachine',{}]]) {
   const data=await api(command,{account:reg.tenant.name,domainid:reg.tenant.domainid,listall:true,page:1,pagesize:500,...args})
   if(data[key]?.some(x=>x.account===reg.tenant.name || x.name?.startsWith(prefix+'-')))throw new Error('Tenant has active resources: '+command)
  }
  if(reg.project){if(!reg.project.name.startsWith(prefix+'-'))throw new Error('Unsafe project');record('project',reg.project.id,await job('deleteProject',{id:reg.project.id}))}
  record('account',reg.tenant.id,await job('deleteAccount',{id:reg.tenant.id}))
  if(!reg.role.name.startsWith(prefix+'-'))throw new Error('Unsafe role')
  record('role',reg.role.id,await api('deleteRole',{id:reg.role.id}))
 } else {
  for(const t of reg.templates){guarded(t);const current=await api('listTemplates',{id:t.id,templatefilter:'all',listall:true});if(current.template?.length)record('template',t.id,await job('deleteTemplate',{id:t.id}))}
  const currentVm=await api('listVirtualMachines',{id:reg.vm.id,listall:true})
  if(currentVm.virtualmachine?.length){guarded(reg.vm);record('vm',reg.vm.id,await job('destroyVirtualMachine',{id:reg.vm.id,expunge:true}))}
  for(const v of reg.volumes){guarded(v);if(v.scenario==='vm-root'||(phase==='partial'&&v.scenario==='tenant'))continue;const current=(await api('listVolumes',{id:v.id,listall:true})).volume?.[0];if(!current)continue;if(current.virtualmachineid)throw new Error('Still attached: '+v.id);record('volume',v.id,await job('deleteVolume',{id:v.id}))}
  // Include allocated/error snapshot rows omitted by normal API list. Read-only DB audit is the allowlist.
  const db=JSON.parse(fs.readFileSync(`${root}/${cluster}-pre-cleanup-db.json`))
  const all=new Map([...reg.snapshots.map(x=>[x.id,x]),...db.snapshots.filter(x=>!x.removed).map(x=>[x.uuid,{id:x.uuid,name:x.name,state:x.state}])])
  for(const s of all.values()){
   guarded(s)
   const known=reg.snapshots.find(x=>x.id===s.id)
   if(phase==='partial' && (known?.volumeid===reg.volumes.find(x=>x.scenario==='tenant').id || /tenant-|lost-|query-project/.test(s.name)))continue
   let preview=await job('deleteSnapshot',{id:s.id,dryrun:true})
   if(preview.dbcleanupcomplete && preview.snapshotremoved)continue
   try{record('snapshot',s.id,await job('deleteSnapshot',{id:s.id,expectedscopehash:preview.scopehash,customjobid:randomUUID()}))}
   catch(error){record('normal-delete-failure',s.id,JSON.parse(error.message));preview=await job('deleteSnapshot',{id:s.id,force:true,dryrun:true});if(!preview.force||!preview.scopehash)throw new Error('Missing force contract');const forced=await job('deleteSnapshot',{id:s.id,force:true,expectedscopehash:preview.scopehash,customjobid:randomUUID()});if(forced.dbcleanupcomplete!==true)throw new Error('DB cleanup incomplete');record('forced-snapshot',s.id,forced)}
  }
 }
 console.log(cluster,'cleanup',phase,'PASS',results.length)
} finally {await logout()}
