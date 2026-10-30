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

import { flushPromises } from '@vue/test-utils'
import FormSchedule from '@/views/compute/backup/FormSchedule.vue'
import { backupUtilPlugin } from '@/utils/plugins'
import { mixinForm } from '@/utils/mixin'
import { postAPI } from '@/api'

jest.mock('@/store', () => ({ getters: { apis: {}, userInfo: {} } }))
jest.mock('@/api', () => ({ getAPI: jest.fn(), postAPI: jest.fn() }))

const app = { config: { globalProperties: {} } }
backupUtilPlugin.install(app)
const supportsQuiesce = app.config.globalProperties.$isBackupProviderSupportsQuiesceVm

function context (provider, quiescevm) {
  const form = { intervaltype: 'hourly', time: 15, timezone: 'Asia/Seoul', maxbackups: 2, quiescevm }
  return {
    resource: { id: 'vm-1' },
    form,
    $refs: Object.fromEntries(Object.keys(form).map(field => [field, {}])),
    formRef: { value: { validate: () => Promise.resolve() } },
    isQuiesceVmSupported: supportsQuiesce(provider),
    handleRemoveFields: mixinForm.methods.handleRemoveFields,
    resetForm: jest.fn(),
    refreshSchedule: jest.fn(),
    closeSchedule: jest.fn(),
    $notification: { success: jest.fn() },
    $notifyError: jest.fn(),
    $t: key => key
  }
}

beforeEach(() => {
  postAPI.mockReset()
  postAPI.mockResolvedValue({})
})

test.each([
  ['ablestack-commvault', true],
  ['ablestack-commvault', false],
  ['ablestack-netbackup', true],
  ['ablestack-netbackup', false],
  ['ablestack-veeam', true],
  ['ablestack-veeam', false]
])('%s schedule sends the explicit quiesce choice %s to the API', async (provider, quiescevm) => {
  const vm = context(provider, quiescevm)
  FormSchedule.methods.handleSubmit.call(vm)
  await flushPromises()
  expect(postAPI).toHaveBeenCalledWith('createBackupSchedule', expect.objectContaining({
    virtualmachineid: 'vm-1', schedule: 15, quiescevm
  }))
  expect(vm.closeSchedule).toHaveBeenCalledTimes(1)
})

test('custom schedule submission retains the disabled choice', async () => {
  const vm = context('ablestack-netbackup', false)
  vm.submitFn = jest.fn()
  FormSchedule.methods.handleSubmit.call(vm)
  await flushPromises()
  expect(vm.submitFn).toHaveBeenCalledWith(expect.objectContaining({ quiescevm: false }))
  expect(postAPI).not.toHaveBeenCalled()
})

test.each(['veeam', 'dummy', undefined, {}])('unsupported provider %j omits quiesce even if the form retains a value', async provider => {
  const vm = context(provider, true)
  FormSchedule.methods.handleSubmit.call(vm)
  await flushPromises()
  expect(postAPI).toHaveBeenCalledTimes(1)
  expect(postAPI.mock.calls[0][1]).not.toHaveProperty('quiescevm')
})

test.each(['ablestack-netbackup', 'ablestack-veeam'])('%s explains the Host schedule mode and filesystem freeze behavior', backupProvider => {
  const vm = { backupProvider, $t: key => key }
  expect(FormSchedule.computed.quiesceVmTooltip.call(vm)).toBe('message.backup.volume.quiesce')
  expect(FormSchedule.computed.quiesceVmHelp.call(vm)).toBe('message.backup.schedule.quiesce.host.mode')
})
