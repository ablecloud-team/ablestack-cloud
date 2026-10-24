import fs from 'node:fs';import {api,job,login,logout,root,prefix,cluster,registryFile} from './api.mjs'
const reg=JSON.parse(fs.readFileSync(registryFile));let actions=[];const save=()=>fs.writeFileSync(`${root}/${cluster}-cleanup.json`,JSON.stringify(actions,null,2));
await login();try{
 const listing=async(cmd,key,args={})=>(await api(cmd,{listall:true,page:1,pagesize:500,...args}))[key]||[]
 const own=x=>x.name?.startsWith(prefix)||x.displayname?.startsWith(prefix)
 const vms=(await listing('listVirtualMachines','virtualmachine')).filter(own)
 for(const v of vms){if(v.state==='Destroyed')await job('expungeVirtualMachine',{id:v.id});else await job('destroyVirtualMachine',{id:v.id,expunge:true});actions.push({command:'destroyVirtualMachine',id:v.id,name:v.name});save()}
 const vols=(await listing('listVolumes','volume')).filter(own)
 for(const v of vols){if(v.virtualmachineid)throw Error('Still attached volume');await job('deleteVolume',{id:v.id});actions.push({command:'deleteVolume',id:v.id,name:v.name});save()}
 const templates=(await listing('listTemplates','template',{templatefilter:'all'})).filter(own)
 for(const t of templates){await job('deleteTemplate',{id:t.id});actions.push({command:'deleteTemplate',id:t.id,name:t.name});save()}
 const snaps=(await listing('listSnapshots','snapshot',{showunique:true})).filter(own).reverse()
 for(const s of snaps){await job('deleteSnapshot',{id:s.id});actions.push({command:'deleteSnapshot',id:s.id,name:s.name});save()}
 for(const [cmd,key,extra] of [['listVirtualMachines','virtualmachine',{}],['listVolumes','volume',{}],['listTemplates','template',{templatefilter:'all'}],['listSnapshots','snapshot',{}]]){const left=(await listing(cmd,key,extra)).filter(own);if(left.length)throw Error('Fixture left: '+cmd+' '+JSON.stringify(left));actions.push({check:cmd,active:0});save()}
 console.log(cluster,'normal API cleanup PASS',actions.length)
}finally{await logout()}
