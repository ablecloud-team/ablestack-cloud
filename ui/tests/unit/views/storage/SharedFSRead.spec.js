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

import { flushPromises } from '@vue/test-utils'
import SharedFSTab from '@/views/storage/SharedFSTab'
import { getAPI } from '@/api'

jest.mock('@/api', () => ({ getAPI: jest.fn(), postAPI: jest.fn() }))

const deferred = () => {
  let fulfill, fail
  const promise = new Promise((resolve, reject) => { fulfill = resolve; fail = reject })
  return { promise, resolve: fulfill, reject: fail }
}
const reply = (api, rows) => ({ [api.toLowerCase() + 'response']: { rows } })
const instance = vmId => ({ id: 'instance-' + vmId, virtualmachineid: vmId })
const fixture = () => {
  const context = {
    resource: { id: 'sharedfs-1', virtualmachineid: 'vm-1', zoneid: 'zone-1', volumeid: 'data-1' },
    $route: { fullPath: '/sharedfs/sharedfs-1' },
    $store: { getters: { apis: { listVolumes: {} }, userInfo: { id: 'user-1' } } },
    $t: key => key,
    hasStorageServiceApi: true
  }
  Object.assign(context, SharedFSTab.data.call(context))
  Object.entries(SharedFSTab.methods).forEach(([name, method]) => { context[name] = method.bind(context) })
  Object.defineProperty(context, 'storageReadScope', { get: () => SharedFSTab.computed.storageReadScope.call(context) })
  const mixin = SharedFSTab.mixins[0]
  Object.assign(context, mixin.data())
  Object.entries(mixin.methods).forEach(([name, method]) => { context[name] = method.bind(context) })
  mixin.created.call(context)
  return context
}

describe('SharedFS read lifecycle', () => {
  let context
  beforeEach(() => {
    jest.useFakeTimers()
    getAPI.mockReset()
    getAPI.mockImplementation((api) => Promise.resolve(reply(api,
      api === 'listStorageServiceInstances' ? [instance('vm-1')] : [])))
    context = fixture()
  })
  afterEach(() => {
    context.cancelStorageRead()
    jest.clearAllTimers()
    jest.useRealTimers()
  })

  it('settles when VM enrichment finishes first, coalesces reads and really refreshes afterwards', async () => {
    const storage = deferred()
    getAPI.mockImplementation(api => {
      if (api === 'listVirtualMachines') return Promise.resolve({ listvirtualmachinesresponse: { virtualmachine: [{ id: 'vm-1' }] } })
      if (api === 'listStorageServiceInstances') return storage.promise
      return Promise.resolve(reply(api, []))
    })
    const vm = context.fetchInstances()
    const read = context.fetchStorageServiceData()
    expect(context.fetchStorageServiceData()).toBe(read)
    await vm
    expect(context.vm.id).toBe('vm-1')
    expect(context.storageService.loading).toBe(true)
    storage.resolve(reply('listStorageServiceInstances', [instance('vm-1')]))
    await read
    expect(context.storageService.initialLoading).toBe(false)
    expect(context.storageService.loaded).toBe(true)
    const before = getAPI.mock.calls.filter(([api]) => api === 'listStorageServiceInstances').length
    await context.fetchStorageServiceData()
    expect(getAPI.mock.calls.filter(([api]) => api === 'listStorageServiceInstances')).toHaveLength(before + 1)
  })

  it('settles when storage finishes before VM and tracks VM loading until completion', async () => {
    const vm = deferred()
    getAPI.mockImplementation(api => api === 'listVirtualMachines' ? vm.promise : Promise.resolve(reply(api,
      api === 'listStorageServiceInstances' ? [instance('vm-1')] : [])))
    const pendingVm = context.fetchInstances()
    expect(context.instanceLoading).toBe(true)
    await context.fetchStorageServiceData()
    expect(context.storageService.initialLoading).toBe(false)
    vm.resolve({ listvirtualmachinesresponse: { virtualmachine: [{ id: 'vm-1' }] } })
    await pendingVm
    expect(context.instanceLoading).toBe(false)
    expect(context.storageService.loading).toBe(false)
  })

  it('releases a failed core request and preserves previously loaded data on refresh failure', async () => {
    await context.fetchStorageServiceData()
    context.storageService.nfsExports = [{ id: 'keep' }]
    getAPI.mockRejectedValue({ response: { status: 500, data: { secret: 'must-not-display' } } })
    await context.fetchStorageServiceData()
    expect(context.storageService.loading).toBe(false)
    expect(context.storageService.initialLoading).toBe(false)
    expect(context.storageService.nfsExports).toEqual([{ id: 'keep' }])
    expect(context.storageReadErrors).toEqual({ instance: 'failed' })
    expect(JSON.stringify(context.storageReadErrors)).not.toContain('must-not-display')
  })

  it('rejects old resource responses without letting the old owner clear a new request', async () => {
    const first = deferred(); const second = deferred()
    let count = 0
    getAPI.mockImplementation(api => api === 'listStorageServiceInstances'
      ? (++count === 1 ? first.promise : second.promise)
      : Promise.resolve(reply(api, [])))
    const old = context.fetchStorageServiceData()
    const oldSignal = getAPI.mock.calls[0][2].signal
    context.resource = { id: 'sharedfs-2', virtualmachineid: 'vm-2', zoneid: 'zone-1' }
    const current = context.fetchStorageServiceData()
    expect(oldSignal.aborted).toBe(true)
    first.resolve(reply('listStorageServiceInstances', [instance('vm-1')]))
    await old
    expect(context.storageService.loading).toBe(true)
    expect(context.storageService.instance).toBeNull()
    second.resolve(reply('listStorageServiceInstances', [instance('vm-2')]))
    await current
    expect(context.storageService.instance.id).toBe('instance-vm-2')
    expect(context.storageService.loading).toBe(false)
  })

  it('keeps NFS data and ACL available while unrelated NVMe reads hang; deadline settles and blocks late data', async () => {
    const nvme = deferred()
    getAPI.mockImplementation(api => {
      if (api === 'listStorageServiceInventory') return nvme.promise
      if (api === 'listStorageServiceInstances') return Promise.resolve(reply(api, [instance('vm-1')]))
      if (api === 'listStorageNfsExports') return Promise.resolve(reply(api, [{ id: 'export-1' }]))
      if (api === 'listStorageNfsAcls') return Promise.resolve(reply(api, [{ id: 'acl-1', exportid: 'export-1' }]))
      return Promise.resolve(reply(api, []))
    })
    const read = context.fetchStorageServiceData()
    await flushPromises()
    expect(context.storageService.nfsExports[0].id).toBe('export-1')
    expect(context.storageService.nfsAcls[0].id).toBe('acl-1')
    expect(context.storageReadPending).toEqual(['nvme'])
    jest.advanceTimersByTime(30000)
    expect(context.storageService.initialLoading).toBe(false)
    expect(context.storageReadErrors.nvme).toBe('timeout')
    getAPI.mockImplementation(api => Promise.resolve(reply(api,
      api === 'listStorageServiceInstances' ? [instance('vm-1')] : [])))
    await context.fetchStorageServiceData()
    expect(context.storageService.loaded).toBe(true)
    expect(context.storageReadErrors).toEqual({})
    nvme.resolve(reply('listStorageServiceInventory', [{ id: 'late-inventory' }]))
    await read
    expect(context.storageService.inventory).toEqual([])
  })

  it('preserves independent successful sections on a protocol read failure', async () => {
    context.storageService.loaded = true
    // Establish the current scope before simulating a subsequent failed update.
    await context.fetchStorageServiceData()
    context.storageService.smbShares = [{ id: 'last-good-share' }]
    getAPI.mockImplementation(api => {
      if (api === 'listStorageSmbShares') return Promise.reject(Object.assign(new Error('controlled read failure'), { response: { status: 503 } }))
      if (api === 'listStorageServiceInstances') return Promise.resolve(reply(api, [instance('vm-1')]))
      if (api === 'listStorageNfsExports') return Promise.resolve(reply(api, [{ id: 'fresh-nfs' }]))
      return Promise.resolve(reply(api, []))
    })
    await context.fetchStorageServiceData()
    expect(context.storageService.nfsExports[0].id).toBe('fresh-nfs')
    expect(context.storageService.smbShares[0].id).toBe('last-good-share')
    expect(context.storageReadErrors.smbShares).toBe('failed')
    expect(context.storageService.loading).toBe(false)
  })

  it('does not invalidate a common read on tab/VM display changes, but does invalidate project changes', () => {
    const pending = deferred()
    getAPI.mockReturnValue(pending.promise)
    context.fetchStorageServiceData()
    const token = context.listRequestToken('fetchStorageServiceData')
    context.currentTab = 'nfs'
    context.vm = { id: 'vm-1' }
    context.$route.fullPath += '?tab=nfs'
    expect(context.isListRequestCurrent('fetchStorageServiceData', token)).toBe(true)
    context.$store.getters.project = { id: 'other-project' }
    expect(context.isListRequestCurrent('fetchStorageServiceData', token)).toBe(false)
    pending.resolve(reply('listStorageServiceInstances', []))
  })

  it('aborts on unmount and does not commit late responses', async () => {
    const pending = deferred()
    getAPI.mockReturnValue(pending.promise)
    const read = context.fetchStorageServiceData()
    const signal = getAPI.mock.calls[0][2].signal
    SharedFSTab.mixins[0].beforeUnmount.call(context)
    SharedFSTab.beforeUnmount.call(context)
    expect(signal.aborted).toBe(true)
    expect(context.storageService.loading).toBe(false)
    pending.resolve(reply('listStorageServiceInstances', [instance('vm-1')]))
    await read
    expect(context.storageService.instance).toBeNull()
  })
  it('keeps the default all-protocol ACL reader behavior and iSCSI deduplication', async () => {
    const rows = {
      listStorageNfsAcls: [{ id: 'nfs-acl', exportid: 'export-1' }],
      listStorageSmbAcls: [{ id: 'smb-acl' }],
      listStorageIscsiAcls: [{ id: 'iscsi-acl' }, { id: 'iscsi-acl' }],
      listStorageNvmeOfHostAcls: [{ id: 'nvme-acl' }]
    }
    getAPI.mockImplementation(api => Promise.resolve(reply(api, rows[api] || [])))
    const rules = await context.loadAccessRules('instance-vm-1',
      [{ id: 'export-1' }], [{ id: 'smb-1' }], [{ id: 'iscsi-1' }], [{ id: 'nvme-1' }])
    expect(rules.nfsAcls).toEqual(rows.listStorageNfsAcls)
    expect(rules.smbAcls).toEqual(rows.listStorageSmbAcls)
    expect(rules.iscsiAcls).toEqual([{ id: 'iscsi-acl' }])
    expect(rules.nvmeHostAcls).toEqual(rows.listStorageNvmeOfHostAcls)
  })
})
