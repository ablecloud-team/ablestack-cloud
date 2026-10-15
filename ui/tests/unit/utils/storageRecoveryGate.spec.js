// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements. See the NOTICE file
// distributed with this work for additional information.
import { storageOperationRows } from '@/utils/storageRecoveryGate'
import SharedFSTab from '@/views/storage/SharedFSTab'
import { getAPI, postAPI } from '@/api'

jest.mock('@/api', () => ({ getAPI: jest.fn(), postAPI: jest.fn() }))

const envelope = body => ({ liststorageserviceoperationsresponse: body })
describe('Registered Storage Service operation list wire contract', () => {
  it.each([{}, { count: 0 }, { count: '0' }, { storageserviceoperation: [] }, { count: 0, storageserviceoperation: [] }, { count: '0', storageserviceoperation: [] }])('accepts a successful empty list serialized as %j', body => {
    expect(storageOperationRows(envelope(body))).toEqual([])
  })

  it.each([undefined, null, [], '', 0, false, true, 'READY', { errorcode: 530 }, { errorcode: 0, count: 0 }, { unknown: [] }, { status: 'READY' }, { count: 1 }, { count: '1' }, { count: 1, storageserviceoperation: [] }, { count: 0, storageserviceoperation: null }, { count: 0, storageserviceoperation: {} }, { storageserviceoperation: 'missing list' }, { count: null }, { count: true }])('fails closed for an absent, malformed or failed list body %j', body => {
    expect(() => storageOperationRows(envelope(body))).toThrow('STORAGE_OPERATION_STATE_UNVERIFIED')
  })

  it.each([undefined, null, {}, [], ''])('requires the registered response envelope %j', response => {
    expect(() => storageOperationRows(response)).toThrow('STORAGE_OPERATION_STATE_UNVERIFIED')
  })

  it('does not accept a class or inherited object as the exact empty JSON object', () => {
    class UnknownBody {}
    expect(() => storageOperationRows(envelope(new UnknownBody()))).toThrow('STORAGE_OPERATION_STATE_UNVERIFIED')
    expect(() => storageOperationRows(envelope(Object.create({ count: 0 })))).toThrow('STORAGE_OPERATION_STATE_UNVERIFIED')
  })

  it('retains explicit nonempty rows and checks the supplied count without coercing malformed counts', () => {
    const rows = [{ id: 'operation', instanceid: 'instance', revision: 1, state: 'COMPLETE' }]
    expect(storageOperationRows(envelope({ count: 1, storageserviceoperation: rows }))).toBe(rows)
    expect(storageOperationRows(envelope({ count: '1', storageserviceoperation: rows }))).toBe(rows)
    for (const count of [0, '0', 2, '01', '', false, -1]) {
      expect(() => storageOperationRows(envelope({ count, storageserviceoperation: rows }))).toThrow('STORAGE_OPERATION_STATE_UNVERIFIED')
    }
  })
})

describe('Fresh SharedFS first operation list read', () => {
  const fresh = () => {
    const vm = {
      resource: { id: 'new-fs', virtualmachineid: 'new-vm' },
      hasStorageServiceApi: true,
      storageService: { instance: null, loaded: false },
      storageOperationGate: { status: 'UNKNOWN', rows: [] },
      storageOperationReadSequence: 0,
      storageOperationHistoryRead: null,
      storageRefreshGeneration: 0,
      $store: { getters: { apis: { listStorageServiceInstances: {}, listStorageServiceOperations: {} }, userInfo: { id: 'admin' }, project: {} } },
      $t: key => key,
      listRequestToken: () => ({}),
      isListRequestCurrent: () => true,
      fetchNvmeStorageSnapshot: async () => ({ inventory: [], sessions: [], nvmeSubsystems: [], nvmeNamespaces: [], nvmeHostAcls: [] }),
      loadAccessRules: async () => ({}),
      loadBackingVolumes: async () => []
    }
    for (const name of ['beginStorageOperationRead', 'acceptStorageOperationRead', 'fetchStorageOperationGate', 'fetchStorageServiceData', 'listApi']) vm[name] = (...args) => SharedFSTab.methods[name].apply(vm, args)
    for (const name of ['storageOperationGateEnabled', 'storageOperationReadSupported', 'storageMutationBlocked', 'storageRecoveryGateMessage', 'storageRecoveryGate']) Object.defineProperty(vm, name, { get: () => SharedFSTab.computed[name].call(vm) })
    return vm
  }
  const respond = command => Promise.resolve(command === 'listStorageServiceInstances'
    ? { liststorageserviceinstancesresponse: { count: 1, storageserviceinstance: [{ id: 'instance', virtualmachineid: 'new-vm' }] } }
    : command === 'listStorageServiceOperations' ? envelope({}) : {})
  beforeEach(() => { getAPI.mockReset(); postAPI.mockReset() })

  it('unlocks the first real parent read only after the API succeeds with its standard zero-row object', async () => {
    getAPI.mockImplementation(respond)
    const vm = fresh()
    expect(vm.storageMutationBlocked).toBe(true)
    await vm.fetchStorageServiceData()
    expect(getAPI).toHaveBeenCalledWith('listStorageServiceOperations', { instanceid: 'instance' }, { preserveOnFailure: true, timeout: 15000 })
    expect(vm.storageOperationGate.status).toBe('READY')
    expect(vm.storageOperationGate.rows).toEqual([])
    expect(vm.storageMutationBlocked).toBe(false)
    expect(postAPI).not.toHaveBeenCalled()
  })

  it('keeps the fresh instance closed after an actual transport rejection', async () => {
    getAPI.mockImplementation(command => command === 'listStorageServiceOperations' ? Promise.reject(new Error('transport unavailable')) : respond(command))
    const vm = fresh()
    await vm.fetchStorageServiceData()
    expect(vm.storageOperationGate.status).toBe('ERROR')
    expect(vm.storageMutationBlocked).toBe(true)
    expect(postAPI).not.toHaveBeenCalled()
  })
})
