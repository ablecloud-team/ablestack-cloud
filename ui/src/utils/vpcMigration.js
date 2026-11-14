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

import { networkCollection } from '@/utils/networkLifecycle'
import { migrationCheckpoint, resolveMigrationPhysical, migrationTargetDiagnostic } from '@/utils/networkMigration'
import { networkOfferingFingerprint } from '@/utils/networkOfferingChange'
import { vpcSupportsTierOffering } from '@/utils/networkOfferingDiagnostics'
import { sameVpcProviders, vpcServiceProviderMap } from '@/utils/vpcOfferingProviders'

export const vpcMigrationApis = ['migrateVPC', 'listVPCs', 'listVPCOfferings', 'listNetworks', 'listNetworkOfferings', 'listPhysicalNetworks', 'listTrafficTypes', 'listNetworkServiceProviders', 'listVirtualRouterElements', 'listVirtualMachines', 'listRouters', 'listPublicIpAddresses', 'listPrivateGateways', 'listVpnGateways', 'listStaticRoutes', 'listAsyncJobs', 'queryAsyncJobResult']
const fail = key => { throw new Error('message.vpc.migration.' + key) }
const truth = value => value === true || value === 'true'
const owner = v => JSON.stringify([v.account, v.domainid, v.projectid || '', v.zoneid])
const sorted = rows => [...rows].sort((a, b) => String(a[0]).localeCompare(String(b[0])))
const pairServices = offering => (offering?.service || []).map(s => [s.name, (s.provider || []).map(p => p.name).sort(), (s.capability || []).map(c => [c.name, c.value]).sort()]).sort((a, b) => a[0].localeCompare(b[0]))
const scopeAllows = (offering, vpc) => (!offering.zoneid || String(offering.zoneid).split(',').includes(vpc.zoneid)) && (!offering.domainid || String(offering.domainid).split(',').includes(vpc.domainid))

export function vpcMigrationVisible (vpc, getters) {
  return !!vpc?.id && getters?.userInfo?.roletype === 'Admin' && (!getters.project?.id || getters.project.id === vpc.projectid) && vpcMigrationApis.every(a => !!getters.apis?.[a])
}

export function vpcMigrationIdentity (vpc) {
  return JSON.stringify([vpc.id, vpc.name, vpc.zoneid, vpc.account, vpc.domainid, vpc.projectid || '', vpc.cidr, vpc.networkdomain, vpc.dns1, vpc.dns2, vpc.publicmtu, vpc.keepmacaddressonpublicnic])
}

function standardVpcOffering (offering) {
  return offering?.redundantvpcrouter === false && !truth(offering.distributedvpcrouter) && !truth(offering.supportsregionLevelvpc) && !truth(offering.fornsx) &&
    !truth(offering.specifyasnumber) && (offering.internetprotocol || 'IPv4') === 'IPv4' && (offering.networkmode || 'NATTED') === 'NATTED' && (offering.routingmode || 'Static') === 'Static' &&
    offering.service?.some(s => s.name === 'SourceNat') && offering.service.every(s => s.provider?.length && s.provider.every(p => p.name === 'VpcVirtualRouter' || (s.name === 'Lb' && p.name === 'InternalLbVm')))
}

export function vpcMigrationOfferingCompatible (current, offering, vpc) {
  return standardVpcOffering(current) && standardVpcOffering(offering) && offering.state === 'Enabled' && scopeAllows(offering, vpc) &&
    truth(current.conservemode) === truth(offering.conservemode) && sameVpcProviders(vpcServiceProviderMap(current.service), vpcServiceProviderMap(offering.service))
}

export function vpcTierMigrationCompatible (current, offering, targetVpc, vpc) {
  if (!current || !offering || offering.state !== 'Enabled' || !truth(offering.forvpc) || truth(offering.issystem) || offering.guestiptype !== 'Isolated' || offering.traffictype !== 'Guest' || !scopeAllows(offering, vpc)) return false
  if ([current, offering].some(o => (o.networkmode || 'NATTED') !== 'NATTED' || (o.internetprotocol || 'IPv4') !== 'IPv4')) return false
  if (['specifyipranges', 'specifyvlan', 'supportsinternallb', 'supportspublicaccess', 'conservemode', 'ispersistent'].some(k => truth(current[k]) !== truth(offering[k]))) return false
  return offering.service?.length > 0 && offering.service.every(s => s.provider?.length && s.provider.every(p => p.name === 'VpcVirtualRouter')) &&
    JSON.stringify(pairServices(current)) === JSON.stringify(pairServices(offering)) && vpcSupportsTierOffering(targetVpc, offering)
}

export function vpcMigrationReason (context) {
  const v = context?.vpc
  if (!v?.id || !context.offering) return 'lookup'
  if (v.fordisplay === false) return 'checkpoint'
  if (v.state !== 'Enabled' || v.restartrequired) return 'state'
  if (truth(v.redundantvpcrouter) || truth(v.distributedvpcrouter) || truth(v.regionlevelvpc) || !standardVpcOffering(context.offering)) return context.offering.redundantvpcrouter === undefined ? 'metadata' : 'unsupported'
  if (context.privateGateways.length || context.vpnGateways.length || context.routes.length) return 'advanced'
  if (context.tiers.some(r => !['Allocated', 'Setup', 'Implemented'].includes(r.network.state))) return 'tier.state'
  if (context.tiers.some(r => r.network.type !== 'Isolated' || r.network.traffictype !== 'Guest' || r.network.ip4routing || !r.network.physicalnetworkid)) return 'unsupported'
  if (context.vms.some(v => !['Running', 'Stopped'].includes(v.state)) || context.routers.some(r => !['Running', 'Stopped'].includes(r.state))) return 'transition'
  return ''
}

export async function readVpcMigrationContext (get, resource) {
  const scope = { listall: true, ignoreproject: true, ...(resource.projectid ? { projectid: resource.projectid } : {}) }
  const list = (api, field, params = {}, key = 'id') => networkCollection(get, api, field, { ...scope, ...params }, key)
  const visible = await list('listVPCs', 'vpc', { id: resource.id })
  const vpc = visible.find(v => v.id === resource.id)
  if (visible.length !== 1 || !vpc || owner(vpc) !== owner(resource)) fail('scope')
  const checkpoints = (vpc.tags || []).filter(t => t.key === 'migration')
  if (checkpoints.length > 1) fail('checkpoint')
  let previous = null
  if (checkpoints.length) {
    // The API exposes the checkpoint's previous VPC as a numeric reference.
    // Resolve it through the normal authenticated API, never through a DB call.
    if (!/^[1-9]\d*$/.test(checkpoints[0].value)) fail('checkpoint')
    const rows = await list('listVPCs', 'vpc', { id: checkpoints[0].value, fordisplay: false })
    previous = rows[0]
    if (rows.length !== 1 || previous.id === vpc.id || previous.fordisplay !== false || owner(previous) !== owner(vpc) || previous.name !== vpc.name || previous.cidr !== vpc.cidr || previous.networkdomain !== vpc.networkdomain) fail('checkpoint')
  }
  const ids = [vpc.id, ...(previous ? [previous.id] : [])]
  const [offerings, networkOfferings, physical, pending, tierLists, vmLists, routerLists, ipLists, privateLists, vpnLists, routeLists] = await Promise.all([
    list('listVPCOfferings', 'vpcoffering'), list('listNetworkOfferings', 'networkoffering'),
    list('listPhysicalNetworks', 'physicalnetwork', { zoneid: vpc.zoneid, projectid: undefined }),
    networkCollection(get, 'listAsyncJobs', 'asyncjobs', { listall: true, ignoreproject: true }, 'jobid'),
    Promise.all(ids.map(vpcid => list('listNetworks', 'network', { vpcid, displaynetwork: true }))),
    Promise.all(ids.map(vpcid => list('listVirtualMachines', 'virtualmachine', { vpcid }))),
    Promise.all(ids.map(vpcid => list('listRouters', 'router', { vpcid }))),
    Promise.all(ids.map(vpcid => list('listPublicIpAddresses', 'publicipaddress', { vpcid }))),
    Promise.all(ids.map(vpcid => list('listPrivateGateways', 'privategateway', { vpcid }))),
    Promise.all(ids.map(vpcid => list('listVpnGateways', 'vpngateway', { vpcid }))),
    Promise.all(ids.map(vpcid => list('listStaticRoutes', 'staticroute', { vpcid })))
  ])
  const offering = offerings.find(o => o.id === vpc.vpcofferingid)
  if (!offering) fail('lookup')
  const traffic = Object.fromEntries(await Promise.all(physical.map(async p => [p.id, await list('listTrafficTypes', 'traffictype', { physicalnetworkid: p.id, projectid: undefined })])))
  const tiers = []
  for (const network of tierLists.flat()) {
    if (!ids.includes(network.vpcid) || owner(network) !== owner(vpc) || tiers.some(r => r.network.id === network.id)) fail('scope')
    let source = null
    if (network.related !== network.id) {
      source = (await list('listNetworks', 'network', { id: network.related, displaynetwork: false }))[0]
      migrationCheckpoint(network, source)
      if (!source || !ids.includes(source.vpcid)) fail('checkpoint')
    }
    const sourceOffering = networkOfferings.find(o => o.id === network.networkofferingid)
    if (!sourceOffering) fail('lookup')
    tiers.push({ network, source, offering: sourceOffering, locked: !!previous && network.vpcid === vpc.id })
  }
  const unique = lists => [...new Map(lists.flat().map(x => [x.id, x])).values()]
  const jobs = pending.filter(j => j.jobstatus === 0 && (String(j.jobinstancetype).toLowerCase() === 'vpc' || /\.(?:Migrate|Restart|Delete|Create)VPCCmd$/.test(j.cmd || '')))
  return {
    vpc,
    previous,
    checkpoint: checkpoints[0]?.value || '',
    offering,
    offerings,
    networkOfferings,
    physical,
    traffic,
    tiers,
    jobs,
    vms: unique(vmLists),
    routers: unique(routerLists),
    publicIps: unique(ipLists),
    privateGateways: unique(privateLists),
    vpnGateways: unique(vpnLists),
    routes: unique(routeLists)
  }
}

export function vpcMigrationTargets (context) {
  return context.previous ? [context.offering] : context.offerings.filter(o => o.id !== context.offering.id && vpcMigrationOfferingCompatible(context.offering, o, context.vpc))
}

export function vpcTierMigrationTargets (context, row, target) {
  return context.networkOfferings.filter(o => (row.locked ? o.id === row.network.networkofferingid : o.id !== row.network.networkofferingid) && vpcTierMigrationCompatible(row.offering, o, target, context.vpc))
    .map(offering => ({ offering, physical: resolveMigrationPhysical(offering, context.physical, context.traffic) }))
    .filter(t => t.physical?.state === 'Enabled' && (context.traffic[t.physical.id] || []).some(t => t.traffictype === 'Guest' && String(t.isolationmethods || '').toLowerCase() === 'vlan'))
}

export function buildVpcMigrationPlan (context, targetId, mappings) {
  const reason = vpcMigrationReason(context)
  if (reason) fail(reason)
  if (context.jobs.length) fail('pending')
  const target = vpcMigrationTargets(context).find(o => o.id === targetId)
  if (!target) fail('target')
  const rows = context.tiers.map(row => {
    const selected = vpcTierMigrationTargets(context, row, target).find(t => t.offering.id === mappings[row.network.id])
    if (!selected || (row.locked && selected.physical.id !== row.network.physicalnetworkid)) fail('mapping')
    return { id: row.network.id, offeringId: selected.offering.id, physicalId: selected.physical.id }
  })
  return { vpcId: context.vpc.id, offeringId: target.id, resume: !!context.previous, rows }
}

export async function diagnoseVpcMigrationPlan (get, getters, context, plan) {
  await Promise.all(plan.rows.map(async r => {
    const row = context.tiers.find(t => t.network.id === r.id)
    const offering = context.networkOfferings.find(o => o.id === r.offeringId)
    const diagnostic = await migrationTargetDiagnostic(get, getters, { network: row.network }, offering)
    if (diagnostic.network?.id !== r.physicalId || diagnostic.network.isolation !== 'vlan') fail('provider')
  }))
}

export function vpcMigrationParams (plan, jobId) {
  const params = { vpcid: plan.vpcId, vpcofferingid: plan.offeringId, resume: plan.resume, customjobid: jobId }
  plan.rows.forEach((r, i) => { params[`tiernetworkofferings[${i}].networkid`] = r.id; params[`tiernetworkofferings[${i}].networkofferingid`] = r.offeringId })
  return params
}

export function vpcMigrationPlanFingerprint (context, plan) {
  const target = context.offerings.find(o => o.id === plan.offeringId)
  return JSON.stringify([plan, target && [target.id, target.name, target.state, target.redundantvpcrouter, target.conservemode, target.zoneid, target.domainid, pairServices(target)],
    plan.rows.map(r => networkOfferingFingerprint(context.networkOfferings.find(o => o.id === r.offeringId)))])
}

export function validVpcMigrationJob (job, id, vpcId, userId) {
  return job?.jobid === id && job.userid === userId && /\.MigrateVPCCmd$/.test(job.cmd || '') && [0, 1, 2].includes(job.jobstatus) && (!job.jobinstanceid || job.jobinstanceid === vpcId) && (!job.jobinstancetype || String(job.jobinstancetype).toLowerCase() === 'vpc')
}

export function vpcMigrationPreservation (context) {
  const ids = context.tiers.flatMap(r => [r.network.id, r.source?.id]).filter(Boolean)
  const canonicalTier = id => context.tiers.find(r => [r.network.id, r.source?.id].includes(id))?.network.id || id
  return {
    identity: vpcMigrationIdentity(context.vpc),
    routersWereRunning: context.routers.some(r => r.state === 'Running'),
    tiers: sorted(context.tiers.map(r => [r.network.id, r.network.cidr, r.network.gateway, r.network.netmask, r.network.networkaclid, r.network.aclid, r.network.dns1, r.network.dns2, r.network.networkdomain])),
    nics: sorted(context.vms.flatMap(v => (v.nic || []).filter(n => ids.includes(n.networkid)).map(n => [n.id, v.id, v.state, n.ipaddress, n.macaddress, canonicalTier(n.networkid)]))),
    ips: sorted(context.publicIps.map(p => [p.id, p.ipaddress, p.issourcenat, p.isstaticnat, canonicalTier(p.associatednetworkid)]))
  }
}

export function vpcMigrationFingerprint (context) {
  return JSON.stringify({
    preserved: vpcMigrationPreservation(context),
    checkpoint: context.checkpoint,
    previous: context.previous?.id,
    offering: [context.vpc.vpcofferingid, context.offering.redundantvpcrouter, context.offering.conservemode, pairServices(context.offering)],
    tiers: sorted(context.tiers.map(r => [r.network.id, r.network.vpcid, r.network.state, r.network.related, r.network.physicalnetworkid, networkOfferingFingerprint(r.offering)])),
    routers: sorted(context.routers.map(r => [r.id, r.state])),
    dependencies: [context.privateGateways, context.vpnGateways, context.routes].map(rows => sorted(rows.map(r => [r.id, r.state]))),
    physical: context.physical.map(p => [p.id, p.state, p.tags, context.traffic[p.id]]).sort()
  })
}

export function vpcMigrationSucceeded (context, plan, original) {
  if (context.previous || context.checkpoint || context.vpc.vpcofferingid !== plan.offeringId || vpcMigrationIdentity(context.vpc) !== original.identity || vpcMigrationReason(context)) return false
  const now = vpcMigrationPreservation(context)
  if (JSON.stringify(now.tiers) !== JSON.stringify(original.tiers) || JSON.stringify(now.nics) !== JSON.stringify(original.nics) || JSON.stringify(now.ips) !== JSON.stringify(original.ips)) return false
  if (original.routersWereRunning && !context.routers.some(r => r.state === 'Running')) return false
  return context.tiers.length === plan.rows.length && plan.rows.every(p => context.tiers.some(r => r.network.id === p.id && r.network.vpcid === plan.vpcId && r.network.related === r.network.id && r.network.networkofferingid === p.offeringId && r.network.physicalnetworkid === p.physicalId))
}
