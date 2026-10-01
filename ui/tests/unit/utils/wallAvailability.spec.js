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

import { createWallGate } from '@/utils/wallAvailability'

afterEach(() => jest.restoreAllMocks())
test('unavailable service never authorizes alert requests, recovery uses only status', async () => {
  let now = 1000
  jest.spyOn(Date, 'now').mockImplementation(() => now)
  const request = jest.fn().mockResolvedValueOnce({ getwallalertavailabilityresponse: { wallavailability: { state: 'ConnectionFailed', available: false } } }).mockResolvedValue({ getwallalertavailabilityresponse: { wallavailability: { state: 'Ready', available: true } } })
  const gate = createWallGate(request, () => true)
  expect(await gate.check()).toBe(false)
  expect(await gate.check()).toBe(false)
  expect(request).toHaveBeenCalledTimes(1)
  now += 31000
  expect(await gate.check()).toBe(true)
  expect(request.mock.calls.every(([name]) => name === 'getWallAlertAvailability')).toBe(true)
})
test('simultaneous probes are coalesced', async () => {
  const request = jest.fn().mockResolvedValue({ getwallalertavailabilityresponse: { wallavailability: { state: 'Ready', available: true } } })
  const gate = createWallGate(request, () => true)
  expect(await Promise.all([gate.check(), gate.check()])).toEqual([true, true])
  expect(request).toHaveBeenCalledTimes(1)
})
test('logout while probe is pending cannot authorize old session', async () => {
  let finish
  let current = true
  const gate = createWallGate(() => new Promise(resolve => { finish = resolve }), () => current)
  const result = gate.check()
  current = false
  finish({ getwallalertavailabilityresponse: { wallavailability: { state: 'Ready', available: true } } })
  expect(await result).toBe(false)
  expect(await gate.check()).toBe(false)
})
test('missing status API and transport failure fail closed', async () => {
  for (const request of [() => Promise.resolve({}), () => Promise.reject(new Error('offline'))]) {
    expect(await createWallGate(request, () => true).check()).toBe(false)
  }
})
test('alert failure invalidates previously ready state', async () => {
  const request = jest.fn().mockResolvedValueOnce({ getwallalertavailabilityresponse: { wallavailability: { state: 'Ready', available: true } } }).mockResolvedValue({ getwallalertavailabilityresponse: { wallavailability: { state: 'Degraded', available: false } } })
  const gate = createWallGate(request, () => true)
  expect(await gate.check()).toBe(true)
  gate.fail()
  expect(await gate.check()).toBe(false)
})
