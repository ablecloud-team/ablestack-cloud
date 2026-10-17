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

import { mount, shallowMount, flushPromises } from '@vue/test-utils'
import { reactive } from 'vue'
import Widget from '@/views/storage/StorageBootReconcileStatus'
import { getAPI, postAPI } from '@/api'
import { bootHealth, INSTANCE, BOOT, NOW } from '../../fixtures/storageBootReconcile'
jest.mock('@/api', () => ({ getAPI: jest.fn(), postAPI: jest.fn() }))
const wire = (health = bootHealth(), id = INSTANCE) => ({ liststorageservicehealthresponse: { count: 1, storageserviceruntime: [{ id, success: false, resultjson: JSON.stringify(health) }] } })
const finished = () => {
  const value = bootHealth()
  Object.assign(value.bootReconcile, { status: 'READY', phase: 'COMPLETE', detailedVerified: true })
  Object.assign(value.bootReconcile.protocols.NFS, { applyState: 'VERIFIED', probeState: 'VERIFIED', verified: true, completedProbeCount: 10, detailVerificationKind: 'MOUNT_PROBES_COMPLETE' })
  return value
}
const deferred = () => { let finish; const promise = new Promise(resolve => { finish = resolve }); return { promise, resolve: finish } }
let wrapper, store
const render = (props, shallow = false) => {
  store = reactive({ getters: { apis: { listStorageServiceHealth: {} }, userInfo: { id: 'user-a' }, project: { id: 'project-a' } }, state: { user: { token: 'test-token' } } })
  wrapper = (shallow ? shallowMount : mount)(Widget, { props: { instanceId: INSTANCE, active: true, scopeKey: 'owned-fs/operations', ...props }, global: { mocks: { $store: store, $t: key => key } }, attachTo: document.body })
  wrapper.element.getClientRects = () => [{ width: 100, height: 100 }]
  return wrapper
}
describe('#914 actual AntD boot status and scoped readonly lifecycle', () => {
  beforeEach(() => {
    jest.useFakeTimers(); jest.spyOn(Date, 'now').mockReturnValue(NOW * 1000)
    Object.defineProperty(document, 'hidden', { configurable: true, value: false })
    getAPI.mockReset(); postAPI.mockReset()
  })
  afterEach(() => { if (wrapper) wrapper.unmount(); wrapper = null; jest.clearAllTimers(); jest.useRealTimers(); jest.restoreAllMocks(); document.body.innerHTML = '' })
  it('shows ready listeners and pending detailed NFS probes without a false success or invented percent', async () => {
    getAPI.mockResolvedValue(wire()); render(); await flushPromises()
    expect(wrapper.get('[role="status"]').classes()).toContain('ant-tag-blue')
    expect(wrapper.text()).toContain('label.storage.boot.status.RECONCILING')
    expect(wrapper.text()).toContain('label.storage.boot.probe.PENDING')
    expect(wrapper.text()).toContain('1 / 10')
    expect(wrapper.text()).toContain('message.storage.boot.probes.pending')
    expect(wrapper.text()).not.toContain('%')
    expect(getAPI).toHaveBeenCalledWith('listStorageServiceHealth', { instanceid: INSTANCE }, { timeout: 15000, preserveOnFailure: true })
    expect(postAPI).not.toHaveBeenCalled()
  })
  it.each([['DEGRADED', 'orange'], ['FAILED', 'red'], ['HELD', 'orange']])('does not describe terminal %s permission-denied details as ongoing probes', async (status, color) => {
    const value = bootHealth()
    value.status = status.toLowerCase()
    Object.assign(value.bootReconcile, {
      status,
      phase: status === 'HELD' ? 'HELD' : 'FAILED',
      attempt: 1,
      runtimeReady: true,
      detailedVerified: false,
      elapsedSeconds: 179.505190298,
      deadlineSeconds: 1500,
      remainingSeconds: 1320.494809197,
      lastFailure: {
        protocol: 'NFS',
        phase: 'PROBE_EXPORTS',
        endpointIp: '0.0.0.0',
        endpointPort: 2049,
        exportUuid: BOOT,
        exportName: 'issue914-nfs001',
        protocolMode: 'NFS4',
        code: 'PROBE_PERMISSION_DENIED',
        timedOut: false,
        retryable: false,
        cleanup: { complete: true, childActive: false, mountPresent: false }
      }
    })
    Object.assign(value.bootReconcile.protocols.NFS, {
      applyState: 'APPLIED',
      observationComplete: true,
      probeState: 'FAILED',
      completedProbeCount: 99,
      totalProbeCount: 100
    })
    Object.assign(value.bootReconcile.protocols.SMB, { completedProbeCount: 0, totalProbeCount: 0 })
    getAPI.mockResolvedValue(wire(value)); render(); await flushPromises()
    expect(wrapper.get('[role="status"]').classes()).toContain('ant-tag-' + color)
    expect(wrapper.text()).toContain('label.storage.boot.status.' + status)
    expect(wrapper.text()).not.toContain('message.storage.boot.probes.pending')
    expect(wrapper.text()).toContain('label.storage.boot.last.failure')
    expect(wrapper.text()).toContain('PROBE_PERMISSION_DENIED')
    expect(wrapper.text()).toContain('99 / 100')
    expect(wrapper.text()).toContain('label.storage.boot.verification.EXACT_RUNTIME_READBACK')
    expect(wrapper.text()).not.toContain('label.storage.boot.verification.MOUNT_PROBES_COMPLETE')
    jest.advanceTimersByTime(20000); await flushPromises()
    expect(getAPI).toHaveBeenCalledTimes(1)
    expect(postAPI).not.toHaveBeenCalled()
  })
  it('polls completion-first every five seconds and stops on terminal READY', async () => {
    getAPI.mockResolvedValueOnce(wire()).mockResolvedValue(wire(finished())); render(); await flushPromises()
    jest.advanceTimersByTime(5000); await flushPromises()
    expect(getAPI).toHaveBeenCalledTimes(2); expect(wrapper.get('[role="status"]').classes()).toContain('ant-tag-green')
    jest.advanceTimersByTime(60000); await flushPromises(); expect(getAPI).toHaveBeenCalledTimes(2)
  })
  it('never reads while the operation tab is inactive', async () => {
    getAPI.mockResolvedValue(wire()); render({ active: false }); await flushPromises()
    expect(getAPI).not.toHaveBeenCalled()
    await wrapper.setProps({ active: true }); await flushPromises(); expect(getAPI).toHaveBeenCalledTimes(1)
    await wrapper.setProps({ active: false }); jest.advanceTimersByTime(20000); await flushPromises(); expect(getAPI).toHaveBeenCalledTimes(1)
  })
  it('drops old tab results and bounds an in-flight GET without sending a concurrent request', async () => {
    const first = deferred(); getAPI.mockReturnValueOnce(first.promise).mockResolvedValue(wire(finished()))
    render(); await flushPromises(); await wrapper.setProps({ active: false }); await wrapper.setProps({ active: true })
    expect(getAPI).toHaveBeenCalledTimes(1)
    first.resolve(wire()); await flushPromises()
    expect(getAPI).toHaveBeenCalledTimes(2); expect(wrapper.text()).toContain('label.storage.boot.status.READY')
  })
  it.each(['instance', 'route', 'user', 'project', 'token'])('discards pending %s scope results before reading the new scope', async kind => {
    const first = deferred(); getAPI.mockReturnValueOnce(first.promise).mockResolvedValue(wire(finished()))
    render(); await flushPromises()
    if (kind === 'instance') await wrapper.setProps({ instanceId: BOOT })
    if (kind === 'route') await wrapper.setProps({ scopeKey: 'owned-fs/other-route' })
    if (kind === 'user') store.getters.userInfo.id = 'user-b'
    if (kind === 'project') store.getters.project.id = 'project-b'
    if (kind === 'token') store.state.user.token = 'changed-token'
    await flushPromises(); first.resolve(wire()); await flushPromises()
    if (kind === 'instance') expect(wrapper.find('.ant-tag-green').exists()).toBe(false)
    else expect(wrapper.text()).toContain('label.storage.boot.status.READY')
    expect(postAPI).not.toHaveBeenCalled()
  })
  it('stops after a read error and recovers through the real modal-free update button with GET only', async () => {
    getAPI.mockRejectedValueOnce(new Error('private raw traceback')).mockResolvedValue(wire(finished())); render(); await flushPromises()
    expect(wrapper.text()).toContain('message.storage.boot.read.failed'); expect(wrapper.text()).not.toContain('private raw traceback')
    jest.advanceTimersByTime(30000); await flushPromises(); expect(getAPI).toHaveBeenCalledTimes(1)
    await wrapper.get('button').trigger('click'); await flushPromises()
    expect(wrapper.get('[role="status"]').classes()).toContain('ant-tag-green'); expect(postAPI).not.toHaveBeenCalled()
  })
  it('cannot promote malformed, stale or another BOOT observation to READY', async () => {
    const value = finished(); value.bootReconcile.bootId = INSTANCE
    getAPI.mockResolvedValue(wire(value)); render(); await flushPromises()
    expect(wrapper.get('[role="status"]').classes()).not.toContain('ant-tag-green')
    expect(wrapper.text()).toContain('label.storage.boot.status.UNKNOWN')
  })
  it('treats no-config SKIPPED as no configuration instead of four ready protocol listeners', async () => {
    const value = finished(); value.bootReconcile.phase = 'SKIPPED'
    for (const row of Object.values(value.bootReconcile.protocols)) Object.assign(row, { configured: false, applyState: 'NOT_REQUIRED', listenerReady: false, observationComplete: true, probeState: 'NOT_REQUIRED', verified: true, completedProbeCount: 0, totalProbeCount: 0, skippedAclCount: 0, detailVerificationKind: 'NOT_REQUIRED' })
    getAPI.mockResolvedValue(wire(value)); render(); await flushPromises()
    expect(wrapper.text()).toContain('message.storage.boot.skipped')
    expect(wrapper.text()).toContain('label.storage.boot.apply.NOT_REQUIRED')
    expect(wrapper.get('[role="status"]').classes()).toContain('ant-tag-green')
  })
  it('drops a late unmounted result without re-reading or producing an error notification', async () => {
    const first = deferred(); getAPI.mockReturnValue(first.promise); render(); await flushPromises(); wrapper.unmount(); wrapper = null
    first.resolve(wire()); await flushPromises(); jest.advanceTimersByTime(30000)
    expect(getAPI).toHaveBeenCalledTimes(1); expect(postAPI).not.toHaveBeenCalled()
  })
})

describe('#914 bounded lifetime and authentication', () => {
  beforeEach(() => {
    jest.useFakeTimers(); jest.spyOn(Date, 'now').mockReturnValue(NOW * 1000)
    Object.defineProperty(document, 'hidden', { configurable: true, value: false })
    getAPI.mockReset(); postAPI.mockReset()
  })
  afterEach(() => { if (wrapper) wrapper.unmount(); wrapper = null; jest.clearAllTimers(); jest.useRealTimers(); jest.restoreAllMocks(); document.body.innerHTML = '' })
  it('ends automatic reads after 1530 seconds and permits only an explicit read to restart observation', async () => {
    getAPI.mockResolvedValue(wire()); render({}, true); await flushPromises()
    for (let elapsed = 5; elapsed <= 1530; elapsed += 5) {
      Date.now.mockReturnValue((NOW + elapsed) * 1000)
      const value = bootHealth(); value.generatedEpoch += elapsed; value.bootReconcile.updatedEpoch += elapsed
      getAPI.mockResolvedValue(wire(value))
      jest.advanceTimersByTime(5000); await flushPromises()
    }
    const stoppedReads = getAPI.mock.calls.length
    expect(stoppedReads).toBeGreaterThan(1); expect(stoppedReads).toBeLessThanOrEqual(307)
    expect(wrapper.vm.lifetimeEnded).toBe(true)
    jest.advanceTimersByTime(30000); await flushPromises(); expect(getAPI).toHaveBeenCalledTimes(stoppedReads)
    Date.now.mockReturnValue((NOW + 1531) * 1000)
    const value = bootHealth(); value.generatedEpoch += 1531; value.bootReconcile.updatedEpoch += 1531
    getAPI.mockResolvedValue(wire(value))
    await wrapper.vm.refresh(true); await flushPromises()
    expect(getAPI).toHaveBeenCalledTimes(stoppedReads + 1)
    expect(wrapper.vm.lifetimeEnded).toBe(false)
    expect(postAPI).not.toHaveBeenCalled()
  })
  it('invalidates a lost logged-out observation without sending a fresh unauthenticated GET', async () => {
    const first = deferred(); getAPI.mockReturnValue(first.promise)
    render(); await flushPromises(); store.state.user.token = ''; await flushPromises()
    first.resolve(wire(finished())); await flushPromises(); jest.advanceTimersByTime(30000)
    expect(getAPI).toHaveBeenCalledTimes(1)
    expect(wrapper.find('.ant-tag-green').exists()).toBe(false)
    expect(wrapper.get('button').attributes('disabled')).toBeDefined()
  })
  it('rejects a foreign health envelope even when its nested boot observation has the expected instance', async () => {
    getAPI.mockResolvedValue(wire(finished(), BOOT)); render(); await flushPromises()
    expect(wrapper.text()).toContain('message.storage.boot.read.failed')
    expect(wrapper.find('.ant-tag-green').exists()).toBe(false)
  })
  it('retains a historical timeout beside terminal READY without showing a current failed tag', async () => {
    const declared = require('../../fixtures/storageBootReconcileDeclared.json')
    const value = finished(); value.bootReconcile.lastFailure = declared.samples.allRequiredDetailedVerified.lastFailure
    getAPI.mockResolvedValue(wire(value)); render(); await flushPromises()
    expect(wrapper.get('[role="status"]').classes()).toContain('ant-tag-green')
    expect(wrapper.text()).toContain('label.storage.boot.last.failure')
    expect(wrapper.text()).toContain('NFS_PROBE_TIMEOUT')
    expect(wrapper.text()).not.toContain('message.storage.boot.probes.pending')
  })
})

describe('#914 v2 qualified detailed-verification rendering', () => {
  beforeEach(() => {
    jest.useFakeTimers(); jest.spyOn(Date, 'now').mockReturnValue(NOW * 1000)
    getAPI.mockReset(); postAPI.mockReset()
  })
  afterEach(() => { if (wrapper) wrapper.unmount(); wrapper = null; jest.clearAllTimers(); jest.useRealTimers(); jest.restoreAllMocks(); document.body.innerHTML = '' })
  it('shows exact inventory/eligible mounts and ACL exclusions beside 0 eligible mounts', async () => {
    const value = finished()
    Object.assign(value.bootReconcile.protocols.NFS, { completedProbeCount: 0, totalProbeCount: 0, skippedAclCount: 8, detailVerificationKind: 'EXACT_INVENTORY_AND_ELIGIBLE_MOUNTS' })
    getAPI.mockResolvedValue(wire(value)); render(); await flushPromises()
    expect(wrapper.get('[role="status"]').classes()).toContain('ant-tag-green')
    expect(wrapper.text()).toContain('message.storage.boot.acl.skipped')
    expect(wrapper.text()).toContain('label.storage.boot.verification.EXACT_INVENTORY_AND_ELIGIBLE_MOUNTS')
    const nfs = wrapper.findAll('tr').find(row => row.text().includes('NFS'))
    expect(nfs.text()).toContain('8'); expect(nfs.text()).toContain('0 / 0')
    expect(nfs.text()).not.toContain('MOUNT_PROBES_COMPLETE')
    expect(postAPI).not.toHaveBeenCalled()
  })
  it('keeps runtime-readback completion distinct from a completed NFS mount', async () => {
    getAPI.mockResolvedValue(wire(finished())); render(); await flushPromises()
    expect(wrapper.text()).toContain('label.storage.boot.verification.EXACT_RUNTIME_READBACK')
    expect(wrapper.text()).toContain('label.storage.boot.verification.MOUNT_PROBES_COMPLETE')
  })
  it('displays a failed absence check rather than hiding the protocol as UNKNOWN', async () => {
    const value = bootHealth()
    Object.assign(value.bootReconcile, { status: 'FAILED', phase: 'FAILED', runtimeReady: false })
    Object.assign(value.bootReconcile.protocols.NFS, { configured: false, applyState: 'FAILED', probeState: 'FAILED', listenerReady: false, observationComplete: false, verified: false, completedProbeCount: 0, totalProbeCount: 0 })
    getAPI.mockResolvedValue(wire(value)); render(); await flushPromises()
    expect(wrapper.get('[role="status"]').classes()).toContain('ant-tag-red')
    expect(wrapper.text()).toContain('label.storage.boot.status.FAILED')
    expect(wrapper.text()).toContain('label.storage.boot.apply.FAILED')
  })
  it('does not invent a zero ACL exclusion count when an older wire lacks the new fields', async () => {
    const value = finished(); delete value.bootReconcile.protocols.NFS.skippedAclCount
    getAPI.mockResolvedValue(wire(value)); render(); await flushPromises()
    expect(wrapper.text()).toContain('label.storage.boot.status.UNKNOWN')
    expect(wrapper.find('table').exists()).toBe(false)
    expect(wrapper.find('.ant-tag-green').exists()).toBe(false)
  })
})

describe('#914 actual AntD product-generated v2 wire (DECLARED, not live acceptance)', () => {
  const declaredV2 = require('../../fixtures/storageBootReconcileDeclaredV2.json')
  beforeEach(() => {
    jest.useFakeTimers(); jest.spyOn(Date, 'now').mockReturnValue(declaredV2.declaredNowEpoch * 1000)
    getAPI.mockReset(); postAPI.mockReset()
  })
  afterEach(() => { if (wrapper) wrapper.unmount(); wrapper = null; jest.clearAllTimers(); jest.useRealTimers(); jest.restoreAllMocks(); document.body.innerHTML = '' })
  it.each([
    ['listenerReadyDetailedProbePending', 'blue'],
    ['allRequiredDetailedVerifiedWithPastFailure', 'green'],
    ['noConfiguredProtocolsExplicitSkipped', 'green'],
    ['allAclSkippedExactInventoryOnlyNoMounts', 'green'],
    ['unconfiguredAbsenceVerificationFailed', 'orange']
  ])('renders exact producer %s in AntD without a forged success', async (name, color) => {
    const sample = JSON.parse(JSON.stringify(declaredV2.samples[name]))
    delete sample.instanceUuid
    if (sample.lastFailure === null) delete sample.lastFailure
    else for (const key of Object.keys(sample.lastFailure)) if (sample.lastFailure[key] === null) delete sample.lastFailure[key]
    const health = { bootId: sample.bootId, generatedEpoch: declaredV2.declaredNowEpoch, staleAfterSeconds: 30, bootReconcile: sample }
    getAPI.mockResolvedValue(wire(health)); render(); await flushPromises()
    expect(wrapper.get('[role="status"]').classes()).toContain('ant-tag-' + color)
    expect(wrapper.text()).toContain('label.storage.boot.status.' + sample.status)
    expect(wrapper.text()).toContain('label.storage.boot.phase.' + sample.phase)
    expect(wrapper.find('table').exists()).toBe(true)
    if (name === 'allAclSkippedExactInventoryOnlyNoMounts') {
      const nfs = wrapper.findAll('tr').find(row => row.text().includes('NFS'))
      expect(nfs.text()).toContain('0 / 0')
      expect(nfs.text()).toContain('EXACT_INVENTORY_AND_ELIGIBLE_MOUNTS')
      expect(nfs.text()).not.toContain('MOUNT_PROBES_COMPLETE')
      expect(wrapper.text()).toContain('message.storage.boot.acl.skipped')
    }
    if (name === 'allRequiredDetailedVerifiedWithPastFailure') {
      expect(wrapper.text()).toContain('label.storage.boot.last.failure')
      expect(wrapper.text()).toContain('NFS_PROBE_TIMEOUT')
    }
    if (name === 'unconfiguredAbsenceVerificationFailed') {
      expect(wrapper.text()).toContain('label.storage.boot.apply.FAILED')
      expect(wrapper.find('.ant-tag-green').exists()).toBe(false)
    }
    expect(postAPI).not.toHaveBeenCalled()
  })
})
