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

export function hostBackupIntegrationProviders (host, apis = {}) {
  if (host?.hypervisor !== 'KVM' || (host.type && host.type !== 'Routing') || !Array.isArray(host.backupintegrationproviders)) return []
  const configured = new Set(host.backupintegrationproviders.filter(value => typeof value === 'string').map(value => value.trim().toLowerCase()))
  return ['ablestack-netbackup', 'ablestack-veeam'].filter(provider => configured.has(provider) &&
    (provider !== 'ablestack-netbackup' || !!apis.getNetBackupHostConfiguration))
}

export async function loadHostBackupIntegrationProviders (getAPI, zoneId) {
  const [globalResponse, zoneResponse] = await Promise.all([
    getAPI('listConfigurations', { name: 'backup.framework.', pagesize: -1 }, { preserveOnFailure: true }),
    getAPI('listConfigurations', { name: 'backup.framework.', zoneid: zoneId, pagesize: -1 }, { preserveOnFailure: true })
  ])
  const settings = response => {
    const body = response?.listconfigurationsresponse
    const rows = body?.configuration
    if (!body || body.errorcode || !Array.isArray(rows) || Number(body.count) > rows.length) throw new Error('Backup framework settings are unavailable')
    return new Map(rows.map(row => [row.name, row.value]))
  }
  const global = settings(globalResponse)
  const zone = settings(zoneResponse)
  const effective = key => zone.get(key) ?? global.get(key)
  const enabled = value => String(value).trim().toLowerCase() === 'true'
  if (!enabled(global.get('backup.framework.enabled')) || !enabled(effective('backup.framework.enabled'))) return []
  const configured = new Set(String(effective('backup.framework.provider.plugin') ?? '').split(',').map(value => value.trim().toLowerCase()))
  return ['ablestack-netbackup', 'ablestack-veeam'].filter(provider => configured.has(provider))
}
