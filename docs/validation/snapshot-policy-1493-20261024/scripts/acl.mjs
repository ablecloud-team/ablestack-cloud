import fs from 'node:fs'
import assert from 'node:assert/strict'
import {api,job,login,logout,root,prefix,cluster,registryFile}from'./api.mjs'
const reg=JSON.parse(fs.readFileSync(registryFile)),owner=reg.accounts.find(x=>x.scenario==='owner'),v=reg.volumes.find(x=>x.scenario==='owner'),other=reg.volumes.find(x=>x.scenario==='other'),checks=[]
await login();let p;try{p=(await api('createSnapshotPolicy',{volumeid:other.id,intervaltype:'DAILY',schedule:'59:23',timezone:'UTC',maxsnaps:2,fordisplay:false})).snapshotpolicy;reg.policies.push({...p,scenario:'acl'});fs.writeFileSync(registryFile,JSON.stringify(reg,null,2))}finally{await logout()}
process.env.MOLD_TEST_USER=owner.name;process.env.MOLD_TEST_DOMAIN=reg.domain.path.replace(/^\/?ROOT/,'')
await login();try{for(const args of [{volumeid:v.id,policyid:p.id,name:prefix+'-must-not-allocate-owner'},{volumeid:other.id,name:prefix+'-must-not-foreign-source'},{volumeid:v.id,policyid:'-1',name:prefix+'-must-not-internal-owner'}]){let error;try{await job('createSnapshot',args)}catch(e){error=JSON.parse(e.message)}assert(error);assert([431,531,403].includes(error.errorcode));checks.push({args,error})}}finally{await logout()}
delete process.env.MOLD_TEST_USER;delete process.env.MOLD_TEST_DOMAIN
await login();try{await job('deleteSnapshotPolicies',{id:p.id});reg.policies.find(x=>x.id===p.id).deleted=true;fs.writeFileSync(registryFile,JSON.stringify(reg,null,2));assert.equal((await api('listSnapshots',{listall:true,keyword:prefix+'-must-not'})).count||0,0);const alerts=await api('listAlerts',{page:1,pagesize:5});fs.writeFileSync(root+'/'+cluster+'-alerts-after.json',JSON.stringify(alerts,null,2))}finally{await logout()}
fs.writeFileSync(root+'/'+cluster+'-allocation-acl.json',JSON.stringify({checks,noUnauthorizedAllocation:true},null,2));console.log(cluster,'owner creation ACL 3 denials,zero unauthorized snapshots')
