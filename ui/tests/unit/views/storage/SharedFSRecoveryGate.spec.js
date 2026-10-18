// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements. See the NOTICE file
// distributed with this work for additional information.
import { shallowMount, flushPromises } from '@vue/test-utils'
import { reactive } from 'vue'
import { Alert } from 'ant-design-vue'
import SharedFSTab from '@/views/storage/SharedFSTab'
import History from '@/views/storage/StorageOperationHistory'
import { getAPI, postAPI } from '@/api'
import { requireStorageMutationAllowed, storageMutationGateMixin, storageOperationRows } from '@/utils/storageRecoveryGate'
import NetworkAccess from '@/views/storage/SmbNetworkAccess'
import Configuration from '@/views/storage/StorageServiceConfiguration'
import RuntimeUpgrade from '@/views/storage/StorageServiceRuntimeUpgrade'
import RootHistory from '@/views/storage/StorageServiceTemplateUpgradeHistory'
import RootUpgrade from '@/views/storage/StorageServiceSystemVmTemplateUpgrade'

jest.mock('@/api', () => ({ getAPI: jest.fn(), postAPI: jest.fn() }))
const deferred = () => { let complete; const promise = new Promise(resolve => { complete = resolve }); return { promise, resolve: complete } }
const row = (state, revision, id = state + revision) => ({ id, instanceid: 'instance-a', state, revision })
const context = () => {
  const vm = {
    resource: { id: 'fs-a' },
    hasStorageServiceApi: true,
    storageService: { instance: { id: 'instance-a', name: 'owned-a' }, loading: false },
    storageOperationGate: { status: 'UNKNOWN', rows: [] },
    storageOperationReadSequence: 0,
    storageOperationHistoryRead: null,
    $store: { getters: { apis: { listStorageServiceOperations: {} }, userInfo: { id: 'user-a' }, project: { id: 'project-a' } } },
    $t: key => key,
    $message: { error: jest.fn() },
    $getApiParams: () => ({ idempotencykey: {} }),
    listApi: jest.fn().mockResolvedValue([]),
    actionModal: { visible: true, type: 'editNfsExport', context: { id: 'export-a' }, loading: false },
    actionLoading: {},
    cleanParams: value => ({ ...value }),
    $pollJob: jest.fn().mockResolvedValue({ jobstatus: 1 }),
    refreshAfterStorageAction: jest.fn(),
    refreshStorageOperationHistory: jest.fn(),
    cancelAdMutation: jest.fn(),
    forms: { nfsExport: { readonly: true } }
  }
  for (const name of ['beginStorageOperationRead', 'acceptStorageOperationRead', 'fetchStorageOperationGate', 'storageOperationHistoryStarted', 'storageOperationHistoryLoaded', 'runStorageAction', 'submitActionModal', 'closeActionModal', 'openActionModal', 'openDeleteModal', 'storageLabel']) {
    vm[name] = (...args) => SharedFSTab.methods[name].apply(vm, args)
  }
  for (const name of ['storageOperationGateEnabled', 'storageOperationReadSupported', 'storageMutationBlocked', 'storageRecoveryGateMessage', 'storageRecoveryGate', 'storageModalCanResumeIntent']) {
    Object.defineProperty(vm, name, { get: () => SharedFSTab.computed[name].call(vm) })
  }
  return vm
}
const ready = (vm, rows = []) => vm.acceptStorageOperationRead(vm.beginStorageOperationRead('instance-a'), rows, false)
const run = vm => vm.runStorageAction('editNfsExport', 'updateStorageNfsExport', { id: 'export-a', readonly: true }, 'update')

const historyContext = parent => {
  const history = { ...History.data(), instanceId: 'instance-a', $store: parent.$store }
  Object.entries(History.methods).forEach(([name, method]) => { history[name] = (...args) => method.apply(history, args) })
  history.$emit = (event, value) => {
    if (event === 'read-started') parent.storageOperationHistoryStarted(value)
    if (event === 'read-complete') parent.storageOperationHistoryLoaded(value)
  }
  parent.$refs = { storageOperationHistory: history }
  parent.refreshStorageOperationHistory = (...args) => SharedFSTab.methods.refreshStorageOperationHistory.apply(parent, args)
  return history
}
const historyEnvelope = rows => ({ liststorageserviceoperationsresponse: { count: rows.length, storageserviceoperation: rows } })

describe('SharedFS current writer recovery gate', () => {
  beforeEach(() => { jest.clearAllMocks(); postAPI.mockResolvedValue({ updatestoragenfsexportresponse: { jobid: 'job-a' } }) })

  it('blocks a newly opened page until its scoped operation state is known', () => {
    const vm = context()
    expect(vm.storageMutationBlocked).toBe(true)
    expect(vm.storageRecoveryGateMessage).toContain('확인')
    ready(vm)
    expect(vm.storageMutationBlocked).toBe(false)
  })

  it('fails closed when the active StorageService has no operations capability or permission', () => {
    const vm = context(); ready(vm)
    delete vm.$store.getters.apis.listStorageServiceOperations
    expect(vm.storageMutationBlocked).toBe(true)
    expect(vm.storageRecoveryGateMessage).toContain('확인하지 못해')
    vm.hasStorageServiceApi = false
    expect(vm.storageMutationBlocked).toBe(false)
  })

  it.each([null, '', true, false, '01', '-1', '1.0', ' 1', 1.5, Number.MAX_SAFE_INTEGER + 1])('rejects malformed operation revision %j instead of coercing a committed revision', revision => {
    const vm = context(); ready(vm, [{ ...row('COMPLETE', 1), revision }])
    expect(vm.storageOperationGate.status).toBe('ERROR')
    expect(vm.storageMutationBlocked).toBe(true)
  })

  it('accepts the canonical integer string exposed by compatible API serializers', () => {
    const vm = context(); ready(vm, [row('COMPLETE', '71'), row('RECOVERY_REQUIRED', '72')])
    expect(vm.storageRecoveryGate.committedRevision).toBe(71)
    expect(vm.storageMutationBlocked).toBe(true)
  })

  it.each([
    [row('RECOVERY_REQUIRED', 72), true],
    [row('RECOVERY_REQUIRED', 71), false],
    [row('RECOVERY_REQUIRED', 70), false],
    [row('RUNNING', 70), true],
    [row('ROLLED_BACK', 72), false],
    [row('BLOCKED', 72), false]
  ])('mirrors the server current writer predicate for %j', (operation, blocked) => {
    const vm = context()
    ready(vm, [operation, row('COMPLETE', 71)])
    expect(vm.storageMutationBlocked).toBe(blocked)
    if (operation.state === 'RECOVERY_REQUIRED' && blocked) expect(vm.storageRecoveryGateMessage).toContain('복구')
  })

  it.each([{}, { liststorageserviceoperationsresponse: { errorcode: 530 } }, { liststorageserviceoperationsresponse: { count: 2, storageserviceoperation: [] } }])('rejects incomplete or failed history envelopes %j', response => {
    expect(() => storageOperationRows(response)).toThrow('STORAGE_OPERATION_STATE_UNVERIFIED')
  })

  it('accepts a verified empty history without disabling a healthy new instance forever', () => {
    expect(storageOperationRows({ liststorageserviceoperationsresponse: { count: 0 } })).toEqual([])
  })

  it('does not let foreign or malformed operation snapshots unlock the current scope', () => {
    const vm = context()
    ready(vm, [row('COMPLETE', 71), row('RECOVERY_REQUIRED', 72)])
    ready(vm, [{ ...row('COMPLETE', 100), instanceid: 'instance-b' }])
    expect(vm.storageMutationBlocked).toBe(true)
    ready(vm, [{ ...row('COMPLETE', 100), revision: 'not-a-revision' }])
    expect(vm.storageMutationBlocked).toBe(true)
  })

  it('fails closed during refresh and read errors without destroying previous history', async () => {
    const vm = context()
    ready(vm, [row('COMPLETE', 71)])
    const wait = deferred(); vm.listApi.mockReturnValue(wait.promise)
    const read = vm.fetchStorageOperationGate('instance-a')
    expect(vm.storageMutationBlocked).toBe(true)
    wait.resolve([row('COMPLETE', 71)])
    await read
    expect(vm.storageMutationBlocked).toBe(false)
    vm.listApi.mockRejectedValue(new Error('PRIVATE_BACKEND_CONTEXT'))
    await expect(vm.fetchStorageOperationGate('instance-a')).rejects.toThrow()
    expect(vm.storageMutationBlocked).toBe(true)
    expect(vm.storageOperationGate.rows).toEqual([row('COMPLETE', 71)])
    expect(JSON.stringify(vm.storageOperationGate)).not.toContain('PRIVATE')
  })

  it('loads authoritative operation state on the first protocol-page read before an instance was previously known', async () => {
    const vm = context()
    vm.storageService = { instance: null, loaded: false }
    vm.resource.virtualmachineid = 'vm-a'
    vm.storageRefreshGeneration = 0
    vm.listRequestToken = () => ({})
    vm.isListRequestCurrent = () => true
    vm.fetchStorageServiceData = (...args) => SharedFSTab.methods.fetchStorageServiceData.apply(vm, args)
    vm.fetchNvmeStorageSnapshot = async () => ({ inventory: [], sessions: [], nvmeSubsystems: [], nvmeNamespaces: [], nvmeHostAcls: [] })
    vm.loadAccessRules = async () => ({})
    vm.loadBackingVolumes = async () => []
    vm.listApi.mockImplementation(async command => command === 'listStorageServiceInstances'
      ? [{ id: 'instance-a', name: 'a', virtualmachineid: 'vm-a' }]
      : command === 'listStorageServiceOperations' ? [row('COMPLETE', 71), row('RECOVERY_REQUIRED', 72)] : [])
    await vm.fetchStorageServiceData()
    expect(vm.listApi).toHaveBeenCalledWith('listStorageServiceOperations', { instanceid: 'instance-a' }, 'storageserviceoperation')
    expect(vm.storageOperationGate.status).toBe('READY')
    expect(vm.storageMutationBlocked).toBe(true)
    expect(vm.storageRecoveryGate.blocker.id).toBe('RECOVERY_REQUIRED72')
  })

  it('does not accept an older successful read after a newer recovery snapshot', () => {
    const vm = context()
    const old = vm.beginStorageOperationRead('instance-a')
    const current = vm.beginStorageOperationRead('instance-a')
    vm.acceptStorageOperationRead(current, [row('COMPLETE', 71), row('RECOVERY_REQUIRED', 72)], false)
    vm.acceptStorageOperationRead(old, [row('COMPLETE', 100)], false)
    expect(vm.storageMutationBlocked).toBe(true)
  })

  it.each(['resource', 'instance', 'user', 'project'])('does not reuse an accepted snapshot across a changed %s boundary', scope => {
    const vm = context(); ready(vm)
    if (scope === 'resource') vm.resource.id = 'fs-b'
    if (scope === 'instance') vm.storageService.instance.id = 'instance-b'
    if (scope === 'user') vm.$store.getters.userInfo.id = 'user-b'
    if (scope === 'project') vm.$store.getters.project.id = 'project-b'
    expect(vm.storageMutationBlocked).toBe(true)
  })

  it('unlocks immediately after an ordered same-scope recovery history read', () => {
    const vm = context(); ready(vm, [row('COMPLETE', 71), row('RECOVERY_REQUIRED', 72)])
    vm.storageOperationHistoryStarted({ instanceId: 'instance-a', generation: 4 })
    vm.storageOperationHistoryLoaded({ instanceId: 'instance-a', generation: 4, rows: [row('COMPLETE', 71), row('ROLLED_BACK', 72)], readError: false })
    expect(vm.storageMutationBlocked).toBe(false)
    vm.storageOperationHistoryLoaded({ instanceId: 'instance-a', generation: 3, rows: [], readError: false })
    expect(vm.storageMutationBlocked).toBe(false)
  })

  it('does not let a history completion cross a newer parent read or security boundary', () => {
    const vm = context()
    vm.storageOperationHistoryStarted({ instanceId: 'instance-a', generation: 1 })
    const newer = vm.beginStorageOperationRead('instance-a')
    vm.acceptStorageOperationRead(newer, [row('COMPLETE', 71), row('RECOVERY_REQUIRED', 72)], false)
    vm.storageOperationHistoryLoaded({ instanceId: 'instance-a', generation: 1, rows: [], readError: false })
    expect(vm.storageMutationBlocked).toBe(true)
    vm.storageOperationHistoryStarted({ instanceId: 'instance-a', generation: 2 })
    vm.$store.getters.project.id = 'changed'
    vm.storageOperationHistoryLoaded({ instanceId: 'instance-a', generation: 2, rows: [], readError: false })
    expect(vm.storageMutationBlocked).toBe(true)
  })

  it('invalidates stale READY immediately and queues one post-mutation read behind an in-flight history query', async () => {
    const vm = context(); const history = historyContext(vm)
    const oldRead = deferred(); const freshRead = deferred()
    getAPI.mockReturnValueOnce(oldRead.promise).mockReturnValueOnce(freshRead.promise)
    const pending = history.refresh()
    // A parallel parent read can finish while the older history query is still running.
    ready(vm, [row('COMPLETE', 71)])
    expect(vm.storageMutationBlocked).toBe(false)
    vm.refreshStorageOperationHistory('instance-a')
    vm.refreshStorageOperationHistory('instance-a')
    expect(vm.storageOperationGate.status).toBe('LOADING')
    expect(vm.storageMutationBlocked).toBe(true)
    expect(getAPI).toHaveBeenCalledTimes(1)
    oldRead.resolve(historyEnvelope([row('COMPLETE', 71)])); await flushPromises()
    expect(getAPI).toHaveBeenCalledTimes(2)
    expect(vm.storageOperationGate.status).toBe('LOADING')
    expect(vm.storageMutationBlocked).toBe(true)
    expect(await run(vm)).toBe(false)
    freshRead.resolve(historyEnvelope([row('COMPLETE', 71), row('RECOVERY_REQUIRED', 72)]))
    await pending
    expect(vm.storageRecoveryGate.blocker.id).toBe('RECOVERY_REQUIRED72')
    expect(vm.storageRecoveryGateMessage).toContain('복구')
    expect(history.refreshQueued).toBe(false)
    expect(getAPI).toHaveBeenCalledTimes(2)
    expect(postAPI).not.toHaveBeenCalled()
  })

  it('keeps mutations blocked when the queued post-failure observation fails', async () => {
    const vm = context(); const history = historyContext(vm); const oldRead = deferred()
    getAPI.mockReturnValueOnce(oldRead.promise).mockRejectedValueOnce(new Error('PRIVATE_BACKEND_CONTEXT'))
    const pending = history.refresh()
    vm.refreshStorageOperationHistory('instance-a')
    oldRead.resolve(historyEnvelope([row('COMPLETE', 71)])); await pending
    expect(vm.storageOperationGate.status).toBe('ERROR')
    expect(vm.storageMutationBlocked).toBe(true)
    expect(JSON.stringify(vm.storageOperationGate)).not.toContain('PRIVATE')
    expect(postAPI).not.toHaveBeenCalled()
  })

  it.each(['instance', 'user', 'project', 'unmount'])('discards the queued read across the %s boundary', async scope => {
    const vm = context(); const history = historyContext(vm); const oldRead = deferred()
    getAPI.mockReturnValueOnce(oldRead.promise)
    const pending = history.refresh()
    vm.refreshStorageOperationHistory('instance-a')
    if (scope === 'instance') { history.instanceId = 'instance-b'; vm.storageService.instance.id = 'instance-b' }
    if (scope === 'user') vm.$store.getters.userInfo.id = 'user-b'
    if (scope === 'project') vm.$store.getters.project.id = 'project-b'
    if (scope === 'unmount') History.beforeUnmount.call(history)
    else history.invalidateHistory()
    oldRead.resolve(historyEnvelope([row('COMPLETE', 71)])); await pending
    expect(history.refreshQueued).toBe(false)
    expect(vm.storageMutationBlocked).toBe(true)
    expect(getAPI).toHaveBeenCalledTimes(1)
    expect(postAPI).not.toHaveBeenCalled()
  })

  it('does not turn ordinary overlapping history polls into extra queries', async () => {
    const vm = context(); const history = historyContext(vm); const oldRead = deferred()
    getAPI.mockReturnValueOnce(oldRead.promise)
    const pending = history.refresh()
    await history.refresh()
    oldRead.resolve(historyEnvelope([row('COMPLETE', 71)])); await pending
    expect(history.refreshQueued).toBe(false)
    expect(getAPI).toHaveBeenCalledTimes(1)
  })

  it('refreshes the parent gate without requiring the operations tab to have mounted', async () => {
    const vm = context(); vm.$refs = {}; ready(vm)
    const freshRead = deferred(); vm.listApi.mockReturnValueOnce(freshRead.promise)
    const pending = SharedFSTab.methods.refreshStorageOperationHistory.call(vm, 'instance-a')
    expect(vm.storageOperationGate.status).toBe('LOADING')
    freshRead.resolve([row('COMPLETE', 71), row('RECOVERY_REQUIRED', 72)]); await pending
    expect(vm.storageMutationBlocked).toBe(true)
    expect(vm.listApi).toHaveBeenCalledTimes(1)
    expect(postAPI).not.toHaveBeenCalled()
  })

  it('rejects new central mutations and modal submissions without an API POST', async () => {
    const vm = context()
    vm.updateNfsExport = jest.fn()
    expect(await run(vm)).toBe(false)
    expect(await vm.submitActionModal()).toBe(false)
    expect(postAPI).not.toHaveBeenCalled()
    expect(vm.updateNfsExport).not.toHaveBeenCalled()
    expect(vm.actionModal.visible).toBe(true)
  })

  it.each([
    ['prepareNfsExportVolume', 'nfsExport'],
    ['prepareSmbShareVolume', 'smbShare'],
    ['prepareIscsiTargetVolume', 'iscsiTarget'],
    ['prepareNvmeNamespaceVolume', 'nvmeNamespace']
  ])('reuses an already created backing UUID only for the same uncertain create form in %s', async (prepare, type) => {
    const vm = context(); ready(vm)
    vm.$route = { path: '/sharedfs/fs-a' }
    vm.actionModal.type = type
    vm.forms[type] = { name: 'owned', volumemode: 'NEW', storageid: 'pool', diskofferingid: 'sparse', newvolumesize: 20 }
    postAPI.mockRejectedValueOnce(new Error('storage POST response lost after volume creation'))
    await vm.runStorageAction(type, 'createStorageNfsExport', { instanceid: 'instance-a', name: 'owned', volumeid: 'created-sparse-volume' }, 'create')
    ready(vm, [row('COMPLETE', 71), row('RUNNING', 72)])
    expect(await SharedFSTab.methods[prepare].call(vm)).toBe('created-sparse-volume')
    vm.forms[type].newvolumesize = 30
    expect(await SharedFSTab.methods[prepare].call(vm)).toBe(false)
    expect(postAPI).toHaveBeenCalledTimes(1)
  })

  it('allows the same retained unknown POST intent through RUNNING but not a new dialog or parameter intent', async () => {
    const vm = context(); vm.$route = { path: '/sharedfs/fs-a' }; ready(vm)
    postAPI.mockRejectedValueOnce(new Error('lost response'))
    expect(await run(vm)).toBe(false)
    const key = postAPI.mock.calls[0][1].idempotencykey
    ready(vm, [row('COMPLETE', 71), row('RUNNING', 72)])
    expect(vm.storageModalCanResumeIntent).toBe(true)
    expect(await vm.runStorageAction('editNfsExport', 'updateStorageNfsExport', { id: 'export-a', readonly: false }, 'different')).toBe(false)
    expect(await run(vm)).toBe(true)
    expect(postAPI.mock.calls[1][1].idempotencykey).toBe(key)
    vm.storageModalEpoch = 1
    expect(vm.storageModalCanResumeIntent).toBe(false)
    expect(await run(vm)).toBe(false)
    expect(postAPI).toHaveBeenCalledTimes(2)
  })

  it('allows tracking an already accepted job while blocking a different new intent', async () => {
    const vm = context(); vm.$route = { path: '/sharedfs/fs-a' }; ready(vm)
    vm.$pollJob.mockResolvedValueOnce({ jobstatus: 0, trackingStatus: 'unknown' })
    expect(await run(vm)).toBe(false)
    ready(vm, [row('COMPLETE', 71), row('RUNNING', 72)])
    expect(vm.storageModalCanResumeIntent).toBe(true)
    expect(await vm.runStorageAction('editNfsExport', 'updateStorageNfsExport', { id: 'export-a', readonly: false }, 'different')).toBe(false)
    expect(await run(vm)).toBe(true)
    expect(postAPI).toHaveBeenCalledTimes(1)
    expect(vm.$pollJob.mock.calls[1][0]).toMatchObject({ jobId: 'job-a', retry: true })
  })
})

describe('operation history scoped read notifications', () => {
  beforeEach(() => jest.clearAllMocks())
  it('emits ordered begin/result snapshots for the parent without adding a writer', async () => {
    const rows = [row('COMPLETE', 71), row('ROLLED_BACK', 72)]
    getAPI.mockResolvedValue({ liststorageserviceoperationsresponse: { storageserviceoperation: rows } })
    const vm = { instanceId: 'instance-a', generation: 0, rows: [], $emit: jest.fn() }
    await History.methods.refresh.call(vm)
    expect(vm.$emit).toHaveBeenNthCalledWith(1, 'read-started', { instanceId: 'instance-a', generation: 1 })
    expect(vm.$emit).toHaveBeenNthCalledWith(2, 'read-complete', { instanceId: 'instance-a', generation: 1, rows, readError: false })
    expect(postAPI).not.toHaveBeenCalled()
  })
})

describe('injected forward mutation boundary', () => {
  const blocked = () => ({ storageMutationGate: { blocked: () => true, message: () => 'recovery needed' } })
  beforeEach(() => jest.clearAllMocks())

  it.each(['rollbackStorageServiceRuntimeUpgrade', 'rollbackStorageServiceSystemVmTemplateUpgrade', 'finalizeStorageServiceSystemVmTemplateUpgrade', 'reconcileStorageServiceOperation', 'resumeStorageServiceVolumePreparation', 'cancelStorageServiceOperation', 'getStorageServiceOperationControl', 'downloadStorageServiceConfigBackup', 'planStorageServiceConfigRestore', 'preflightStorageServiceRuntimeUpgrade'])('keeps explicit recovery/control/read path %s available', command => {
    expect(() => requireStorageMutationAllowed(blocked(), command)).not.toThrow()
  })

  it.each(['createStoragePosixDirectoryPolicy', 'createStorageSmbNetworkAcl', 'applyStorageServiceConfigRestore', 'upgradeStorageServiceRuntime', 'upgradeStorageServiceSystemVmTemplate', 'configureStorageServiceControlPolicy'])('blocks new forward command %s at the shared boundary', command => {
    expect(() => requireStorageMutationAllowed(blocked(), command)).toThrow('recovery needed')
  })

  it.each([
    ['resume', 'upgradeStorageServiceSystemVmTemplate'],
    ['rollback', 'rollbackStorageServiceSystemVmTemplateUpgrade'],
    ['finalize', 'finalizeStorageServiceSystemVmTemplateUpgrade']
  ])('keeps actual ROOT recovery %s callable under a blocked parent gate', async (action, api) => {
    postAPI.mockResolvedValue({ [api.toLowerCase() + 'response']: { jobid: 'recovery-job' } })
    const vm = { ...blocked(), resource: { id: 'fs-a', name: 'owned-a' }, action, actionTarget: { id: 'upgrade-a' }, busy: false, confirmation: 'owned-a', maintenanceWindow: true, $t: key => key, $pollJob: jest.fn() }
    await RootHistory.methods.performAction.call(vm)
    expect(postAPI).toHaveBeenCalledWith(api, expect.objectContaining({ sharedfilesystemid: 'fs-a', upgradeid: 'upgrade-a' }))
  })

  it('preserves standalone callers without the optional parent injection', () => {
    expect(() => requireStorageMutationAllowed({}, 'upgradeStorageServiceRuntime')).not.toThrow()
    expect(storageMutationGateMixin.computed.storageMutationDisabled.call({})).toBe(false)
  })

  it('guards actual SMB-network and config-restore final POST calls', async () => {
    await expect(NetworkAccess.methods.mutate.call(blocked(), 'createStorageSmbNetworkAcl', {})).rejects.toThrow('recovery needed')
    await expect(Configuration.methods.mutation.call({ ...blocked(), instanceId: 'a' }, 'applyStorageServiceConfigRestore', {})).rejects.toThrow('recovery needed')
    expect(postAPI).not.toHaveBeenCalled()
  })

  it('guards runtime and ROOT forward POST calls while the runtime rollback remains available', async () => {
    const vm = { ...blocked(), resource: { id: 'fs-a' }, generation: 1, submitting: false, $notifyError: jest.fn(), $t: key => key }
    RuntimeUpgrade.methods.startAsync.call(vm, 'upgrade', 'upgradeStorageServiceRuntime', {})
    expect(postAPI).not.toHaveBeenCalled()
    expect(vm.$notifyError).toHaveBeenCalled()
    const root = { ...blocked(), resource: { id: 'fs-a', name: 'a' }, scope: 1, canSubmit: true, plan: { id: 'plan', revision: 1 }, $t: key => key }
    await RootUpgrade.methods.upgrade.call(root)
    expect(postAPI).not.toHaveBeenCalled()
    expect(root.error).toBe('recovery needed')
    postAPI.mockResolvedValue({ rollbackstorageserviceruntimeupgraderesponse: {} })
    vm.$pollJob = jest.fn()
    RuntimeUpgrade.methods.startAsync.call(vm, 'rollback', 'rollbackStorageServiceRuntimeUpgrade', {})
    await Promise.resolve()
    expect(postAPI).toHaveBeenCalledTimes(1)
  })
})

describe('modal operation-state requery', () => {
  const wrappers = []
  const Widget = { ...SharedFSTab, mixins: [], watch: {}, created () {}, mounted () {} }
  const envelope = rows => ({ liststorageserviceoperationsresponse: { count: rows.length, storageserviceoperation: rows } })
  const params = { id: 'export-a', readonly: true }
  const mountModal = async () => {
    const store = reactive({
      state: { app: { device: 'desktop' }, user: { token: 'session-a' } },
      getters: { apis: { listStorageServiceInstances: {}, listStorageServiceOperations: {} }, features: {}, userInfo: { id: 'user-a' }, project: { id: 'project-a' } }
    })
    const wrapper = shallowMount(Widget, {
      props: { resource: { id: 'fs-a', name: 'owned-a' } },
      global: {
        components: { AAlert: Alert },
        provide: { parentFetchData: jest.fn() },
        mocks: {
          $store: store,
          $t: key => key,
          $message: { error: jest.fn() },
          $getApiParams: () => ({ idempotencykey: {} }),
          $route: { path: '/sharedfs/fs-a', fullPath: '/sharedfs/fs-a?tab=nfs', query: { tab: 'nfs' } }
        },
        stubs: {
          'a-spin': { template: '<div><slot /></div>' },
          'a-tabs': true,
          'a-modal': { props: ['visible', 'okButtonProps'], template: '<section v-if="visible" class="operation-gate-test-modal"><slot /><button class="test-confirm" :disabled="okButtonProps && okButtonProps.disabled">confirm</button></section>' },
          'a-form': { template: '<div><slot /></div>' },
          'a-alert': false,
          'a-button': { props: ['disabled', 'loading'], template: '<button :disabled="disabled || loading"><slot name="icon" /><slot /></button>' }
        }
      }
    })
    wrappers.push(wrapper)
    await wrapper.setData({
      actionModal: { visible: true, type: 'gateReadFixture', context: { id: 'export-a' }, error: 'previous public read failure', loading: false },
      storageService: { ...wrapper.vm.storageService, instance: { id: 'instance-a', name: 'owned-a' }, loaded: true },
      forms: { ...wrapper.vm.forms, nfsExport: { ...wrapper.vm.forms.nfsExport, readonly: true } }
    })
    wrapper.vm.refreshAfterStorageAction = jest.fn()
    wrapper.vm.refreshStorageOperationHistory = jest.fn()
    wrapper.vm.$pollJob = jest.fn().mockResolvedValue({ jobstatus: 1 })
    wrapper.vm.acceptStorageOperationRead(wrapper.vm.beginStorageOperationRead('instance-a'), [], true)
    await wrapper.vm.$nextTick()
    return wrapper
  }
  const refreshButton = wrapper => {
    expect(wrapper.findAllComponents(Alert).some(alert => alert.element.closest('.operation-gate-test-modal'))).toBe(true)
    const button = wrapper.find('.operation-gate-test-modal').findAll('button').find(item => item.text() === 'label.refresh')
    expect(button).toBeDefined()
    return button
  }
  const mutation = wrapper => wrapper.vm.runStorageAction('gateReadFixture', 'updateStorageNfsExport', params, 'own update')

  beforeEach(() => {
    jest.clearAllMocks()
    getAPI.mockResolvedValue(envelope([]))
    postAPI.mockResolvedValue({ updatestoragenfsexportresponse: { jobid: 'job-a' } })
  })
  afterEach(() => wrappers.splice(0).forEach(wrapper => { if (!wrapper.vm.storageMutationDisposed) wrapper.unmount() }))

  it('renders a GET-only retry inside the blocked modal and unlocks the same retained failed form', async () => {
    const wrapper = await mountModal()
    const modal = wrapper.vm.actionModal; const form = wrapper.vm.forms.nfsExport; const target = modal.context
    expect(wrapper.get('.test-confirm').element.disabled).toBe(true)
    await refreshButton(wrapper).trigger('click')
    await flushPromises()
    expect(getAPI).toHaveBeenCalledTimes(1)
    expect(getAPI).toHaveBeenCalledWith('listStorageServiceOperations', { instanceid: 'instance-a' }, { preserveOnFailure: true, timeout: 15000 })
    expect(postAPI).not.toHaveBeenCalled()
    expect(wrapper.vm.storageMutationBlocked).toBe(false)
    expect(wrapper.get('.test-confirm').element.disabled).toBe(false)
    expect(wrapper.vm.actionModal).toBe(modal)
    expect(wrapper.vm.actionModal.context).toBe(target)
    expect(wrapper.vm.forms.nfsExport).toBe(form)
    expect(form.readonly).toBe(true)
    expect(modal.visible).toBe(true)
    expect(modal.error).toBe('previous public read failure')
  })

  it('preserves an existing uncertain intent and its key across the read-only refresh', async () => {
    const wrapper = await mountModal()
    wrapper.vm.acceptStorageOperationRead(wrapper.vm.beginStorageOperationRead('instance-a'), [], false)
    postAPI.mockRejectedValueOnce(new Error('controlled lost POST response'))
    expect(await mutation(wrapper)).toBe(false)
    const key = postAPI.mock.calls[0][1].idempotencykey
    wrapper.vm.acceptStorageOperationRead(wrapper.vm.beginStorageOperationRead('instance-a'), [], true)
    await wrapper.vm.$nextTick()
    getAPI.mockResolvedValue(envelope([row('COMPLETE', 71), row('RUNNING', 72)]))
    await refreshButton(wrapper).trigger('click'); await flushPromises()
    expect(postAPI).toHaveBeenCalledTimes(1)
    expect(wrapper.vm.actionModal.visible).toBe(true)
    expect(wrapper.vm.forms.nfsExport.readonly).toBe(true)
    expect(wrapper.vm.storageMutationBlocked).toBe(true)
    expect(wrapper.vm.storageModalCanResumeIntent).toBe(true)
    expect(wrapper.get('.test-confirm').element.disabled).toBe(false)
    expect(await mutation(wrapper)).toBe(true)
    expect(postAPI.mock.calls[1][1].idempotencykey).toBe(key)
  })

  it.each(['RUNNING', 'RECOVERY_REQUIRED'])('keeps the form blocked after a fresh authoritative %s snapshot', async state => {
    const wrapper = await mountModal()
    getAPI.mockResolvedValue(envelope([row('COMPLETE', 71), row(state, 72)]))
    await refreshButton(wrapper).trigger('click'); await flushPromises()
    expect(wrapper.vm.storageMutationBlocked).toBe(true)
    expect(wrapper.get('.test-confirm').element.disabled).toBe(true)
    expect(wrapper.vm.actionModal.visible).toBe(true)
    expect(wrapper.vm.forms.nfsExport.readonly).toBe(true)
    expect(postAPI).not.toHaveBeenCalled()
    expect(refreshButton(wrapper).element.disabled).toBe(false)
  })

  it('keeps the gate closed on another read error without leaking the provider context', async () => {
    const wrapper = await mountModal()
    getAPI.mockRejectedValue(new Error('PRIVATE_PROVIDER_CONTEXT'))
    await refreshButton(wrapper).trigger('click'); await flushPromises()
    expect(wrapper.vm.storageOperationGate.status).toBe('ERROR')
    expect(wrapper.get('.test-confirm').element.disabled).toBe(true)
    expect(wrapper.find('.operation-gate-test-modal').text()).not.toContain('PRIVATE_PROVIDER_CONTEXT')
    expect(wrapper.vm.actionModal.visible).toBe(true)
    expect(refreshButton(wrapper).element.disabled).toBe(false)
    expect(postAPI).not.toHaveBeenCalled()
  })

  it('serializes the modal read while LOADING without resetting the draft', async () => {
    const wrapper = await mountModal(); const pending = deferred()
    getAPI.mockReturnValue(pending.promise)
    await refreshButton(wrapper).trigger('click')
    expect(wrapper.vm.storageOperationGate.status).toBe('LOADING')
    expect(refreshButton(wrapper).element.disabled).toBe(true)
    expect(await wrapper.vm.refreshStorageModalOperationGate()).toBe(false)
    expect(getAPI).toHaveBeenCalledTimes(1)
    pending.resolve(envelope([])); await flushPromises()
    expect(wrapper.vm.forms.nfsExport.readonly).toBe(true)
    expect(wrapper.get('.test-confirm').element.disabled).toBe(false)
  })

  it.each(['resource', 'instance', 'user', 'project'])('ignores a successful old modal read after the %s scope changes', async scope => {
    const wrapper = await mountModal(); const pending = deferred()
    getAPI.mockReturnValue(pending.promise)
    await refreshButton(wrapper).trigger('click')
    if (scope === 'resource') await wrapper.setProps({ resource: { id: 'fs-b', name: 'owned-b' } })
    if (scope === 'instance') wrapper.vm.storageService.instance.id = 'instance-b'
    if (scope === 'user') wrapper.vm.$store.getters.userInfo.id = 'user-b'
    if (scope === 'project') wrapper.vm.$store.getters.project.id = 'project-b'
    pending.resolve(envelope([])); await flushPromises()
    expect(wrapper.vm.storageMutationBlocked).toBe(true)
    expect(wrapper.get('.test-confirm').element.disabled).toBe(true)
    expect(postAPI).not.toHaveBeenCalled()
  })
})
