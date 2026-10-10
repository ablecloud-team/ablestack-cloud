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
import { getAPI, postAPI } from '@/api'
jest.mock('@/api', () => ({ getAPI: jest.fn(), postAPI: jest.fn() }))
const policy = (extra = {}) => ({ instanceUuid: 'instance', policyRevision: 4, enabled: false, active: false, globalEnabled: false, coverage: { CONFIGURATION: 'LINKED', ROOT_UPGRADE: 'PENDING' }, ...extra })
const evidence = value => ({ success: true, resultjson: value })
const vm = () => ({ generation: 0, instanceId: 'instance', instanceName: 'service', policy: policy(), supported: true, canConfigure: true, writable: true, confirmed: true, confirmation: 'service', desiredEnabled: true, requestKey: 'scope-key', requestIntent: null, loading: false, saving: false, visible: true, error: '', $t: key => key, $emit: jest.fn(), evidence: Widget.methods.evidence, refresh: jest.fn() })
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
