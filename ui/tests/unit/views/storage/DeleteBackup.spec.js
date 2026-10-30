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
const flush = async () => { for (let i = 0; i < 35; i++) await Promise.resolve() }
const record = (id = 'full') => ({ id, name: id, provider: 'ablestack-nas', status: 'BackedUp' })
const point = id => ({ id, name: id, type: id === 'full' ? 'FULL' : 'INCREMENTAL' })
function mount (props = {}, mocks = {}) {
  return shallowMount(DeleteBackup, {
    props: { resource: record(), ...props },
    global: {
      stubs: {
        ASpin: { template: '<div><slot /></div>' },
        AAlert: { props: ['message'], template: '<div class="delete-alert">{{ message }}</div>' }
      },
      mocks: { $t: key => key, $route: { path: '/backup' }, $notifyError: jest.fn(), $pollJob: jest.fn(options => { options.successMethod({ jobstatus: 1 }); return Promise.resolve() }), ...mocks }
    }
  })
}
beforeEach(() => {
  jest.clearAllMocks()
  getAPI.mockImplementation((api, params) => Promise.resolve(api === 'listBackups'
    ? { listbackupsresponse: { backup: [record(params.id)] } }
    : {
      getbackupdeletionplanresponse: {
        backupdeletionplan: {
          id: params.id,
          provider: 'ablestack-nas',
          forced: params.forced,
          deletionplan: params.id + '-' + params.forced,
          state: params.forced ? 'READY' : 'WAITING_DEPENDENCIES',
          backup: params.forced && params.id === 'full' ? [point('inc'), point('full')] : [point(params.id)],
          dependentbackup: params.id === 'full' ? [point('inc')] : []
        }
      }
    }))
  postAPI.mockResolvedValue({ deletebackupresponse: { jobid: 'delete-job' } })
})
test('ordinary deletion stays simple; force alone displays its warning and affected scope', async () => {
  const w = mount(); await flush()
  expect(w.find('.backup-delete-scope').exists()).toBe(false)
  expect(w.findAll('.delete-alert')).toHaveLength(1)
  expect(w.find('.delete-alert').text()).toBe('message.delete.backup')
  expect(w.html()).not.toContain('message.backup.delete.normal')
  expect(w.html()).not.toContain('message.backup.delete.deferred')
  expect(w.html()).not.toContain('message.backup.delete.force')
  expect(w.vm.affected.map(point => point.id)).toEqual(['full'])
  await w.setData({ forced: true }); await flush()
  expect(w.vm.affected.map(point => point.id)).toEqual(['inc', 'full'])
  expect(w.find('.backup-delete-scope').text()).toContain('(2)')
  expect(w.findAll('.delete-alert').map(alert => alert.text())).toEqual(['message.delete.backup', 'message.backup.delete.force'])
  expect(w.html()).not.toContain('message.backup.delete.deferred')
  await w.setData({ forced: false }); await flush()
  expect(w.find('.backup-delete-scope').exists()).toBe(false)
  expect(w.findAll('.delete-alert')).toHaveLength(1)
  expect(w.find('.delete-alert').text()).toBe('message.delete.backup')
  await w.setData({ forced: true }); await flush()
  await w.vm.submit()
  expect(postAPI).toHaveBeenCalledWith('deleteBackup', { id: 'full', forced: true, deletionplan: 'full-true' }, { preserveOnFailure: true })
  expect(w.emitted('close-action')).toHaveLength(1); w.unmount()
})
test('failed preview prevents deletion', async () => {
  getAPI.mockImplementation((api, params) => api === 'listBackups'
    ? Promise.resolve({ listbackupsresponse: { backup: [record(params.id)] } }) : Promise.reject(new Error('query failed')))
  const w = mount(); await flush(); await w.vm.submit()
  expect(w.vm.error).toBe('query failed'); expect(postAPI).not.toHaveBeenCalled(); w.unmount()
})
test('overlapping bulk forced scopes submit the ancestor only', async () => {
  const w = mount({ currentAction: { invokedAsGroupAction: true }, selectedRowKeys: ['full', 'inc'] }); await flush()
  await w.setData({ forced: true }); await flush()
  expect(w.vm.affected).toHaveLength(2)
  await w.vm.submit(); expect(postAPI).toHaveBeenCalledTimes(1)
  expect(postAPI.mock.calls[0][1].id).toBe('full'); w.unmount()
})
test('independent bulk backups hide force details; failure stops remaining requests and leaves the dialog open', async () => {
  postAPI.mockRejectedValue(new Error('approval pending'))
  const w = mount({ currentAction: { invokedAsGroupAction: true }, selectedRowKeys: ['a', 'b'] }); await flush()
  await w.setData({ forced: true }); await flush()
  expect(w.vm.affected).toHaveLength(2)
  expect(w.find('.backup-delete-scope').exists()).toBe(false)
  expect(w.findAll('.delete-alert').map(alert => alert.text())).toEqual(['message.delete.backup'])
  await w.vm.submit()
  expect(postAPI).toHaveBeenCalledTimes(1); expect(w.vm.error).toBe('approval pending')
  expect(w.emitted('close-action')).toBeUndefined(); w.unmount()
})
test('stale preview response cannot replace the current force scope', async () => {
  const w = mount(); await flush()
  let old
  getAPI.mockImplementationOnce(() => new Promise(resolve => { old = resolve }))
  const pending = w.vm.loadPlans()
  await w.setData({ forced: true }); await flush()
  old({ getbackupdeletionplanresponse: { backupdeletionplan: { id: 'full', deletionplan: 'stale', backup: [point('stale')] } } })
  await pending
  expect(w.vm.plans[0].deletionplan).toBe('full-true'); w.unmount()
})
test('an existing forced deletion initializes its retry preview with forced enabled', async () => {
  const w = mount({ resource: { ...record(), deletionforced: true } }); await flush()
  expect(w.vm.forced).toBe(true); expect(w.vm.affected).toHaveLength(2); w.unmount()
})
test('upstream providers retain provider-specific force and require no ABLESTACK preview', async () => {
  getAPI.mockResolvedValue({ listbackupsresponse: { backup: [{ ...record(), provider: 'kboss' }] } })
  const w = mount(); await flush(); await w.vm.submit()
  expect(w.vm.hasOther).toBe(true)
  expect(getAPI.mock.calls.every(([api]) => api === 'listBackups')).toBe(true)
  expect(postAPI).toHaveBeenCalledWith('deleteBackup', { id: 'full', forced: false }, { preserveOnFailure: true }); w.unmount()
})

test('closes after request acceptance while the common tracker continues the job', async () => {
  let accept; let finish
  postAPI.mockReturnValue(new Promise(resolve => { accept = resolve }))
  const poll = jest.fn(options => new Promise(resolve => { finish = () => { options.successMethod({ jobstatus: 1 }); resolve() } }))
  const close = jest.fn()
  const w = mount({ onCloseAction: close }, { $pollJob: poll }); await flush()
  const pending = w.vm.submit(); await flush()
  expect(w.vm.busy).toBe(true)
  expect(w.emitted('close-action')).toBeUndefined()
  accept({ deletebackupresponse: { jobid: 'delete-job' } }); await flush()
  expect(w.emitted('close-action')).toHaveLength(1)
  expect(w.emitted('refresh-data')).toHaveLength(1)
  expect(w.vm.busy).toBe(false)
  expect(poll).toHaveBeenCalledWith(expect.objectContaining({
    jobId: 'delete-job',
    originalPage: '/backup',
    title: 'label.delete.backup',
    description: 'full',
    name: 'full',
    loadingMessage: 'label.delete.backup - full',
    showLoading: true,
    showSuccessMessage: true,
    resourceId: 'full',
    action: { api: 'deleteBackup', label: 'label.delete.backup' }
  }))
  w.unmount(); finish(); await pending
  expect(close).toHaveBeenCalledTimes(1)
})

test('bulk deletion continues in order after the dialog is unmounted', async () => {
  const jobs = []
  const poll = jest.fn(options => new Promise(resolve => { jobs.push(() => { options.successMethod({ jobstatus: 1 }); resolve() }) }))
  const close = jest.fn()
  const w = mount({ currentAction: { invokedAsGroupAction: true }, selectedRowKeys: ['a', 'b'], onCloseAction: close }, { $pollJob: poll }); await flush()
  const pending = w.vm.submit(); await flush()
  expect(w.emitted('close-action')).toHaveLength(1)
  expect(postAPI).toHaveBeenCalledTimes(1)
  w.unmount()
  jobs[0](); await flush()
  expect(postAPI).toHaveBeenCalledTimes(2)
  expect(postAPI.mock.calls.map(([, params]) => params.id)).toEqual(['a', 'b'])
  jobs[1](); await pending
  expect(close).toHaveBeenCalledTimes(1)
})

test.each(['errorMethod', 'catchMethod'])('a job %s stops the closed bulk dialog without duplicate errors', async callback => {
  let finish
  const poll = jest.fn(options => new Promise(resolve => { finish = () => { options[callback]({ jobstatus: callback === 'errorMethod' ? 2 : null }); resolve() } }))
  const notify = jest.fn()
  const w = mount({ currentAction: { invokedAsGroupAction: true }, selectedRowKeys: ['a', 'b'] }, { $pollJob: poll, $notifyError: notify }); await flush()
  const pending = w.vm.submit(); await flush(); w.unmount()
  finish(); await pending
  expect(postAPI).toHaveBeenCalledTimes(1)
  expect(notify).not.toHaveBeenCalled()
})

test('a later request failure is notified after closure and stops the remaining bulk requests', async () => {
  const error = new Error('second request failed')
  postAPI.mockResolvedValueOnce({ deletebackupresponse: { jobid: 'first-job' } }).mockRejectedValueOnce(error)
  const notify = jest.fn()
  const w = mount({ currentAction: { invokedAsGroupAction: true }, selectedRowKeys: ['a', 'b', 'c'] }, { $notifyError: notify }); await flush()
  await w.vm.submit()
  expect(w.emitted('close-action')).toHaveLength(1)
  expect(postAPI).toHaveBeenCalledTimes(2)
  expect(notify).toHaveBeenCalledWith(error)
  expect(w.vm.error).toBe(''); w.unmount()
})

test('a response without a job ID keeps the confirmation open without claiming success', async () => {
  postAPI.mockResolvedValue({ deletebackupresponse: {} })
  const w = mount(); await flush(); await w.vm.submit()
  expect(w.emitted('close-action')).toBeUndefined()
  expect(w.vm.$pollJob).not.toHaveBeenCalled()
  expect(w.vm.error).toBe('message.backup.delete.unconfirmed')
  expect(w.vm.busy).toBe(false); w.unmount()
})
