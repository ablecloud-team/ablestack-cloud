// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

function validLdapHostname (host) {
  if (host.startsWith('[') && host.endsWith(']')) {
    try {
      return new URL('http://' + host).hostname.startsWith('[')
    } catch {
      return false
    }
  }
  if (/^\d+\.\d+\.\d+\.\d+$/.test(host)) {
    return host.split('.').every(part => Number(part) <= 255)
  }
  const dns = host.endsWith('.') ? host.slice(0, -1) : host
  return dns.length <= 253 && dns.split('.').every(part => /^[a-z\d](?:[a-z\d-]{0,61}[a-z\d])?$/i.test(part))
}

export function ldapRegistration (form, domainSelected) {
  const hostname = form.hostname.trim()
  const port = String(form.port).trim()
  const errors = {}
  if (!hostname || !validLdapHostname(hostname)) errors.hostname = 'message.ldap.host.invalid'
  if (!/^\d+$/.test(port) || Number(port) < 1 || Number(port) > 65535) errors.port = 'message.ldap.port.range'
  if (form.scope === 'domain' && !domainSelected) errors.domainid = 'message.ldap.domain.required'
  const params = { hostname, port: Number(port) }
  if (form.scope === 'domain') params.domainid = form.domainid
  return { errors, params }
}

export function ldapRegistrationFailure (error) {
  const response = error?.response
  const apiError = Object.values(response?.data || {}).find(value => value?.errorcode)
  const code = Number(apiError?.cserrorcode)
  if (code === 4562) return 'message.ldap.add.duplicate'
  if (code === 4563) return 'message.ldap.add.bind'
  return response ? 'message.ldap.add.failed' : 'message.ldap.add.unknown'
}

export async function ldapEndpointExists (getAPI, params, id) {
  const response = await getAPI('listLdapConfigurations', {
    ...params,
    listall: !!params.domainid
  }, { preserveOnFailure: true, timeout: 10000 })
  return (response.ldapconfigurationresponse?.LdapConfiguration || []).some(row =>
    (!id || row.id === id) && row.hostname === params.hostname && row.port === params.port &&
    (row.domainid || undefined) === params.domainid)
}
