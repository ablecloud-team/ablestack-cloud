import fs from 'node:fs'
import assert from 'node:assert/strict'
import {api,login,logout,root,cluster,registryFile}from './api.mjs'
const r=JSON.parse(fs.readFileSync(registryFile)),db=JSON.parse(fs.readFileSync(`${root}/${cluster}-ui-db-oracle.json`)),out=[]
const pairs=ts=>ts.map(t=>JSON.stringify([t.key,t.value])).sort()
await login();try{for(const t of r.targets){const args={id:t.id,listall:true,...(t.projectid?{projectid:t.projectid}:{})};const tags=(await api('listTags',{...args,resourceid:t.id,resourcetype:t.type,page:1,pagesize:100})).tag||[];const parent=await api(t.type==='Snapshot'?'listSnapshots':'listVirtualMachines',args);const obj=(parent[t.type==='Snapshot'?'snapshot':'virtualmachine']||[])[0];assert.ok(obj);assert.deepEqual(pairs(tags),pairs(db.tags.filter(x=>x.resourceid===t.id)));assert.deepEqual(pairs(obj.tags||[]),pairs(tags));out.push({id:t.id,type:t.type,scenario:t.scenario,tags,apiParentAndDbAgree:true})}fs.writeFileSync(`${root}/${cluster}-ui-api-oracle.json`,JSON.stringify(out,null,2));console.log(cluster,'UI tags API/parent/DB agree',out.length)}finally{await logout()}
