import fs from 'node:fs'
import assert from 'node:assert/strict'
import{api,login,logout,root,prefix,cluster,registryFile}from './api.mjs'
const r=JSON.parse(fs.readFileSync(registryFile)),cases=[]
for(const role of ['owner','other']){
 process.env.MOLD_TEST_USER=prefix+'-'+role;process.env.MOLD_TEST_DOMAIN='/'+prefix+'-domain/';await login()
 try{
  const foreign=r.snapshots.find(s=>s.scenario===(role==='owner'?'other-00':'owner-00'))
  const tests=[{id:foreign.id},{account:prefix+'-'+(role==='owner'?'other':'owner'),domainid:r.domain.id},...(role==='other'?[{projectid:r.project.id}]:[])]
  for(const args of tests){try{const x=await api('listSnapshots',{keyword:prefix,listall:true,page:1,pagesize:100,...args});assert.equal(x.count||0,0);assert.equal((x.snapshot||[]).length,0);cases.push({role,args,deniedOrEmpty:true,empty:true})}catch(e){const x=JSON.parse(e.message);assert.ok([403,431,432,531].includes(x.errorcode));cases.push({role,args,deniedOrEmpty:true,error:x})}}
 }finally{await logout()}
}
fs.writeFileSync(`${root}/${cluster}-acl.json`,JSON.stringify(cases,null,2));console.log(cluster,'foreign account/resource/project ACL PASS',cases.length)
