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

export const ACTION_MENU_GROUPS = [
  { key: 'ACCESS', label: 'label.access' },
  { key: 'COMPUTE', label: 'label.compute' },
  { key: 'NETWORK', label: 'label.network' },
  { key: 'STORAGE', label: 'label.storage' },
  { key: 'BACKUP', label: 'label.backup' },
  { key: 'MANAGEMENT', label: 'label.management' },
  { key: 'GENERAL', label: 'label.actions' },
  { key: 'DANGER', label: 'label.delete' }
]

const GROUP_MATCHERS = [
  ['ACCESS', /(console|portal|ssh|password|keypair|apikey|permission|acl|role|account|user|login|outofband|oobm)/],
  ['BACKUP', /(backup|restore|recovery|schedule)/],
  ['NETWORK', /(network|nic|ipaddress|publicip|firewall|vpn|portforward|loadbalanc|gateway|route|vlan|asn)/],
  ['STORAGE', /(volume|snapshot|disk|storage|template|iso|image|resize|expand|attachvolume|detachvolume)/],
  ['COMPUTE', /(virtualmachine|systemvm|router|instance|deploy|start|stop|reboot|restart|reinstall|migrate|scale|affinity)/],
  ['MANAGEMENT', /(update|edit|change|configure|reset|sync|enable|disable|attach|detach|manage|assign|upload|download)/]
]

const DANGER_PATTERN = /(delete|destroy|expunge|remove|revoke|release|terminate|unmanage|disableaccount|disableuser)/

function actionSearchText (action) {
  return [action.api, action.label, action.icon, action.key]
    .filter(value => typeof value === 'string')
    .join(' ')
    .toLowerCase()
}

export function isDangerAction (action) {
  if (typeof action.danger === 'boolean') {
    return action.danger
  }
  return DANGER_PATTERN.test(actionSearchText(action))
}

export function resolveActionMenuGroup (action) {
  const explicitGroup = String(action.menuGroup || '').toUpperCase()
  if (ACTION_MENU_GROUPS.some(group => group.key === explicitGroup)) {
    return explicitGroup
  }
  if (isDangerAction(action)) {
    return 'DANGER'
  }
  const text = actionSearchText(action)
  const match = GROUP_MATCHERS.find(([, matcher]) => matcher.test(text))
  return match ? match[0] : 'GENERAL'
}

export function groupActionMenuEntries (entries) {
  return ACTION_MENU_GROUPS
    .map(group => ({
      ...group,
      entries: entries.filter(entry => entry.group === group.key)
    }))
    .filter(group => group.entries.length > 0)
}
