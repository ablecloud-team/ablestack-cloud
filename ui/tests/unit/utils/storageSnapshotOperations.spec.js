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
import './cryptoRandom'
import { getAPI, postAPI } from '@/api'
import { storageSnapshotOperations, submitSnapshotOperation, snapshotOperationBusy, querySnapshotOperation, clearStorageSnapshotOperations, hydrateSnapshotOperations, snapshotSecurityReplaced, releaseUnknownSnapshotSubmission, attachSnapshotOperationJob } from '@/utils/storageSnapshotOperations'
jest.mock('@/api', () => ({ getAPI: jest.fn(), postAPI: jest.fn() }))
let options
beforeEach(() => {
  jest.clearAllMocks(); clearStorageSnapshotOperations()
  options = { api: 'createVolume', scope: 'session-owner-project', target: { id: 'snapshot', name: 'fixture' }, keys: ['snapshot:snapshot', 'volume:volume'], current: () => true, securityCurrent: () => true, validate: jest.fn().mockResolvedValue({ snapshotid: 'snapshot', name: 'fixture' }) }
  postAPI.mockResolvedValue({ createvolumeresponse: { jobid: 'job' } })
  getAPI.mockResolvedValue({ queryasyncjobresultresponse: { jobstatus: 1, jobresult: { volume: { id: 'new-volume' } } } })
})
afterEach(() => { clearStorageSnapshotOperations(); jest.useRealTimers() })
test('reservation is acquired before deferred validation and prevents a double POST', async () => {
  let complete
  options.validate = jest.fn(() => new Promise(resolve => { complete = resolve }))
  const first = submitSnapshotOperation(options)
  expect(snapshotOperationBusy(options.scope, options.keys)).toBe(true)
  await expect(submitSnapshotOperation(options)).rejects.toThrow('message.snapshot.action.busy')
  complete({ snapshotid: 'snapshot' })
  const operation = await first; await operation.completion
  expect(postAPI).toHaveBeenCalledTimes(1); expect(operation.status).toBe('complete')
})
test('accepted job differs from actual completion and blocks another entry point', async () => {
  let complete
  getAPI.mockImplementation(() => new Promise(resolve => { complete = resolve }))
  const operation = await submitSnapshotOperation(options)
  expect(operation.status).toBe('accepted'); expect(operation.jobid).toBe('job')
  expect(snapshotOperationBusy(options.scope, ['volume:volume'])).toBe(true)
  complete({ queryasyncjobresultresponse: { jobstatus: 1, jobresult: { volume: { id: 'new' } } } })
  await operation.completion; expect(operation.status).toBe('complete')
})
test('a lost submit response remains unknown and never retries a POST automatically', async () => {
  postAPI.mockRejectedValue(new Error('network disconnected')); getAPI.mockRejectedValue({ response: { status: 403 } })
  const operation = await submitSnapshotOperation(options)
  expect(operation.status).toBe('unknown'); expect(operation.jobid).toBe('')
  await expect(submitSnapshotOperation(options)).rejects.toThrow('message.snapshot.action.busy')
  expect(postAPI).toHaveBeenCalledTimes(1)
})
test('HTTP 5xx is ambiguous even if a server error message is present', async () => {
  getAPI.mockRejectedValue({ response: { status: 403 } }); postAPI.mockRejectedValue({ response: { status: 500, data: { errorresponse: { errorcode: 530, errortext: 'unavailable' } } } })
  expect((await submitSnapshotOperation(options)).status).toBe('unknown')
})
test('structured API validation rejection is distinct from unknown delivery', async () => {
  postAPI.mockRejectedValue({ response: { status: 431, data: { errorresponse: { errorcode: 431, errortext: 'state changed' } } } })
  const operation = await submitSnapshotOperation(options)
  expect(operation.status).toBe('failed'); expect(snapshotOperationBusy(options.scope, options.keys)).toBe(false)
})
test('plain success response without job ID cannot claim asynchronous completion', async () => {
  getAPI.mockRejectedValue({ response: { status: 403 } }); postAPI.mockResolvedValue({ createvolumeresponse: { success: true } })
  const operation = await submitSnapshotOperation(options)
  expect(operation.status).toBe('unknown'); expect(getAPI).toHaveBeenCalledTimes(1)
})
test('missing terminal resource payload remains unknown', async () => {
  getAPI.mockResolvedValue({ queryasyncjobresultresponse: { jobstatus: 1, jobresult: {} } })
  const operation = await submitSnapshotOperation(options); const result = await operation.completion
  expect(operation.status).toBe('unknown'); expect(result.trackingStatus).toBe('unknown')
})
test('query error retains job and a later check only queries it', async () => {
  getAPI.mockRejectedValue({ response: { status: 403 } })
  const operation = await submitSnapshotOperation(options); await operation.completion
  expect(operation.status).toBe('unknown'); expect(operation.jobid).toBe('job')
  getAPI.mockResolvedValue({ queryasyncjobresultresponse: { jobstatus: 1, jobresult: { volume: { id: 'new' } } } })
  await querySnapshotOperation(operation, () => true)
  expect(operation.status).toBe('complete'); expect(postAPI).toHaveBeenCalledTimes(1)
})
test('context changed during preflight sends no mutation', async () => {
  let current = true
  options.current = () => current
  options.validate = async () => { current = false; return {} }
  await expect(submitSnapshotOperation(options)).rejects.toThrow('message.snapshot.action.context.changed')
  expect(postAPI).not.toHaveBeenCalled()
})
test('job received after project change is retained but never queried under new credentials', async () => {
  let security = true
  options.securityCurrent = () => security
  postAPI.mockImplementation(async () => { security = false; return { createvolumeresponse: { jobid: 'job' } } })
  const operation = await submitSnapshotOperation(options)
  expect(operation.status).toBe('unknown'); expect(operation.jobid).toBe('job'); expect(getAPI).not.toHaveBeenCalled()
})
test('reload preserves a job and never persists authentication secret or mutation payload', async () => {
  options.scope = 'secret-session-value'; options.securityCurrent = () => false
  const operation = await submitSnapshotOperation(options)
  const data = sessionStorage.getItem('mold.snapshot.operations.v1')
  expect(data).not.toContain('secret-session-value'); expect(data).not.toContain('snapshotid')
  delete storageSnapshotOperations[operation.id]; hydrateSnapshotOperations()
  expect(storageSnapshotOperations[operation.id].jobid).toBe('job')
  expect(snapshotOperationBusy(options.scope, options.keys)).toBe(true)
})
test('manual unknown release requires history confirmation and cannot drop an accepted job', async () => {
  postAPI.mockRejectedValue(new Error('network')); getAPI.mockRejectedValue({ response: { status: 403 } })
  const operation = await submitSnapshotOperation(options)
  expect(releaseUnknownSnapshotSubmission(operation, false)).toBe(false)
  expect(releaseUnknownSnapshotSubmission(operation, true)).toBe(true)
  expect(snapshotOperationBusy(options.scope, options.keys)).toBe(false)
  operation.status = 'unknown'; operation.jobid = 'job'
  expect(releaseUnknownSnapshotSubmission(operation, true)).toBe(false)
})

test('lost acceptance can link only an explicitly confirmed existing job without resubmission', async () => {
  postAPI.mockRejectedValue(new Error('lost response')); getAPI.mockRejectedValue({ response: { status: 403 } })
  const entry = await submitSnapshotOperation(options); const jobid = entry.requestid
  getAPI.mockResolvedValue({ queryasyncjobresultresponse: { cmd: 'org.apache.cloudstack.api.command.user.volume.CreateVolumeCmd', jobstatus: 1, jobresult: { volume: { id: 'new', snapshotid: 'snapshot' } } } })
  await expect(attachSnapshotOperationJob(entry, jobid, false, () => true)).rejects.toThrow('message.snapshot.action.job.confirm')
  await attachSnapshotOperationJob(entry, jobid, true, () => true)
  expect(entry.status).toBe('complete'); expect(entry.jobid).toBe(jobid); expect(postAPI).toHaveBeenCalledTimes(1)
})
test('an unrelated job cannot unlock the unknown source', async () => {
  postAPI.mockRejectedValue(new Error('lost response')); getAPI.mockRejectedValue({ response: { status: 403 } })
  const entry = await submitSnapshotOperation(options)
  getAPI.mockResolvedValue({ queryasyncjobresultresponse: { jobstatus: 1, jobresult: { volume: { id: 'new', snapshotid: 'other' } } } })
  await expect(attachSnapshotOperationJob(entry, '00000000-0000-0000-0000-000000000001', true, () => true)).rejects.toThrow('message.snapshot.action.job.mismatch')
  expect(entry.jobid).toBe(''); expect(entry.status).toBe('unknown')
})

test('archive retains its historical createsnapshotresponse envelope and tracks actual completion', async () => {
  options.api = 'archiveSnapshot'
  postAPI.mockResolvedValue({ createsnapshotresponse: { jobid: 'archive-job' } })
  getAPI.mockResolvedValue({ queryasyncjobresultresponse: { jobstatus: 1, jobresult: { createsnapshotresponse: { success: true } } } })
  const entry = await submitSnapshotOperation(options); await entry.completion
  expect(entry.jobid).toBe('archive-job'); expect(entry.status).toBe('complete')
})

test('a lost response queries the preassigned job UUID without any second POST', async () => {
  postAPI.mockRejectedValue(new Error('lost response'))
  getAPI.mockResolvedValue({ queryasyncjobresultresponse: { jobstatus: 1, jobresult: { volume: { id: 'created' } } } })
  const entry = await submitSnapshotOperation(options); await entry.completion
  expect(entry.jobid).toBe(entry.requestid); expect(entry.status).toBe('complete')
  expect(postAPI).toHaveBeenCalledTimes(1)
  expect(postAPI.mock.calls[0][1].customjobid).toBe(entry.requestid)
  expect(getAPI.mock.calls[0][1].jobid).toBe(entry.requestid)
})

test('cold authentication bootstrap preserves hydrated jobs while real security changes clear them', () => {
  const scope = (token, user, project) => JSON.stringify([token, user, project])
  expect(snapshotSecurityReplaced(scope('session', null), scope('', null))).toBe(false)
  expect(snapshotSecurityReplaced(scope('session', 'user'), scope('session', null))).toBe(false)
  expect(snapshotSecurityReplaced(scope('session', 'user', 'project2'), scope('session', 'user', 'project1'))).toBe(false)
  expect(snapshotSecurityReplaced(scope('', 'user'), scope('session', 'user'))).toBe(true)
  expect(snapshotSecurityReplaced(scope('new-session', 'user'), scope('session', 'user'))).toBe(true)
  expect(snapshotSecurityReplaced(scope('session', 'other'), scope('session', 'user'))).toBe(true)
})
