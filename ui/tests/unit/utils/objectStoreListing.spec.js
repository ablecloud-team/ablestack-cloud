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

import { EventEmitter } from 'events'
import { readObjectStoreListing, OBJECT_STORE_LIST_TIMEOUT_MS } from '@/utils/objectStoreListing'

let stream
beforeEach(() => {
  jest.useFakeTimers()
  stream = new EventEmitter()
  stream.destroy = jest.fn(() => stream.emit('close'))
})
afterEach(() => jest.useRealTimers())

test('commits all records only on end and detaches stream listeners', async () => {
  const op = readObjectStoreListing(() => stream)
  stream.emit('data', { name: 'a' }); stream.emit('data', { prefix: 'folder/' }); stream.emit('end')
  expect(await op.promise).toEqual({ status: 'success', error: undefined, records: [{ name: 'a' }, { prefix: 'folder/' }] })
  expect(stream.listenerCount('data')).toBe(0)
  expect(stream.listenerCount('end')).toBe(0)
  expect(jest.getTimerCount()).toBe(0)
  expect(stream.destroy).not.toHaveBeenCalled()
})

test('an empty successful response is a successful empty listing', async () => {
  const op = readObjectStoreListing(() => stream); stream.emit('end')
  expect((await op.promise).records).toEqual([])
  expect((await op.promise).status).toBe('success')
})

test.each([403, 404, 503])('terminates %s without publishing partial records', async statusCode => {
  const op = readObjectStoreListing(() => stream)
  stream.emit('data', { name: 'partial' }); stream.emit('error', Object.assign(new Error('S3 error'), { statusCode }))
  const result = await op.promise
  expect(result.status).toBe('error'); expect(result.records).toEqual([])
  expect(result.error.statusCode).toBe(statusCode); expect(stream.destroy).toHaveBeenCalledTimes(1)
})

test('a synchronous SDK failure resolves the operation and clears its deadline', async () => {
  const error = new Error('invalid client')
  const op = readObjectStoreListing(() => { throw error })
  expect(await op.promise).toMatchObject({ status: 'error', error })
  expect(jest.getTimerCount()).toBe(0)
})

test('a stream which never ends times out, releases callers, and absorbs late events', async () => {
  const op = readObjectStoreListing(() => stream)
  jest.advanceTimersByTime(OBJECT_STORE_LIST_TIMEOUT_MS)
  expect((await op.promise).status).toBe('timeout')
  expect(stream.destroy).toHaveBeenCalledTimes(1)
  expect(() => { stream.emit('error', new Error('late')); stream.emit('end') }).not.toThrow()
  expect(jest.getTimerCount()).toBe(0)
})

test('cancellation is idempotent and a silent close is not a successful empty result', async () => {
  const op = readObjectStoreListing(() => stream); op.cancel(); op.cancel()
  expect((await op.promise).status).toBe('cancelled')
  expect(stream.destroy).toHaveBeenCalledTimes(1)
  const closed = new EventEmitter(); closed.destroy = jest.fn()
  const other = readObjectStoreListing(() => closed); closed.emit('close')
  expect((await other.promise).status).toBe('aborted')
})

test('a throwing destroy does not strand the consumer promise', async () => {
  stream.destroy = jest.fn(() => { throw new Error('dispose failed') })
  const op = readObjectStoreListing(() => stream); op.cancel()
  expect((await op.promise).status).toBe('cancelled')
})
