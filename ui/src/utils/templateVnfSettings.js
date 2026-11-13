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

export const VNF_DETAIL_KEYS = Object.freeze(['access_methods', 'username', 'password', 'ssh_user', 'ssh_password', 'ssh_port', 'web_user', 'web_password', 'http_path', 'http_port', 'https_path', 'https_port', 'version', 'vendor', 'maintainer'])
export const isVnfSecret = key => /password/i.test(key)
export const normalizeVnfKey = key => String(key || '').trim().toLowerCase()
export function vnfWriteAllowed (resource = {}, user = {}, project = {}, apis = {}) {
  if (resource.templatetype !== 'VNF' || !resource.id || !user.id || !('updateVnfTemplate' in apis)) return false
  if (user.roletype === 'Admin') return true
  if (resource.projectid) return resource.projectid === project.id
  if (project.id) return false
  if (user.domainid && user.domainid === resource.domainid && user.account && user.account === resource.account) return true
  if (user.roletype !== 'DomainAdmin') return false
  if (user.domainid && user.domainid === resource.domainid) return true
  const parent = user.domainpath && (user.domainpath.endsWith('/') ? user.domainpath : user.domainpath + '/')
  return !!(parent && resource.domainpath && resource.domainpath.startsWith(parent))
}
export function vnfDetailError (key, value, options) {
  key = normalizeVnfKey(key)
  if (!VNF_DETAIL_KEYS.includes(key) || !Object.prototype.hasOwnProperty.call(options, key)) return 'message.vnf.detail.key.invalid'
  if (typeof value !== 'string' || !value.trim()) return 'message.vnf.detail.value.required'
  if (/_port$/.test(key) && (!/^\d+$/.test(value) || Number(value) < 1 || Number(value) > 65535)) return 'message.vnf.detail.port.invalid'
  if (key === 'access_methods' && value.split(',').some(v => !options[key].includes(v.trim().toLowerCase()))) return 'message.vnf.detail.method.invalid'
  return ''
}
export function vnfNicError (rows) {
  const ids = rows.map(n => Number(n.deviceid)); let optional = false
  if (ids.some(id => !Number.isInteger(id) || id < 0 || id > 2147483647) || new Set(ids).size !== rows.length) return 'message.vnf.nic.ids.invalid'
  for (const [index, nic] of [...rows].sort((a, b) => Number(a.deviceid) - Number(b.deviceid)).entries()) {
    if (Number(nic.deviceid) !== index) return 'message.vnf.nic.ids.invalid'
    if (!nic.name?.trim()) return 'message.vnf.nic.name.required'
    if (optional && nic.required) return 'message.vnf.nic.required.order'
    if (!nic.required) optional = true
  }
  return ''
}
