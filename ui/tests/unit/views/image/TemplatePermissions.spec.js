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
import { Select, Input, Button, Alert } from 'ant-design-vue'
import Permissions from '@/views/image/UpdateTemplateIsoPermissions'
import { getAPI, postAPI } from '@/api'
jest.mock('@/api', () => ({ getAPI: jest.fn(), postAPI: jest.fn() }))
const context = extra => {
  const c = { ...Permissions.data(), resource: { domainid: 'domain', id: 'image', ispublic: true, isextractable: true, isfeatured: false }, $t: k => k, $emit: jest.fn(), parentFetchData: jest.fn(), $notification: { success: jest.fn() }, $notifyError: jest.fn(), ...extra }
  Object.entries(Permissions.methods).forEach(([k, fn]) => { c[k] = fn.bind(c) })
  return c
}
beforeEach(() => { jest.clearAllMocks(); postAPI.mockResolvedValue({}) })
test('projects with the same name are granted only by selected UUID and preserve sharing flags', async () => {
  const c = context({ selectedShareWith: 'Project', projects: [{ id: 'a', name: 'same' }, { id: 'b', name: 'same' }], selectedProjects: ['b'] })
  c.submitData(); await Promise.resolve()
  expect(postAPI).toHaveBeenCalledWith('updateTemplatePermissions', { id: 'image', projectids: 'b', op: 'add' })
})
test('project add/remove choices exclude already granted or ungranted UUIDs', () => {
  const c = context({ projects: [{ id: 'a' }, { id: 'b' }], permittedProjects: ['b'] })
  expect(Permissions.computed.projectsList.call(c)).toEqual([{ id: 'a' }]); c.selectedOperation = 'remove'
  expect(Permissions.computed.projectsList.call(c)).toEqual([{ id: 'b' }])
})
test.each(['add', 'remove'])('empty %s target cannot issue a permission request', op => {
  const c = context({ selectedOperation: op, selectedAccountsList: ' , ', showAccountSelect: false }); c.submitData()
  expect(postAPI).not.toHaveBeenCalled(); expect(c.validationError).toBe('message.template.permissions.target.required')
})
test('manual normal-user account names are trimmed and deduplicated without listing other accounts', () => {
  const c = context({ showAccountSelect: false, selectedAccountsList: ' alpha, beta ,alpha ' }); c.submitData()
  expect(postAPI).toHaveBeenCalledWith('updateTemplatePermissions', { id: 'image', accounts: 'alpha,beta', op: 'add' })
})
test('reset needs no selected target and does not submit flag or stale target fields', () => {
  const c = context({ selectedOperation: 'reset', selectedAccounts: ['old'] }); c.submitData()
  expect(postAPI).toHaveBeenCalledWith('updateTemplatePermissions', { id: 'image', op: 'reset' })
})
test('ISO permissions use the ISO response envelope and empty inventories are valid', async () => {
  const c = context({ isImageTypeIso: true })
  getAPI.mockResolvedValueOnce({ listisopermissionsresponse: { templatepermission: { account: ['member'], projectids: ['p'] } } })
  await c.fetchIsoPermissions(); expect(c.permittedAccounts).toEqual(['member']); expect(c.permittedProjects).toEqual(['p'])
  getAPI.mockResolvedValueOnce({ listaccountsresponse: {} }); await c.fetchAccounts(); expect(c.accounts).toEqual([])
  getAPI.mockResolvedValueOnce({ listprojectsresponse: {} }); await c.fetchProjects(); expect(c.projects).toEqual([])
})

test('a successful empty permissions reply clears earlier grants', async () => {
  const c = context({ permittedAccounts: ['old'], permittedProjects: ['old'] })
  getAPI.mockResolvedValueOnce({ listtemplatepermissionsresponse: { templatepermission: { id: 'image' } } })
  await c.fetchTemplatePermissions(); expect(c.permittedAccounts).toEqual([]); expect(c.permittedProjects).toEqual([])
})

test.each(['Admin', 'User'])('actual permission controls have linked accessible labels in %s mode', async roletype => {
  getAPI.mockResolvedValue({ listtemplatepermissionsresponse: { templatepermission: {} }, listaccountsresponse: { account: [] }, listprojectsresponse: { project: [] } })
  const w = mount(Permissions, { attachTo: document.body, props: { resource: { id: 'image', domainid: 'domain' } }, global: { provide: { parentFetchData: jest.fn() }, mocks: { $t: k => k, $route: { meta: { name: 'template' } }, $store: { getters: { features: { allowuserviewalldomainaccounts: false }, userInfo: { roletype } } } }, components: { 'a-select': Select, 'a-select-option': Select.Option, 'a-input': Input, 'a-button': Button, 'a-alert': Alert }, stubs: { ASelect: false, ASelectOption: false, AInput: false, AButton: false, AAlert: false, Wave: false }, directives: { focus: {}, 'ctrl-enter': {} } } })
  await flushPromises()
  const assertLabels = () => {
    const controls = w.findAll('input[id^="image-permissions-"]')
    expect(controls).toHaveLength(3)
    for (const input of controls) expect(Array.from(input.element.labels || []).some(label => label.textContent.trim())).toBe(true)
  }
  assertLabels(); await w.setData({ selectedShareWith: 'Project' }); assertLabels(); w.unmount()
})
