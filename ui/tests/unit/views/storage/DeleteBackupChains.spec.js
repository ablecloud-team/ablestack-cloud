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

import { shallowMount } from '@vue/test-utils'
import DeleteBackup from '@/views/storage/DeleteBackup.vue'
import { getAPI, postAPI } from '@/api'
jest.mock('@/api', () => ({ getAPI: jest.fn(), postAPI: jest.fn() }))

const flush = async () => { for (let i = 0; i < 50; i++) await Promise.resolve() }
const point = (id, parents = [], extra = {}) => ({ id, name: id, provider: 'ablestack-nas', status: 'BackedUp', parents, ...extra })
const chain = () => [point('full'), point('inc1', ['full']), point('inc2', ['inc1'])]
const orders = [['full', 'inc1', 'inc2'], ['full', 'inc2', 'inc1'], ['inc1', 'full', 'inc2'], ['inc1', 'inc2', 'full'], ['inc2', 'full', 'inc1'], ['inc2', 'inc1', 'full']]

// Model the API contract: fingerprints include the current dependencies, and deleting
// a leaf may also remove ancestors with a previously accepted ordinary request.
function repository (points) {
  const active = new Map(points.map(point => [point.id, point]))
  const pending = new Set()
  const removed = []
  const dependsOn = (id, ancestor) => (active.get(id)?.parents || []).some(parent => parent === ancestor || dependsOn(parent, ancestor))
  const plan = (id, forced) => {
    const record = active.get(id)
    if (!record) throw new Error('Backup does not exist')
    const dependentbackup = [...active.values()].reverse().filter(point => point.id !== id && dependsOn(point.id, id))
    return {
      id,
      provider: record.provider,
      forced,
      deletionplan: JSON.stringify([id, forced, dependentbackup.map(point => point.id).sort()]),
      state: !forced && dependentbackup.length ? 'WAITING_DEPENDENCIES' : 'READY',
      backup: forced ? [...dependentbackup, record] : [record],
      dependentbackup
    }
  }
  const remove = id => { active.delete(id); pending.delete(id); removed.push(id) }
  getAPI.mockImplementation(async (api, params) => {
    if (api === 'listBackups') return { listbackupsresponse: { backup: active.has(params.id) ? [{ ...active.get(params.id) }] : [] } }
    if (api === 'getBackupDeletionPlan') return { getbackupdeletionplanresponse: { backupdeletionplan: plan(params.id, params.forced) } }
    throw new Error('Unexpected query: ' + api)
  })
  postAPI.mockImplementation(async (api, params) => {
    const current = plan(params.id, params.forced)
    if (params.deletionplan !== current.deletionplan) throw new Error('Backup deletion scope changed')
    if (current.state === 'WAITING_DEPENDENCIES') pending.add(params.id)
    else current.backup.forEach(point => remove(point.id))
    let swept
    do {
      swept = [...pending].find(id => !plan(id, false).dependentbackup.length)
      if (swept) remove(swept)
    } while (swept)
    return { deletebackupresponse: { jobid: 'delete-' + params.id } }
  })
  return { active, pending, removed }
}

async function mount (ids, forced = false, mocks = {}) {
  const wrapper = shallowMount(DeleteBackup, {
    props: { resource: { id: ids[0] }, currentAction: { invokedAsGroupAction: ids.length > 1 }, selectedRowKeys: ids },
    global: {
      stubs: { ASpin: { template: '<div><slot /></div>' } },
      mocks: {
        $t: key => key,
        $route: { path: '/backup' },
        $notifyError: jest.fn(),
        $pollJob: jest.fn(options => { options.successMethod({ jobstatus: 1 }); return Promise.resolve({ jobstatus: 1 }) }),
        ...mocks
      }
    }
  })
  await flush()
  if (forced) { await wrapper.setData({ forced: true }); await flush() }
  expect(wrapper.vm.error).toBe('')
  return wrapper
}

beforeEach(() => jest.clearAllMocks())

test.each(orders.map(order => [order.join(', '), order]))('ordinary mixed-chain selection %s deletes leaves first with current fingerprints', async (_, order) => {
  const repo = repository(chain())
  const w = await mount(order)
  await w.vm.submit()
  expect(postAPI.mock.calls.map(([, params]) => params.id)).toEqual(['inc2', 'inc1', 'full'])
  expect(repo.removed).toEqual(['inc2', 'inc1', 'full'])
  expect(repo.active.size).toBe(0)
  expect(w.vm.$notifyError).not.toHaveBeenCalled(); w.unmount()
})

test.each(orders.map(order => [order.join(', '), order]))('forced mixed-chain selection %s submits the full scope only once', async (_, order) => {
  const repo = repository(chain())
  const w = await mount(order, true)
  await w.vm.submit()
  expect(postAPI).toHaveBeenCalledTimes(1)
  expect(postAPI.mock.calls[0][1].id).toBe('full')
  expect(repo.removed).toEqual(['inc2', 'inc1', 'full'])
  expect(repo.active.size).toBe(0); w.unmount()
})

test.each(['ablestack-nas', 'ablestack-commvault', 'ablestack-netbackup', 'ablestack-veeam'].flatMap(provider => [false, true].flatMap(forced => [false, true].map(bulk => [provider, forced, bulk]))))('%s force=%s bulk=%s deletes selected independent Fulls only', async (provider, forced, bulk) => {
  const repo = repository(['full-a', 'full-b', 'full-c'].map(id => point(id, [], { provider, virtualmachineid: id + '-vm' })))
  const selected = bulk ? ['full-b', 'full-a'] : ['full-a']
  const w = await mount(selected, forced)
  expect(w.vm.showDependentDeletion).toBe(false)
  await w.vm.submit()
  expect(postAPI.mock.calls.map(([, params]) => params.id)).toEqual(selected)
  expect(repo.removed).toEqual(selected)
  expect(repo.active.has('full-c')).toBe(true)
  expect(w.vm.$notifyError).not.toHaveBeenCalled(); w.unmount()
})

test.each(['full', 'inc1'])('ordinary single %s preserves dependents and reports request acceptance', async id => {
  const repo = repository(chain())
  const w = await mount([id]); await w.vm.submit()
  expect(repo.removed).toEqual([])
  expect([...repo.pending]).toEqual([id])
  expect(w.vm.$pollJob).toHaveBeenCalledWith(expect.objectContaining({ successMessage: 'label.backup.delete.requested' }))
  expect(w.emitted('close-action')).toHaveLength(1); w.unmount()
})

test('forced single incremental preserves its prefix, sibling and unrelated Full', async () => {
  const repo = repository([...chain(), point('sibling', ['full']), point('another-full')])
  const w = await mount(['inc1'], true); await w.vm.submit()
  expect(repo.removed).toEqual(['inc2', 'inc1'])
  expect([...repo.active.keys()]).toEqual(['full', 'sibling', 'another-full']); w.unmount()
})

test('single latest incremental reports completed deletion without a deferred success message', async () => {
  const repo = repository(chain())
  const w = await mount(['inc2']); await w.vm.submit()
  expect(repo.removed).toEqual(['inc2'])
  expect(w.vm.$pollJob.mock.calls[0][0].successMessage).toBeUndefined(); w.unmount()
})

test('overlapping forced roots refresh the shrunken scope of their shared descendant', async () => {
  const repo = repository([point('full-a'), point('full-b'), point('shared-inc', ['full-a', 'full-b'])])
  const w = await mount(['full-a', 'full-b'], true); await w.vm.submit()
  expect(repo.removed).toEqual(['shared-inc', 'full-a', 'full-b'])
  expect(postAPI.mock.calls.map(([, params]) => params.id)).toEqual(['full-a', 'full-b'])
  expect(w.vm.$notifyError).not.toHaveBeenCalled(); w.unmount()
})

test('already pending ancestors swept by the leaf are skipped rather than submitted again', async () => {
  const repo = repository(chain()); repo.pending.add('full'); repo.pending.add('inc1')
  const w = await mount(['full', 'inc1', 'inc2']); await w.vm.submit()
  expect(postAPI.mock.calls.map(([, params]) => params.id)).toEqual(['inc2'])
  expect(repo.removed).toEqual(['inc2', 'inc1', 'full'])
  expect(w.vm.$notifyError).not.toHaveBeenCalled(); w.unmount()
})

test.each([false, true])('force=%s accepts a reduced scope without expanding the approved deletion', async forced => {
  const repo = repository(chain())
  const w = await mount(['inc1'], forced)
  repo.active.delete('inc2')
  await w.vm.submit()
  expect(repo.removed).toEqual(['inc1'])
  expect(repo.active.has('full')).toBe(true)
  expect(w.vm.error).toBe(''); w.unmount()
})

test.each([false, true])('force=%s requires a new confirmation when a dependent is added', async forced => {
  const repo = repository([point('full')])
  const w = await mount(['full'], forced)
  repo.active.set('new-inc', point('new-inc', ['full']))
  await w.vm.submit()
  expect(postAPI).not.toHaveBeenCalled()
  expect(w.vm.error).toBe('message.backup.delete.scope.changed')
  expect(w.emitted('close-action')).toBeUndefined(); w.unmount()
})

test('a changed later scope stops the background batch and notifies without deleting new points', async () => {
  const repo = repository([point('full-a'), point('full-b')])
  const poll = jest.fn(options => {
    repo.active.set('new-inc', point('new-inc', ['full-b']))
    options.successMethod({ jobstatus: 1 }); return Promise.resolve({ jobstatus: 1 })
  })
  const notify = jest.fn()
  const w = await mount(['full-a', 'full-b'], true, { $pollJob: poll, $notifyError: notify }); await w.vm.submit()
  expect(repo.removed).toEqual(['full-a'])
  expect(notify).toHaveBeenCalledWith(expect.objectContaining({ message: 'message.backup.delete.scope.changed' }))
  expect(postAPI).toHaveBeenCalledTimes(1); w.unmount()
})

test('a failed revalidation query never treats a still existing backup as deleted', async () => {
  const repo = repository(chain())
  const w = await mount(['inc2'])
  getAPI.mockRejectedValueOnce(new Error('catalog unavailable'))
  await w.vm.submit()
  expect(repo.removed).toEqual([])
  expect(postAPI).not.toHaveBeenCalled()
  expect(w.vm.error).toBe('catalog unavailable'); w.unmount()
})

test('a backend scope rejection is not retried with its fingerprint guard omitted', async () => {
  const repo = repository(chain())
  const w = await mount(['inc1', 'inc2'])
  postAPI.mockRejectedValueOnce(new Error('Backup deletion scope changed'))
  await w.vm.submit()
  expect(postAPI).toHaveBeenCalledTimes(1)
  expect(postAPI.mock.calls[0][1].deletionplan).toBeTruthy()
  expect(repo.removed).toEqual([])
  expect(w.vm.error).toBe('Backup deletion scope changed'); w.unmount()
})

test('a security scope change stops unsent requests after the current job', async () => {
  const repo = repository([point('full-a'), point('full-b')])
  const store = { state: { user: { token: 'token' } }, getters: { userInfo: { id: 'user' }, project: { id: 'first-project' } } }
  const poll = jest.fn(options => {
    store.getters.project.id = 'another-project'
    options.successMethod({ jobstatus: 1 }); return Promise.resolve({ jobstatus: 1 })
  })
  const w = await mount(['full-a', 'full-b'], false, { $store: store, $pollJob: poll }); await w.vm.submit()
  expect(repo.removed).toEqual(['full-a'])
  expect(postAPI).toHaveBeenCalledTimes(1); w.unmount()
})
