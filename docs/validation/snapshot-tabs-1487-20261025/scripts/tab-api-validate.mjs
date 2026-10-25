import fs from 'node:fs'
import assert from 'node:assert/strict'
import { api, login, logout, root, cluster, registryFile } from './api.mjs'
const r=JSON.parse(fs.readFileSync(registryFile)),db=JSON.parse(fs.readFileSync(root+'/'+cluster+'-tab-db-before.json')),copies=JSON.parse(fs.readFileSync(root+'/'+cluster+'-db-before.json'))
const cases=[],observed=[];const pass=(name,extra={})=>cases.push({name,...extra,pass:true})
await login()
try{
 for(const s of r.snapshots){
  const scope=s.projectid?{projectid:s.projectid}:{}
  const allNotes=(await api('listAnnotations',{entityid:s.id,entitytype:'SNAPSHOT',annotationfilter:'all',page:1,pagesize:100,...scope}));const notes=allNotes.annotation||[];const expected=db.notes.filter(x=>x.entityid===s.id&&!x.removed)
  assert.deepEqual(notes.map(x=>x.id).sort(),expected.map(x=>x.id).sort());for(const n of notes){const d=expected.find(x=>x.id===n.id);assert.equal(n.annotation,d.text);assert.equal(n.adminsonly,!!d.adminsonly)}pass(s.scenario+'-notes-db',{count:notes.length})
  const allEvents=await api('listEvents',{resourceid:s.id,resourcetype:'Snapshot',listall:true,page:1,pagesize:200,...scope});const events=allEvents.event||[],expectedEvents=db.events.filter(x=>x.resourceid===s.id&&(!s.projectid||x.accountId===x.resourceAccountId))
  assert.deepEqual(events.map(x=>x.id).sort(),expectedEvents.map(x=>x.id).sort());for(const e of events){const d=expectedEvents.find(x=>x.id===e.id);assert.equal(e.type,d.type);assert.equal(e.state,d.state);assert.equal(e.description,d.description)}pass(s.scenario+'-events-db',{count:events.length})
  const refs=await api('listSnapshots',{id:s.id,showunique:false,listall:true,page:1,pagesize:100,...scope});const expectedRefs=copies.references.filter(x=>x.snapshot===s.id&&x.state!=='Destroyed');assert.equal((refs.snapshot||[]).length,expectedRefs.length);assert.equal(new Set((refs.snapshot||[]).map(x=>x.copy.id)).size,expectedRefs.length);for(const copy of refs.snapshot||[]){const ref=expectedRefs.find(x=>x.role===copy.copy.role&&x.store===copy.datastoreid);assert(ref);assert.equal(copy.copy.state,ref.state);assert.equal(copy.copy.virtualsize,ref.size)}
  pass(s.scenario+'-copies-db',{count:(refs.snapshot||[]).length});observed.push({scenario:s.scenario,notes:allNotes,events:allEvents,copies:refs})
  if(s.scenario==='owner'){
   for(const[command,key,size,base]of[['listAnnotations','annotation',10,{entityid:s.id,entitytype:'SNAPSHOT',annotationfilter:'all'}],['listEvents','event',10,{resourceid:s.id,resourcetype:'Snapshot',listall:true}]]){
    const total=key==='annotation'?notes:events;const ids=[]
    for(let page=1;page<=Math.ceil(total.length/size);page++){const response=await api(command,{...base,page,pagesize:size});assert.equal(response.count,total.length);ids.push(...response[key].map(x=>x.id))}
    assert.equal(new Set(ids).size,ids.length);assert.deepEqual(ids.slice().sort(),total.map(x=>x.id).sort());pass(command+'-all-pages',{count:ids.length,pages:Math.ceil(ids.length/size)})
   }
   const filtered=await api('listEvents',{resourceid:s.id,resourcetype:'Snapshot',keyword:'annotation',listall:true,page:1,pagesize:100});assert.equal(filtered.count,events.filter(x=>x.description.toLowerCase().includes('annotation')).length);pass('events-server-search-db',{count:filtered.count})
  }
 }
}finally{await logout()}
for(const role of ['owner','other','domainadmin']){
 const account=r.accounts.find(x=>x.scenario===role);process.env.MOLD_TEST_USER=account.name;process.env.MOLD_TEST_DOMAIN=r.domain.path.replace(/^\/?ROOT/,'');await login()
 try{
  for(const scenario of ['owner','other','project']){const s=r.snapshots.find(x=>x.scenario===scenario),scope=s.projectid?{projectid:s.projectid}:{};const allowed=role==='domainadmin'||role===scenario||(role==='owner'&&scenario==='project')
   for(const[command,key,args]of[['listAnnotations','annotation',{entityid:s.id,entitytype:'SNAPSHOT',annotationfilter:'all'}],['listEvents','event',{resourceid:s.id,resourcetype:'Snapshot',listall:true}]]){
    let rows=[],denied=false;try{rows=(await api(command,{...args,page:1,pagesize:200,...scope}))[key]||[]}catch{denied=true}
    if(allowed){assert(!denied);const expected=command==='listAnnotations'?db.notes.filter(x=>x.entityid===s.id&&!x.removed&&!x.adminsonly):db.events.filter(x=>x.resourceid===s.id&&(s.projectid?x.accountId===x.resourceAccountId:role==='domainadmin'?x.domainid===r.domain.id:x.account===account.name));assert.deepEqual(rows.map(x=>x.id).sort(),expected.map(x=>x.id).sort())}else assert(denied||rows.length===0)
    pass(role+'-'+scenario+'-'+command+'-scope',{allowed,count:rows.length,denied})
   }
  }
 }finally{await logout()}
}
fs.writeFileSync(root+'/'+cluster+'-tab-api-oracle.json',JSON.stringify({cases,observed},null,2));console.log(cluster,'Tab API/independent DB/ACL',cases.length,'PASS')
