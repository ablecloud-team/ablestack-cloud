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
import ProjectRolePermissionTab from '@/views/project/iam/ProjectRolePermissionTab.vue'

jest.mock('@/api', () => ({ getAPI: jest.fn(), postAPI: jest.fn() }))

const rules = [
  { id: 'a', rule: 'listVirtualMachines', permission: 'allow' },
  { id: 'b', rule: 'listVolumes', permission: 'allow' }
]
const permissions = rows => ({ listprojectrolepermissionsresponse: { projectrolepermission: rows } })
const apis = { listProjectRolePermissions: {}, updateProjectRolePermission: {}, createProjectRolePermission: {} }

function mountEditor () {
  return shallowMount(ProjectRolePermissionTab, {
    props: { resource: { id: 'project' }, role: { id: 'role' } },
    global: {
      mocks: { $t: key => key, $store: { getters: { apis } } },
      stubs: ['a-alert', 'a-form', 'a-form-item', 'a-table', 'a-button', 'reload-outlined']
    }
  })
}

describe('project role permission editing', () => {
  beforeEach(() => {
    getAPI.mockReset()
    postAPI.mockReset()
  })

  it('waits for API confirmation and displays the server order after a move', async () => {
    getAPI.mockResolvedValueOnce(permissions(rules)).mockResolvedValueOnce(permissions([rules[1], rules[0]]))
    postAPI.mockResolvedValue({})
    const editor = mountEditor()
    await flushPromises()
    await editor.vm.moveRule(1, -1)
    expect(postAPI).toHaveBeenCalledTimes(1)
    expect(postAPI.mock.calls[0].slice(0, 2)).toEqual([
      'updateProjectRolePermission',
      { projectid: 'project', projectroleid: 'role', ignoreproject: true, ruleorder: ['b', 'a'] }
    ])
    expect(editor.vm.rules.map(rule => rule.id)).toEqual(['b', 'a'])
    expect(editor.vm.announcement).toBe('message.project.rule.moved')
    await editor.vm.moveRule(0, -1)
    expect(postAPI).toHaveBeenCalledTimes(1)
    editor.unmount()
  })

  it('keeps a rejected rule draft and exposes the API failure', async () => {
    getAPI.mockResolvedValue(permissions(rules))
    const failure = Object.assign(new Error('Rejected'), { response: { data: { failure: { errortext: 'Duplicate rule' } } } })
    postAPI.mockRejectedValue(failure)
    const editor = mountEditor()
    await flushPromises()
    editor.vm.draft.rule = 'listVolumes'
    await editor.vm.createRule()
    expect(editor.vm.error).toBe('Duplicate rule')
    expect(editor.vm.draft.rule).toBe('listVolumes')
    expect(editor.vm.rules).toEqual(rules)
    expect(editor.vm.locked).toBe(false)
    editor.unmount()
  })

  it('requires a successful refresh before the next edit if readback fails', async () => {
    getAPI.mockResolvedValueOnce(permissions(rules)).mockRejectedValueOnce(new Error('Read failed'))
      .mockResolvedValueOnce(permissions([rules[1], rules[0]]))
    postAPI.mockResolvedValue({})
    const editor = mountEditor()
    await flushPromises()
    await editor.vm.moveRule(1, -1)
    expect(editor.vm.locked).toBe(true)
    await editor.vm.moveRule(1, -1)
    expect(postAPI).toHaveBeenCalledTimes(1)
    await editor.vm.refresh()
    expect(editor.vm.locked).toBe(false)
    expect(editor.vm.rules.map(rule => rule.id)).toEqual(['b', 'a'])
    editor.unmount()
  })
})
