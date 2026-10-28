// Licensed to the Apache Software Foundation (ASF) under one or more
// contributor license agreements. See the NOTICE file distributed with this
// work for additional information regarding copyright ownership. The ASF
// licenses this file to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance with the License.
// You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0 .
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
// WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
// License for the specific language governing permissions and limitations.
import { flushPromises } from '@vue/test-utils'
import Clone from '@/views/compute/CloneVM'
import { getAPI } from '@/api'
jest.mock('@/api', () => ({ getAPI: jest.fn() }))
const volume = (policy = 'off', format = 'raw') => Object.freeze({ id: 'root', allocation: Object.freeze({ policyorigin: 'explicit', requestedpolicy: policy, actualformat: format }) })
function context (pooltype = 'RBD', state = 'Stopped', volumes = [volume()]) {
  const vm = { resource: { id: 'source', name: 'source', pooltype, state }, $t: k => k, $emit: jest.fn(), $notifyError: jest.fn(), $pollJob: jest.fn() }
  Object.assign(vm, Clone.data.call(vm))
  Object.entries(Clone.methods).forEach(([key, method]) => { vm[key] = method.bind(vm) })
  Object.entries(Clone.computed).forEach(([key, method]) => Object.defineProperty(vm, key, { get: () => method.call(vm) }))
  vm.initForm()
  vm.form.name = 'new-clone'
  vm.sourceVolumes = volumes
  vm.formRef = { value: { validate: jest.fn().mockResolvedValue(), scrollToField: jest.fn() } }
  return vm
}
beforeEach(() => getAPI.mockReset())

test.each(['full', 'linked'])('RBD off permits %s and forwards explicit false start without modifying source policies', async type => {
  getAPI.mockResolvedValue({ clonevirtualmachineresponse: { jobid: 'job' } })
  const vm = context()
  vm.form.clonetype = type
  vm.handleSubmit(); await flushPromises()
  expect(getAPI).toHaveBeenCalledWith('cloneVirtualMachine', { virtualmachineid: 'source', name: 'new-clone', count: 1, clonetype: type, startvm: false })
  expect(vm.sourceVolumes[0].allocation.requestedpolicy).toBe('off')
  expect(vm.$pollJob.mock.calls[0][0].jobId).toBe('job')
})
test('any Full RBD disk blocks only the conflicting Linked choice and permits Full', async () => {
  getAPI.mockResolvedValue({ clonevirtualmachineresponse: { jobid: 'full' } })
  const vm = context('RBD', 'Running', [volume(), volume('full')])
  vm.form.clonetype = 'linked'; vm.handleSubmit(); await flushPromises()
  expect(vm.cloneBlocked).toBe(true); expect(getAPI).not.toHaveBeenCalled()
  vm.form.clonetype = 'full'; vm.form.count = 2; vm.handleSubmit(); await flushPromises()
  expect(getAPI).toHaveBeenCalledTimes(1)
  expect(getAPI.mock.calls[0][1]).toMatchObject({ clonetype: 'full', count: 2 })
})
test.each(['metadata', 'falloc', 'full'])('GFS2 %s QCOW2 does not receive an RBD clone type', async policy => {
  getAPI.mockResolvedValue({ clonevirtualmachineresponse: { jobid: 'gfs' } })
  const vm = context('SharedMountPoint', 'Running', [volume(policy, 'qcow2')])
  vm.handleSubmit(); await flushPromises()
  expect(vm.cloneBlocked).toBe(false)
  expect(getAPI.mock.calls[0][1].clonetype).toBeNull()
})
test('GFS2 RAW requires Stopped while legacy QCOW2 remains available', () => {
  expect(context('SharedMountPoint', 'Running').cloneBlocked).toBe(true)
  expect(context('SharedMountPoint', 'Stopped').cloneBlocked).toBe(false)
  expect(context('SharedMountPoint', 'Running', [{ allocation: { policyorigin: 'legacy', actualformat: 'qcow2' } }]).cloneBlocked).toBe(false)
})
test('pending lookup blocks submission and retry preserves entered name', async () => {
  const vm = context()
  getAPI.mockRejectedValueOnce(new Error('lookup failed')).mockResolvedValueOnce({ listvolumesresponse: { volume: [volume()] } })
  await vm.fetchSourceVolumes()
  expect(vm.cloneBlocked).toBe(true); vm.handleSubmit(); expect(getAPI).toHaveBeenCalledTimes(1)
  await vm.fetchSourceVolumes()
  expect(vm.cloneBlocked).toBe(false); expect(vm.form.name).toBe('new-clone')
})
test('an empty source lookup fails closed', async () => {
  getAPI.mockResolvedValue({ listvolumesresponse: {} })
  const vm = context(); await vm.fetchSourceVolumes()
  expect(vm.cloneBlocked).toBe(true)
})
test('two submissions sharing asynchronous form validation create only one API request', async () => {
  getAPI.mockResolvedValue({ clonevirtualmachineresponse: { jobid: 'single' } })
  const vm = context(); vm.handleSubmit(); vm.handleSubmit(); await flushPromises()
  expect(getAPI).toHaveBeenCalledTimes(1); expect(vm.$emit).toHaveBeenCalledTimes(1)
})
test('backend rejection retains inputs and permits retry without changing allocation', async () => {
  const error = new Error('name conflict')
  getAPI.mockRejectedValueOnce(error).mockResolvedValueOnce({ clonevirtualmachineresponse: { jobid: 'retry' } })
  const vm = context(); vm.handleSubmit(); await flushPromises()
  expect(vm.$notifyError).toHaveBeenCalledWith(error); expect(vm.$emit).not.toHaveBeenCalled()
  expect(vm.loading).toBe(false); expect(vm.form.name).toBe('new-clone')
  vm.handleSubmit(); await flushPromises()
  expect(getAPI).toHaveBeenCalledTimes(2); expect(vm.sourceVolumes[0]).toEqual(volume())
})
