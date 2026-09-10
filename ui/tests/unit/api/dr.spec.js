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

import { getAPI, postAPI } from '@/api'
import { createDrPlan, normalizeAcceptedDrRun, startDrAction, waitForDrMutation } from '@/api/dr'

jest.mock('@/api', () => ({
  getAPI: jest.fn(),
  postAPI: jest.fn()
}))

describe('DR action acceptance contract', () => {
  beforeEach(() => {
    jest.clearAllMocks()
  })

  test('normalizes nested and array run payloads', () => {
    expect(normalizeAcceptedDrRun({ drrun: [{ id: 'run-1' }] })).toEqual({ id: 'run-1' })
  })

  test('recovers an accepted run by idempotency key when the start response is empty', async () => {
    postAPI.mockResolvedValue({
      startdrfailbackresponse: {}
    })
    getAPI.mockResolvedValue({
      listdrrunsresponse: {
        count: 1,
        drrun: [{
          id: 'run-106',
          runtype: 'FAILBACK',
          idempotencykey: 'request-1'
        }]
      }
    })

    const run = await startDrAction('startDrFailback', {
      planid: 'plan-38',
      actionintent: 'FAILBACK',
      idempotencykey: 'request-1'
    }, {
      expectedRunType: 'FAILBACK',
      intervalMs: 1,
      maxAttempts: 2
    })

    expect(run.id).toBe('run-106')
    expect(getAPI).toHaveBeenCalledWith('listDrRuns', {
      planid: 'plan-38',
      idempotencykey: 'request-1'
    })
  })

  test('returns the direct accepted run without recovery polling', async () => {
    postAPI.mockResolvedValue({
      startdrfailbackresponse: {
        drrun: {
          id: 'run-107',
          runtype: 'FAILBACK'
        }
      }
    })

    const run = await startDrAction('startDrFailback', {
      planid: 'plan-38',
      actionintent: 'FAILBACK',
      idempotencykey: 'request-2'
    }, {
      expectedRunType: 'FAILBACK'
    })

    expect(run.id).toBe('run-107')
    expect(getAPI).not.toHaveBeenCalled()
  })

  test('waits for the async acceptance job before returning the accepted run', async () => {
    postAPI.mockResolvedValue({
      startdrtestfailoverresponse: {
        jobid: 'job-2673'
      }
    })
    getAPI.mockResolvedValue({
      queryasyncjobresultresponse: {
        jobstatus: 1,
        jobresult: {
          startdrtestfailoverresponse: {
            drrun: {
              id: 'run-118',
              runtype: 'TEST_FAILOVER'
            }
          }
        }
      }
    })

    const run = await startDrAction('startDrTestFailover', {
      planid: 'plan-38',
      actionintent: 'TEST_FAILOVER',
      idempotencykey: 'request-3'
    }, {
      expectedRunType: 'TEST_FAILOVER',
      acceptanceIntervalMs: 1
    })

    expect(run.id).toBe('run-118')
    expect(getAPI).toHaveBeenCalledTimes(1)
    expect(getAPI).toHaveBeenCalledWith('queryAsyncJobResult', { jobId: 'job-2673' })
  })

  test('surfaces an async acceptance failure without attempting run recovery', async () => {
    postAPI.mockResolvedValue({
      startdrtestfailoverresponse: {
        jobid: 'job-2674'
      }
    })
    getAPI.mockResolvedValue({
      queryasyncjobresultresponse: {
        jobstatus: 2,
        jobresult: {
          errorcode: 530,
          errortext: 'DR_TEST_SESSION_BLOCKING: previous test session requires cleanup'
        }
      }
    })

    await expect(startDrAction('startDrTestFailover', {
      planid: 'plan-38',
      actionintent: 'TEST_FAILOVER',
      idempotencykey: 'request-4'
    }, {
      expectedRunType: 'TEST_FAILOVER',
      acceptanceIntervalMs: 1
    })).rejects.toMatchObject({
      code: 'DR_TEST_SESSION_BLOCKING',
      jobid: 'job-2674'
    })

    expect(getAPI).toHaveBeenCalledTimes(1)
    expect(getAPI).toHaveBeenCalledWith('queryAsyncJobResult', { jobId: 'job-2674' })
  })

  test('returns plan mutation admission without waiting for terminal completion', async () => {
    postAPI.mockResolvedValue({
      createdrplanresponse: { jobid: 'job-create-1' }
    })

    const admission = await createDrPlan({ name: 'plan-1' })

    expect(admission).toMatchObject({
      admitted: true,
      command: 'createDrPlan',
      jobid: 'job-create-1'
    })
    expect(getAPI).not.toHaveBeenCalled()
  })

  test('resolves the primitive plan mutation result in the background', async () => {
    getAPI.mockResolvedValue({
      queryasyncjobresultresponse: {
        jobstatus: 1,
        jobresult: {
          createdrplanresponse: {
            drplanmutation: {
              id: 'plan-uuid-1',
              operation: 'CREATE',
              initialrunid: 'run-uuid-1'
            }
          }
        }
      }
    })

    await expect(waitForDrMutation({
      admitted: true,
      command: 'createDrPlan',
      jobid: 'job-create-1'
    }, { intervalMs: 1 })).resolves.toEqual({
      id: 'plan-uuid-1',
      operation: 'CREATE',
      initialrunid: 'run-uuid-1'
    })
  })
})
