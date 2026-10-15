// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements. See the NOTICE file
// distributed with this work for additional information.

function storageRevision (value) {
  if (typeof value !== 'number' && (typeof value !== 'string' || !/^(0|[1-9][0-9]*)$/.test(value))) throw new Error('STORAGE_OPERATION_STATE_UNVERIFIED')
  const revision = Number(value)
  if (!Number.isSafeInteger(revision) || revision < 0) throw new Error('STORAGE_OPERATION_STATE_UNVERIFIED')
  return revision
}

export function storageOperationRows (response) {
  const body = response?.liststorageserviceoperationsresponse
  if (!body || typeof body !== 'object' || Object.getPrototypeOf(body) !== Object.prototype || Object.prototype.hasOwnProperty.call(body, 'errorcode')) throw new Error('STORAGE_OPERATION_STATE_UNVERIFIED')
  // ApiResponseSerializer emits exactly {} for a successful zero-count ListResponse.
  if (Object.keys(body).length === 0) return []
  if ((body.storageserviceoperation !== undefined && !Array.isArray(body.storageserviceoperation)) ||
      (!Array.isArray(body.storageserviceoperation) && body.count !== 0 && body.count !== '0')) throw new Error('STORAGE_OPERATION_STATE_UNVERIFIED')
  const rows = body.storageserviceoperation || []
  if (body.count !== undefined && (storageRevision(body.count) !== rows.length)) throw new Error('STORAGE_OPERATION_STATE_UNVERIFIED')
  return rows
}

// Match DesiredStateChange's current writer predicate, not historical errors.
export function currentStorageWriter (rows, instanceId) {
  if (!Array.isArray(rows)) throw new Error('STORAGE_OPERATION_STATE_UNVERIFIED')
  const values = rows.map(row => {
    const revision = storageRevision(row.revision)
    if (typeof row.id !== 'string' || !row.id || row.instanceid !== instanceId || typeof row.state !== 'string' || !row.state) throw new Error('STORAGE_OPERATION_STATE_UNVERIFIED')
    return { id: row.id, state: row.state, revision }
  })
  const committedRevision = Math.max(0, ...values.filter(row => row.state === 'COMPLETE').map(row => row.revision))
  const blocker = values.filter(row => row.state === 'RUNNING' || (row.state === 'RECOVERY_REQUIRED' && row.revision > committedRevision))
    .sort((a, b) => b.revision - a.revision)[0] || null
  return { committedRevision, blocker }
}

const forwardStorageCommands = new Set([
  'createStoragePosixDirectoryPolicy', 'updateStoragePosixDirectoryPolicy', 'deleteStoragePosixDirectoryPolicy', 'applyStoragePosixDirectoryPolicy',
  'createStorageSmbNetworkAcl', 'updateStorageSmbNetworkAcl', 'deleteStorageSmbNetworkAcl',
  'verifyStorageServiceConfiguration', 'applyStorageServiceConfigRestore', 'restoreStorageServiceLastKnownGood',
  'upgradeStorageServiceRuntime', 'upgradeStorageServiceSystemVmTemplate', 'configureStorageServiceControlPolicy'
])

// Explicit recovery/control and read-only commands retain their server scope guards.
export function requireStorageMutationAllowed (vm, command) {
  if (command && !forwardStorageCommands.has(command)) return
  if (vm.storageMutationGate?.blocked?.()) throw new Error(vm.storageMutationGate.message())
}

export const storageMutationGateMixin = {
  inject: { storageMutationGate: { default: null } },
  computed: {
    storageMutationDisabled () { return !!this.storageMutationGate?.blocked?.() }
  }
}
