const fs=require('fs');const assert=require('assert');
const dir=__dirname;const fixture=JSON.parse(fs.readFileSync(dir+'/rollback-race-actual-wire.json','utf8'));
const source=fs.readFileSync('/root/work/ablestack-cloud/ui/src/utils/storageConfigOperation.js','utf8');
const decodeSource=source.slice(source.indexOf('export function decodeConfigurationOperations'),source.indexOf('export const terminalConfigurationState')).replace('export function','function');
const decode=new Function(decodeSource+';return decodeConfigurationOperations')();
const functionSource=source.slice(source.indexOf('export async function observeConfigurationOperation'),source.indexOf('export async function observeConfigurationJob')).replace('export async function','async function');
const observe=new Function('decodeConfigurationOperations',functionSource+';return observeConfigurationOperation')(decode);
const calls=[];let lists=0;
(async()=>{
 const result=await observe({instanceid:fixture.instance.id,requestid:fixture.stale.params.requestid,jobid:fixture.job.params.jobid,
  get:async(api)=>{calls.push(api);if(api==='listStorageServiceConfigurationOperations')return ++lists===1?fixture.stale.body:fixture.terminal.body;if(api==='queryAsyncJobResult')return fixture.job.body;throw Error(api)},
  sleep:async()=>{},maxAttempts:3});
 assert.strictEqual(result.state,'RUNNING');assert.strictEqual(lists,1);
 const proof={actualSourceUsed:true,actualWireFixtureUsed:true,sourceFile:'ui/src/utils/storageConfigOperation.js',returnedState:result.state,returnedCanResume:result.canresume,jobStatus:2,authoritativeFinalState:fixture.terminal.body.liststorageserviceconfigurationoperationsresponse.configurationoperation[0].state,finalReadSkipped:true,calls,clusterMutations:0};
 fs.writeFileSync(dir+'/observer-race-proof.json',JSON.stringify(proof,null,2));console.log(JSON.stringify(proof));
})().catch(e=>{console.error(e);process.exit(1)});
