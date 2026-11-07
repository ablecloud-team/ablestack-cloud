// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements. See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership. The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License. You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied. See the License for the
// specific language governing permissions and limitations
// under the License.

function validCidr (cidr, ipv6) {
  const [ip, prefix, extra] = cidr.split('/')
  if (extra !== undefined || !/^\d{1,3}$/.test(prefix || '') || Number(prefix) > (ipv6 ? 128 : 32)) return false
  if (!ipv6) return /^(\d{1,3}\.){3}\d{1,3}$/.test(ip) && ip.split('.').every(octet => Number(octet) <= 255)
  if (!/^[0-9a-f:.]+$/i.test(ip) || !ip.includes(':')) return false
  try { return new URL('http://[' + ip + ']/').hostname.startsWith('[') } catch (_) { return false }
}

export function conditionalFirewallRule (rule, networkid, ipv6 = false) {
  const { protocol, traffictype } = rule
  if (!networkid || !['ingress', 'egress'].includes(traffictype) ||
      !(['tcp', 'udp', 'icmp'].includes(protocol) || (traffictype === 'egress' && protocol === 'all'))) return null
  const params = { networkid, protocol, traffictype }
  for (const field of ['cidrlist', 'destcidrlist']) {
    const value = String(rule[field] ?? '').trim()
    if (!value) continue
    const blocks = value.split(',').map(block => block.trim())
    if (blocks.some(block => !validCidr(block, ipv6))) return null
    params[field] = blocks.join(',')
  }
  const integer = (field, min, max) => {
    const raw = String(rule[field] ?? '').trim()
    if (!raw) return true
    const value = Number(raw)
    if (!/^-?\d+$/.test(raw) || value < min || value > max) return false
    params[field] = value
    return true
  }
  if (['tcp', 'udp'].includes(protocol) &&
    (!integer('startport', 1, 65535) || !integer('endport', 1, 65535) || (params.endport !== undefined && (params.startport === undefined || params.startport > params.endport)))) return null
  if (protocol === 'icmp' &&
    (!integer('icmptype', -1, 255) || !integer('icmpcode', -1, 255) || (params.icmpcode !== undefined && params.icmptype === undefined))) return null
  return params
}
