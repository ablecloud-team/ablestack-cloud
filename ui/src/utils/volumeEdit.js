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

// Volume edit requests contain only explicitly changed, authorized fields.
export function volumePathEditReason (volume, { admin, params = {} }) {
  if (!admin || !params.path) return 'message.volume.edit.path.admin'
  if (volume.kvdoenable || volume.allocation?.contractversion === '1') return 'message.volume.edit.path.unsupported'
  if (!['Allocated', 'Ready', 'Uploaded'].includes(volume.state)) return 'message.volume.edit.path.state'
  if (volume.virtualmachineid && volume.vmstate !== 'Stopped') return 'message.volume.edit.path.stopped'
  return ''
}

export function volumeEditParams (volume, form, context) {
  const result = { id: volume.id }
  if (!volume.id) throw new Error('message.volume.edit.changed')
  if (form.name !== volume.name) {
    if (!context.params.name) throw new Error('message.volume.edit.forbidden')
    if (!form.name?.trim()) throw new Error('message.volume.edit.name.required')
    result.name = form.name
  }
  if (form.deleteprotection !== volume.deleteprotection) {
    if (!context.params.deleteprotection || typeof form.deleteprotection !== 'boolean') throw new Error('message.volume.edit.forbidden')
    result.deleteprotection = form.deleteprotection
  }
  if (form.pathEnabled && form.path !== volume.path) {
    const reason = volumePathEditReason(volume, context)
    if (reason) throw new Error(reason)
    if (!form.path?.trim()) throw new Error('message.volume.edit.path.required')
    result.path = form.path
  }
  return result
}

export function validateVolumeEditSnapshot (snapshot, current, params, context) {
  if (!current || current.id !== snapshot.id ||
    ['account', 'domainid', 'projectid'].some(key => (snapshot[key] || '') !== (current[key] || ''))) throw new Error('message.volume.edit.changed')
  for (const key of ['name', 'deleteprotection']) {
    if (key in params && current[key] !== snapshot[key] && current[key] !== params[key]) throw new Error('message.volume.edit.changed')
  }
  if ('path' in params) {
    const reason = volumePathEditReason(current, context)
    if (reason) throw new Error(reason)
    if (['path', 'chaininfo', 'storageid', 'state', 'virtualmachineid'].some(key => (snapshot[key] || '') !== (current[key] || ''))) throw new Error('message.volume.edit.changed')
  }
}

export function canDeleteVolume (record = {}, store = {}) {
  const canExpunge = ['Admin', 'DomainAdmin'].includes(store.userInfo?.roletype) || !!store.features?.allowuserexpungerecovervolume
  if (!canExpunge) return false
  const detached = !record.virtualmachineid
  return ['Expunging', 'Expunged', 'UploadError'].includes(record.state) ||
    (['Allocated', 'Uploaded', 'Ready'].includes(record.state) && record.type !== 'ROOT' && detached) ||
    (record.state === 'Allocated' && record.type === 'ROOT' && detached) ||
    ((['Admin', 'DomainAdmin'].includes(store.userInfo?.roletype) || !!store.features?.allowuserexpungerecovervolume) && record.state === 'Destroy')
}

export function volumeDeleteReason (rows, store, api = 'deleteVolume') {
  if (!rows.length || rows.some(row => !row?.id)) return 'message.volume.delete.unavailable'
  if (rows.some(row => row.deleteprotection)) return 'message.volume.delete.protected'
  if (rows.some(row => String(row.clonefastflattenstatus || row.details?.['clone.fast.flatten.status'] || '').toLowerCase() === 'running')) return 'message.sharedmountpoint.clone.flatten.in.progress'
  if (rows.some(row => api === 'deleteVolume' ? !canDeleteVolume(row, store)
    : ['Destroy', 'Destroyed', 'Expunging', 'Expunged', 'Migrating', 'Uploading', 'UploadError', 'Creating', 'Allocated', 'Uploaded'].includes(row.state) || row.type === 'ROOT' || row.virtualmachineid)) return 'message.volume.delete.unavailable'
  return ''
}
