// Licensed to the Apache Software Foundation (ASF) under one or more
// contributor license agreements. See NOTICE. Apache License, Version 2.0.
// http://www.apache.org/licenses/LICENSE-2.0
import { observeConfigurationOperation } from '@/utils/storageConfigOperation'
import wire from '../fixtures/storage-1451-rollback-race-wire.json'

const row = value => value.liststorageserviceconfigurationoperationsresponse.configurationoperation[0]
const envelope = operation => ({ liststorageserviceconfigurationoperationsresponse: { configurationoperation: [operation] } })
const observe = get => observeConfigurationOperation({
  get, ...wire.stale.params, jobid: wire.job.params.jobid, sleep: async () => {}, maxAttempts: 1
})

describe('terminal async job and configuration snapshot race', () => {
  test('actual failed job re-reads the same operation before returning its completed rollback', async () => {
    const get = jest.fn().mockResolvedValueOnce(wire.stale.body).mockResolvedValueOnce(wire.job.body).mockResolvedValueOnce(wire.terminal.body)
    const result = await observe(get)
    expect(result).toEqual(row(wire.terminal.body))
    expect(result.canresume).toBe(false)
    expect(result.originalfailure).toEqual(row(wire.stale.body).originalfailure)
    expect(get.mock.calls.map(([api]) => api)).toEqual(['listStorageServiceConfigurationOperations', 'queryAsyncJobResult', 'listStorageServiceConfigurationOperations'])
    expect(get.mock.calls[2][1]).toEqual({ instanceid: wire.stale.params.instanceid, requestid: wire.stale.params.requestid })
  })

  test('success variant preserves the newly committed stored result when the job has no result', async () => {
    const committed = { ...row(wire.terminal.body), state: 'COMMITTED', result: { policy: 'NUMERIC' } }
    const get = jest.fn().mockResolvedValueOnce(wire.stale.body)
      .mockResolvedValueOnce({ queryasyncjobresultresponse: { jobstatus: 1 } }).mockResolvedValueOnce(envelope(committed))
    expect(await observe(get)).toEqual(committed)
  })

  test('a scope change during the terminal re-read cannot update or unlock another instance', async () => {
    let current = true
    const changed = jest.fn()
    const get = jest.fn().mockResolvedValueOnce(wire.stale.body).mockResolvedValueOnce(wire.job.body)
      .mockImplementationOnce(async () => { current = false; return wire.terminal.body })
    const result = await observeConfigurationOperation({ get, ...wire.stale.params, jobid: wire.job.params.jobid, changed, current: () => current })
    expect(result).toBeNull()
    expect(changed).toHaveBeenCalledTimes(1)
    expect(changed).toHaveBeenLastCalledWith(row(wire.stale.body))
  })

  test.each(['missing', 'other-operation', 'other-instance', 'read-failure'])('%s cannot turn a stale snapshot into confirmed recovery', async kind => {
    const changed = jest.fn()
    const get = jest.fn().mockResolvedValueOnce(wire.stale.body).mockResolvedValueOnce(wire.job.body)
    if (kind === 'read-failure') get.mockRejectedValueOnce(new Error('read unavailable'))
    else {
      get.mockResolvedValueOnce(kind === 'missing' ? { liststorageserviceconfigurationoperationsresponse: { configurationoperation: [] } } : envelope({
        ...row(wire.terminal.body), ...(kind === 'other-operation' ? { id: 'other' } : { instanceid: 'other-instance' })
      }))
    }
    const result = await observeConfigurationOperation({ get, ...wire.stale.params, jobid: wire.job.params.jobid, changed, sleep: async () => {}, maxAttempts: 1 })
    expect(result.state).toBe('OBSERVATION_UNAVAILABLE')
    expect(changed.mock.calls.some(([value]) => value.observationwarning)).toBe(true)
    expect(get).toHaveBeenCalledTimes(3)
  })

  test('a freshly confirmed recovery-required state remains protected without a mutation', async () => {
    const recovery = { ...row(wire.stale.body), state: 'RECOVERY_REQUIRED' }
    const get = jest.fn().mockResolvedValueOnce(wire.stale.body).mockResolvedValueOnce(wire.job.body).mockResolvedValueOnce(envelope(recovery))
    expect(await observe(get)).toEqual(recovery)
    expect(get.mock.calls.every(([api]) => !api.startsWith('resume') && !api.startsWith('update'))).toBe(true)
  })
  test('a fresh non-resumable running state continues observing until committed', async () => {
    const running = { ...row(wire.stale.body), canresume: false }
    const committed = { ...running, state: 'COMMITTED', result: {} }
    const get = jest.fn().mockResolvedValueOnce(wire.stale.body).mockResolvedValueOnce(wire.job.body)
      .mockResolvedValueOnce(envelope(running)).mockResolvedValueOnce(envelope(committed))
    expect((await observeConfigurationOperation({ get, ...wire.stale.params, jobid: wire.job.params.jobid, sleep: async () => {} })).state).toBe('COMMITTED')
    expect(get).toHaveBeenCalledTimes(4)
  })
})
