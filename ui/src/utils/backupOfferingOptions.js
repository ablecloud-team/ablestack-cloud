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

export function fixedBackupOfferingExternalId (provider) {
  return {
    'ablestack-netbackup': 'netbackup',
    'ablestack-veeam': 'veeam'
  }[(provider || '').toLowerCase()]
}

function externalIdKey (provider, value) {
  const id = String(value)
  return fixedBackupOfferingExternalId(provider) ? id.toLowerCase() : id
}

// An offering belongs to a Zone. Reusing its external ID in another Zone is valid.
export async function listZoneBackupOfferings (getAPI, zoneId) {
  const offerings = []
  const ids = new Set()
  for (let page = 1; ; page++) {
    const response = await getAPI('listBackupOfferings', { zoneid: zoneId, listall: true, page, pagesize: 100 })
    const body = response.listbackupofferingsresponse
    if (!body || body.errorcode) throw new Error('Unable to read registered backup offerings')
    const items = body.backupoffering || []
    if (!Array.isArray(items)) throw new Error('Invalid backup offering inventory')
    for (const item of items) {
      if (!item.id || ids.has(item.id)) throw new Error('Repeated or unidentified backup offering')
      ids.add(item.id)
      offerings.push(item)
    }
    const count = body.count == null ? NaN : Number(body.count)
    if (Number.isFinite(count) && count > offerings.length && !items.length) throw new Error('Incomplete backup offering inventory')
    if (!items.length || (Number.isFinite(count) ? offerings.length >= count : items.length < 100)) {
      return offerings.filter(offering => !(offering.zoneid || offering.zoneId) || (offering.zoneid || offering.zoneId) === zoneId)
    }
  }
}

export async function loadBackupOfferingOptions (getAPI, zoneId, provider) {
  const [response, registered] = await Promise.all([
    getAPI('listBackupProviderOfferings', { zoneid: zoneId, provider }),
    listZoneBackupOfferings(getAPI, zoneId)
  ])
  const body = response.listbackupproviderofferingsresponse
  if (!body || body.errorcode) throw new Error('Unable to read external backup offerings')
  const external = body.backupoffering || []
  if (!Array.isArray(external)) throw new Error('Invalid external backup offerings')
  const providerName = (provider || '').toLowerCase()
  const fixedId = fixedBackupOfferingExternalId(provider)
  const singleOffering = fixedId || providerName === 'ablestack-commvault'
  const hasExistingSingleProviderOffering = !!singleOffering && registered.some(offering => (offering.provider || '').toLowerCase() === providerName)
  // Backend uniqueness is external ID + Zone, including IDs used by another provider.
  const used = new Set(registered.filter(offering => offering.externalid != null).map(offering => externalIdKey(provider, offering.externalid)))
  const options = hasExistingSingleProviderOffering ? [] : external.filter(offering => {
    if (offering.externalid == null) return false
    const id = externalIdKey(provider, offering.externalid)
    if ((fixedId && id !== fixedId) || used.has(id)) return false
    used.add(id)
    return true
  })
  const hasProviderOfferings = external.some(offering => offering.externalid != null && (!fixedId || externalIdKey(provider, offering.externalid) === fixedId))
  return { options, hasExistingSingleProviderOffering, hasProviderOfferings }
}
