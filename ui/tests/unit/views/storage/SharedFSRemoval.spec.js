// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements. See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership. The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License. You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied. See the License for the
// specific language governing permissions and limitations
// under the License.

import { shallowMount, flushPromises } from '@vue/test-utils'
import SharedFSRemoval from '@/views/storage/SharedFSRemoval'
import { getAPI, postAPI } from '@/api'
jest.mock('@/api', () => ({ getAPI: jest.fn(), postAPI: jest.fn() }))

function preview (policy = 'PRESERVE_VOLUMES', hash = 'current-plan') {
  return { getsharedfilesystemdeletionplanresponse: { sharedfilesystemdeletionplan: { plan: JSON.stringify({ policy, planHash: hash, volumes: [{ id: 1, uuid: 'volume-1', sizeBytes: 100 }] }) } } }
}
function mount () {
  return shallowMount(SharedFSRemoval, { props: { resource: { id: 'service-1', name: 'fixture', state: 'Ready' }, currentAction: { api: 'destroySharedFileSystem', label: 'label.destroy.sharedfs' } },
    global: { mocks: { $t: key => key, $bytesToHumanReadableSize: value => String(value), $notifyError: jest.fn(), $pollJob: jest.fn() } } })
}

beforeEach(() => { jest.clearAllMocks(); getAPI.mockResolvedValue(preview()) })

describe('SharedFS explicit data-volume retention', () => {
  it('defaults to preserving data and requires a successful authoritative preview', async () => {
    const wrapper = mount()
    expect(wrapper.vm.policy).toBe('PRESERVE_VOLUMES')
    expect(wrapper.vm.canSubmit).toBe(false)
    await flushPromises()
    expect(wrapper.vm.canSubmit).toBe(true)
    expect(getAPI).toHaveBeenCalledWith('getSharedFileSystemDeletionPlan', { id: 'service-1', datavolumepolicy: 'PRESERVE_VOLUMES' }, expect.objectContaining({ preserveOnFailure: true }))
    wrapper.unmount()
  })
  it('never submits deletion when preview retrieval fails', async () => {
    getAPI.mockRejectedValue(new Error('preview unavailable'))
    const wrapper = mount(); await flushPromises()
    await wrapper.vm.submit()
    expect(wrapper.vm.readError).toBe(true)
    expect(postAPI).not.toHaveBeenCalled()
    wrapper.unmount()
  })
  it('requires acknowledgement and the exact name and sends the current hash', async () => {
    const wrapper = mount(); await flushPromises()
    wrapper.vm.policy = 'DELETE_VOLUMES'
    getAPI.mockResolvedValue(preview('DELETE_VOLUMES', 'delete-plan'))
    await wrapper.vm.refreshPlan()
    expect(wrapper.vm.canSubmit).toBe(false)
    wrapper.vm.acknowledged = true; wrapper.vm.confirmation = 'wrong'
    expect(wrapper.vm.canSubmit).toBe(false)
    wrapper.vm.confirmation = 'fixture'
    postAPI.mockResolvedValue({ destroysharedfilesystemresponse: { jobid: 'job-1' } })
    await wrapper.vm.submit()
    expect(postAPI).toHaveBeenCalledWith('destroySharedFileSystem', expect.objectContaining({ datavolumepolicy: 'DELETE_VOLUMES', confirmdataloss: 'fixture', expectedplanhash: 'delete-plan', forced: true, expunge: false }))
    expect(wrapper.emitted('close-action')).toHaveLength(1)
    wrapper.unmount()
  })
  it('ignores late responses belonging to the former policy', async () => {
    let resolveOld
    getAPI.mockImplementationOnce(() => new Promise(resolve => { resolveOld = resolve }))
    const wrapper = mount()
    wrapper.vm.policy = 'DELETE_VOLUMES'; getAPI.mockResolvedValue(preview('DELETE_VOLUMES', 'new-plan'))
    await wrapper.vm.refreshPlan(); resolveOld(preview()); await flushPromises()
    expect(wrapper.vm.plan.planHash).toBe('new-plan')
    expect(wrapper.vm.plan.policy).toBe('DELETE_VOLUMES')
    wrapper.unmount()
  })
})
