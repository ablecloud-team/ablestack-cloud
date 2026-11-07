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

import { hasVpc } from '@/utils/networkTabs'
import { readDiagnosticList, diagnoseNetworkOffering } from '@/utils/networkOfferingDiagnostics'
import { networkOfferingFingerprint } from '@/utils/networkOfferingChange'

export const migrationApis = ['migrateNetwork', 'listNetworks', 'listNetworkOfferings', 'listPhysicalNetworks', 'listTrafficTypes', 'listNetworkServiceProviders', 'listVirtualRouterElements', 'listVirtualMachines', 'listRouters', 'listPublicIpAddresses', 'listAsyncJobs', 'queryAsyncJobResult']
const readOptions = { preserveOnFailure: true, timeout: 20000 }
const fail = reason => { throw new Error('message.network.migration.' + reason) }
const tags = value => (Array.isArray(value) ? value : String(value || '').split(',')).map(s => s.trim()).filter(Boolean)
const services = offering => (offering?.service || []).flatMap(s => (s.provider || []).map(p => s.name + ':' + p.name)).sort()
const vpcOffering = offering => offering?.forvpc === true || offering?.forvpc === 'true'

export function migrationVisible (network, getters) {
  return getters?.userInfo?.roletype === 'Admin' && migrationApis.every(api => api in (getters.apis || {})) &&
    network?.type === 'Isolated' && !hasVpc(network) && !network.ip4routing && network.traffictype === 'Guest'
}

export function migrationNetworkReason (network) {
  if (!network?.id || !network.physicalnetworkid || !network.networkofferingid || !network.related) return 'read.failed'
  if (network.type !== 'Isolated' || hasVpc(network) || network.ip4routing || network.traffictype !== 'Guest') return 'unsupported'
  if (!['Implemented', 'Setup', 'Allocated'].includes(network.state)) return 'state'
  return ''
}

export function migrationCheckpoint (network, previous) {
  if (network.related === network.id) return false
  if (!previous || previous.id !== network.related || previous.related !== network.id || previous.displaynetwork !== false ||
    previous.account !== network.account || previous.domainid !== network.domainid || previous.projectid !== network.projectid || previous.zoneid !== network.zoneid || previous.type !== 'Isolated') fail('checkpoint')
  return true
}

export function resolveMigrationPhysical (offering, physical, traffic) {
  let eligible = physical.filter(p => (traffic[p.id] || []).some(t => t.traffictype === (offering.traffictype || 'Guest')))
  if (eligible.length > 1) eligible = eligible.filter(p => offering.tags ? tags(p.tags).includes(offering.tags) : !tags(p.tags).length)
  return eligible.length === 1 ? eligible[0] : null
}

export function migrationOfferingCompatible (current, offering) {
  // This conservative UI scope covers standard VirtualRouter networks. It is
  // independent of updateNetwork's fornetworkid candidate list; POST validation
  // remains authoritative for migration, IP usage and provider implementation.
  if (!current || !offering || offering.state !== 'Enabled' || vpcOffering(offering) || offering.issystem || offering.guestiptype !== 'Isolated' || offering.traffictype !== 'Guest') return false
  if ((offering.networkmode || 'NATTED') !== 'NATTED' || (current.networkmode || 'NATTED') !== 'NATTED') return false
  if ((current.internetprotocol || 'IPv4') !== (offering.internetprotocol || 'IPv4')) return false
  if (current.specifyipranges !== offering.specifyipranges || current.specifyvlan !== offering.specifyvlan || current.supportsinternallb !== offering.supportsinternallb || current.supportspublicaccess !== offering.supportspublicaccess) return false
  const providers = services(offering)
  return providers.length > 0 && providers.every(s => s.endsWith(':VirtualRouter')) && JSON.stringify(providers) === JSON.stringify(services(current))
}

export function migrationFingerprint (context) {
  const network = n => n && [n.id, n.related, n.state, n.physicalnetworkid, n.networkofferingid, n.account, n.domainid, n.projectid, n.cidr, n.gateway, n.dns1, n.dns2]
  return JSON.stringify({ network: network(context.network), previous: network(context.previous), offering: networkOfferingFingerprint(context.offering), routers: (context.routers || []).map(r => [r.id, r.state, r.migrationnetworkid]).sort(), vms: context.vms.map(v => [v.id, v.state, v.nic.filter(n => [context.network.id, context.previous?.id].includes(n.networkid)).map(n => [n.id, n.networkid, n.ipaddress, n.macaddress]).sort()]).sort() })
}

export async function readMigrationJobs (get, networkId) {
  const rows = []; const seen = new Set()
  for (let page = 1; page <= 100; page++) {
    const json = await get('listAsyncJobs', { resourceid: networkId, resourcetype: 'Network', keyword: 'MigrateNetworkCmd', includecompleted: true, listall: true, ignoreproject: true, page, pagesize: 500 }, readOptions)
    const response = json?.listasyncjobsresponse
    if (!response || response.errorcode || !Array.isArray(response.asyncjobs || [])) fail('read.failed')
    const next = response.asyncjobs || []
    for (const row of next) {
      if (!row.jobid || seen.has(row.jobid) || row.jobinstanceid !== networkId || String(row.jobinstancetype).toLowerCase() !== 'network' || !String(row.cmd).endsWith('.MigrateNetworkCmd')) fail('job.scope')
      seen.add(row.jobid); rows.push(row)
    }
    if (response.count !== undefined ? rows.length >= response.count : next.length < 500) return rows.reverse().sort((a, b) => (Date.parse(b.created) || 0) - (Date.parse(a.created) || 0))
    if (!next.length) fail('read.failed')
  }
  fail('read.failed')
}

export async function readMigrationContext (get, networkId, projectId) {
  // Resource reads follow the actual network's project. Migration jobs belong
  // to the Root Admin caller, so do not inherit the UI's current project filter.
  const read = (command, params, options) => get(command, { ...params, ignoreproject: true, ...(['listNetworks', 'listVirtualMachines', 'listRouters', 'listPublicIpAddresses'].includes(command) && projectId ? { projectid: projectId } : {}) }, options)
  const networks = await readDiagnosticList(read, 'listNetworks', { id: networkId, listall: true }, 'network')
  const network = networks.find(n => n.id === networkId)
  if (!network) fail('read.failed')
  const previous = network.related && network.related !== network.id
    ? (await readDiagnosticList(read, 'listNetworks', { id: network.related, listall: true, displaynetwork: false }, 'network')).find(n => n.id === network.related) : null
  const checkpoint = migrationCheckpoint(network, previous)
  const [source, offerings, physical, jobs] = await Promise.all([
    readDiagnosticList(read, 'listNetworkOfferings', { id: network.networkofferingid }, 'networkoffering'),
    readDiagnosticList(read, 'listNetworkOfferings', { zoneid: network.zoneid, state: 'Enabled', guestiptype: 'Isolated', traffictype: 'Guest' }, 'networkoffering'),
    readDiagnosticList(read, 'listPhysicalNetworks', { zoneid: network.zoneid }, 'physicalnetwork'),
    readMigrationJobs(read, network.id)
  ])
  const offering = source.find(o => o.id === network.networkofferingid)
  if (!offering) fail('read.failed')
  const traffic = Object.fromEntries(await Promise.all(physical.map(async p => [p.id, await readDiagnosticList(read, 'listTrafficTypes', { physicalnetworkid: p.id }, 'traffictype')])))
  const ids = [network.id, ...(previous ? [previous.id] : [])]
  const lists = await Promise.all(ids.map(id => readDiagnosticList(read, 'listVirtualMachines', { networkid: id, listall: true }, 'virtualmachine')))
  const vms = [...new Map(lists.flat().map(v => [v.id, v])).values()]
  const routers = (await Promise.all(ids.map(async id => (await readDiagnosticList(read, 'listRouters', { networkid: id, listall: true }, 'router')).map(r => ({ ...r, migrationnetworkid: id }))))).flat()
  const publicIps = (await Promise.all(ids.map(async id => (await readDiagnosticList(read, 'listPublicIpAddresses', { associatednetworkid: id, listall: true }, 'publicipaddress')).map(p => ({ ...p, migrationnetworkid: id }))))).flat()
  const candidates = offerings.filter(o => migrationOfferingCompatible(offering, o)).map(o => ({ offering: o, physical: resolveMigrationPhysical(o, physical, traffic) }))
    .filter(c => c.physical?.state === 'Enabled' && c.physical.id !== network.physicalnetworkid)
  const pending = jobs.filter(j => j.jobstatus === 0)
  if (pending.length > 1) fail('jobs.multiple')
  return { network, previous, checkpoint, offering, physical, traffic, vms, routers, publicIps, candidates, jobs, job: pending[0] || jobs[0] || null }
}

export async function migrationTargetDiagnostic (get, getters, context, offering) {
  const result = await diagnoseNetworkOffering((command, params, options) => get(command, { ...params, ignoreproject: true }, options), getters.apis, { zoneId: context.network.zoneid, offering })
  if (result.status !== 'ready' || result.blocked) fail('provider')
  return result
}

export function migrationParams (context, target) {
  const reason = migrationNetworkReason(context.network)
  if (reason) fail(reason)
  if (context.jobs.some(j => j.jobstatus === 0)) fail('job.pending')
  if (context.vms.some(v => !['Running', 'Stopped'].includes(v.state))) fail('vm.state')
  if (context.routers?.some(r => !['Running', 'Stopped'].includes(r.state))) fail('router.state')
  if (context.checkpoint) {
    if (target?.offering.id !== context.network.networkofferingid || target.physical.id !== context.network.physicalnetworkid) fail('destination')
  } else if (!context.candidates.some(c => c.offering.id === target?.offering.id && c.physical.id === target?.physical.id)) fail('destination')
  return { networkid: context.network.id, networkofferingid: target.offering.id, resume: context.checkpoint }
}

export async function queryMigrationJob (get, networkId, jobId) {
  const response = (await get('queryAsyncJobResult', { jobid: jobId, ignoreproject: true }, { ...readOptions, backgroundJob: true }))?.queryasyncjobresultresponse
  if (!response || response.jobid !== jobId || response.jobinstanceid !== networkId || String(response.jobinstancetype).toLowerCase() !== 'network' || ![0, 1, 2].includes(response.jobstatus)) fail('job.scope')
  return response
}

export function migrationSucceeded (context, expected) {
  return !context.checkpoint && context.network.related === context.network.id &&
    context.network.networkofferingid === expected.offering.id && context.network.physicalnetworkid === expected.physical.id &&
    ['Implemented', 'Setup', 'Allocated'].includes(context.network.state)
}
