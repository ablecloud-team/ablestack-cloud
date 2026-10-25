import fs from 'node:fs'
import {api,login,logout,root,cluster,registryFile} from './api.mjs'
const r=JSON.parse(fs.readFileSync(registryFile)),owner=r.accounts.find(x=>x.scenario==='owner'),own=r.snapshots.find(x=>x.scenario==='owner'),other=r.snapshots.find(x=>x.scenario==='other')
process.env.MOLD_TEST_USER=owner.name;process.env.MOLD_TEST_DOMAIN='/'+r.domain.name
await login()
try {
 const ownList=await api('listSnapshots',{id:own.id,page:1,pagesize:20});if(ownList.snapshot?.[0]?.id!==own.id)throw Error('Own snapshot unavailable')
 const hidden=await api('listSnapshots',{id:other.id,page:1,pagesize:20});if(hidden.snapshot?.length)throw Error('Foreign snapshot disclosed')
 const rejected=[]
 for(const [command,args] of [['listSnapshotCopyTargets',{id:other.id,sourcezoneid:other.zoneid}],['deleteSnapshot',{id:other.id,dryrun:true}],['archiveSnapshot',{id:other.id,dryrun:true}]]){
  try{await api(command,args);throw Error('Expected access rejection: '+command)}catch(e){if(e.message.startsWith('Expected'))throw e;const d=JSON.parse(e.message);if(![431,435,436,530,531].includes(d.errorcode))throw e;rejected.push({command,errorcode:d.errorcode,errortext:d.errortext})}
 }
 fs.writeFileSync(root+'/'+cluster+'-dialog-acl.json',JSON.stringify({own:ownList,foreignHidden:true,rejected,status:'PASS'},null,2));console.log(cluster,'owner scope read and three foreign operation rejections PASS')
}finally{await logout()}
