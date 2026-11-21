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
import { postAPI } from '@/api'
import projectSection from '@/config/section/project'
import CreateProjectDialog from '@/views/project/CreateProjectDialog.vue'

jest.mock('@/api', () => ({ postAPI: jest.fn() }))

const roleApis = {
  createProject: { params: [{ name: 'accountid' }, { name: 'userid' }] },
  listDomains: {},
  listAccounts: {},
  listUsers: {}
}

function mountDialog (role = 'Admin', apis = roleApis) {
  const pollJob = jest.fn().mockResolvedValue({ jobstatus: 1, jobresult: { project: { id: 'new-project' } } })
  const wrapper = shallowMount(CreateProjectDialog, {
    global: {
      mocks: {
        $store: { getters: { apis, userInfo: { account: 'caller', domain: 'ROOT', roletype: role } } },
        $t: key => key,
        $pollJob: pollJob
      }
    }
  })
  return { wrapper, pollJob }
}

describe('CreateProjectDialog', () => {
  beforeEach(() => {
    postAPI.mockReset().mockResolvedValue({ createprojectresponse: { jobid: 'job' } })
  })

  it('opens the custom form from the project list action', () => {
    const create = projectSection.actions.find(action => action.api === 'createProject')
    expect(create).toMatchObject({ listView: true, popup: true, selfManagedDialog: true })
  })

  it('omits owner selectors for the default caller-owned project', async () => {
    const { wrapper, pollJob } = mountDialog('User')
    await wrapper.setData({ name: ' new project ' })
    expect(wrapper.vm.canDelegate).toBe(false)
    await wrapper.vm.submit()
    expect(postAPI.mock.calls[0].slice(0, 2)).toEqual(['createProject', {
      name: 'new project', displaytext: '', ignoreproject: true
    }])
    expect(pollJob).toHaveBeenCalledTimes(1)
    expect(wrapper.emitted('refresh-data')).toHaveLength(1)
    wrapper.unmount()
  })

  it('clears scoped account and user when the domain changes', async () => {
    const { wrapper } = mountDialog('DomainAdmin')
    await wrapper.setData({ name: 'new project', delegated: true })
    wrapper.vm.selectDomain({ id: 'first-domain', path: 'ROOT/first' })
    wrapper.vm.selectAccount({ id: 'old-account', domainid: 'first-domain', name: 'first' })
    wrapper.vm.selectUser({ id: 'old-user', accountid: 'old-account', username: 'first-user' })
    wrapper.vm.selectDomain({ id: 'new-domain', path: 'ROOT/next' })
    wrapper.vm.selectAccount({ id: 'old-account', domainid: 'first-domain' })
    expect(wrapper.vm.submitDisabled).toBe(true)
    expect(wrapper.vm.user).toBeNull()
    await wrapper.vm.submit()
    expect(postAPI).not.toHaveBeenCalled()

    wrapper.vm.selectAccount({ id: 'new-account', domainid: 'new-domain', name: 'next' })
    wrapper.vm.selectUser({ id: 'new-user', accountid: 'new-account', username: 'next-user' })
    expect(wrapper.vm.ownerAccount).toBe('next')
    expect(wrapper.vm.ownerDomain).toBe('ROOT/next')
    await wrapper.vm.submit()
    expect(postAPI.mock.calls[0].slice(0, 2)).toEqual(['createProject', {
      name: 'new project',
      displaytext: '',
      ignoreproject: true,
      domainid: 'new-domain',
      accountid: 'new-account',
      userid: 'new-user'
    }])
    wrapper.unmount()
  })

  it('keeps input and retries the existing job when its result is unknown', async () => {
    const { wrapper, pollJob } = mountDialog()
    pollJob.mockResolvedValueOnce({ trackingStatus: 'unknown' })
    await wrapper.setData({ name: 'new project' })
    await wrapper.vm.submit()
    expect(wrapper.vm.closed).toBe(false)
    expect(wrapper.vm.fieldsLocked).toBe(true)
    await wrapper.vm.submit()
    expect(postAPI).toHaveBeenCalledTimes(1)
    expect(pollJob).toHaveBeenLastCalledWith(expect.objectContaining({ jobId: 'job', retry: true }))
    expect(wrapper.emitted('refresh-data')).toHaveLength(1)
    wrapper.unmount()
  })
})
