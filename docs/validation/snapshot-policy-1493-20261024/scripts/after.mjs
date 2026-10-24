import fs from'node:fs'
import assert from'node:assert/strict'
import{execFileSync}from'node:child_process'
import{api,job,login,logout,root,prefix,cluster,registryFile}from'./api.mjs'
const reg=JSON.parse(fs.readFileSync(registryFile)),save=()=>fs.writeFileSync(registryFile,JSON.stringify(reg,null,2)),db=(phase,args={})=>JSON.parse(execFileSync('python3',[`${root}/scripts/owned_db.py`,cluster,phase,JSON.stringify(args)],{encoding:'utf8'}))
const phase=process.argv[3],v=reg.volumes.find(x=>x.scenario==='owner'),other=reg.volumes.find(x=>x.scenario==='other')
const checks=[];async function reject(command,args){let error;try{await api(command,args)}catch(e){error=JSON.parse(e.message)}assert(error);assert([431,531,403].includes(error.errorcode));checks.push({command,args,error})}
async function policy(interval,volume=v,max=8,rekey=true){const p=(await api('createSnapshotPolicy',{volumeid:volume.id,intervaltype:interval,schedule:interval==='HOURLY'?'59':interval==='DAILY'?'59:23':interval==='WEEKLY'?'59:23:7':'59:23:28',timezone:'UTC',maxsnaps:max,fordisplay:false,usestoragereplication:false})).snapshotpolicy;reg.policies.push({...p,scenario:phase+'-'+interval});save();if(rekey){reg.policies.at(-1).db=db('rekey',{uuid:p.id});save()}return p}
async function remove(p){await job('deleteSnapshotPolicies',{id:p.id});reg.policies.find(x=>x.id===p.id).deleted=true;save()}
async function snap(p,name,interval){const s=(await job('createSnapshot',{volumeid:v.id,policyid:p.id,name:prefix+'-'+name,asyncbackup:false,usestoragereplication:false})).snapshot;reg.snapshots.push({...s,scenario:name});save();assert.equal(s.intervaltype,interval);return s}
await login();try{
 if(phase==='fixed'){
  await remove(reg.policies.find(x=>x.scenario==='legacy-MONTHLY'))
  for(const interval of ['HOURLY','DAILY','WEEKLY','MONTHLY']){const p=await policy(interval);await snap(p,'fixed-'+interval,interval);await remove(p)}
  const manual=(await job('createSnapshot',{volumeid:v.id,name:prefix+'-manual',asyncbackup:false})).snapshot;reg.snapshots.push({...manual,scenario:'manual'});save();assert.equal(manual.intervaltype,'MANUAL')
  const foreign=await policy('DAILY',other,8,false)
  await reject('createSnapshot',{volumeid:v.id,policyid:foreign.id,name:prefix+'-must-not-allocate'})
  await reject('createSnapshot',{volumeid:v.id,policyid:'-1',name:prefix+'-must-not-internal'})
  await reject('createSnapshot',{volumeid:v.id,policyid:'-2',name:prefix+'-must-not-unknown'})
  await reject('createSnapshot',{volumeid:v.id,policyid:'00000000-0000-0000-0000-000000000000'})
  await remove(foreign)
  await reject('createSnapshot',{volumeid:other.id,policyid:foreign.id})
  fs.writeFileSync(`${root}/${cluster}-api-rejections.json`,JSON.stringify(checks,null,2))
  const rows=db('inspect');fs.writeFileSync(`${root}/${cluster}-fixed-db.json`,JSON.stringify(rows,null,2))
  for(const s of reg.snapshots.filter(x=>x.scenario.startsWith('fixed-'))){const row=rows.snapshots.find(x=>x.uuid===s.id);assert(row.snapshot_type>=3&&row.snapshot_type<=6);const d=Object.fromEntries(rows.details.filter(x=>x.snapshot_id===row.id).map(x=>[x.name,x.value]));assert.equal(d['snapshot.creation.intent'],'POLICY');assert.equal(d['snapshot.policy.id'],'8');assert.equal(d['snapshot.policy.interval'],s.intervaltype)}
 }else if(phase==='retention'){
  const p=await policy('HOURLY',v,2)
  for(let i=0;i<2;i++)await snap(p,'retention-'+i,'HOURLY')
  const result=await api('listSnapshots',{volumeid:v.id,intervaltype:'HOURLY',listall:true});assert.equal(result.count,2);assert(result.snapshot.every(x=>x.name.includes('-retention-')))
  for(const interval of ['DAILY','WEEKLY','MONTHLY'])assert.equal((await api('listSnapshots',{volumeid:v.id,intervaltype:interval,listall:true})).count,2)
  assert.equal((await api('listSnapshots',{volumeid:v.id,snapshottype:'MANUAL',listall:true})).count,1)
  await remove(p);const rows=db('inspect');assert.equal(rows.schedules.length,0);assert.equal(rows.policies.length,0)
  fs.writeFileSync(`${root}/${cluster}-retention.json`,JSON.stringify({retained:result,db:rows},null,2))
 }else if(phase==='scheduler-start'){
  const p=await policy('HOURLY',v,2);reg.schedulerPolicy=p;save()
  await job('createTags',{resourcetype:'SnapshotPolicy',resourceids:p.id,'tags[0].key':'qa1493-scheduler','tags[0].value':prefix})
  await job('updateSnapshotPolicy',{id:p.id,fordisplay:true});db('due',{uuid:p.id});console.log(cluster,'scheduler policy8 made due')
 }else if(phase==='scheduler-wait'){
  let found=null,restarted=false,latest=null
  for(let i=0;i<400;i++){
   const r=await api('listSnapshots',{volumeid:v.id,intervaltype:'HOURLY',listall:true,'tags[0].key':'qa1493-scheduler','tags[0].value':prefix})
   if(r.snapshot?.length){found=r.snapshot[0];if(!reg.snapshots.some(x=>x.id===found.id)){reg.snapshots.push({...found,scenario:'scheduler'});save()}
    latest=db('inspect');const sc=latest.schedules.find(x=>x.policy_id===8)
    if(sc&&!sc.async_job_id&&!sc.snapshot_id){restarted=true;break}
   }
   if(i%15===0)console.log(cluster,'scheduler pending',i*2,'seconds',found?'created; waiting next schedule':'waiting creation')
   await new Promise(r=>setTimeout(r,2000))
  }
  assert(found&&restarted);assert.equal(found.intervaltype,'HOURLY');assert.equal((await api('listSnapshots',{volumeid:v.id,intervaltype:'HOURLY',listall:true})).count,2)
  fs.writeFileSync(`${root}/${cluster}-scheduler.json`,JSON.stringify({created:found,nextSchedule:latest.schedules,db:latest,systemJobObserved:true},null,2))
  await remove(reg.schedulerPolicy);assert.equal(db('inspect').schedules.length,0)
 }else throw Error(phase)
 console.log(cluster,phase,'PASS')
}finally{await logout()}
