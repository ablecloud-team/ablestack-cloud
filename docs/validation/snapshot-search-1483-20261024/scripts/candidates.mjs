import fs from 'node:fs'
import assert from 'node:assert/strict'
import{api,login,logout,root,cluster,registryFile}from './api.mjs'
const reg=JSON.parse(fs.readFileSync(registryFile)),evidence={cluster,checks:[]},owner=reg.accounts.find(x=>x.scenario==='owner'),other=reg.accounts.find(x=>x.scenario==='other'),vol=reg.volumes.find(x=>x.scenario==='owner')
for(const user of ['admin','owner','other','domainadmin']){
 const a=reg.accounts.find(x=>x.scenario===user);process.env.MOLD_TEST_USER=a?.name||'admin';process.env.MOLD_TEST_DOMAIN=a?a.loginDomain.replace(/^ROOT/,'')+'/':'/';await login();try{
 if(user==='admin'||user==='domainadmin'){const accounts=await api('listAccounts',{listall:false,domainid:owner.domainid,isrecursive:false,page:1,pagesize:20});assert.equal(accounts.count,3);assert((accounts.account||[]).every(x=>x.domainid===owner.domainid));evidence.checks.push({user,exactAccountDomain:true,count:accounts.count,ids:accounts.account.map(x=>x.id)})}
 const params={listall:true,...(['admin','domainadmin'].includes(user)?{account:owner.name,domainid:owner.domainid}:{}),zoneid:vol.zoneid};const rows=[],pages=[]
 for(let page=1;page<=2;page++){const r=await api('listVolumes',{...params,page,pagesize:20});rows.push(...(r.volume||[]));pages.push({page,count:r.count||0,ids:(r.volume||[]).map(x=>x.id)})}
 const expected=reg.volumes.filter(x=>!x.sourceDeleted&&(user==='other'?x.scenario==='other':x.account===owner.name&&!x.projectid)).map(x=>x.id).sort();assert.deepEqual(rows.map(x=>x.id).sort(),expected);assert.equal(new Set(rows.map(x=>x.id)).size,rows.length)
 const replay=await api('listVolumes',{...params,page:1,pagesize:20});assert.deepEqual((replay.volume||[]).map(x=>x.id).sort(),pages[0].ids.slice().sort());evidence.checks.push({user,params,pages,expectedIds:expected,match:true})
 if(user==='admin'||user==='owner'){
  const p=await api('listVolumes',{listall:true,projectid:reg.project.id,page:1,pagesize:20});assert.deepEqual((p.volume||[]).map(x=>x.id),reg.volumes.filter(x=>x.scenario==='project').map(x=>x.id));evidence.checks.push({user,project:reg.project.id,ids:p.volume.map(x=>x.id),match:true})
 }
 if(user==='admin'){const r=await api('listVolumes',{...params,keyword:reg.prefix+'-candidate-23',page:1,pagesize:20});assert.equal(r.count,1);evidence.checks.push({user,keyword:reg.prefix+'-candidate-23',count:r.count,id:r.volume[0].id,match:true})}
 }finally{await logout()}
}
evidence.status='PASS';fs.writeFileSync(`${root}/${cluster}-candidates.json`,JSON.stringify(evidence,null,2));console.log(cluster,'candidate API PASS',evidence.checks.length)
