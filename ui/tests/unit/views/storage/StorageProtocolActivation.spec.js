// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements. See the NOTICE file
// distributed with this work for additional information.
import { mount, shallowMount, flushPromises } from '@vue/test-utils'
import Widget from '@/views/storage/StorageProtocolAddressStatus'
import SharedFSTab from '@/views/storage/SharedFSTab'
import { protocolAddressObservation, protocolActivationFailurePresentation } from '@/utils/storageProtocolActivation'
import { getAPI, postAPI } from '@/api'

jest.mock('@/api', () => ({ getAPI: jest.fn(), postAPI: jest.fn() }))
const boot = '11111111-1111-4111-8111-111111111111'
const now = 1792125000
const readiness = () => ({
  schemaVersion: 1,
  kind: 'SHAREDFS_NETWORK_READINESS',
  success: true,
  status: 'OK',
  mode: 'STATIC',
  observedEpoch: now,
  bootId: boot,
  expected: { mode: 'STATIC', macAddress: '02:01:00:cc:00:17', ipAddress: '10.10.13.228', cidr: '10.10.0.0/16', gateway: '10.10.0.1', dns: ['10.10.13.10'] },
  actual: {
    mode: 'STATIC',
    interfaceName: 'ens3',
    macAddress: '02:01:00:cc:00:17',
    addresses: [{ ipAddress: '10.10.13.228', prefixLength: 16 }],
    defaultRoutes: [{ gateway: '10.10.0.1', interfaceName: 'ens3' }],
    dns: ['10.10.13.10'],
    dhcpUnit: { name: 'cloud-dhclient@ens3.service', active: false, masked: true },
    dhcpProcessCount: 0
  },
  mismatches: []
})
const observe = extra => protocolAddressObservation({ readiness: readiness(), currentBootId: boot, ip: '10.10.13.228', now, ...extra })

describe('Protocol activation address observation follows server evidence', () => {
  beforeEach(() => { getAPI.mockReset(); postAPI.mockReset() })

  it('does not derive an observed address from management/configuration input alone', () => {
    expect(observe({ readiness: null }).status).toBe('UNKNOWN')
    expect(observe({ readiness: { ...readiness(), bootId: 'invalid' }, currentBootId: 'invalid' }).status).toBe('UNKNOWN')
    expect(observe({ readiness: null, registeredIp: '10.10.13.228', configuredPort: 3260, port: 2049 }).status).toBe('UNKNOWN')
    expect(observe({ port: 3260 })).toEqual(observe({ port: 2049 }))
    expect(observe().status).toBe('OBSERVED')
  })

  it.each([
    { currentBootId: '' }, { currentBootId: 'different-boot' }, { now: now + 31 }, { stale: true }, { readFailed: true }, { now: now - 6 }
  ])('withdraws current address observation for missing, mismatched or expired evidence %p', extra => {
    expect(observe(extra).status).toBe('UNKNOWN')
  })

  it('distinguishes an absent new address from unavailable or explicitly failed server observations', () => {
    expect(observe({ ip: '10.10.13.229' }).status).toBe('NOT_OBSERVED')
    const value = readiness()
    Object.assign(value, { status: 'ERROR', success: false, mismatches: ['ADDRESS_MISMATCH'] })
    expect(observe({ readiness: value }).status).toBe('ERROR')
    expect(observe({ ip: '' }).status).toBe('EMPTY')
    expect(postAPI).not.toHaveBeenCalled()
  })

  it('renders address reuse, new address and independent protocol port information without submitting any request', async () => {
    jest.spyOn(Date, 'now').mockReturnValue(now * 1000)
    const wrapper = mount(Widget, { props: { readiness: readiness(), currentBootId: boot, ip: '10.10.13.228', mode: 'EXISTING' }, global: { mocks: { $t: key => key } } })
    try {
      expect(wrapper.text()).toContain('message.storage.service.activation.address.reuse')
      expect(wrapper.text()).toContain('message.storage.service.activation.port.independent')
      expect(wrapper.find('[role=status]').classes()).toContain('ant-tag-green')
      await wrapper.setProps({ mode: 'NEW', ip: '10.10.13.229' })
      expect(wrapper.text()).toContain('message.storage.service.activation.address.new')
      expect(wrapper.find('[role=status]').text()).toContain('NOT_OBSERVED')
      expect(getAPI).not.toHaveBeenCalled()
      expect(postAPI).not.toHaveBeenCalled()
    } finally { wrapper.unmount(); jest.restoreAllMocks() }
  })

  it('updates an open dialog observation to unknown at expiry without background API calls', async () => {
    jest.useFakeTimers()
    const clock = jest.spyOn(Date, 'now').mockReturnValue(now * 1000)
    const wrapper = mount(Widget, { props: { readiness: readiness(), currentBootId: boot, ip: '10.10.13.228' }, global: { mocks: { $t: key => key } } })
    try {
      expect(wrapper.find('[role=status]').classes()).toContain('ant-tag-green')
      clock.mockReturnValue((now + 31) * 1000)
      jest.advanceTimersByTime(30001)
      await wrapper.vm.$nextTick()
      expect(wrapper.find('[role=status]').text()).toContain('UNKNOWN')
      expect(wrapper.find('[role=status]').classes()).not.toContain('ant-tag-green')
      expect(getAPI).not.toHaveBeenCalled()
      expect(postAPI).not.toHaveBeenCalled()
    } finally { wrapper.unmount(); jest.clearAllTimers(); jest.useRealTimers(); jest.restoreAllMocks() }
  })
})

function context () {
  const value = {
    resource: { id: 'fs', name: 'owned' },
    storageService: { instance: { id: 'instance', name: 'owned' }, domains: [] },
    forms: { enableProtocol: { protocol: 'NFS', listenipmode: 'EXISTING', listenip: '10.10.13.228', port: 2049, protocolmode: 'V4_ONLY', idmappingmode: 'NAME_DOMAIN' } },
    currentTab: 'nfs',
    nfsDesiredIdMode: 'NAME_DOMAIN',
    actionLoading: {},
    actionModal: { visible: true, type: 'enableProtocol', context: {}, loading: false },
    $store: { getters: { userInfo: { id: 'admin' } } },
    $route: { path: '/sharedfs/fs' },
    $t: key => key,
    $message: { error: jest.fn() },
    cleanParams: value => value,
    $pollJob: jest.fn().mockResolvedValue({ jobstatus: 1 }),
    refreshAfterStorageAction: jest.fn().mockResolvedValue(),
    refreshStorageOperationHistory: jest.fn(),
    cancelAdMutation: jest.fn()
  }
  for (const key of ['validateListenIpSelection', 'isValidIpv4', 'runStorageAction', 'enableProtocol']) value[key] = SharedFSTab.methods[key].bind(value)
  return value
}

describe('Protocol activation keeps the existing single API request and recovery gate', () => {
  beforeEach(() => { getAPI.mockReset(); postAPI.mockReset() })

  it('reuses an existing IP and sends NFS 2049 only to the existing activation API', async () => {
    const value = context()
    postAPI.mockResolvedValue({ enablestorageserviceprotocolresponse: { jobid: 'job' } })
    expect(await value.enableProtocol()).toBe(true)
    expect(postAPI).toHaveBeenCalledTimes(1)
    expect(postAPI.mock.calls[0][0]).toBe('enableStorageServiceProtocol')
    expect(postAPI.mock.calls[0][1]).toMatchObject({ instanceid: 'instance', protocol: 'NFS', listenip: '10.10.13.228', port: 2049 })
    expect(postAPI.mock.calls[0][1]).not.toHaveProperty('deferCanonical')
    expect(postAPI.mock.calls[0][1]).not.toHaveProperty('prepareAddress')
  })

  it.each([
    ['NFS_SOURCE_CHANGED', 'source'],
    ['ENDPOINT_PREPARATION_FAILED', 'address'],
    ['NFS_RUNTIME_NOT_READY', 'runtime'],
    ['ENDPOINT_SOURCE_RESTORE_FAILED', 'source.restore']
  ])('shows the fixed public cause for %s and preserves recovery without resubmitting', async (code, category) => {
    const value = context()
    const phase = code === 'NFS_SOURCE_CHANGED' ? 'VERIFY_SOURCE' : (code === 'NFS_RUNTIME_NOT_READY' ? 'WAIT_RUNTIME_READY' : 'PREPARED')
    value.$pollJob.mockResolvedValue({ jobstatus: 2, jobresult: { errortext: 'BLOCKED failurePhase=' + phase + '; code=' + code + '; raw-private-detail=synthetic-private-value' } })
    value.refreshAfterStorageAction.mockRejectedValue(new Error('follow-up read failed'))
    postAPI.mockResolvedValue({ enablestorageserviceprotocolresponse: { jobid: 'job' } })
    expect(await value.enableProtocol()).toBe(false)
    expect(value.actionModal.error).toBe('message.storage.service.activation.failure.' + category + ' message.storage.service.activation.failure.recovery.required')
    expect(JSON.stringify(value.$message.error.mock.calls)).not.toContain('synthetic-private-value')
    expect(value.actionModal.visible).toBe(true)
    expect(value.refreshStorageOperationHistory).toHaveBeenCalledWith('instance')
    expect(postAPI).toHaveBeenCalledTimes(1)
    value.storageMutationBlocked = true
    value.storageRecoveryGateMessage = 'recovery required'
    expect(await value.enableProtocol()).toBe(false)
    expect(postAPI).toHaveBeenCalledTimes(1)
  })

  it('serializes two confirmation clicks while the activation result is pending', async () => {
    const value = context()
    let complete
    postAPI.mockResolvedValue({ enablestorageserviceprotocolresponse: { jobid: 'job' } })
    value.$pollJob.mockImplementation(() => new Promise(resolve => { complete = resolve }))
    const pending = value.enableProtocol()
    await Promise.resolve(); await Promise.resolve()
    expect(await value.enableProtocol()).toBe(false)
    expect(postAPI).toHaveBeenCalledTimes(1)
    complete({ jobstatus: 1 })
    expect(await pending).toBe(true)
  })

  it.each(['job', 'http'])('shows a runtime upgrade requirement without implying rollback or automatically retrying: %s', async path => {
    const value = context()
    if (path === 'job') {
      postAPI.mockResolvedValue({ enablestorageserviceprotocolresponse: { jobid: 'job' } })
      value.$pollJob.mockResolvedValue({ jobstatus: 2, jobresult: { errortext: 'ENDPOINT_PREPARATION_UNSUPPORTED' } })
    } else postAPI.mockRejectedValue({ response: { data: { enablestorageserviceprotocolresponse: { errortext: 'ENDPOINT_PREPARATION_UNSUPPORTED' } } } })
    expect(await value.enableProtocol()).toBe(false)
    expect(value.actionModal.error).toBe('message.storage.service.activation.failure.unsupported')
    expect(value.actionModal.error).not.toContain('recovery')
    expect(value.actionModal.error).not.toContain('rolled.back')
    expect(postAPI).toHaveBeenCalledTimes(1)
    expect(value.forms.enableProtocol.listenip).toBe('10.10.13.228')
    expect(protocolActivationFailurePresentation({ errortext: 'ENDPOINT_PREPARATION_UNSUPPORTED' })).toMatchObject({ operationId: null, recoveryRequired: false, rolledBack: false })
  })

  it.each([
    ['NFS_SOURCE_CHANGED', 'VERIFY_SOURCE', 'RECOVERY_REQUIRED', 'source', 'source.restore', ' | rollback: ENDPOINT_SOURCE_RESTORE_FAILED'],
    ['ENDPOINT_PREPARATION_UNSUPPORTED', 'APPLYING', 'RECOVERY_REQUIRED', 'unsupported', 'recovery.required', ' | rollback: checkpoint cleanup failed'],
    ['ENDPOINT_PREPARATION_UNSUPPORTED', 'APPLYING', 'BLOCKED', 'unsupported', 'recovery.required', ' | checkpoint cleanup: original writer cleanup failed']
  ])('keeps the real modal primary cause and exact recovery gate after %s / %s / %s', async (code, phase, jobState, category, recoveryCategory, rollbackDiagnostic) => {
    const Widget = { ...SharedFSTab, mixins: [], watch: {}, created () {}, mounted () {} }
    const wrapper = shallowMount(Widget, {
      props: { resource: { id: 'fs', name: 'owned' } },
      global: {
        provide: { parentFetchData: jest.fn() },
        mocks: {
          $t: key => key,
          $message: { error: jest.fn() },
          $route: { path: '/sharedfs/fs', fullPath: '/sharedfs/fs?tab=nfs', query: {}, params: {} },
          $getApiParams: () => ({ idempotencykey: {} }),
          $store: { getters: { userInfo: { id: 'admin' }, project: null, features: {}, resourceTabPosition: 'left', apis: { listStorageServiceInstances: {}, listStorageServiceOperations: {} } } },
          $pollJob: jest.fn().mockResolvedValue({ jobstatus: 2, jobresult: { errortext: 'Storage Service operation aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa ' + jobState + ': failurePhase=' + phase + '; ' + code } })
        },
        stubs: {
          'a-spin': { template: '<div><slot /></div>' },
          'a-tabs': true,
          'a-form': { template: '<div><slot /></div>' },
          'a-form-item': { template: '<div><slot name="label" /><slot /></div>' },
          'a-select': { props: ['value'], inheritAttrs: false, template: '<div><slot /></div>' },
          'a-alert': { props: ['message'], template: '<div class="alert">{{ message }}<slot name="description" /></div>' },
          'a-modal': { props: ['visible', 'okButtonProps', 'confirmLoading'], emits: ['ok', 'cancel'], template: '<section v-if="visible"><slot /><button class="confirm" :disabled="confirmLoading || (okButtonProps && okButtonProps.disabled)" @click="$emit(\'ok\')">Confirm</button></section>' }
        }
      }
    })
    try {
      await wrapper.setData({
        currentTab: 'nfs',
        actionModal: { visible: true, type: 'enableProtocol', context: {}, loading: false, error: '' },
        storageService: { ...wrapper.vm.storageService, instance: { id: 'instance', name: 'owned' }, loading: false, domains: [] },
        forms: { ...wrapper.vm.forms, enableProtocol: { protocol: 'NFS', listenipmode: 'EXISTING', listenip: '10.10.13.228', port: 2049, protocolmode: 'V4_ONLY', idmappingmode: 'NAME_DOMAIN' } }
      })
      wrapper.vm.acceptStorageOperationRead(wrapper.vm.beginStorageOperationRead('instance'), [], false)
      wrapper.vm.refreshAfterStorageAction = jest.fn().mockResolvedValue()
      wrapper.vm.refreshStorageOperationHistory = jest.fn(async () => {
        wrapper.vm.acceptStorageOperationRead(wrapper.vm.beginStorageOperationRead('instance'), [{ id: 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', instanceid: 'instance', revision: 5, state: 'RECOVERY_REQUIRED', phase: 'RECOVERY_REQUIRED', diagnostic: 'failurePhase=' + phase + '; ' + code + rollbackDiagnostic }], false)
      })
      postAPI.mockResolvedValue({ enablestorageserviceprotocolresponse: { jobid: 'job' } })
      await wrapper.vm.$nextTick()
      expect(wrapper.find('.confirm').attributes('disabled')).toBeUndefined()
      await wrapper.find('.confirm').trigger('click')
      await flushPromises()
      expect(postAPI).toHaveBeenCalledTimes(1)
      expect(postAPI.mock.calls[0][0]).toBe('enableStorageServiceProtocol')
      expect(wrapper.vm.actionModal.error).toBe('message.storage.service.activation.failure.' + category + ' message.storage.service.activation.failure.' + recoveryCategory)
      expect(wrapper.vm.actionModal.visible).toBe(true)
      expect(wrapper.vm.storageMutationBlocked).toBe(true)
      expect(wrapper.find('.confirm').attributes('disabled')).toBeDefined()
      expect(wrapper.text()).toContain('message.storage.service.activation.failure.' + recoveryCategory)
      await wrapper.find('.confirm').trigger('click')
      await flushPromises()
      expect(postAPI).toHaveBeenCalledTimes(1)
      expect(wrapper.vm.actionModal.error).toBe('message.storage.service.activation.failure.' + category + ' message.storage.service.activation.failure.' + recoveryCategory)
    } finally { wrapper.unmount() }
  })

  it.each(['other-operation', 'stale-read'])('never appends a rollback code from an unrelated or stale operation read: %s', async kind => {
    const value = context()
    value.storageOperationReadSequence = 1
    value.$pollJob.mockResolvedValue({ jobstatus: 2, jobresult: { errortext: 'Storage Service operation aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa RECOVERY_REQUIRED: failurePhase=VERIFY_SOURCE; NFS_SOURCE_CHANGED' } })
    value.refreshStorageOperationHistory = jest.fn(async () => {
      value.storageOperationGate = {
        status: 'READY',
        sequence: kind === 'stale-read' ? 1 : 2,
        resourceId: 'fs',
        instanceId: 'instance',
        security: 'admin|',
        rows: [{ id: kind === 'other-operation' ? 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb' : 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', instanceid: 'instance', revision: 5, state: 'RECOVERY_REQUIRED', diagnostic: 'NFS_SOURCE_CHANGED | rollback: ENDPOINT_SOURCE_RESTORE_FAILED' }]
      }
    })
    postAPI.mockResolvedValue({ enablestorageserviceprotocolresponse: { jobid: 'job' } })
    expect(await value.enableProtocol()).toBe(false)
    expect(value.actionModal.error).toBe('message.storage.service.activation.failure.source message.storage.service.activation.failure.recovery.required')
    expect(value.actionModal.error).not.toContain('failure.source.restore')
    expect(postAPI).toHaveBeenCalledTimes(1)
  })

  it.each([
    ['RECOVERY_REQUIRED', true, false],
    ['ROLLED_BACK', false, true],
    ['BLOCKED', false, false]
  ])('preserves an admitted unsupported writer terminal state without changing its primary cause: %s', (state, recoveryRequired, rolledBack) => {
    const value = protocolActivationFailurePresentation({ errortext: 'Storage Service operation aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa ' + state + ': failurePhase=APPLYING; ENDPOINT_PREPARATION_UNSUPPORTED' })
    expect(value).toMatchObject({ messageKey: 'message.storage.service.activation.failure.unsupported', operationId: 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', recoveryRequired, rolledBack })
    expect(protocolActivationFailurePresentation({ errortext: 'ENDPOINT_PREPARATION_UNSUPPORTED ' + state })).toMatchObject({ operationId: null, recoveryRequired: false, rolledBack: false })
  })

  it('prioritizes SOURCE and address failures over a later generic runtime message and never returns raw text', () => {
    expect(protocolActivationFailurePresentation({ jobresult: { errortext: 'NFS_SOURCE_CHANGED code=NFS_RUNTIME_NOT_READY synthetic-private-value' } }).messageKey).toBe('message.storage.service.activation.failure.source')
    expect(protocolActivationFailurePresentation({ errortext: 'ENDPOINT_PREPARATION_FAILED NFS_RUNTIME_NOT_READY' }).messageKey).toBe('message.storage.service.activation.failure.address')
    expect(protocolActivationFailurePresentation({ errortext: 'NFS_SOURCE_CHANGED_OTHER' }).messageKey).toBeNull()
    expect(protocolActivationFailurePresentation({ errortext: 'NFS_SOURCE_CHANGED | rollback: ENDPOINT_SOURCE_RESTORE_FAILED' })).toMatchObject({ messageKey: 'message.storage.service.activation.failure.source', recoveryMessageKey: 'message.storage.service.activation.failure.source.restore', recoveryRequired: true, rolledBack: false })
    expect(protocolActivationFailurePresentation({ errortext: 'ROLLED_BACK NFS_SOURCE_CHANGED' })).toMatchObject({ rolledBack: true, recoveryRequired: false })
    expect(JSON.stringify(protocolActivationFailurePresentation(new Error('synthetic-private-value')))).not.toContain('synthetic-private-value')
  })
})
