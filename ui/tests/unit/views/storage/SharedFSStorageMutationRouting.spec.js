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

import { watch, nextTick } from 'vue'
import { createRouter, createMemoryHistory } from 'vue-router'
import SharedFSTab from '@/views/storage/SharedFSTab'
import AutogenView from '@/views/AutogenView'
import { pollJobPlugin } from '@/utils/plugins'
import { getAPI, postAPI } from '@/api'
import eventBus from '@/config/eventBus'

jest.mock('@/api', () => ({ getAPI: jest.fn(), postAPI: jest.fn() }))
jest.mock('@/locales', () => ({ i18n: { global: { t: key => key } } }))
jest.mock('@/store', () => ({ getters: { countNotify: 0, headerNotices: [], userInfo: { id: 'user-a' }, project: {} }, state: { user: { token: 'session-a' } }, watch: jest.fn(), dispatch: jest.fn(), commit: jest.fn() }))
jest.mock('@/config/eventBus', () => ({ emit: jest.fn() }))
jest.mock('ant-design-vue', () => ({
  ...jest.requireActual('ant-design-vue'),
  message: { destroy: jest.fn(), loading: jest.fn(), success: jest.fn(), error: jest.fn() },
  notification: { error: jest.fn(), warning: jest.fn(), close: jest.fn() }
}))
const flush = async () => { for (let i = 0; i < 30; i++) await Promise.resolve(); await nextTick() }

async function context (initialTab = 'details') {
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/sharedfs/:id', component: { template: '<div />' }, meta: { name: 'sharedfs' } }] })
  await router.push('/sharedfs/fs-a?tab=' + initialTab); await router.isReady()
  window.history.replaceState({ fixture: 'retain-existing-router-state' }, '', '/#/sharedfs/fs-a?tab=' + initialTab)
  const vm = {
    resource: { id: 'fs-a', name: 'owned' },
    storageService: { instance: { id: 'instance-a', name: 'owned' }, domains: [], loaded: true },
    currentTab: 'nfs',
    protocolWideLayout: false,
    actionLoading: {},
    actionModal: { visible: true, type: 'editNfsExport', context: { id: 'export-a' }, loading: false },
    forms: { nfsExport: { readonly: true } },
    $router: router,
    $t: key => key,
    $getApiParams: () => ({ idempotencykey: {} }),
    $message: { error: jest.fn() },
    $emit: jest.fn(),
    cleanParams: parameters => ({ ...parameters }),
    fetchData: jest.fn(),
    initStorageDefaults: jest.fn(),
    cancelAdMutation: jest.fn(),
    fetchRuntime: jest.fn().mockResolvedValue(),
    fetchCollection: jest.fn().mockResolvedValue(),
    fetchAccessRules: jest.fn().mockResolvedValue(),
    fetchBackingVolumes: jest.fn().mockResolvedValue(),
    refreshStorageOperationHistory: jest.fn()
  }
  Object.defineProperty(vm, '$route', { get: () => router.currentRoute.value })
  for (const name of ['runStorageAction', 'submitActionModal', 'closeActionModal', 'refreshAfterStorageAction', 'isStorageProtocolTab', 'updateRouteQuery', 'preserveCurrentProtocolRoute', 'emitWideLayout']) vm[name] = SharedFSTab.methods[name]
  vm.updateNfsExport = () => vm.runStorageAction('editNfsExport', 'updateStorageNfsExport', { id: 'export-a', readonly: true }, 'Update own export')
  const parent = {
    resetSelection: jest.fn(),
    clearAutoRefresh: jest.fn(),
    scheduleAutoRefresh: jest.fn(),
    fetchData: jest.fn(() => {
      // Real Autogen fetchData clears a detail resource on a changed fullPath
      // scope. Apply that prop boundary through the production SharedFS watcher.
      const previous = vm.resource; vm.resource = {}
      SharedFSTab.watch.resource.handler.call(vm, vm.resource, previous)
      nextTick(() => {
        const empty = vm.resource; vm.resource = { id: 'fs-a', name: 'owned' }
        SharedFSTab.watch.resource.handler.call(vm, vm.resource, empty)
      })
    })
  }
  const stop = watch(router.currentRoute, (to, from) => AutogenView.watch.$route.call(parent, to, from))
  eventBus.emit.mockImplementation(name => { if (name === 'async-job-complete') parent.fetchData() })
  const app = { config: { globalProperties: {} } }
  pollJobPlugin.install(app)
  vm.$pollJob = app.config.globalProperties.$pollJob.bind(vm)
  return { vm, parent, router, stop }
}

describe('SharedFS mutation with the actual job tracker and route owner', () => {
  beforeEach(() => { jest.clearAllMocks(); postAPI.mockResolvedValue({ updatestoragenfsexportresponse: { jobid: 'job-a' } }) })
  afterEach(() => { jest.useRealTimers(); window.history.replaceState(null, '', '/') })

  it('retains the failed form when a tab was selected through the existing History API', async () => {
    const { vm, parent, router, stop } = await context()
    try {
      vm.updateRouteQuery('nfs')
      expect(window.location.hash).toContain('tab=nfs')
      expect(router.currentRoute.value.query.tab).toBe('details')
      getAPI.mockResolvedValue({ queryasyncjobresultresponse: { jobid: 'job-a', jobstatus: 2, jobresult: { errortext: 'VERIFYING failed and original configuration restored' } } })
      expect(await vm.submitActionModal()).toBe(false)
      await flush()
      expect(parent.fetchData).not.toHaveBeenCalled()
      expect(vm.actionModal).toMatchObject({ visible: true, type: 'editNfsExport', loading: false, error: 'message.storage.config.failed' })
      expect(vm.forms.nfsExport.readonly).toBe(true)
      expect(window.location.hash).toContain('tab=nfs')
      expect(vm.refreshStorageOperationHistory).toHaveBeenCalledWith('instance-a')
    } finally { stop() }
  })

  it.each([1, 2])('keeps refresh ownership after an unknown known job is resumed to terminal %s', async jobstatus => {
    jest.useFakeTimers()
    const { vm, parent, stop } = await context('nfs')
    try {
      getAPI.mockRejectedValue(new Error('controlled polling outage'))
      const first = vm.submitActionModal()
      await flush()
      for (let i = 0; i < 4; i++) { jest.runOnlyPendingTimers(); await flush() }
      expect(await first).toBe(false)
      expect(vm.actionModal.visible).toBe(true)
      expect(postAPI).toHaveBeenCalledTimes(1)
      getAPI.mockResolvedValue({ queryasyncjobresultresponse: { jobid: 'job-a', jobstatus } })
      expect(await vm.submitActionModal()).toBe(jobstatus === 1)
      await flush()
      expect(postAPI).toHaveBeenCalledTimes(1)
      expect(parent.fetchData).not.toHaveBeenCalled()
      expect(vm.forms.nfsExport.readonly).toBe(true)
      expect(vm.actionModal.visible).toBe(jobstatus !== 1)
      expect(vm.refreshStorageOperationHistory).toHaveBeenCalledWith('instance-a')
    } finally { stop() }
  })

  it('replaces the current protocol URL without adding history entries or losing router state', async () => {
    const { vm, router, stop } = await context()
    try {
      vm.currentTab = 'nvmeof'; vm.protocolWideLayout = true
      const before = window.history.length
      vm.preserveCurrentProtocolRoute(); await flush()
      expect(window.location.hash).toContain('tab=nvmeof')
      expect(window.location.hash).toContain('wide=true')
      expect(window.history.length).toBe(before)
      expect(window.history.state).toEqual({ fixture: 'retain-existing-router-state' })
      expect(router.currentRoute.value.query.tab).toBe('details')
    } finally { stop() }
  })
  it('keeps existing browser back and forward entries after preserving the selected protocol', async () => {
    const { vm, stop } = await context()
    const moved = () => new Promise(resolve => window.addEventListener('popstate', resolve, { once: true }))
    try {
      vm.updateRouteQuery('nfs')
      vm.preserveCurrentProtocolRoute()
      const back = moved(); window.history.back(); await back
      expect(window.location.hash).toContain('tab=details')
      const forward = moved(); window.history.forward(); await forward
      expect(window.location.hash).toContain('tab=nfs')
    } finally { stop() }
  })
})
