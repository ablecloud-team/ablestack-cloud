import fs from 'node:fs'
import assert from 'node:assert/strict'
import{api,login,logout,root,prefix,cluster,registryFile}from './api.mjs'
const reg=JSON.parse(fs.readFileSync(registryFile)),oracle=JSON.parse(fs.readFileSync(`${root}/${cluster}-oracle.json`)),evidence={cluster,method:oracle.method,cases:[],invalid:[]};const save=()=>fs.writeFileSync(`${root}/${cluster}-api-validation.json`,JSON.stringify(evidence,null,2))
for(const user of ['admin','owner','other','domainadmin']){
 const a=reg.accounts.find(x=>x.scenario===user);process.env.MOLD_TEST_USER=a?.name||'admin';process.env.MOLD_TEST_DOMAIN=a?a.loginDomain.replace(/^ROOT/,'')+'/':'/'
 await login();try{
  if(user==='admin'){
   const contract=await api('listApis',{name:'listSnapshots'});assert(JSON.stringify(contract).includes('"name":"sortkey"'));assert(JSON.stringify(contract).includes('"name":"sortorder"'))
   for(const args of [{sortkey:'physicalsize'},{sortkey:'zonename'},{sortkey:'name desc;--'},{sortorder:'descending'},{sortkey:''},{sortorder:''}]){
    let error;try{await api('listSnapshots',{keyword:prefix,...args})}catch(e){error=JSON.parse(e.message)}
    assert.equal(error?.errorcode,431);evidence.invalid.push({args,errorcode:error.errorcode});save()
   }
  }
  for(const expected of oracle.cases.filter(x=>x.user===user)){
   const total=expected.rows.length,actual=[],pages=[],pagesize=7
   for(let page=1;page<=Math.max(1,Math.ceil(total/pagesize));page++){
    const args={...expected.args,page,pagesize};const response=await api('listSnapshots',args);const rows=response.snapshot||[]
    assert.equal(response.count||0,total,expected.name+' count')
    assert.equal(rows.length,Math.min(pagesize,Math.max(0,total-(page-1)*pagesize)),expected.name+' page length')
    actual.push(...rows);pages.push({page,count:response.count||0,rows:rows.map(x=>({id:x.id,copyid:x.copy?.id,name:x.name,created:x.created,account:x.account,zoneid:x.zoneid,tags:x.tags}))})
   }
   const keys=actual.map(x=>expected.args.showunique?x.id:x.copy?.id),truth=expected.rows.map(x=>expected.args.showunique?x.id:x.copyid)
   assert.deepEqual(keys,truth,expected.name+' global page order');assert.equal(new Set(keys).size,keys.length,expected.name+' duplicate')
   const beyond=await api('listSnapshots',{...expected.args,page:Math.ceil(total/pagesize)+2,pagesize});assert.equal(beyond.count||0,total);assert.equal((beyond.snapshot||[]).length,0)
   if(['name','created'].some(k=>expected.name==='unique-'+k+'-asc'||expected.name==='copies-'+k+'-desc')){
    const replay=await api('listSnapshots',{...expected.args,page:1,pagesize:500});assert.deepEqual((replay.snapshot||[]).map(x=>expected.args.showunique?x.id:x.copy?.id),truth)
   }
   evidence.cases.push({name:expected.name,user,args:expected.args,count:total,pages,globalOrderMatchesDb:true,noDuplicates:true,lastPageAndBeyond:true});save()
  }
 }finally{await logout()}
}
evidence.status='PASS';save();console.log(cluster,'PASS',evidence.cases.length,'API/DB cases',evidence.invalid.length,'invalid cases')
