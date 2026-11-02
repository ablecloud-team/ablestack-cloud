// Licensed to the Apache Software Foundation (ASF) under one or more
// contributor license agreements. See NOTICE. Apache License, Version 2.0.
// http://www.apache.org/licenses/LICENSE-2.0
import { normalizeClientSources, smbClientSources, clientAccessForm, clientAccessParameters, clientAccessIntent } from '@/utils/smbClientAccess'
import { smbSharePolicyParameters, smbSharePolicyIntent } from '@/utils/smbShareParameters'
import fs from 'fs'
import path from 'path'
const share = '0fc08aa0-61e2-4a35-a23f-94eb4afcf7de'
describe('share source request contract', () => {
  test('numeric canonicalization is idempotent and rejects duplicate or unsafe literals', () => {
    const sources = normalizeClientSources(['10.10.254.224', '10.10.13.22/24', '10.0.0.1/1'])
    expect(sources).toEqual(['0.0.0.0/1', '10.10.13.0/24', '10.10.254.224/32'])
    expect(normalizeClientSources(sources)).toEqual(sources)
    for (const input of [['host.local'], ['*'], ['::1'], ['10.0.0.1/0'], ['10.01.2.3'], ['1.2.3.256'], ['0.0.0.0'], ['224.0.0.1'], ['1.2.3.4', '1.2.3.4/32']]) expect(() => normalizeClientSources(input)).toThrow()
  })
  test('legacy is ANY_SOURCE; omitted and unchanged fields are not submitted', () => {
    expect(clientAccessParameters([], clientAccessForm('{}'))).toEqual({})
    const config = { clientAccess: { version: 1, cidrs: ['10.0.0.1'] } }
    expect(clientAccessParameters(smbClientSources(config), clientAccessForm(config))).toEqual({})
    expect(() => smbClientSources({ clientAccess: null })).toThrow()
  })
  test('last-rule clear needs confirmation without modifying account or POSIX fields', () => {
    const form = { mode: 'ANY_SOURCE', input: '', confirmed: false }
    expect(() => clientAccessParameters(['10.0.0.1'], form)).toThrow('SMB_CLIENT_ACCESS_CONFIRM_ANY_REQUIRED')
    expect(clientAccessParameters(['10.0.0.1'], { ...form, confirmed: true })).toEqual({ clientcidrs: '[]', confirmanysource: true })
  })
  test('creation preview and submit reuse one optional source patch; legacy edit remains separate', () => {
    const form = { fileidentity: { mode: 'AUTHENTICATED', uid: null, gid: null }, clientaccess: { mode: 'RESTRICTED', input: '10.10.13.27/24', confirmed: false } }
    const params = smbSharePolicyParameters(form)
    expect(params.clientcidrs).toBe('["10.10.13.0/24"]')
    expect(smbSharePolicyIntent(params).smbclientaccess).toEqual({ cidrs: ['10.10.13.0/24'] })
    expect(smbSharePolicyParameters(form, true).clientcidrs).toBeUndefined()
  })
  test('publish canonical UI request/intent pairs for the actual API marshaller regression', () => {
    const cases = [
      { name: 'add', before: [], form: { mode: 'RESTRICTED', input: '10.10.254.224', confirmed: false } },
      { name: 'replace', before: ['10.10.254.224/32'], form: { mode: 'RESTRICTED', input: '10.10.254.223', confirmed: false } },
      { name: 'network', before: [], form: { mode: 'RESTRICTED', input: '10.10.13.27/24', confirmed: false } },
      { name: 'clear', before: ['10.10.254.224/32'], form: { mode: 'ANY_SOURCE', input: '', confirmed: true } }
    ].map(value => {
      const params = { id: share, ...clientAccessParameters(value.before, value.form) }
      return { name: value.name, before: value.before, request: params, intent: { api: 'updateStorageSmbShare', protocol: 'SMB', resourceid: share, ...clientAccessIntent(params) } }
    })
    expect(cases).toHaveLength(4)
    fs.writeFileSync(path.resolve(__dirname, '../../../fixtures/storage-1457-client-contract.json'), JSON.stringify(cases, null, 2) + '\n')
  })
})
