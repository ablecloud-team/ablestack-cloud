// Licensed to the Apache Software Foundation (ASF) under one or more
// contributor license agreements. See NOTICE. Apache License, Version 2.0.
// http://www.apache.org/licenses/LICENSE-2.0
export function adDomainProgress (domain) {
  try {
    const config = typeof domain?.config === 'string' ? JSON.parse(domain.config) : domain?.config
    const value = config?.adJoin
    if (!value || typeof value !== 'object' || Array.isArray(value)) return {}
    return Object.fromEntries(['operationid', 'jobid', 'phase', 'state', 'membership', 'serviceApply', 'rollback', 'failureCode']
      .filter(key => typeof value[key] === 'string' && value[key].length <= 160).map(key => [key, value[key]]))
  } catch { return {} }
}
export function adDomainApplied (status) {
  return status?.membership === 'JOINED' && status?.serviceApply === 'VERIFIED'
}
export function adDomainBusy (status) {
  return ['RUNNING', 'RECOVERY_REQUIRED'].includes(status?.state)
}
