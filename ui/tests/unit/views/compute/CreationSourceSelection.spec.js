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
  expect(wrapper.emitted('select').at(-1)[0].id).toBe('source-uuid'); wrapper.unmount()
})
test('a blocked row is disabled and a changed selected source is cleared', async () => {
  getAPI.mockResolvedValue({ listvirtualmachinecreationsourcesresponse: { count: 1, creationsource: [source('source-uuid', false)] } })
  const wrapper = mountSelection({ selected: source() }); await flushPromises()
  expect(wrapper.vm.rowSelection.getCheckboxProps(wrapper.vm.sources[0]).disabled).toBe(true)
  expect(wrapper.emitted('select').at(-1)[0]).toBe(null); wrapper.unmount()
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
  expect(wrapper.vm.sources).toEqual([]); expect(wrapper.vm.error).toBeTruthy(); expect(wrapper.emitted('select').at(-1)[0]).toBe(null)
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
  getAPI.mockResolvedValue({ queryasyncjobresultresponse: { jobstatus: 1, jobresult: { virtualmachine: { id: 'new-vm' } } } })
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
