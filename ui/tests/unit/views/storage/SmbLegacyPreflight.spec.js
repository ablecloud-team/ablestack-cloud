// Licensed under the Apache License, Version 2.0. See NOTICE.
import { shallowMount } from '@vue/test-utils'
import Preflight from '@/components/storage/StorageConfigurationPreflightPanel'
import Operation from '@/components/storage/StorageConfigurationOperationPanel'
import { decodeConfigurationPreflight, preflightApplicable } from '@/utils/storageConfigPreflight'
import { decodeConfigurationOperation } from '@/utils/storageConfigOperation'
import preflightWire from '../../../fixtures/smb-legacy-preflight-wire.json'
import operationWire from '../../../fixtures/smb-legacy-operation-wire.json'
import { getAPI } from '@/api'
jest.mock('@/api', () => ({ getAPI: jest.fn() }))

const container = { template: '<div><slot /></div>' }
const item = { props: ['label'], template: '<p><b>{{ label }}</b><slot /></p>' }
const alert = { props: ['message', 'description'], template: '<aside>{{ message }} {{ description }}</aside>' }
const checkbox = { props: ['checked', 'disabled'], emits: ['change'], template: '<input type="checkbox" :checked="checked" :disabled="disabled" @change="$emit(\'change\', $event)" />' }
const mountGlobal = { mocks: { $t: key => key }, stubs: { ADescriptions: container, ADescriptionsItem: item, AAlert: alert, ACheckbox: checkbox } }
let wrapper
afterEach(() => { if (wrapper) wrapper.unmount(); jest.clearAllMocks() })

test('actual guest -> real API serializer preview displays the default binding and requested handoff', async () => {
  const report = decodeConfigurationPreflight(preflightWire)
  wrapper = shallowMount(Preflight, { props: { report, acknowledged: false }, global: mountGlobal })
  expect(wrapper.find('[data-testid="smb-legacy-handoff"]').text()).toContain('0.0.0.0:445')
  expect(wrapper.text()).toContain('[::]:445')
  expect(wrapper.text()).toContain('10.10.13.188:445')
  expect(report.impact.drainrequired).toBe(true)
  expect(preflightApplicable(report, false, Date.parse(report.expiresat) - 1)).toBe(false)
  await wrapper.find('input[type="checkbox"]').setValue(true)
  expect(wrapper.emitted('acknowledge')[0]).toEqual([true])
  expect(getAPI).not.toHaveBeenCalled()
})
test('actual interruption cannot be acknowledged past a drain failure or expired observation', () => {
  const report = decodeConfigurationPreflight(preflightWire)
  report.status = 'BLOCKED'; report.checks.push({ code: 'DRAIN_REQUIRED', blocking: true })
  wrapper = shallowMount(Preflight, { props: { report, acknowledged: true }, global: mountGlobal })
  expect(wrapper.find('input').attributes('disabled')).toBeDefined()
  expect(preflightApplicable(report, true, Date.parse(report.expiresat) - 1)).toBe(false)
  report.status = 'READY'; report.checks = []
  expect(preflightApplicable(report, true, Date.parse(report.expiresat) + 1)).toBe(false)
})
test('actual coordinator -> DAO -> response formatter keeps first child and rollback failures separate', () => {
  const operation = decodeConfigurationOperation(operationWire)
  wrapper = shallowMount(Operation, { props: { operation }, global: mountGlobal })
  expect(wrapper.text()).toContain('PROTOCOL_APPLY_FAILED')
  expect(wrapper.text()).toContain('SMB_LEGACY_IDENTITY_UNCONFIRMED')
  expect(wrapper.text()).toContain('SMB_LEGACY_BINDING_UNCONFIRMED')
  expect(wrapper.text()).toContain('label.storage.configuration.first.failure.detail')
  expect(getAPI).not.toHaveBeenCalled()
})
test('unstructured child output is not rendered as a public diagnostic', () => {
  const operation = decodeConfigurationOperation(operationWire)
  operation.originalfailure.detail = { code: 'SMB_SECRET\nraw output', message: 'private-value' }
  wrapper = shallowMount(Operation, { props: { operation }, global: mountGlobal })
  expect(wrapper.text()).not.toContain('private-value')
  expect(wrapper.text()).not.toContain('SMB_SECRET')
  expect(wrapper.text()).toContain('PROTOCOL_APPLY_FAILED')
})
test('normal managed or non-SMB preflight keeps the existing layout without a duplicate title', () => {
  const report = decodeConfigurationPreflight(preflightWire); delete report.smblegacypreview
  wrapper = shallowMount(Preflight, { props: { report }, global: mountGlobal })
  expect(wrapper.find('[data-testid="smb-legacy-handoff"]').exists()).toBe(false)
})
