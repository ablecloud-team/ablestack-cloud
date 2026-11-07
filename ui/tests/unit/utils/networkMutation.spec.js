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

import { shallowMount, flushPromises } from '@vue/test-utils'
import { reactive } from 'vue'
import { networkMutationMixin } from '@/utils/networkMutation'
import FirewallRules from '@/views/network/FirewallRules'
import { getAPI, postAPI } from '@/api'

jest.mock('@/api', () => ({ getAPI: jest.fn(), postAPI: jest.fn() }))
const component = { mixins: [networkMutationMixin], props: ['resource'], data: () => ({ draft: '10.0.0.0/24', listRefreshFailed: false }), template: '<div>{{ draft }}</div>' }
let wrappers
const result = status => ({ queryasyncjobresultresponse: { jobid: 'job', jobinstanceid: 'rule', jobinstancetype: 'FirewallRule', cmd: 'org.apache.cloudstack.api.command.user.firewall.CreateEgressFirewallRuleCmd', jobstatus: status, ...(status === 2 ? { jobresult: { errorcode: 530, errortext: 'Router implementation is incomplete' } } : {}) } })
beforeEach(() => {
  jest.clearAllMocks(); wrappers = []
  getAPI.mockImplementation(async api => api === 'listNetworks' ? { listnetworksresponse: { count: 1, network: [{ id: 'net', type: 'Isolated', state: 'Implemented' }] } } : api === 'listAsyncJobs' ? { listasyncjobsresponse: { count: 0, asyncjobs: [] } } : result(1))
  postAPI.mockResolvedValue({ createegressfirewallruleresponse: { jobid: 'job', id: 'rule' } })
})
afterEach(() => { wrappers.forEach(w => w.unmount()); jest.useRealTimers() })
function mount () {
  const getters = reactive({ apis: Object.fromEntries(['createEgressFirewallRule', 'queryAsyncJobResult', 'listNetworks', 'listAsyncJobs'].map(a => [a, {}])), userInfo: { id: 'user', roletype: 'Admin', roleid: 'role', domainid: 'root' }, project: {} })
  const route = reactive({ path: '/guestnetwork/net', meta: { name: 'guestnetwork' } })
  const w = shallowMount(component, { props: { resource: { id: 'net', projectid: 'actual-project' } }, global: { mocks: { $store: { getters, state: { user: { token: 'session' } } }, $route: route, $t: k => k } } })
  wrappers.push(w); return { w, getters, route }
}
const args = { networkid: 'net', protocol: 'tcp', cidrlist: '10.0.0.0/24', startport: 443, endport: 443 }

test('Public IP firewall initialization binds the IP and verifies its associated guest network', async () => {
  const draft = { newRule: {}, resource: { id: 'public-ip' }, resetRulePorts: jest.fn() }
  FirewallRules.methods.resetAllRules.call(draft)
  expect(draft.newRule).toEqual({ protocol: 'tcp', cidrlist: null, ipaddressid: 'public-ip' })
  const original = getAPI.getMockImplementation()
  getAPI.mockImplementation(async (api, params) => api === 'queryAsyncJobResult'
    ? { queryasyncjobresultresponse: { jobid: 'job', jobinstanceid: 'rule', cmd: 'org.apache.cloudstack.api.command.user.firewall.CreateFirewallRuleCmd', jobstatus: 1 } }
    : original(api, params))
  postAPI.mockResolvedValue({ createfirewallruleresponse: { jobid: 'job', id: 'rule' } })
  const { w, getters } = mount(); getters.apis.createFirewallRule = {}
  await w.setProps({ resource: { id: 'public-ip', associatednetworkid: 'net', projectid: 'actual-project' } }); await flushPromises()
  expect(await w.vm.submitNetworkMutation('createFirewallRule', draft.newRule, jest.fn())).toBe(true)
  expect(getAPI.mock.calls.find(c => c[0] === 'listNetworks')[1].id).toBe('net')
  expect(postAPI.mock.calls[0][1]).toMatchObject({ ipaddressid: 'public-ip' })
})

test.each(['org.apache.cloudstack.api.command.user.address.AssociateIPAddrCmd', 'org.apache.cloudstack.api.command.admin.address.AssociateIPAddrCmdByAdmin'])('IP acquisition accepts the registered command %s', async cmd => {
  const original = getAPI.getMockImplementation()
  getAPI.mockImplementation(async (api, params) => api === 'queryAsyncJobResult'
    ? { queryasyncjobresultresponse: { jobid: 'ip-job', jobinstanceid: 'ip', jobinstancetype: 'IpAddress', cmd, jobstatus: 1 } }
    : original(api, params))
  postAPI.mockResolvedValue({ associateipaddressresponse: { jobid: 'ip-job', id: 'ip' } })
  const { w, getters } = mount(); getters.apis.associateIpAddress = {}; await flushPromises()
  const success = jest.fn()
  expect(await w.vm.submitNetworkMutation('associateIpAddress', { networkid: 'net' }, success)).toBe(true)
  expect(success).toHaveBeenCalledTimes(1)
  expect(postAPI).toHaveBeenCalledTimes(1)
})

test('Source NAT changes accept the admin command for the same network', async () => {
  const original = getAPI.getMockImplementation()
  getAPI.mockImplementation(async (api, params) => api === 'queryAsyncJobResult'
    ? { queryasyncjobresultresponse: { jobid: 'nat-job', jobinstanceid: 'net', jobinstancetype: 'Network', cmd: 'org.apache.cloudstack.api.command.admin.network.UpdateNetworkCmdByAdmin', jobstatus: 1 } }
    : original(api, params))
  postAPI.mockResolvedValue({ updatenetworkresponse: { jobid: 'nat-job' } })
  const { w, getters } = mount()
  getters.apis.updateNetwork = {}
  await flushPromises()
  const success = jest.fn()
  expect(await w.vm.submitNetworkMutation('updateNetwork', { id: 'net', sourcenatipaddress: '192.0.2.3' }, success)).toBe(true)
  expect(success).toHaveBeenCalledTimes(1)
})

test('Remote Access VPN job verifies the returned VPN and public address', async () => {
  const original = getAPI.getMockImplementation()
  getAPI.mockImplementation(async (api, params) => api === 'queryAsyncJobResult'
    ? {
      queryasyncjobresultresponse: {
        jobid: 'vpn-job',
        jobinstanceid: 'different-ip-object',
        jobinstancetype: 'IpAddress',
        cmd: 'org.apache.cloudstack.api.command.user.vpn.CreateRemoteAccessVpnCmd',
        jobstatus: 1,
        jobresult: { remoteaccessvpn: { id: 'vpn', publicip: '192.0.2.3' } }
      }
    }
    : original(api, params))
  postAPI.mockResolvedValue({ createremoteaccessvpnresponse: { jobid: 'vpn-job', id: 'vpn' } })
  const { w, getters } = mount()
  getters.apis.createRemoteAccessVpn = {}
  await w.setProps({ resource: { id: 'public-ip', associatednetworkid: 'net', ipaddress: '192.0.2.3' } })
  const success = jest.fn()
  expect(await w.vm.submitNetworkMutation('createRemoteAccessVpn', { publicipid: 'public-ip' }, success)).toBe(true)
  expect(success).toHaveBeenCalledTimes(1)
})

test('acceptance does not close or clear a draft until the server job completes', async () => {
  let release
  getAPI.mockImplementation(async api => api === 'queryAsyncJobResult' ? new Promise(resolve => { release = resolve }) : api === 'listAsyncJobs' ? { listasyncjobsresponse: { count: 0 } } : { listnetworksresponse: { count: 1, network: [{ id: 'net', state: 'Implemented' }] } })
  const { w } = mount(); const success = jest.fn(() => { w.vm.draft = '' })
  const pending = w.vm.submitNetworkMutation('createEgressFirewallRule', args, success); await flushPromises()
  expect(w.vm.networkMutation.status).toBe('running'); expect(w.vm.draft).toBe('10.0.0.0/24'); expect(success).not.toHaveBeenCalled()
  release(result(1)); await pending; expect(success).toHaveBeenCalledTimes(1)
})

test.each(['post', 'job'])('%s failure preserves the input and detailed server cause', async stage => {
  if (stage === 'post') postAPI.mockRejectedValue({ response: { data: { errorresponse: { errorcode: 530, errortext: 'Router implementation is incomplete' } } } })
  else getAPI.mockImplementation(async api => api === 'queryAsyncJobResult' ? result(2) : api === 'listAsyncJobs' ? { listasyncjobsresponse: { count: 0 } } : { listnetworksresponse: { count: 1, network: [{ id: 'net' }] } })
  const { w } = mount(); const success = jest.fn()
  expect(await w.vm.submitNetworkMutation('createEgressFirewallRule', args, success)).toBe(false)
  expect(w.vm.networkMutation).toMatchObject({ status: 'failed', code: 530, error: 'Router implementation is incomplete' })
  expect(w.vm.draft).toBe(args.cidrlist); expect(success).not.toHaveBeenCalled()
})

test('ambiguous acceptance blocks all repeated writes', async () => {
  postAPI.mockRejectedValue(new Error('Connection lost'))
  const { w } = mount(); await w.vm.submitNetworkMutation('createEgressFirewallRule', args, jest.fn())
  expect(w.vm.networkMutation.status).toBe('unknown')
  await w.vm.submitNetworkMutation('createEgressFirewallRule', args, jest.fn()); await w.vm.recheckNetworkMutation()
  expect(postAPI).toHaveBeenCalledTimes(1)
})

test('rechecking a known job after transient read failure cannot send another POST', async () => {
  jest.useFakeTimers()
  getAPI.mockImplementation(async api => {
    if (api === 'queryAsyncJobResult') throw new Error('Read failed')
    return api === 'listAsyncJobs' ? { listasyncjobsresponse: { count: 0 } } : { listnetworksresponse: { count: 1, network: [{ id: 'net' }] } }
  })
  const { w } = mount(); const success = jest.fn()
  const pending = w.vm.submitNetworkMutation('createEgressFirewallRule', args, success); await flushPromises()
  for (let i = 0; i < 3; i++) { jest.advanceTimersByTime(2000); await flushPromises() }
  await pending; expect(w.vm.networkMutation.status).toBe('unknown')
  getAPI.mockResolvedValue(result(1)); await w.vm.recheckNetworkMutation()
  expect(postAPI).toHaveBeenCalledTimes(1); expect(success).toHaveBeenCalledTimes(1)
})

test('a successful job followed by failed list refresh remains successful and cannot be resent', async () => {
  const { w } = mount()
  await w.vm.submitNetworkMutation('createEgressFirewallRule', args, () => { throw new Error('Final list read failed') })
  expect(w.vm.networkMutation).toMatchObject({ status: 'success', error: 'message.network.lifecycle.mutation.refresh' })
  await w.vm.submitNetworkMutation('createEgressFirewallRule', args, jest.fn()); expect(postAPI).toHaveBeenCalledTimes(1)
})

test.each(['project', 'role', 'route'])('%s changes suppress late job success callbacks', async field => {
  let release
  const original = getAPI.getMockImplementation()
  getAPI.mockImplementation(async (api, params) => api === 'queryAsyncJobResult' ? new Promise(resolve => { release = resolve }) : original(api, params))
  const { w, getters, route } = mount(); const success = jest.fn()
  const pending = w.vm.submitNetworkMutation('createEgressFirewallRule', args, success); await flushPromises()
  if (field === 'project') getters.project.id = 'other'
  if (field === 'role') getters.userInfo.roletype = 'ReadOnlyAdmin'
  if (field === 'route') route.path = '/publicip/other'
  await flushPromises(); release(result(1)); await pending
  expect(w.vm.networkMutation).toBe(null); expect(success).not.toHaveBeenCalled()
})

test('current resource project is explicit in reads and writes', async () => {
  const { w, getters } = mount(); getters.project.id = 'selected-project'; await flushPromises()
  await w.vm.submitNetworkMutation('createEgressFirewallRule', args, jest.fn())
  expect(postAPI).toHaveBeenCalledWith('createEgressFirewallRule', { ...args, ignoreproject: true, projectid: 'actual-project' }, { preserveOnFailure: true })
  expect(getAPI.mock.calls.find(c => c[0] === 'listNetworks')[1]).toMatchObject({ projectid: 'actual-project', ignoreproject: true })
})

test('a stale list or a busy network prevents a new write', async () => {
  const { w } = mount(); w.vm.listRefreshFailed = true
  await w.vm.submitNetworkMutation('createEgressFirewallRule', args, jest.fn()); expect(postAPI).not.toHaveBeenCalled()
  w.vm.listRefreshFailed = false; getAPI.mockResolvedValue({ listnetworksresponse: { count: 1, network: [{ id: 'net', state: 'Implementing' }] } })
  await w.vm.submitNetworkMutation('createEgressFirewallRule', args, jest.fn()); expect(postAPI).not.toHaveBeenCalled()
})
