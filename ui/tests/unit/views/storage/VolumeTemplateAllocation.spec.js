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
import storage from '@/config/section/storage'
import Template from '@/views/storage/CreateTemplate.vue'
import { postAPI } from '@/api'
jest.mock('@/store', () => ({ getters: { apis: {}, userInfo: {} } }))
jest.mock('@/api', () => ({ getAPI: jest.fn(), postAPI: jest.fn() }))
const actions = storage.children.find(section => section.name === 'volume').actions
const template = actions.find(action => action.api === 'createTemplate')
const download = actions.find(action => action.api === 'extractVolume')
const volume = allocation => ({ id: 'source', type: 'ROOT', state: 'Ready', virtualmachineid: 'vm', vmstate: 'Stopped', allocation })
const explicit = { contractversion: '1', requestedpolicy: 'off', requestedformat: 'raw', evidence: 'verified', appliedmethod: 'rbd-thin' }
beforeEach(() => postAPI.mockReset())

test.each([
  ['legacy Thin', undefined],
  ['RBD Thin', explicit],
  ['RBD Full', { ...explicit, requestedpolicy: 'full' }],
  ['GFS2 metadata', { ...explicit, requestedpolicy: 'metadata', requestedformat: 'qcow2' }],
  ['GFS2 falloc', { ...explicit, requestedpolicy: 'falloc', requestedformat: 'qcow2' }],
  ['GFS2 Full', { ...explicit, requestedpolicy: 'full', requestedformat: 'qcow2' }]
])('%s remains eligible for template creation without changing allocation', (name, allocation) => {
  const record = Object.freeze(volume(allocation && Object.freeze(allocation)))
  expect(template.show(record)).toBe(true)
  expect(template.disabled(record)).toBe(false)
  expect(template.tooltip(record)).toBe('label.action.create.template.from.volume')
  expect(record.allocation).toEqual(allocation)
})

test.each(['Running', 'Starting', 'Stopping'])('attached %s VM cannot expose template creation', vmstate => {
  expect(template.show({ ...volume(explicit), vmstate })).toBe(false)
})

test('detached Ready DATA volumes remain eligible, other volume states do not', () => {
  const detached = { ...volume(explicit), type: 'DATADISK', virtualmachineid: undefined, vmstate: undefined }
  expect(template.show(detached)).toBe(true)
  for (const state of ['Allocated', 'Creating', 'Copying', 'Destroy', 'Expunging']) {
    expect(template.show({ ...detached, state })).toBe(false)
  }
})

test.each(['pending', 'running'])('active %s flatten still blocks template creation and explains why', status => {
  for (const marker of [{ clonefastflattenstatus: status }, { details: { 'clone.fast.flatten.status': status } }]) {
    const record = { ...volume(explicit), ...marker }
    expect(template.disabled(record)).toBe(true)
    expect(template.tooltip(record)).toBe('message.sharedmountpoint.clone.flatten.in.progress')
  }
})

test.each([undefined, explicit, { ...explicit, requestedpolicy: 'full' }, { ...explicit, requestedformat: 'qcow2', requestedpolicy: 'metadata' }, { ...explicit, requestedformat: 'qcow2', requestedpolicy: 'falloc' }])('download eligibility is independent of allocation %j', allocation => {
  const record = Object.freeze(volume(allocation && Object.freeze(allocation)))
  expect(download.show(record)).toBe(true)
  expect(download.disabled(record)).toBe(false)
  expect(download.tooltip(record)).toBe('label.action.download.volume')
  expect(record.allocation).toEqual(allocation)
  expect(download.mapping.mode.value(record)).toBe('HTTP_DOWNLOAD')
})

test('download retains running, non-Ready and active flatten guards', () => {
  expect(download.show({ ...volume(explicit), vmstate: 'Running' })).toBe(false)
  expect(download.show({ ...volume(explicit), state: 'Copying' })).toBe(false)
  expect(download.show({ ...volume(explicit), virtualmachineid: undefined, vmstate: undefined, type: 'DATADISK' })).toBe(true)
  for (const status of ['pending', 'running']) {
    const record = { ...volume(explicit), details: { 'clone.fast.flatten.status': status } }
    expect(download.disabled(record)).toBe(true)
    expect(download.tooltip(record)).toBe('message.sharedmountpoint.clone.flatten.in.progress')
  }
})

function context () {
  return { loading: false, queryLoading: false, lookupError: '', snapshotSafetyBlocked: false, dialogCurrent: () => true, formRef: { value: { validate: () => Promise.resolve() } }, form: { name: 'new-template', ostypeid: 'os', arch: 'x86_64' }, handleRemoveFields: value => value, resource: Object.freeze(volume(Object.freeze({ ...explicit }))), $pollJob: jest.fn(), $notifyError: jest.fn(), $t: key => key, closeModal: jest.fn() }
}

test('explicit source uses the existing template request once without forwarding or modifying allocation', async () => {
  postAPI.mockResolvedValue({ createtemplateresponse: { jobid: 'template-job' } })
  const vm = context()
  Template.methods.handleSubmit.call(vm); Template.methods.handleSubmit.call(vm)
  await flushPromises()
  expect(postAPI).toHaveBeenCalledTimes(1)
  expect(postAPI).toHaveBeenCalledWith('createTemplate', { volumeid: 'source', name: 'new-template', ostypeid: 'os', arch: 'x86_64' })
  expect(vm.resource.allocation).toEqual(explicit)
  expect(vm.$pollJob.mock.calls[0][0].jobId).toBe('template-job')
  expect(vm.closeModal).toHaveBeenCalledTimes(1)
})

test('backend rejection preserves form and source, then allows a corrected retry', async () => {
  const failure = new Error('permission/state/encryption rejected by backend')
  postAPI.mockRejectedValueOnce(failure).mockResolvedValueOnce({ createtemplateresponse: { jobid: 'retry' } })
  const vm = context()
  Template.methods.handleSubmit.call(vm); await flushPromises()
  expect(vm.$notifyError).toHaveBeenCalledWith(failure)
  expect(vm.closeModal).not.toHaveBeenCalled(); expect(vm.loading).toBe(false)
  expect(vm.form).toEqual({ name: 'new-template', ostypeid: 'os', arch: 'x86_64' })
  expect(vm.resource.allocation).toEqual(explicit)
  Template.methods.handleSubmit.call(vm); await flushPromises()
  expect(postAPI).toHaveBeenCalledTimes(2); expect(vm.closeModal).toHaveBeenCalledTimes(1)
})
