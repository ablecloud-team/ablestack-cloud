// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

export function toApiMetadata (apiList = []) {
  return Object.fromEntries(apiList.map(api => [api.name, {
    params: api.params,
    isasync: api.isasync,
    since: api.since,
    description: api.description,
    // API documentation and event exports use the response schema in memory.
    response: api.response
  }]))
}

export function toCachedApiMetadata (apis) {
  return Object.fromEntries(Object.entries(apis).map(([name, api]) => [name, {
    params: api.params,
    isasync: api.isasync,
    since: api.since,
    description: api.description
  }]))
}
