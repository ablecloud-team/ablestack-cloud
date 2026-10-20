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

import { shallowMount, mount, flushPromises } from '@vue/test-utils'
import Widget from '@/views/storage/StorageVolumePreparation'
import { getAPI, postAPI } from '@/api'
jest.mock('@/api', () => ({ getAPI: jest.fn(), postAPI: jest.fn() }))
const instanceUuid = '11111111-1111-4111-8111-111111111111'
const volumeUuid = '22222222-2222-4222-8222-222222222222'
const formatOwner = { instanceUuid, managerOperationUuid: '33333333-3333-4333-8333-333333333333', revision: 8 }
const formatterReceipt = {
  schemaVersion: 2,
  originalFormatOwner: formatOwner,
  formatterBootId: '44444444-4444-4444-8444-444444444444',
  formatterPid: 1234,
  formatterStartTicks: 54321,
  formatterExitCode: 0,
  volumeUuid,
  serial: '22222222222242228222',
  sizeBytes: 10995116277760,
  filesystem: 'xfs',
  filesystemUuid: '66666666-6666-4666-8666-666666666666',
  formatterOptions: ['-f', '-K'],
  formatDiscardPolicy: 'SKIP_DISCARD',
  verifiedEpoch: 1792099997
}
const completedObservation = overrides => ({
  success: true,
  status: 'RECONCILE_REQUIRED',
  operationId: 'volume-' + volumeUuid,
  formatterActive: false,
  formatterObservationComplete: true,
  bootId: '55555555-5555-4555-8555-555555555555',
  terminationPending: false,
  stale: false,
  observedEpoch: 1792100000,
  filesystemCompletionProven: true,
  resumeAllowed: true,
  operation: {
    operationId: 'volume-' + volumeUuid,
    volumeUuid,
    phase: 'FILESYSTEM_VERIFIED',
    heartbeat: 1792099998,
    started: 1792099900,
    formatDeadlineSeconds: 900,
    volumeSizeBytes: 10995116277760,
    filesystem: 'xfs',
    filesystemUuid: formatterReceipt.filesystemUuid,
    formatStarted: true,
    formatterExitCode: 0,
    terminationPending: false,
    originalFormatOwner: formatOwner,
    formatterBootId: formatterReceipt.formatterBootId,
    formatterPid: formatterReceipt.formatterPid,
    formatterStartTicks: formatterReceipt.formatterStartTicks,
    formatterOptions: formatterReceipt.formatterOptions,
    formatDiscardPolicy: formatterReceipt.formatDiscardPolicy,
    formatterSuccessReceipt: formatterReceipt,
    devicePath: '/dev/sdc'
  },
  observedOperation: { phase: 'RECONCILE_REQUIRED', elapsedSeconds: 100 },
  currentIdentityStatus: 'EXACT',
  currentIdentity: { volumeUuid, mappingStatus: 'EXACT', matchedBy: 'VOLUME_SERIAL', observedDevicePath: '/dev/sdb', filesystemUuid: formatterReceipt.filesystemUuid, serial: formatterReceipt.serial, filesystem: 'xfs', sizeBytes: 10995116277760 },
  ...overrides
})
const currentWire = observation => ({ getstorageservicevolumepreparationresponse: { storageserviceruntime: { id: instanceUuid, success: true, resultjson: JSON.stringify(observation) } } })
function functionalVm () {
  const vm = { ...Widget.data(), instanceId: instanceUuid, volumes: [{ id: volumeUuid, name: 'large-data', size: 10995116277760 }], initialVolumeId: '', active: true, selectedVolume: volumeUuid, $t: key => key, $emit: jest.fn(), $store: { getters: { apis: { getStorageServiceVolumePreparation: {}, resumeStorageServiceVolumePreparation: {}, queryAsyncJobResult: {} }, userInfo: { id: 'actor-a' }, project: { id: 'project-a' } }, state: { user: { token: 'runtime-only-test-token' } } } }
  for (const [name, method] of Object.entries(Widget.methods)) vm[name] = method.bind(vm)
  for (const [name, method] of Object.entries(Widget.computed)) Object.defineProperty(vm, name, { get: method.bind(vm), configurable: true })
  vm.observation = completedObservation()
  return vm
}
describe('#974 actual preparation contract and response-loss boundaries', () => {
  beforeEach(() => { getAPI.mockReset(); postAPI.mockReset() })
  it('uses the server completion and resume decision rather than a partial local receipt', () => {
    const vm = functionalVm()
    expect(vm.canResume).toBe(true)
    vm.observation.resumeAllowed = false
    expect(vm.canResume).toBe(false)
    vm.observation.resumeAllowed = true; vm.observation.filesystemCompletionProven = false
    expect(vm.canResume).toBe(false)
  })
  it('accepts the real server schema2 completion decision after a BOOT change', () => {
    const vm = functionalVm()
    expect(vm.observation.operation.formatterSuccessReceipt.schemaVersion).toBe(2)
    expect(vm.observation.operation.formatterBootId).not.toBe(vm.observation.bootId)
    expect(vm.canResume).toBe(true)
  })
  it.each([false, undefined, 'true'])('fails closed with formatterObservationComplete=%p', complete => {
    const vm = functionalVm(); vm.observation.formatterObservationComplete = complete
    expect(vm.canResume).toBe(false)
  })
  it('retracks a known job after a real read rejection without another mutation POST', async () => {
    const vm = functionalVm(); vm.openResume()
    postAPI.mockResolvedValue({ resumestorageservicevolumepreparationresponse: { jobid: 'known-job' } })
    let lost = true
    getAPI.mockImplementation(command => {
      if (command !== 'queryAsyncJobResult') return Promise.resolve(currentWire(completedObservation()))
      if (lost) { lost = false; return Promise.reject(new Error('transport lost')) }
      return Promise.resolve({ queryasyncjobresultresponse: { jobstatus: 1 } })
    })
    await vm.resume()
    expect(vm.resumeVisible).toBe(true); expect(vm.resumeJob).toBe('known-job')
    await vm.resume()
    expect(postAPI).toHaveBeenCalledTimes(1)
    expect(getAPI.mock.calls.filter(([command]) => command === 'queryAsyncJobResult').map(([, params]) => params.jobid)).toEqual(['known-job', 'known-job'])
    expect(vm.$emit).toHaveBeenCalledWith('operation-updated', instanceUuid)
  })
  it('keeps a missing job response unknown with the original modal and no completion event', async () => {
    const vm = functionalVm(); vm.openResume()
    postAPI.mockResolvedValue({ resumestorageservicevolumepreparationresponse: {} })
    await vm.resume()
    expect(vm.resumeVisible).toBe(true)
    expect(vm.resumeError).toBe('message.storage.volume.resume.unknown')
    expect(vm.$emit).not.toHaveBeenCalled()
    expect(getAPI).not.toHaveBeenCalled()
  })
  it('preserves the exact key and scope after an unknown POST and only retries explicitly', async () => {
    const vm = functionalVm(); vm.openResume()
    postAPI.mockRejectedValueOnce(new Error('POST response lost')).mockResolvedValue({ resumestorageservicevolumepreparationresponse: { jobid: 'real-job' } })
    getAPI.mockImplementation(command => Promise.resolve(command === 'queryAsyncJobResult' ? { queryasyncjobresultresponse: { jobstatus: 1 } } : currentWire(completedObservation())))
    await vm.resume()
    expect(postAPI).toHaveBeenCalledTimes(1)
    const original = { ...postAPI.mock.calls[0][1] }
    await vm.resume()
    expect(postAPI).toHaveBeenCalledTimes(2)
    expect(postAPI.mock.calls[1][1]).toEqual(original)
    expect(postAPI.mock.calls[0][2]).toMatchObject({ preserveOnFailure: true })
  })
  it('does not resend a definitively failed job and directs the operator to recovery', async () => {
    const vm = functionalVm(); vm.openResume()
    postAPI.mockResolvedValue({ resumestorageservicevolumepreparationresponse: { jobid: 'failed-job' } })
    getAPI.mockResolvedValue({ queryasyncjobresultresponse: { jobstatus: 2, jobresult: { errortext: 'Traceback: sensitive internal diagnostics' } } })
    await vm.resume(); await vm.resume()
    expect(postAPI).toHaveBeenCalledTimes(1)
    expect(vm.resumeError).not.toContain('Traceback')
    expect(vm.resumeVisible).toBe(true)
    expect(vm.$emit).not.toHaveBeenCalled()
  })
  it('ignores a delayed observation after actor changes within the same resource', async () => {
    const vm = functionalVm(); vm.observation = null
    let done; getAPI.mockImplementation(() => new Promise(resolve => { done = resolve }))
    const pending = vm.refresh()
    vm.$store.getters.userInfo.id = 'actor-b'
    done(currentWire(completedObservation())); await pending
    expect(vm.observation).toBeNull()
  })
  it('does not query a preparation widget in a hidden parent tab', async () => {
    const vm = functionalVm(); vm.active = false
    vm.openResume(); expect(vm.resumeVisible).toBe(false)
    await vm.refresh()
    expect(getAPI).not.toHaveBeenCalled()
  })
  it('renders volume, filesystem and heartbeat with real AntD while raw traceback stays folded', async () => {
    getAPI.mockResolvedValue(currentWire(completedObservation({ diagnostic: 'Traceback: formatter details', errorCode: 'FORMATTER_COMPLETION_OR_CURRENT_IDENTITY_UNVERIFIED' })))
    const vm = functionalVm()
    const wrapper = mount(Widget, { props: { instanceId: instanceUuid, volumes: vm.volumes, active: true }, global: { mocks: { $t: key => key, $store: vm.$store } }, attachTo: document.body })
    await wrapper.vm.selectVolume(volumeUuid); await flushPromises()
    expect(wrapper.text()).toContain('large-data')
    expect(wrapper.text()).toContain('xfs')
    expect(wrapper.text()).toContain(new Date(1792099998 * 1000).toISOString())
    expect(wrapper.text()).toContain(new Date((1792099900 + 900) * 1000).toISOString())
    expect(wrapper.text()).toContain('FORMATTER_COMPLETION_OR_CURRENT_IDENTITY_UNVERIFIED')
    expect(wrapper.findAll('.ant-alert-message').length).toBeGreaterThan(0)
    expect(wrapper.findAll('.ant-alert-message').map(alert => alert.text()).join(' ')).not.toContain('Traceback')
    expect(wrapper.find('details[open]').exists()).toBe(false)
    wrapper.unmount()
  })
})

describe('#974 scoped readonly observation', () => {
  beforeEach(() => { getAPI.mockReset(); postAPI.mockReset() })
  afterEach(() => { jest.useRealTimers(); document.body.innerHTML = '' })
  it('renders an empty initial state without touching diagnostics or API', () => {
    const wrapper = shallowMount(Widget, { props: { instanceId: instanceUuid, volumes: [] }, global: { mocks: { $t: key => key, $store: functionalVm().$store } } })
    expect(wrapper.find('.storage-volume-preparation').exists()).toBe(true)
    expect(getAPI).not.toHaveBeenCalled(); wrapper.unmount()
  })
  it.each([true, undefined, 'false'])('never resumes with formatterActive=%p', active => {
    const vm = functionalVm(); vm.observation.formatterActive = active
    expect(vm.canResume).toBe(false)
  })
  it('keeps current filesystem and device independent of the historical journal', async () => {
    const vm = functionalVm()
    getAPI.mockResolvedValue(currentWire(completedObservation()))
    await vm.refresh()
    expect(vm.identity.observedDevicePath).toBe('/dev/sdb')
    expect(vm.operation.devicePath).toBe('/dev/sdc')
    vm.observation.currentIdentity = { volumeUuid }
    expect(vm.identity.filesystemUuid).toBeUndefined()
    expect(vm.operation.filesystemUuid).toBe(formatterReceipt.filesystemUuid)
  })
  it('preserves the previous observation and fails closed after observation transport failure', async () => {
    const vm = functionalVm(); getAPI.mockRejectedValue(new Error('timeout'))
    const before = vm.observation
    await vm.refresh()
    expect(vm.observation).toBe(before); expect(vm.readError).toBe(true)
    expect(vm.canResume).toBe(false); expect(postAPI).not.toHaveBeenCalled()
  })
  it('does not overlap reads', async () => {
    const vm = functionalVm(); let done
    getAPI.mockImplementation(() => new Promise(resolve => { done = resolve }))
    const pending = vm.refresh(); await vm.refresh()
    expect(getAPI).toHaveBeenCalledTimes(1)
    done(currentWire(completedObservation())); await pending
    expect(vm.loading).toBe(false)
  })
  it.each(['instance', 'volume', 'project', 'token', 'permission', 'unmount'])('drops delayed old %s observation', async boundary => {
    const vm = functionalVm(); vm.observation = null; let done
    getAPI.mockImplementation(() => new Promise(resolve => { done = resolve }))
    const pending = vm.refresh()
    if (boundary === 'instance') { vm.invalidate(); vm.instanceId = 'other-instance' }
    if (boundary === 'volume') { vm.invalidate(); vm.selectedVolume = 'other-volume' }
    if (boundary === 'project') vm.$store.getters.project.id = 'other-project'
    if (boundary === 'token') vm.$store.state.user.token = ''
    if (boundary === 'permission') delete vm.$store.getters.apis.getStorageServiceVolumePreparation
    if (boundary === 'unmount') Widget.beforeUnmount.call(vm)
    done(currentWire(completedObservation())); await pending
    expect(vm.observation).toBeNull()
  })
  it('does not let an old poll finally unlock a new instance modal', async () => {
    const vm = functionalVm(); vm.openResume(); let done
    postAPI.mockResolvedValue({ resumestorageservicevolumepreparationresponse: { jobid: 'old-job' } })
    getAPI.mockImplementation(() => new Promise(resolve => { done = resolve }))
    const pending = vm.resume(); await flushPromises()
    vm.invalidate(); vm.instanceId = 'other-instance'; vm.resuming = true; vm.resumeVisible = true
    done({ queryasyncjobresultresponse: { jobstatus: 1 } }); await pending
    expect(vm.resuming).toBe(true); expect(vm.resumeVisible).toBe(true)
    expect(vm.$emit).not.toHaveBeenCalled()
  })
  it('retains known-job tracking across cancel and reopen but never borrows the old modal epoch', async () => {
    const vm = functionalVm(); vm.openResume()
    postAPI.mockResolvedValue({ resumestorageservicevolumepreparationresponse: { jobid: 'known-job' } })
    getAPI.mockRejectedValueOnce(new Error('READ lost'))
    await vm.resume(); vm.closeResume(); vm.openResume()
    getAPI.mockImplementation(command => Promise.resolve(command === 'queryAsyncJobResult' ? { queryasyncjobresultresponse: { jobstatus: 1 } } : currentWire(completedObservation())))
    await vm.resume()
    expect(postAPI).toHaveBeenCalledTimes(1)
    expect(vm.resumeVisible).toBe(false); expect(vm.resumeCompleted).toBe(true)
  })
})

describe('#974 active polling and retained resume intent', () => {
  let wrapper
  beforeEach(() => { getAPI.mockReset(); postAPI.mockReset(); jest.useFakeTimers() })
  afterEach(() => { if (wrapper) wrapper.unmount(); wrapper = null; jest.clearAllTimers(); jest.useRealTimers(); document.body.innerHTML = '' })
  const mountCurrent = props => {
    const vm = functionalVm()
    wrapper = mount(Widget, { props: { instanceId: instanceUuid, volumes: vm.volumes, active: true, ...props }, global: { mocks: { $t: key => key, $store: vm.$store } }, attachTo: document.body })
    Object.defineProperty(wrapper.element, 'getClientRects', { value: () => [{}] })
    return wrapper
  }
  it('observes an active formatter only with bounded reads and pauses when the parent tab hides', async () => {
    getAPI.mockResolvedValue(currentWire(completedObservation({ status: 'FORMATTING', formatterActive: true, resumeAllowed: false })))
    mountCurrent()
    await flushPromises()
    expect(getAPI).toHaveBeenCalledTimes(1)
    jest.advanceTimersByTime(5000); await flushPromises()
    expect(getAPI).toHaveBeenCalledTimes(2)
    expect(wrapper.vm.canResume).toBe(false)
    await wrapper.setProps({ active: false })
    jest.advanceTimersByTime(15000); await flushPromises()
    expect(getAPI).toHaveBeenCalledTimes(2); expect(postAPI).not.toHaveBeenCalled()
    await wrapper.setProps({ active: true }); await flushPromises()
    expect(getAPI).toHaveBeenCalledTimes(3)
    getAPI.mockResolvedValue(currentWire(completedObservation({ status: 'COMPLETE' })))
    await wrapper.vm.refresh(); const finishedReads = getAPI.mock.calls.length
    jest.advanceTimersByTime(15000); await flushPromises()
    expect(getAPI).toHaveBeenCalledTimes(finishedReads)
  })
  it('stops polling after a failed read until explicit update succeeds', async () => {
    getAPI.mockRejectedValueOnce(new Error('network read failed'))
    mountCurrent(); await flushPromises()
    expect(wrapper.vm.readError).toBe(true)
    jest.advanceTimersByTime(15000); await flushPromises()
    expect(getAPI).toHaveBeenCalledTimes(1)
    getAPI.mockResolvedValue(currentWire(completedObservation({ status: 'FORMATTING', formatterActive: true, resumeAllowed: false })))
    await wrapper.vm.refresh()
    expect(wrapper.vm.readError).toBe(false)
    jest.advanceTimersByTime(5000); await flushPromises()
    expect(getAPI).toHaveBeenCalledTimes(3)
  })
  it('opens the exact query-selected volume and never queries an unowned initial volume', async () => {
    getAPI.mockResolvedValue(currentWire(completedObservation()))
    mountCurrent({ initialVolumeId: volumeUuid, volumes: [{ id: 'other-volume', name: 'other' }, ...functionalVm().volumes] })
    await flushPromises()
    expect(wrapper.vm.selectedVolume).toBe(volumeUuid)
    expect(getAPI.mock.calls[0][1].volumeid).toBe(volumeUuid)
    wrapper.unmount(); wrapper = null; getAPI.mockClear()
    mountCurrent({ initialVolumeId: 'foreign-volume' }); await flushPromises()
    expect(getAPI).not.toHaveBeenCalled()
    expect(wrapper.vm.selectedVolume).toBe('')
  })
  it('never sends a different filesystem intent under an uncertain request key', async () => {
    const vm = functionalVm(); vm.openResume()
    postAPI.mockRejectedValue(new Error('response lost'))
    await vm.resume()
    vm.observation.currentIdentity.filesystemUuid = 'different-filesystem'
    vm.observation.operation.filesystemUuid = 'different-filesystem'
    await vm.resume()
    expect(postAPI).toHaveBeenCalledTimes(1)
    expect(vm.canSubmitResume).toBe(false)
  })
  it.each(['token', 'readPermission'])('immediately prevents a new mutation when %s disappears', async boundary => {
    const vm = functionalVm(); vm.openResume()
    if (boundary === 'token') vm.$store.state.user.token = ''
    else delete vm.$store.getters.apis.getStorageServiceVolumePreparation
    await vm.resume()
    expect(postAPI).not.toHaveBeenCalled()
  })
})

describe('#974 semantic states in the existing themes', () => {
  it.each(['light', 'dark'])('keeps recovery state and guidance visible in %s mode', async theme => {
    document.body.classList.toggle('dark-mode', theme === 'dark')
    const vm = functionalVm()
    getAPI.mockResolvedValue(currentWire(completedObservation({ status: 'RECOVERY_REQUIRED', resumeAllowed: false, filesystemCompletionProven: false })))
    const wrapper = mount(Widget, { props: { instanceId: instanceUuid, volumes: vm.volumes }, global: { mocks: { $t: key => key, $store: vm.$store } }, attachTo: document.body })
    await flushPromises()
    expect(wrapper.find('.ant-tag-red').text()).toBe('label.storage.volume.phase.RECOVERY_REQUIRED')
    expect(wrapper.find('.ant-alert-warning').text()).toContain('message.storage.volume.preparation.recovery')
    expect(wrapper.vm.canResume).toBe(false)
    wrapper.unmount(); document.body.classList.remove('dark-mode')
  })
})
