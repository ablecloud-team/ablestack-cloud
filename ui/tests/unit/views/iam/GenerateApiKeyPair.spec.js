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

import GenerateApiKeyPair from '@/views/iam/GenerateApiKeyPair.vue'
import { postAPI } from '@/api'

jest.mock('@/api', () => ({ postAPI: jest.fn() }))

const settle = async () => {
  for (let i = 0; i < 8; i++) await Promise.resolve()
}

const context = () => ({
  form: { name: 'automation', description: 'limited key' },
  formRef: { value: { validate: jest.fn().mockResolvedValue() } },
  rules: [{ rule: 'listVirtualMachines', permission: 'allow' }],
  resource: { id: 'user-id', username: 'europa-user' },
  loading: false,
  $t: key => key,
  $notification: { success: jest.fn(), error: jest.fn() },
  fetchData: jest.fn(),
  closeModal: jest.fn(),
  buildRequestParams () { return GenerateApiKeyPair.methods.buildRequestParams.call(this) }
})

describe('API key pair synchronous creation', () => {
  beforeEach(() => jest.clearAllMocks())

  it('sends ordered permissions and refreshes on the synchronous response', async () => {
    const vm = context()
    postAPI.mockResolvedValue({ registeruserkeysresponse: { userkeys: { id: 'pair-id' } } })
    GenerateApiKeyPair.methods.handleSubmit.call(vm)
    await settle()
    expect(postAPI).toHaveBeenCalledWith('registerUserKeys', expect.objectContaining({
      id: 'user-id', name: 'automation', 'rules[0].rule': 'listVirtualMachines', 'rules[0].permission': 'allow'
    }))
    expect(vm.fetchData).toHaveBeenCalledTimes(1)
    expect(vm.closeModal).toHaveBeenCalledTimes(1)
    expect(vm.loading).toBe(false)
  })

  it('keeps the form open on a rejected request and permits retry', async () => {
    const vm = context()
    postAPI.mockRejectedValue(new Error('permission denied'))
    GenerateApiKeyPair.methods.handleSubmit.call(vm)
    await settle()
    expect(vm.$notification.error).toHaveBeenCalled()
    expect(vm.fetchData).not.toHaveBeenCalled()
    expect(vm.closeModal).not.toHaveBeenCalled()
    expect(vm.loading).toBe(false)
  })
})
