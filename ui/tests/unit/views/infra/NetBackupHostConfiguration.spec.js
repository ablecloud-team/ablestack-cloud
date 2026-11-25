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

import { shallowMount } from '@vue/test-utils'
import { getAPI, postAPI } from '@/api'
import NetBackupHostConfiguration from '@/views/infra/backup/NetBackupHostConfiguration.vue'
import ko from '../../../../public/locales/ko_KR.json'

jest.mock('@/api', () => ({ getAPI: jest.fn(), postAPI: jest.fn() }))

const base = {
  state: 'NOT_CONFIGURED',
  hoststate: 'Up',
  frameworkenabled: true,
  frameworkglobalenabled: true,
  frameworkzoneenabled: true,
  providerenabled: true,
  stagingenabled: true,
  netbackupurl: 'https://netbackup.test/netbackup',
  credentialsconfigured: true,
  moldcredentialsavailable: true,
  vmschedulebackup: true,
  stagingpath: '/stage/ablestack-netbackup',
  incrementalbackup: false,
  backupchainsize: 8,
  settingsource: {
    'backup.framework.enabled': 'Global',
    'backup.framework.provider.plugin': 'Global',
    'backup.plugin.netbackup.url': 'Global',
    'backup.plugin.netbackup.apikey': 'Global',
    'kvm.incremental.backup': 'Global'
  },
  virtualmachine: []
}
const response = info => ({ getnetbackuphostconfigurationresponse: { netbackuphostconfiguration: { ...base, ...info } } })
const flush = async () => { for (let i = 0; i < 8; i++) await Promise.resolve() }

async function mount (info = {}, options = {}) {
  getAPI.mockResolvedValue(response(info))
  const wrapper = shallowMount(NetBackupHostConfiguration, {
    props: { resource: { id: 'host-1', zoneid: 'zone-1', clusterid: 'cluster-1' } },
    global: {
      stubs: {
        ASteps: { template: '<div><slot /></div>' },
        AStep: { props: ['title'], template: '<span>{{ title }}</span>' },
        ADescriptions: { template: '<div><slot /></div>' },
        ADescriptionsItem: { props: ['label'], template: '<div>{{ label }}<slot /></div>' },
        ACard: { props: ['title'], template: '<div>{{ title }}<slot /></div>' },
        ASpin: { template: '<div><slot /></div>' },
        ATypographyParagraph: { template: '<p><slot /></p>' },
        ATag: { template: '<span><slot /></span>' },
        ATable: {
          props: ['columns', 'dataSource'],
          template: '<div><div v-for="record in dataSource" :key="record.key || record.id" :data-setting="record.key"><div v-for="column in columns" :key="column.key"><slot v-if="[\'setting\', \'source\'].includes(column.key)" name="bodyCell" :column="column" :record="record" /><span v-else>{{ record[column.dataIndex] }}</span></div></div></div>'
        },
        ASpace: { template: '<div><slot /></div>' },
        AAlert: { props: ['message', 'description', 'type'], template: '<div :data-type="type">{{ message }} {{ description }}</div>' },
        RouterLink: { props: ['to'], template: '<a :data-route="JSON.stringify(to)"><slot /></a>' }
      },
      mocks: {
        $store: { getters: { apis: { configureNetBackupHost: {} } } },
        $t: (key, params = {}) => (ko[key] || key).replace(/\{(\w+)\}/g, (match, name) => params[name] ?? match),
        $toLocaleDate: value => value,
        $route: { path: '/host/host-1' },
        $pollJob: options.pollJob || jest.fn(async ({ successMethod }) => successMethod())
      }
    }
  })
  await flush()
  return wrapper
}

beforeEach(() => { jest.clearAllMocks(); jest.useFakeTimers() })
afterEach(() => { jest.clearAllTimers(); jest.useRealTimers() })

test('shared configuration starts in Global and displays inherited sources with exact setting names', async () => {
  const w = await mount()
  expect(w.text()).toContain(ko['message.backup.integration.inheritance'])
  expect(w.text()).toContain('backup.thirdparty.staging.*')
  expect(w.text()).toContain('kvm.incremental.backup')
  expect(w.text()).toContain(ko['message.backup.integration.restart'])
  const endpoint = w.find('[data-setting="backup.plugin.netbackup.url"]')
  expect(endpoint.text()).toContain('https://netbackup.test/netbackup')
  expect(endpoint.text()).toContain(ko['label.backup.integration.source.global'])
  expect(endpoint.find('a').exists()).toBe(false)
  const settingsLink = w.findAll('a').find(a => a.text() === ko['label.backup.integration.shared.settings'])
  expect(JSON.parse(settingsLink.attributes('data-route'))).toEqual({ name: 'globalsetting' })
  w.unmount()
})

test('Zone and Cluster sources are informational and scope navigation is kept in separate links', async () => {
  const w = await mount({ settingsource: { ...base.settingsource, 'backup.plugin.netbackup.url': 'Zone', 'kvm.incremental.backup': 'Cluster' }, incrementalbackup: true })
  const endpoint = w.find('[data-setting="backup.plugin.netbackup.url"]')
  expect(endpoint.text()).toContain(ko['label.backup.integration.source.zone'])
  expect(endpoint.find('a').exists()).toBe(false)
  const zoneLink = w.findAll('a').find(a => a.text() === ko['label.backup.integration.zone.settings'])
  expect(JSON.parse(zoneLink.attributes('data-route'))).toEqual({ path: '/zone/zone-1', query: { tab: 'settings' } })
  const incremental = w.find('[data-setting="kvm.incremental.backup"]')
  expect(incremental.text()).toContain('true')
  expect(incremental.find('a').exists()).toBe(false)
  const clusterLink = w.findAll('a').find(a => a.text() === ko['label.backup.integration.cluster.settings'])
  expect(JSON.parse(clusterLink.attributes('data-route'))).toEqual({ path: '/cluster/cluster-1', query: { tab: 'settings' } })
  w.unmount()
})

test('initial disabled settings stay on shared setup and identify the Global framework master switch', async () => {
  const w = await mount({ state: 'PROVIDER_DISABLED', frameworkenabled: false, frameworkglobalenabled: false, frameworkzoneenabled: false, providerenabled: false, stagingenabled: false })
  expect(w.vm.step).toBe(0)
  expect(w.vm.canRun).toBe(false)
  expect(w.vm.settings.find(setting => setting.key === 'backup.framework.enabled').value).toBe('false')
  expect(w.find('[data-setting="backup.framework.enabled"]').text()).not.toContain('Global: false / Zone: false')
  expect(w.text()).toContain(ko['message.backup.integration.prerequisites'])
  w.unmount()
})

test.each([
  ['Global', true, true, 'true'],
  ['Zone', true, false, 'false'],
  ['Zone', false, true, 'true']
])('framework current value shows only the value from its %s source', async (scope, global, zone, expected) => {
  const w = await mount({ frameworkglobalenabled: global, frameworkzoneenabled: zone, settingsource: { ...base.settingsource, 'backup.framework.enabled': scope } })
  const row = w.find('[data-setting="backup.framework.enabled"]')
  expect(row.text()).toContain(ko['label.backup.integration.source.' + scope.toLowerCase()])
  expect(row.text()).not.toContain('Global:')
  expect(row.text()).not.toContain('Zone:')
  expect(w.vm.settings.find(setting => setting.key === 'backup.framework.enabled').value).toBe(expected)
  w.unmount()
})

test('a preflight receipt instructs applying Host setup before assigning offerings', async () => {
  const raw = 'Assign the NetBackup offering to the VMs to protect and register their Mold backup schedules'
  const w = await mount({ state: 'CHECKED', action: 'CHECK', step: 'FINISHED', policyname: 'seocho+01', moldurl: 'http://mold.test/client/api', message: raw })
  expect(w.vm.canApply).toBe(true)
  expect(w.vm.statusMessage).toBe(ko['message.backup.integration.checked'])
  expect(w.text()).toContain(ko['label.backup.integration.step.finished'])
  expect(w.text()).not.toContain(raw)
  expect(w.text()).not.toContain('FINISHED')
  w.unmount()
})

test('an applied configuration translates offering guidance, check names and results', async () => {
  const raw = 'Assign the NetBackup offering to the VMs to protect and register their Mold backup schedules'
  const w = await mount({ state: 'COMPLETED', action: 'APPLY', step: 'FINISHED', message: raw, check: [{ name: 'CLIENT', state: 'OK' }, { name: 'POLICY_PATH', state: 'MISMATCH' }] })
  expect(w.vm.statusMessage).toBe(ko['message.backup.integration.applied.schedule'])
  expect(w.text()).toContain(ko['label.backup.integration.step.client'] + ' · ' + ko['label.success'])
  expect(w.text()).toContain(ko['label.backup.integration.step.policy_path'] + ' · ' + ko['label.backup.integration.check.mismatch'])
  expect(w.text()).not.toContain(raw)
  w.unmount()
})

test('policy schedule guidance does not ask for Mold VM schedules after applying configuration', async () => {
  const w = await mount({ state: 'COMPLETED', action: 'APPLY', vmschedulebackup: false, message: 'Assign the NetBackup offering to the VMs to protect; no Mold VM schedule is required' })
  expect(w.vm.statusMessage).toBe(ko['message.backup.integration.applied.policy'])
  expect(w.text()).toContain(ko['message.backup.integration.policy.limits'])
  expect(w.text()).toContain('BPSTART_TIMEOUT')
  w.unmount()
})

test('rechecking an already configured Host does not instruct applying it again', async () => {
  const w = await mount({ state: 'CHECKED', action: 'CHECK', configured: true, hooksready: true, timeractive: true, message: 'Assign the NetBackup offering to the VMs to protect and register their Mold backup schedules' })
  expect(w.vm.statusMessage).toBe(ko['message.backup.integration.rechecked'])
  w.unmount()
})

test('an applied Host with mismatching policy selections instructs fixing the path first', async () => {
  const w = await mount({ state: 'POLICY_PATH_MISMATCH', action: 'APPLY', message: 'Assign the NetBackup offering to the VMs to protect and register their Mold backup schedules' })
  expect(w.vm.statusMessage).toBe(ko['message.backup.integration.applied.path'])
  w.unmount()
})

test.each([401, 403, 404])('policy HTTP %i errors are localized and retain the response status', async status => {
  const raw = 'Cannot read NetBackup policy: HTTP ' + status + '; check the API key, permissions and policy name'
  const w = await mount({ state: 'FAILED', step: 'POLICY', message: raw })
  expect(w.vm.statusMessage).toContain('HTTP ' + status)
  expect(w.vm.statusMessage).toContain('NetBackup API 키')
  expect(w.text()).not.toContain(raw)
  w.unmount()
})

test('unknown diagnostic text and stage codes remain visible', async () => {
  const raw = 'Custom filesystem error: /mnt/staging/example'
  const w = await mount({ state: 'FAILED', step: 'NEW_AGENT_STAGE', message: raw, check: [{ name: 'NEW_AGENT_CHECK', state: 'NEW_AGENT_RESULT' }] })
  expect(w.text()).toContain(raw)
  expect(w.text()).toContain('NEW_AGENT_STAGE')
  expect(w.text()).toContain('NEW_AGENT_CHECK · NEW_AGENT_RESULT')
  w.unmount()
})

test('API error responses use the same localized configuration messages', async () => {
  const w = await mount()
  getAPI.mockRejectedValueOnce({ response: { data: { errorresponse: { errortext: 'Register an API key pair for this administrator before preparing Host integration' } } } })
  await w.vm.load()
  expect(w.text()).toContain(ko['message.backup.integration.result.credentials.required'])
  expect(w.vm.canRun).toBe(false)
  w.unmount()
})

test('default true mode guides offering assignment and VM schedules with an empty inventory', async () => {
  const w = await mount()
  expect(w.text()).toContain(ko['label.backup.integration.schedule'])
  expect(w.text()).not.toContain(ko['message.backup.integration.next.schedule'])
  expect(w.vm.step).toBe(1)
  await w.setData({ policyName: 'seocho+01', moldUrl: 'http://mold.test/client/api' })
  expect(w.vm.canRun).toBe(true)
  expect(w.vm.canApply).toBe(false)
  expect(w.text()).not.toContain('VM_INCLUDE')
  expect(w.text()).not.toContain('VM_EXCLUDE')
  await w.setData({ info: { ...w.vm.info, state: 'COMPLETED', linkready: true } })
  expect(w.text()).toContain(ko['message.backup.integration.next.schedule'])
  w.unmount()
})

test('off mode guides offering assignment without requiring or displaying VM schedules', async () => {
  const w = await mount({ vmschedulebackup: false, linkready: true })
  expect(w.text()).toContain(ko['message.backup.integration.next.policy'])
  expect(w.vm.columns.some(column => column.key === 'hasschedule')).toBe(false)
  expect(w.vm.step).toBe(2)
  expect(w.text()).not.toContain(ko['label.backup.integration.schedule'])
  w.unmount()
})

test('applying requires successful preflight for the exact current policy and endpoint', async () => {
  const w = await mount({ state: 'CHECKED', action: 'CHECK', policyname: 'seocho+01', moldurl: 'http://mold.test/client/api' })
  expect(w.vm.canApply).toBe(true)
  await w.setData({ policyName: 'other-policy' })
  expect(w.vm.canApply).toBe(false)
  await w.vm.run('APPLY')
  expect(postAPI).not.toHaveBeenCalled()
  w.unmount()
})

test('policy path mismatch shows expected and actual selections without reporting integration ready', async () => {
  const w = await mount({ state: 'POLICY_PATH_MISMATCH', bootstrappath: '/stage/bootstrap/seocho+01', policyselections: ['/wrong/path'], policypathmatched: false, linkready: false })
  expect(w.text()).toContain('/stage/bootstrap/seocho+01')
  expect(w.text()).toContain('/wrong/path')
  expect(w.text()).toContain(ko['label.backup.integration.path.mismatch'])
  expect(w.find('[data-type="success"]').exists()).toBe(false)
  w.unmount()
})

test('opening a running configuration resumes status polling and unmount cancels polling', async () => {
  const w = await mount({ state: 'RUNNING', operationid: 'operation-1' })
  expect(w.vm.busy).toBe(true)
  getAPI.mockResolvedValue(response({ state: 'COMPLETED', operationid: 'operation-1' }))
  jest.advanceTimersByTime(5000)
  await flush()
  expect(w.vm.busy).toBe(false)
  expect(getAPI).toHaveBeenCalledTimes(2)
  w.unmount()
  jest.advanceTimersByTime(10000)
  expect(getAPI).toHaveBeenCalledTimes(2)
})

test('a running receipt shows path verification in progress without a false mismatch or inspection time', async () => {
  const started = Date.UTC(2026, 10, 18, 4, 30, 37)
  const w = await mount({ state: 'RUNNING', step: 'PREFLIGHT', bootstrappath: '/stage/bootstrap/seocho+01', checkedat: started, policyselections: [], policypathmatched: false })
  expect(w.text()).toContain(ko['label.backup.integration.path.checking'])
  expect(w.text()).toContain(ko['message.backup.integration.running'])
  expect(w.text()).not.toContain(ko['label.backup.integration.path.mismatch'])
  expect(w.text()).not.toContain(ko['message.backup.integration.path.check'])
  expect(w.text()).not.toContain(new Date(started).toISOString())
  expect(w.find('[data-type="success"]').exists()).toBe(false)
  w.unmount()
})

test('applying stays in progress across job acceptance and receipt refresh until final path verification', async () => {
  const path = '/stage/bootstrap/seocho+01'
  let completeJob
  const w = await mount({ state: 'CHECKED', action: 'CHECK', policyname: 'seocho+01', moldurl: 'http://mold.test/client/api', bootstrappath: path, policyselections: ['/old/path'], check: [{ name: 'POLICY_PATH', state: 'MISMATCH' }], checkedat: 1 }, {
    pollJob: jest.fn(async ({ successMethod }) => { completeJob = successMethod })
  })
  expect(w.text()).toContain(ko['label.backup.integration.path.mismatch'])
  postAPI.mockResolvedValue({ configurenetbackuphostresponse: { jobid: 'apply-1' } })
  await w.vm.run('APPLY')
  await flush()
  expect(w.vm.busy).toBe(true)
  expect(w.text()).not.toContain(ko['label.backup.integration.path.mismatch'])
  let finishStatus
  getAPI.mockImplementationOnce(() => new Promise(resolve => { finishStatus = resolve }))
  const refreshing = completeJob()
  await flush()
  expect(w.vm.busy).toBe(true)
  expect(w.vm.pathState).toBe('checking')
  finishStatus(response({ state: 'RUNNING', action: 'APPLY', bootstrappath: path, checkedat: 2, policyselections: [], check: [] }))
  await refreshing
  await flush()
  expect(w.vm.submitting).toBe(false)
  expect(w.vm.pathState).toBe('checking')
  expect(w.text()).not.toContain(ko['label.backup.integration.path.mismatch'])
  const finished = Date.UTC(2026, 10, 18, 4, 30, 41)
  getAPI.mockResolvedValue(response({ state: 'COMPLETED', action: 'APPLY', bootstrappath: path, checkedat: finished, policyselections: [path], policypathmatched: true, check: [{ name: 'POLICY_PATH', state: 'OK' }], linkready: true }))
  jest.advanceTimersByTime(5000)
  await flush()
  expect(w.vm.pathState).toBe('matched')
  expect(w.text()).toContain(new Date(finished).toISOString())
  expect(w.find('[data-type="success"]').exists()).toBe(true)
  expect(w.text()).not.toContain(ko['label.backup.integration.path.mismatch'])
  w.unmount()
})

test('an actual empty selection after verification is shown as requiring a policy path update', async () => {
  const w = await mount({ state: 'CHECKED', action: 'CHECK', bootstrappath: '/stage/bootstrap/seocho+01', policyselections: [], policypathmatched: false, checkedat: 1, check: [{ name: 'POLICY_PATH', state: 'MISMATCH' }] })
  expect(w.vm.pathState).toBe('mismatch')
  expect(w.text()).toContain(ko['label.backup.integration.path.empty'])
  expect(w.text()).toContain(ko['label.backup.integration.path.mismatch'])
  w.unmount()
})

test('a failed preflight before policy path verification shows the path as unconfirmed', async () => {
  const w = await mount({ state: 'FAILED', step: 'POLICY', bootstrappath: '/stage/bootstrap/seocho+01', policyselections: [], policypathmatched: false, checkedat: 1, check: [{ name: 'POLICY', state: 'FAILED' }] })
  expect(w.vm.pathState).toBe('unconfirmed')
  expect(w.text()).toContain(ko['label.backup.integration.path.unconfirmed'])
  expect(w.text()).not.toContain(ko['label.backup.integration.path.mismatch'])
  w.unmount()
})

test('starting a recheck hides the previous ready state and restores it only after refreshing results', async () => {
  let completeJob
  const w = await mount({ state: 'COMPLETED', linkready: true, bootstrappath: '/stage/bootstrap/seocho+01', policypathmatched: true, check: [{ name: 'POLICY_PATH', state: 'OK' }] }, {
    pollJob: jest.fn(async ({ successMethod }) => { completeJob = successMethod })
  })
  await w.setData({ policyName: 'seocho+01', moldUrl: 'http://mold.test/client/api' })
  postAPI.mockResolvedValue({ configurenetbackuphostresponse: { jobid: 'check-2' } })
  await w.vm.run('CHECK')
  await flush()
  expect(w.find('[data-type="success"]').exists()).toBe(false)
  expect(w.text()).not.toContain(ko['message.backup.integration.next.schedule'])
  getAPI.mockResolvedValue(response({ state: 'COMPLETED', linkready: true }))
  await completeJob()
  await flush()
  expect(w.find('[data-type="success"]').exists()).toBe(true)
  w.unmount()
})

test('preflight starts through the admin API without transmitting API keys from the browser', async () => {
  const w = await mount()
  await w.setData({ policyName: 'seocho+01', moldUrl: 'http://mold.test/client/api' })
  postAPI.mockResolvedValue({ configurenetbackuphostresponse: { jobid: 'async-1' } })
  await w.vm.run('CHECK')
  await flush()
  expect(postAPI).toHaveBeenCalledWith('configureNetBackupHost', { hostid: 'host-1', action: 'CHECK', policyname: 'seocho+01', moldurl: 'http://mold.test/client/api' })
  expect(w.vm.submitting).toBe(false)
  w.unmount()
})

test('a late response from a previous Host cannot replace the current Host state', async () => {
  const w = await mount()
  let finish
  getAPI.mockImplementationOnce(() => new Promise(resolve => { finish = resolve }))
  const pending = w.vm.load()
  getAPI.mockResolvedValue(response({ policyname: 'host-two-policy' }))
  await w.setProps({ resource: { id: 'host-2', zoneid: 'zone-1', clusterid: 'cluster-1' } })
  await flush()
  finish(response({ policyname: 'stale-policy' }))
  await pending
  expect(w.vm.policyName).toBe('host-two-policy')
  w.unmount()
})

test('disabling the provider in a refreshed Host configuration disables both actions', async () => {
  const w = await mount({ providerenabled: false })
  await w.setData({ policyName: 'seocho+01', moldUrl: 'http://mold.test/client/api' })
  expect(w.vm.canRun).toBe(false)
  expect(w.vm.canApply).toBe(false)
  await w.vm.run('CHECK')
  expect(postAPI).not.toHaveBeenCalled()
  w.unmount()
})

test('a rejected provider status query disables actions using stale previous settings', async () => {
  const w = await mount({ state: 'CHECKED', action: 'CHECK', policyname: 'seocho+01', moldurl: 'http://mold.test/client/api' })
  expect(w.vm.canApply).toBe(true)
  getAPI.mockRejectedValueOnce(new Error('Zone NetBackup provider is disabled'))
  await w.vm.load()
  expect(w.vm.canRun).toBe(false)
  expect(w.vm.canApply).toBe(false)
  expect(w.text()).toContain('Zone NetBackup provider is disabled')
  w.unmount()
})
