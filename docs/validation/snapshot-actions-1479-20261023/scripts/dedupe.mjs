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
import crypto from 'node:crypto'
import { api, job, login, logout, root, cluster, registryFile, prefix } from './api.mjs'
const suffix=process.argv[4] || ''
const reg=JSON.parse(fs.readFileSync(registryFile)), results=[]
await login()
try {
 for (const command of ['createSnapshot','createVolume','createTemplate']) {
  const customjobid=crypto.randomUUID(), snapshot=reg.snapshots.find(x=>x.scenario==='orphan-source'), volume=reg.volumes.find(x=>x.scenario==='tenant')
  const inventory=JSON.parse(fs.readFileSync(`${root}/${cluster}-existing-before.json`)), template=inventory.listTemplates.template.find(x=>x.name==='nginx-lb-test.qcow2')
  const args={customjobid,name:`${prefix}-dedupe-${command}${suffix}`,...(command==='createSnapshot'?{volumeid:volume.id,asyncbackup:false}:{snapshotid:snapshot.id,...(command==='createTemplate'?{displaytext:`${prefix}-dedupe-template`,ostypeid:template.ostypeid}:{zoneid:volume.zoneid})})}
  const responses=await Promise.all([api(command,args),api(command,args)])
  if (responses.some(x=>x.jobid!==customjobid)) throw new Error('Concurrent request did not reuse custom job UUID')
  let result
  for(let i=0;i<120;i++) {
   result=await api('queryAsyncJobResult',{jobid:customjobid})
   if(result.jobstatus===1)break
   if(result.jobstatus===2)throw new Error(JSON.stringify(result))
   await new Promise(resolve=>setTimeout(resolve,1000))
  }
  if(result.jobstatus!==1)throw new Error('Unknown concurrent result')
  const repeated=await api(command,args)
  if(repeated.jobid!==customjobid)throw new Error('Terminal replay created another job')
  let mismatch
  try {await api(command,{...args,name:args.name+'-changed'})}catch(error){mismatch=JSON.parse(error.message)}
  if(!mismatch || !/different command|specification/.test(mismatch.errortext))throw new Error('Changed request was not rejected')
  const resources=await api(command==='createVolume'?'listVolumes':command==='createSnapshot'?'listSnapshots':'listTemplates',{keyword:args.name,listall:true,page:1,pagesize:500,...(command==='createTemplate'?{templatefilter:'all'}:{})})
  const rows=resources[command==='createVolume'?'volume':command==='createSnapshot'?'snapshot':'template']?.filter(x=>x.name===args.name) || []
  if(rows.length!==1)throw new Error('Duplicate resource allocation: '+JSON.stringify(rows))
  const bucket=command==='createVolume'?'volumes':command==='createSnapshot'?'snapshots':'templates'
  reg[bucket].push({...rows[0],scenario:'dedupe-'+command});fs.writeFileSync(registryFile,JSON.stringify(reg,null,2))
  results.push({command,customjobid,responses,repeated,result,mismatch,resources:rows,status:'PASS'})
 }
 fs.writeFileSync(`${root}/${cluster}-dedupe-api.json`,JSON.stringify(results,null,2))
 console.log(cluster,'concurrent and terminal replay PASS',results.length)
} finally {await logout()}
