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

import { flushPromises } from '@vue/test-utils'
import { applyCustomGuiTheme } from '@/utils/guiTheme'
import { vueProps } from '@/vue-app'
import { getAPI } from '@/api'
import Login from '@/views/auth/Login'

jest.mock('@/api', () => ({ getAPI: jest.fn() }))
jest.mock('@/locales', () => ({ loadLanguageAsync: jest.fn() }))
jest.mock('@/vue-app', () => ({ vueProps: { $config: {}, $localStorage: { get: jest.fn(), set: jest.fn() } } }))

describe('GUI theme login domain', () => {
  beforeEach(() => {
    global.fetch = jest.fn().mockResolvedValue({ json: async () => ({ appTitle: 'ABLESTACK', loginBaseDomain: 'static', plugins: [] }) })
    getAPI.mockReset()
  })
  afterEach(() => { delete global.fetch })

  it('retains the configured base domain and branding without a matching theme', async () => {
    getAPI.mockResolvedValue({ listguithemesresponse: {} })
    await applyCustomGuiTheme()
    expect(vueProps.$config.loginBaseDomain).toBe('static')
    expect(vueProps.$config.appTitle).toBe('ABLESTACK')
  })
  it('uses an explicit override, then restores the static value on a later theme selection', async () => {
    getAPI.mockResolvedValue({ listguithemesresponse: { guiThemes: [{ loginbasedomain: 'tenant' }] } })
    await applyCustomGuiTheme()
    expect(vueProps.$config.loginBaseDomain).toBe('tenant')
    getAPI.mockResolvedValue({ listguithemesresponse: {} })
    await applyCustomGuiTheme()
    expect(vueProps.$config.loginBaseDomain).toBe('static')
  })
  it('allows an explicit empty theme value to clear the static base domain', async () => {
    getAPI.mockResolvedValue({ listguithemesresponse: { guiThemes: [{ loginbasedomain: '' }] } })
    await applyCustomGuiTheme()
    expect(vueProps.$config.loginBaseDomain).toBe('')
  })
  it.each([
    ['password', { domain: 'password-child', oauthDomain: 'oauth-child' }, 'tenant/password-child'],
    ['oauth', { domain: 'password-child', oauthDomain: 'oauth-child' }, 'tenant/oauth-child'],
    ['oauth', {}, 'tenant']
  ])('keeps the %s domain field independent', (tab, form, expected) => {
    const vm = { customActiveKey: tab, form, $config: { loginBaseDomain: 'tenant' }, $store: { commit: jest.fn() } }
    vm.getLoginDomain = Login.methods.getLoginDomain.bind(vm)
    Login.methods.handleDomain.call(vm)
    expect(vm.$store.commit).toHaveBeenCalledWith('SET_DOMAIN_USED_TO_LOGIN', expected)
  })
  it.each([undefined, 'child'])('queries OAuth providers inside the theme base domain (%s)', async domain => {
    getAPI.mockResolvedValue({ listoauthproviderresponse: { oauthprovider: [] } })
    const vm = { $config: { loginBaseDomain: 'tenant' } }
    vm.getLoginDomain = Login.methods.getLoginDomain.bind(vm)
    Login.methods.fetchOauthProviders.call(vm, domain)
    await flushPromises()
    expect(getAPI).toHaveBeenCalledWith('listOauthProvider', { domain: domain ? 'tenant/child' : 'tenant' })
  })
  it('submits the OAuth domain instead of a stale password domain', async () => {
    const vm = {
      customActiveKey: 'oauth',
      $config: { loginBaseDomain: 'tenant' },
      form: { domain: 'wrong', oauthDomain: 'child' },
      formRef: { value: { validate: jest.fn().mockResolvedValue() } },
      setRules: jest.fn(),
      OauthLogin: jest.fn().mockResolvedValue({}),
      loginSuccess: jest.fn()
    }
    vm.getLoginDomain = Login.methods.getLoginDomain.bind(vm)
    Login.methods.handleSubmitOauth.call(vm, 'keycloak')
    await flushPromises()
    expect(vm.OauthLogin).toHaveBeenCalledWith(expect.objectContaining({ domain: 'tenant/child' }))
  })
  it('uses root without a theme or explicit domain', () => {
    expect(Login.methods.getLoginDomain.call({ $config: {} }, '')).toBe('/')
  })
})
