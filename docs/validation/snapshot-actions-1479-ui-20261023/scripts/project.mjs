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
import {job,login,logout,registryFile,prefix,cluster} from './api.mjs'
await login()
try{const r=JSON.parse(fs.readFileSync(registryFile));r.project=(await job('createProject',{name:prefix+'-project',displaytext:prefix+'-project'})).project;fs.writeFileSync(registryFile,JSON.stringify(r,null,2));console.log(cluster,'project created',r.project.id)}finally{await logout()}
