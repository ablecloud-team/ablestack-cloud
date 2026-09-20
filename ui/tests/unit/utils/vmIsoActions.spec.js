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

import { attachedIsos, isoCapacity, isoActionReason, startIsoOperation, clearIsoOperations } from '@/utils/vmIsoActions'

const items = [{ id: 'one' }, { id: 'two' }]
const setup = overrides => ({ current: () => true, refresh: jest.fn(), validate: jest.fn().mockResolvedValue(), submit: jest.fn(item => Promise.resolve({ jobid: item.id })), poll: jest.fn().mockResolvedValue({ jobstatus: 1 }), ...overrides })
beforeEach(clearIsoOperations)
it('normalizes legacy and secondary-only ISO responses without duplicates', () => {
  expect(attachedIsos({ isoid: 'one' })).toEqual([expect.objectContaining({ id: 'one', deviceseq: 3 })])
  expect(attachedIsos({ isoid: 'old', isos: [{ id: 'two', deviceseq: 4 }, { id: 'two', deviceseq: 4 }] })).toHaveLength(1)
  expect(isoCapacity({ hypervisor: 'KVM', isomaxcount: 1, isos: items })).toBe(0)
  expect(isoCapacity({ hypervisor: 'VMware' })).toBe(1)
})
it('gates unavailable state, host and full capacity', () => {
  expect(isoActionReason({ state: 'Starting' })).toBeTruthy()
  expect(isoActionReason({ state: 'Running', hostcontrolstate: 'Offline' })).toBeTruthy()
  expect(isoActionReason({ state: 'Running', hypervisor: 'KVM', isos: items }, true)).toContain('full')
})
it('waits for the first terminal job before submitting the second', async () => {
  let finish
  const deps = setup({ poll: jest.fn().mockImplementationOnce(() => new Promise(resolve => { finish = resolve })).mockResolvedValue({ jobstatus: 1 }) })
  const op = startIsoOperation('vm', { items }, deps)
  await new Promise(resolve => setImmediate(resolve))
  expect(deps.submit).toHaveBeenCalledTimes(1)
  finish({ jobstatus: 1 }); await op.done
  expect(deps.submit).toHaveBeenCalledTimes(2)
  expect(op.items.map(i => i.status)).toEqual(['success', 'success'])
})
it('keeps successful ISO and retries only explicit failure', async () => {
  const deps = setup({ poll: jest.fn().mockResolvedValueOnce({ jobstatus: 2, jobresult: { errortext: 'rejected' } }).mockResolvedValue({ jobstatus: 1 }) })
  const op = startIsoOperation('vm', { items }, deps); await op.done
  expect(op.items.map(i => i.status)).toEqual(['failed', 'success'])
  await op.retry()
  expect(deps.submit.mock.calls.map(c => c[0].id)).toEqual(['one', 'two', 'one'])
})
it('stops after an uncertain job, rechecks it without submitting it twice and resumes pending ISO', async () => {
  const deps = setup({ poll: jest.fn().mockResolvedValueOnce({ trackingStatus: 'unknown' }).mockResolvedValue({ jobstatus: 1 }) })
  const op = startIsoOperation('vm', { items }, deps); await op.done
  expect(op.items.map(i => i.status)).toEqual(['unknown', 'pending'])
  await op.retry(); expect(deps.submit).toHaveBeenCalledTimes(1)
  await op.check()
  expect(deps.submit.mock.calls.map(c => c[0].id)).toEqual(['one', 'two'])
})
it('does not resubmit a response without job ID or transport failure', async () => {
  for (const submit of [jest.fn().mockResolvedValue({}), jest.fn().mockRejectedValue(new Error('timeout'))]) {
    clearIsoOperations()
    const deps = setup({ submit }); const op = startIsoOperation('vm', { items }, deps); await op.done; await op.retry(); await op.check()
    expect(op.items.map(i => i.status)).toEqual(['unknown', 'pending'])
    expect(submit).toHaveBeenCalledTimes(1)
  }
})
it('blocks duplicate begin and double retry while running', async () => {
  let finish
  const deps = setup({ poll: () => new Promise(resolve => { finish = resolve }) })
  const op = startIsoOperation('vm', { items: [items[0]] }, deps)
  expect(startIsoOperation('vm', { items }, deps)).toBe(op)
  await op.retry(); await new Promise(resolve => setImmediate(resolve))
  finish({ jobstatus: 1 }); await op.done
  expect(deps.submit).toHaveBeenCalledTimes(1)
})
it('stops submission after a security scope change', async () => {
  let current = true
  const deps = setup({ current: () => current, validate: async () => { current = false } })
  const op = startIsoOperation('vm', { items }, deps); await op.done
  expect(deps.submit).not.toHaveBeenCalled()
})
