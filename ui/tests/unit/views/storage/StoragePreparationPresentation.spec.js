// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements. See the NOTICE file
// distributed with this work for additional information.
import { mount } from '@vue/test-utils'
import Preparation from '@/views/storage/StoragePreparationStatus'
import { getAPI } from '@/api'

jest.mock('@/api', () => ({ getAPI: jest.fn() }))
jest.mock('@/utils/listRefresh', () => ({ canRefreshList: () => true }))

const now = 1792483200000
const receipt = (offset = 0) => ({
  id: 'instance',
  sharedfilesystemid: 'sharedfs',
  purpose: 'INITIAL_CONFIG',
  ready: true,
  status: 'READY',
  reason: 'CURRENT_BOOT_READY',
  bootid: 'boot-a',
  observedatmillis: now - 1000 + offset,
  expiresatmillis: now + 29000 + offset
})
function view () {
  return mount({ ...Preparation, mounted () {}, watch: {} }, {
    props: { sharedFileSystemId: 'sharedfs', instanceId: 'instance', active: true },
    global: {
      mocks: {
        $store: { getters: { userInfo: { id: 'admin' }, apis: { getStorageServicePreparationStatus: {} } }, state: { user: { token: 'session' } } },
        $t: (key, parameters) => key + (parameters?.time ? ' ' + parameters.time : '')
      }
    }
  })
}

describe('Preparation card current observation presentation', () => {
  beforeEach(() => { jest.useFakeTimers(); jest.spyOn(Date, 'now').mockReturnValue(now); getAPI.mockReset() })
  afterEach(() => { jest.clearAllTimers(); jest.useRealTimers(); jest.restoreAllMocks() })

  it('renders one confirming state before a second same-BOOT witness without a current-ready success reason', async () => {
    const wrapper = view()
    try {
      await wrapper.setData({ receipt: receipt(), lastObservedReceipt: receipt(), clock: now })
      expect(wrapper.find('[role=status]').text()).toContain('status.CONFIRMING')
      expect(wrapper.find('.ant-alert-message').text()).toBe('message.storage.service.preparation.confirming')
      expect(wrapper.text()).not.toContain('reason.CURRENT_BOOT_READY')
      expect(wrapper.find('.ant-tag-green').exists()).toBe(false)
      expect(wrapper.vm.confirmedReady).toBe(false)
    } finally { wrapper.unmount() }
  })

  it('removes all ready and confirming text when the observation expires and withdraws the published receipt', async () => {
    const wrapper = view()
    try {
      await wrapper.setData({ receipt: receipt(500), previousReceipt: receipt(), lastObservedReceipt: receipt(500), clock: now })
      expect(wrapper.vm.confirmedReady).toBe(true)
      expect(wrapper.find('.ant-tag-green').exists()).toBe(true)
      await wrapper.setData({ clock: now + 30001 })
      wrapper.vm.publish()
      expect(wrapper.find('[role=status]').text()).toContain('status.OBSERVATION_UNAVAILABLE')
      expect(wrapper.find('.ant-alert-message').text()).toBe('message.storage.service.preparation.observation.expired')
      expect(wrapper.find('.ant-alert-description').text()).toContain('message.storage.service.preparation.last.observed')
      expect(wrapper.find('.ant-alert-description').text()).toContain('message.storage.service.preparation.update.required')
      expect(wrapper.text()).not.toContain('reason.CURRENT_BOOT_READY')
      expect(wrapper.text()).not.toContain('preparation.confirming')
      expect(wrapper.find('.ant-tag-green').exists()).toBe(false)
      expect(wrapper.find('.ant-descriptions').exists()).toBe(false)
      expect(wrapper.find('button').attributes('disabled')).toBeUndefined()
      expect(wrapper.emitted('status').slice(-1)[0][0]).toMatchObject({ ready: false, receipt: null, status: 'OBSERVATION_UNAVAILABLE' })
    } finally { wrapper.unmount() }
  })

  it('publishes and displays the same unknown state when automatic observation ends, even while the last receipt is fresh', async () => {
    const wrapper = view()
    try {
      await wrapper.setData({ receipt: receipt(500), previousReceipt: receipt(), lastObservedReceipt: receipt(500), clock: now, startedAt: now - 15 * 60 * 1000 })
      wrapper.vm.schedule()
      await wrapper.vm.$nextTick()
      expect(wrapper.vm.automaticEnded).toBe(true)
      expect(wrapper.vm.confirmedReady).toBe(false)
      expect(wrapper.find('[role=status]').text()).toContain('status.OBSERVATION_UNAVAILABLE')
      expect(wrapper.find('.ant-alert-message').text()).toBe('message.storage.service.preparation.auto.limit')
      expect(wrapper.find('.ant-alert-description').text()).toContain('message.storage.service.preparation.last.observed')
      expect(wrapper.text()).not.toContain('reason.CURRENT_BOOT_READY')
      expect(wrapper.text()).not.toContain('preparation.confirming')
      expect(wrapper.find('.ant-tag-green').exists()).toBe(false)
      expect(wrapper.emitted('status').slice(-1)[0][0]).toMatchObject({ ready: false, receipt: null })
    } finally { wrapper.unmount() }
  })

  it('retains only historical observation time after a rejected read and never retains success content', async () => {
    const wrapper = view()
    try {
      await wrapper.setData({ receipt: receipt(500), previousReceipt: receipt(), lastObservedReceipt: receipt(500), clock: now })
      getAPI.mockRejectedValue(new Error('controlled failed read'))
      await wrapper.vm.refresh(true)
      expect(wrapper.find('.ant-alert-message').text()).toBe('message.storage.service.preparation.read.failed')
      expect(wrapper.find('.ant-alert-description').text()).toContain('message.storage.service.preparation.last.observed')
      expect(wrapper.text()).not.toContain('reason.CURRENT_BOOT_READY')
      expect(wrapper.vm.confirmedReady).toBe(false)
      expect(wrapper.vm.receipt).toBeNull()
    } finally { wrapper.unmount() }
  })

  it('clears ended observation proof immediately when Update starts and confirms new witnesses before showing success again', async () => {
    const wrapper = view()
    try {
      await wrapper.setData({ receipt: receipt(500), previousReceipt: receipt(), lastObservedReceipt: receipt(500), clock: now, automaticEnded: true })
      let complete
      getAPI.mockImplementationOnce(() => new Promise(resolve => { complete = resolve }))
      const pending = wrapper.vm.refresh(true)
      await wrapper.vm.$nextTick()
      expect(wrapper.find('[role=status]').text()).toContain('status.CHECKING')
      expect(wrapper.text()).not.toContain('reason.CURRENT_BOOT_READY')
      expect(wrapper.vm.confirmedReady).toBe(false)
      expect(wrapper.emitted('status').slice(-1)[0][0]).toMatchObject({ ready: false, receipt: null })
      complete({ getstorageservicepreparationstatusresponse: { storageservicepreparationstatus: receipt(600) } })
      await pending
      expect(wrapper.vm.status).toBe('CONFIRMING')
      getAPI.mockResolvedValueOnce({ getstorageservicepreparationstatusresponse: { storageservicepreparationstatus: receipt(700) } })
      await wrapper.vm.refresh()
      expect(wrapper.vm.status).toBe('READY')
      expect(wrapper.vm.confirmedReady).toBe(true)
    } finally { wrapper.unmount() }
  })

  it('retains the first closed failure diagnosis separately after a manual refresh and clears it on context reset', async () => {
    const wrapper = view()
    try {
      const failure = { ...receipt(), ready: false, status: 'OBSERVATION_UNAVAILABLE', reason: 'HEALTH_RPC_OBSERVATION_FAILED', diagnostics: JSON.stringify({ healthOrigin: 'LIVE', failedObservation: 'HEALTH', secret: 'synthetic-hidden-value', rawHealth: { password: 'synthetic-hidden-value' } }) }
      getAPI.mockResolvedValueOnce({ getstorageservicepreparationstatusresponse: { storageservicepreparationstatus: failure } })
      await wrapper.vm.refresh()
      expect(wrapper.vm.firstFailureObservation.diagnostics).toEqual({ healthOrigin: 'LIVE', failedObservation: 'HEALTH' })
      getAPI.mockResolvedValueOnce({ getstorageservicepreparationstatusresponse: { storageservicepreparationstatus: { ...receipt(600), diagnostics: JSON.stringify({ healthOrigin: 'CACHE' }) } } })
      await wrapper.vm.refresh(true)
      expect(wrapper.vm.diagnosticObservation.reason).toBe('HEALTH_RPC_OBSERVATION_FAILED')
      expect(wrapper.vm.diagnosticObservation.diagnostics.healthOrigin).toBe('LIVE')
      expect(wrapper.vm.lastObservedReceipt.diagnostics.healthOrigin).toBe('CACHE')
      await wrapper.find('.ant-collapse-header').trigger('click')
      expect(wrapper.text()).toContain('diagnostics.first.failure')
      expect(wrapper.text()).toContain('HEALTH_RPC_OBSERVATION_FAILED')
      expect(wrapper.text()).not.toContain('synthetic-hidden-value')
      wrapper.vm.reset()
      await wrapper.vm.$nextTick()
      expect(wrapper.vm.firstFailureObservation).toBeNull()
      expect(wrapper.vm.diagnosticObservation).toBeNull()
      expect(wrapper.find('.ant-collapse-header').exists()).toBe(false)
    } finally { wrapper.unmount() }
  })

  it('keeps explicit preparation failures terminal instead of silently polling them as startup', async () => {
    for (const status of ['BLOCKED', 'OBSERVATION_UNAVAILABLE']) {
      const wrapper = view()
      try {
        await wrapper.setData({ receipt: { ...receipt(), ready: false, status, reason: 'BOOT_RECONCILE_FAILED' }, clock: now, startedAt: now })
        wrapper.vm.schedule()
        jest.advanceTimersByTime(10000)
        expect(getAPI).not.toHaveBeenCalled()
        expect(wrapper.vm.presentation.ready).toBe(false)
        expect(wrapper.vm.presentation.type).toBe('warning')
      } finally { wrapper.unmount() }
    }
  })
})
