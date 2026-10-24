import fs from 'node:fs'
import{api,job,login,logout,root,prefix,cluster,registryFile}from './api.mjs'
const reg=JSON.parse(fs.readFileSync(registryFile)),file=`${root}/${cluster}-cleanup.json`,actions=fs.existsSync(file)?JSON.parse(fs.readFileSync(file)):[]
const save=()=>fs.writeFileSync(file,JSON.stringify(actions,null,2));async function remove(command,args){if(actions.some(x=>x.command===command&&x.id===args.id))return;await job(command,args);actions.push({command,id:args.id,complete:true});save()}
await login();try{
 for(const s of reg.snapshots){if(!s.name.startsWith(prefix))throw Error('Foreign snapshot');if(actions.some(x=>x.command==='deleteSnapshot'&&x.id===s.id))continue;const plan=await job('deleteSnapshot',{id:s.id,dryrun:true});await remove('deleteSnapshot',{id:s.id,expectedscopehash:plan.scopehash})}
 for(const v of reg.volumes){if(!v.name.startsWith(prefix)||v.virtualmachineid)throw Error('Foreign or attached volume');if(v.sourceDeleted){if(!actions.some(x=>x.command==='deleteVolume'&&x.id===v.id)){actions.push({command:'deleteVolume',id:v.id,complete:true,completedBeforeSearch:true});save()}}else await remove('deleteVolume',{id:v.id})}
 await remove('deleteProject',{id:reg.project.id})
 for(const a of reg.accounts){if(!a.name.startsWith(prefix))throw Error('Foreign account');await remove('deleteAccount',{id:a.id})}
 await remove('deleteDomain',{id:reg.child.id});await remove('deleteDomain',{id:reg.domain.id});console.log(cluster,'cleanup PASS',actions.length)
}finally{save();await logout()}
