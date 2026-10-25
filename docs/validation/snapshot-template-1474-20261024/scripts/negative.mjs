import fs from 'node:fs';import {api,login,logout,root,prefix,cluster,registryFile} from './api.mjs'
const mode=process.argv[3],r=JSON.parse(fs.readFileSync(registryFile)),s=r.snapshots.find(x=>x.scenario==='negative'),before=await (async()=>null)();
await login();try{
 const name=`${prefix}-negative-${mode}`;const response=await api('createTemplate',{snapshotid:s.id,name,displaytext:name,ostypeid:JSON.parse(fs.readFileSync(`${root}/${cluster}-existing-before.json`)).listTemplates.template.find(x=>x.name==='nginx-lb-test.qcow2').ostypeid})
 let result;for(let i=0;i<90;i++){result=await api('queryAsyncJobResult',{jobid:response.jobid});if(result.jobstatus)break;await new Promise(r=>setTimeout(r,1000))}
 if(result.jobstatus!==2)throw Error('Negative fixture unexpectedly succeeded or unresolved')
 const rows=await api('listTemplates',{templatefilter:'all',keyword:name,listall:true,page:1,pagesize:100});if((rows.template||[]).some(x=>x.name===name&&x.isready))throw Error('Invalid image was Ready')
 fs.writeFileSync(`${root}/${cluster}-negative-${mode}.json`,JSON.stringify({snapshotid:s.id,jobid:response.jobid,job:result,templates:rows,noReady:true},null,2));console.log(cluster,mode,'correctly failed',result.jobresult.errortext)
}finally{await logout()}
