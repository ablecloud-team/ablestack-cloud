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
import { listZoneBackupOfferings, loadBackupOfferingOptions } from '@/utils/backupOfferingOptions'

const inventory = (rows, count = rows.length) => ({ listbackupofferingsresponse: { backupoffering: rows, count } })
const external = rows => ({ listbackupproviderofferingsresponse: { backupoffering: rows } })
const option = externalid => ({ id: 'provider-' + externalid, externalid, name: externalid })
const registered = (externalid, provider = 'ablestack-nas', zoneid = 'zone-a') => ({ id: 'mold-' + externalid, externalid, provider, zoneid })
function apiFor (externalRows, registeredRows) {
  return jest.fn((command) => Promise.resolve(command === 'listBackupOfferings' ? inventory(registeredRows) : external(externalRows)))
}

test('NAS excludes both the clone source and other registered repositories in the destination Zone', async () => {
  const api = apiFor(['source', 'used', 'available'].map(option), ['source', 'used'].map(id => registered(id)))
  const result = await loadBackupOfferingOptions(api, 'zone-a', 'ablestack-nas')
  expect(result.options.map(row => row.externalid)).toEqual(['available'])
  expect(api).toHaveBeenCalledWith('listBackupOfferings', { zoneid: 'zone-a', listall: true, page: 1, pagesize: 100 })
})

test('IDs registered only in another Zone remain available', async () => {
  const api = apiFor([option('netbackup')], [registered('netbackup', 'ablestack-netbackup', 'zone-b')])
  const result = await loadBackupOfferingOptions(api, 'zone-a', 'ablestack-netbackup')
  expect(result.options).toEqual([option('netbackup')])
  expect(result.hasExistingSingleProviderOffering).toBe(false)
})

test('every registration page is read before offering an ID for import', async () => {
  const first = Array.from({ length: 100 }, (_, index) => registered('id-' + index))
  const api = jest.fn((command, params) => Promise.resolve(command === 'listBackupOfferings'
    ? inventory(params.page === 1 ? first : [registered('last-page')], 101)
    : external([option('last-page'), option('free')])))
  expect((await loadBackupOfferingOptions(api, 'zone-a', 'ablestack-nas')).options).toEqual([option('free')])
  expect(api).toHaveBeenCalledWith('listBackupOfferings', expect.objectContaining({ zoneid: 'zone-a', page: 2 }))
})

test.each(['ablestack-netbackup', 'ablestack-veeam', 'ablestack-commvault'])('existing %s offering prevents a second offering in the same Zone', async provider => {
  const api = apiFor([option('another-id')], [registered('existing-id', provider)])
  const result = await loadBackupOfferingOptions(api, 'zone-a', provider)
  expect(result.options).toEqual([])
  expect(result.hasExistingSingleProviderOffering).toBe(true)
})

test.each([['ablestack-netbackup', 'netbackup'], ['ablestack-veeam', 'veeam']])('%s exposes only its canonical external ID', async (provider, id) => {
  const api = apiFor([option('host-policy'), option(id)], [])
  expect((await loadBackupOfferingOptions(api, 'zone-a', provider)).options).toEqual([option(id)])
  const legacy = apiFor([option(id)], [registered(id.toUpperCase(), provider)])
  expect((await loadBackupOfferingOptions(legacy, 'zone-a', provider)).options).toEqual([])
})

test('native Veeam still lists multiple external jobs and Commvault still lists Plans', async () => {
  const api = apiFor([option('plan-or-job-1'), option('plan-or-job-2')], [])
  for (const provider of ['veeam', 'ablestack-commvault']) {
    expect((await loadBackupOfferingOptions(api, 'zone-a', provider)).options).toHaveLength(2)
  }
})

test('same-Zone external IDs used by another provider are excluded, while NAS path case is preserved', async () => {
  const api = apiFor([option('/Backup/A'), option('/backup/a'), option('shared-id')], [registered('/Backup/A'), registered('shared-id', 'another-provider')])
  expect((await loadBackupOfferingOptions(api, 'zone-a', 'ablestack-nas')).options).toEqual([option('/backup/a')])
})

test.each([
  {},
  { listbackupofferingsresponse: { errorcode: 530 } },
  { listbackupofferingsresponse: { backupoffering: {} } },
  inventory([], 1)
])('an invalid or incomplete registration response is not treated as an empty Zone: %p', async response => {
  await expect(listZoneBackupOfferings(jest.fn().mockResolvedValue(response), 'zone-a')).rejects.toThrow()
})

test('a repeated page fails instead of publishing a partial inventory', async () => {
  const api = jest.fn().mockResolvedValue(inventory([registered('repeated')], 2))
  await expect(listZoneBackupOfferings(api, 'zone-a')).rejects.toThrow('Repeated')
  expect(api).toHaveBeenCalledTimes(2)
})

test('a later page failure is propagated even if the provider listing succeeded', async () => {
  const api = jest.fn((command, params) => {
    if (command !== 'listBackupOfferings') return Promise.resolve(external([option('free')]))
    if (params.page === 1) return Promise.resolve(inventory([registered('first')], 2))
    return Promise.reject(new Error('offline'))
  })
  await expect(loadBackupOfferingOptions(api, 'zone-a', 'ablestack-nas')).rejects.toThrow('offline')
})
