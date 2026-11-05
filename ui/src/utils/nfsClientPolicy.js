// Licensed under the Apache License, Version 2.0. See NOTICE.
// http://www.apache.org/licenses/LICENSE-2.0
const fields = ['principal', 'principals', 'principaltype', 'permission', 'rootsquash',
  'allsquash', 'anonuid', 'anongid', 'sync', 'secure', 'inheritpolicy']
export function nfsClientPolicyFields (params) {
  return Object.fromEntries(fields.filter(key => params[key] !== undefined && params[key] !== null).map(key => [key, params[key]]))
}
export function nfsAclRequestParameters (form, mapping, principals, editing, id) {
  return {
    ...(editing ? { id } : { exportid: form.exportid, principaltype: form.principaltype, principals: principals.join(',') }),
    principal: principals[0],
    permission: form.permission,
    ...mapping,
    sync: form.sync,
    secure: form.secure
  }
}
