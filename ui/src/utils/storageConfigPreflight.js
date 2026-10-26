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
    const form = forms[type] || {}
    const protocol = (form.protocol || 'NFS').toUpperCase()
    const result = { api: type === 'enableProtocol' ? 'enableStorageServiceProtocol' : 'deleteStorageServiceProtocol', protocol }
    if (protocol === 'SMB') {
      if (form.endpointid) result.endpointid = form.endpointid
      if (form.listenip) result.listenip = form.listenip
      if (form.port) result.port = form.port
    }
    return result
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

// Keep public API rejection details separate from an unknown guest observation.
// Never copy request parameters, headers or stack traces into the UI report.
export function configurationPreflightFailure (error, revision) {
  const data = error?.response?.data || {}
  const body = data.errorresponse || Object.values(data).find(value => value?.errortext) || error?.errorresponse || error || {}
  const http = Number(error?.response?.status || 0)
  const api = Number(body.errorcode || 0)
  const rejected = !!body.errortext && ((http >= 400 && http < 500) || (api >= 400 && api < 500))
  return {
    status: rejected ? 'BLOCKED' : 'UNKNOWN',
    revision,
    observedat: '-',
    checks: [{
      code: rejected ? 'PREFLIGHT_API_REJECTED' : 'RESOURCE_OBSERVATION_UNAVAILABLE',
      blocking: true,
      severity: 'ERROR',
      ...(rejected ? { message: String(body.errortext).slice(0, 1024), httpstatus: http || api } : {})
    }],
    cancellationsupported: false
  }
}
