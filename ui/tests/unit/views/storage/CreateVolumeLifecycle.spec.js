// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements. See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership. The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License. You may obtain a copy of the License at
// http://www.apache.org/licenses/LICENSE-2.0
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied. See the License for the
// specific language governing permissions and limitations
// under the License.

import '../../utils/cryptoRandom'
import CreateVolume from '@/views/storage/CreateVolume.vue'
import { getAPI, postAPI } from '@/api'
import { clearStorageSnapshotOperations } from '@/utils/storageSnapshotOperations'
import { mixinForm } from '@/utils/mixin'
jest.mock('@/api', () => ({ getAPI: jest.fn(), postAPI: jest.fn() }))
jest.mock('@/store', () => ({ getters: { userInfo: { account: 'admin', roletype: 'Admin' }, project: null } }))
jest.mock('@/role', () => ({ isAdmin: () => true }))
const flush = async () => { for (let i = 0; i < 30; i++) await Promise.resolve() }
function context () {
  return {
    loading: false,
    allocationBlocked: false,
    createOnStorage: false,
    formRef: { value: { validate: () => Promise.resolve(), scrollToField: jest.fn() } },
    form: { name: 'first-volume', diskofferingid: 'disk', size: '1', virtualmachineid: 'original-vm', deviceid: 4 },
    owner: { account: 'admin', domainid: 'domain' },
    resource: {},
    customDiskOffering: true,
    createVolumeFromVM: false,
    createVolumeFromSnapshot: false,
    attachVolume: false,
    handleRemoveFields: values => ({ ...values }),
    dialogCurrent: () => true,
    closeModal: jest.fn(),
    $pollJob: jest.fn(),
    $notifyError: jest.fn(),
    $t: x => x
  }
}
beforeEach(() => {
  jest.clearAllMocks()
  postAPI.mockImplementation(command => Promise.resolve(command === 'createVolume' ? { createvolumeresponse: { jobid: 'create-job' } } : { attachvolumeresponse: { jobid: 'attach-job' } }))
})

test('a completed background create cannot close a form opened after the accepted request', async () => {
  const ctx = context()
  CreateVolume.methods.handleSubmit.call(ctx)
  await flush()
  expect(ctx.closeModal).toHaveBeenCalledTimes(1)
  ctx.form.name = 'next-unsaved-volume'
  ctx.$pollJob.mock.calls[0][0].successMethod({ jobresult: { volume: { id: 'created-volume' } } })
  expect(ctx.closeModal).toHaveBeenCalledTimes(1)
  expect(ctx.form.name).toBe('next-unsaved-volume')
})

test('queued attachment keeps the submitted VM, device and project after form context changes', async () => {
  const ctx = context(); ctx.attachVolume = true; ctx.owner.projectid = 'original-project'
  CreateVolume.methods.handleSubmit.call(ctx)
  await flush()
  ctx.vmidtoattach = 'different-vm'; ctx.owner.projectid = 'different-project'; ctx.form.deviceid = 7
  ctx.$pollJob.mock.calls[0][0].successMethod({ jobresult: { volume: { id: 'created-volume' } } })
  expect(postAPI).toHaveBeenLastCalledWith('attachVolume', { id: 'created-volume', virtualmachineid: 'original-vm', deviceid: 4, projectid: 'original-project' })
})

test('an attachment option in a later form cannot attach a previously standalone create', async () => {
  const ctx = context()
  CreateVolume.methods.handleSubmit.call(ctx)
  await flush()
  ctx.attachVolume = true; ctx.vmidtoattach = 'later-vm'
  ctx.$pollJob.mock.calls[0][0].successMethod({ jobresult: { volume: { id: 'created-volume' } } })
  expect(postAPI).toHaveBeenCalledTimes(1)
})

test('a registered attachment device survives form filtering and is sent only to attachVolume', async () => {
  const ctx = context(); ctx.attachVolume = true
  ctx.$refs = Object.fromEntries(Object.keys(ctx.form).map(key => [key, {}]))
  ctx.handleRemoveFields = values => mixinForm.methods.handleRemoveFields.call(ctx, values)
  CreateVolume.methods.handleSubmit.call(ctx)
  await flush()
  expect(postAPI.mock.calls[0][0]).toBe('createVolume')
  expect(postAPI.mock.calls[0][1]).not.toHaveProperty('deviceid')
  ctx.$pollJob.mock.calls[0][0].successMethod({ jobresult: { volume: { id: 'created-volume' } } })
  expect(postAPI).toHaveBeenLastCalledWith('attachVolume', { id: 'created-volume', virtualmachineid: 'original-vm', deviceid: 4, projectid: '-1' })
})

test('a close from an obsolete dialog scope cannot close the currently active dialog', () => {
  const ctx = { dialogCurrent: () => false, $emit: jest.fn() }
  CreateVolume.methods.closeModal.call(ctx)
  expect(ctx.$emit).not.toHaveBeenCalled()
  ctx.dialogCurrent = () => true
  CreateVolume.methods.closeModal.call(ctx)
  expect(ctx.$emit).toHaveBeenCalledWith('close-action')
})

afterEach(() => clearStorageSnapshotOperations())
function snapshotContext () {
  const ctx = context(); ctx.attachVolume = true; ctx.snapshotSafetyTarget = { id: 'snapshot' }; ctx.createVolumeFromSnapshot = true; ctx.resource.id = 'snapshot'; ctx.form.zoneid = 'zone'
  ctx.$store = { getters: { userInfo: { id: 'admin' }, project: {} }, state: { user: { token: 'session' } } }
  ctx.submitSnapshotSafely = jest.fn(async (values, validate) => {
    await validate()
    return { jobid: 'created-job', status: 'complete', completion: Promise.resolve({ jobstatus: 1, jobresult: { volume: { id: 'created-volume', name: 'restored' } } }) }
  })
  getAPI.mockImplementation(api => Promise.resolve(api === 'listApis' ? { listapisresponse: { api: [{ name: 'attachVolume' }] } } : api === 'listVirtualMachines' ? { listvirtualmachinesresponse: { virtualmachine: [{ id: 'original-vm', state: 'Stopped', account: 'admin', domainid: 'domain', zoneid: 'zone' }] } } : { queryasyncjobresultresponse: { jobstatus: 1, jobresult: { volume: { id: 'created-volume' } } } }))
  return ctx
}
test('snapshot restore retains its requested attachment and checks the fixed VM again before attach', async () => {
  const ctx = snapshotContext(); CreateVolume.methods.handleSubmit.call(ctx); await flush()
  expect(postAPI).toHaveBeenCalledWith('attachVolume', { id: 'created-volume', virtualmachineid: 'original-vm', deviceid: 4, projectid: '-1', customjobid: expect.any(String) }, { preserveOnFailure: true })
  expect(getAPI.mock.calls.filter(([api]) => api === 'listVirtualMachines')).toHaveLength(2)
})
test('a changed destination VM prevents snapshot volume creation before its first mutation', async () => {
  const ctx = snapshotContext()
  getAPI.mockResolvedValue({ listapisresponse: { api: [{ name: 'attachVolume' }] }, listvirtualmachinesresponse: { virtualmachine: [{ id: 'different-vm' }] } })
  CreateVolume.methods.handleSubmit.call(ctx); await flush()
  expect(postAPI).not.toHaveBeenCalled(); expect(ctx.$notifyError).toHaveBeenCalled()
})
test('closing the snapshot dialog during creation prevents a late attachment', async () => {
  const ctx = snapshotContext()
  ctx.submitSnapshotSafely.mockImplementation(async () => { ctx.dialogCurrent = () => false; return { jobid: 'job', status: 'complete', completion: Promise.resolve({ jobstatus: 1 }) } })
  CreateVolume.methods.handleSubmit.call(ctx); await flush()
  expect(postAPI).not.toHaveBeenCalled(); expect(ctx.closeModal).not.toHaveBeenCalled()
})

test('confirmed missing source permits snapshot recovery while incomplete source lookup still blocks', () => {
  const ctx = { createVolumeFromSnapshot: true, createOnStorage: false, snapshotAllocationLoading: false, snapshotAllocationError: true, explicitAllocation: null, snapshotAllocation: null, snapshotSafetyContext: { source: { sourceavailable: false } } }
  expect(CreateVolume.computed.allocationBlocked.call(ctx)).toBe(false)
  ctx.snapshotSafetyContext.source.sourceavailable = true
  expect(CreateVolume.computed.allocationBlocked.call(ctx)).toBe(true)
  ctx.snapshotSafetyContext = null
  expect(CreateVolume.computed.allocationBlocked.call(ctx)).toBe(true)
  ctx.snapshotSafetyContext = { source: { sourceavailable: false } }; ctx.explicitAllocation = { policy: 'full' }
  expect(CreateVolume.computed.allocationBlocked.call(ctx)).toBe(true)
})

test('failed snapshot restore retains target and inputs while surfacing the async failure', async () => {
  const ctx = snapshotContext(); ctx.attachVolume = false
  ctx.submitSnapshotSafely.mockResolvedValue({ jobid: 'failed-job', status: 'failed', error: 'storage unavailable', completion: Promise.resolve({ jobstatus: 2 }) })
  CreateVolume.methods.handleSubmit.call(ctx); await flush()
  expect(ctx.closeModal).not.toHaveBeenCalled(); expect(ctx.form.name).toBe('first-volume')
  expect(ctx.resource.id).toBe('snapshot'); expect(ctx.snapshotSafetyError).toBe('storage unavailable')
  expect(ctx.loading).toBe(false)
})
