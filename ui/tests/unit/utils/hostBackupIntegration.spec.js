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
import { hostBackupIntegrationProviders, loadHostBackupIntegrationProviders } from '@/utils/hostBackupIntegration'

const enabled = 'backup.framework.enabled'
const provider = 'backup.framework.provider.plugin'
const response = values => ({ listconfigurationsresponse: { configuration: Object.entries(values).map(([name, value]) => ({ name, value })), count: Object.keys(values).length } })
const api = (global, zone) => jest.fn((command, params) => Promise.resolve(response(params.zoneid ? zone : global)))

test('Global-only configuration is inherited without writing any Zone values', async () => {
  const read = api({ [enabled]: 'true', [provider]: 'ablestack-netbackup,ablestack-veeam' }, {})
  expect(await loadHostBackupIntegrationProviders(read, 'zone-1')).toEqual(['ablestack-netbackup', 'ablestack-veeam'])
  expect(read).toHaveBeenCalledTimes(2)
  expect(read).toHaveBeenCalledWith('listConfigurations', { name: 'backup.framework.', zoneid: 'zone-1', pagesize: -1 }, { preserveOnFailure: true })
})

test('Zone provider overrides replace the Global provider list', async () => {
  const read = api({ [enabled]: 'true', [provider]: 'ablestack-netbackup' }, { [enabled]: 'true', [provider]: 'ablestack-veeam' })
  expect(await loadHostBackupIntegrationProviders(read, 'zone-1')).toEqual(['ablestack-veeam'])
})

test.each([['false', 'true'], ['true', 'false'], ['false', 'false']])('framework Global %s and Zone %s hide all host integrations', async (global, zone) => {
  expect(await loadHostBackupIntegrationProviders(api({ [enabled]: global, [provider]: 'ablestack-netbackup' }, { [enabled]: zone }), 'zone-1')).toEqual([])
})

test('explicitly empty Zone providers do not fall back to Global', async () => {
  expect(await loadHostBackupIntegrationProviders(api({ [enabled]: 'true', [provider]: 'ablestack-netbackup' }, { [provider]: '' }), 'zone-1')).toEqual([])
})

test('provider names use exact comma-separated tokens with whitespace and case normalization', async () => {
  const read = api({ [enabled]: 'true', [provider]: 'veeam,other-ablestack-netbackup, ABLESTACK-VEEAM , ablestack-netbackup,ablestack-netbackup' }, {})
  expect(await loadHostBackupIntegrationProviders(read, 'zone-1')).toEqual(['ablestack-netbackup', 'ablestack-veeam'])
})

test('NAS, native NetBackup and native Veeam do not enable these host integration screens', async () => {
  expect(await loadHostBackupIntegrationProviders(api({ [enabled]: 'true', [provider]: 'ablestack-nas,netbackup,veeam' }, {}), 'zone-1')).toEqual([])
})

test.each([{}, { listconfigurationsresponse: { errorcode: 530 } }, { listconfigurationsresponse: { configuration: [], count: 2 } }])('an invalid or incomplete configuration response %p is rejected', async invalid => {
  const read = jest.fn().mockResolvedValue(invalid)
  await expect(loadHostBackupIntegrationProviders(read, 'zone-1')).rejects.toThrow()
})

test('a failed Global/Zone request is not interpreted as successful activation', async () => {
  await expect(loadHostBackupIntegrationProviders(jest.fn().mockRejectedValue(new Error('offline')), 'zone-1')).rejects.toThrow('offline')
})

test('NetBackup requires its read API permission while Veeam remains independent', () => {
  const host = { hypervisor: 'KVM', type: 'Routing', backupintegrationproviders: ['ablestack-netbackup', 'ablestack-veeam'] }
  expect(hostBackupIntegrationProviders(host, {})).toEqual(['ablestack-veeam'])
  expect(hostBackupIntegrationProviders({ ...host, hypervisor: 'VMware' }, { getNetBackupHostConfiguration: {} })).toEqual([])
  expect(hostBackupIntegrationProviders({ ...host, type: 'ConsoleProxy' }, { getNetBackupHostConfiguration: {} })).toEqual([])
})
