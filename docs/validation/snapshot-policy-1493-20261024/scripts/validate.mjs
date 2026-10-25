import fs from'node:fs'
import assert from'node:assert/strict'
import{api,login,logout,root,prefix,cluster,registryFile}from'./api.mjs'
const reg=JSON.parse(fs.readFileSync(registryFile)),oracle=JSON.parse(fs.readFileSync(`${root}/${cluster}-oracle.json`)),out=[]
for(const role of ['admin','owner','other']){
 if(role!=='admin'){process.env.MOLD_TEST_USER=reg.accounts.find(x=>x.scenario===role).name;process.env.MOLD_TEST_DOMAIN=reg.domain.path.replace(/^\/?ROOT/,'')}
 await login();try{
  for(const c of oracle.cases.filter(x=>x.role===role)){
   const rows=[];let count,blocked=null
   for(let page=1;page<=30;page++){
    let r;try{r=await api('listSnapshots',{...c.args,page,pagesize:2})}catch(e){const error=JSON.parse(e.message);if(role==='other'&&c.args.volumeid&&c.expected.length===0&&[431,531,403].includes(error.errorcode)&&/permission|access|authorized/i.test(error.errortext)){blocked=error;break}throw e}count=r.count||0;assert.equal(count,c.expected.length,c.name+' count')
    rows.push(...(r.snapshot||[]));if(rows.length>=count)break
   }
   if(blocked){out.push({case:c.name,role,request:c.args,count:0,denied:blocked,expectedIds:[],pass:true});continue}
   const actual=rows.map(s=>c.args.showunique?s.id:s.copy.id),expected=c.expected.map(s=>c.args.showunique?s.uuid:s.copyid)
   assert.deepEqual(actual,expected,c.name+' IDs/order');out.push({case:c.name,role,request:c.args,count,ids:actual,expectedIds:expected,pass:true})
  }
 }finally{await logout()}
}
fs.writeFileSync(`${root}/${cluster}-api-db-validation.json`,JSON.stringify({cases:out,pass:out.length},null,2));console.log(cluster,out.length,'API/DB cases PASS')
