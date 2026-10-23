// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements. See the NOTICE file
// distributed with this work for additional information.
// The ASF licenses this file under the Apache License, Version 2.0.
// You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
// Unless required by law or agreed in writing, software is distributed
// on an AS IS BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND.
export function creationRequestId () {
  const bytes = new Uint8Array(16)
  crypto.getRandomValues(bytes)
  bytes[6] = (bytes[6] & 15) | 64
  bytes[8] = (bytes[8] & 63) | 128
  const x = Array.from(bytes, b => b.toString(16).padStart(2, '0')).join('')
  return [x.slice(0, 8), x.slice(8, 12), x.slice(12, 16), x.slice(16, 20), x.slice(20)].join('-')
}
export function backingRequest (form = {}, requestid) {
  if (form.useexistingvolume) {
    if (!form.existingvolumeid) throw new Error('EXISTING_VOLUME_REQUIRED')
    if (!['INSPECT_ONLY', 'MOUNT_EXISTING'].includes(form.importmode || 'MOUNT_EXISTING')) throw new Error('EXISTING_FORMAT_FORBIDDEN')
    return { volumeid: form.existingvolumeid, importmode: form.importmode || 'MOUNT_EXISTING', filesystem: form.existingfilesystem || 'AUTO', requestid }
  }
  return { diskofferingid: form.diskofferingid, storageid: form.storageid, size: form.size, filesystem: form.filesystem, requestid }
}
const setupFields = ['name', 'services', 'useexistingvolume', 'existingvolumeid', 'filesystem', 'importmode',
  'nfsname', 'nfspath', 'nfsquotaamount', 'nfsquotaunit', 'nfsprotocolmode', 'nfsport', 'nfsprincipal', 'nfspermission',
  'nfsrootsquash', 'nfssync', 'nfssecure', 'smbidentitymode', 'smblocalusername', 'smbaddomain', 'smbadusername', 'smbaddns', 'smbadou', 'smbadworkgroup', 'smbname', 'smbpath', 'smbreadonly', 'smbbrowseable', 'smbguestok', 'resizeallowed']
export function publicCreationSetup (form) {
  return Object.fromEntries(setupFields.filter(k => form[k] !== undefined).map(k => [k, form[k]]))
}
export function backingFilesystem (resource, observed) {
  const fs = String(observed || resource?.observedfilesystem || resource?.filesystem || '').toLowerCase()
  return ['xfs', 'ext4'].includes(fs) ? fs : 'auto'
}
