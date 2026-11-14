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

import { fixture, copy, partial, completed } from '../../fixtures/vpcMigration'
import { vpcMigrationApis, vpcMigrationVisible, vpcMigrationOfferingCompatible, vpcTierMigrationCompatible, vpcMigrationReason, readVpcMigrationContext, vpcMigrationTargets, vpcTierMigrationTargets, buildVpcMigrationPlan, diagnoseVpcMigrationPlan, vpcMigrationParams, vpcMigrationPlanFingerprint, validVpcMigrationJob, vpcMigrationPreservation, vpcMigrationFingerprint, vpcMigrationSucceeded } from '@/utils/vpcMigration'
import { migrationTargetDiagnostic } from '@/utils/networkMigration'
import section from '@/config/section/network'
jest.mock('@/utils/networkMigration', () => ({ ...jest.requireActual('@/utils/networkMigration'), migrationTargetDiagnostic: jest.fn() }))
jest.mock('@/api', () => ({ getAPI: jest.fn(), postAPI: jest.fn() }))
const getters = { userInfo: { roletype: 'Admin', id: 'u' }, apis: Object.fromEntries(vpcMigrationApis.map(a => [a, {}])) }
const mappings = { t1: 'n2', t2: 'n2' }

test.each(['User', 'DomainAdmin', 'ResourceAdmin', 'ReadOnlyAdmin'])('%s cannot see migration', role => expect(vpcMigrationVisible(fixture().vpc, { ...getters, userInfo: { roletype: role } })).toBe(false))
test.each(vpcMigrationApis)('missing %s prevents migration', api => expect(vpcMigrationVisible(fixture().vpc, { ...getters, apis: { ...getters.apis, [api]: undefined } })).toBe(false))
test('capability removal and project mismatch hide action; root may operate an explicitly scoped owner', () => {
  for (const missing of vpcMigrationApis) expect(vpcMigrationVisible(fixture().vpc, { ...getters, apis: Object.fromEntries(vpcMigrationApis.filter(a => a !== missing).map(a => [a, {}])) })).toBe(false)
  expect(vpcMigrationVisible(fixture().vpc, getters)).toBe(true)
  expect(vpcMigrationVisible(fixture().vpc, { ...getters, project: { id: 'other' } })).toBe(false)
  expect(vpcMigrationVisible({ ...fixture().vpc, projectid: 'p' }, { ...getters, project: { id: 'p' } })).toBe(true)
  const action = section.children.find(s => s.name === 'vpc').actions.find(a => a.api === 'migrateVPC')
  expect(action).toMatchObject({ dataView: true, selfManagedDialog: true }); expect(action.groupAction).toBeUndefined()
})

test.each([{ redundantvpcrouter: undefined }, { redundantvpcrouter: true }, { state: 'Disabled' }, { internetprotocol: 'dualstack' }, { networkmode: 'ROUTED' }, { routingmode: 'Dynamic' }, { specifyasnumber: true }, { conservemode: true }, { zoneid: 'other' }, { domainid: 'other' }, { distributedvpcrouter: true }, { supportsregionLevelvpc: true }, { fornsx: true }])('unsafe VPC offering is excluded %j', change => {
  const c = fixture(); expect(vpcMigrationOfferingCompatible(c.offering, { ...c.offerings[1], ...change }, c.vpc)).toBe(false)
})
test('service/provider changes are excluded and only other VPC offerings are new targets', () => {
  const c = fixture(); expect(vpcMigrationTargets(c).map(o => o.id)).toEqual(['o2'])
  c.offerings[1].service[0].provider = [{ name: 'Nsx' }]; expect(vpcMigrationTargets(c)).toEqual([])
})
test.each([{ forvpc: false }, { state: 'Disabled' }, { guestiptype: 'Shared' }, { traffictype: 'Management' }, { specifyvlan: true }, { specifyipranges: true }, { conservemode: true }, { ispersistent: true }, { networkmode: 'ROUTED' }, { internetprotocol: 'dualstack' }, { issystem: true }])('unsafe Tier mapping is excluded %j', change => {
  const c = fixture(); expect(vpcTierMigrationCompatible(c.tiers[0].offering, { ...c.networkOfferings[1], ...change }, c.offerings[1], c.vpc)).toBe(false)
})
test('extra Firewall, provider and capability differences never become selectable mappings', () => {
  const c = fixture(); const o = copy(c.networkOfferings[1]); o.service.push({ name: 'Firewall', provider: [{ name: 'VpcVirtualRouter' }] })
  expect(vpcTierMigrationCompatible(c.tiers[0].offering, o, c.offerings[1], c.vpc)).toBe(false)
  o.service = copy(c.networkOfferings[1].service); o.service[0].capability = [{ name: 'Contract', value: 'changed' }]
  expect(vpcTierMigrationCompatible(c.tiers[0].offering, o, c.offerings[1], c.vpc)).toBe(false)
})
test('all Tier mappings are required; explicit false and complete Map parameters are sent', () => {
  const c = fixture(); expect(() => buildVpcMigrationPlan(c, 'o2', { t1: 'n2' })).toThrow('mapping')
  expect(() => buildVpcMigrationPlan(c, 'o2', { t1: 'n1', t2: 'n2' })).toThrow('mapping')
  const plan = buildVpcMigrationPlan(c, 'o2', mappings)
  expect(vpcMigrationParams(plan, 'job')).toEqual({ vpcid: 'v', vpcofferingid: 'o2', resume: false, customjobid: 'job', 'tiernetworkofferings[0].networkid': 't1', 'tiernetworkofferings[0].networkofferingid': 'n2', 'tiernetworkofferings[1].networkid': 't2', 'tiernetworkofferings[1].networkofferingid': 'n2' })
})
test('resume fixes destination VPC and applied Tier while permitting correction of remaining Tier', () => {
  const c = partial(fixture()); expect(vpcMigrationTargets(c).map(o => o.id)).toEqual(['o2'])
  expect(() => buildVpcMigrationPlan(c, 'o1', mappings)).toThrow('target')
  expect(() => buildVpcMigrationPlan(c, 'o2', { t1: 'n1', t2: 'n2' })).toThrow('mapping')
  expect(buildVpcMigrationPlan(c, 'o2', mappings).resume).toBe(true)
})
test.each(['disabled', 'ambiguous', 'not-vlan'])('unready %s physical networks are excluded', kind => {
  const c = fixture()
  if (kind === 'disabled') c.physical[0].state = 'Disabled'
  else if (kind === 'ambiguous') { c.physical.push({ ...c.physical[0], id: 'other' }); c.traffic.other = c.traffic.ph } else c.traffic.ph[0].isolationmethods = 'VXLAN'
  expect(vpcTierMigrationTargets(c, c.tiers[0], c.offerings[1])).toEqual([])
})
test.each(['privateGateways', 'vpnGateways', 'routes'])('configured %s is explicitly outside adopted scope', field => { const c = fixture(); c[field] = [{ id: 'configured' }]; expect(vpcMigrationReason(c)).toBe('advanced') })
test('hidden VPC and transitioning resources cannot start; pending unbound jobs block all mappings', () => {
  const c = fixture(); c.vpc.fordisplay = false; expect(vpcMigrationReason(c)).toBe('checkpoint'); c.vpc.fordisplay = true
  c.vms[0].state = 'Starting'; expect(vpcMigrationReason(c)).toBe('transition'); c.vms[0].state = 'Running'
  c.jobs = [{ jobid: 'unbound' }]; expect(() => buildVpcMigrationPlan(c, 'o2', mappings)).toThrow('pending')
})

function api (c) {
  return jest.fn(async (command, p) => {
    let rows; let field
    if (command === 'listVPCs') { rows = p.id === 'v' ? [c.vpc] : p.id === '41' && c.previous ? [c.previous] : []; field = 'vpc' } else if (command === 'listVPCOfferings') { rows = c.offerings; field = 'vpcoffering' } else if (command === 'listNetworkOfferings') { rows = c.networkOfferings; field = 'networkoffering' } else if (command === 'listPhysicalNetworks') { rows = c.physical; field = 'physicalnetwork' } else if (command === 'listTrafficTypes') { rows = c.traffic[p.physicalnetworkid]; field = 'traffictype' } else if (command === 'listNetworks') { rows = p.id ? c.tiers.map(r => r.source).filter(n => n?.id === p.id) : c.tiers.map(r => r.network).filter(n => n.vpcid === p.vpcid); field = 'network' } else if (command === 'listAsyncJobs') { rows = c.jobs; field = 'asyncjobs' } else if (command === 'listVirtualMachines') { rows = c.vms; field = 'virtualmachine' } else if (command === 'listRouters') { rows = c.routers; field = 'router' } else if (command === 'listPublicIpAddresses') { rows = c.publicIps; field = 'publicipaddress' } else if (command === 'listPrivateGateways') { rows = c.privateGateways; field = 'privategateway' } else if (command === 'listVpnGateways') { rows = c.vpnGateways; field = 'vpngateway' } else if (command === 'listStaticRoutes') { rows = c.routes; field = 'staticroute' } else throw new Error(command)
    return { [command.toLowerCase() + 'response']: { [field]: copy(rows), count: rows.length } }
  })
}
test('authenticated checkpoint reference resolves hidden VPC and unions both Tier parents', async () => {
  const c = partial(fixture()); const get = api(c); const fresh = await readVpcMigrationContext(get, c.vpc)
  expect(fresh.previous.id).toBe('old-vpc'); expect(fresh.tiers.map(r => r.locked)).toEqual([true, false])
  expect(get).toHaveBeenCalledWith('listVPCs', expect.objectContaining({ id: '41', fordisplay: false, ignoreproject: true }), expect.anything())
})
test.each(['reference', 'owner', 'visible', 'same-id', 'cidr'])('invalid checkpoint %s is rejected before writes', async kind => {
  const c = partial(fixture())
  if (kind === 'reference') c.vpc.tags[0].value = 'invalid'
  else if (kind === 'owner') c.previous.account = 'foreign'
  else if (kind === 'visible') c.previous.fordisplay = true
  else if (kind === 'same-id') c.previous.id = 'v'
  else c.previous.cidr = '10.9.0.0/16'
  await expect(readVpcMigrationContext(api(c), c.vpc)).rejects.toThrow('checkpoint')
})
test('read errors and mismatched owner are surfaced; actual project is scoped but Root jobs are not', async () => {
  const c = fixture(); c.vpc.projectid = 'p'; c.tiers.forEach(r => { r.network.projectid = 'p' })
  const get = api(c); await readVpcMigrationContext(get, c.vpc)
  expect(get.mock.calls.filter(x => ['listVPCs', 'listNetworks', 'listVirtualMachines'].includes(x[0])).every(x => x[1].projectid === 'p' && x[1].ignoreproject)).toBe(true)
  expect(get.mock.calls.find(x => x[0] === 'listAsyncJobs')[1].projectid).toBeUndefined()
  await expect(readVpcMigrationContext(api(c), { ...c.vpc, account: 'wrong' })).rejects.toThrow('scope')
  await expect(readVpcMigrationContext(jest.fn().mockRejectedValue(new Error('Read failed')), c.vpc)).rejects.toThrow('Read failed')
})
test('provider diagnosis checks every Tier and rejects a changed physical destination', async () => {
  const c = fixture(); const plan = buildVpcMigrationPlan(c, 'o2', mappings)
  migrationTargetDiagnostic.mockReset().mockResolvedValue({ network: { id: 'ph', isolation: 'vlan' } })
  await diagnoseVpcMigrationPlan(jest.fn(), getters, c, plan); expect(migrationTargetDiagnostic).toHaveBeenCalledTimes(2)
  migrationTargetDiagnostic.mockResolvedValue({ network: { id: 'wrong', isolation: 'vlan' } })
  await expect(diagnoseVpcMigrationPlan(jest.fn(), getters, c, plan)).rejects.toThrow('provider')
})
test('job receipts bind caller and command even when the deployed API omits instance metadata', () => {
  const job = { jobid: 'j', userid: 'u', cmd: 'org.apache.cloudstack.api.command.admin.network.MigrateVPCCmd', jobstatus: 1 }
  expect(validVpcMigrationJob(job, 'j', 'v', 'u')).toBe(true)
  for (const change of [{ jobid: 'wrong' }, { userid: 'foreign' }, { cmd: 'DeleteVPCCmd' }, { jobinstanceid: 'other' }, { jobinstancetype: 'Network' }, { jobstatus: 3 }]) expect(validVpcMigrationJob({ ...job, ...change }, 'j', 'v', 'u')).toBe(false)
})
test('success requires every Tier, canonical VM NIC membership, public IP and checkpoint cleanup', () => {
  const c = fixture(); const plan = buildVpcMigrationPlan(c, 'o2', mappings); const original = vpcMigrationPreservation(c); const done = completed(c, plan)
  expect(vpcMigrationSucceeded(done, plan, original)).toBe(true)
  for (const change of [x => x.tiers.pop(), x => { x.tiers[0].network.related = 'hidden' }, x => { x.vms[0].nic[0].networkid = 't2' }, x => { x.vms[0].nic[0].macaddress = 'changed' }, x => x.publicIps.pop(), x => { x.checkpoint = '41' }, x => { x.vpc.account = 'foreign' }]) {
    const bad = copy(done); change(bad); expect(vpcMigrationSucceeded(bad, plan, original)).toBe(false)
  }
})
test('fresh-review fingerprints include Tier state, owner, source contract and selected offering changes', () => {
  const c = fixture(); const original = vpcMigrationFingerprint(c); const plan = buildVpcMigrationPlan(c, 'o2', mappings); const target = vpcMigrationPlanFingerprint(c, plan)
  c.networkOfferings[1].networkrate = 123; expect(vpcMigrationPlanFingerprint(c, plan)).not.toBe(target)
  c.tiers[0].network.state = 'Shutdown'; expect(vpcMigrationFingerprint(c)).not.toBe(original)
})
