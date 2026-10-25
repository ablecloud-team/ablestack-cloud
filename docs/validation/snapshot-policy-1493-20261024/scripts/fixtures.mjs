import fs from 'node:fs'
import crypto from 'node:crypto'
import{execFileSync}from'node:child_process'
import{api,job,login,logout,root,prefix,cluster,registryFile}from'./api.mjs'
const reg=fs.existsSync(registryFile)?JSON.parse(fs.readFileSync(registryFile)):{cluster,prefix,accounts:[],volumes:[],snapshots:[],policies:[],archive:[],controls:[]}
const save=()=>fs.writeFileSync(registryFile,JSON.stringify(reg,null,2))
const db=(phase,args={})=>JSON.parse(execFileSync('python3',[`${root}/scripts/owned_db.py`,cluster,phase,JSON.stringify(args)],{encoding:'utf8'}))
const schedule=i=>i==='HOURLY'?'59':i==='DAILY'?'59:23':'59:23:28'
const phase=process.argv[3]
await login();try{
 if(phase==='seed'){
  const inv={};for(const cmd of ['listVolumes','listSnapshots','listVirtualMachines','listHosts','listStoragePools','listDiskOfferings','listZones','listImageStores','listAccounts','listDomains'])inv[cmd]=await api(cmd,{listall:true,page:1,pagesize:500,...(cmd==='listSnapshots'?{showunique:false}:{})})
  fs.writeFileSync(`${root}/${cluster}-existing-before.json`,JSON.stringify(inv,null,2))
  reg.domain=(await api('createDomain',{name:prefix+'-domain'})).domain;save()
  for(const role of ['owner','other']){
   const account=(await api('createAccount',{username:prefix+'-'+role,account:prefix+'-'+role,password:process.env.MOLD_TEST_PASSWORD,firstname:'Snapshot',lastname:'PolicyQA',email:'snapshot-policy@example.invalid',accounttype:0,domainid:reg.domain.id})).account
   reg.accounts.push({...account,scenario:role});save()
   for(const [res,max]of [[3,100],[2,100],[10,100],[11,100]])await api('updateResourceLimit',{account:account.name,domainid:reg.domain.id,resourcetype:res,max})
   const pool=inv.listStoragePools.storagepool[0],offering=inv.listDiskOfferings.diskoffering.find(x=>x.name===(cluster==='31'?'Custom-GFS-Sparse':'Custom'))
   const v=(await job('createVolume',{name:prefix+'-'+role,zoneid:pool.zoneid,storageid:pool.id,diskofferingid:offering.id,size:1,account:account.name,domainid:reg.domain.id})).volume
   reg.volumes.push({...v,scenario:role});save()
  }
 }else if(phase==='legacy'){
  const v=reg.volumes.find(v=>v.scenario==='owner')
  for(const interval of ['HOURLY','DAILY','WEEKLY','MONTHLY']){
   const s=interval==='WEEKLY'?'59:23:7':schedule(interval)
   const policy=(await api('createSnapshotPolicy',{volumeid:v.id,intervaltype:interval,schedule:s,timezone:'UTC',maxsnaps:8,fordisplay:false,usestoragereplication:false})).snapshotpolicy
   const proof=db('rekey',{uuid:policy.id});reg.policies.push({...policy,scenario:'legacy-'+interval,db:proof});save()
   const evidence={...proof,evidence_sha256:crypto.createHash('sha256').update(JSON.stringify({api:policy,db:proof})).digest('hex')};delete evidence.previous_id;reg.archive.push(evidence);save()
   const snap=(await job('createSnapshot',{volumeid:v.id,policyid:policy.id,name:prefix+'-legacy-'+interval,asyncbackup:false,usestoragereplication:false})).snapshot
   if(snap.intervaltype!=='INTERNAL')throw Error('Expected pre-fix collision: '+JSON.stringify(snap))
   reg.snapshots.push({...snap,scenario:'legacy-'+interval});save()
   if(interval!=='MONTHLY'){await job('deleteSnapshotPolicies',{id:policy.id});reg.policies.at(-1).deleted=true;save()}
  }
  const vOther=reg.volumes.find(v=>v.scenario==='other')
  for(const scenario of ['internal-control','insufficient-proof']){
   const snap=(await job('createSnapshot',{volumeid:vOther.id,name:prefix+'-'+scenario,asyncbackup:false})).snapshot
   reg.snapshots.push({...snap,scenario});save()
   reg.controls.push(db('control',{uuid:snap.id,intent:scenario==='internal-control'?'INTERNAL':null}));save()
  }
  fs.writeFileSync(`${root}/${cluster}-archive-policy-evidence.json`,JSON.stringify(reg.archive,null,2))
 }else throw Error(phase)
 console.log(cluster,phase,'PASS',reg.snapshots.length,'snapshots')
}finally{await logout()}
