// Licensed under the Apache License, Version 2.0. See the NOTICE file.
import { shallowMount, flushPromises } from '@vue/test-utils'
import Action from '@/views/tools/RuntimeCatalogAction.vue'
import Records from '@/views/tools/RuntimeCatalogRecords.vue'
import Summary from '@/views/tools/RuntimeBundleCatalogSummary.vue'
import { getAPI, postAPI } from '@/api'
import wire from '../../../fixtures/storage-1447-catalog-wire.json'
jest.mock('@/api', () => ({ getAPI: jest.fn(), postAPI: jest.fn() }))
const copy = x => JSON.parse(JSON.stringify(x))
describe('runtime catalogue mounted components and real ApiResponseSerializer response', () => {
  let wrapper, poll
  const resource = () => copy(wire.bundles.liststorageserviceruntimebundlesresponse.storageserviceruntimebundle[0])
  const mountAction = async (kind) => {
    wrapper = shallowMount(Action, { props: { resource: resource(), currentAction: { catalogAction: kind } }, global: { mocks: { $t: x => x, $pollJob: poll, $notifyError: jest.fn() } } })
    await flushPromises()
    return wrapper.vm
  }
  beforeEach(() => {
    global.crypto = require('crypto').webcrypto
    poll = jest.fn(); postAPI.mockReset(); getAPI.mockReset()
    postAPI.mockImplementation(async name => ({ [name.toLowerCase() + 'response']: { jobid: 'original-async-job' } }))
    getAPI.mockResolvedValue(copy(wire.validations))
  })
  afterEach(() => wrapper?.unmount())
  it('sends one revisioned suspend request and polls the returned job without staging a VM', async () => {
    const vm = await mountAction('SUSPEND')
    vm.reason = 'operator reason'
    vm.submit(); vm.submit()
    await flushPromises()
    expect(postAPI).toHaveBeenCalledTimes(1)
    const [api, params] = postAPI.mock.calls[0]
    expect(api).toBe('transitionStorageServiceRuntimeBundle')
    expect(params).toMatchObject({ action: 'SUSPEND', bundleid: 'bundle-uuid', expectedrevision: 4, reason: 'operator reason' })
    expect(params.requestid).toMatch(/^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$/)
    expect(poll.mock.calls[0][0].jobId).toBe('original-async-job')
  })
  it('keeps referenced or unobserved bundle deletion disabled without sending a mutation', async () => {
    const vm = await mountAction('DELETE')
    vm.reason = 'delete protection check'
    await vm.submit()
    expect(postAPI).not.toHaveBeenCalled()
    await wrapper.setProps({ resource: { ...resource(), referencecount: 3 } })
    expect(vm.valid).toBe(false)
    await vm.submit()
    expect(postAPI).not.toHaveBeenCalled()
  })
  it('requires explicit security acknowledgement and reason before sending revoke', async () => {
    const vm = await mountAction('REVOKE')
    vm.reason = 'security review'
    await vm.submit(); expect(postAPI).not.toHaveBeenCalled()
    vm.acknowledged = true
    await vm.submit(); expect(postAPI.mock.calls[0][1].action).toBe('REVOKE')
  })
  it('registers only draft metadata; registration does not start verification, publication or VM application', async () => {
    const vm = await mountAction('REGISTER')
    vm.form = { version: 'v2', signingkeyid: 'operator-key', runtimeabiversion: '1', desiredstateschemaversion: '1', serviceimpact: 'NONE', artifacturl: 'http://localhost/b', manifesturl: 'http://localhost/m', signatureurl: 'http://localhost/s', sha256: 'a'.repeat(64), manifestsha256: 'b'.repeat(64) }
    expect(vm.valid).toBe(true)
    await vm.submit()
    expect(postAPI.mock.calls).toEqual([['registerStorageServiceRuntimeBundle', vm.form]])
    expect(wrapper.emitted('refresh-data')).toHaveLength(1)
    expect(wrapper.emitted('close-action')).toHaveLength(1)
  })
  it('uses the named list record and explicit pagination contract for verification history', async () => {
    wrapper = shallowMount(Records, { props: { resource: resource(), tab: 'runtime.validations' }, global: { mocks: { $t: x => x, $notifyError: jest.fn() }, stubs: { RouterLink: true } } })
    await flushPromises()
    expect(getAPI).toHaveBeenLastCalledWith('listStorageServiceRuntimeBundleValidations', { bundleid: 'bundle-uuid', page: 1, pagesize: 10 })
    expect(wrapper.vm.rows[0].id).toBe('validation-uuid')
    expect(wrapper.vm.rows[0].state).toBe('VERIFIED')
    expect(JSON.parse(wrapper.vm.rows[0].detailsjson).workerUid).toBe(65534)
    wrapper.vm.changePage(2, 20)
    await flushPromises()
    expect(getAPI).toHaveBeenLastCalledWith('listStorageServiceRuntimeBundleValidations', { bundleid: 'bundle-uuid', page: 2, pagesize: 20 })
    expect(postAPI).not.toHaveBeenCalled()
  })
  it('template and infrastructure summaries consume the same catalogue without mutation or invented target compatibility', async () => {
    getAPI.mockResolvedValue(copy(wire.bundles))
    wrapper = shallowMount(Summary, { props: { resource: { id: 'unobserved-template' } }, global: { mocks: { $t: x => x, $notifyError: jest.fn() }, stubs: { RouterLink: true } } })
    await flushPromises()
    expect(getAPI).toHaveBeenLastCalledWith('listStorageServiceRuntimeBundles', { purpose: 'catalog', page: 1, pagesize: 10 })
    expect(wrapper.vm.rows[0].state).toBe('SUSPENDED')
    expect(wrapper.html()).toContain('message.runtime.catalog.context.unknown')
    expect(postAPI).not.toHaveBeenCalled()
  })
})
