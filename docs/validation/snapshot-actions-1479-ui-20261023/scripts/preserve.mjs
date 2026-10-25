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
import {createHash} from 'node:crypto'
import {api,login,logout,root,prefix,cluster} from './api.mjs'
const before=JSON.parse(fs.readFileSync(`${root}/${cluster}-existing-before.json`))
const map=[['listVolumes','volume'],['listSnapshots','snapshot'],['listVirtualMachines','virtualmachine'],['listTemplates','template']]
const project=x=>Object.fromEntries(['id','name','displayname','state','account','domainid','projectid','volumeid','virtualmachineid','zoneid','format','size','isready'].filter(k=>x[k]!==undefined).map(k=>[k,x[k]]))
const hash=x=>createHash('sha256').update(JSON.stringify(x)).digest('hex')
await login()
try{
 const evidence={cluster,fixtureActive:[],preserved:[]}
 for(const [command,key] of map){
  const args={listall:true,page:1,pagesize:500,...(command==='listSnapshots'?{showunique:false}:{}),...(command==='listTemplates'?{templatefilter:'executable'}:{})}
  const after=await api(command,args)
  const a=(before[command][key]||[]).filter(x=>!x.name?.startsWith(prefix)),b=after[key]||[]
  for(const x of a){const current=b.find(y=>y.id===x.id&&y.zoneid===x.zoneid);if(!current||JSON.stringify(project(x))!==JSON.stringify(project(current)))throw new Error('Operational projection changed: '+command+' '+x.id)}
  evidence.preserved.push({command,count:a.length,sha256:hash(a.map(project).sort((x,y)=>JSON.stringify(x).localeCompare(JSON.stringify(y)))),unchanged:true})
  evidence.fixtureActive.push({command,count:b.filter(x=>x.name?.startsWith(prefix)).length});if(evidence.fixtureActive.at(-1).count)throw new Error('Active fixture: '+command)
 }
 evidence.status='PASS';fs.writeFileSync(`${root}/${cluster}-preservation.json`,JSON.stringify(evidence,null,2));console.log(cluster,'operational projections preserved and fixture API=0')
}finally{await logout()}
