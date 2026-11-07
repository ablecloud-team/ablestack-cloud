// Licensed to the Apache Software Foundation (ASF) under one or more
// contributor license agreements. See the NOTICE file distributed with this
// work for additional information regarding copyright ownership. The ASF
// licenses this file under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License. You may
// obtain a copy at http://www.apache.org/licenses/LICENSE-2.0 . Unless required
// by applicable law or agreed to in writing, software distributed under the
// License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS.

export const HAPROXY_PROVIDERS = ['VirtualRouter', 'VpcVirtualRouter', 'InternalLbVm']
export function offeringLbSupport (offering) {
  const lb = offering.service?.find(s => s.name === 'Lb')
  if (!lb) return 'no.service'
  if (!lb.provider?.length) return 'unknown.provider'
  if (String(offering.guestiptype).toLowerCase() !== 'isolated') return 'type'
  if (!lb.provider.every(p => HAPROXY_PROVIDERS.includes(p.name))) return 'provider'
  return ''
}
export function lbSettingsParams (draft, original = {}, changedOnly = false) {
  const result = {}
  if (draft.maxconnections !== undefined && draft.maxconnections !== null && String(draft.maxconnections).trim() !== '') {
    const value = Number(draft.maxconnections)
    if (!Number.isInteger(value) || value < 0 || value > 2147483647) throw new Error('maxconnections')
    if (!changedOnly || value !== original.maxconnections) result.maxconnections = value
  }
  if (typeof draft.keepaliveenabled === 'boolean' && (!changedOnly || draft.keepaliveenabled !== original.keepaliveenabled)) result.keepaliveenabled = draft.keepaliveenabled
  return result
}
