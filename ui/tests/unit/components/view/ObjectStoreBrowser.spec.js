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

import { mount, flushPromises } from '@vue/test-utils'
import { h, reactive } from 'vue'
import { EventEmitter } from 'events'
import * as Minio from 'minio'
import Browser from '@/components/view/ObjectStoreBrowser.vue'

jest.mock('minio', () => ({ Client: jest.fn() }))
jest.mock('@/api', () => ({ getAPI: jest.fn() }))

const resource = (id = 'A') => ({ id, name: `bucket-${id}`, url: `http://store:3333/bucket-${id}`, accesskey: `access-${id}`, usersecretkey: `secret-${id}`, quota: 1, size: 0 })
const Harness = {
  ...Browser,
  render () {
    return h('div', [h('span', `${this.loading}/${this.fetching}`), h('ul', this.records.map(row => h('li', row.name || row.prefix)))])
  }
}

class Stream extends EventEmitter {
  constructor () {
    super()
    this.destroy = jest.fn(() => this.emit('close'))
  }
}

describe('bucket browser request lifecycle', () => {
  let wrapper, streams, route, store, router
  const open = async (query = { tab: 'browser' }) => {
    streams = []
    Minio.Client.mockClear()
    Minio.Client.mockImplementation(() => ({ extensions: { listObjectsV2WithMetadata: jest.fn(() => { const stream = new Stream(); streams.push(stream); return stream }) } }))
    route = reactive({ path: '/buckets/A', query, fullPath: '/buckets/A?' + new URLSearchParams(query) })
    store = reactive({ getters: { userInfo: { id: 'user' }, project: { id: 'project' } }, state: { user: { token: 'session' } } })
    router = { replace: jest.fn(({ query }) => Promise.resolve().then(() => { route.query = query; route.fullPath = route.path + '?' + new URLSearchParams(query) })) }
    wrapper = mount(Harness, { props: { resource: resource(), resourceType: 'Bucket' }, global: { mocks: { $route: route, $router: router, $store: store, $t: key => key, $notifyError: jest.fn(), $notification: { error: jest.fn() } } } })
    await flushPromises()
  }
  const complete = async (stream, rows) => { rows.forEach(row => stream.emit('data', row)); stream.emit('end'); await flushPromises() }
  afterEach(() => { if (wrapper) wrapper.unmount(); wrapper = null; jest.useRealTimers() })

  it('renders the first listing after its own browserPath URL normalization', async () => {
    await open()
    expect(route.query.browserPath).toBe('')
    await complete(streams[0], [{ name: 'file.txt', size: 32 }, { prefix: 'folder/' }])
    expect(wrapper.vm.records.map(row => row.name || row.prefix)).toEqual(['file.txt', 'folder/'])
    expect(wrapper.vm.loading).toBe(false)
    expect(wrapper.vm.fetching).toBe(false)
  })

  it('renders a folder response without waiting for periodic refresh after URL changes', async () => {
    await open({ tab: 'browser', browserPath: '' })
    await complete(streams[0], [{ prefix: 'folder/' }])
    wrapper.vm.openDir('folder/')
    await flushPromises()
    await complete(streams[1], [{ name: 'folder/nested.txt', size: 16 }])
    expect(route.query.browserPath).toBe('folder/')
    expect(wrapper.vm.records.map(row => row.name)).toEqual(['folder/nested.txt'])
    expect(wrapper.vm.loading).toBe(false)
  })
  it.each([403, 404])('releases initial loading on S3 %s and retries successfully', async statusCode => {
    await open()
    streams[0].emit('error', Object.assign(new Error(`S3 ${statusCode}`), { statusCode }))
    await flushPromises()
    expect(wrapper.vm.loading).toBe(false)
    expect(wrapper.vm.fetching).toBe(false)
    expect(wrapper.vm.objectListError).toBe(`S3 ${statusCode}`)
    wrapper.vm.fetchData(); await flushPromises()
    await complete(streams[1], [{ name: 'retry.txt' }])
    expect(wrapper.vm.records[0].name).toBe('retry.txt')
    expect(wrapper.vm.objectListError).toBe('')
  })

  it('retains the last good rows on refresh failure and accepts a successful empty refresh', async () => {
    await open(); await complete(streams[0], [{ name: 'keep.txt' }])
    wrapper.vm.listObjects()
    expect(wrapper.vm.loading).toBe(false)
    streams[1].emit('data', { name: 'partial.txt' }); streams[1].emit('error', new Error('offline'))
    await flushPromises()
    expect(wrapper.vm.records.map(row => row.name)).toEqual(['keep.txt'])
    expect(wrapper.vm.fetching).toBe(false)
    wrapper.vm.listObjects(); await complete(streams[2], [])
    expect(wrapper.vm.records).toEqual([])
    expect(wrapper.vm.total).toBe(0)
  })

  it('cancels a superseded folder listing and ignores its late response', async () => {
    await open()
    const old = streams[0]
    wrapper.vm.openDir('folder/'); await flushPromises()
    expect(old.destroy).toHaveBeenCalledTimes(1)
    old.emit('data', { name: 'late.txt' }); old.emit('end')
    await flushPromises()
    expect(wrapper.vm.loading).toBe(true)
    await complete(streams[1], [{ name: 'folder/current.txt' }])
    expect(wrapper.vm.records.map(row => row.name)).toEqual(['folder/current.txt'])
  })

  it('treats browser history query changes as folder navigation', async () => {
    await open({ tab: 'browser', browserPath: 'folder/' })
    await complete(streams[0], [{ name: 'folder/a.txt' }])
    route.query = { tab: 'browser', browserPath: '' }; route.fullPath = '/buckets/A?tab=browser&browserPath='
    await flushPromises()
    await complete(streams[1], [{ name: 'root.txt' }])
    expect(wrapper.vm.browserPath).toBe('')
    expect(wrapper.vm.records.map(row => row.name)).toEqual(['root.txt'])
  })

  it('ignores unsubmitted search text, selected object detail, tab URL and client pagination', async () => {
    await open()
    wrapper.vm.searchInput = 'draft'
    wrapper.vm.page = 2; wrapper.vm.record = { id: 'detail' }
    route.query = { ...route.query, tab: 'details' }; route.fullPath += '&tab=details'
    await complete(streams[0], [{ name: 'root.txt' }])
    expect(wrapper.vm.records[0].name).toBe('root.txt')
    expect(wrapper.vm.loading).toBe(false)
    wrapper.vm.searchObjects(); await complete(streams[1], [{ name: 'draft.txt' }])
    expect(wrapper.vm.searchPrefix).toBe('draft')
    expect(wrapper.vm.page).toBe(1)
  })

  it.each(['user', 'project', 'session', 'route'])('clears old data/client on %s scope change until fresh resource props arrive', async identity => {
    await open(); await complete(streams[0], [{ name: 'private.txt' }])
    wrapper.vm.listObjects(); const old = streams[1]
    if (identity === 'user') store.getters.userInfo.id = 'other'
    if (identity === 'project') store.getters.project.id = 'other'
    if (identity === 'session') store.state.user.token = 'other'
    if (identity === 'route') route.path = '/buckets/B'
    await flushPromises()
    expect(wrapper.vm.records).toEqual([])
    expect(wrapper.vm.client).toBe(null)
    expect(wrapper.vm.loading).toBe(false)
    expect(old.destroy).toHaveBeenCalledTimes(1)
    old.emit('data', { name: 'private-late.txt' }); old.emit('end'); await flushPromises()
    expect(wrapper.vm.records).toEqual([])
    await wrapper.setProps({ resource: resource('B') }); await flushPromises()
    await complete(streams[2], [{ name: 'authorized.txt' }])
    expect(wrapper.vm.records[0].name).toBe('authorized.txt')
  })

  it('reinitializes the SDK for a bucket/endpoint/credential change', async () => {
    await open(); await complete(streams[0], [{ name: 'private.txt' }])
    wrapper.vm.listObjects(); const old = streams[1]
    await wrapper.setProps({ resource: resource('B') }); await flushPromises()
    expect(old.destroy).toHaveBeenCalledTimes(1)
    expect(wrapper.vm.records).toEqual([])
    expect(Minio.Client).toHaveBeenCalledTimes(2)
    await complete(streams[2], [{ name: 'bucket-b.txt' }])
    expect(wrapper.vm.records[0].name).toBe('bucket-b.txt')
  })

  it('deduplicates same-query requests and releases cancellation for a retry', async () => {
    await open()
    wrapper.vm.listObjects(); wrapper.vm.listObjects()
    expect(streams).toHaveLength(1)
    wrapper.vm.cancelObjectListing(); await flushPromises()
    expect(wrapper.vm.loading).toBe(false)
    expect(wrapper.vm.objectListError).toBe('message.objectstore.list.cancelled')
    wrapper.vm.fetchData(); await flushPromises()
    await complete(streams[1], [{ name: 'retry.txt' }])
    expect(wrapper.vm.records[0].name).toBe('retry.txt')
  })

  it('terminates a stalled listing after 30 seconds and allows retry', async () => {
    await open()
    // The stream deadline is a real timer in open(), so replace it under fake timers.
    wrapper.vm.cancelObjectListing(); await flushPromises(); jest.useFakeTimers()
    wrapper.vm.listObjects(); jest.advanceTimersByTime(30000); await flushPromises()
    expect(wrapper.vm.loading).toBe(false)
    expect(wrapper.vm.fetching).toBe(false)
    expect(wrapper.vm.objectListError).toBe('message.objectstore.list.timeout')
    wrapper.vm.listObjects(); await complete(streams[2], [{ name: 'recovered.txt' }])
    expect(wrapper.vm.records[0].name).toBe('recovered.txt')
  })

  it('destroys a pending listing on unmount and never commits late records', async () => {
    await open(); const vm = wrapper.vm; const old = streams[0]
    wrapper.unmount(); wrapper = null
    old.emit('data', { name: 'late.txt' }); old.emit('end'); await flushPromises()
    expect(old.destroy).toHaveBeenCalledTimes(1)
    expect(vm.records).toEqual([])
  })

  it('reports a synchronous SDK constructor error without stranding the spinner', async () => {
    await open(); wrapper.vm.cancelObjectListing(); await flushPromises()
    Minio.Client.mockImplementationOnce(() => { throw new Error('invalid endpoint') })
    await wrapper.setProps({ resource: resource('B') }); await flushPromises()
    expect(wrapper.vm.loading).toBe(false)
    expect(wrapper.vm.fetching).toBe(false)
    expect(wrapper.vm.objectListError).toBe('invalid endpoint')
  })

  it('isolates an old router rejection from the new folder listing', async () => {
    await open(); await complete(streams[0], [{ name: 'root.txt' }])
    let rejectOld
    router.replace.mockImplementationOnce(() => new Promise((resolve, reject) => { rejectOld = reject }))
    wrapper.vm.openDir('old/')
    wrapper.vm.openDir('new/'); await flushPromises()
    rejectOld(new Error('superseded navigation')); await flushPromises()
    expect(wrapper.vm.loading).toBe(true)
    expect(wrapper.vm.objectListError).toBe('')
    await complete(streams[1], [{ name: 'new/current.txt' }])
    expect(wrapper.vm.records[0].name).toBe('new/current.txt')
  })
})
