import fs from 'node:fs'
import {api,job,login,logout,root,prefix,cluster,registryFile} from './api.mjs'
const r=JSON.parse(fs.readFileSync(registryFile)),file=`${root}/${cluster}-cleanup.json`,actions=fs.existsSync(file)?JSON.parse(fs.readFileSync(file)):[]
const save=()=>fs.writeFileSync(file,JSON.stringify(actions,null,2))
async function remove(command,args){if(actions.some(x=>x.command===command&&x.id===args.id))return;await job(command,args);actions.push({command,id:args.id,complete:true});save()}
await login();try{
 for(const t of r.targets){if(!t.name.startsWith(prefix))throw Error('Foreign target');const tags=(await api('listTags',{resourceid:t.id,resourcetype:t.type,listall:true,page:1,pagesize:100,...(t.projectid?{projectid:t.projectid}:{})})).tag||[];for(const tag of tags)await job('deleteTags',{resourceids:t.id,resourcetype:t.type,'tags[0].key':tag.key,'tags[0].value':tag.value,...(t.projectid?{projectid:t.projectid}:{})});if((await api('listTags',{resourceid:t.id,resourcetype:t.type,listall:true,...(t.projectid?{projectid:t.projectid}:{})})).count)throw Error('Tags remain')}
 for(const s of [...r.snapshots].reverse()){if(!s.name.startsWith(prefix))throw Error('Foreign snapshot');if(actions.some(x=>x.command==='deleteSnapshot'&&x.id===s.id))continue;const plan=await job('deleteSnapshot',{id:s.id,dryrun:true});if(!plan.scopehash)throw Error('No scope receipt');await remove('deleteSnapshot',{id:s.id,expectedscopehash:plan.scopehash})}
 for(const v of r.vms){if(!v.name.startsWith(prefix)||v.state!=='Stopped')throw Error('Foreign VM');await remove('destroyVirtualMachine',{id:v.id,expunge:true})}
 for(const v of r.volumes){if(!v.name.startsWith(prefix)||v.virtualmachineid)throw Error('Foreign volume');await remove('deleteVolume',{id:v.id})}
 await remove('deleteProject',{id:r.project.id})
 for(const a of r.accounts){if(!a.name.startsWith(prefix))throw Error('Foreign account');await remove('deleteAccount',{id:a.id})}
 await remove('deleteDomain',{id:r.domain.id});console.log(cluster,'normal API cleanup PASS',actions.length)
}finally{save();await logout()}
