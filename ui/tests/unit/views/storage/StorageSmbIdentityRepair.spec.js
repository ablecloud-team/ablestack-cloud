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

import Widget from '@/views/storage/StorageSmbIdentityRepair'
import { getAPI, postAPI } from '@/api'
jest.mock('@/api', () => ({ getAPI: jest.fn(), postAPI: jest.fn() }))
const vm = () => ({ generation: 0, instanceId: 'instance', instanceName: 'service', confirmation: 'service', maintenanceWindow: true, supported: true, confirmed: true, requestKey: 'stable-request', saving: false, visible: true, $emit: jest.fn(), $t: key => key })
describe('Scoped SMB authentication maintenance', () => {
  beforeEach(() => { getAPI.mockReset(); postAPI.mockReset() })
  it('requires the exact instance name and an explicit maintenance window', () => {
    const value = vm()
    value.confirmation = 'other'
    expect(Widget.computed.confirmed.call(value)).toBe(false)
    value.confirmation = 'service'; value.maintenanceWindow = false
    expect(Widget.computed.confirmed.call(value)).toBe(false)
    value.maintenanceWindow = true
    expect(Widget.computed.confirmed.call(value)).toBe(true)
  })
  it('does not submit unconfirmed maintenance', async () => {
    const value = vm(); value.confirmed = false
    await Widget.methods.repair.call(value)
    expect(postAPI).not.toHaveBeenCalled()
  })
  it('passes scoped confirmation and refreshes history only after the repair job completes', async () => {
    const value = vm()
    postAPI.mockResolvedValue({ repairstorageservicesmbidentityresponse: { jobid: 'job' } })
    getAPI.mockResolvedValue({ queryasyncjobresultresponse: { jobstatus: 1 } })
    await Widget.methods.repair.call(value)
    expect(postAPI.mock.calls[0][1]).toEqual({ instanceid: 'instance', confirmation: 'service', maintenancewindow: true, idempotencykey: 'stable-request' })
    expect(value.$emit).toHaveBeenCalledWith('operation-updated', 'instance')
    expect(value.visible).toBe(false)
  })
  it('retains the idempotency key when a maintenance result is unknown', async () => {
    const value = vm()
    postAPI.mockRejectedValue(new Error('Transport timeout'))
    await Widget.methods.repair.call(value)
    expect(value.requestKey).toBe('stable-request')
    expect(value.error).toBe('Transport timeout')
    Widget.methods.close.call(value)
    Widget.methods.open.call(value)
    expect(value.requestKey).toBe('stable-request')
    expect(value.$emit).not.toHaveBeenCalled()
  })
  it('never polls an old service job or publishes its result on a different service', async () => {
    let complete
    const value = vm()
    postAPI.mockImplementation(() => new Promise(resolve => { complete = resolve }))
    const pending = Widget.methods.repair.call(value)
    value.instanceId = 'other'; value.generation++
    complete({ repairstorageservicesmbidentityresponse: { jobid: 'old-job' } })
    await pending
    expect(getAPI).not.toHaveBeenCalled()
    expect(value.$emit).not.toHaveBeenCalled()
  })
  it('keeps the original maintenance scope after a failed job and refreshes its recovery history', async () => {
    const value = vm()
    postAPI.mockResolvedValue({ repairstorageservicesmbidentityresponse: { jobid: 'job' } })
    getAPI.mockResolvedValue({ queryasyncjobresultresponse: { jobstatus: 2, jobresult: { errortext: 'ACTIVE_SESSIONS' } } })
    await Widget.methods.repair.call(value)
    expect(value.error).toBe('ACTIVE_SESSIONS')
    expect(value.requestKey).toBe('stable-request')
    expect(value.visible).toBe(true)
    expect(value.$emit).toHaveBeenCalledWith('operation-updated', 'instance')
    Widget.methods.close.call(value); Widget.methods.open.call(value)
    await Widget.methods.repair.call(value)
    expect(postAPI.mock.calls[1][1].idempotencykey).toBe('stable-request')
  })
})
