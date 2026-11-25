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

import { shallowMount, flushPromises } from '@vue/test-utils'
import { createI18n } from 'vue-i18n'
import Action from '@/views/tools/RuntimeCatalogAction.vue'
import Records from '@/views/tools/RuntimeCatalogRecords.vue'
import Summary from '@/views/tools/RuntimeBundleCatalogSummary.vue'
import RuntimeCatalogValue from '@/components/view/RuntimeCatalogValue.vue'
import { getAPI, postAPI } from '@/api'
import wire from '../../../fixtures/storage-1447-catalog-wire.json'
jest.mock('@/api', () => ({ getAPI: jest.fn(), postAPI: jest.fn() }))
const copy = x => JSON.parse(JSON.stringify(x))
const draftMetadata = {
  version: 'v2',
  signingkeyid: 'operator-key',
  runtimeabiversion: '1',
  desiredstateschemaversion: '1',
  serviceimpact: 'NONE',
  artifacturl: 'http://localhost/b',
  manifesturl: 'http://localhost/m',
  signatureurl: 'http://localhost/s',
  sha256: 'a'.repeat(64),
  manifestsha256: 'b'.repeat(64)
}
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
    vm.form = { ...draftMetadata, artifactsize: '' }
    expect(vm.valid).toBe(true)
    await vm.submit()
    expect(postAPI.mock.calls).toEqual([['registerStorageServiceRuntimeBundle', draftMetadata]])
    expect(wrapper.emitted('refresh-data')).toHaveLength(1)
    expect(wrapper.emitted('close-action')).toHaveLength(1)
  })

  it.each([['1', 1], ['67108864', 67108864], ['0', null], ['67108865', null], ['1.5', null]])('uses a whole byte count for bundle size %s', async (input, expected) => {
    const vm = await mountAction('REGISTER')
    vm.form = { ...draftMetadata, artifactsize: input }
    await vm.submit()
    if (expected === null) {
      expect(vm.artifactSizeInvalid).toBe(true)
      expect(postAPI).not.toHaveBeenCalled()
    } else {
      expect(postAPI).toHaveBeenCalledWith('registerStorageServiceRuntimeBundle', { ...draftMetadata, artifactsize: expected })
    }
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

  it('reports unknown usage on failure and only reports zero after a successful lookup', async () => {
    getAPI.mockRejectedValueOnce(new Error('unavailable'))
    wrapper = shallowMount(Records, {
      props: { resource: resource(), tab: 'runtime.usages' },
      global: {
        mocks: { $t: key => key },
        stubs: { AAlert: { template: '<div>{{ message }}<slot name="description" /></div>', props: ['message'] }, ASpace: { template: '<div><slot /></div>' } }
      }
    })
    expect(wrapper.text()).not.toContain('message.runtime.catalog.unused')
    await flushPromises()
    expect(wrapper.text()).toContain('message.runtime.catalog.records.load.failed')
    expect(wrapper.text()).not.toContain('message.runtime.catalog.unused')
    expect(wrapper.vm.hasSuccessfulRead).toBe(false)

    getAPI.mockResolvedValueOnce({ liststorageserviceruntimebundleusagesresponse: {} })
    await wrapper.vm.load()
    expect(wrapper.text()).toContain('message.runtime.catalog.unused')
    expect(wrapper.text()).not.toContain('message.runtime.catalog.records.load.failed')
  })

  it('retries the failed page and page size while retaining the last successful results', async () => {
    wrapper = shallowMount(Records, {
      props: { resource: resource(), tab: 'runtime.validations' },
      global: {
        mocks: { $t: key => key },
        stubs: {
          AAlert: { template: '<div><slot name="description" /></div>' },
          ASpace: { template: '<div><slot /></div>' },
          AButton: { template: '<button><slot /></button>' }
        }
      }
    })
    await flushPromises()
    const previousRows = wrapper.vm.rows

    getAPI.mockRejectedValueOnce(new Error('unavailable'))
    wrapper.vm.changePage(2, 20)
    await flushPromises()
    expect(wrapper.vm.rows).toEqual(previousRows)
    expect(wrapper.vm.page).toBe(1)
    expect(wrapper.vm.pagesize).toBe(10)

    const retry = wrapper.findAll('button').find(button => button.text() === 'label.retry')
    await retry.trigger('click')
    await flushPromises()
    expect(getAPI).toHaveBeenLastCalledWith('listStorageServiceRuntimeBundleValidations', { bundleid: 'bundle-uuid', page: 2, pagesize: 20 })
    expect(wrapper.vm.page).toBe(2)
    expect(wrapper.vm.pagesize).toBe(20)
    expect(wrapper.vm.error).toBeNull()
  })

  it('keeps the last successful usage rows after a failure only within the same bundle', async () => {
    const row = { id: 'current-reference', type: 'CURRENT' }
    getAPI.mockResolvedValueOnce({ liststorageserviceruntimebundleusagesresponse: { count: 1, storageserviceruntimecatalogrecord: [row] } })
    wrapper = shallowMount(Records, {
      props: { resource: resource(), tab: 'runtime.usages' },
      global: {
        mocks: { $t: key => key },
        stubs: { AAlert: { template: '<div>{{ message }}<slot name="description" /></div>', props: ['message'] }, ASpace: { template: '<div><slot /></div>' } }
      }
    })
    await flushPromises()
    expect(wrapper.text()).toContain('message.runtime.catalog.used')

    getAPI.mockRejectedValueOnce(new Error('unavailable'))
    await wrapper.vm.load()
    expect(wrapper.vm.rows).toEqual([row])
    expect(wrapper.text()).toContain('message.runtime.catalog.records.previous.result')
    expect(wrapper.text()).not.toContain('message.runtime.catalog.used')

    getAPI.mockRejectedValueOnce(new Error('unavailable'))
    await wrapper.setProps({ resource: { id: 'different-bundle' } })
    await flushPromises()
    expect(wrapper.vm.rows).toEqual([])
    expect(wrapper.text()).not.toContain('message.runtime.catalog.records.previous.result')
  })

  it('waits for its own tab and ignores responses received after deactivation or bundle change', async () => {
    let settle
    getAPI.mockReturnValueOnce(new Promise(resolve => { settle = resolve }))
    wrapper = shallowMount(Records, {
      props: { resource: resource(), tab: 'runtime.validations', active: false },
      global: { mocks: { $t: key => key } }
    })
    expect(getAPI).not.toHaveBeenCalled()

    await wrapper.setProps({ active: true })
    expect(getAPI).toHaveBeenCalledTimes(1)
    await wrapper.setProps({ active: false, resource: { id: 'second-bundle' } })
    settle(copy(wire.validations))
    await flushPromises()
    expect(wrapper.vm.rows).toEqual([])
    expect(getAPI).toHaveBeenCalledTimes(1)

    await wrapper.setProps({ active: true })
    await flushPromises()
    expect(getAPI).toHaveBeenCalledTimes(2)
    expect(getAPI).toHaveBeenLastCalledWith('listStorageServiceRuntimeBundleValidations', { bundleid: 'second-bundle', page: 1, pagesize: 10 })
    expect(wrapper.vm.rows[0].id).toBe('validation-uuid')
  })
  it('Storage Service template summaries consume the same catalogue without mutation or invented target compatibility', async () => {
    getAPI.mockResolvedValue(copy(wire.bundles))
    wrapper = shallowMount(Summary, { props: { resource: { id: 'storage-template', templatetype: 'SHAREDFS' } }, global: { mocks: { $route: { path: '/template/storage-template', meta: { name: 'template' } }, $store: { getters: { userInfo: { roletype: 'Admin' }, apis: { listStorageServiceRuntimeBundles: {} } } }, $t: x => x, $notifyError: jest.fn() }, stubs: { RouterLink: true } } })
    await flushPromises()
    expect(getAPI).toHaveBeenLastCalledWith('listStorageServiceRuntimeBundles', { purpose: 'catalog', page: 1, pagesize: 10 })
    expect(wrapper.vm.rows[0].state).toBe('SUSPENDED')
    expect(wrapper.html()).toContain('message.runtime.catalog.context.unknown')
    expect(postAPI).not.toHaveBeenCalled()
  })
  it('preserves the authorized global catalogue on infrastructure without a template record', async () => {
    getAPI.mockResolvedValue(copy(wire.bundles))
    wrapper = shallowMount(Summary, { global: { mocks: { $route: { path: '/infrasummary', meta: { name: 'infrasummary' } }, $store: { getters: { userInfo: { roletype: 'Admin' }, apis: { listStorageServiceRuntimeBundles: {} } } }, $t: x => x, $notifyError: jest.fn() }, stubs: { RouterLink: true } } })
    await flushPromises()
    expect(getAPI).toHaveBeenLastCalledWith('listStorageServiceRuntimeBundles', { purpose: 'catalog', page: 1, pagesize: 10 })
    expect(wrapper.vm.rows[0].state).toBe('SUSPENDED')
    expect(postAPI).not.toHaveBeenCalled()
  })

  it('localizes known diagnostic codes and preserves their wire value and unknown codes', async () => {
    const key = 'label.runtime.catalog.errorcode.REGISTERED_METADATA_MISMATCH'
    const translated = '서명된 메타데이터가 등록 정보와 다릅니다.'
    const locale = createI18n({ locale: 'ko_KR', messages: { ko_KR: { [key]: translated } } })
    wrapper = shallowMount(RuntimeCatalogValue, {
      props: { group: 'errorcode', value: 'REGISTERED_METADATA_MISMATCH', showCode: true },
      global: { plugins: [locale] }
    })
    expect(wrapper.text()).toContain(translated)
    expect(wrapper.get('code').text()).toBe('REGISTERED_METADATA_MISMATCH')
    await wrapper.setProps({ value: 'NEW_SERVER_DIAGNOSTIC' })
    expect(wrapper.text()).toBe('NEW_SERVER_DIAGNOSTIC')
    expect(wrapper.find('code').exists()).toBe(false)
  })
})
