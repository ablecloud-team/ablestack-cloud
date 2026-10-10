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

import { shallowMount, flushPromises } from '@vue/test-utils'
import Selection from '@/views/compute/wizard/CreationSourceSelection.vue'
import Operations from '@/views/compute/wizard/CreationSourceOperations.vue'
import { getAPI, postAPI } from '@/api'
jest.mock('@/api', () => ({ getAPI: jest.fn(), postAPI: jest.fn() }))
const source = (id = 'source-uuid', allowed = true) => ({ id, name: id, allowed, revision: 'revision-1', sourcekind: 'snapshot', reasoncodes: [] })
const mountSelection = (props = {}) => shallowMount(Selection, {
  props: { imageType: 'snapshotid', query: { zoneid: 'zone', projectid: 'project' }, ...props },
  global: { mocks: { $t: key => key } }
})
beforeEach(() => { jest.clearAllMocks(); sessionStorage.clear() })
test('zero results with an omitted array renders empty without an exception', async () => {
  getAPI.mockResolvedValue({ listvirtualmachinecreationsourcesresponse: { count: 0 } })
  const wrapper = mountSelection(); await flushPromises()
  expect(wrapper.vm.sources).toEqual([]); expect(wrapper.vm.error).toBe(''); expect(wrapper.vm.count).toBe(0)
  wrapper.unmount()
})
test('server page and count are preserved; page two never slices or duplicates candidates', async () => {
  const rows = Array.from({ length: 10 }, (_, i) => source('source-' + (i + 10)))
  getAPI.mockResolvedValue({ listvirtualmachinecreationsourcesresponse: { count: 25, creationsource: rows } })
  const wrapper = mountSelection(); await flushPromises(); wrapper.vm.changePage({ current: 2, pageSize: 10 }); await flushPromises()
  expect(getAPI).toHaveBeenLastCalledWith('listVirtualMachineCreationSources', expect.objectContaining({ page: 2, pagesize: 10, projectid: 'project' }))
  expect(wrapper.vm.sources).toEqual(rows); expect(wrapper.vm.pagination.total).toBe(25)
  wrapper.unmount()
})
test('snapshot deep link is sent to the server and auto selects only an eligible response', async () => {
  getAPI.mockResolvedValue({ listvirtualmachinecreationsourcesresponse: { count: 1, creationsource: [source()] } })
  const wrapper = mountSelection({ preselectedId: 'source-uuid' }); await flushPromises()
  expect(getAPI).toHaveBeenLastCalledWith('listVirtualMachineCreationSources', expect.objectContaining({ id: 'source-uuid', sourcekind: 'snapshot' }))
  expect(wrapper.emitted('select')[wrapper.emitted('select').length - 1][0].id).toBe('source-uuid'); wrapper.unmount()
})
test('a blocked row is disabled and a changed selected source is cleared', async () => {
  getAPI.mockResolvedValue({ listvirtualmachinecreationsourcesresponse: { count: 1, creationsource: [source('source-uuid', false)] } })
  const wrapper = mountSelection({ selected: source() }); await flushPromises()
  expect(wrapper.vm.rowSelection.getCheckboxProps(wrapper.vm.sources[0]).disabled).toBe(true)
  expect(wrapper.emitted('select')[wrapper.emitted('select').length - 1][0]).toBe(null); wrapper.unmount()
})
test('a delayed old zone response cannot overwrite the latest zone', async () => {
  let resolveOld
  getAPI.mockImplementationOnce(() => new Promise(resolve => { resolveOld = resolve }))
  getAPI.mockResolvedValue({ listvirtualmachinecreationsourcesresponse: { count: 1, creationsource: [source('new')] } })
  const wrapper = mountSelection(); await wrapper.setProps({ query: { zoneid: 'new-zone' } }); await flushPromises()
  resolveOld({ listvirtualmachinecreationsourcesresponse: { count: 1, creationsource: [source('old')] } }); await flushPromises()
  expect(wrapper.vm.sources[0].id).toBe('new'); wrapper.unmount()
})
test('API failure clears sources and eligibility rather than leaving an enabled old selection', async () => {
  getAPI.mockRejectedValue(new Error('offline'))
  const wrapper = mountSelection({ selected: source() }); await flushPromises()
  expect(wrapper.vm.sources).toEqual([]); expect(wrapper.vm.error).toBeTruthy(); expect(wrapper.emitted('select')[wrapper.emitted('select').length - 1][0]).toBe(null)
  wrapper.unmount()
})
const mountOperations = operations => shallowMount(Operations, { props: { operations, storageKey: 'source-jobs' }, global: { mocks: { $t: key => key } } })
test('unconfirmed submit survives navigation and never automatically reposts deploy', async () => {
  sessionStorage.setItem('source-jobs', JSON.stringify([{ status: 'submitting', created: 'run-1' }]))
  const wrapper = mountOperations([]); await flushPromises()
  expect(wrapper.emitted('update:operations')[0][0][0].status).toBe('unknown'); expect(postAPI).not.toHaveBeenCalled()
  wrapper.unmount()
})
test('rechecking a completed job records the VM and never calls deploy again', async () => {
  getAPI.mockImplementation(async command => command === 'queryAsyncJobResult' ? { queryasyncjobresultresponse: { jobstatus: 1, jobresult: { virtualmachine: { id: 'new-vm' } } } } : command === 'listVirtualMachines' ? { listvirtualmachinesresponse: {} } : { listvolumesresponse: {} })
  const operations = [{ jobid: 'job', status: 'pending', created: 'run-2' }]
  const wrapper = mountOperations(operations); await wrapper.vm.check(operations[0])
  expect(operations[0]).toMatchObject({ vmid: 'new-vm', status: 'complete' }); expect(postAPI).not.toHaveBeenCalled(); wrapper.unmount()
})
test('start retry requires a stopped VM and exactly one Ready ROOT disk', async () => {
  getAPI.mockImplementation(async command => command === 'listVirtualMachines' ? { listvirtualmachinesresponse: { virtualmachine: [{ state: 'Running' }] } } : { listvolumesresponse: { volume: [{ state: 'Ready' }] } })
  const operation = { status: 'failed', vmid: 'vm', created: 'run-3' }; const wrapper = mountOperations([operation])
  await wrapper.vm.retryStart(operation); expect(postAPI).not.toHaveBeenCalled(); expect(operation.retryable).toBe(false); wrapper.unmount()
})

test('lost deploy response reconciles by source UUID, VM name and creation time without reposting', async () => {
  const operation = { sourceid: 'source-uuid', name: 'unique-fixture', status: 'unknown', created: '2026-10-09T01:00:00Z' }
  getAPI.mockImplementation(async command => {
    if (command === 'listVirtualMachines') {
      return {
        listvirtualmachinesresponse: {
          virtualmachine: [
            { id: 'vm', name: 'unique-fixture', created: '2026-10-09T01:00:01Z', details: { 'vm.creation.source.id': 'source-uuid' } },
            { id: 'other', name: 'unique-fixture', created: '2026-10-09T01:00:01Z', details: { 'vm.creation.source.id': 'other-source' } }
          ]
        }
      }
    }
    if (command === 'listVolumes') return { listvolumesresponse: {} }
    if (command === 'listAsyncJobs') return { listasyncjobsresponse: { asyncjobs: [{ jobid: 'job', jobinstanceid: 'vm', cmd: 'org.apache.cloudstack.api.command.user.vm.DeployVMCmd', created: '2026-10-09T01:00:01Z' }] } }
    return { queryasyncjobresultresponse: { jobstatus: 1, jobresult: { virtualmachine: { id: 'vm' } } } }
  })
  const wrapper = mountOperations([operation]); await wrapper.vm.check(operation)
  expect(operation).toMatchObject({ vmid: 'vm', jobid: 'job', status: 'complete' }); expect(postAPI).not.toHaveBeenCalled(); wrapper.unmount()
})
test('same-name unrelated VM cannot resolve an unknown source request', async () => {
  const operation = { sourceid: 'source-uuid', name: 'unique-fixture', status: 'unknown', created: '2026-10-09T01:00:00Z' }
  getAPI.mockResolvedValue({ listvirtualmachinesresponse: { virtualmachine: [{ id: 'unrelated', name: 'unique-fixture', created: '2026-10-09T01:00:01Z', details: { 'vm.creation.source.id': 'other-source' } }] } })
  const wrapper = mountOperations([operation]); await wrapper.vm.check(operation)
  expect(operation.vmid).toBeUndefined(); expect(operation.status).toBe('unknown'); expect(postAPI).not.toHaveBeenCalled(); wrapper.unmount()
})

test('unavailable server command displays a localized version and permission action', async () => {
  getAPI.mockRejectedValue({ response: { data: { errorresponse: { errortext: 'Unknown API command: listVirtualMachineCreationSources' } } } })
  const wrapper = mountSelection(); await flushPromises()
  expect(wrapper.vm.error).toBe('message.creation.source.api.required')
  expect(wrapper.vm.sources).toEqual([]); wrapper.unmount()
})

test('known source states translate without an injected translation-existence helper', async () => {
  getAPI.mockResolvedValue({ listvirtualmachinecreationsourcesresponse: { count: 0 } })
  const translated = { 'label.creation.source.state.Ready': '사용 가능', 'label.creation.source.state.BackedUp': '백업 됨', 'label.creation.source.state.unknown': '확인 필요' }
  const wrapper = shallowMount(Selection, { props: { imageType: 'volumeid', query: { zoneid: 'zone' } }, global: { mocks: { $t: key => translated[key] || key } } })
  await flushPromises()
  expect(wrapper.vm.stateLabel('Ready')).toBe('사용 가능')
  expect(wrapper.vm.stateLabel('BackedUp')).toBe('백업 됨')
  expect(wrapper.vm.stateLabel('Unexpected')).toBe('확인 필요')
  wrapper.unmount()
})

test('removing the owner clears candidates and ignores a late prior-owner response', async () => {
  let resolveOld
  getAPI.mockImplementationOnce(() => new Promise(resolve => { resolveOld = resolve }))
  const wrapper = mountSelection({ preselectedId: 'source-uuid' })
  await wrapper.setProps({ query: { zoneid: undefined, projectid: undefined }, ownerReady: false })
  await flushPromises()
  expect(wrapper.vm.sources).toEqual([])
  expect(wrapper.vm.count).toBe(0)
  expect(wrapper.vm.loading).toBe(false)
  expect(getAPI).toHaveBeenCalledTimes(1)
  resolveOld({ listvirtualmachinecreationsourcesresponse: { count: 1, creationsource: [source()] } })
  await flushPromises()
  expect(wrapper.vm.sources).toEqual([])
  expect(wrapper.emitted('select')[wrapper.emitted('select').length - 1][0]).toBe(null)
  wrapper.unmount()
})

test('a management transport failure makes an existing source job uncertain without submitting another VM', async () => {
  const op = { jobid: 'existing-job', status: 'pending' }
  const vm = { checking: false, alive: true, $t: key => key, save: jest.fn(), schedule: jest.fn() }
  getAPI.mockRejectedValue(new Error('management unavailable'))
  await Operations.methods.check.call(vm, op)
  expect(op.status).toBe('unknown')
  expect(op.jobid).toBe('existing-job')
  expect(getAPI).toHaveBeenCalledWith('queryAsyncJobResult', { jobid: 'existing-job' }, { backgroundJob: true, timeout: 15000 })
  expect(vm.save).toHaveBeenCalled()
  expect(postAPI).not.toHaveBeenCalled()
})

test('response-loss recovery bounds its lookup and leaves unmatched operations uncertain', async () => {
  const op = { name: 'pending', sourceid: 'snapshot', created: new Date().toISOString() }
  getAPI.mockResolvedValue({ listvirtualmachinesresponse: {} })
  await Operations.methods.recover.call({}, op)
  expect(getAPI).toHaveBeenCalledWith('listVirtualMachines', { keyword: 'pending', details: 'all' }, { backgroundJob: true, timeout: 15000 })
  expect(op.jobid).toBeUndefined()
  expect(postAPI).not.toHaveBeenCalled()
})

const recoveredOperation = () => ({ sourceid: 'source-uuid', sourcekind: 'snapshot', name: 'unique-fixture', requiredDataDisks: 1, startvm: false, status: 'unknown', created: '2026-10-09T01:00:00Z' })
const restoredVm = () => ({ id: 'vm', name: 'unique-fixture', state: 'Stopped', created: '2026-10-09T01:00:01Z', details: { 'vm.creation.source.id': 'source-uuid', 'vm.creation.source.kind': 'snapshot' } })
const restoredDisks = () => [{ id: 'new-root', type: 'ROOT', deviceid: 0, state: 'Ready', virtualmachineid: 'vm' }, { id: 'data', type: 'DATADISK', deviceid: 1, state: 'Ready', virtualmachineid: 'vm' }]
const mockFinishedRestore = (vm, volumes) => getAPI.mockImplementation(async command => command === 'listVirtualMachines' ? { listvirtualmachinesresponse: { virtualmachine: [vm] } } : command === 'listAsyncJobs' ? { listasyncjobsresponse: {} } : { listvolumesresponse: { volume: volumes } })
test('response loss after a job leaves the pending list reconciles only matching VM and all Ready disks', async () => {
  const operation = recoveredOperation(); mockFinishedRestore(restoredVm(), restoredDisks())
  const wrapper = mountOperations([operation]); await wrapper.vm.check(operation)
  expect(operation).toMatchObject({ vmid: 'vm', rootid: 'new-root', status: 'complete', reconciled: true, retryable: false })
  expect(operation.jobid).toBeUndefined(); expect(postAPI).not.toHaveBeenCalled(); wrapper.unmount()
})
test.each(['missing-data', 'allocated-root', 'other-vm-root', 'old-operation-without-disk-count', 'start-not-running'])('incomplete or uncertain readback remains blocked: %s', async reason => {
  const operation = recoveredOperation(); const volumes = restoredDisks()
  if (reason === 'missing-data') volumes.pop()
  if (reason === 'allocated-root') volumes[0].state = 'Allocated'
  if (reason === 'other-vm-root') volumes[0].virtualmachineid = 'other-vm'
  if (reason === 'old-operation-without-disk-count') delete operation.requiredDataDisks
  if (reason === 'start-not-running') operation.startvm = true
  mockFinishedRestore(restoredVm(), volumes)
  const wrapper = mountOperations([operation]); await wrapper.vm.check(operation)
  expect(operation.status).toBe('unknown'); expect(operation.reconciled).toBeUndefined(); expect(postAPI).not.toHaveBeenCalled(); wrapper.unmount()
})
test('retry refuses a stopped Ready ROOT that belongs to a different source', async () => {
  const operation = { ...recoveredOperation(), vmid: 'vm', status: 'failed' }; const vm = restoredVm(); vm.details['vm.creation.source.id'] = 'other-source'
  mockFinishedRestore(vm, restoredDisks())
  const wrapper = mountOperations([operation]); await wrapper.vm.retryStart(operation)
  expect(operation.retryable).toBe(false); expect(postAPI).not.toHaveBeenCalled(); wrapper.unmount()
})

const mockFailedPartialStart = status => getAPI.mockImplementation(async command => command === 'listVirtualMachines' ? { listvirtualmachinesresponse: { virtualmachine: [restoredVm()] } } : command === 'queryAsyncJobResult' ? { queryasyncjobresultresponse: { jobstatus: status } } : { listvolumesresponse: { volume: restoredDisks().map(v => v.type === 'DATADISK' ? { ...v, state: 'Allocated' } : v) } })
test('confirmed failed start retries the same VM and preserves the Ready ROOT with its Allocated data disk', async () => {
  const operation = { ...recoveredOperation(), vmid: 'vm', jobid: 'failed-job', status: 'failed', startvm: true }; mockFailedPartialStart(2)
  postAPI.mockResolvedValue({ startvirtualmachineresponse: { jobid: 'retry-job' } })
  const wrapper = mountOperations([operation]); await wrapper.vm.retryStart(operation)
  expect(postAPI).toHaveBeenCalledTimes(1); expect(postAPI).toHaveBeenCalledWith('startVirtualMachine', { id: 'vm' }, { preserveOnFailure: true })
  expect(operation).toMatchObject({ jobid: 'retry-job', rootid: 'new-root', status: 'pending', pendingCommand: 'startVirtualMachine' }); wrapper.unmount()
})
test('a start retry is blocked if its supposedly failed job is still pending', async () => {
  const operation = { ...recoveredOperation(), vmid: 'vm', jobid: 'pending-job', status: 'failed' }; mockFailedPartialStart(0)
  const wrapper = mountOperations([operation]); await wrapper.vm.retryStart(operation)
  expect(postAPI).not.toHaveBeenCalled(); wrapper.unmount()
})
test('a lost retry response clears the old failed job and recovers the matching start job without resubmitting', async () => {
  const operation = { ...recoveredOperation(), vmid: 'vm', jobid: 'failed-job', status: 'failed', startvm: true }; mockFailedPartialStart(2)
  postAPI.mockRejectedValue(new Error('Network Error'))
  const wrapper = mountOperations([operation]); await wrapper.vm.retryStart(operation)
  expect(operation).toMatchObject({ status: 'unknown', jobid: null, retryable: false, pendingCommand: 'startVirtualMachine' })
  getAPI.mockImplementation(async command => command === 'listVirtualMachines' ? { listvirtualmachinesresponse: { virtualmachine: [restoredVm()] } } : command === 'listAsyncJobs' ? { listasyncjobsresponse: { asyncjobs: [{ jobid: 'retry-job', jobinstanceid: 'vm', cmd: 'org.apache.cloudstack.api.command.user.vm.StartVMCmd', created: operation.retryCreated }] } } : { queryasyncjobresultresponse: { jobstatus: 0 } })
  await wrapper.vm.check(operation)
  expect(operation).toMatchObject({ status: 'pending', jobid: 'retry-job' }); expect(postAPI).toHaveBeenCalledTimes(1); wrapper.unmount()
})

test('project response loss recovers within the requested project and reads its ROOT without a global project selection', async () => {
  const operation = { ...recoveredOperation(), projectid: 'project' }
  mockFinishedRestore({ ...restoredVm(), projectid: 'project' }, restoredDisks())
  const wrapper = mountOperations([operation]); await wrapper.vm.check(operation)
  expect(getAPI).toHaveBeenCalledWith('listVirtualMachines', expect.objectContaining({ keyword: operation.name, projectid: 'project' }), expect.any(Object))
  expect(getAPI).toHaveBeenCalledWith('listVolumes', { virtualmachineid: 'vm', projectid: 'project' }, expect.any(Object))
  expect(operation).toMatchObject({ rootid: 'new-root', status: 'complete', reconciled: true })
  expect(postAPI).not.toHaveBeenCalled(); wrapper.unmount()
})

test('older project operation records discover the VM project before reading ROOT', async () => {
  const operation = { ...recoveredOperation(), vmid: 'vm' }
  mockFinishedRestore({ ...restoredVm(), projectid: 'project' }, restoredDisks())
  const wrapper = mountOperations([operation]); await wrapper.vm.inspectVm(operation)
  expect(getAPI).toHaveBeenCalledWith('listVolumes', { virtualmachineid: 'vm', projectid: 'project' }, expect.any(Object))
  expect(operation).toMatchObject({ projectid: 'project', rootid: 'new-root' }); wrapper.unmount()
})
