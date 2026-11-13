// Licensed to the Apache Software Foundation (ASF) under one or more
// contributor license agreements. See NOTICE for copyright ownership.
// Licensed under the Apache License, Version 2.0 (the "License");
// http://www.apache.org/licenses/LICENSE-2.0 . Distributed AS IS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND.
import { flushPromises } from '@vue/test-utils'
import { createVpcOfferingOperation, vpcOfferingBlocked } from '@/utils/vpcOfferingOperations'

const deferred = () => { let done; const promise = new Promise(resolve => { done = resolve }); return { promise, resolve: done } }
const entity = { id: 'new-uuid', name: 'Draft', state: 'Disabled' }
const rejected = { response: { status: 431, data: { errorresponse: { errorcode: 431, errortext: 'Invalid source' } } } }
let operations
beforeEach(() => { operations = []; jest.useFakeTimers() })
afterEach(() => { operations.forEach(operation => operation.invalidate()); jest.useRealTimers() })

function setup (overrides = {}) {
  const memory = new Map()
  const storage = { getItem: key => memory.get(key) || null, setItem: (key, value) => memory.set(key, value), removeItem: key => memory.delete(key) }
  let sequence = 0
  const job = (id, status = 1) => ({ jobid: id, userid: 'user', cmd: 'org.apache.cloudstack.api.command.admin.vpc.CloneVPCOfferingCmd', jobstatus: status, jobresult: status === 1 ? { vpcoffering: entity } : { errorcode: 530, errortext: 'Actual backend error' } })
  const get = jest.fn(async (command, params) => command === 'queryAsyncJobResult'
    ? { queryasyncjobresultresponse: job(params.jobid) }
    : { listvpcofferingsresponse: { vpcoffering: params.id ? [entity] : [] } })
  const post = jest.fn(async (command, params) => ({ [command.toLowerCase() + 'response']: { id: '1', jobid: params.customjobid } }))
  const options = { get, post, storage, storageKey: 'ledger', scope: 'secret-token', userId: 'user', makeId: () => 'request-' + ++sequence, pollOptions: { interval: 50, maxRetries: 0, maxDuration: 500 }, ...overrides }
  const operation = createVpcOfferingOperation(options)
  operations.push(operation)
  return { operation, get, post, storage, memory, job, options }
}
const run = operation => operation.run('cloneVPCOffering', { sourceofferingid: 'source-uuid', name: 'Draft', enable: false }, { form: { name: 'Draft', enable: false }, selectedServiceProviderMap: { Lb: ['VpcVirtualRouter', 'InternalLbVm'] } })

test('receipt alone is pending; duplicate submit is blocked; final UUID is verified instead of numeric receipt.id', async () => {
  const { operation, get, post, job } = setup()
  const pending = deferred()
  get.mockImplementation(async (command, params) => command === 'queryAsyncJobResult' ? pending.promise : { listvpcofferingsresponse: { vpcoffering: params.id ? [entity] : [] } })
  const result = run(operation); await flushPromises()
  expect(operation.snapshot().status).toBe('running')
  const duplicate = run(operation)
  expect(post).toHaveBeenCalledTimes(1)
  pending.resolve({ queryasyncjobresultresponse: job('request-1') }); await Promise.all([result, duplicate])
  expect(operation.snapshot()).toMatchObject({ status: 'succeeded', resultId: 'new-uuid' })
  expect(get).toHaveBeenCalledWith('listVPCOfferings', expect.objectContaining({ id: 'new-uuid' }), expect.anything())
  expect(get.mock.calls.some(([, params]) => params.id === '1')).toBe(false)
})

test('jobstatus 0 remains pending and only terminal success allows completion', async () => {
  const { operation, get, job } = setup()
  let reads = 0
  get.mockImplementation(async (command, params) => command === 'queryAsyncJobResult'
    ? { queryasyncjobresultresponse: job(params.jobid, reads++ === 0 ? 0 : 1) }
    : { listvpcofferingsresponse: { vpcoffering: params.id ? [entity] : [] } })
  const result = run(operation); await flushPromises()
  expect(operation.snapshot().status).toBe('running')
  jest.advanceTimersByTime(50); await result
  expect(operation.snapshot().status).toBe('succeeded')
})

test('actual job failure keeps error, submitted input, source and job; safe retry receives a new request ID', async () => {
  const { operation, get, job, post } = setup()
  get.mockImplementation(async (command, params) => command === 'queryAsyncJobResult'
    ? { queryasyncjobresultresponse: job(params.jobid, params.jobid === 'request-1' ? 2 : 1) }
    : { listvpcofferingsresponse: { vpcoffering: params.id ? [entity] : [] } })
  await run(operation)
  expect(operation.snapshot()).toMatchObject({ status: 'failed', failure: 'job', code: 530, jobId: 'request-1', error: 'Actual backend error', draft: { form: { enable: false } }, params: { sourceofferingid: 'source-uuid' } })
  await run(operation)
  expect(operation.snapshot().status).toBe('succeeded')
  expect(post.mock.calls.map(([, params]) => params.customjobid)).toEqual(['request-1', 'request-2'])
})

test('HTTP rejection is distinct from a transport error and permits an edited retry', async () => {
  const { operation, post } = setup()
  post.mockRejectedValueOnce(rejected)
  await run(operation)
  expect(operation.snapshot()).toMatchObject({ status: 'rejected', failure: 'http', jobId: '', error: 'Invalid source' })
  await run(operation)
  expect(post).toHaveBeenCalledTimes(2)
  expect(operation.snapshot().status).toBe('succeeded')
})

test('lost receipt is recovered by the original customjobid without a second POST', async () => {
  const { operation, post } = setup()
  post.mockRejectedValueOnce(new Error('Response lost after server acceptance'))
  await run(operation)
  expect(operation.snapshot()).toMatchObject({ status: 'unknown', failure: 'receipt', requestId: 'request-1', jobId: 'request-1' })
  await run(operation)
  expect(post).toHaveBeenCalledTimes(1)
  await operation.recheck()
  expect(operation.snapshot().status).toBe('succeeded')
  expect(post).toHaveBeenCalledTimes(1)
})

test('an unconfirmed submission with no queryable job stays blocked even when no offering is listed', async () => {
  const { operation, post, get } = setup()
  post.mockRejectedValue(new Error('Connection lost'))
  await run(operation)
  get.mockRejectedValue(new Error('Job not found'))
  await operation.recheck()
  await run(operation)
  expect(operation.snapshot().status).toBe('unknown')
  expect(vpcOfferingBlocked(operation.snapshot())).toBe(true)
  expect(post).toHaveBeenCalledTimes(1)
})

test('query failure can be rechecked without resubmission', async () => {
  const { operation, get, job, post } = setup()
  let fail = true
  get.mockImplementation(async (command, params) => {
    if (command === 'queryAsyncJobResult') {
      if (fail) throw new Error('503')
      return { queryasyncjobresultresponse: job(params.jobid) }
    }
    return { listvpcofferingsresponse: { vpcoffering: params.id ? [entity] : [] } }
  })
  await run(operation)
  expect(operation.snapshot()).toMatchObject({ status: 'unknown', failure: 'query' })
  fail = false; await operation.recheck()
  expect(operation.snapshot().status).toBe('succeeded')
  expect(post).toHaveBeenCalledTimes(1)
})

test('a successful job with unavailable entity read is not announced complete until the same result is verified', async () => {
  const { operation, get, job, post } = setup()
  let fail = true
  get.mockImplementation(async (command, params) => {
    if (command === 'queryAsyncJobResult') return { queryasyncjobresultresponse: job(params.jobid) }
    if (params.id && fail) throw new Error('Result lookup failed')
    return { listvpcofferingsresponse: { vpcoffering: params.id ? [entity] : [] } }
  })
  await run(operation)
  expect(operation.snapshot()).toMatchObject({ status: 'unknown', failure: 'result' })
  fail = false; await operation.recheck()
  expect(operation.snapshot().status).toBe('succeeded')
  expect(post).toHaveBeenCalledTimes(1)
})

test.each(['jobid', 'cmd', 'userid', 'entity'])('mismatched %s never completes another operation', async mismatch => {
  const { operation, get, job } = setup()
  get.mockImplementation(async (command, params) => {
    if (command !== 'queryAsyncJobResult') return { listvpcofferingsresponse: { vpcoffering: [] } }
    const result = job(params.jobid)
    if (mismatch === 'entity') result.jobresult.vpcoffering = { id: 'other', name: 'Other offering' }
    else result[mismatch] = 'other'
    return { queryasyncjobresultresponse: result }
  })
  await run(operation)
  expect(operation.snapshot().status).toBe('unknown')
})

test('existing-name preflight blocks a new POST and an async-failed leftover also blocks retry', async () => {
  const { operation, get, post, job } = setup()
  get.mockResolvedValue({ listvpcofferingsresponse: { vpcoffering: [entity] } })
  await run(operation)
  expect(operation.snapshot()).toMatchObject({ status: 'rejected', failure: 'existing' })
  expect(post).not.toHaveBeenCalled()
  get.mockImplementation(async (command, params) => command === 'queryAsyncJobResult' ? { queryasyncjobresultresponse: job(params.jobid, 2) } : { listvpcofferingsresponse: { vpcoffering: [] } })
  await run(operation)
  expect(operation.snapshot().status).toBe('failed')
  get.mockResolvedValue({ listvpcofferingsresponse: { vpcoffering: [entity] } })
  await run(operation)
  expect(operation.snapshot()).toMatchObject({ status: 'unknown', failure: 'existing' })
  expect(post).toHaveBeenCalledTimes(1)
})

test('reload restores the frozen form and receipt; recheck verifies the previous job', async () => {
  const initial = setup()
  initial.post.mockRejectedValueOnce(new Error('Lost receipt'))
  await run(initial.operation)
  expect([...initial.memory.values()].join('')).not.toContain('secret-token')
  initial.operation.invalidate()
  const restored = setup({ ...initial.options })
  expect(restored.operation.snapshot()).toMatchObject({ status: 'unknown', jobId: 'request-1', draft: { form: { name: 'Draft', enable: false } } })
  await restored.operation.recheck()
  expect(restored.operation.snapshot().status).toBe('succeeded')
  expect(initial.post).toHaveBeenCalledTimes(1)
})

test('unreadable storage or inability to record a request prevents a POST', async () => {
  for (const storage of [{ getItem: () => '{broken' }, { getItem: () => null, setItem: () => { throw new Error('quota') } }]) {
    const { operation, post } = setup({ storage })
    await run(operation)
    expect(post).not.toHaveBeenCalled()
    expect(operation.snapshot().status).toBe('unknown')
  }
})

test('reload during an outstanding POST retains the custom request ID even before a receipt arrives', async () => {
  const initial = setup(); const pending = deferred()
  initial.post.mockReturnValue(pending.promise)
  const original = run(initial.operation); await flushPromises()
  initial.operation.invalidate()
  const restored = setup({ ...initial.options })
  expect(restored.operation.snapshot()).toMatchObject({ status: 'unknown', jobId: 'request-1' })
  await restored.operation.recheck()
  expect(restored.operation.snapshot().status).toBe('succeeded')
  expect(initial.post).toHaveBeenCalledTimes(1)
  pending.resolve({ clonevpcofferingresponse: { jobid: 'request-1' } }); await original
})

test('request ID generation failure rejects safely before a POST', async () => {
  const { operation, post } = setup({ makeId: () => { throw new Error('Unavailable random source') } })
  await run(operation)
  expect(operation.snapshot()).toMatchObject({ status: 'rejected', failure: 'requestId' })
  expect(post).not.toHaveBeenCalled()
})

test('security scope replacement discards late receipt/result and prevents more reads', async () => {
  let scope = 'secret-token'
  const { operation, post, get } = setup({ getScope: () => scope })
  const pending = deferred(); post.mockReturnValue(pending.promise)
  const result = run(operation); await flushPromises()
  scope = 'another-session'
  pending.resolve({ clonevpcofferingresponse: { jobid: 'request-1' } }); await result
  expect(get.mock.calls.some(([command]) => command === 'queryAsyncJobResult')).toBe(false)
  expect(operation.snapshot().status).not.toBe('succeeded')
})
