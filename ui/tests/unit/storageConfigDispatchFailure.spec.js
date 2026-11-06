// Licensed to the Apache Software Foundation (ASF) under one or more
// contributor license agreements. See NOTICE. Apache License, Version 2.0.
// http://www.apache.org/licenses/LICENSE-2.0
import { shallowMount } from '@vue/test-utils'
import Panel from '@/components/storage/StorageConfigurationOperationPanel.vue'
import { decodeConfigurationOperation } from '@/utils/storageConfigOperation'
import wire from '../fixtures/storage-1459-dispatch-wire.json'
import preparationWire from '../fixtures/storage-1459-preparation-wire.json'
import ko from '../../public/locales/ko_KR.json'
import en from '../../public/locales/en.json'

jest.mock('@/api', () => ({ getAPI: jest.fn() }))

const render = operation => shallowMount(Panel, {
  props: { operation },
  global: {
    mocks: { $t: key => key },
    stubs: {
      'a-alert': { props: ['description', 'message'], template: '<aside>{{ message }} {{ description }}</aside>' },
      'a-descriptions': { template: '<dl><slot /></dl>' },
      'a-descriptions-item': { props: ['label'], template: '<div>{{ label }}<slot /></div>' },
      'a-button': { template: '<button><slot /></button>' }
    }
  }
})

describe('actual coordinator DAO/API dispatch rejection guidance', () => {
  test('the real serialized no-start failure renders its preserved code and dispatch-specific guidance', () => {
    const operation = decodeConfigurationOperation(wire)
    expect(operation.state).toBe('ROLLED_BACK')
    expect(operation.originalfailure.started).toBe(false)
    const wrapper = render(operation)
    expect(wrapper.text()).toContain('QGA_EXEC_ARGUMENT_TOO_LARGE')
    expect(wrapper.text()).toContain('label.storage.configuration.phase.dispatch')
    expect(wrapper.text()).toContain('message.storage.configuration.dispatch.not.started')
    expect(wrapper.text()).toContain('label.storage.configuration.rollback.not_needed')
    expect(wrapper.vm.alertType).toBe('warning')
    expect(wrapper.findAll('button')).toHaveLength(1)
    expect(wrapper.emitted('resume')).toBeUndefined()
    wrapper.unmount()
  })

  test('an uncertain response keeps recovery guidance and does not claim the command was never started', () => {
    const operation = { ...decodeConfigurationOperation(wire), state: 'RECOVERY_REQUIRED', phase: 'OBSERVING', originalfailure: {}, canresume: false }
    const wrapper = render(operation)
    expect(wrapper.text()).toContain('message.storage.configuration.recovery.required')
    expect(wrapper.text()).not.toContain('message.storage.configuration.dispatch.not.started')
    expect(wrapper.emitted('resume')).toBeUndefined()
    wrapper.unmount()
  })

  test('Korean and English dispatch messages exist on the unchanged VM standard descriptions', () => {
    for (const locale of [ko, en]) {
      expect(locale['label.storage.configuration.phase.dispatch']).toBeTruthy()
      expect(locale['message.storage.configuration.dispatch.not.started']).toBeTruthy()
      expect(locale['message.storage.configuration.preparation.failed']).toBeTruthy()
      expect(locale['label.storage.configuration.preparation.failed']).toBeTruthy()
    }
  })
  test('actual DAO/API preparation failure keeps its original code and read-only inspection cause', () => {
    const operation = decodeConfigurationOperation(preparationWire)
    expect(operation.originalfailure).toMatchObject({
      code: 'DIRECTORY_OBSERVATION_UNAVAILABLE',
      phase: 'VALIDATING',
      stage: 'DIRECTORY_POLICY_INSPECT',
      reason: 'QGA_COMMAND_TIMEOUT'
    })
    const wrapper = render(operation)
    expect(wrapper.text()).toContain('DIRECTORY_OBSERVATION_UNAVAILABLE')
    expect(wrapper.text()).toContain('QGA_COMMAND_TIMEOUT')
    expect(wrapper.text()).toContain('DIRECTORY_POLICY_INSPECT')
    expect(wrapper.text()).toContain('label.storage.configuration.preparation.failed')
    expect(wrapper.text()).toContain('message.storage.configuration.preparation.failed')
    expect(wrapper.text()).not.toContain('message.storage.configuration.dispatch.not.started')
    expect(wrapper.text()).not.toContain('message.storage.configuration.rolled.back')
    expect(wrapper.emitted('resume')).toBeUndefined()
    wrapper.unmount()
  })
})
