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

/** @jest-environment jsdom */
import { shallowMount } from '@vue/test-utils'
import { postAPI } from '@/api'
import ConfigurationValue from '@/views/setting/ConfigurationValue.vue'
import { netBackupScheduleModeKey, netBackupHookTimeoutKey } from '@/utils/netbackupScheduleMode'
import ko from '../../../../public/locales/ko_KR.json'

jest.mock('@/api', () => ({ postAPI: jest.fn() }))

const defaultRecord = { name: 'default.ui.page.size', type: 'Number', value: '20', defaultvalue: '20' }
const wrappers = []

function editor (record = defaultRecord, route = { meta: { name: 'globalsetting' } }, resource, scope) {
  const wrapper = shallowMount(ConfigurationValue, {
    props: { configrecord: record, resource, scope },
    global: {
      mocks: {
        $t: key => key,
        $route: route,
        $store: { getters: { apis: { updateConfiguration: {}, resetConfiguration: {} } }, dispatch: jest.fn() },
        $message: { error: jest.fn() },
        $notification: { error: jest.fn() },
        $messageConfigSuccess: jest.fn(),
        $notifyConfigurationValueChange: jest.fn()
      },
      stubs: {
        AList: { template: '<div><slot /></div>' },
        AListItem: { template: '<div><slot /></div>' },
        ATooltip: { template: '<div><slot /></div>' },
        AInputNumber: { template: '<input />' }
      }
    }
  })
  wrappers.push(wrapper)
  return wrapper
}

beforeEach(() => postAPI.mockReset())
afterEach(() => wrappers.splice(0).forEach(wrapper => wrapper.unmount()))

test('restores the baseline on Escape and suppresses the following Enter', async () => {
  const wrapper = editor()
  const input = wrapper.find('input')
  wrapper.vm.editableValue = 21
  await input.trigger('keydown', { key: 'Escape' })
  expect(wrapper.vm.editableValue).toBe(20)
  expect(wrapper.vm.hasChanges).toBe(false)
  await wrapper.vm.updateConfigurationValue(defaultRecord)
  expect(postAPI).not.toHaveBeenCalled()
})

test.each(['Tab', 'ArrowUp', 'ArrowDown', 'Enter', 'Escape'])('keeps %s available in a numeric input', key => {
  const { vm } = editor()
  const preventDefault = jest.fn()
  vm.handleInputNumberKeyDown({ key, preventDefault }, false)
  expect(preventDefault).not.toHaveBeenCalled()
})

test('cancels array edits and recognizes equal ordered lists', () => {
  const { vm } = editor({ ...defaultRecord, type: 'CSV', value: 'one,two' })
  vm.editableValue = ['one', 'two']
  expect(vm.hasChanges).toBe(false)
  vm.editableValue.push('three')
  expect(vm.hasChanges).toBe(true)
  vm.cancelEditConfigurationValue()
  expect(vm.editableValue).toEqual(['one', 'two'])
  expect(vm.hasChanges).toBe(false)
})

test('blocks duplicate writes and updates the baseline from the server', async () => {
  const { vm } = editor()
  let resolveUpdate
  postAPI.mockReturnValue(new Promise(resolve => { resolveUpdate = resolve }))
  vm.editableValue = 21
  const saving = vm.updateConfigurationValue(defaultRecord)
  expect(vm.valueLoading).toBe(true)
  await vm.updateConfigurationValue(defaultRecord)
  expect(postAPI).toHaveBeenCalledTimes(1)
  resolveUpdate({ updateconfigurationresponse: { configuration: { ...defaultRecord, value: '21' } } })
  await saving
  expect(vm.actualValue).toBe(21)
  expect(vm.hasChanges).toBe(false)
})

test('restores the confirmed value and reports an error for a rejected update', async () => {
  const { vm } = editor()
  const error = jest.spyOn(console, 'error').mockImplementation(() => {})
  try {
    postAPI.mockRejectedValue({ response: { data: { updateconfigurationresponse: { errortext: 'Rejected' } } } })
    vm.editableValue = 21
    await vm.updateConfigurationValue(defaultRecord)
    expect(vm.editableValue).toBe(20)
    expect(vm.hasChanges).toBe(false)
    expect(vm.$messageConfigSuccess).not.toHaveBeenCalled()
    expect(vm.$notification.error).toHaveBeenCalledWith({ message: 'label.error', description: 'Rejected' })
  } finally {
    error.mockRestore()
  }
})

test('keeps the domain scope for saving and resetting', async () => {
  const record = { ...defaultRecord, value: '25' }
  const { vm } = editor(record, { meta: { name: 'domain' }, params: {} }, { id: 'domain-id' })
  postAPI.mockResolvedValueOnce({ updateconfigurationresponse: { configuration: record } })
  postAPI.mockResolvedValueOnce({ resetconfigurationresponse: { configuration: defaultRecord } })
  vm.editableValue = 26
  await vm.updateConfigurationValue(record)
  expect(postAPI).toHaveBeenNthCalledWith(1, 'updateConfiguration', { domainid: 'domain-id', name: record.name, value: 26 })
  expect(vm.actualValue).toBe(26)
  await vm.resetConfigurationValue(record)
  expect(postAPI).toHaveBeenNthCalledWith(2, 'resetConfiguration', { domainid: 'domain-id', name: record.name })
  expect(vm.actualValue).toBe(20)
})

function mount (record = {}) {
  const mocks = {
    $route: { meta: { name: 'globalsetting' }, params: {} },
    $store: { getters: { apis: { updateConfiguration: {}, resetConfiguration: {} } }, dispatch: jest.fn() },
    $t: (key, params = {}) => (ko[key] || key).replace(/\{(\w+)\}/g, (match, name) => params[name] ?? match),
    $confirm: jest.fn(),
    $resetConfigurationValueConfirm: jest.fn(),
    $messageConfigSuccess: jest.fn(),
    $notifyConfigurationValueChange: jest.fn(),
    $message: { error: jest.fn() },
    $notification: { error: jest.fn() }
  }
  const wrapper = shallowMount(ConfigurationValue, {
    props: { configrecord: { name: netBackupScheduleModeKey, type: 'Boolean', value: 'true', defaultvalue: 'true', ...record } },
    global: {
      mocks,
      stubs: {
        AList: { template: '<div><slot /></div>' },
        AListItem: { template: '<div><slot /></div>' },
        ATooltip: { template: '<div><slot /></div>' },
        ASwitch: { props: ['checked', 'disabled'], template: '<button :disabled="disabled">{{ checked }}</button>' },
        AAlert: { props: ['message'], template: '<div>{{ message }}</div>' }
      }
    }
  })
  return { wrapper, mocks, vm: wrapper.vm, record: wrapper.props('configrecord') }
}

beforeEach(() => { jest.clearAllMocks() })

test('changing mode requires directional confirmation before submitting, and cancel restores the switch', async () => {
  const { wrapper, vm, mocks, record } = mount()
  vm.editableValue = false
  vm.updateConfigurationValue(record)
  expect(postAPI).not.toHaveBeenCalled()
  const confirmation = mocks.$confirm.mock.calls[0][0]
  expect(confirmation.content).toBe(ko['message.netbackup.schedule.mode.disable'])
  confirmation.onCancel()
  expect(vm.editableValue).toBe(true)
  expect(vm.actualValue).toBe(true)
  expect(wrapper.text()).not.toContain(ko['message.netbackup.schedule.mode.help'])
  wrapper.unmount()
})

test('confirmed changes show pending state and commit the submitted value only after success', async () => {
  const { wrapper, vm, mocks, record } = mount()
  let finish
  postAPI.mockImplementationOnce(() => new Promise(resolve => { finish = resolve }))
  vm.editableValue = false
  vm.updateConfigurationValue(record)
  const pending = mocks.$confirm.mock.calls[0][0].onOk()
  await vm.$nextTick()
  expect(postAPI).toHaveBeenCalledWith('updateConfiguration', expect.objectContaining({ name: netBackupScheduleModeKey, value: false }))
  expect(wrapper.find('button').attributes('disabled')).toBeDefined()
  expect(vm.actualValue).toBe(true)
  finish({ updateconfigurationresponse: { configuration: { ...record, value: 'false' } } })
  await pending
  expect(vm.actualValue).toBe(false)
  expect(wrapper.emitted('change-config')).toEqual([[{ value: false }]])
  expect(vm.valueLoading).toBe(false)
  wrapper.unmount()
})

test('blocked changes restore the persisted value and explain the server guard in Korean', async () => {
  const { wrapper, vm, mocks, record } = mount()
  const consoleError = jest.spyOn(console, 'error').mockImplementation(() => {})
  postAPI.mockRejectedValueOnce({ response: { data: { updateconfigurationresponse: { errortext: 'NETBACKUP_VM_SCHEDULE_MODE_POLICY_ACTIVE: host-policy' } } } })
  vm.editableValue = false
  vm.updateConfigurationValue(record)
  await mocks.$confirm.mock.calls[0][0].onOk()
  expect(vm.editableValue).toBe(true)
  expect(wrapper.emitted('change-config')).toBeUndefined()
  expect(mocks.$notification.error.mock.calls[0][0].description).toContain('NetBackup 정책을 비활성화')
  expect(mocks.$notification.error.mock.calls[0][0].description).toContain('host-policy')
  consoleError.mockRestore()
  wrapper.unmount()
})

test('resetting the mode confirms enabling the default and still handles server rejection', async () => {
  const { wrapper, vm, mocks, record } = mount({ value: 'false' })
  const consoleError = jest.spyOn(console, 'error').mockImplementation(() => {})
  postAPI.mockRejectedValueOnce({ response: { data: { resetconfigurationresponse: { errortext: 'NETBACKUP_VM_SCHEDULE_MODE_CLEANUP_PENDING: backup-id' } } } })
  vm.confirmResetConfiguration(record)
  expect(postAPI).not.toHaveBeenCalled()
  expect(mocks.$confirm.mock.calls[0][0].content).toBe(ko['message.netbackup.schedule.mode.enable'])
  await mocks.$confirm.mock.calls[0][0].onOk()
  expect(postAPI).toHaveBeenCalledWith('resetConfiguration', expect.objectContaining({ name: netBackupScheduleModeKey }))
  expect(vm.actualValue).toBe(false)
  expect(wrapper.emitted('change-config')).toBeUndefined()
  expect(mocks.$notification.error.mock.calls[0][0].description).toBe(ko['message.netbackup.schedule.mode.cleanup_pending'])
  consoleError.mockRestore()
  wrapper.unmount()
})

test('ordinary settings retain their update and reset flow and raw API error detail', async () => {
  const { wrapper, vm, mocks, record } = mount({ name: 'ordinary.boolean' })
  const consoleError = jest.spyOn(console, 'error').mockImplementation(() => {})
  postAPI.mockRejectedValueOnce({ response: { data: { updateconfigurationresponse: { errortext: 'Existing setting diagnostic' } } } })
  vm.editableValue = false
  await vm.updateConfigurationValue(record)
  expect(mocks.$confirm).not.toHaveBeenCalled()
  expect(mocks.$notification.error.mock.calls[0][0].description).toBe('Existing setting diagnostic')
  vm.confirmResetConfiguration(record)
  expect(mocks.$resetConfigurationValueConfirm).toHaveBeenCalledWith(record, vm.resetConfigurationValue)
  consoleError.mockRestore()
  wrapper.unmount()
})

test('Hook timeout explains automatic Host synchronization and localizes recovery failures', async () => {
  const { wrapper, vm, mocks, record } = mount({ name: netBackupHookTimeoutKey, type: 'Number', value: '86400', defaultvalue: '86400' })
  const consoleError = jest.spyOn(console, 'error').mockImplementation(() => {})
  expect(wrapper.text()).not.toContain(ko['message.netbackup.hook.timeout.help'])
  postAPI.mockRejectedValueOnce({ response: { data: { updateconfigurationresponse: { errortext: 'NETBACKUP_HOOK_TIMEOUT_RECOVERY_PENDING: host-1' } } } })
  vm.editableValue = 3600
  await vm.updateConfigurationValue(record)
  expect(vm.editableValue).toBe(86400)
  expect(mocks.$notification.error.mock.calls[0][0].description).toContain('호스트 설정의 반영·복구 확인')
  expect(wrapper.emitted('change-config')).toBeUndefined()
  expect(mocks.$confirm).not.toHaveBeenCalled()
  consoleError.mockRestore()
  wrapper.unmount()
})

test('reset dialogs retain the NetBackup schedule mode confirmation', async () => {
  const { wrapper, vm, mocks, record } = mount({ value: 'false' })
  await wrapper.setProps({ dialogMode: 'reset' })
  postAPI.mockResolvedValueOnce({ resetconfigurationresponse: { configuration: { ...record, value: 'true' } } })
  vm.confirmResetConfiguration(record)
  expect(postAPI).not.toHaveBeenCalled()
  expect(mocks.$confirm.mock.calls[0][0].content).toBe(ko['message.netbackup.schedule.mode.enable'])
  await mocks.$confirm.mock.calls[0][0].onOk()
  expect(postAPI).toHaveBeenCalledWith('resetConfiguration', expect.objectContaining({ name: netBackupScheduleModeKey }))
  expect(vm.actualValue).toBe(true)
  wrapper.unmount()
})

test('ordinary reset dialogs submit without opening another confirmation', async () => {
  const { wrapper, vm, mocks, record } = mount({ name: 'ordinary.boolean', value: 'false' })
  await wrapper.setProps({ dialogMode: 'reset' })
  postAPI.mockResolvedValueOnce({ resetconfigurationresponse: { configuration: { ...record, value: 'true' } } })
  await vm.confirmResetConfiguration(record)
  expect(postAPI).toHaveBeenCalledWith('resetConfiguration', expect.objectContaining({ name: record.name }))
  expect(mocks.$confirm).not.toHaveBeenCalled()
  expect(mocks.$resetConfigurationValueConfirm).not.toHaveBeenCalled()
  expect(vm.actualValue).toBe(true)
  wrapper.unmount()
})

test.each([
  ['zone', 'zoneid', 'zoneid'],
  ['cluster', 'clusterid', 'clusterid'],
  ['storagepool', 'storageid', 'storageid'],
  ['imagestore', 'imagestoreuuid', 'imagestoreid'],
  ['managementserver', 'managementserverid', 'managementserverid']
])('writes and resets only the selected %s scope', async (type, updateKey, resetKey) => {
  const route = { meta: { name: type }, params: { id: 'other-id' } }
  const { vm } = editor(defaultRecord, route, { id: 'other-id' }, { type, id: 'selected-id' })
  postAPI.mockResolvedValueOnce({ updateconfigurationresponse: { configuration: defaultRecord } })
  postAPI.mockResolvedValueOnce({ resetconfigurationresponse: { configuration: defaultRecord } })
  vm.editableValue = 21
  await vm.updateConfigurationValue(defaultRecord)
  expect(postAPI).toHaveBeenNthCalledWith(1, 'updateConfiguration', { [updateKey]: 'selected-id', name: defaultRecord.name, value: 21 })
  await vm.resetConfigurationValue(defaultRecord)
  expect(postAPI).toHaveBeenNthCalledWith(2, 'resetConfiguration', { [resetKey]: 'selected-id', name: defaultRecord.name })
})

test('allows removing a scoped override equal to the default', async () => {
  const route = { meta: { name: 'zone' } }
  const { vm } = editor(defaultRecord, route, undefined, { type: 'zone', id: 'zone-id' })
  postAPI.mockResolvedValue({ resetconfigurationresponse: { configuration: defaultRecord } })
  expect(vm.canReset).toBe(true)
  await vm.resetConfigurationValue(defaultRecord)
  expect(postAPI).toHaveBeenCalledWith('resetConfiguration', { zoneid: 'zone-id', name: defaultRecord.name })
})

test('blocks both writes if the selected resource has no ID', async () => {
  const route = { meta: { name: 'managementserver' }, params: { id: 'other-id' } }
  const { vm } = editor(defaultRecord, route, { id: 'other-id' }, { type: 'managementserver' })
  await vm.resetConfigurationValue(defaultRecord)
  vm.editableValue = 21
  await vm.updateConfigurationValue(defaultRecord)
  expect(postAPI).not.toHaveBeenCalled()
})
