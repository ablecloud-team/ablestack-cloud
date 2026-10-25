import fs from 'node:fs'
import assert from 'node:assert/strict'
import{api,job,login,logout,root,prefix,cluster,registryFile}from './api.mjs'
const r=JSON.parse(fs.readFileSync(registryFile)),db=JSON.parse(fs.readFileSync(`${root}/${cluster}-db-before.json`)),cases=[]
const byId=new Map(db.snapshots.map(s=>[s.uuid,s])),all=db.snapshots
const save=()=>fs.writeFileSync(`${root}/${cluster}-api-db-validation.json`,JSON.stringify({cases},null,2))
let role='admin'
function expected(args){let xs=all.filter(s=>role==='admin'||s.account===prefix+'-'+role||s.project===r.project.id&&role==='owner');if(!args.projectid)xs=xs.filter(s=>!s.project);else if(args.projectid!=='-1')xs=xs.filter(s=>s.project===args.projectid);if(args.volumeid)xs=xs.filter(s=>s.volume===args.volumeid);if(args.account)xs=xs.filter(s=>s.account===args.account);if(args.intervaltype)xs=xs.filter(s=>s.description===args.intervaltype);if(args.snapshottype)xs=xs.filter(s=>args.snapshottype==='MANUAL'?s.type===0:s.type>=3&&s.type<=6);if(args.name)xs=xs.filter(s=>s.name===args.name);if(args.keyword===prefix+'-none')xs=[];if(args['tags[0].key'])xs=xs.filter(s=>['owner-00','owner-01','project-00'].some(n=>s.name===prefix+'-'+n));return xs}
const value=(s,k)=>k==='virtualsize'?s.size:k==='intervaltype'?s.type:s[k]
async function check(label,args={}){
 const params={listall:true,keyword:prefix,sortkey:'name',sortorder:'asc',...args},base=expected(params),dir=params.sortorder==='asc'?1:-1
 base.sort((a,b)=>{const av=value(a,params.sortkey),bv=value(b,params.sortkey);return (av<bv?-1:av>bv?1:a.id-b.id)*dir})
 const copies=params.showunique===false||params.showunique==='false'
 const expectedCount=copies?db.references.filter(x=>base.some(s=>s.uuid===x.snapshot)&&(!params.locationtype||(params.locationtype==='primary'?'Primary':'Image')===x.role)).length:base.length
 const rows=[],requests=[];const size=Number(params.pagesize||20)
 for(let page=1;page<=Math.max(1,Math.ceil(expectedCount/size));page++){
  const x=await api('listSnapshots',{...params,page,pagesize:size});assert.equal(x.count||0,expectedCount,label+' count');assert.equal((x.snapshot||[]).length,Math.min(size,Math.max(0,expectedCount-(page-1)*size)),label+' page rows');requests.push({page,count:x.count||0,ids:(x.snapshot||[]).map(y=>copies?y.copy.id:y.id)});rows.push(...(x.snapshot||[]))
 }
 if(!copies)assert.deepEqual(rows.map(x=>x.id),base.map(x=>x.uuid),label+' DB order')
 else assert.equal(new Set(rows.map(x=>x.copy.id)).size,expectedCount,label+' copy key')
 for(const s of rows){const d=byId.get(s.id);assert.ok(d);assert.equal(s.virtualsize,d.size);assert.equal(s.intervaltype,d.description);const refs=db.references.filter(x=>x.snapshot===s.id&&(!copies||x.role===s.copy.role));if(!copies)assert.ok(refs.some(x=>x.physical===s.physicalsize),label+' physical byte count');else{assert.equal(s.copy.virtualsize,d.size);if(s.copy.physicalsize!==undefined)assert.ok(refs.some(x=>x.physical===s.copy.physicalsize))}assert.equal(s.domainid,d.domain)}
 cases.push({label,role,args:params,count:expectedCount,pages:requests,byteAndIntervalDbConsistent:true,...(copies?{perCopyPhysicalAvailable:rows.some(x=>x.copy.physicalsize!==undefined),missingMetricRenderedUnknown:true}:{}),pass:true});save()
}
await login();try{
 for(const n of ['owner-00','owner-01','project-00']){const s=r.snapshots.find(s=>s.scenario===n);if((await api('listTags',{resourceid:s.id,resourcetype:'Snapshot',listall:true,...(s.projectid?{projectid:s.projectid}:{})})).tag?.some(t=>t.key==='list-qa'&&t.value===prefix))continue;await job('createTags',{resourcetype:'Snapshot',resourceids:s.id,'tags[0].key':'list-qa','tags[0].value':prefix,...(s.projectid?{projectid:s.projectid}:{})})}
 for(const [key,orders]of [['name',['asc','desc']],['created',['asc','desc']],['virtualsize',['asc','desc']],['intervaltype',['asc','desc']]])for(const sortorder of orders)await check('global-'+key+'-'+sortorder,{sortkey:key,sortorder,projectid:'-1',pagesize:10})
 for(const size of [20,50])await check('page-size-'+size,{projectid:'-1',pagesize:size})
 await check('default-nonproject');await check('project',{projectid:r.project.id});await check('volume',{projectid:'-1',volumeid:r.volumes.find(v=>v.scenario==='owner').id});await check('other-account',{account:prefix+'-other',domainid:r.domain.id})
 await check('manual',{projectid:'-1',snapshottype:'MANUAL'});await check('recurring',{projectid:'-1',snapshottype:'RECURRING'})
 for(const intervaltype of ['HOURLY','DAILY','WEEKLY','MONTHLY'])await check('interval-'+intervaltype,{projectid:'-1',intervaltype})
 await check('tags',{projectid:'-1','tags[0].key':'list-qa','tags[0].value':prefix});await check('empty',{keyword:prefix+'-none'})
 for(const locationtype of [undefined,'primary','secondary'])await check('copies-'+(locationtype||'all'),{projectid:'-1',showunique:false,...(locationtype?{locationtype}:{}),pagesize:10})
 await logout()
 for(const user of ['owner','other']){role=user;process.env.MOLD_TEST_USER=prefix+'-'+user;process.env.MOLD_TEST_DOMAIN='/'+prefix+'-domain/';await login();await check(user+'-default');await check(user+'-all-projects',{projectid:'-1'});if(user==='owner')await check('owner-project',{projectid:r.project.id});await logout()}
 console.log(cluster,'API/UI-list contract + independent DB PASS',cases.length,'cases')
}finally{save();try{await logout()}catch{}}
