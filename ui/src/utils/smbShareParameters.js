// Licensed to the Apache Software Foundation (ASF) under one or more
// contributor license agreements. See NOTICE. Apache License, Version 2.0.
// http://www.apache.org/licenses/LICENSE-2.0
import { fileSharePathParameters } from '@/utils/fileSharePaths'
import { inheritOwnerParameters } from '@/utils/smbParentInheritance'
import { clientAccessParameters, clientAccessIntent } from '@/utils/smbClientAccess'
import { smbIdentityParameters } from '@/utils/smbFileIdentity'

// Optional booleans and identity deltas have one contract for preview and submit.
export function smbSharePolicyParameters (form, edit = false) {
  return {
    ...(!edit && form.clientaccess ? clientAccessParameters([], form.clientaccess) : {}),
    readonly: form.readonly,
    browseable: form.browseable,
    guestok: form.guestok,
    createdirectory: form.createdirectory,
    ...fileSharePathParameters(form, edit),
    crossprotocol: form.crossprotocol,
    directorypolicyid: form.directorypolicyid || undefined,
    ...inheritOwnerParameters(form),
    ...smbIdentityParameters(form.baseline?.fileidentity, form.fileidentity || { mode: 'AUTHENTICATED', uid: null, gid: null }),
    directorymode: form.directorypolicyid ? undefined : form.directorymode
  }
}
export function smbSharePolicyIntent (params) {
  const result = { ...clientAccessIntent(params) }; const inheritance = {}; const options = {}; const identity = {}
  if (params.inheritowner !== undefined) inheritance.inheritOwner = params.inheritowner
  if (params.directorypolicyid !== undefined) inheritance.directoryPolicyId = params.directorypolicyid
  if (Object.keys(inheritance).length) result.smbinheritance = inheritance
  if (params.readonly !== undefined) options.readOnly = params.readonly
  if (params.browseable !== undefined) options.browseable = params.browseable
  if (Object.keys(options).length) result.smboptions = options
  if (params.guestok !== undefined) result.guestok = params.guestok
  if (params.fileidentitymode !== undefined) identity.mode = params.fileidentitymode
  if (params.fixeduid !== undefined) identity.uid = params.fixeduid
  if (params.fixedgid !== undefined) identity.gid = params.fixedgid
  if (Object.keys(identity).length) result.smbfileidentity = identity
  return result
}
