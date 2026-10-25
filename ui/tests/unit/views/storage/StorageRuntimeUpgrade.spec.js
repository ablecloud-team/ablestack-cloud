// Licensed under the Apache License, Version 2.0. See NOTICE.
// http://www.apache.org/licenses/LICENSE-2.0
import { shallowMount, flushPromises } from '@vue/test-utils'
import Runtime from '@/views/storage/StorageServiceRuntimeUpgrade'
import { getAPI, postAPI } from '@/api'
import wire from '../../../fixtures/storage-1446-runtime-wire.json'
jest.mock('@/api', () => ({ getAPI: jest.fn(), postAPI: jest.fn() }))
const clone = value => JSON.parse(JSON.stringify(value))
describe('runtime mounted SFC using ApiResponseSerializer wire', () => {
  let wrapper, rows, poll, notify
  const render = async () => {
    wrapper = shallowMount(Runtime, {
      props: { resource: { id: 'sharedfs', name: 'service', templatename: 'template' } },
      global: { stubs: { RouterLink: true }, mocks: { $t: x => x, $pollJob: poll, $notifyError: notify } }
    })
    await flushPromises()
    return wrapper.vm
  }
  beforeEach(() => {
    global.crypto = require('crypto').webcrypto
    rows = []
    poll = jest.fn()
    notify = jest.fn()
    getAPI.mockReset()
    postAPI.mockReset()
    getAPI.mockImplementation(async api => {
      if (api === 'getStorageServiceRuntimeUpgradeCapabilities') return clone(wire.capability)
      if (api === 'listStorageServiceRuntimeBundles') return { liststorageserviceruntimebundlesresponse: { storageserviceruntimebundle: [{ id: 'bundle', version: 'v2', serviceimpact: 'NONE', state: 'PUBLISHED', validationstatus: 'VERIFIED', eligible: true }] } }
      return { liststorageserviceruntimeupgradesresponse: { storageserviceruntimeupgrade: clone(rows) } }
    })
    postAPI.mockImplementation(async api => ({ [api.toLowerCase() + 'response']: { jobid: 'original-job' } }))
  })
  afterEach(() => wrapper?.unmount())
  it('decodes the named capability and native JSON scalar without guessing list envelopes', async () => {
    const vm = await render()
    expect(vm.capability.updaterversion).toBe(2)
    expect(vm.integrity.verified).toBe(true)
    expect(vm.integrity.files[0].path).toBe('ablestack-storagectl')
    expect(vm.supportDescription).toBe('검증 지원')
  })
  it('consumes only the shared published catalogue and never registers from SharedFS', async () => {
    await render()
    expect(getAPI).toHaveBeenCalledWith('listStorageServiceRuntimeBundles', { purpose: 'apply', sharedfilesystemid: 'sharedfs', page: 1, pagesize: 100 })
    expect(wrapper.html()).not.toContain('서명 번들 정보 등록')
    expect(postAPI).not.toHaveBeenCalled()
  })
  it('does not stage or activate from an update/read', async () => {
    const vm = await render()
    await vm.fetchData()
    expect(postAPI).not.toHaveBeenCalled()
    expect(getAPI).toHaveBeenCalledTimes(6)
  })
  it('sends one stable request using HTTP-compatible getRandomValues and rejects duplicate clicks', async () => {
    const vm = await render()
    vm.runPreflight()
    vm.runPreflight()
    await flushPromises()
    expect(postAPI).toHaveBeenCalledTimes(1)
    const params = postAPI.mock.calls[0][1]
    expect(params.requestid).toMatch(/^runtime-[0-9a-f-]{36}$/)
    expect(params.bundleid).toBe('bundle')
    expect(poll.mock.calls[0][0].jobId).toBe('original-job')
  })
  it('does not prepare the already-current version or lose its rollback target', async () => {
    const vm = await render()
    vm.capability.currentversion = 'v2'
    expect(vm.canPreflight).toBe(false)
    vm.runPreflight()
    expect(postAPI).not.toHaveBeenCalled()
    expect(vm.capability.previousversion).toBe('bootstrap-known')
  })
  it('requires explicit acknowledgement after preflight and activates the existing upgrade only', async () => {
    rows = [{ id: 'prepared', state: 'PREFLIGHT_READY', phase: 'PREFLIGHT_OK', bundleid: 'bundle', preflightjson: '{"phase":"PREFLIGHT_OK","previousVersion":"baseline-known"}' }]
    const vm = await render()
    vm.runUpgrade()
    expect(postAPI).not.toHaveBeenCalled()
    vm.acknowledged = true
    vm.runUpgrade()
    await flushPromises()
    expect(postAPI.mock.calls[0][0]).toBe('upgradeStorageServiceRuntime')
    expect(postAPI.mock.calls[0][1]).toEqual({ upgradeid: 'prepared' })
    expect(vm.preflightDetails['원복 버전']).toBe('baseline-known')
  })
  it('observes the original unknown operation, preserving its identity and configuration fence', async () => {
    rows = [clone(wire.operation.upgradestorageserviceruntimeresponse.storageserviceruntimeupgrade)]
    const vm = await render()
    expect(vm.canObserve).toBe(true)
    expect(vm.canPreflight).toBe(false)
    expect(vm.canRollback).toBe(true)
    vm.openPreparation()
    await flushPromises()
    expect(postAPI.mock.calls[0][0]).toBe('upgradeStorageServiceRuntime')
    expect(postAPI.mock.calls[0][1]).toEqual({ upgradeid: 'operation' })
  })
  it('keeps request identity on lost POST response until the server result is observed', async () => {
    postAPI.mockRejectedValue(new Error('transport lost'))
    const vm = await render()
    vm.runPreflight()
    await flushPromises()
    const requestid = postAPI.mock.calls[0][1].requestid
    vm.runPreflight()
    await flushPromises()
    expect(postAPI.mock.calls[1][1].requestid).toBe(requestid)
  })
  it('rejects rollback unless the latest operation is confirmed with an observed previous version', async () => {
    const vm = await render()
    vm.runRollback()
    expect(postAPI).not.toHaveBeenCalled()
    vm.upgrades = [{ id: 'confirmed', state: 'COMPLETE' }]
    vm.runRollback()
    await flushPromises()
    expect(postAPI.mock.calls[0][1]).toEqual({ upgradeid: 'confirmed' })
  })
})
