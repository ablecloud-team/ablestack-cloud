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
import { api,login,logout,root,cluster,registryFile } from './api.mjs'
const scenario=process.argv[3]||'native',tag=process.argv[4]||scenario
const reg=JSON.parse(fs.readFileSync(registryFile)),snapshot=reg.snapshots.find(x=>x.scenario===scenario)
await login()
try {
 const started=Date.now(),accepted=await api('extractSnapshot',{id:snapshot.id,zoneid:snapshot.zoneid})
 let job
 for(let i=0;i<180;i++){
  job=await api('queryAsyncJobResult',{jobid:accepted.jobid})
  if(job.jobstatus!==0)break
  await new Promise(r=>setTimeout(r,1000))
 }
 const result={snapshot:snapshot.id,scenario,accepted,job,elapsedMs:Date.now()-started}
 fs.writeFileSync(`${root}/${cluster}-extract-${tag}.json`,JSON.stringify(result,null,2))
 console.log(cluster,tag,'status',job.jobstatus,JSON.stringify(job.jobresult))
 if(job.jobstatus===1){
  const d=job.jobresult.extractsnapshotresponse?.snapshot||job.jobresult.snapshot
  if(!d||!['RAW','QCOW2'].includes(d.format)||!(d.size>0)||!(d.physicalsize>0)||!d.url.endsWith('.'+d.format.toLowerCase())||!d.url.endsWith('/'+d.filename))throw Error('Metadata mismatch')
 }
}finally{await logout()}
