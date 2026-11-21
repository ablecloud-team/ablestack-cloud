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
import InvitationsTemplate from '@/views/project/InvitationsTemplate.vue'

jest.mock('@/api', () => ({ getAPI: jest.fn(), postAPI: jest.fn() }))

const invitation = { id: 'invite', projectid: 'project', project: 'Review', state: 'Pending', active: true, userid: 'user' }
const response = (rows, count = rows.length) => ({ listprojectinvitationsresponse: { count, projectinvitation: rows } })

function mountInvitations (incoming = false) {
  return shallowMount(InvitationsTemplate, {
    props: {
      resource: { id: 'project', name: 'Review', state: 'Active' },
      ...(incoming ? { currentAction: { api: 'listProjectInvitations' } } : {})
    },
    global: {
      mocks: {
        $t: key => key,
        $store: { getters: { apis: { deleteProjectInvitation: {}, updateProjectInvitation: {} }, userInfo: { roletype: 'Admin', id: 'admin' } } },
        $pollJob: async () => ({ jobstatus: 1 })
      }
    }
  })
}

describe('project invitations', () => {
  beforeEach(() => {
    getAPI.mockReset()
    postAPI.mockReset()
  })

  it('uses the outgoing project scope and preserves the API total across pages', async () => {
    getAPI.mockResolvedValue(response([invitation], 17))
    const wrapper = mountInvitations()
    await flushPromises()
    wrapper.vm.changePage(2, 10)
    await flushPromises()
    expect(getAPI.mock.calls[getAPI.mock.calls.length - 1][1]).toMatchObject({
      direction: 'outgoing', projectid: 'project', activeonly: true, page: 2, pagesize: 10
    })
    expect(wrapper.vm.total).toBe(17)
    wrapper.unmount()
  })

  it('checks the active invitation again before submitting a revocation', async () => {
    getAPI.mockResolvedValueOnce(response([invitation])).mockResolvedValueOnce(response([]))
    const wrapper = mountInvitations()
    await flushPromises()
    wrapper.vm.open('revoke', invitation)
    await wrapper.vm.submit()
    expect(postAPI).not.toHaveBeenCalled()
    expect(wrapper.vm.staleTarget).toBe(true)
    expect(getAPI.mock.calls[getAPI.mock.calls.length - 1][1]).toMatchObject({ direction: 'outgoing', id: 'invite', activeonly: true })
    wrapper.unmount()
  })

  it('responds to received user invitations using the external user ID', async () => {
    getAPI.mockResolvedValue(response([invitation]))
    postAPI.mockResolvedValue({ updateprojectinvitationresponse: { jobid: 'job' } })
    const wrapper = mountInvitations(true)
    await flushPromises()
    expect(getAPI.mock.calls[0][1].direction).toBe('incoming')
    expect(getAPI.mock.calls[0][1].projectid).toBeUndefined()
    wrapper.vm.open('decline', invitation)
    await wrapper.vm.submit()
    expect(postAPI.mock.calls[0].slice(0, 2)).toEqual([
      'updateProjectInvitation',
      { projectid: 'project', userid: 'user', accept: false, ignoreproject: true }
    ])
    wrapper.unmount()
  })
})
