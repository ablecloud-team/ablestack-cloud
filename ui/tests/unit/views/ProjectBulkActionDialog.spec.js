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
import { ref } from 'vue'
import { getAPI, postAPI } from '@/api'
import ProjectBulkActionDialog from '@/views/project/ProjectBulkActionDialog.vue'
import { currentProjectReason, projectActionReason, projectBulkJobId, projectBulkParams, projectBulkScope, projectBulkTargets } from '@/utils/projectBulk'

jest.mock('@/api', () => ({ getAPI: jest.fn(), postAPI: jest.fn() }))

const root = {
  apis: { activateProject: {}, suspendProject: {}, deleteProject: { params: [{ name: 'cleanup' }] }, listProjects: {}, queryAsyncJobResult: {} },
  project: {},
  userInfo: { roletype: 'Admin', id: 'admin' }
}
const active = { id: 'active', name: 'Active project', domainid: 'domain', state: 'Active' }
const suspended = { id: 'suspended', name: 'Suspended project', domainid: 'domain', state: 'Suspended' }

function openDialog ({ api = 'deleteProject', current = () => true } = {}) {
  return shallowMount(ProjectBulkActionDialog, {
    props: {
      currentAction: {
        api,
        toolbarLabel: 'bulk-action',
        projectBulkTargets: projectBulkTargets(api, [active.id, suspended.id], [active, suspended], root),
        projectBulkCurrent: current
      }
    },
    global: { mocks: { $t: key => key, $store: { getters: root } } }
  })
}

describe('project bulk operations', () => {
  beforeEach(() => {
    getAPI.mockReset()
    postAPI.mockReset()
  })

  it('captures mixed states and only marks the eligible rows as ready', () => {
    const targets = projectBulkTargets('activateProject', ['active', 'suspended'], [active, suspended], root)
    expect(targets.map(row => [row.id, row.reason])).toEqual([
      ['active', 'message.project.bulk.state.unavailable'],
      ['suspended', '']
    ])
    expect(projectBulkTargets('activateProject', ['suspended', 'missing'], [suspended], root)).toEqual([])
    expect(projectBulkTargets('activateProject', ['suspended'], [suspended], { ...root, apis: { activateProject: {} } })).toEqual([])
  })

  it('checks the exact user or account owner and excludes deletion of the current project', () => {
    expect(projectActionReason('deleteProject', active, { ...root, project: active })).toBe('message.project.bulk.current.project')
    const project = { ...active, owner: [{ account: 'member', userid: 'owner-user' }] }
    const owner = { id: 'owner-user', account: 'member', domainid: 'domain' }
    expect(projectActionReason('suspendProject', project, { ...root, userInfo: owner })).toBe('')
    expect(projectActionReason('suspendProject', project, { ...root, userInfo: { ...owner, id: 'peer' } })).toBe('message.project.bulk.denied')
  })

  it('locks page, filters, identity and selection into the confirmation context', () => {
    const snapshot = projectBulkScope({ fullPath: '/project?page=1' }, root, 'token', ['active'])
    expect(projectBulkScope({ fullPath: '/project?page=2' }, root, 'token', ['active'])).not.toBe(snapshot)
    expect(projectBulkScope({ fullPath: '/project?page=1&name=x' }, root, 'token', ['active'])).not.toBe(snapshot)
    expect(projectBulkScope({ fullPath: '/project?page=1' }, root, 'token', ['suspended'])).not.toBe(snapshot)
    expect(projectBulkScope({ fullPath: '/project?page=1' }, { ...root, project: active }, 'token', ['active'])).not.toBe(snapshot)
    expect(currentProjectReason('deleteProject', active, { ...active, name: 'Renamed' }, root)).toBe('message.project.bulk.target.changed')
  })

  it('sends explicit cleanup false or true and omits the field for older APIs', () => {
    const base = { id: 'active', ignoreproject: true }
    expect(projectBulkParams('deleteProject', active, false, true)).toEqual({ ...base, cleanup: false })
    expect(projectBulkParams('deleteProject', active, true, true)).toEqual({ ...base, cleanup: true })
    expect(projectBulkParams('deleteProject', active, false, false)).toEqual(base)
    expect(projectBulkParams('suspendProject', active, false, true)).toEqual(base)
  })

  it('retries only the failed target after independent API job results', async () => {
    getAPI.mockImplementation((command, params) => {
      if (command === 'listProjects') {
        return Promise.resolve({ listprojectsresponse: { project: [params.id === active.id ? active : suspended] } })
      }
      const rejected = params.jobid === 'job-2'
      return Promise.resolve({
        queryasyncjobresultresponse: rejected
          ? { jobstatus: 2, jobresult: { errortext: 'Project has resources' } }
          : { jobstatus: 1, jobresult: { success: true } }
      })
    })
    postAPI.mockImplementation(() => Promise.resolve({ deleteprojectresponse: { jobid: 'job-' + postAPI.mock.calls.length } }))
    const wrapper = openDialog()
    await wrapper.vm.submit()
    expect(wrapper.vm.entries.map(row => row.status)).toEqual(['success', 'failed'])
    await wrapper.vm.submit()
    expect(postAPI.mock.calls.map(call => call[1])).toEqual([
      { id: 'active', ignoreproject: true, cleanup: false },
      { id: 'suspended', ignoreproject: true, cleanup: false },
      { id: 'suspended', ignoreproject: true, cleanup: false }
    ])
    expect(wrapper.vm.entries.map(row => row.status)).toEqual(['success', 'success'])
    wrapper.unmount()
  })

  it('reads the legacy activation job receipt used by the deployed server', () => {
    expect(projectBulkJobId('activateProject', { activaterojectresponse: { jobid: 'legacy-job' } })).toBe('legacy-job')
    expect(projectBulkJobId('activateProject', { activateprojectresponse: { jobid: 'standard-job' } })).toBe('standard-job')
    expect(projectBulkJobId('deleteProject', { activaterojectresponse: { jobid: 'wrong-job' } })).toBe('')
  })

  it('does not repost a target without a confirmed job receipt', async () => {
    getAPI.mockResolvedValue({ listprojectsresponse: { project: [suspended] } })
    postAPI.mockResolvedValue({ activateprojectresponse: {} })
    const wrapper = openDialog({ api: 'activateProject' })
    await wrapper.vm.submit()
    await wrapper.vm.submit()
    expect(wrapper.vm.entries.map(row => row.status)).toEqual(['skipped', 'unknown'])
    expect(postAPI).toHaveBeenCalledTimes(1)
    wrapper.unmount()
  })

  it('checks the current context again after asynchronous target revalidation', async () => {
    const current = ref(true)
    getAPI.mockImplementation(() => {
      current.value = false
      return Promise.resolve({ listprojectsresponse: { project: [suspended] } })
    })
    const wrapper = openDialog({ api: 'activateProject', current: () => current.value })
    await wrapper.vm.submit()
    expect(postAPI).not.toHaveBeenCalled()
    expect(wrapper.vm.entries.map(row => row.status)).toEqual(['skipped', 'skipped'])
    wrapper.unmount()
  })
})
