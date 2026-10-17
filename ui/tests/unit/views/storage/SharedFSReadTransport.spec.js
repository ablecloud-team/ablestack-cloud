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
import SharedFSTab from '@/views/storage/SharedFSTab'
import { readStorageSections } from '@/utils/storageRead'
import { getAPI } from '@/api'

jest.mock('@/api', () => ({ getAPI: jest.fn(), postAPI: jest.fn() }))

describe('SharedFS read transport ownership', () => {
  const context = { $store: { getters: { apis: {} } } }
  beforeEach(() => getAPI.mockReset())

  it('keeps transient reads in the view with a bounded transport deadline', async () => {
    getAPI.mockResolvedValue({ liststorageservicehealthresponse: { storageserviceruntime: [{ status: 'ok' }] } })
    const values = await SharedFSTab.methods.listApi.call(context, 'listStorageServiceHealth', { instanceid: 'instance' }, 'storageserviceruntime')
    expect(values).toEqual([{ status: 'ok' }])
    expect(getAPI).toHaveBeenCalledWith('listStorageServiceHealth', { instanceid: 'instance' }, { preserveOnFailure: true, timeout: 15000 })
  })

  it('propagates transport failure to section-level recovery', async () => {
    const error = new Error('network unavailable')
    getAPI.mockRejectedValue(error)
    await expect(SharedFSTab.methods.listApi.call(context, 'listStorageServiceHealth', { instanceid: 'instance' }, 'storageserviceruntime')).rejects.toBe(error)
  })
})

describe('SharedFS public section failure diagnostics', () => {
  it('preserves successful values and old failed section names without provider context', async () => {
    const previous = [{ status: 'ok' }]
    const result = await readStorageSections({
      health: () => Promise.reject(Object.assign(new Error('PRIVATE_PROVIDER_CONTEXT'), { code: 'ERR_NETWORK' })),
      protocols: () => Promise.resolve(previous)
    })
    expect(result.values).toEqual({ protocols: previous })
    expect(result.errors).toEqual(['health'])
    expect(result.errorKinds).toEqual({ health: 'TRANSPORT' })
    expect(JSON.stringify(result)).not.toContain('PRIVATE_PROVIDER_CONTEXT')
  })

  it.each([
    [{ code: 'ECONNABORTED', message: 'PRIVATE_TIMEOUT' }, 'TIMEOUT'],
    [{ response: { status: 530, data: { errortext: 'PRIVATE_API_CONTEXT' } } }, 'READ_FAILURE'],
    [{ code: 'ERR_CANCELED', request: {}, message: 'PRIVATE_CANCEL' }, 'READ_FAILURE'],
    [{ code: true, message: 'PRIVATE_UNTYPED_CONTEXT' }, 'READ_FAILURE']
  ])('returns only a fixed public cause for a rejected provider', async (error, kind) => {
    const result = await readStorageSections({ health: () => Promise.reject(error) })
    expect(result.errors).toEqual(['health'])
    expect(result.errorKinds).toEqual({ health: kind })
    expect(Object.keys(result)).toEqual(['values', 'errors', 'errorKinds'])
    expect(JSON.stringify(result)).not.toContain('PRIVATE_')
  })

  it('fails closed for unreadable error metadata while committing other reads', async () => {
    const error = Object.defineProperty({}, 'code', { get () { throw new Error('PRIVATE_ACCESSOR') } })
    const result = await readStorageSections({
      health: () => Promise.reject(error),
      protocols: () => Promise.resolve([{ enabled: true }])
    })
    expect(result.errorKinds).toEqual({ health: 'READ_FAILURE' })
    expect(result.values.protocols).toEqual([{ enabled: true }])
  })

  it('classifies its own deadline and does not commit late successful data', async () => {
    jest.useFakeTimers()
    let resolveLate
    const read = readStorageSections({ health: () => new Promise(resolve => { resolveLate = resolve }) })
    await Promise.resolve()
    await jest.advanceTimersByTime(15001)
    const result = await read
    expect(result.errors).toEqual(['health'])
    expect(result.errorKinds).toEqual({ health: 'TIMEOUT' })
    expect(result.values).toEqual({})
    resolveLate([{ status: 'ok' }])
    await Promise.resolve()
    expect(result.values).toEqual({})
    jest.useRealTimers()
  })

  it.each([
    ['TIMEOUT', 'timeout'], ['TRANSPORT', 'transport'], ['READ_FAILURE', 'failure'],
    [undefined, 'failure'], ['PRIVATE_PROVIDER_CONTEXT', 'failure']
  ])('resolves only a fixed localized cause in production presentation', (kind, label) => {
    const context = { storageService: { readErrorKinds: { health: kind } }, $t: key => key }
    expect(SharedFSTab.methods.storageReadFailureCause.call(context, 'health'))
      .toBe(`message.storage.service.read.cause.${label}`)
  })

  it('renders the production partial alert with a public cause and the existing retry button', async () => {
    getAPI.mockResolvedValue({ listvirtualmachinesresponse: { virtualmachine: [{ id: 'vm' }] } })
    const wrapper = shallowMount(SharedFSTab, {
      props: { resource: { id: 'sharedfs', zoneid: 'zone', virtualmachineid: 'vm', state: 'Ready' } },
      global: {
        provide: { parentFetchData: jest.fn() },
        mocks: {
          $route: { fullPath: '/sharedfs/sharedfs', query: {} },
          $store: { state: { app: { device: 'desktop' } }, getters: { apis: {}, features: {}, userInfo: {} } },
          $t: key => key,
          $getApiParams: () => ({})
        },
        stubs: {
          'a-spin': { template: '<div><slot /></div>' },
          'a-alert': { template: '<div data-test="public-read-alert"><slot name="description" /></div>' },
          'a-tabs': true,
          'a-button': { template: '<button><slot /></button>' }
        }
      }
    })
    await wrapper.setData({ storageService: { ...wrapper.vm.storageService, readErrors: ['health'], readErrorKinds: { health: 'TIMEOUT' }, initialLoading: false } })
    const alert = wrapper.find('[data-test="public-read-alert"]')
    expect(alert.text()).toContain('health: message.storage.service.read.cause.timeout')
    expect(alert.text()).toContain('label.refresh')
    await flushPromises()
    getAPI.mockClear()
    await alert.get('button').trigger('click')
    await flushPromises()
    expect(getAPI).toHaveBeenCalledWith('listVirtualMachines', { id: 'vm', listall: true }, { preserveOnFailure: true, timeout: 15000 })
    expect(wrapper.vm.resource.state).toBe('Ready')
    wrapper.unmount()
  })
})
