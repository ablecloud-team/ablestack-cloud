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

import DetailSettings from '@/components/view/DetailSettings.vue'
import { getAPI } from '@/api'
import axios from 'axios'

jest.mock('@/api', () => ({ getAPI: jest.fn(), postAPI: jest.fn() }))
jest.mock('@/vue-app', () => ({ vueProps: {} }))
const flush = async () => { for (let i = 0; i < 6; i++) await Promise.resolve() }
const resource = (extra = {}) => ({ id: 'vm', state: 'Stopped', templateid: 'image', templateformat: 'ISO', ...extra })
function context () {
  return { ...DetailSettings.data(), ...DetailSettings.methods, $route: { meta: { name: 'vm', resourceType: 'UserVm' } }, $notifyError: jest.fn() }
}
describe('VM detail settings metadata', () => {
  beforeEach(() => { jest.clearAllMocks(); getAPI.mockResolvedValue({}) })
  it('does not query a template for an ISO-based VM', async () => {
    const vm = context()
    vm.updateResource(resource())
    await flush()
    expect(getAPI).toHaveBeenCalledTimes(1)
    expect(getAPI).toHaveBeenCalledWith('listDetailOptions', { resourcetype: 'UserVm', resourceid: 'vm' })
    expect(vm.deployasistemplate).toBe(false)
    expect(vm.disableSettings).toBe(false)
  })
  it('preserves deploy-as-is restrictions for disk templates', async () => {
    getAPI.mockImplementation(name => Promise.resolve(name === 'listTemplates' ? { listtemplatesresponse: { template: [{ deployasis: true }] } } : {}))
    const vm = context()
    vm.updateResource(resource({ templateformat: 'QCOW2' }))
    expect(vm.disableSettings).toBe(true)
    await flush()
    expect(vm.deployasistemplate).toBe(true)
    expect(vm.disableSettings).toBe(false)
  })
  it('handles an empty template response without enabling edits', async () => {
    const vm = context()
    vm.updateResource(resource({ templateformat: 'QCOW2' }))
    await flush()
    expect(vm.disableSettings).toBe(true)
  })
  it('does not issue an unfiltered lookup when template ID is absent', async () => {
    context().updateResource(resource({ templateid: undefined }))
    await flush()
    expect(getAPI).toHaveBeenCalledTimes(1)
  })
  it('handles API errors and keeps template edits disabled', async () => {
    const error = new Error('failed')
    getAPI.mockRejectedValue(error)
    const vm = context()
    vm.updateResource(resource({ templateformat: 'QCOW2' }))
    await flush()
    expect(vm.$notifyError).toHaveBeenCalledWith(error)
    expect(vm.disableSettings).toBe(true)
  })
  it('consumes route cancellation without error notifications', async () => {
    getAPI.mockRejectedValue(new axios.CanceledError('canceled'))
    const vm = context()
    vm.updateResource(resource({ templateformat: 'QCOW2' }))
    await flush()
    expect(vm.$notifyError).not.toHaveBeenCalled()
  })
  it('ignores older responses after switching resources', async () => {
    let resolveOld
    getAPI.mockImplementationOnce(() => new Promise(resolve => { resolveOld = resolve }))
    const vm = context()
    vm.updateResource(resource())
    vm.updateResource(resource({ id: 'next' }))
    resolveOld({ listdetailoptionsresponse: { detailoptions: { details: { stale: ['value'] } } } })
    await flush()
    expect(vm.detailOptions).toEqual({})
  })
  it('ignores pending metadata after unmount', async () => {
    let resolveOld
    getAPI.mockImplementationOnce(() => new Promise(resolve => { resolveOld = resolve }))
    const vm = context()
    vm.updateResource(resource())
    DetailSettings.beforeUnmount.call(vm)
    resolveOld({ listdetailoptionsresponse: { detailoptions: { details: { stale: ['value'] } } } })
    await flush()
    expect(vm.detailOptions).toEqual({})
  })
  it('keeps running VM settings disabled', async () => {
    const vm = context()
    vm.updateResource(resource({ state: 'Running' }))
    await flush()
    expect(vm.disableSettings).toBe(true)
  })
})
