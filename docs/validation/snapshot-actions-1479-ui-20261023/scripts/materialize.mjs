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
import {api,job,login,logout,root,registryFile,cluster} from './api.mjs'
await login()
try {
 const r=JSON.parse(fs.readFileSync(registryFile))
 for(const v of r.volumes){await job('attachVolume',{id:v.id,virtualmachineid:r.vm.id});await job('detachVolume',{id:v.id})}
 console.log(cluster,'materialized 3 detached fixture volumes')
}finally{await logout()}
