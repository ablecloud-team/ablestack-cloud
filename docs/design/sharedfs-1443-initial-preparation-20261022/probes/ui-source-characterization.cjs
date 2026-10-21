// SPDX-License-Identifier: Apache-2.0
const fs=require('fs'),vm=require('vm'),parser=require('/root/work/ablestack-cloud/ui/node_modules/@babel/parser');
const path='/root/work/ablestack-cloud/ui/src/views/storage/CreateSharedFS.vue';
const all=fs.readFileSync(path,'utf8'),script=all.split('<script>')[1].split('</script>')[0];
const ast=parser.parse(script,{sourceType:'module'}),root=ast.program.body.find(n=>n.type==='ExportDefaultDeclaration').declaration;
const methods=root.properties.find(n=>n.key.name==='methods').value.properties;
function method(name,globals={}){const n=methods.find(x=>x.key.name===name);return vm.runInNewContext('({'+script.slice(n.start,n.end)+'})',globals)[name];}
(async()=>{
 const result={source:'ui/src/views/storage/CreateSharedFS.vue',cases:[]};
 let reads=0,wait=0;
 try{await method('pollStorageServiceSetupJob',{getAPI:async()=>{reads++;return {queryasyncjobresultresponse:{jobstatus:0}};}}).call({$t:k=>k,delay:async n=>{wait+=n;}},'test-job','createStorageNfsExport');throw Error('expected timeout');}
 catch(e){if(e.message!=='message.storage.service.setup.job.timeout')throw e;result.cases.push({case:'all_pending_default_poll',requests:reads,nominalWaitMs:wait,result:e.message});}
 const traceback="subprocess.TimeoutExpired: Command mkfs.xfs timed out after 20 seconds";
 try{await method('pollStorageServiceSetupJob',{getAPI:async()=>({queryasyncjobresultresponse:{jobstatus:2,jobresult:{errortext:traceback}}})}).call({$t:k=>k,delay:async()=>{}},'test-job','createStorageNfsExport');throw Error('expected failure');}
 catch(e){if(e.message!==traceback)throw e;result.cases.push({case:'guest_timeout_job_failure',result:e.message,structuredPhase:false});}
 for(const existing of [false,true]){
  let configure=0,pending=[];
  const fsrow={id:'fixture',state:'Allocated',creationstage:existing?'INSPECTED':'VOLUME_PREPARING',observedfilesystem:'ext4'};
  const ctx={pendingCreation:{request:{requestid:'fixture-key'}},readPendingCreation:()=>null,pollStorageServiceSetupJob:async()=>({}),resolveCreatedSharedFileSystem:async()=>fsrow,
   savePendingCreation:v=>pending.push(v),configureInitialStorageServices:async()=>{configure++;},notifyStorageServiceSetup:()=>{},parentFetchData:()=>{}};
  await method('runInitialStorageServiceSetup',{offeringFailureText:()=>''}).call(ctx,'fixture-job',{name:'fixture',useexistingvolume:existing},'fixture-note');
  if(configure!==(existing?0:1))throw Error('unexpected source flow');
  result.cases.push({case:existing?'existing_inspected_does_not_configure':'hypothetical_new_preparing_current_ui_continues',input:fsrow,configureCalls:configure,pendingCleared:pending.at(-1)===null,productionApiCalled:false});
 }
 fs.writeFileSync(__dirname+'/ui-source-injection-result.json',JSON.stringify(result,null,2)+'\n');
 console.log(JSON.stringify(result,null,2));
})().catch(e=>{console.error(e);process.exitCode=1;});
