// Licensed to the Apache Software Foundation (ASF) under one or more
// contributor license agreements. See the NOTICE file distributed with this
// work for additional information regarding copyright ownership. The ASF
// licenses this file to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance with the License.
// You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0 .
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
// WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
// License for the specific language governing permissions and limitations.
import { shallowMount, flushPromises } from '@vue/test-utils'
import { reactive } from 'vue'
import EventsTab from '@/components/view/EventsTab'
import AnnotationsTab from '@/components/view/AnnotationsTab'
import { getAPI } from '@/api'

jest.mock('@/api', () => ({ getAPI: jest.fn(), postAPI: jest.fn() }))
jest.mock('@/components/view/ListView', () => ({ template: '<div />' }))

const cases = [['volume', 'Volume'], ['computeoffering', 'ServiceOffering'], ['diskoffering', 'DiskOffering']]
beforeEach(() => getAPI.mockReset())

function mountedTab (name, resourceType, comments) {
  const route = reactive({ path: '/' + name + '/first', fullPath: '/' + name + '/first', query: {}, meta: { resourceType } })
  const getters = reactive({ apis: { listAnnotations: {}, addAnnotation: {}, listEvents: {} }, userInfo: { id: 'user', roletype: 'Admin' }, project: {}, defaultListViewPageSize: 10 })
  const wrapper = shallowMount(comments ? AnnotationsTab : EventsTab, {
    props: { resource: { id: 'first' }, ...(!comments ? { resourceType, loading: false } : {}) },
    global: { provide: { parentFetchData: jest.fn() }, mocks: { $route: route, $store: { getters, state: { user: {} } }, $localStorage: { get: () => null }, $t: key => key, $toLocaleDate: value => value, $notifyError: jest.fn() } }
  })
  return { wrapper, route, getters }
}

describe.each(cases)('%s detail tab navigation', (name, resourceType) => {
  test.each([false, true])('the first response ends loading when tab URL commits during the request (comments=%s)', async comments => {
    let release
    getAPI.mockReturnValueOnce(new Promise(resolve => { release = resolve }))
    const { wrapper, route } = mountedTab(name, resourceType, comments)
    expect(comments ? wrapper.vm.loadingAnnotations : wrapper.vm.tabLoading).toBe(true)
    route.query = { tab: comments ? 'comments' : 'events' }; route.fullPath = route.path + '?tab=' + route.query.tab
    release(comments ? { listannotationsresponse: { count: 1, annotation: [{ id: 'note', annotation: 'owned note' }] } } : { listeventsresponse: { count: 1, event: [{ id: 'event', description: 'owned event' }] } })
    await flushPromises()
    expect(comments ? wrapper.vm.loadingAnnotations : wrapper.vm.tabLoading).toBe(false)
    expect(comments ? wrapper.vm.notes[0].id : wrapper.vm.events[0].id).toBe(comments ? 'note' : 'event')
    expect(getAPI).toHaveBeenCalledTimes(1)
    wrapper.unmount()
  })
})

test.each([false, true])('a tab-only URL change coalesces an in-flight request and settles an empty result (comments=%s)', async comments => {
  let release
  getAPI.mockReturnValueOnce(new Promise(resolve => { release = resolve }))
  const { wrapper, route } = mountedTab('volume', 'Volume', comments)
  route.query = { tab: comments ? 'comments' : 'events' }; route.fullPath = route.path + '?tab=' + route.query.tab
  const pending = comments ? wrapper.vm.getAnnotations() : wrapper.vm.fetchEvents()
  expect(getAPI).toHaveBeenCalledTimes(1)
  release(comments ? { listannotationsresponse: {} } : { listeventsresponse: {} }); await pending; await flushPromises()
  expect(comments ? wrapper.vm.loadingAnnotations : wrapper.vm.tabLoading).toBe(false)
  expect(comments ? wrapper.vm.notes : wrapper.vm.events).toEqual([])
  wrapper.unmount()
})

describe.each([false, true])('shared detail response ownership (comments=%s)', comments => {
  test.each(['resource', 'project', 'user', 'page', 'query'])('rejects the old response when %s changes', async change => {
    const releases = []
    getAPI.mockImplementation(() => new Promise(resolve => releases.push(resolve)))
    const { wrapper, route, getters } = mountedTab('volume', 'Volume', comments)
    if (change === 'resource') await wrapper.setProps({ resource: { id: 'second' } })
    if (change === 'project') getters.project = { id: 'new-project' }
    if (change === 'user') getters.userInfo = { id: 'new-user', roletype: 'Admin' }
    if (change === 'page') wrapper.vm.page = 2
    if (change === 'query') { route.query = { projectid: 'new-project' }; route.fullPath = route.path + '?projectid=new-project' }
    const pending = comments ? wrapper.vm.getAnnotations() : wrapper.vm.fetchEvents()
    expect(getAPI).toHaveBeenCalledTimes(2)
    releases[0](comments ? { listannotationsresponse: { count: 1, annotation: [{ id: 'old-private' }] } } : { listeventsresponse: { count: 1, event: [{ id: 'old-private' }] } })
    await flushPromises()
    expect(comments ? wrapper.vm.notes : wrapper.vm.events).toEqual([])
    const count = change === 'page' ? wrapper.vm.pageSize + 1 : 1
    releases[1](comments ? { listannotationsresponse: { count, annotation: [{ id: 'current' }] } } : { listeventsresponse: { count, event: [{ id: 'current' }] } })
    await pending; await flushPromises()
    expect(comments ? wrapper.vm.notes[0].id : wrapper.vm.events[0].id).toBe('current')
    wrapper.unmount()
  })
})
