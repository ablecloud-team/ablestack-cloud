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
import { shallowMount } from '@vue/test-utils'
import { reactive } from 'vue'
import { getAPI, postAPI } from '@/api'
jest.mock('@/api', () => ({ getAPI: jest.fn(), postAPI: jest.fn() }))
const envelope = (api, result) => ({ [api.toLowerCase() + 'response']: { storageserviceruntime: { id: 'service', success: true, resultjson: JSON.stringify({ instanceUuid: 'service', state: 'RUNNING', phase: 'PREFLIGHT', controlSupported: true, cancelable: false, cancelRequested: false, drainRequired: false, drainSupported: false, ...result }) } } })
function vm (control = {}) {
  const value = { ...Widget.data(), instanceId: 'service', operationId: 'operation', instanceName: 'service-name', generation: 0, loading: false, readError: false, busy: '', confirmation: '', control: { instanceUuid: 'service', operationUuid: 'operation', controlRevision: 7, desiredRevision: 42, state: 'RUNNING', phase: 'PREFLIGHT', controlSupported: true, drainSupported: true, cancelable: true, drainState: 'NOT_REQUESTED', cancelRequested: false, ...control }, can: () => true, unwrap: Widget.methods.unwrap, $emit: jest.fn(), $t: key => key }
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

describe('Operation control live safety and observations', () => {
  beforeEach(() => { getAPI.mockReset(); postAPI.mockReset() })
  afterEach(() => { jest.useRealTimers() })
  it('does not offer drain when native session hold is unavailable', () => {
    const value = vm({ drainRequired: true, drainSupported: false })
    value.confirmation = value.instanceName
    expect(value.canDrain).toBe(false)
  })
  it('does not duplicate a drain already requested or observed complete', () => {
    for (const drainState of ['REQUESTED', 'DRAINING', 'DRAINED']) {
      const value = vm({ drainRequired: true, drainSupported: true, drainState })
      value.confirmation = value.instanceName
      expect(value.canDrain).toBe(false)
    }
  })
  it('does not enable cancellation on an unsupported control response', () => {
    expect(vm({ controlSupported: false, cancelable: true }).canCancel).toBe(false)
  })
  it('rejects a runtime envelope belonging to another instance', () => {
    const response = envelope('getStorageServiceOperationControl', { operationUuid: 'operation', controlRevision: 8 })
    response.getstorageserviceoperationcontrolresponse.storageserviceruntime.id = 'other'
    expect(() => Widget.methods.unwrap.call(vm(), response, 'getStorageServiceOperationControl')).toThrow()
  })
  it('does not race an old observation against an in-flight control request', async () => {
    const value = vm({ cancelable: true }); value.busy = 'cancel'
    getAPI.mockResolvedValue(envelope('getStorageServiceOperationControl', { operationUuid: 'operation', controlRevision: 7 }))
    await Widget.methods.refresh.call(value)
    expect(getAPI).not.toHaveBeenCalled()
  })
  it('requires a fresh successful observation after a lost control POST', async () => {
    const value = vm({ cancelable: true })
    postAPI.mockRejectedValue(new Error('response lost'))
    await Widget.methods.request.call(value, 'cancel')
    await Widget.methods.request.call(value, 'cancel')
    expect(postAPI).toHaveBeenCalledTimes(1)
    getAPI.mockResolvedValue(envelope('getStorageServiceOperationControl', { instanceUuid: 'service', operationUuid: 'operation', controlRevision: 8, controlSupported: true, state: 'RUNNING', phase: 'PREFLIGHT', cancelRequested: true, cancelable: true, drainRequired: false, drainSupported: false }))
    await Widget.methods.refresh.call(value)
    expect(value.control.cancelRequested).toBe(true)
    await Widget.methods.request.call(value, 'cancel')
    expect(postAPI).toHaveBeenCalledTimes(1)
  })
  it('refreshes a visible active operation and stops network reads once terminal', async () => {
    jest.useFakeTimers()
    Object.defineProperty(document, 'hidden', { configurable: true, value: false })
    const state = phase => envelope('getStorageServiceOperationControl', { instanceUuid: 'service', operationUuid: 'operation', controlRevision: 8, state: phase, phase, controlSupported: true, cancelRequested: false, cancelable: false, drainRequired: false, drainSupported: false })
    getAPI.mockResolvedValueOnce(state('RUNNING')).mockResolvedValue(state('COMPLETE'))
    const wrapper = shallowMount(Widget, { props: { instanceId: 'service', operationId: 'operation' }, global: { mocks: { $store: { getters: { apis: { getStorageServiceOperationControl: {} } } }, $t: key => key } } })
    wrapper.element.getClientRects = () => [{ width: 100, height: 100 }]
    for (let i = 0; i < 8; i++) await Promise.resolve()
    jest.advanceTimersByTime(5000)
    for (let i = 0; i < 8; i++) await Promise.resolve()
    expect(getAPI).toHaveBeenCalledTimes(2)
    jest.advanceTimersByTime(30000)
    for (let i = 0; i < 8; i++) await Promise.resolve()
    expect(getAPI).toHaveBeenCalledTimes(2)
    wrapper.unmount(); jest.useRealTimers()
  })
  it('discards an old user observation even if the operation UUID stays the same', async () => {
    let finish
    const getters = reactive({ apis: { getStorageServiceOperationControl: {} }, userInfo: { id: 'user-a' }, project: { id: 'p' } })
    getAPI.mockImplementationOnce(() => new Promise(resolve => { finish = resolve })).mockResolvedValue(envelope('getStorageServiceOperationControl', { instanceUuid: 'service', operationUuid: 'operation', controlRevision: 9, state: 'COMPLETE', phase: 'COMPLETE', controlSupported: true, cancelable: false, cancelRequested: false, drainRequired: false, drainSupported: false }))
    const wrapper = shallowMount(Widget, { props: { instanceId: 'service', operationId: 'operation' }, global: { mocks: { $store: { getters }, $t: key => key } } })
    getters.userInfo.id = 'user-b'
    await wrapper.vm.$nextTick()
    finish(envelope('getStorageServiceOperationControl', { instanceUuid: 'service', operationUuid: 'operation', controlRevision: 8, state: 'RUNNING', phase: 'PREFLIGHT', controlSupported: true, cancelable: true, cancelRequested: false, drainRequired: false, drainSupported: false }))
    for (let i = 0; i < 8; i++) await Promise.resolve()
    expect(wrapper.vm.control?.controlRevision).toBe(9)
    expect(wrapper.vm.canCancel).toBe(false)
    wrapper.unmount()
  })
})

describe('Session drain safe boundary', () => {
  beforeEach(() => { postAPI.mockReset() })
  it.each([false, undefined])('does not drain outside a confirmed cancelable phase (%s)', async cancelable => {
    const value = vm({ drainRequired: true, drainSupported: true, cancelable })
    value.confirmation = value.instanceName
    await Widget.methods.request.call(value, 'drain')
    expect(value.canDrain).toBe(false)
    expect(postAPI).not.toHaveBeenCalled()
  })
  it.each([undefined, '', 'UNKNOWN', 'FAILED'])('does not infer an unrequested drain from an unknown state (%s)', async drainState => {
    const value = vm({ drainRequired: true, drainSupported: true, drainState })
    value.confirmation = value.instanceName
    await Widget.methods.request.call(value, 'drain')
    expect(value.canDrain).toBe(false)
    expect(postAPI).not.toHaveBeenCalled()
  })
})

describe('Typed TCP session observation', () => {
  it('labels only the explicit TCP-only observation as active TCP connections', () => {
    const value = vm({ observed: { sessionObservationKind: 'TCP_CONNECTIONS_ONLY', activeSessions: 3 } })
    const row = Widget.computed.requirementRows.call(value).find(row => row.key === 'sessions')
    expect(row.label).toBe('label.storage.operation.resource.tcp.connections')
    expect(row.observed).toBe(3)
    value.control.observed.sessionObservationKind = undefined
    expect(Widget.computed.requirementRows.call(value).find(row => row.key === 'sessions').label).toBe('label.storage.operation.resource.sessions')
  })
})
