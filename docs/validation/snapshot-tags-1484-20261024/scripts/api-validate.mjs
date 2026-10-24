import fs from'node:fs'
import assert from'node:assert/strict'
import{execFileSync}from'node:child_process'
import{api,job,login,logout,root,prefix,cluster,registryFile}from'./api.mjs'
const r=JSON.parse(fs.readFileSync(registryFile)),out=[],phase=process.argv[3]||'all'
const db=()=>JSON.parse(execFileSync('python3',[root+'/scripts/dbtags.py',cluster],{encoding:'utf8'}))
const normalize=rows=>rows.map(t=>({key:t.key,value:t.value,resourceid:t.resourceid,resourcetype:t.resourcetype})).sort((a,b)=>JSON.stringify(a).localeCompare(JSON.stringify(b)))
const args=t=>({resourceids:t.id,resourcetype:t.type,...(t.projectid?{projectid:t.projectid}:{})})
async function state(t,label){const tags=await api('listTags',{resourceid:t.id,resourcetype:t.type,listall:true,...(t.projectid?{projectid:t.projectid}:{})});const database=db();const expected=database.tags.filter(x=>x.resourceid===t.id);assert.deepEqual(normalize(tags.tag||[]),normalize(expected));assert.equal(tags.count||0,expected.length);out.push({label,target:t.id,type:t.type,api:tags,database,pass:true});return tags.tag||[]}
async function rejected(command,params,kind){let error;try{await job(command,params)}catch(e){error=JSON.parse(e.message)}assert(error,'Expected rejection '+kind);assert(error.jobstatus===2||[431,531,403].includes(error.errorcode));out.push({label:kind,command,params,error,pass:true})}
async function positive(role,t){const p={...args(t),'tags[0].key':'api-'+role,'tags[0].value':prefix+' literal + & <value> 한글'};await job('createTags',p);let rows=await state(t,role+'-create');assert(rows.some(x=>x.key==='api-'+role));await job('deleteTags',p);await state(t,role+'-delete')}
for(const role of['admin','owner','other','domainadmin']){
 if(role==='admin'){delete process.env.MOLD_TEST_USER;delete process.env.MOLD_TEST_DOMAIN}else{process.env.MOLD_TEST_USER=r.accounts.find(x=>x.scenario===role).name;process.env.MOLD_TEST_DOMAIN=r.domain.path.replace(/^\/?ROOT/,'')}
 await login();try{
  if(role==='admin'){
   for(const t of r.targets){
    const p={...args(t),'tags[0].key':'api-admin','tags[0].value':prefix+' literal + & <value> 한글'}
    await job('createTags',p);await state(t,'admin-create')
    await rejected('createTags',p,'duplicate-job-failed');await state(t,'duplicate-preserves-tags')
    await job('deleteTags',p);await state(t,'admin-delete')
    await job('createTags',p);await state(t,'explicit-retry-after-conflict-removed')
    await job('deleteTags',p);await state(t,'admin-restored')
    const parent=await api(t.type==='Snapshot'?'listSnapshots':'listVirtualMachines',{id:t.id,listall:true,...(t.type==='Snapshot'?{showunique:true}:{}),...(t.projectid?{projectid:t.projectid}:{})});const resource=(parent.snapshot||parent.virtualmachine||[])[0];assert(resource&&resource.tags.some(x=>x.key==='baseline'));out.push({label:'parent-api-tags',target:t.id,type:t.type,tags:resource.tags,pass:true})
   }
   await rejected('createTags',{...args(r.targets[0]),'tags[0].key':'empty','tags[0].value':''},'empty-value-job-failed')
  }else if(role==='owner'||role==='other'){
   for(const t of r.targets.filter(x=>x.scenario===role||(role==='owner'&&x.scenario==='project')))await positive(role,t)
   for(const t of r.targets.filter(x=>x.type==='UserVm'||(x.type==='Snapshot'&&x.scenario===(role==='owner'?'other':'owner')))){
    await rejected('createTags',{...args(t),'tags[0].key':'must-not-write','tags[0].value':prefix},role+'-foreign-create-denied')
    await rejected('deleteTags',{...args(t),'tags[0].key':'baseline','tags[0].value':prefix+'-'+t.scenario},role+'-foreign-delete-denied')
    let response;try{response=await api('listTags',{listall:true,resourceid:t.id,resourcetype:t.type})}catch(e){response={denied:JSON.parse(e.message)}}
    assert(!response.tag?.length);out.push({label:role+'-foreign-read',target:t.id,result:response,pass:true})
   }
  }else{
   for(const t of r.targets.filter(x=>x.type==='Snapshot'&&x.scenario!=='project')){const response=await api('listTags',{listall:true,resourceid:t.id,resourcetype:t.type});assert.equal(response.count,1);out.push({label:'domainadmin-read',target:t.id,result:response,pass:true})}
  }
 }finally{await logout()}
}
const database=db();assert.equal(database.tags.length,4);assert(database.tags.every(x=>x.key==='baseline'));fs.writeFileSync(root+'/'+cluster+'-api-db-validation.json',JSON.stringify({cases:out,pass:out.length,final:database},null,2));console.log(cluster,out.length,'API/job/DB scenarios PASS')
