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

import Wizard from '@/views/compute/CreateAutoScaleVmGroup.vue'
import Offering from '@/views/compute/wizard/ComputeOfferingSelection.vue'
import NetworkConfiguration from '@/views/compute/wizard/NetworkConfiguration.vue'
import { autoScaleInteger } from '@/utils/autoscaleValidation'
import { getAPI } from '@/api'
jest.mock('@/api', () => ({ getAPI: jest.fn(), postAPI: jest.fn() }))
jest.mock('@/store', () => ({ getters: { apis: {}, userInfo: {}, project: null } }))

const context = () => {
  const ctx = {
    ...Wizard.data.call({ ...Wizard.methods, $t: key => key }),
    form: {},
    hasRequiredCreationApis: true,
    isUserAllowedToListUserDatas: true,
    $t: key => key,
    $notification: { error: jest.fn() },
    $store: { getters: { project: null, userInfo: { account: 'owner', domainid: 'domain' } } }
  }
  for (const [name, method] of Object.entries(Wizard.methods)) ctx[name] = method.bind(ctx)
  Object.defineProperty(ctx, 'userDataLookupFailed', { get: () => Wizard.computed.userDataLookupFailed.call(ctx) })
  return ctx
}
beforeEach(() => { jest.clearAllMocks() })
test.each([0, '0', 12, '12', Number.MAX_SAFE_INTEGER])('accepts precise nonnegative integer %s', value => {
  expect(autoScaleInteger(value)).toBe(Number(value))
})
test.each([-1, '-1', 0.1, '0.1', '', ' ', null, undefined, true, NaN, Infinity, '1e3', Number.MAX_SAFE_INTEGER + 1])('rejects invalid threshold %s', value => {
  expect(autoScaleInteger(value)).toBeNull()
})
test('metadata for a role lacking creation APIs does not crash initialization', () => {
  expect(() => Wizard.beforeCreate.call({ $store: { getters: { apis: {} } } })).not.toThrow()
})
test.each(['userDataParams', 'templateUserDataParams'])('empty id never calls listUserData (%s)', async property => {
  const ctx = context(); ctx[property] = [{ key: 'old' }]
  await ctx.loadUserDataParams(undefined, property)
  expect(getAPI).not.toHaveBeenCalled(); expect(ctx[property]).toEqual([])
})
test.each([{}, { listuserdataresponse: {} }, { listuserdataresponse: { userdata: [] } }])('missing UserData is recoverable', async response => {
  getAPI.mockResolvedValue(response); const ctx = context(); await ctx.loadUserDataParams('id', 'userDataParams')
  expect(ctx.userDataParams).toEqual([]); expect(ctx.userDataLookupErrors.userDataParams).toBe(true)
})
test('UserData without params renders no parameter fields', async () => {
  getAPI.mockResolvedValue({ listuserdataresponse: { userdata: [{ id: 'id' }] } })
  const ctx = context(); await ctx.loadUserDataParams('id', 'templateUserDataParams')
  expect(ctx.templateUserDataParams).toEqual([]); expect(ctx.userDataLookupErrors.templateUserDataParams).toBe(false)
})
test('switching UserData ignores an older response', async () => {
  let resolveOld; getAPI.mockImplementationOnce(() => new Promise(resolve => { resolveOld = resolve }))
  getAPI.mockResolvedValue({ listuserdataresponse: { userdata: [{ id: 'new', params: ' a, , b ' }] } })
  const ctx = context(); const old = ctx.loadUserDataParams('old', 'userDataParams'); await ctx.loadUserDataParams('new', 'userDataParams')
  resolveOld({ listuserdataresponse: { userdata: [{ id: 'old', params: 'stale' }] } }); await old
  expect(ctx.userDataParams.map(item => item.key)).toEqual(['a', 'b'])
})
test('UserData API failure and missing permission are handled without an unhandled rejection', async () => {
  const ctx = context(); getAPI.mockRejectedValue(new Error('denied')); await ctx.loadUserDataParams('id', 'userDataParams')
  expect(ctx.userDataLookupErrors.userDataParams).toBe(true)
  getAPI.mockClear(); ctx.isUserAllowedToListUserDatas = false; await ctx.loadUserDataParams('id', 'userDataParams')
  expect(getAPI).not.toHaveBeenCalled()
})
test('min/max accepts positive int, rejects zero/fraction/overflow; grace period allows zero', async () => {
  const ctx = context()
  await expect(ctx.validateNumber({ field: 'minmembers' }, '1')).resolves.toBeUndefined()
  for (const n of [0, 1.5, 2147483648]) await expect(ctx.validateNumber({ field: 'minmembers' }, n)).rejects.toBeDefined()
  await expect(ctx.validateNumber({ field: 'expungevmgraceperiod' }, 0)).resolves.toBeUndefined()
})
const fixture = () => ({
  listTemplates: { listtemplatesresponse: { template: [{ id: 'template', isready: true, hypervisor: 'KVM' }] } },
  listServiceOfferings: { listserviceofferingsresponse: { serviceoffering: [{ id: 'offering', iscustomized: false }] } },
  listNetworks: { listnetworksresponse: { network: [{ id: 'network', supportsvmautoscaling: true, service: [{ name: 'Lb', provider: [{ name: 'VirtualRouter' }] }] }] } },
  listLoadBalancerRules: { listloadbalancerrulesresponse: { loadbalancerrule: [{ id: 'lb', networkid: 'network' }] } },
  listLoadBalancerRuleInstances: { listloadbalancerruleinstancesresponse: {} },
  listAutoScaleVmGroups: { listautoscalevmgroupsresponse: {} },
  listCounters: { counterresponse: { counter: [{ id: 'cpu' }] } }
})
const preflight = (changes = {}) => {
  const data = { ...fixture(), ...changes }; getAPI.mockImplementation(command => Promise.resolve(data[command]))
  const ctx = context(); ctx.defaultNetworkId = 'network'; ctx.scaleUpPolicies = [{ conditions: [{ counterid: 'cpu', relationaloperator: 'GT', threshold: 0 }] }]
  return ctx
}
const values = { templateid: 'template', computeofferingid: 'offering', zoneid: 'zone', loadbalancerid: 'lb' }
test('eligible resources are rechecked with owner scope and zero condition remains valid', async () => {
  const ctx = preflight(); expect(await ctx.validateCreationEligibility(values)).toBe(true)
  expect(getAPI).toHaveBeenCalledWith('listNetworks', expect.objectContaining({ account: 'owner', domainid: 'domain', id: 'network' }))
  expect(ctx.checkingEligibility).toBe(false)
})
test('submission waits for the selected UserData lookup and catches deletion before resource preflight', async () => {
  const ctx = preflight({ listUserData: { listuserdataresponse: {} } })
  expect(await ctx.validateCreationEligibility({ ...values, userdataid: 'deleted' })).toBe(false)
  expect(getAPI.mock.calls.map(call => call[0])).toEqual(['listUserData'])
  expect(ctx.$notification.error).toHaveBeenCalledWith(expect.objectContaining({ description: 'message.autoscale.userdata.unavailable' }))
  expect(ctx.checkingEligibility).toBe(false)
})
test('changed UserData parameter definitions stop submission so the form can be reviewed', async () => {
  const ctx = preflight({ listUserData: { listuserdataresponse: { userdata: [{ id: 'id', params: 'new' }] } } })
  expect(await ctx.validateCreationEligibility({ ...values, userdataid: 'id' })).toBe(false)
  expect(ctx.userDataParams.map(item => item.key)).toEqual(['new'])
  expect(getAPI.mock.calls.map(call => call[0])).toEqual(['listUserData'])
})
test('unchanged UserData parameters retain entered values and pass the fresh preflight', async () => {
  const ctx = preflight({ listUserData: { listuserdataresponse: { userdata: [{ id: 'id', params: 'key' }] } } })
  ctx.userDataParams = [{ key: 'key' }]; ctx.userDataValues = { key: 'entered' }
  expect(await ctx.validateCreationEligibility({ ...values, userdataid: 'id' })).toBe(true)
  expect(ctx.userDataValues).toEqual({ key: 'entered' })
})
test('project scope does not send account/domain selectors', async () => {
  const ctx = preflight(); ctx.$store.getters.project = { id: 'project' }; expect(await ctx.validateCreationEligibility(values)).toBe(true)
  const request = getAPI.mock.calls.find(call => call[0] === 'listNetworks')[1]
  expect(request.projectid).toBe('project'); expect(request.account).toBeUndefined(); expect(request.domainid).toBeUndefined()
})
test.each([
  ['listTemplates', { listtemplatesresponse: {} }],
  ['listServiceOfferings', { listserviceofferingsresponse: { serviceoffering: [{ id: 'offering', iscustomized: true }] } }],
  ['listNetworks', { listnetworksresponse: { network: [{ id: 'network', supportsvmautoscaling: false }] } }],
  ['listLoadBalancerRules', { listloadbalancerrulesresponse: { loadbalancerrule: [{ id: 'lb', networkid: 'foreign' }] } }],
  ['listLoadBalancerRuleInstances', { listloadbalancerruleinstancesresponse: { count: 1 } }],
  ['listAutoScaleVmGroups', { listautoscalevmgroupsresponse: { count: 1 } }],
  ['listCounters', { counterresponse: {} }]
])('rejects stale/ineligible %s', async (command, data) => {
  const ctx = preflight({ [command]: data }); expect(await ctx.validateCreationEligibility(values)).toBe(false)
  expect(ctx.$notification.error).toHaveBeenCalled(); expect(ctx.checkingEligibility).toBe(false)
})
test('query failure and missing role permissions stop before resource creation', async () => {
  const ctx = preflight(); getAPI.mockRejectedValue(new Error('offline')); expect(await ctx.validateCreationEligibility(values)).toBe(false)
  getAPI.mockClear(); ctx.hasRequiredCreationApis = false; expect(await ctx.validateCreationEligibility(values)).toBe(false); expect(getAPI).not.toHaveBeenCalled()
})
const offeringContext = (props = {}) => {
  const ctx = {
    autoscale: true,
    value: '',
    preFillContent: {},
    selectedRowKeys: [],
    $emit: jest.fn(),
    tableSource: [{ key: 'dynamic', disabled: true }, { key: 'fixed', disabled: false }],
    ...props
  }
  ctx.selectInitialRow = Offering.methods.selectInitialRow.bind(ctx)
  return ctx
}
test('loading completion never defaults AutoScale to a disabled dynamic offering', () => {
  const ctx = offeringContext({ loading: false }); Offering.watch.loading.call(ctx)
  expect(ctx.selectedRowKeys).toEqual(['fixed']); expect(ctx.$emit).toHaveBeenCalledWith('select-compute-item', 'fixed', undefined)
})
test('a disabled AutoScale prefill falls back to fixed; all disabled clears selection', () => {
  const ctx = offeringContext({ value: 'dynamic' }); ctx.selectInitialRow(); expect(ctx.selectedRowKeys).toEqual(['fixed'])
  ctx.tableSource = [{ key: 'dynamic', disabled: true }]; ctx.selectInitialRow(); expect(ctx.selectedRowKeys).toEqual([]); expect(ctx.$emit).toHaveBeenLastCalledWith('select-compute-item', '')
})
test('pagination preserves a valid selection outside the current page', () => {
  const ctx = offeringContext({ value: 'other-page' }); ctx.selectInitialRow(); expect(ctx.selectedRowKeys).toEqual(['other-page']); expect(ctx.$emit).not.toHaveBeenCalled()
})
test('normal VM prefill behavior is preserved and disabled radio selection is ignored', () => {
  const ctx = offeringContext({ autoscale: false, value: 'dynamic' }); ctx.selectInitialRow(); expect(ctx.selectedRowKeys).toEqual(['dynamic'])
  Offering.methods.onSelectRow.call(ctx, ['dynamic']); expect(ctx.$emit).not.toHaveBeenCalled()
})

test.each(['scaleup', 'scaledown'])('policy inputs validate the selected policy model (%s)', async direction => {
  const ctx = context()
  const key = direction === 'scaleup' ? 'selectedScaleUpPolicy' : 'selectedScaleDownPolicy'
  ctx[key] = { [`${direction}duration`]: '30', [`${direction}quiettime`]: '0' }
  await expect(ctx.validateNumber({ field: `${direction}duration` }, undefined)).resolves.toBeUndefined()
  await expect(ctx.validateNumber({ field: `${direction}quiettime` }, undefined)).resolves.toBeUndefined()
  ctx[key][`${direction}duration`] = '0.5'
  await expect(ctx.validateNumber({ field: `${direction}duration` }, undefined)).rejects.toBeDefined()
})

test.each([
  ['fetchAvailableGuestIps', 'listAvailableGuestIps'],
  ['fetchPublicIps', 'listPublicIpAddresses']
])('optional IP query is skipped without permission (%s)', async (method, api) => {
  const ctx = { $store: { getters: { apis: {} } }, ipOptions: {}, ipOptionsLoading: {} }
  await NetworkConfiguration.methods[method].call(ctx, { id: 'network' })
  expect(getAPI).not.toHaveBeenCalled()
  expect(ctx.ipOptions.network).toEqual([])
  expect(ctx.ipOptionsLoading.network).toBe(false)
})
test('authorized available-IP query retains network and finishes loading on empty response', async () => {
  const ctx = { $store: { getters: { apis: { listAvailableGuestIps: {} } } }, ipOptions: {}, ipOptionsLoading: {}, $notifyError: jest.fn() }
  getAPI.mockResolvedValue({})
  await NetworkConfiguration.methods.fetchAvailableGuestIps.call(ctx, { id: 'network' })
  expect(getAPI).toHaveBeenCalledWith('listAvailableGuestIps', { networkid: 'network', pagesize: -1 })
  expect(ctx.ipOptionsLoading.network).toBe(false)
  expect(ctx.$notifyError).not.toHaveBeenCalled()
})
