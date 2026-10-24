import fs from'node:fs'
import{api,job,login,logout,root,prefix,cluster,registryFile}from'./api.mjs'
const reg=fs.existsSync(registryFile)?JSON.parse(fs.readFileSync(registryFile)):{cluster,prefix,accounts:[],volumes:[],snapshots:[],vms:[]},save=()=>fs.writeFileSync(registryFile,JSON.stringify(reg,null,2))
const inv=JSON.parse(fs.readFileSync(root+'/'+cluster+'-existing-before.json'))
await login();try{
 if(!reg.domain){reg.domain=(await api('createDomain',{name:prefix+'-domain'})).domain;save()}
 for(const[role,type]of[['owner',0],['other',0],['domainadmin',2]]){
  if(reg.accounts.some(x=>x.scenario===role))continue
  const a=(await api('createAccount',{username:prefix+'-'+role,account:prefix+'-'+role,password:process.env.MOLD_TEST_PASSWORD,firstname:'Tags',lastname:'QA',email:'tags-qa@example.invalid',accounttype:type,domainid:reg.domain.id})).account;reg.accounts.push({...a,scenario:role});save()
  for(const[type,max]of[[2,20],[3,30],[10,30],[11,30]])await api('updateResourceLimit',{account:a.name,domainid:reg.domain.id,resourcetype:type,max})
 }
 const owner=reg.accounts.find(x=>x.scenario==='owner')
 if(!reg.project){reg.project=(await job('createProject',{name:prefix+'-project',displaytext:prefix+'-project',account:owner.name,domainid:reg.domain.id})).project;save();for(const[type,max]of[[2,20],[3,30],[10,30],[11,30]])await api('updateResourceLimit',{projectid:reg.project.id,resourcetype:type,max})}
 const pool=inv.listStoragePools.storagepool[0],disk=inv.listDiskOfferings.diskoffering.find(x=>x.name===(cluster==='31'?'Custom-GFS-Sparse':'Custom'))
 for(const role of['owner','other','project']){
  const a=reg.accounts.find(x=>x.scenario===role)
  if(!reg.volumes.some(x=>x.scenario===role)){const v=(await job('createVolume',{name:prefix+'-'+role,zoneid:pool.zoneid,storageid:pool.id,diskofferingid:disk.id,size:1,...(a?{account:a.name,domainid:a.domainid}:{projectid:reg.project.id})})).volume;reg.volumes.push({...v,scenario:role});save()}
  if(!reg.snapshots.some(x=>x.scenario===role)){const v=reg.volumes.find(x=>x.scenario===role);const s=(await job('createSnapshot',{volumeid:v.id,name:prefix+'-'+role,asyncbackup:false,...(role==='project'?{projectid:reg.project.id}:{})})).snapshot;reg.snapshots.push({...s,scenario:role});save()}
 }
 if(!reg.vms.length){
  const template=inv.listTemplates.template.find(x=>x.name==='nginx-lb-test.qcow2'&&x.isready),offering=inv.listServiceOfferings.serviceoffering.find(x=>x.name===(cluster==='31'?'AS-PROD-31-AS01-2C2G-Sparse':'AS-PROD-32-AS01-2C2G')),network=inv.listNetworks.network.find(x=>x.name==='ADMIN-L2-Network')
  if(!template||!offering||!network)throw Error('Dedicated VM prerequisites missing')
  const vm=(await job('deployVirtualMachine',{name:prefix+'-vm',displayname:prefix+'-vm',zoneid:pool.zoneid,templateid:template.id,serviceofferingid:offering.id,networkids:network.id,startvm:false})).virtualmachine;reg.vms.push({...vm,scenario:'vm-admin'});save()
 }
 reg.targets=[...reg.snapshots.map(s=>({...s,type:'Snapshot'})),...reg.vms.map(s=>({...s,type:'UserVm'}))];save()
 for(const target of reg.targets){if(target.seeded)continue;await job('createTags',{resourceids:target.id,resourcetype:target.type,'tags[0].key':'baseline','tags[0].value':prefix+'-'+target.scenario,...(target.projectid?{projectid:target.projectid}:{})});target.seeded=true;save();const tags=await api('listTags',{resourceid:target.id,resourcetype:target.type,listall:true,...(target.projectid?{projectid:target.projectid}:{})});fs.writeFileSync(root+'/'+cluster+'-initial-tags-'+target.scenario+'.json',JSON.stringify(tags,null,2))}
 console.log(cluster,'fixtures PASS: 3 snapshots,3 owners,project,Stopped VM; baseline tags seeded')
}finally{await logout()}
