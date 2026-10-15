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

import Widget from '@/views/storage/StorageControlPolicy'
import { shallowMount } from '@vue/test-utils'
import { reactive } from 'vue'
import { getAPI, postAPI } from '@/api'
jest.mock('@/api', () => ({ getAPI: jest.fn(), postAPI: jest.fn() }))
const policy = (extra = {}) => ({ instanceUuid: 'instance', policyRevision: 4, enabled: false, active: false, globalEnabled: false, coverage: { CONFIGURATION: 'LINKED', ROOT_UPGRADE: 'PENDING' }, ...extra })
const evidence = value => ({ success: true, resultjson: value })
const vm = () => {
  const value = { ...Widget.data(), ...Widget.methods, instanceId: 'instance', instanceName: 'service', policy: policy(), supported: true, canConfigure: true, confirmation: 'service', desiredEnabled: true, requestKey: 'scope-key', visible: true, storageMutationDisabled: false, $t: key => key, $emit: jest.fn(), refresh: jest.fn() }
  for (const key of ['writable', 'confirmed', 'retainedRequest', 'canOpen']) {
    if (Widget.computed[key]) Object.defineProperty(value, key, { get: () => Widget.computed[key].call(value) })
  }
  return value
}
describe('Per-instance resource control policy', () => {
  beforeEach(() => { getAPI.mockReset(); postAPI.mockReset() })
  it('requires API capability and exact instance name before changing a policy', () => {
    const value = vm()
    value.confirmation = 'other'
    expect(Widget.computed.confirmed.call(value)).toBe(false)
    value.confirmation = 'service'; value.desiredEnabled = false
    expect(Widget.computed.confirmed.call(value)).toBe(false)
    value.desiredEnabled = true
    expect(Widget.computed.confirmed.call(value)).toBe(true)
  })
  it('does not convert a failed policy read into a writable default', async () => {
    const value = vm(); value.policy = policy()
    getAPI.mockRejectedValue(new Error('timeout'))
    await Widget.methods.refresh.call(value)
    expect(value.policy.policyRevision).toBe(4)
    expect(value.error).toBe('timeout')
    expect(Widget.computed.writable.call(value)).toBe(false)
  })
  it('ignores a late policy read after changing SharedFS instance', async () => {
    let complete
    const value = vm()
    getAPI.mockImplementation(() => new Promise(resolve => { complete = resolve }))
    const pending = Widget.methods.refresh.call(value)
    value.instanceId = 'other'; value.generation++
    complete({ getstorageservicecontrolpolicyresponse: { storageserviceruntime: evidence(policy()) } })
    await pending
    expect(value.policy.instanceUuid).toBe('instance')
    expect(value.loading).toBe(true)
  })
  it('rejects foreign policy scope and invalid server revision', () => {
    expect(() => Widget.methods.evidence.call(vm(), evidence(policy({ instanceUuid: 'other' })), 'getStorageServiceControlPolicy', 'instance')).toThrow()
    expect(() => Widget.methods.evidence.call(vm(), evidence(policy({ policyRevision: '4' })), 'getStorageServiceControlPolicy', 'instance')).toThrow()
  })
  it('posts the policy revision CAS and closes only after matching async result', async () => {
    const value = vm()
    postAPI.mockResolvedValue({ configurestorageservicecontrolpolicyresponse: { jobid: 'job' } })
    getAPI.mockResolvedValue({ queryasyncjobresultresponse: { jobstatus: 1, jobresult: { storageserviceruntime: evidence(policy({ policyRevision: 5, enabled: true })) } } })
    await Widget.methods.save.call(value)
    expect(postAPI.mock.calls[0][1]).toEqual({ instanceid: 'instance', enabled: true, expectedpolicyrevision: 4, confirmation: 'service', idempotencykey: 'scope-key' })
    expect(value.visible).toBe(false)
    expect(value.policy.enabled).toBe(true)
    expect(value.$emit).toHaveBeenCalledWith('operation-updated', 'instance')
  })
  it('retains the key after unknown transport result and does not claim successful enable', async () => {
    const value = vm()
    postAPI.mockRejectedValue(new Error('Transport timeout'))
    await Widget.methods.save.call(value)
    expect(value.requestKey).toBe('scope-key')
    expect(value.requestIntent).toMatchObject({ enabled: true, expectedpolicyrevision: 4 })
    value.policy.policyRevision = 5
    await Widget.methods.save.call(value)
    expect(postAPI.mock.calls[1][1].expectedpolicyrevision).toBe(4)
    expect(value.policy.enabled).toBe(false)
    expect(value.$emit).not.toHaveBeenCalled()
  })
  it('shows known unsupported-native rejection while preserving the disabled policy', async () => {
    const value = vm()
    postAPI.mockResolvedValue({ configurestorageservicecontrolpolicyresponse: { jobid: 'job' } })
    getAPI.mockResolvedValue({ queryasyncjobresultresponse: { jobstatus: 2, jobresult: { errortext: 'FRESH_NATIVE_OBSERVATION_REQUIRED' } } })
    await Widget.methods.save.call(value)
    expect(value.saveError).toBe('FRESH_NATIVE_OBSERVATION_REQUIRED')
    expect(value.policy.enabled).toBe(false)
    expect(value.requestKey).toBe('')
    expect(value.$emit).not.toHaveBeenCalled()
  })
})

describe('Resource-control policy unknown-result retracing', () => {
  beforeEach(() => { getAPI.mockReset(); postAPI.mockReset() })
  it('retains the known job when reopened and follows it by READ without another POST', async () => {
    const value = vm()
    postAPI.mockResolvedValue({ configurestorageservicecontrolpolicyresponse: { jobid: 'known-job' } })
    getAPI.mockRejectedValueOnce(new Error('poll response lost'))
    await value.save()
    expect(value.jobId).toBe('known-job')
    value.policy = policy({ enabled: true, policyRevision: 5 })
    value.close(); value.open(); value.confirmation = 'service'
    expect(value.jobId).toBe('known-job')
    expect(value.confirmed).toBe(true)
    getAPI.mockResolvedValue({ queryasyncjobresultresponse: { jobstatus: 1, jobresult: { storageserviceruntime: evidence(policy({ enabled: true, policyRevision: 5 })) } } })
    await value.save()
    expect(postAPI).toHaveBeenCalledTimes(1)
    expect(getAPI.mock.calls.filter(call => call[0] === 'queryAsyncJobResult').map(call => call[1].jobid)).toEqual(['known-job', 'known-job'])
    expect(value.visible).toBe(false)
  })
  it('allows exact same-key unknown POST replay after observation already matches the requested value', async () => {
    const value = vm()
    postAPI.mockRejectedValueOnce(new Error('POST response lost'))
    await value.save()
    value.policy = policy({ enabled: true, policyRevision: 5 })
    expect(value.confirmed).toBe(true)
    postAPI.mockResolvedValue({ configurestorageservicecontrolpolicyresponse: { jobid: 'replay-job' } })
    getAPI.mockResolvedValue({ queryasyncjobresultresponse: { jobstatus: 1, jobresult: { storageserviceruntime: evidence(policy({ enabled: true, policyRevision: 5 })) } } })
    await value.save()
    expect(postAPI).toHaveBeenCalledTimes(2)
    expect(postAPI.mock.calls[1][1]).toEqual(postAPI.mock.calls[0][1])
    expect(postAPI.mock.calls[1][1].expectedpolicyrevision).toBe(4)
    expect(value.visible).toBe(false)
  })
  it('permits only known-job READ retracing while the parent gate or policy observation is unavailable', async () => {
    const value = vm()
    postAPI.mockResolvedValue({ configurestorageservicecontrolpolicyresponse: { jobid: 'known-job' } })
    getAPI.mockRejectedValueOnce(new Error('poll lost'))
    await value.save()
    value.error = 'policy read failed'; value.storageMutationDisabled = true
    value.storageMutationGate = { blocked: () => true, message: () => 'current writer' }
    value.close(); value.open(); value.confirmation = 'service'
    expect(value.visible).toBe(true)
    expect(value.confirmed).toBe(true)
    getAPI.mockResolvedValue({ queryasyncjobresultresponse: { jobstatus: 1, jobresult: { storageserviceruntime: evidence(policy({ enabled: true, policyRevision: 5 })) } } })
    await value.save()
    expect(postAPI).toHaveBeenCalledTimes(1)
    expect(value.visible).toBe(false)
  })
  it('does not let a new intent or changed confirmation bypass the mutation gate', async () => {
    const value = vm(); value.storageMutationDisabled = true
    value.storageMutationGate = { blocked: () => true, message: () => 'current writer' }
    await value.save()
    expect(postAPI).not.toHaveBeenCalled()
    expect(value.requestIntent).toBe(null)
    value.storageMutationDisabled = false; value.storageMutationGate = null
    postAPI.mockRejectedValue(new Error('POST response lost'))
    await value.save()
    value.confirmation = 'other'
    await value.save()
    expect(postAPI).toHaveBeenCalledTimes(1)
  })
  it('does not race policy observations against a pending configure POST', async () => {
    const value = vm(); value.saving = true
    await Widget.methods.refresh.call(value)
    expect(getAPI).not.toHaveBeenCalled()
  })
  it('does not publish an old job result or preserve an intent across user/project changes', async () => {
    let finish
    const getters = reactive({ apis: { getStorageServiceControlPolicy: {}, configureStorageServiceControlPolicy: {} }, userInfo: { id: 'a' }, project: { id: 'one' } })
    getAPI.mockResolvedValue({ getstorageservicecontrolpolicyresponse: { storageserviceruntime: evidence(policy()) } })
    const wrapper = shallowMount(Widget, { props: { instanceId: 'instance', instanceName: 'service' }, global: { mocks: { $store: { getters }, $t: key => key } } })
    for (let i = 0; i < 8; i++) await Promise.resolve()
    wrapper.vm.open(); wrapper.vm.desiredEnabled = true; wrapper.vm.confirmation = 'service'
    postAPI.mockResolvedValue({ configurestorageservicecontrolpolicyresponse: { jobid: 'old-job' } })
    getAPI.mockImplementationOnce(() => new Promise(resolve => { finish = resolve }))
    const saving = wrapper.vm.save()
    for (let i = 0; i < 8; i++) await Promise.resolve()
    getters.project.id = 'two'; await wrapper.vm.$nextTick()
    for (let i = 0; i < 8; i++) await Promise.resolve()
    wrapper.vm.open(); wrapper.vm.desiredEnabled = true; wrapper.vm.confirmation = 'service'
    finish({ queryasyncjobresultresponse: { jobstatus: 1, jobresult: { storageserviceruntime: evidence(policy({ enabled: true, policyRevision: 5 })) } } })
    await saving
    expect(wrapper.vm.requestIntent).toBe(null)
    expect(wrapper.vm.jobId).toBe('')
    expect(wrapper.vm.policy.enabled).toBe(false)
    expect(wrapper.vm.visible).toBe(true)
    expect(wrapper.emitted('operation-updated')).toBeUndefined()
    wrapper.unmount()
  })
})

describe('Resource-control policy intent and logout boundaries', () => {
  beforeEach(() => { getAPI.mockReset(); postAPI.mockReset() })
  it('blocks changed form values for a retained intent even after a later policy observation', async () => {
    const value = vm()
    postAPI.mockRejectedValue(new Error('POST response lost'))
    await value.save()
    value.policy = policy({ enabled: true, policyRevision: 5 })
    value.desiredEnabled = false
    expect(value.confirmed).toBe(false)
    await value.save()
    expect(postAPI).toHaveBeenCalledTimes(1)
  })
  it('discards a pending policy read and cached request on logout', async () => {
    let finish
    const getters = reactive({ apis: { getStorageServiceControlPolicy: {}, configureStorageServiceControlPolicy: {} }, userInfo: { id: 'a' } })
    const state = reactive({ user: { token: 'session-a' } })
    getAPI.mockImplementation(() => new Promise(resolve => { finish = resolve }))
    const wrapper = shallowMount(Widget, { props: { instanceId: 'instance', instanceName: 'service' }, global: { mocks: { $store: { getters, state }, $t: key => key } } })
    wrapper.vm.requestKey = 'pending-key'; wrapper.vm.requestAttempted = true
    wrapper.vm.requestIntent = { instanceid: 'instance', enabled: true, confirmation: 'service', expectedpolicyrevision: 4, idempotencykey: 'pending-key' }
    wrapper.vm.jobId = 'old-job'
    state.user.token = ''; await wrapper.vm.$nextTick()
    finish({ getstorageservicecontrolpolicyresponse: { storageserviceruntime: evidence(policy({ enabled: true, policyRevision: 5 })) } })
    for (let i = 0; i < 8; i++) await Promise.resolve()
    expect(wrapper.vm.policy).toBe(null)
    expect(wrapper.vm.requestIntent).toBe(null)
    expect(wrapper.vm.requestKey).toBe('')
    expect(wrapper.vm.jobId).toBe('')
    expect(wrapper.vm.visible).toBe(false)
    expect(getAPI).toHaveBeenCalledTimes(1)
    wrapper.unmount()
  })
})
