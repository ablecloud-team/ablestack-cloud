import fs from 'node:fs'
import assert from 'node:assert/strict'
import { api, login, logout, root, cluster, registryFile } from './api.mjs'
const r=JSON.parse(fs.readFileSync(registryFile)),owner=r.accounts.find(x=>x.scenario==='owner'),s=r.snapshots.find(x=>x.scenario==='owner')
process.env.MOLD_TEST_USER=owner.name;process.env.MOLD_TEST_DOMAIN=r.domain.path.replace(/^\/?ROOT/,'')
await login();const cases=[]
try{
 const notes=await api('listAnnotations',{entityid:s.id,entitytype:'SNAPSHOT',annotationfilter:'all',page:1,pagesize:100});assert.equal(notes.count,25);assert(!notes.annotation.some(x=>x.adminsonly));cases.push({name:'readonly-public-notes',pass:true})
 const events=await api('listEvents',{resourceid:s.id,resourcetype:'Snapshot',listall:true,page:1,pagesize:100});assert.equal(events.count,1);cases.push({name:'readonly-own-account-events',pass:true})
 for(const[command,args]of[['addAnnotation',{entityid:s.id,entitytype:'SNAPSHOT',annotation:r.prefix+'-must-not-create'}],['removeAnnotation',{id:notes.annotation[0].id}],['updateAnnotationVisibility',{id:notes.annotation[0].id,adminsonly:true}]]){
  let error;try{await api(command,args)}catch(e){error=e}assert(error,command+' must be denied');cases.push({name:'readonly-'+command+'-denied',pass:true})
 }
 fs.writeFileSync(root+'/'+cluster+'-tab-readonly.json',JSON.stringify(cases,null,2));console.log(cluster,'Read-only tab API 5 PASS')
}finally{await logout()}
