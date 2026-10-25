// Licensed under the Apache License, Version 2.0. See NOTICE.
export function ipv4Number (value) {
  const parts = String(value || '').trim().split('.')
  if (parts.length !== 4 || parts.some(x => !/^\d+$/.test(x) || String(Number(x)) !== x || Number(x) > 255)) return null
  return parts.reduce((n, x) => n * 256 + Number(x), 0)
}
export function staticNetworkError (form, field = 'ipcidr') {
  if (form.networkmode !== 'STATIC') return ''
  const parts = String(form.ipcidr || '').trim().split('/')
  const address = ipv4Number(parts[0])
  const prefix = Number(parts[1])
  if (parts.length !== 2 || !/^\d+$/.test(parts[1]) || prefix < 0 || prefix > 32 || address === null || address === 0 || address >= 0xe0000000) return 'message.sharedfs.static.ip.cidr.invalid'
  const block = 2 ** (32 - prefix)
  const network = Math.floor(address / block) * block
  const broadcast = network + block - 1
  if (prefix <= 30 && [network, broadcast].includes(address)) return 'message.sharedfs.static.ip.cidr.invalid'
  if (field === 'gateway' && form.gateway) {
    const gateway = ipv4Number(form.gateway)
    if (gateway === null || gateway === 0 || gateway >= 0xe0000000 || gateway === address || gateway < network || gateway > broadcast || (prefix <= 30 && [network, broadcast].includes(gateway))) return 'message.sharedfs.static.gateway.invalid'
  }
  if (['dns1', 'dns2'].includes(field) && form[field]) {
    const dns = ipv4Number(form[field])
    if (dns === null || dns === 0 || dns >= 0xe0000000) return 'message.sharedfs.static.dns.invalid'
  }
  return ''
}
export function networkPolicyObservation (rows) {
  try {
    const row = rows?.[0]
    if (!row || row.success === false || row.success === 'false') return { policy: null, stale: true }
    const wire = typeof row.resultjson === 'string' ? JSON.parse(row.resultjson) : row.resultjson || row
    const policy = wire.network?.policy
    const generated = Number(wire.generatedEpoch || policy?.observedEpoch || 0) * 1000
    const stale = !generated || Date.now() - generated > Number(wire.staleAfterSeconds || 30) * 1000
    return { policy: policy || null, stale, generated }
  } catch (_) { return { policy: null, stale: true } }
}
