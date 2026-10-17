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

import { createScopedStorageReads } from '@/utils/scopedStorageReads'
import { flushPromises } from '@vue/test-utils'

describe('resource-owned Storage Service reference reads', () => {
  let scope, active, states, reader
  beforeEach(() => {
    scope = 'first'
    active = true
    states = []
    reader = createScopedStorageReads({ scope: () => scope, active: () => active, onState: (key, state) => states.push({ key, ...state }) })
  })
  afterEach(() => jest.useRealTimers())

  it('coalesces one resource and retries a stale resource with freshly constructed input', async () => {
    const pending = []
    const read = jest.fn(() => new Promise(resolve => pending.push({ scope, resolve })))
    const apply = jest.fn()
    const first = reader.run('vm', read, apply)
    expect(reader.run('vm', read, apply)).toBe(first)
    await flushPromises()
    scope = 'second'
    pending[0].resolve({ id: 'old' })
    await flushPromises()
    expect(apply).not.toHaveBeenCalled()
    expect(pending[1].scope).toBe('second')
    pending[1].resolve({ id: 'new' })
    await first
    expect(apply).toHaveBeenCalledWith({ id: 'new' })
    expect(states[states.length - 1]).toMatchObject({ pending: false, failed: false })
  })

  it('does not apply or retry a response after unmount', async () => {
    let complete
    const apply = jest.fn()
    const promise = reader.run('vm', () => new Promise(resolve => { complete = resolve }), apply)
    await flushPromises()
    active = false
    complete({ id: 'old' })
    await promise
    expect(apply).not.toHaveBeenCalled()
  })

  it('reports a timeout and ignores its late response', async () => {
    jest.useFakeTimers()
    let complete
    const apply = jest.fn()
    const promise = reader.run('vm', () => new Promise(resolve => { complete = resolve }), apply)
    await Promise.resolve()
    jest.advanceTimersByTime(15001)
    await promise
    expect(states[states.length - 1]).toMatchObject({ pending: false, failed: true })
    complete({ id: 'late' })
    await Promise.resolve()
    expect(apply).not.toHaveBeenCalled()
  })

  it('never lets an older generation clear a newer pending read', async () => {
    const pending = []
    const read = () => new Promise(resolve => pending.push(resolve))
    const apply = jest.fn()
    const first = reader.run('vm', read, apply)
    await flushPromises()
    scope = 'second'
    const second = reader.run('vm', read, apply)
    await flushPromises()
    pending[0]({ id: 'old' })
    await first
    expect(states[states.length - 1]).toMatchObject({ pending: true })
    pending[1]({ id: 'new' })
    await second
    expect(apply).toHaveBeenCalledTimes(1)
    expect(apply).toHaveBeenCalledWith({ id: 'new' })
  })
})
