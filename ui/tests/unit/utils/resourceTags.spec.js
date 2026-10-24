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
import { clearResourceTagOperations, getResourceTagOperation, readResourceTags, tagAccessError, tagContext, tagIdentity, tagOperationBlocked, tagTargetParams } from '@/utils/resourceTags'

const flush = async () => { for (let i = 0; i < 20; i++) await Promise.resolve() }
const deferred = () => { let finish; const promise = new Promise(resolve => { finish = resolve }); return { promise, resolve: finish } }
const target = { id: 'snapshot', account: 'owner', domainid: 'domain' }
const params = { resourceids: 'snapshot', resourcetype: 'Snapshot', 'tags[0].key': 'Key', 'tags[0].value': 'value' }
const row = (i = 0) => ({ resourceid: 'snapshot', resourcetype: 'Snapshot', key: 'key-' + i, value: 'value-' + i })
function setup (options = {}) {
  let identity = 'user/session'; let context = 'resource/owner'; let visible = true
  const get = jest.fn().mockResolvedValue({ queryasyncjobresultresponse: { jobstatus: 1 } })
  const post = jest.fn().mockResolvedValue({ createtagsresponse: { jobid: 'job' } })
  const args = { identity, context, getIdentity: () => identity, get, post, storage: sessionStorage, interval: 10, maxRetries: 0, maxDuration: 100, ...options }
  const op = getResourceTagOperation(args)
  const release = op.bind(() => visible && context === args.context)
  return { op, get, post, args, release, identity: v => { identity = v }, context: v => { context = v }, visible: v => { visible = v } }
}
beforeEach(() => { clearResourceTagOperations(sessionStorage); sessionStorage.clear(); jest.useFakeTimers() })
afterEach(() => { clearResourceTagOperations(sessionStorage); jest.useRealTimers() })

test('identity, owner and project changes have distinct scopes; explicit owner project is used', () => {
  const store = { state: { user: { token: 'private' } }, getters: { userInfo: { id: 'user', account: 'owner', domainid: 'domain' }, project: {} } }
  const original = tagContext(store, target, 'Snapshot')
  expect(tagTargetParams(target, 'Snapshot')).toEqual({ resourcetype: 'Snapshot', ignoreproject: true })
  expect(tagTargetParams({ ...target, projectid: 'owner-project' }, 'Snapshot')).toEqual({ resourcetype: 'Snapshot', projectid: 'owner-project' })
  expect(tagTargetParams({ id: 'project' }, 'Project').projectid).toBe('project')
  store.getters.project = { id: 'selected-project' }
  expect(tagContext(store, target, 'Snapshot')).not.toBe(original)
  store.state.user.token = 'another-session'
  expect(tagIdentity(store)).not.toContain('private')
})

test('receipt is pending until the original job completes; duplicate clicks never POST twice', async () => {
  const f = setup(); const pending = deferred(); f.get.mockReturnValueOnce(pending.promise)
  const p = f.op.run('createTags', params); f.op.run('createTags', params); await flush()
  expect(f.post).toHaveBeenCalledTimes(1); expect(f.op.snapshot().status).toBe('pending')
  pending.resolve({ queryasyncjobresultresponse: { jobstatus: 0 } }); await flush()
  expect(f.op.snapshot().status).toBe('pending')
  jest.advanceTimersByTime(10); await p
  expect(f.op.snapshot().status).toBe('succeeded'); expect(f.get.mock.calls.every(c => c[1].jobid === 'job')).toBe(true)
})

test('a frozen target and desired tag survive caller object mutation', async () => {
  const f = setup(); const gate = deferred(); const args = { ...params }
  const p = f.op.run('createTags', args, () => gate.promise)
  args.resourceids = 'other'; args['tags[0].value'] = 'changed'; gate.resolve(); await p
  expect(f.post.mock.calls[0][1]).toEqual(params)
})

test('job failure preserves actual reason and explicit retry starts exactly one new job', async () => {
  const f = setup(); f.get.mockResolvedValueOnce({ queryasyncjobresultresponse: { jobstatus: 2, jobresult: { errortext: 'Duplicate tag' } } })
  await f.op.run('createTags', params)
  expect(f.op.snapshot()).toMatchObject({ status: 'failed', error: 'Duplicate tag' })
  expect(f.post).toHaveBeenCalledTimes(1)
  f.post.mockResolvedValueOnce({ createtagsresponse: { jobid: 'retry-job' } })
  await f.op.run('createTags', params)
  expect(f.post).toHaveBeenCalledTimes(2); expect(f.op.snapshot().status).toBe('succeeded')
})

test('job lookup failure resumes the same job and never resubmits', async () => {
  const f = setup(); f.get.mockRejectedValueOnce({ response: { status: 503 } })
  await f.op.run('createTags', params)
  expect(f.op.snapshot()).toMatchObject({ status: 'unknown', jobId: 'job' })
  await f.op.run('createTags', params); expect(f.post).toHaveBeenCalledTimes(1)
  await f.op.resume(); expect(f.op.snapshot().status).toBe('succeeded'); expect(f.post).toHaveBeenCalledTimes(1)
})

test('POST timeout or malformed receipt stays unknown without automatic or click resubmission', async () => {
  for (const response of [new Error('timeout'), { createtagsresponse: {} }]) {
    clearResourceTagOperations(sessionStorage); sessionStorage.clear()
    const f = setup()
    if (response instanceof Error) f.post.mockRejectedValue(response); else f.post.mockResolvedValue(response)
    await f.op.run('createTags', params); await f.op.run('createTags', params); await f.op.resume()
    expect(f.op.snapshot()).toMatchObject({ status: 'unknown', jobId: '' }); expect(f.post).toHaveBeenCalledTimes(1); expect(f.get).not.toHaveBeenCalled()
  }
})

test('definitive API rejection and failed preparation permit only an explicit retry', async () => {
  const f = setup(); f.post.mockRejectedValueOnce({ response: { status: 431, data: { createtagsresponse: { errorcode: 431, errortext: 'Denied input' } } } })
  await f.op.run('createTags', params); expect(f.op.snapshot()).toMatchObject({ status: 'failed', error: 'Denied input' })
  await f.op.run('createTags', params, async () => { throw new Error('read failed') })
  expect(f.post).toHaveBeenCalledTimes(1); expect(f.op.snapshot().reason).toBe('prepare')
})

test('scope change before submission prevents the POST', async () => {
  const f = setup(); const gate = deferred(); const p = f.op.run('createTags', params, () => gate.promise)
  f.context('another-owner'); f.release(); gate.resolve(); await p
  expect(f.post).not.toHaveBeenCalled(); expect(f.op.snapshot().status).toBe('failed')
})

test('late POST receipt is retained only for the original owner and resumed on return', async () => {
  const f = setup(); const late = deferred(); f.post.mockReturnValueOnce(late.promise)
  const p = f.op.run('createTags', params); await flush(); f.visible(false); f.release()
  late.resolve({ createtagsresponse: { jobid: 'late-job' } }); await p
  expect(f.get).not.toHaveBeenCalled(); expect(f.op.snapshot()).toMatchObject({ status: 'unknown', jobId: 'late-job' })
  f.visible(true); f.op.bind(() => true); await f.op.resume()
  expect(f.get.mock.calls[0][1]).toEqual({ jobid: 'late-job' }); expect(f.post).toHaveBeenCalledTimes(1)
})

test('identity change invalidates old receipts and observers', async () => {
  const f = setup(); const late = deferred(); const listener = jest.fn(); f.op.subscribe(listener); f.post.mockReturnValueOnce(late.promise)
  const p = f.op.run('createTags', params); await flush(); f.identity('another-login')
  const other = getResourceTagOperation({ ...f.args, identity: 'another-login', getIdentity: () => 'another-login' })
  listener.mockClear(); late.resolve({ createtagsresponse: { jobid: 'old-user-job' } }); await p
  expect(listener).not.toHaveBeenCalled(); expect(other.snapshot().status).toBe('idle'); expect(f.get).not.toHaveBeenCalled()
})

test('job timeout is unknown and locks further mutations', async () => {
  const f = setup(); f.get.mockReturnValue(new Promise(() => {})); const p = f.op.run('createTags', params); await flush()
  jest.advanceTimersByTime(100); await p
  expect(f.op.snapshot().status).toBe('unknown'); expect(tagOperationBlocked(f.op.snapshot())).toBe(true)
})

test('paused poll cannot overwrite a newly resumed result; multiple views do not pause each other', async () => {
  const f = setup(); const old = deferred(); f.get.mockReturnValueOnce(old.promise)
  const p = f.op.run('createTags', params); await flush()
  const releaseSecond = f.op.bind(() => true); f.release()
  expect(f.op.snapshot().status).toBe('pending')
  releaseSecond(); f.op.bind(() => true)
  const resumed = f.op.resume(); old.resolve({ queryasyncjobresultresponse: { jobstatus: 2 } }); await resumed; await p
  expect(f.op.snapshot().status).toBe('succeeded')
})

test('receipt survives a registry reload without a token in its stored value', async () => {
  const f = setup(); const response = deferred(); f.get.mockReturnValueOnce(response.promise)
  const p = f.op.run('createTags', params); await flush(); f.release()
  const saved = Object.keys(sessionStorage).map(k => [k, sessionStorage.getItem(k)])
  clearResourceTagOperations(); sessionStorage.clear(); for (const [key, value] of saved) sessionStorage.setItem(key, value)
  const reopened = setup(); expect(reopened.op.snapshot()).toMatchObject({ status: 'unknown', jobId: 'job' })
  await reopened.op.resume(); expect(reopened.post).not.toHaveBeenCalled(); expect(reopened.op.snapshot().status).toBe('succeeded'); await p
  expect(JSON.stringify(saved)).not.toContain('user/session')
})

test('receipt persistence failure prevents the POST', async () => {
  const storage = { getItem: () => null, setItem: () => { throw new Error('quota') } }
  const f = setup({ storage }); await f.op.run('createTags', params)
  expect(f.post).not.toHaveBeenCalled(); expect(f.op.snapshot().error).toBe('message.tags.storage.failed')
})

test('tag pages are exact and scoped; an empty response is valid', async () => {
  const get = jest.fn().mockResolvedValueOnce({ listtagsresponse: { count: 101, tag: Array.from({ length: 100 }, (_, i) => row(i)) } }).mockResolvedValueOnce({ listtagsresponse: { count: 101, tag: [row(100)] } })
  expect(await readResourceTags(get, target, 'Snapshot')).toHaveLength(101)
  expect(get.mock.calls.map(c => c[1].page)).toEqual([1, 2]); expect(get.mock.calls[0][1]).toMatchObject({ resourceid: 'snapshot', ignoreproject: true })
  expect(await readResourceTags(jest.fn().mockResolvedValue({ listtagsresponse: {} }), target, 'Snapshot')).toEqual([])
})

test.each([
  { count: 2, tag: [row(), row()] },
  { count: 1, tag: [{ ...row(), resourceid: 'other' }] },
  { count: 1, tag: [{ ...row(), resourcetype: 'UserVm' }] },
  { count: 1, tag: [] },
  { tag: [row()] }
])('incomplete or foreign tag response is rejected: %p', async listtagsresponse => {
  await expect(readResourceTags(jest.fn().mockResolvedValue({ listtagsresponse }), target, 'Snapshot')).rejects.toThrow('message.tags.read.incomplete')
})

test('late read and an access error cannot be mistaken for an empty list', async () => {
  const gate = deferred(); let current = true; const p = readResourceTags(() => gate.promise, target, 'Snapshot', () => current)
  current = false; gate.resolve({ listtagsresponse: { count: 1, tag: [row()] } })
  await expect(p).rejects.toThrow('message.tags.scope.changed')
  expect(tagAccessError({ response: { status: 403 } })).toBe(true)
  expect(tagAccessError({ response: { data: { errorresponse: { errorcode: 531, errortext: 'Caller does not have permission' } } } })).toBe(true)
})
