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

import AddQuotaCredit from '@/views/plugins/quota/AddQuotaCredit.vue'
import { getAPI } from '@/api'

jest.mock('@/api', () => ({ getAPI: jest.fn() }))
jest.mock('@/store', () => ({ getters: { userInfo: {}, project: null } }))
jest.mock('@/utils/mixin', () => ({ mixinForm: {} }))
jest.mock('@/views/compute/wizard/OwnershipSelection.vue', () => ({}))
jest.mock('@/components/widgets/TooltipLabel', () => ({}))

const settle = async () => {
  for (let i = 0; i < 10; i++) await Promise.resolve()
}

const context = () => ({
  loading: false,
  owner: { account: 'europa-user', domainid: 'domain-1', name: 'europa-user' },
  form: { value: 12.3456, min_balance: 3, quota_enforce: true },
  formRef: { value: { validate: jest.fn().mockResolvedValue(), scrollToField: jest.fn() } },
  handleRemoveFields: value => ({ ...value }),
  $t: (key, params) => ({ key, params }),
  $message: { success: jest.fn(), error: jest.fn() },
  $notifyError: jest.fn(),
  parentFetchData: jest.fn(),
  closeModal: jest.fn()
})

describe('Quota credit account and response contract', () => {
  beforeEach(() => jest.clearAllMocks())

  it('sends the selected account and preserves credit precision', async () => {
    const vm = context()
    getAPI.mockResolvedValue({ quotacreditsresponse: { quotacredits: { credit: 12.3456 } } })
    AddQuotaCredit.methods.handleSubmit.call(vm)
    await settle()
    expect(getAPI).toHaveBeenCalledWith('quotaCredits', {
      value: 12.3456,
      min_balance: 3,
      quota_enforce: true,
      account: 'europa-user',
      domainid: 'domain-1',
      ignoreproject: true
    })
    expect(vm.$message.success).toHaveBeenCalledWith(expect.objectContaining({
      params: { credit: 12.3456, account: 'europa-user' }
    }))
    expect(vm.parentFetchData).toHaveBeenCalledTimes(1)
    expect(vm.closeModal).toHaveBeenCalledTimes(1)
    expect(vm.loading).toBe(false)
  })

  it('uses only project ownership when a project is selected', async () => {
    const vm = context()
    vm.owner = { projectid: 'project-1', name: 'europa-project' }
    getAPI.mockResolvedValue({ quotacreditsresponse: { quotacredits: { credit: 12.3456 } } })
    AddQuotaCredit.methods.handleSubmit.call(vm, { preventDefault: jest.fn() })
    await settle()
    expect(getAPI.mock.calls[0][1]).toMatchObject({ projectid: 'project-1', ignoreproject: true })
    expect(getAPI.mock.calls[0][1]).not.toHaveProperty('account')
    expect(getAPI.mock.calls[0][1]).not.toHaveProperty('domainid')
  })

  it.each([{}, { account: 'europa-user' }])('rejects incomplete ownership instead of crediting the caller', async owner => {
    const vm = context()
    vm.owner = owner
    AddQuotaCredit.methods.handleSubmit.call(vm)
    await settle()
    expect(getAPI).not.toHaveBeenCalled()
    expect(vm.$message.error).toHaveBeenCalledTimes(1)
  })

  it('keeps the form open after API failure and permits retry', async () => {
    const vm = context()
    const error = new Error('denied')
    getAPI.mockRejectedValue(error)
    AddQuotaCredit.methods.handleSubmit.call(vm)
    await settle()
    expect(vm.$notifyError).toHaveBeenCalledWith(error)
    expect(vm.closeModal).not.toHaveBeenCalled()
    expect(vm.parentFetchData).not.toHaveBeenCalled()
    expect(vm.loading).toBe(false)
  })
})
