// Licensed to the Apache Software Foundation (ASF) under one or more
// contributor license agreements. See NOTICE. Apache License, Version 2.0.
// http://www.apache.org/licenses/LICENSE-2.0
const kinds = {
  nfsExport: ['NfsExport', 'NFS'],
  nfsAcl: ['NfsAcl', 'NFS'],
  smbShare: ['SmbShare', 'SMB'],
  smbAcl: ['SmbAcl', 'SMB'],
  iscsiTarget: ['IscsiTarget', 'ISCSI'],
  iscsiAcl: ['IscsiAcl', 'ISCSI'],
  nvmeSubsystem: ['NvmeOfSubsystem', 'NVME_OF'],
  nvmeNamespace: ['NvmeOfNamespace', 'NVME_OF'],
  nvmeHostAcl: ['NvmeOfHostAcl', 'NVME_OF']
}
export function configurationModalIntent (type, forms, context) {
  if (['enableProtocol', 'deleteEndpoint'].includes(type)) {
    return {
      api: type === 'enableProtocol' ? 'enableStorageServiceProtocol' : 'deleteStorageServiceProtocol',
      protocol: (forms[type]?.protocol || 'NFS').toUpperCase()
    }
  }
  let action = 'create'; let key = type
  if (type === 'deleteConfirm') { action = 'delete'; key = context?.resourceType } else if (type.startsWith('edit')) { action = 'update'; key = type.charAt(4).toLowerCase() + type.slice(5) }
  if (!kinds[key]) return null
  const [kind, protocol] = kinds[key]
  const form = forms[key] || {}
  const result = { api: action + 'Storage' + kind, protocol, recursive: !!form.recursivepermission }
  if (action !== 'create' && (context?.raw?.id || form.id)) result.resourceid = context?.raw?.id || form.id
  if (action !== 'delete' && form.volumeid) result.volumeid = form.volumeid
  if (form.volumemode) result.volumemode = form.volumemode
  if (form.volumemode === 'NEW') {
    result.newvolume = { diskofferingid: form.diskofferingid, storageid: form.storageid, size: form.newvolumesize }
    delete result.volumeid
  }
  return result
}
export function decodeConfigurationPreflight (wire) {
  const report = wire?.preflightstorageserviceconfigurationresponse?.configurationpreflight
  return report && ['READY', 'BLOCKED', 'UNKNOWN'].includes(report.status) && Array.isArray(report.checks) ? report : null
}
export function preflightApplicable (report, acknowledged, now = Date.now()) {
  return !!report && report.status === 'READY' && !report.checks.some(check => check.blocking) &&
    Date.parse(report.expiresat) > now && (!report.impact?.ackrequired || acknowledged)
}
export function preparedVolumeKey (user, instance, type, params) {
  if (!user || !instance) return null
  return 'sharedfs-prepared:' + JSON.stringify([user, instance, type, params.name, params.zoneid,
    params.diskofferingid, params.storageid, params.size])
}
