// Licensed to the Apache Software Foundation (ASF) under one or more
// contributor license agreements. See the NOTICE file distributed with this
// work for additional information regarding copyright ownership. The ASF
// licenses this file to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance with the License.
// You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0 .
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
// WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
// License for the specific language governing permissions and limitations.
import fs from 'node:fs'
import {api,job,login,logout,root,cluster,registryFile} from './api.mjs'
const reg=JSON.parse(fs.readFileSync(registryFile)),source=reg.volumes.find(x=>x.scenario==='negative'),s0=reg.snapshots.find(x=>x.scenario==='native')
const anonymous=await fetch(`http://10.10.${cluster}.10:8080/client/api`,{method:'POST',body:new URLSearchParams({command:'extractSnapshot',response:'json',id:s0.id,zoneid:s0.zoneid})});const denied=await anonymous.json();if(!Object.values(denied)[0]?.errorcode)throw Error('Unauthenticated export accepted')
fs.writeFileSync(`${root}/${cluster}-unauthenticated.json`,JSON.stringify({http:anonymous.status,response:denied},null,2))
await login();let snapshot,result
try {
 snapshot=(await job('createSnapshot',{volumeid:source.id,name:'다운로드 검증',asyncbackup:false})).snapshot
 if(snapshot.volumeid!==source.id)throw Error('Unexpected source')
 reg.snapshots.push({...snapshot,scenario:'korean'});fs.writeFileSync(registryFile,JSON.stringify(reg,null,2))
 result=await job('extractSnapshot',{id:snapshot.id,zoneid:snapshot.zoneid})
 const d=result.snapshot
 if(!/^[a-f0-9-]{36}\.qcow2$/.test(d.filename)||d.name!=='다운로드 검증')throw Error('Korean name fallback invalid')
 const get=await fetch(d.url,{headers:{Range:'bytes=0-7'}});const bytes=Buffer.from(await get.arrayBuffer());if(get.status!==206||bytes.subarray(0,4).toString('hex')!=='514649fb')throw Error('Korean download invalid')
 result.http=get.status;result.magic=bytes.toString('hex');fs.writeFileSync(`${root}/${cluster}-korean-name.json`,JSON.stringify(result,null,2));console.log(cluster,'Korean filename and anonymous rejection PASS')
}finally {
 if(snapshot){await job('deleteSnapshot',{id:snapshot.id});if(result?.snapshot?.url){const deleted=await fetch(result.snapshot.url);if(deleted.status!==404)throw Error('Deleted URL still accessible');result.afterDeleteHttp=deleted.status;fs.writeFileSync(`${root}/${cluster}-korean-name.json`,JSON.stringify(result,null,2))}}
 await logout()
}
