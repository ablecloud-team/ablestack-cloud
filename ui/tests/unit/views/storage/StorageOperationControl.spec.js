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

import Widget from '@/views/storage/StorageOperationControl'
import { getAPI, postAPI } from '@/api'
jest.mock('@/api', () => ({ getAPI: jest.fn(), postAPI: jest.fn() }))
const envelope = (api, result) => ({ [api.toLowerCase() + 'response']: { storageserviceruntime: { success: true, resultjson: JSON.stringify(result) } } })
function vm (control = {}) {
  const value = { instanceId: 'service', operationId: 'operation', instanceName: 'service-name', generation: 0, loading: false, readError: false, busy: '', confirmation: '', control: { operationUuid: 'operation', controlRevision: 7, desiredRevision: 42, ...control }, can: () => true, unwrap: Widget.methods.unwrap, $emit: jest.fn(), $t: key => key }
  for (const key of ['writable', 'canCancel', 'canDrain']) Object.defineProperty(value, key, { get: () => Widget.computed[key].call(value) })
  return value
}
describe('Scoped operation cancellation and drain requests', () => {
  beforeEach(() => { getAPI.mockReset(); postAPI.mockReset() })
  it('does not send cancellation when the formatter phase is not cancelable', async () => {
    const value = vm({ cancelable: false })
    await Widget.methods.request.call(value, 'cancel')
    expect(postAPI).not.toHaveBeenCalled()
  })
  it('uses controlRevision CAS instead of the configuration desiredRevision', async () => {
    const value = vm({ cancelable: true })
    postAPI.mockResolvedValue(envelope('cancelStorageServiceOperation', { operationUuid: 'operation', controlRevision: 8, cancelRequested: true }))
    await Widget.methods.request.call(value, 'cancel')
    expect(postAPI.mock.calls[0][1]).toEqual({ instanceid: 'service', operationid: 'operation', expectedrevision: 7 })
    expect(value.control.cancelRequested).toBe(true)
    expect(value.$emit).toHaveBeenCalledWith('operation-updated', 'service')
  })
  it('requires exact instance confirmation before requesting a session drain', async () => {
    const value = vm({ drainRequired: true })
    value.confirmation = 'other'
    await Widget.methods.request.call(value, 'drain')
    expect(postAPI).not.toHaveBeenCalled()
    value.confirmation = 'service-name'
    postAPI.mockResolvedValue(envelope('drainStorageServiceOperation', { operationUuid: 'operation', controlRevision: 8, drainState: 'REQUESTED' }))
    await Widget.methods.request.call(value, 'drain')
    expect(postAPI.mock.calls[0][1]).toEqual({ instanceid: 'service', operationid: 'operation', expectedrevision: 7, confirmation: 'service-name' })
  })
  it('preserves stale control information but disables writes after a failed refresh', async () => {
    const value = vm({ cancelable: true })
    getAPI.mockRejectedValue(new Error('read timeout'))
    await Widget.methods.refresh.call(value)
    expect(value.control.controlRevision).toBe(7)
    expect(value.readError).toBe(true)
    expect(value.canCancel).toBe(false)
  })
  it('does not publish a request result after changing the operation scope', async () => {
    let complete
    const value = vm({ cancelable: true })
    postAPI.mockImplementation(() => new Promise(resolve => { complete = resolve }))
    const request = Widget.methods.request.call(value, 'cancel')
    value.operationId = 'new'; value.generation++
    complete(envelope('cancelStorageServiceOperation', { operationUuid: 'operation', controlRevision: 8 }))
    await request
    expect(value.control.controlRevision).toBe(7)
    expect(value.$emit).not.toHaveBeenCalled()
  })
  it('rejects a successful response for a different operation', () => {
    const value = vm()
    expect(() => value.unwrap(envelope('getStorageServiceOperationControl', { operationUuid: 'other' }), 'getStorageServiceOperationControl')).toThrow('scope mismatch')
  })
  it('rejects string or missing CAS revisions and absent role capability', async () => {
    const value = vm({ cancelable: true, controlRevision: '7' })
    expect(value.writable).toBe(false)
    value.control.controlRevision = 7; value.can = () => false
    await Widget.methods.request.call(value, 'cancel')
    expect(postAPI).not.toHaveBeenCalled()
  })
  it('compares load per CPU with the per-CPU budget and does not infer a missing CPU count', () => {
    const value = vm({ requirements: { maxLoadPerCpu: 2 }, observed: { loadOneMinute: 4, onlineCpuCount: 8 } })
    expect(Widget.computed.requirementRows.call(value).find(row => row.key === 'load')).toEqual({ key: 'load', label: 'label.storage.operation.resource.load', required: 2, observed: 0.5 })
    delete value.control.observed.onlineCpuCount
    expect(Widget.computed.requirementRows.call(value).find(row => row.key === 'load').observed).toBe('—')
  })
})
