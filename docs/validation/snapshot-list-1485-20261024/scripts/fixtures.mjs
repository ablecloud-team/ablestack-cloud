import fs from 'node:fs'
import {api,job,login,logout,root,prefix,cluster,registryFile}from './api.mjs'
const r=fs.existsSync(registryFile)?JSON.parse(fs.readFileSync(registryFile)):{prefix,cluster,accounts:[],volumes:[],snapshots:[],policies:[]},save=()=>fs.writeFileSync(registryFile,JSON.stringify(r,null,2))
const inv=JSON.parse(fs.readFileSync(`${root}/${cluster}-existing-before.json`))
await login();try{
 if(!r.domain){r.domain=(await api('createDomain',{name:prefix+'-domain'})).domain;save()}
 for(const role of ['owner','other']){
  if(r.accounts.some(a=>a.scenario===role))continue
  const a=(await api('createAccount',{username:prefix+'-'+role,account:prefix+'-'+role,password:process.env.MOLD_TEST_PASSWORD,firstname:'Snapshot',lastname:'ListQA',email:'snapshot-list@example.invalid',accounttype:0,domainid:r.domain.id})).account;r.accounts.push({...a,scenario:role});save()
  for(const[type,max]of[[2,20],[3,100],[10,100],[11,100]])await api('updateResourceLimit',{account:a.name,domainid:r.domain.id,resourcetype:type,max})
 }
 const owner=r.accounts.find(a=>a.scenario==='owner')
 if(!r.project){r.project=(await job('createProject',{name:prefix+'-project',displaytext:prefix+'-project',account:owner.name,domainid:r.domain.id})).project;save();for(const[type,max]of[[2,20],[3,100],[10,100],[11,100]])await api('updateResourceLimit',{projectid:r.project.id,resourcetype:type,max})}
 const pool=inv.listStoragePools.storagepool[0],offering=inv.listDiskOfferings.diskoffering.find(x=>x.name===(cluster==='31'?'Custom-GFS-Sparse':'Custom'))
 for(const role of ['owner','other','project']){
  const a=r.accounts.find(a=>a.scenario===role)
  if(!r.volumes.some(v=>v.scenario===role)){const v=(await job('createVolume',{name:prefix+'-'+role,zoneid:pool.zoneid,storageid:pool.id,diskofferingid:offering.id,size:1,...(a?{account:a.name,domainid:r.domain.id}:{projectid:r.project.id})})).volume;r.volumes.push({...v,scenario:role});save()}
  const v=r.volumes.find(v=>v.scenario===role),total=role==='owner'?23:role==='project'?2:1
  for(let i=0;i<total;i++){
   const scenario=role+'-'+String(i).padStart(2,'0');if(r.snapshots.some(s=>s.scenario===scenario))continue
   const interval=role==='owner'?['HOURLY','DAILY','WEEKLY','MONTHLY'][i]:undefined
   let policy
   if(interval){const schedule={HOURLY:'59',DAILY:'59:23',WEEKLY:'59:23:7',MONTHLY:'59:23:28'}[interval];policy=(await api('createSnapshotPolicy',{volumeid:v.id,intervaltype:interval,schedule,timezone:'UTC',maxsnaps:8,fordisplay:false})).snapshotpolicy;r.policies.push({...policy,scenario,deleted:false});save()}
   const s=(await job('createSnapshot',{volumeid:v.id,name:prefix+'-'+scenario,asyncbackup:false,...(policy?{policyid:policy.id}:{}),...(role==='project'?{projectid:r.project.id}:{})})).snapshot
   if(s.intervaltype!==(interval||'MANUAL'))throw Error('Incorrect interval '+s.intervaltype)
   r.snapshots.push({...s,scenario});save()
   if(policy){await job('deleteSnapshotPolicies',{id:policy.id});r.policies.find(p=>p.id===policy.id).deleted=true;save()}
  }
 }
 console.log(cluster,'fixtures PASS: 26 snapshots, 3 volumes, 2 users, project; four real recurring types')
}finally{save();await logout()}
