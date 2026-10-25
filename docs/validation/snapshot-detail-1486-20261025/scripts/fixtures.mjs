import fs from'node:fs'
import{api,job,login,logout,root,prefix,cluster,registryFile}from'./api.mjs'
const reg=fs.existsSync(registryFile)?JSON.parse(fs.readFileSync(registryFile)):{cluster,prefix,accounts:[],volumes:[],snapshots:[],vms:[]},save=()=>fs.writeFileSync(registryFile,JSON.stringify(reg,null,2))
const inv=JSON.parse(fs.readFileSync(root+'/'+cluster+'-existing-before.json'))
await login();try{
 if(!reg.domain){reg.domain=(await api('createDomain',{name:prefix+'-domain'})).domain;save()}
 for(const[role,type]of[['owner',0],['other',0],['domainadmin',2]]){
  if(reg.accounts.some(x=>x.scenario===role))continue
  const a=(await api('createAccount',{username:prefix+'-'+role,account:prefix+'-'+role,password:process.env.MOLD_TEST_PASSWORD,firstname:'Detail',lastname:'QA',email:'detail-qa@example.invalid',accounttype:type,domainid:reg.domain.id})).account;reg.accounts.push({...a,scenario:role});save()
  for(const[type,max]of[[2,20],[3,30],[10,30],[11,30]])await api('updateResourceLimit',{account:a.name,domainid:reg.domain.id,resourcetype:type,max})
 }

 const owner=reg.accounts.find(x=>x.scenario==='owner')
 if(!reg.project){reg.project=(await job('createProject',{name:prefix+'-project',displaytext:prefix+'-project',account:owner.name,domainid:reg.domain.id})).project;save()}
 const pool=inv.listStoragePools.storagepool[0],disk=inv.listDiskOfferings.diskoffering.find(x=>x.name===(cluster==='31'?'Custom-GFS-Sparse':'Custom'))
 for(const role of['owner','other','project','deleted','vmdata']){
  const a=reg.accounts.find(x=>x.scenario===(role==='deleted'?'owner':role));const scope=role==='project'?{projectid:reg.project.id}:a?{account:a.name,domainid:a.domainid}:{}
  if(!reg.volumes.some(x=>x.scenario===role)){const v=(await job('createVolume',{name:prefix+'-'+role,zoneid:pool.zoneid,storageid:pool.id,diskofferingid:disk.id,size:1,...scope})).volume;reg.volumes.push({...v,scenario:role});save()}
 }
 if(!reg.vms.length){
  const template=inv.listTemplates.template.find(x=>x.name==='nginx-lb-test.qcow2'&&x.isready),offering=inv.listServiceOfferings.serviceoffering.find(x=>x.name===(cluster==='31'?'AS-PROD-31-AS01-2C2G-Sparse':'AS-PROD-32-AS01-2C2G')),network=inv.listNetworks.network.find(x=>x.name==='ADMIN-L2-Network');if(!template||!offering||!network)throw Error('VM prerequisites missing')
  const vm=(await job('deployVirtualMachine',{name:prefix+'-vm',displayname:prefix+'-vm',zoneid:pool.zoneid,templateid:template.id,serviceofferingid:offering.id,networkids:network.id,startvm:true})).virtualmachine;reg.vms.push({...vm,scenario:'vm-admin'});save()
 }
 if(!reg.vmStopped){await job('stopVirtualMachine',{id:reg.vms[0].id});reg.vmStopped=true;save()}
 if(!reg.vmAttached){await job('attachVolume',{id:reg.volumes.find(v=>v.scenario==='vmdata').id,virtualmachineid:reg.vms[0].id});reg.vmAttached=true;save()}
 if(!reg.volumes.some(x=>x.scenario==='root')){const vols=(await api('listVolumes',{virtualmachineid:reg.vms[0].id,listall:true})).volume;const v=vols.find(x=>x.type==='ROOT');if(!v)throw Error('ROOT absent');reg.volumes.push({...v,scenario:'root'});save()}
 for(const role of['owner','owner-next','other','project','deleted','vmdata','root']){
  if(reg.snapshots.some(x=>x.scenario===role))continue;const v=reg.volumes.find(x=>x.scenario===(role==='owner-next'?'owner':role));const sn=(await job('createSnapshot',{volumeid:v.id,name:prefix+'-'+role,asyncbackup:false,...(role==='project'?{projectid:reg.project.id}:{})})).snapshot;reg.snapshots.push({...sn,scenario:role});save()
 }
 if(!reg.sourceDeleted){await job('deleteVolume',{id:reg.volumes.find(x=>x.scenario==='deleted').id});reg.sourceDeleted=true;save()}
 for(const sn of reg.snapshots){if(reg.seeded?.includes(sn.id))continue;await job('createTags',{resourceids:sn.id,resourcetype:'Snapshot','tags[0].key':'baseline','tags[0].value':prefix+'-'+sn.scenario,...(sn.projectid?{projectid:sn.projectid}:{})});reg.seeded=[...(reg.seeded||[]),sn.id];save()}
 console.log(cluster,'detail fixtures PASS:7 snapshots;2 owners/project/deleted source/attached DATA/ROOT/Stopped VM')
}finally{await logout()}
