import fs from 'node:fs'
import assert from 'node:assert/strict'
import{api,login,logout,root,prefix,cluster,registryFile}from './api.mjs'
const reg=JSON.parse(fs.readFileSync(registryFile));await login();try{
 const args={listall:true,projectid:'-1',keyword:prefix,page:1,pagesize:500};const implicit=await api('listSnapshots',args),explicit=await api('listSnapshots',{...args,showunique:true,sortkey:'created',sortorder:'desc'});assert.equal(implicit.count,30);assert.deepEqual(implicit.snapshot.map(x=>x.id),explicit.snapshot.map(x=>x.id));assert(!implicit.snapshot.some(x=>x.copy))
 const denied=[];for(const invalid of [{account:reg.accounts[0].name,domainid:reg.domain.id,projectid:'-1'},{storageid:reg.volumes[0].storageid||JSON.parse(fs.readFileSync(`${root}/${cluster}-existing-before.json`)).listStoragePools.storagepool[0].id,imagestoreid:JSON.parse(fs.readFileSync(`${root}/${cluster}-existing-before.json`)).listImageStores.imagestore[0].id}]){let error;try{await api('listSnapshots',{listall:true,keyword:prefix,...invalid})}catch(e){error=JSON.parse(e.message)}assert.equal(error?.errorcode,431);denied.push({invalid,errorcode:error.errorcode})}
 fs.writeFileSync(`${root}/${cluster}-compatibility.json`,JSON.stringify({implicitArgs:args,count:implicit.count,ids:implicit.snapshot.map(x=>x.id),defaultMatchesExplicit:true,defaultLogicalMode:true,invalidScopeCombinations:denied,status:'PASS'},null,2));console.log(cluster,'default compatibility and conflicting scopes PASS')
}finally{await logout()}
