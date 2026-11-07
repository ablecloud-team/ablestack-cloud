// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements. See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership. The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License. You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied. See the License for the
// specific language governing permissions and limitations
// under the License.

const routes = { User: '/vm/', DomainRouter: '/router/', ConsoleProxy: '/systemvm/', SecondaryStorageVm: '/systemvm/' }
const text = value => typeof value === 'string' && !/^(undefined|null|nan|none)$/i.test(value.trim()) ? value.trim() : ''

// IP responses already carry the target identity; resolving a cell must not issue lookups.
export function networkIpVmReference (record = {}) {
  const id = text(record.virtualmachineid)
  const name = text(record.virtualmachinename)
  const displayName = text(record.virtualmachinedisplayname)
  const label = displayName || name || id || '—'
  if (!/^[a-z0-9][a-z0-9_-]*$/i.test(id)) return { label, path: '' }

  let type = record.virtualmachinetype
  // Legacy Shared user-NIC responses omit type, but include both names and explicit flags.
  // Do not apply this compatibility rule to unknown/blank types or incomplete identities.
  if (!Object.prototype.hasOwnProperty.call(record, 'virtualmachinetype') &&
      record.forvirtualnetwork === false && record.issystem === false &&
      record.forrouter !== true && name && displayName) type = 'User'
  const route = Object.prototype.hasOwnProperty.call(routes, type) ? routes[type] : ''
  return { label, path: route ? route + encodeURIComponent(id) : '' }
}
