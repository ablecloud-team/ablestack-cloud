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

import Registration from '@/views/storage/ImportStorageVolume.vue'
import { postAPI } from '@/api'
jest.mock('@/api', () => ({ getAPI: jest.fn(), postAPI: jest.fn() }))
beforeEach(() => jest.clearAllMocks())
const context = () => ({ resource: { id: 'pool' }, path: ' image.qcow2 ', name: ' external ', offering: 'custom', loading: false, $t: key => key, $pollJob: jest.fn(), $notifyError: jest.fn(), $emit: jest.fn(), $router: { push: jest.fn() } })
test('storage registration uses the driver import API and stays on the form until the asynchronous job completes', async () => {
  const vm = context()
  postAPI.mockResolvedValue({ importvolumeresponse: { jobid: 'job' } })
  await Registration.methods.submit.call(vm)
  expect(postAPI).toHaveBeenCalledWith('importVolume', { storageid: 'pool', path: 'image.qcow2', name: 'external', diskofferingid: 'custom' })
  expect(vm.loading).toBe(true); expect(vm.$router.push).not.toHaveBeenCalled()
  vm.$pollJob.mock.calls[0][0].successMethod({ jobresult: { volume: { id: 'registered' } } })
  expect(vm.$router.push).toHaveBeenCalledWith('/volume/registered')
})
test('double submission is prevented while registration is pending', async () => {
  const vm = { ...context(), loading: true }
  await Registration.methods.submit.call(vm)
  expect(postAPI).not.toHaveBeenCalled()
})
test('failed registration leaves editable source settings for retry', async () => {
  const vm = context(); postAPI.mockRejectedValue(new Error('stored object locked'))
  await Registration.methods.submit.call(vm)
  expect(vm.loading).toBe(false); expect(vm.path).toBe(' image.qcow2 ')
  expect(vm.$notifyError).toHaveBeenCalled(); expect(vm.$router.push).not.toHaveBeenCalled()
})
