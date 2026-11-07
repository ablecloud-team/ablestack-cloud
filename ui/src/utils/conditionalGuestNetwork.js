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

import { hasVpc } from './networkTabs'

const guestTypes = ['Isolated', 'L2', 'Shared']
const lists = {
  'bgp.peers': 'listBgpPeers',
  'routing.firewall': 'listRoutingFirewallRules',
  'ip.v6.firewall': 'listIpv6FirewallRules',
  'network.policy': 'listTungstenFabricPolicy',
  'tungsten.logical.router': 'listTungstenFabricLogicalRouter',
  'vnf.appliances': 'listVnfAppliances',
  'custom.actions': 'listCustomActions'
}
export const conditionalGuestTab = key => Object.prototype.hasOwnProperty.call(lists, key)
export const isRoutedGuestNetwork = network => ['Static', 'Dynamic'].includes(network.ip4routing)
export const hasNetworkService = (network, service) => Array.isArray(network.service) && network.service.some(row => row?.name === service)

function hasOwnerScope (network, user, project) {
  if (user.roletype === 'Admin') return true
  if (network.projectid && project?.id === network.projectid) return true
  // Domain administrator visibility and scope have already been checked by listNetworks.
  if (user.roletype === 'DomainAdmin') return true
  return !network.projectid && !!user.domainid && network.domainid === user.domainid && !!user.account && user.account === network.account
}

export function guestFeatureStatus (key, network = {}, getters = {}, user = getters.userInfo || {}) {
  if (!conditionalGuestTab(key) || !network.id || !guestTypes.includes(network.type) ||
      (network.traffictype && network.traffictype !== 'Guest')) return 'unsupported'
  const standalone = network.type === 'Isolated' && !hasVpc(network)
  const tungsten = ['Isolated', 'L2'].includes(network.type) && network.broadcasturi === 'tf://tf'
  const contract = {
    'bgp.peers': [standalone && network.ip4routing === 'Dynamic', user.roletype === 'Admin'],
    'routing.firewall': [standalone && isRoutedGuestNetwork(network), hasOwnerScope(network, user, getters.project)],
    'ip.v6.firewall': [standalone && ['IPv6', 'DualStack'].includes(network.internetprotocol) && network.ip6firewall !== false && network.ip6firewall !== 'false', hasOwnerScope(network, user, getters.project)],
    'network.policy': [tungsten && !!network.zoneid, hasOwnerScope(network, user, getters.project)],
    'tungsten.logical.router': [tungsten && !!network.zoneid, hasOwnerScope(network, user, getters.project)],
    // This is an association lookup. An empty result is valid for any guest type.
    'vnf.appliances': [true, true],
    'custom.actions': [hasNetworkService(network, 'CustomAction'), hasOwnerScope(network, user, getters.project)]
  }
  const [supported, allowed] = contract[key]
  if (!supported) return 'unsupported'
  if (!allowed || !(lists[key] in (getters.apis || {}))) return 'forbidden'
  return 'available'
}

export const showGuestFeature = (key, network, getters, user) => guestFeatureStatus(key, network, getters, user) === 'available'
export const canWriteGuestFeature = (key, command, network, getters, user) =>
  showGuestFeature(key, network, getters, user) && command in (getters.apis || {}) && !['Destroy', 'Shutdown', 'Expunging', 'Migrating'].includes(network.state)
