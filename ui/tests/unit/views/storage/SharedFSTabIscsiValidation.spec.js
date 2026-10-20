// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements. See the NOTICE file
// distributed with this work for additional information.
import SharedFSTab from '@/views/storage/SharedFSTab'
import CreateSharedFS from '@/views/storage/CreateSharedFS'
import { postAPI } from '@/api'

jest.mock('@/api', () => ({ getAPI: jest.fn(), postAPI: jest.fn() }))

const name = 'iqn.2026-10.com.example:custom-target'
function context () {
  const value = {
    resource: { id: 'fs', name: 'owned', zoneid: 'zone' },
    storageService: { instance: { id: 'instance', name: 'owned' }, domains: [], iscsiTargets: [], iscsiAcls: [], protocols: [{ protocol: 'ISCSI', port: 3261, listenip: '10.10.13.111', enabled: true }] },
    serviceEndpoints: ['10.10.13.111', '10.10.13.112'],
    forms: { iscsiTarget: { id: '', targetname: name, lun: '0', volumemode: 'NEW', listenerports: [3261], newvolumename: 'new-sparse-data', diskofferingid: 'sparse', storageid: 'pool', newvolumesize: 2 } },
    storageMutationBlocked: false,
    actionModal: { visible: true, type: 'iscsiTarget', context: {}, loading: false },
    actionLoading: {},
    currentTab: 'iscsi',
    protocolWideLayout: false,
    $route: { path: '/sharedfs/fs' },
    $store: { getters: { userInfo: { id: 'admin' } } },
    $t: key => key,
    $message: { error: jest.fn() },
    cleanParams: values => values,
    waitVolumeAttachable: jest.fn().mockResolvedValue(),
    runStorageAction: jest.fn().mockResolvedValue(true),
    refreshAfterStorageAction: jest.fn().mockResolvedValue(),
    refreshStorageOperationHistory: jest.fn(),
    cancelAdMutation: jest.fn()
  }
  for (const key of ['validateIscsiTargetForm', 'prepareIscsiTargetVolume', 'createIscsiTarget', 'updateIscsiTarget', 'validateIscsiAclIdentity', 'createIscsiAcl', 'updateIscsiAcl', 'validateIscsiChapForm', 'protocolListenerEntries', 'normalizeListenerPorts', 'formatBlockProtocolListenerGroupEndpoints', 'formatIscsiListenerGroupEndpoints', 'iscsiListenerGroupScope', 'isWildcardListenIp', 'boolValue']) value[key] = SharedFSTab.methods[key].bind(value)
  Object.defineProperty(value, 'iscsiListenerGroupOptions', { get: () => SharedFSTab.computed.iscsiListenerGroupOptions.call(value) })
  return value
}

describe('iSCSI target validates before allocating DATA', () => {
  beforeEach(() => { postAPI.mockReset(); postAPI.mockResolvedValue({ createvolumeresponse: { volume: { id: 'new-volume' } } }) })

  it.each([
    { targetname: '' }, { targetname: 'bad target' }, { targetname: name + '\n' },
    { lun: '' }, { lun: '65536' }, { lun: '-1' }, { lun: '1.5' },
    { listenerports: [] }, { listenerports: [3260] }, { listenerports: [3261, 'invalid'] }
  ])('never allocates a new volume for invalid target input %p', async changes => {
    const value = context()
    Object.assign(value.forms.iscsiTarget, changes)
    expect(await value.createIscsiTarget()).toBe(false)
    expect(postAPI).not.toHaveBeenCalled()
    expect(value.waitVolumeAttachable).not.toHaveBeenCalled()
    expect(value.runStorageAction).not.toHaveBeenCalled()
    expect(value.$message.error).toHaveBeenCalled()
  })

  it.each([name, 'iqn.2026-05.local.storage:default-target'])('allocates DATA and submits the exact validated IQN once: %s', async iqn => {
    const value = context()
    value.forms.iscsiTarget.targetname = iqn
    expect(await value.createIscsiTarget()).toBe(true)
    expect(postAPI).toHaveBeenCalledTimes(1)
    expect(postAPI.mock.calls[0][0]).toBe('createVolume')
    expect(value.waitVolumeAttachable).toHaveBeenCalledWith('new-volume')
    expect(value.runStorageAction).toHaveBeenCalledWith('iscsiTarget', 'createStorageIscsiTarget', expect.objectContaining({ targetname: iqn, lun: '0', listenerports: '3261', volumeid: 'new-volume', endpointmode: 'LISTENER_GROUP' }), 'label.storage.service.create.iscsi.target')
  })

  it('keeps unsupported ACL mapped LUNs from allocating DATA while preserving large targets without ACLs', async () => {
    const value = context()
    value.forms.iscsiTarget.lun = '65535'
    value.storageService.iscsiTargets = [{ id: 'existing', targetname: name, lunornamespace: '0', state: 'Ready' }]
    value.storageService.iscsiAcls = [{ id: 'acl', resourceid: 'existing', state: 'Ready' }]
    expect(await value.createIscsiTarget()).toBe(false)
    expect(value.iscsiTargetInputErrors.lun).toBe('ISCSI_MAPPED_LUN_UNSUPPORTED')
    expect(postAPI).not.toHaveBeenCalled()
    value.storageService.iscsiAcls = []
    expect(await value.createIscsiTarget()).toBe(true)
    expect(value.runStorageAction.mock.calls[0][2].lun).toBe('65535')
  })

  it('uses the submitted validated snapshot and stops if the resource context changes during allocation', async () => {
    const value = context()
    let complete
    postAPI.mockImplementation(() => new Promise(resolve => { complete = resolve }))
    const pending = value.createIscsiTarget()
    value.resource = { id: 'different-fs' }
    value.storageService.instance = { id: 'different-instance' }
    complete({ createvolumeresponse: { volume: { id: 'new-volume' } } })
    expect(await pending).toBe(false)
    expect(value.runStorageAction).not.toHaveBeenCalled()
    expect(postAPI).toHaveBeenCalledTimes(1)
  })

  it('updates invalid existing target input without any mutation', async () => {
    const value = context()
    value.forms.iscsiTarget.targetname = 'invalid-name'
    expect(await value.updateIscsiTarget()).toBe(false)
    expect(postAPI).not.toHaveBeenCalled()
    expect(value.runStorageAction).not.toHaveBeenCalled()
  })
})

describe('iSCSI listener scope and mapped ACL checks', () => {
  it('offers only registered active ports and does not invent wildcard 3260', () => {
    const value = context()
    expect(value.iscsiListenerGroupOptions.map(row => row.value)).toEqual([3261])
    expect(value.iscsiListenerGroupScope([3261])).toContain('scope.specific: 10.10.13.111')
    expect(value.formatIscsiListenerGroupEndpoints([3261])).toBe('10.10.13.111:3261')
    expect(value.formatIscsiListenerGroupEndpoints([3260])).toBe('-')
    value.storageService.protocols.push({ protocol: 'ISCSI', port: 3262, listenip: null, enabled: true })
    expect(value.iscsiListenerGroupScope([3262])).toBe('label.storage.service.iscsi.listener.scope.all')
    expect(value.formatIscsiListenerGroupEndpoints([3262])).toBe('10.10.13.111:3262, 10.10.13.112:3262')
    value.storageService.protocols = [{ protocol: 'ISCSI', port: 3260, enabled: false }]
    expect(value.iscsiListenerGroupOptions).toEqual([])
    expect(SharedFSTab.methods.defaultIscsiListenerPort.call(value)).toBeNull()
  })

  it('checks every active LUN in the selected IQN before submitting its first ACL', async () => {
    const value = context()
    value.storageService.iscsiTargets = [{ id: 'first', targetname: name, lunornamespace: '0', state: 'Ready', targetluns: '0,256' }, { id: 'high', targetname: name, lunornamespace: '256', state: 'Ready' }]
    value.forms.iscsiAcl = { targetid: 'first', initiatoriqn: 'iqn.2026-10.com.client:one', chapenabled: false }
    expect(await value.createIscsiAcl()).toBe(false)
    expect(value.$message.error).toHaveBeenCalledWith('message.storage.service.iscsi.input.ISCSI_MAPPED_LUN_UNSUPPORTED')
    expect(value.runStorageAction).not.toHaveBeenCalled()
    value.storageService.iscsiTargets[1].state = 'Disabled'
    await value.createIscsiAcl()
    expect(value.runStorageAction).toHaveBeenCalledTimes(1)
  })
})

describe('iSCSI terminal failure display preserves operation recovery', () => {
  beforeEach(() => postAPI.mockReset())

  it('shows only the fixed ownership category and required recovery while preserving the target and first error', async () => {
    const value = context()
    value.actionModal.type = 'iscsiAcl'
    value.storageService.iscsiTargets = [{ id: 'target', targetname: name }]
    value.$pollJob = jest.fn().mockResolvedValue({ jobstatus: 2, jobresult: { errortext: 'Storage operation BLOCKED: failurePhase=APPLYING; ISCSI guard=AUTH; category=OWNERSHIP_MISMATCH synthetic-secret-value | rollback: failed' } })
    value.refreshAfterStorageAction.mockRejectedValue(new Error('controlled read failure'))
    postAPI.mockResolvedValue({ createstorageiscsiaclresponse: { jobid: 'job' } })
    const result = await SharedFSTab.methods.runStorageAction.call(value, 'iscsiAcl', 'createStorageIscsiAcl', { targetid: 'target', initiatoriqn: 'iqn.2026-10.com.client:one', chapsecret: 'synthetic-secret-value' }, 'ACL')
    expect(result).toBe(false)
    expect(value.actionModal.visible).toBe(true)
    expect(value.actionModal.error).toBe('message.storage.service.iscsi.failure.ownership message.storage.service.iscsi.failure.recovery.required')
    expect(JSON.stringify(value.$message.error.mock.calls)).not.toContain('synthetic-secret-value')
    expect(value.storageService.iscsiTargets).toEqual([{ id: 'target', targetname: name }])
    expect(value.refreshStorageOperationHistory).toHaveBeenCalledWith('instance')
    expect(postAPI).toHaveBeenCalledTimes(1)
    expect(postAPI.mock.calls[0][1].chapsecret).toBe('')
  })
})

describe('iSCSI creation wizard input is checked before VM creation', () => {
  const wizard = () => {
    const value = { $t: key => key }
    for (const [key, method] of Object.entries(CreateSharedFS.methods)) value[key] = method.bind(value)
    value.initForm()
    value.form.services = ['ISCSI']
    return value
  }
  it('permits custom IQN and a large LUN without an ACL, with the mapped limit applied only when initiator access is requested', async () => {
    const value = wizard()
    await expect(value.rules.iscsitargetname[0].validator({}, name)).resolves.toBeUndefined()
    await expect(value.rules.iscsilun[0].validator({}, '65535')).resolves.toBeUndefined()
    value.form.iscsiinitiator = 'iqn.2026-10.com.client:one'
    await expect(value.rules.iscsilun[0].validator({}, '65535')).rejects.toBe('message.storage.service.iscsi.input.ISCSI_MAPPED_LUN_UNSUPPORTED')
    await expect(value.rules.iscsitargetname[0].validator({}, 'invalid')).rejects.toBe('message.storage.service.iscsi.input.ISCSI_TARGET_IQN_INVALID')
  })
})
