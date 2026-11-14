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

export function templateSettingsCanWrite (resource = {}, getters = {}) {
  const user = getters.userInfo || {}
  if (!resource.id || !user.id || !('updateTemplate' in (getters.apis || {}))) return false
  return user.roletype === 'Admin' || (resource.projectid
    ? resource.projectid === getters.project?.id
    : !getters.project?.id && resource.domainid === user.domainid && resource.account === user.account)
}
export function templateSettingRestriction (resource, name) {
  if (name.startsWith('extraconfig') || (resource.readonlydetails || '').split(',').map(v => v.trim()).includes(name)) return 'readonly'
  return ''
}
export function templateSettingsFingerprint (resource) {
  return JSON.stringify([resource.readonlydetails || '', Object.entries(resource.details || {}).sort(([a], [b]) => a.localeCompare(b))])
}
export function templateSettingsParams (resource, action, name, value) {
  name = String(name || '').trim()
  if (!name || (action !== 'delete' && !String(value ?? '').trim())) throw new Error('required')
  if (templateSettingRestriction(resource, name)) throw new Error('readonly')
  const details = { ...(resource.details || {}) }
  const exists = Object.prototype.hasOwnProperty.call(details, name)
  if ((action === 'add' && exists) || (action !== 'add' && !exists)) throw new Error(action === 'add' ? 'duplicate' : 'changed')
  if (action === 'delete') delete details[name]
  else details[name] = value
  // updateTemplate replaces the map. Preserve every unrelated entry, including
  // unchanged read-only values; only a genuinely empty map may use cleanup.
  return Object.keys(details).length
    ? Object.fromEntries([['id', resource.id], ...Object.entries(details).map(([key, val]) => ['details[0].' + key, val])])
    : { id: resource.id, cleanupdetails: true }
}
