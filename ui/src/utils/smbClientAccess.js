// Licensed to the Apache Software Foundation (ASF) under one or more
// contributor license agreements. See NOTICE. Apache License, Version 2.0.
// http://www.apache.org/licenses/LICENSE-2.0
const prefix = 'SMB_CLIENT_ACCESS_'
export function normalizeClientSources (values) {
  if (!Array.isArray(values) || values.length > 32) throw new Error(prefix + 'INVALID')
  const seen = new Set()
  const rows = values.map(value => {
    if (typeof value !== 'string') throw new Error(prefix + 'INVALID')
    const text = value.trim()
    if (text.includes(':')) throw new Error(prefix + 'IPV6_UNSUPPORTED')
    if (!/^(0|[1-9][0-9]{0,2})(\.(0|[1-9][0-9]{0,2})){3}(\/([1-9]|[12][0-9]|3[0-2]))?$/.test(text)) throw new Error(prefix + 'INVALID')
    const [ip, mask] = text.split('/')
    const octets = ip.split('.').map(Number)
    if (octets.some(n => n > 255) || ip === '255.255.255.255' || (octets[0] >= 224 && octets[0] <= 239)) throw new Error(prefix + 'INVALID')
    const bits = mask === undefined ? 32 : Number(mask)
    if (ip === '0.0.0.0' && bits === 32) throw new Error(prefix + 'INVALID')
    const number = octets.reduce((v, n) => ((v << 8) | n) >>> 0, 0)
    const network = (number & ((0xffffffff << (32 - bits)) >>> 0)) >>> 0
    const cidr = [24, 16, 8, 0].map(n => (network >>> n) & 255).join('.') + '/' + bits
    if (seen.has(cidr)) throw new Error(prefix + 'DUPLICATE')
    seen.add(cidr)
    return { network, bits, cidr }
  })
  return rows.sort((a, b) => a.network - b.network || a.bits - b.bits).map(v => v.cidr)
}
export function smbClientSources (config) {
  const value = typeof config === 'string' ? JSON.parse(config || '{}') : config || {}
  if (!Object.prototype.hasOwnProperty.call(value, 'clientAccess')) return []
  const policy = value.clientAccess
  if (!policy || policy.version !== 1 || Object.keys(policy).some(k => !['version', 'cidrs'].includes(k))) throw new Error(prefix + 'INVALID')
  return normalizeClientSources(policy.cidrs)
}
export function clientAccessForm (config) {
  const cidrs = smbClientSources(config)
  return { mode: cidrs.length ? 'RESTRICTED' : 'ANY_SOURCE', input: cidrs.join('\n'), confirmed: false }
}
export function clientAccessValues (form) {
  if (form?.mode === 'ANY_SOURCE') return []
  if (form?.mode !== 'RESTRICTED') throw new Error(prefix + 'INVALID')
  const rows = normalizeClientSources(String(form.input || '').split(/[\n,]+/).map(v => v.trim()).filter(Boolean))
  if (!rows.length) throw new Error(prefix + 'INVALID')
  return rows
}
export function clientAccessParameters (baseline, form) {
  const before = normalizeClientSources(baseline || [])
  const after = clientAccessValues(form)
  if (JSON.stringify(before) === JSON.stringify(after)) return {}
  const clear = before.length > 0 && after.length === 0
  if (clear && !form.confirmed) throw new Error(prefix + 'CONFIRM_ANY_REQUIRED')
  return { clientcidrs: JSON.stringify(after), ...(clear ? { confirmanysource: true } : {}) }
}
export function clientAccessIntent (params) {
  if (params.clientcidrs === undefined) return {}
  return { smbclientaccess: { cidrs: normalizeClientSources(JSON.parse(params.clientcidrs)), ...(params.confirmanysource !== undefined ? { confirmAnySource: params.confirmanysource } : {}) } }
}
