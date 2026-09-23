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

import { allocated, allocationReason, validCidr, validPassword, vbmcOperations, runVbmc, pollVbmc } from '@/utils/vbmc'
import { getAPI, postAPI } from '@/api'
jest.mock('@/api', () => ({ getAPI: jest.fn(), postAPI: jest.fn() }))
beforeEach(() => { jest.clearAllMocks(); Object.keys(vbmcOperations).forEach(k => delete vbmcOperations[k]) })
test('allocation restrictions do not erase existing allocations', () => {
  expect(allocated({ vbmcport: '6230', state: 'Stopped' })).toBe(true)
  expect(allocated({ vbmcport: 'None' })).toBe(false)
  expect(allocationReason({ state: 'Running', hypervisor: 'KVM', haenable: true })).toBe('ha')
  expect(allocationReason({ state: 'Running', hypervisor: 'KVM', hostid: 'h' })).toBe('')
})
test('password and IPv4 CIDR contract', () => {
  for (const value of ['12345678', '1234567890123456', '12345678901234567890']) expect(validPassword(value)).toBe(true)
  for (const value of ['1234567', '123456789012345678901']) expect(validPassword(value)).toBe(false)
  for (const value of ['short', '123456789012345%', '123456789012345 ', '한글1234567890123456']) expect(validPassword(value)).toBe(false)
  for (const value of ['10.10.0.0/16', '127.0.0.1/32', '0.0.0.0/0']) expect(validCidr(value)).toBe(true)
  for (const value of ['10.0.0.0/33', '256.0.0.1/24', '10.0.0.1', '::1/128', '10.0.0.0/-1']) expect(validCidr(value)).toBe(false)
})
test('unknown submission never resubmits or stores credentials', async () => {
  postAPI.mockRejectedValue(new Error('transport error'))
  await runVbmc('vm', 'allocateVbmcToVM', { password: 'sensitive' })
  await runVbmc('vm', 'allocateVbmcToVM', {})
  expect(postAPI).toHaveBeenCalledTimes(1)
  expect(vbmcOperations.vm.state).toBe('unknown')
  expect(JSON.stringify(vbmcOperations)).not.toContain('sensitive')
})
test('retains job ID after lost poll and resolves without resubmission', async () => {
  postAPI.mockResolvedValue({ removevbmctovmresponse: { jobid: 'j' } })
  getAPI.mockRejectedValueOnce(new Error('timeout'))
  await runVbmc('vm', 'removeVbmcToVM', {})
  expect(vbmcOperations.vm.state).toBe('unknown')
  getAPI.mockResolvedValue({ queryasyncjobresultresponse: { jobstatus: 1 } })
  await pollVbmc('vm')
  expect(vbmcOperations.vm.state).toBe('success')
  expect(postAPI).toHaveBeenCalledTimes(1)
})
test('server failure never implies endpoint removal succeeded', async () => {
  postAPI.mockResolvedValue({ removevbmctovmresponse: { jobid: 'j' } })
  getAPI.mockResolvedValue({ queryasyncjobresultresponse: { jobstatus: 2 } })
  await runVbmc('vm', 'removeVbmcToVM', {})
  expect(vbmcOperations.vm.state).toBe('failed')
})
