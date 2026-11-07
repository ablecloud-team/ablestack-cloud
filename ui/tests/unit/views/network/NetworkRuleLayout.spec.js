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

import { mount, flushPromises } from '@vue/test-utils'
import { h } from 'vue'
import EgressRulesTab from '@/views/network/EgressRulesTab'
import RoutingFirewallRulesTab from '@/views/network/RoutingFirewallRulesTab'
import Ipv6FirewallRulesTab from '@/views/network/Ipv6FirewallRulesTab'
import { getAPI, postAPI } from '@/api'

jest.mock('@/api', () => ({ getAPI: jest.fn(), postAPI: jest.fn() }))
const cases = [
  ['egress', EgressRulesTab, 'createEgressFirewallRule', 'listEgressFirewallRules', 'deleteEgressFirewallRule'],
  ['routing', RoutingFirewallRulesTab, 'createRoutingFirewallRule', 'listRoutingFirewallRules', 'deleteRoutingFirewallRule'],
  ['IPv6', Ipv6FirewallRulesTab, 'createIpv6FirewallRule', 'listIpv6FirewallRules', 'deleteIpv6FirewallRule']
]
const dialog = { props: ['visible'], render () { return this.visible ? h('section', { class: 'test-rule-dialog' }, [this.$slots.default?.(), this.$slots.footer?.()]) : null } }
const wrappers = []
let pendingJobReads = []
let pendingFirewallJobs = []
async function open (component, create, list, remove, denied = false) {
  const apis = { [list]: {}, [remove]: {} }
  if (create === 'createEgressFirewallRule') Object.assign(apis, { listNetworks: {}, listAsyncJobs: {}, queryAsyncJobResult: {} })
  if (!denied) apis[create] = {}
  const w = mount(component, {
    props: { resource: { id: 'network-a', type: 'Isolated', egressdefaultpolicy: false, ip4routing: create === 'createRoutingFirewallRule' ? 'Static' : null, internetprotocol: create === 'createIpv6FirewallRule' ? 'DualStack' : 'IPv4' } },
    global: {
      mocks: { $t: k => k, $store: { getters: { apis, userInfo: { roletype: 'Admin' } } }, $route: { path: '/guestnetwork/network-a', query: {}, meta: { name: 'guestnetwork' } }, $notifyError: jest.fn(), $pollJob: jest.fn(() => new Promise(resolve => pendingFirewallJobs.push(resolve))) },
      provide: { parentFetchData: jest.fn(), resourceActionReady: () => true },
      directives: { focus: () => {}, 'ctrl-enter': () => {} },
      stubs: { MoldDialog: dialog, ATable: { render: () => h('div', { class: 'test-rule-table' }) }, BulkActionView: true }
    }
  })
  wrappers.push(w); await flushPromises(); return w
}
beforeEach(() => {
  jest.clearAllMocks()
  pendingJobReads = []
  pendingFirewallJobs = []
  getAPI.mockImplementation(cmd => {
    if (cmd === 'queryAsyncJobResult') return new Promise(resolve => pendingJobReads.push(resolve))
    if (cmd === 'listNetworks') return Promise.resolve({ listnetworksresponse: { count: 1, network: [{ id: 'network-a', state: 'Implemented' }] } })
    return Promise.resolve({ [cmd.toLowerCase() + 'response']: { networkprotocol: [], firewallrule: [], count: 0 } })
  })
})
afterEach(() => { wrappers.splice(0).forEach(w => w.unmount()) })

describe.each(cases)('%s VM-standard rule tab content', (name, component, create, list, remove) => {
  test('actions precede guidance, stale notice and table; the form opens only in a dialog', async () => {
    const w = await open(component, create, list, remove)
    await w.setData({ listRefreshFailed: true })
    const toolbar = w.get('.detail-tab-toolbar').element
    expect(w.element.firstElementChild).toBe(toolbar)
    for (const banner of w.findAll('.network-rules-guidance')) expect(toolbar.compareDocumentPosition(banner.element) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
    expect(toolbar.compareDocumentPosition(w.get('.test-rule-table').element) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
    expect(w.find('.form').exists()).toBe(false)
    if (name !== 'egress') expect(w.get('.detail-tab-toolbar .ant-btn-primary').attributes('disabled')).toBeDefined()
    await w.setData({ listRefreshFailed: false })
    await w.get('.detail-tab-toolbar .ant-btn-primary').trigger('click')
    expect(w.find('.test-rule-dialog .form').exists()).toBe(true)
    expect(postAPI).not.toHaveBeenCalled()
    const cancel = w.findAll('.test-rule-dialog button').find(b => b.text() === 'label.cancel')
    await cancel.trigger('click')
    expect(w.find('.test-rule-dialog').exists()).toBe(false)
    expect(postAPI).not.toHaveBeenCalled()
  })
  test('permission denial disables add while refresh remains usable', async () => {
    const w = await open(component, create, list, remove, true)
    expect(w.get('.detail-tab-toolbar .ant-btn-primary').attributes('disabled')).toBeDefined()
    getAPI.mockClear()
    await w.findAll('.detail-tab-toolbar button').find(b => b.text() === 'label.refresh').trigger('click'); await flushPromises()
    expect(getAPI.mock.calls.filter(([cmd]) => cmd === list)).toHaveLength(1)
    expect(postAPI).not.toHaveBeenCalled()
  })
  test('selected-row delete action stays with the top toolbar', async () => {
    const w = await open(component, create, list, remove)
    await w.setData({ selectedRowKeys: ['rule-a'], selectedItems: [{ id: 'rule-a' }] })
    expect(w.find('.detail-tab-toolbar .ant-btn-dangerous').exists()).toBe(true)
  })
  test('submit keeps the existing API payload and closes only on confirmed job success', async () => {
    const w = await open(component, create, list, remove)
    await w.get('.detail-tab-toolbar .ant-btn-primary').trigger('click')
    const expected = { ...w.vm.newRule, cidrlist: name === 'IPv6' ? '2001:db8::/64' : '192.0.2.0/24' }
    w.vm.newRule = { ...expected }
    postAPI.mockResolvedValue({ [create.toLowerCase() + 'response']: { jobid: 'job-a' } })
    await w.findAll('.test-rule-dialog button').find(b => b.text() === 'label.add').trigger('click'); await flushPromises()
    expect(postAPI).toHaveBeenCalledTimes(1)
    if (name === 'egress') expect(postAPI).toHaveBeenCalledWith(create, { ...expected, ignoreproject: true }, { preserveOnFailure: true })
    else expect(postAPI).toHaveBeenCalledWith(create, Object.fromEntries(Object.entries(expected).filter(([, v]) => v != null)))
    expect(w.vm.addRuleVisible).toBe(true)
    if (name === 'egress') {
      expect(w.vm.networkMutation.jobId).toBe('job-a')
      pendingJobReads.shift()({ queryasyncjobresultresponse: { jobid: 'job-a', jobstatus: 1 } })
    } else {
      const job = w.vm.$pollJob.mock.calls[0][0]
      expect(job.jobId).toBe('job-a'); pendingFirewallJobs.shift()({ jobstatus: 1 })
    }
    await flushPromises()
    expect(w.vm.addRuleVisible).toBe(false)
  })
  test('job failure leaves the dialog available for correction', async () => {
    const w = await open(component, create, list, remove)
    await w.get('.detail-tab-toolbar .ant-btn-primary').trigger('click')
    postAPI.mockResolvedValue({ [create.toLowerCase() + 'response']: { jobid: 'job-a' } })
    await w.findAll('.test-rule-dialog button').find(b => b.text() === 'label.add').trigger('click'); await flushPromises()
    const draft = { ...w.vm.newRule }
    if (name === 'egress') pendingJobReads.shift()({ queryasyncjobresultresponse: { jobid: 'job-a', jobstatus: 2, jobresult: { errorcode: 530, errortext: 'Router not ready' } } })
    else pendingFirewallJobs.shift()({ jobstatus: 2 })
    await flushPromises()
    expect(w.vm.addRuleVisible).toBe(true)
    expect(w.vm.newRule).toEqual(draft)
    if (name === 'egress') expect(w.vm.networkMutation.error).toBe('Router not ready')
  })
})
