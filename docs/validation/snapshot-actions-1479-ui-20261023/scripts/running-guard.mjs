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
import {job,login,logout,registryFile,root,cluster} from './api.mjs'
await login()
try{
 const r=JSON.parse(fs.readFileSync(registryFile));const s=r.snapshots.find(x=>x.scenario==='source');let rejection
 try{await job('revertSnapshot',{id:s.id})}catch(error){rejection=error.message}
 if(!/VM_MUST_BE_STOPPED|must be stopped|VM state/i.test(rejection||''))throw new Error('Expected stopped-state rejection: '+rejection)
 fs.writeFileSync(`${root}/${cluster}-running-api-rejection.json`,JSON.stringify({snapshot:s.id,status:'PASS',rejection:JSON.parse(rejection)},null,2));console.log(cluster,'Running-source API revert rejected')
}finally{await logout()}
