import fs from 'node:fs'
import{api,job,login,logout,root,prefix,cluster,registryFile}from './api.mjs'
const inv=JSON.parse(fs.readFileSync(`${root}/${cluster}-existing-before.json`))
const reg=fs.existsSync(registryFile)?JSON.parse(fs.readFileSync(registryFile)):{cluster,prefix,accounts:[],volumes:[],snapshots:[]}
const save=()=>fs.writeFileSync(registryFile,JSON.stringify(reg,null,2));const phase=process.argv[3]
await login();try{
if(phase==='seed'){

 reg.domain=reg.domain||(await api('createDomain',{name:prefix+'-domain'})).domain;save()
 reg.child=reg.child||(await api('createDomain',{name:prefix+'-child',parentdomainid:reg.domain.id})).domain;save()
 for(const [role,type,domain]of [['owner',0,reg.domain],['other',0,reg.domain],['child',0,reg.child],['domainadmin',2,reg.domain]]){
  if(reg.accounts.some(x=>x.scenario===role))continue
  const account=(await api('createAccount',{username:prefix+'-'+role,account:prefix+'-'+role,password:process.env.MOLD_TEST_PASSWORD,firstname:'Snapshot',lastname:'OrderQA',email:'snapshot-order@example.invalid',accounttype:type,domainid:domain.id})).account
  reg.accounts.push({...account,scenario:role,loginDomain:domain.path});save()
  for(const [res,max]of [[3,50],[11,100]])await api('updateResourceLimit',{account:account.name,domainid:domain.id,resourcetype:res,max})
 }
 const owner=reg.accounts.find(x=>x.scenario==='owner')
 reg.project=reg.project||(await api('listProjects',{listall:true,name:prefix+'-project'})).project?.[0]||(await job('createProject',{name:prefix+'-project',displaytext:prefix+'-project',account:owner.name,domainid:reg.domain.id})).project;save()
 for(const [res,max]of [[3,50],[11,100]])await api('updateResourceLimit',{projectid:reg.project.id,resourcetype:res,max})
 const pool=inv.listStoragePools.storagepool[0],offering=inv.listDiskOfferings.diskoffering.find(x=>x.name===(cluster==='31'?'Custom-GFS-Sparse':'Custom'))
 if(!offering)throw Error('Offering unavailable')
 for(const scenario of ['owner','other','child','project']){
  if(reg.volumes.some(x=>x.scenario===scenario))continue
  const a=reg.accounts.find(x=>x.scenario===scenario)
  const v=(await job('createVolume',{name:prefix+'-'+scenario,zoneid:pool.zoneid,storageid:pool.id,diskofferingid:offering.id,size:1,...(a?{account:a.name,domainid:a.domainid}:{projectid:reg.project.id})})).volume
  reg.volumes.push({...v,scenario});save()
 }
}else if(phase==='snapshots'){
 for(const v of reg.volumes){
  const total=v.scenario==='owner'?24:v.scenario==='other'?2:v.scenario==='project'?3:1
  for(let i=reg.snapshots.filter(s=>s.scenario===v.scenario).length;i<total;i++){
   const suffix=i<8?'same':String.fromCharCode(90-(i%20))+'-'+String(i).padStart(2,'0')
   const s=(await job('createSnapshot',{volumeid:v.id,name:prefix+'-'+suffix,asyncbackup:false,usestoragereplication:false,...(v.projectid?{projectid:v.projectid}:{})})).snapshot
   reg.snapshots.push({...s,scenario:v.scenario,index:i});save()
   if(i<10)await job('createTags',{resourceids:s.id,resourcetype:'Snapshot','tags[0].key':'qa-order','tags[0].value':prefix,'tags[1].key':'qa-scenario','tags[1].value':v.scenario,...(v.projectid?{projectid:v.projectid}:{})})
  }
 }
}else throw Error('Unknown phase')
console.log(cluster,phase,'PASS',reg.volumes.length,reg.snapshots.length)
}finally{await logout()}
