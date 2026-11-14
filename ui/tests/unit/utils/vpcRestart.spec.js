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
import { vpcRestartApis, vpcRestartOptions, vpcRestartReason, vpcRestartCapabilities, vpcRestartOptionReason, vpcRestartParams, validVpcRestartJob } from '@/utils/vpcRestart'
import network from '@/config/section/network'
import AutogenView from '@/views/AutogenView'
jest.mock('@/api', () => ({ getAPI: jest.fn(), postAPI: jest.fn(), callAPI: jest.fn() }))
const apis = Object.fromEntries(vpcRestartApis.map(a => [a, {}]))
const user = { id: 'u', roletype: 'User', account: 'owner', domainid: 'd', domainpath: 'ROOT/D' }
const vpc = { id: 'a', zoneid: 'z', account: 'owner', domainid: 'd', domainpath: 'ROOT/D', state: 'Enabled', vpcofferingid: 'o', redundantvpcrouter: false }
const data = { vpc, offering: { service: [{ name: 'SourceNat', provider: [{ name: 'VpcVirtualRouter' }] }] }, redundantOffering: { id: 'r' }, routers: [{ state: 'Running' }], networks: [{ id: 'n' }], jobs: [] }

test.each([
  [{}, user, {}, ''],
  [{ id: undefined }, user, {}, 'missing'],
  [{ state: 'Disabled' }, user, {}, 'state'],
  [{ state: 'Inactive' }, user, {}, 'state'],
  [{ jobid: 'job', jobstatus: 0 }, user, {}, 'state'],
  [{ account: 'foreign' }, user, {}, 'permission'],
  [{ domainid: 'foreign' }, user, {}, 'permission'],
  [{ account: 'foreign', projectid: 'p' }, user, { id: 'p' }, ''],
  [{ projectid: 'p' }, user, { id: 'q' }, 'permission'],
  [{}, { ...user, roletype: 'Admin' }, { id: 'p' }, 'project'],
  [{ domainid: 'child', domainpath: 'ROOT/D/CHILD' }, { ...user, roletype: 'DomainAdmin' }, {}, ''],
  [{ domainid: 'peer', domainpath: 'ROOT/DIFFERENT' }, { ...user, roletype: 'DomainAdmin' }, {}, 'permission']
])('target policy %j', (extra, actor, project, reason) => expect(vpcRestartReason({ ...vpc, ...extra }, { apis, userInfo: actor, project })).toBe(reason))

test('all required capabilities must be visible', () => {
  for (const missing of vpcRestartApis) expect(vpcRestartReason(vpc, { apis: Object.fromEntries(vpcRestartApis.filter(a => a !== missing).map(a => [a, {}])), userInfo: user })).toBe('permission')
})

test.each([undefined, {}, { cleanup: false, livepatch: false, makeredundant: false }, { cleanup: null, livepatch: 'true' }])('false/default values remain explicit: %j', values => expect(vpcRestartParams('a', values)).toEqual({ id: 'a', cleanup: false, makeredundant: false, livepatch: false }))
test('true values and absent API parameters have matching single/bulk semantics', () => {
  expect(vpcRestartParams('a', { livepatch: true }, ['livepatch'])).toEqual({ id: 'a', livepatch: true })
  const action = network.children.find(s => s.name === 'vpc').actions.find(a => a.api === 'restartVPC')
  expect(action.groupMap(['a', 'b'], { livepatch: true })).toEqual(['a', 'b'].map(id => ({ id, cleanup: false, makeredundant: false, livepatch: true })))
  expect(action.selfManagedDialog).toBe(true)
  expect(action.disabled(vpc, { apis, userInfo: user }, [vpc, { ...vpc, id: 'b', state: 'Inactive' }])).toBe(true)
  expect(action.tooltip(vpc, { apis, userInfo: user }, [vpc, { ...vpc, id: 'b', state: 'Inactive' }])).toBe('message.vpc.restart.reason.state')
})
test('guest network #1559 mapping is unchanged', () => {
  const action = network.children.find(s => s.name === 'guestnetwork').actions.find(a => a.api === 'restartNetwork')
  expect(action.groupMap(['n'], { livepatch: true })).toEqual([{ id: 'n', cleanup: false, makeredundant: false, livepatch: true }])
  expect(action.selfManagedDialog).toBe(true)
})
test.each([
  [{}, { cleanup: true, makeredundant: true, livepatch: true }],
  [{ vpc: { ...vpc, redundantvpcrouter: true } }, { makeredundant: false }],
  [{ redundantOffering: undefined }, { makeredundant: false }],
  [{ offering: { fornsx: true } }, { cleanup: false, makeredundant: false, livepatch: false }],
  [{ routers: null }, { livepatch: false }],
  [{ routers: [{ state: 'Stopped' }] }, { livepatch: false }],
  [{ networks: [] }, { livepatch: false }]
])('provider, redundancy, Tier and router capability %j', (change, expected) => expect(vpcRestartCapabilities({ ...data, ...change }, vpcRestartOptions)).toMatchObject(expected))
test.each([
  [{ cleanup: true, livepatch: true }, {}, 'livepatch'],
  [{ makeredundant: true, livepatch: true }, {}, 'livepatch'],
  [{}, { jobs: [{ jobid: 'unbound' }] }, 'pending'],
  [{}, { routers: [{ state: 'Starting' }] }, 'router'],
  [{ makeredundant: true }, { vpc: { ...vpc, redundantvpcrouter: true } }, 'redundant']
])('invalid option/state combination %j', (options, change, expected) => expect(vpcRestartOptionReason({ ...data, ...change }, options, vpcRestartOptions)).toBe(expected))

const job = { jobid: 'job', cmd: 'org.apache.cloudstack.api.command.user.vpc.RestartVPCCmd', userid: 'u', jobstatus: 1, jobinstancetype: 'Vpc' }
test('actual VPC job contract may omit the instance UUID', () => expect(validVpcRestartJob(job, 'a', 'job', 'u')).toBe(true))
test.each([{ jobid: 'other' }, { cmd: 'DeleteVPCCmd' }, { userid: 'other' }, { jobinstanceid: 'b' }, { jobinstancetype: 'Network' }, { jobstatus: 3 }])('mismatched job is rejected %j', change => expect(validVpcRestartJob({ ...job, ...change }, 'a', 'job', 'u')).toBe(false))

test('Autogen freezes only the invoked VPC selection and leaves guest restart integration intact', () => {
  const selected = [{ ...vpc }, { ...vpc, id: 'b' }]
  const context = { $route: { path: '/vpc' }, $store: { getters: { apis: { restartVPC: {}, listVPCs: {} } } }, selectedItems: selected, resource: vpc, storageActionGeneration: 0 }
  AutogenView.methods.execAction.call(context, { api: 'restartVPC' }, true)
  selected[0].name = 'changed'
  expect(context.currentAction.vpcRestartTargets[0].name).toBeUndefined()
  expect(Object.isFrozen(context.currentAction.vpcRestartTargets)).toBe(true)
  expect(context.currentAction.invokedAsGroupAction).toBe(true)
  expect(context.showAction).toBe(true)
  const single = { ...context, $store: { getters: { apis: { restartNetwork: {}, listNetworks: {} } } } }
  AutogenView.methods.execAction.call(single, { api: 'restartNetwork', resource: { id: 'n' } }, false)
  expect(single.currentAction.networkRestartTargets.map(r => r.id)).toEqual(['n'])
})
