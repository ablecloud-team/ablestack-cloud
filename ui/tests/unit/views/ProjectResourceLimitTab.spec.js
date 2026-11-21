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

import { flushPromises, shallowMount } from '@vue/test-utils'
import { getAPI, postAPI } from '@/api'
import ProjectResourceLimitTab from '@/views/project/ProjectResourceLimitTab.vue'
import { orderProjectLimitChanges, projectLimitDirty, projectLimitEditAllowed, projectLimitParams, projectLimitRows, projectLimitValue } from '@/utils/projectLimits'

jest.mock('@/api', () => ({ getAPI: jest.fn(), postAPI: jest.fn() }))

const active = { id: 'p1', state: 'Active' }
const apis = { listProjects: {}, listResourceLimits: {}, updateResourceLimit: {} }
const getters = { apis, userInfo: { id: 'admin', roletype: 'Admin' } }
const record = (resourcetype, resourcetypename, max, tag) => ({ projectid: active.id, resourcetype, resourcetypename, max, tag })

function editor () {
  const reload = jest.fn()
  const wrapper = shallowMount(ProjectResourceLimitTab, {
    props: { resource: active },
    global: {
      provide: { parentFetchData: reload },
      mocks: { $t: key => key, $store: { getters, state: { user: { token: 'token' } } } }
    }
  })
  return { wrapper, reload }
}

describe('project resource limit editing', () => {
  beforeEach(() => {
    getAPI.mockReset()
    postAPI.mockReset()
  })

  it('keeps zero distinct from unlimited, null input and the hidden project-count quota', () => {
    const rows = projectLimitRows([record('0', 'user_vm', 0), record('5', 'project', 10), record('8', 'cpu', '-1')])
    expect(rows.map(row => [row.type, row.saved, row.draft])).toEqual([[0, 0, 0], [8, -1, -1]])
    expect(rows.some(projectLimitDirty)).toBe(false)
    expect(projectLimitValue(null)).toBeNull()
    expect(projectLimitValue('')).toBeNull()
    expect(projectLimitValue(-2)).toBeNull()
  })

  it('respects active-state and API permission gates', () => {
    expect(projectLimitEditAllowed(active, getters)).toBe(true)
    expect(projectLimitEditAllowed({ ...active, state: 'Suspended' }, getters)).toBe(false)
    expect(projectLimitEditAllowed(active, { ...getters, apis: { listProjects: {}, listResourceLimits: {} } })).toBe(false)
    expect(projectLimitEditAllowed(active, { ...getters, userInfo: { roletype: 'User' } })).toBe(false)
  })

  it('places a tagged reduction before its lower general limit and omits tag on general rows', () => {
    const [general, tagged] = projectLimitRows([record('8', 'cpu', 12), record('8', 'cpu', 7, 'test-tag')])
    const observed = new Map([[general.key, 12], [tagged.key, 7]])
    const changes = [{ row: general, value: 4 }, { row: tagged, value: 0 }]
    expect(orderProjectLimitChanges(changes, observed).map(change => change.row.key)).toEqual([tagged.key, general.key])
    expect(projectLimitParams('p1', general, 4)).toEqual({ projectid: 'p1', resourcetype: 8, max: 4, ignoreproject: true })
    expect(projectLimitParams('p1', tagged, 0)).toEqual({ projectid: 'p1', resourcetype: 8, max: 0, ignoreproject: true, tag: 'test-tag' })
  })

  it('sends only changed rows and leaves only the rejected row for retry', async () => {
    let values = [record('0', 'user_vm', 0), record('8', 'cpu', 12), record('9', 'memory', 16)]
    getAPI.mockImplementation(command => Promise.resolve(command === 'listProjects'
      ? { listprojectsresponse: { project: [active] } }
      : { listresourcelimitsresponse: { resourcelimit: values } }))
    postAPI.mockImplementation((command, params) => {
      if (params.resourcetype === 8 && postAPI.mock.calls.length === 2) {
        return Promise.reject(Object.assign(new Error('Rejected'), { response: { status: 431, data: { updateresourcelimitresponse: { errortext: 'Rejected' } } } }))
      }
      const result = { ...values.find(row => Number(row.resourcetype) === params.resourcetype), max: params.max }
      values = values.map(row => row.resourcetype === result.resourcetype ? result : row)
      return Promise.resolve({ updateresourcelimitresponse: { resourcelimit: result } })
    })
    const { wrapper, reload } = editor()
    await flushPromises()
    await wrapper.vm.save()
    expect(postAPI).not.toHaveBeenCalled()
    wrapper.vm.edit(wrapper.vm.rows[0], -1)
    wrapper.vm.edit(wrapper.vm.rows[1], 17)
    await wrapper.vm.save()
    expect(wrapper.vm.rows.map(r => [r.saved, r.draft, r.status])).toEqual([[-1, -1, 'saved'], [12, 17, 'failed'], [16, 16, '']])
    await wrapper.vm.save()
    expect(postAPI.mock.calls.map(call => [call[1].resourcetype, call[1].max])).toEqual([[0, -1], [8, 17], [8, 17]])
    expect(reload).toHaveBeenCalledTimes(2)
    wrapper.unmount()
  })

  it('keeps tagged and general writes distinct when saving them together', async () => {
    let values = [record('8', 'cpu', 12), record('8', 'cpu', 0, 'test-tag'), record('9', 'memory', 16)]
    getAPI.mockImplementation(command => Promise.resolve(command === 'listProjects'
      ? { listprojectsresponse: { project: [active] } }
      : { listresourcelimitsresponse: { resourcelimit: values } }))
    postAPI.mockImplementation((command, params) => {
      const correct = row => Number(row.resourcetype) === params.resourcetype && row.tag === params.tag
      const result = { ...values.find(correct), max: params.max }
      values = values.map(row => correct(row) ? result : row)
      return Promise.resolve({ updateresourcelimitresponse: { resourcelimit: result } })
    })
    const { wrapper } = editor()
    await flushPromises()
    wrapper.vm.edit(wrapper.vm.rows[0], 16)
    wrapper.vm.edit(wrapper.vm.rows[1], 4)
    wrapper.vm.edit(wrapper.vm.rows[2], 8)
    await wrapper.vm.save()
    expect(postAPI.mock.calls.map(call => [call[1].resourcetype, call[1].tag, call[1].max])).toEqual([[8, undefined, 16], [8, 'test-tag', 4], [9, undefined, 8]])
    expect(wrapper.vm.rows.map(row => [row.saved, row.status])).toEqual([[16, 'saved'], [4, 'saved'], [8, 'saved']])
    wrapper.unmount()
  })
  it('requires successful readback after a write if the first confirmation fails', async () => {
    let value = 0
    let limitReads = 0
    getAPI.mockImplementation(command => {
      if (command === 'listProjects') return Promise.resolve({ listprojectsresponse: { project: [active] } })
      if (++limitReads === 3) return Promise.reject(new Error('Readback unavailable'))
      return Promise.resolve({ listresourcelimitsresponse: { resourcelimit: [record('0', 'user_vm', value)] } })
    })
    postAPI.mockImplementation((command, params) => {
      value = params.max
      return Promise.resolve({ updateresourcelimitresponse: { resourcelimit: record('0', 'user_vm', value) } })
    })
    const { wrapper } = editor()
    await flushPromises()
    wrapper.vm.edit(wrapper.vm.rows[0], -1)
    await wrapper.vm.save()
    expect(wrapper.vm.rows[0].status).toBe('unknown')
    expect(wrapper.vm.needsReadback).toBe(true)
    await wrapper.vm.save()
    expect(postAPI).toHaveBeenCalledTimes(1)
    await wrapper.vm.refresh()
    expect(wrapper.vm.rows[0]).toMatchObject({ saved: -1, draft: -1, status: 'saved' })
    expect(wrapper.vm.needsReadback).toBe(false)
    wrapper.unmount()
  })
})
