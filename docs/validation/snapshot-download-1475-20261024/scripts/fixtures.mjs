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
import { api,job,login,logout,root,prefix,cluster,registryFile } from './api.mjs'
const phase=process.argv[3]
const reg=fs.existsSync(registryFile)?JSON.parse(fs.readFileSync(registryFile)):{cluster,prefix,volumes:[],snapshots:[],templates:[],vms:[]}
const save=()=>fs.writeFileSync(registryFile,JSON.stringify(reg,null,2))
const inv=JSON.parse(fs.readFileSync(`${root}/${cluster}-existing-before.json`))
const pool=inv.listStoragePools.storagepool[0]
const base=inv.listTemplates.template.find(x=>x.name==='nginx-lb-test.qcow2'&&x.isready)
const offering=inv.listServiceOfferings.serviceoffering.find(x=>x.name===(cluster==='31'?'AS-PROD-31-AS01-2C2G-Sparse':'AS-PROD-32-AS01-2C2G'))
const net=inv.listNetworks.network.find(x=>x.name==='ADMIN-L2-Network')
await login()
try {
 if(phase==='seed'){
  if(reg.vms.length||reg.volumes.length)throw Error('Already seeded')
  const vm=(await job('deployVirtualMachine',{name:`${prefix}-root-source`,displayname:`${prefix}-root-source`,zoneid:pool.zoneid,templateid:base.id,serviceofferingid:offering.id,networkids:net.id,startvm:false})).virtualmachine
  reg.vms.push({...vm,scenario:'root-source'});save()
  for(const scenario of ['native','raw','negative']){
   const d=inv.listDiskOfferings.diskoffering.find(x=>x.name===(cluster==='31'?'Custom-GFS-Sparse':'Custom'))
   const v=(await job('createVolume',{name:`${prefix}-${scenario}`,zoneid:pool.zoneid,storageid:pool.id,diskofferingid:d.id,size:1})).volume
   reg.volumes.push({...v,scenario});save()
  }
 }else if(phase==='snapshots'){
  for(const v of reg.volumes.filter(v=>['native','raw','negative'].includes(v.scenario))){
   if(reg.snapshots.some(s=>s.scenario===v.scenario))continue
   const s=(await job('createSnapshot',{volumeid:v.id,name:`${prefix}-${v.scenario}`,asyncbackup:false,usestoragereplication:false})).snapshot
   reg.snapshots.push({...s,scenario:v.scenario});save()
  }
 }else if(phase==='template'){
  const scenario=process.argv[4];const s=reg.snapshots.find(s=>s.scenario===scenario)
  const name=`${prefix}-${scenario}-${process.argv[5]||'template'}`
  const t=(await job('createTemplate',{snapshotid:s.id,name,displaytext:name,ostypeid:base.ostypeid,requireshvm:true})).template
  reg.templates.push(...(Array.isArray(t)?t:[t]).map(x=>({...x,scenario,name})));save()
 }else if(phase==='root-snapshot'){
  const vm=reg.vms.find(x=>x.scenario==='root-source')
  const vols=(await api('listVolumes',{virtualmachineid:vm.id})).volume
  const v=vols.find(x=>x.type==='ROOT');if(!reg.volumes.some(x=>x.id===v.id))reg.volumes.push({...v,scenario:'root'});save()
  const s=(await job('createSnapshot',{volumeid:v.id,name:`${prefix}-root`,asyncbackup:false,usestoragereplication:false})).snapshot
  reg.snapshots.push({...s,scenario:'root'});save()
 }else if(phase==='start-source'||phase==='stop-source'){
  const vm=reg.vms.find(x=>x.scenario==='root-source');Object.assign(vm,(await job(phase==='start-source'?'startVirtualMachine':'stopVirtualMachine',{id:vm.id})).virtualmachine);save()
 }else if(phase==='orphan'||phase==='orphan-native'){
  const v=reg.volumes.find(x=>x.scenario===(phase==='orphan-native'?'native':'raw'));await job('deleteVolume',{id:v.id});v.deleted=true;save()
 }else if(phase.startsWith('restore')){
  for(const scenario of ['native','raw']){
   const s=reg.snapshots.find(x=>x.scenario===scenario)
   const v=(await job('createVolume',{snapshotid:s.id,name:`${prefix}-${scenario}-${phase}`,zoneid:pool.zoneid})).volume
   reg.volumes.push({...v,scenario:`${scenario}-${phase}`});save()
  }
 }else if(phase==='boot'||phase==='boot-raw'){
  const t=reg.templates.find(x=>x.scenario==='root'&&(phase!=='boot-raw'||x.name.endsWith('-raw-template')))
  const vm=(await job('deployVirtualMachine',{name:`${prefix}-${phase==='boot-raw'?'root-raw-boot':'root-boot'}`,displayname:`${prefix}-${phase==='boot-raw'?'root-raw-boot':'root-boot'}`,zoneid:pool.zoneid,templateid:t.id,serviceofferingid:offering.id,networkids:net.id,startvm:false})).virtualmachine
  reg.vms.push({...vm,scenario:phase==='boot-raw'?'root-raw-boot':'root-boot'});save()
 }else if(phase==='discover'){
  const ts=(await api('listTemplates',{templatefilter:'all',keyword:prefix,listall:true,page:1,pagesize:500})).template||[]
  for(const t of ts)if(t.name.startsWith(prefix)&&!reg.templates.some(x=>x.id===t.id)){reg.templates.push({...t,scenario:t.name.includes('-root-')?'root':'ui'});save()}
 }else throw Error('Unknown phase')
 console.log(cluster,phase,'PASS')
}finally{await logout()}
