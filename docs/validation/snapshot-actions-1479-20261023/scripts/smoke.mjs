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
import { api, login, logout, root, cluster } from './api.mjs'
for(let attempt=0;;attempt++) { try { await login(); break } catch(error) { if(attempt>=17)throw error; await new Promise(resolve=>setTimeout(resolve,2000)) } }
try {
 let hosts
 for(let attempt=0;;attempt++) {
  hosts=await api('listHosts',{listall:true})
  if(hosts.host?.length===5 && hosts.host.every(x=>x.state==='Up'))break
  if(attempt>=17)throw new Error('Wait for all three hosts, SSVM and console Agent to reconnect')
  await new Promise(resolve=>setTimeout(resolve,2000))
 }
 const results=[{command:'listHosts',states:hosts.host.map(x=>({type:x.type,state:x.state,resource:x.resourcestate})),status:'PASS'}]
 for(const command of ['listVolumes','listSnapshots','listVirtualMachines','listTemplates','listIsos','listSSHKeyPairs']) {
  const data=await api(command,{listall:true,page:1,pagesize:500,...(command==='listTemplates'||command==='listIsos'?{templatefilter:'all'}:{})})
  results.push({command,count:data.count||0,status:'PASS'})
 }
 const filtered=await api('listVirtualMachines',{keypair:'SNAP1479-nonexistent-keypair',listall:true})
 if(filtered.count || filtered.virtualmachine?.length)throw new Error('SSH-key filter did not fail empty')
 results.push({command:'listVirtualMachines keypair filter',count:0,status:'PASS'})
 fs.writeFileSync(`${root}/${cluster}-runtime-smoke.json`,JSON.stringify(results,null,2))
 console.log(cluster,'runtime smoke PASS',results.length)
}finally{await logout()}
