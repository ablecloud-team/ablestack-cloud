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

import { getAPI } from '@/api'

export function hasDiscoveryApi (apis, name) {
  return !!apis && Object.prototype.hasOwnProperty.call(apis, name)
}

// Optional discovery must never fail authentication or leave a rejected promise.
export async function discoverOptional (apis, name, params = {}) {
  if (!hasDiscoveryApi(apis, name)) return undefined
  try {
    return await getAPI(name, params, { optionalDiscovery: true })
  } catch (_) {
    return undefined
  }
}
