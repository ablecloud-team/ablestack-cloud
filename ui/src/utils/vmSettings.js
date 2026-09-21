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

const names = value => (value || '').split(',').map(s => s.trim()).filter(Boolean)
export function settingRestriction (vm, template, name) {
  if (name.startsWith('extraconfig')) return 'protected'
  if (vm.hypervisor === 'KVM' && ['TPM', 'tpmversion', 'virtual.tpm.model', 'virtual.tpm.version'].includes(name)) return 'tpm'
  if (names(vm.readonlydetails).includes(name)) return 'readonly'
  if (template?.deployasis && !names(vm.alloweddetails).includes(name)) return 'template'
  return ''
}
export function settingRows (vm) {
  const details = vm.details || {}
  const canonical = vm.hypervisor === 'KVM' && Object.prototype.hasOwnProperty.call(details, 'virtual.tpm.model')
  const rows = Object.entries(details).filter(([key]) => !canonical || !['tpmversion', 'virtual.tpm.model', 'virtual.tpm.version'].includes(key)).map(([name, value]) => ({ name, value: String(value) }))
  if (canonical) rows.push({ name: 'TPM', value: details['virtual.tpm.model'] + ' / ' + (details['virtual.tpm.version'] || '2.0'), original: { 'virtual.tpm.model': details['virtual.tpm.model'], 'virtual.tpm.version': details['virtual.tpm.version'] || '2.0' } })
  return rows.sort((a, b) => a.name.localeCompare(b.name, undefined, { numeric: true }))
}
export function settingsFingerprint (vm) {
  return JSON.stringify(Object.entries(vm.details || {}).sort(([a], [b]) => a.localeCompare(b)))
}
export function settingsParams (vm, template, action, name, value, count, admin) {
  const details = { ...(vm.details || {}) }
  if (!name.trim() || (action !== 'delete' && !String(value).trim())) throw new Error('required')
  if (settingRestriction(vm, template, name)) throw new Error(settingRestriction(vm, template, name))
  if (action !== 'add' && !Object.prototype.hasOwnProperty.call(details, name)) throw new Error('changed')
  if (action === 'add' && name !== 'video.hardware' && Object.prototype.hasOwnProperty.call(details, name)) throw new Error('duplicate')
  if (action === 'add' && name === 'video.hardware') {
    if (!Number.isInteger(count) || count < 1 || count > 4) throw new Error('count')
    const videoKeys = Object.keys(details).filter(k => /^video\.(hardware|ram)\d*$/.test(k))
    const newKeys = Array.from({ length: count }, (_, i) => [i ? 'video.hardware' + (i + 1) : 'video.hardware', i ? 'video.ram' + (i + 1) : 'video.ram']).flat()
    if ([...videoKeys, ...newKeys].some(k => settingRestriction(vm, template, k))) throw new Error('protected')
    videoKeys.forEach(k => { delete details[k] })
    newKeys.forEach(k => { details[k] = k.startsWith('video.hardware') ? value : '16384' })
  } else if (action === 'delete') delete details[name]
  else details[name] = value
  // Non-admin read-only details are restored by updateVirtualMachine itself.
  const sent = Object.entries(details).filter(([key]) => admin || !names(vm.readonlydetails).includes(key))
  if (!sent.length) {
    // cleanupdetails ignores the map and clears all eligible details. Never use it
    // when doing so could remove an unrelated protected entry.
    if (Object.keys(details).length || template?.deployasis) throw new Error('cleanupBlocked')
    return { id: vm.id, cleanupdetails: true }
  }
  return Object.fromEntries([['id', vm.id], ...sent.map(([key, val]) => ['details[0].' + key, val])])
}
