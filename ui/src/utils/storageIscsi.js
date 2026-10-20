// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements. See the NOTICE file
// distributed with this work for additional information.

// Same ASCII IQN grammar as the API and Native auth boundary. A naming
// suffix is not proof of ownership; submitted names are never trimmed.
export function isValidIscsiIqn (value) {
  return typeof value === 'string' && value.length <= 223 && !/[^\x21-\x7e]/.test(value) &&
    /^iqn\.[0-9]{4}-[0-9]{2}\.[A-Za-z0-9.-]+:[A-Za-z0-9_.:-]+$/.test(value)
}

export function isValidIscsiLun (value) {
  return (typeof value === 'string' || typeof value === 'number') &&
    String(value).length > 0 && !/[^0-9]/.test(String(value)) && Number.isInteger(Number(value)) && Number(value) >= 0 && Number(value) <= 65535
}

export function iscsiTargetInput (form, registeredPorts = [], mappedAuthRequired = false) {
  const errors = {}
  if (!isValidIscsiIqn(form?.targetname)) errors.targetname = 'ISCSI_TARGET_IQN_INVALID'
  if (!isValidIscsiLun(form?.lun)) errors.lun = 'ISCSI_TARGET_LUN_INVALID'
  else if (mappedAuthRequired && Number(form.lun) > 255) errors.lun = 'ISCSI_MAPPED_LUN_UNSUPPORTED'
  const raw = form?.listenerports
  const ports = []
  if (!Array.isArray(raw) || !raw.length) errors.listenerports = 'ISCSI_LISTENER_GROUP_REQUIRED'
  else if (raw.some(value => !['number', 'string'].includes(typeof value) || (!String(value).length || /[^0-9]/.test(String(value))) || !Number.isInteger(Number(value)) || Number(value) < 1 || Number(value) > 65535)) errors.listenerports = 'ISCSI_LISTENER_GROUP_INVALID'
  else {
    for (const value of raw) if (!ports.includes(Number(value))) ports.push(Number(value))
    if (ports.some(port => !registeredPorts.includes(port))) errors.listenerports = 'ISCSI_LISTENER_NOT_REGISTERED'
  }
  return { valid: Object.keys(errors).length === 0, errors, ports }
}

const active = row => row && row.enabled !== false && (!row.state || ['ready', 'allocated'].includes(String(row.state).toLowerCase()))
const targetName = row => row?.targetname || row?.targetName || row?.name || ''
const targetId = row => String(row?.id || row?.uuid || '')

export function iscsiGroupHasAcl (targets, acls, name, editingId = '') {
  const editing = targets.find(row => targetId(row) === String(editingId))
  const names = new Set([name, targetName(editing)].filter(Boolean))
  const group = targets.filter(row => names.has(targetName(row)))
  const ids = new Set(group.map(targetId))
  const keys = new Set(group.map(row => row.targetgroupkey || row.targetGroupKey).filter(Boolean))
  return acls.some(row => active(row) && (
    ids.has(String(row.targetid || row.resourceid || row.resourceId || '')) ||
    names.has(row.targetname || row.targetName) ||
    keys.has(row.targetgroupkey || row.targetGroupKey)))
}

export function iscsiMappedGroupSupported (targets, selectedId) {
  const selected = targets.find(row => targetId(row) === String(selectedId))
  if (!selected || !active(selected)) return false
  const group = targets.filter(row => active(row) && targetName(row) === targetName(selected))
  return group.every(row => {
    const own = row.lunornamespace ?? row.lunOrNamespace ?? row.lun
    const raw = row.targetluns ?? row.targetLuns
    // Current rows carry their own state and LUN. Group CSV may also contain
    // disabled historical rows, so use it only for legacy summaries lacking a LUN.
    const luns = own !== undefined && own !== null ? [own]
      : raw === undefined || raw === null || raw === '' ? ['0'] : (Array.isArray(raw) ? raw : String(raw).split(','))
    return luns.every(lun => isValidIscsiLun(lun) && Number(lun) <= 255)
  })
}

export function defaultIscsiTargetIqn (name, nonce) {
  const prefix = 'iqn.2026-05.local.storage:'
  const tail = '-' + String(nonce).replace(/[^A-Za-z0-9_.:-]/g, '').slice(-16)
  const suffix = String(name || 'target').replace(/[^A-Za-z0-9_.:-]+/g, '-').replace(/^-+|-+$/g, '') || 'target'
  return prefix + suffix.slice(0, 223 - prefix.length - tail.length) + tail
}

const categories = [
  ['TARGET_IQN_INVALID', 'target.name'],
  ['INITIATOR_IQN_INVALID', 'initiator.name'],
  ['TARGET_LUN_INVALID', 'target.lun'],
  ['MAPPED_LUN_UNSUPPORTED', 'mapped.lun'],
  ['OWNERSHIP_INVALID', 'ownership'],
  ['OWNERSHIP_MISMATCH', 'ownership'],
  ['AUTH_VALUE_INVALID', 'credential']
]

// Only closed public codes and phases are interpreted. Raw errors are never
// returned, including unknown messages, credentials, stderr and target names.
export function iscsiFailurePresentation (result) {
  const body = result?.jobresult || result?.response?.data || result || {}
  const nested = body && typeof body === 'object' ? Object.values(body).slice(0, 16).filter(value => value && typeof value === 'object' && !Array.isArray(value)).map(value => value.errortext) : []
  const values = [typeof body === 'string' ? body : null, body.errortext, body.errorresponse?.errortext, ...nested, body.reason, body.code, body.message, result?.message]
  const text = values.filter(value => typeof value === 'string').map(value => value.slice(0, 8192)).join(' ')
  const token = code => new RegExp('(?:^|[^A-Z0-9_])(?:ISCSI_)?' + code + '(?=$|[^A-Z0-9_])').test(text)
  const cause = categories.find(([code]) => token(code))
  const phase = /(?:^|[ |;])failurePhase=VERIFYING(?:[ |;]|$)/.test(text) ? 'verify'
    : /(?:^|[ |;])failurePhase=APPLYING(?:[ |;]|$)/.test(text) ? 'apply'
      : /ISCSI guard=(?:AUTH|VAULT_STATE)(?:[ |;]|$)/.test(text) ? 'authentication' : null
  const recovery = /(?:^|[^A-Z0-9_])(?:RECOVERY_REQUIRED|ROLLBACK_FAILED|BLOCKED)(?=$|[^A-Z0-9_])/.test(text)
  return {
    messageKey: cause || phase ? 'message.storage.service.iscsi.failure.' + (cause ? cause[1] : phase) : null,
    recoveryRequired: recovery,
    rolledBack: !recovery && token('ROLLED_BACK')
  }
}
