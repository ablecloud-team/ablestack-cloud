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

import AutogenView from '@/views/AutogenView.vue'
import config from '@/config/section/config'
import store from '@/store'

jest.mock('@/store', () => ({ getters: { apis: {} } }))
jest.mock('@/vue-app', () => ({ vueProps: {} }))

const oauth = config.children.find(section => section.name === 'oauthsetting')
const register = oauth.actions.find(action => action.api === 'registerOauthProvider')
const edit = oauth.actions.find(action => action.api === 'updateOauthProvider' && action.args)
const base = ['provider', 'description', 'clientid', 'secretkey', 'redirecturi', 'details', 'id', 'enabled']
const schema = names => names.map(name => ({ name, type: name === 'domainid' ? 'uuid' : 'string' }))

function context () {
  const vm = {
    ...AutogenView.methods,
    currentAction: {},
    showAction: false,
    actionLoading: true,
    $route: { path: '/oauthsetting' },
    $store: store,
    $emit: jest.fn(),
    $t: jest.fn((key, values) => values ? `${key}: ${values.api}: ${values.parameters}` : key),
    $message: { error: jest.fn() },
    getFilters: jest.fn(),
    setRules: jest.fn(),
    listUuidOpts: jest.fn(),
    fillEditFormFieldValues: jest.fn()
  }
  return vm
}

describe('OAuth API schema compatibility', () => {
  beforeEach(() => { store.getters.apis = {} })

  it.each([register, edit])('rejects missing OAuth fields before opening the form ($api)', action => {
    store.getters.apis[action.api] = { params: schema(base) }
    const vm = context()
    expect(() => vm.execAction(action, false)).not.toThrow()
    expect(vm.showAction).toBe(false)
    expect(vm.currentAction).toEqual({})
    expect(vm.actionLoading).toBe(false)
    expect(vm.$message.error).toHaveBeenCalledWith(expect.stringContaining('authorizeurl, tokenurl, domainid'))
    expect(vm.setRules).not.toHaveBeenCalled()
  })

  it.each([register, edit])('renders every configured field with the Europa schema ($api)', action => {
    store.getters.apis[action.api] = { params: schema([...base, 'authorizeurl', 'tokenurl', 'domainid']) }
    const vm = context()
    vm.execAction(action, false)
    expect(vm.showAction).toBe(true)
    expect(vm.currentAction.paramFields.map(field => field.name)).toEqual(action.args)
    expect(vm.setRules).toHaveBeenCalledTimes(action.args.length)
    expect(vm.$message.error).not.toHaveBeenCalled()
  })

  it('rejects a missing API without a TypeError', () => {
    const vm = context()
    expect(() => vm.execAction(register, false)).not.toThrow()
    expect(vm.showAction).toBe(false)
    expect(vm.$message.error).toHaveBeenCalledWith(expect.stringContaining('registerOauthProvider'))
  })

  it('preserves synthetic fields, functional arguments and case-insensitive matching', () => {
    const vm = context()
    const missing = vm.getArgs({ args: () => ['confirmpassword', 'ostypeid', 'CLIENTID'] }, false, schema(['clientid']))
    expect(missing).toEqual([])
    expect(vm.currentAction.paramFields.map(field => field.name)).toEqual(['confirmpassword', 'ostypeid', 'clientid'])
  })
})
