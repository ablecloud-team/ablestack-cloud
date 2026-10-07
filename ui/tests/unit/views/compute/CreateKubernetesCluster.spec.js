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

import { computed, reactive } from 'vue'
import { getAPI } from '@/api'
import CreateKubernetesCluster from '@/views/compute/CreateKubernetesCluster.vue'

jest.mock('@/api', () => ({ getAPI: jest.fn(), postAPI: jest.fn() }))
jest.mock('@views/compute/wizard/UserDataSelection', () => ({}), { virtual: true })

const state = admin => reactive({
  templates: [],
  templateLoading: false,
  isAdminOrDomainAdmin: () => admin
})
const response = templates => ({ listtemplatesresponse: { template: templates } })
const deferred = () => {
  let complete
  const promise = new Promise(resolve => { complete = resolve })
  return { promise, resolve: complete }
}
beforeEach(() => getAPI.mockReset())

test('a delayed CKS template response updates the reactive selection options', async () => {
  const wait = deferred()
  getAPI.mockReturnValue(wait.promise)
  const vm = state(true)
  const options = computed(() => vm.templates.map(t => t.name))
  const done = CreateKubernetesCluster.methods.fetchCksTemplates.call(vm)
  expect(options.value).toEqual([])
  expect(vm.templateLoading).toBe(true)
  wait.resolve(response([{ id: 'prepared', name: 'Private prepared node' }]))
  await done
  expect(options.value).toEqual(['Private prepared node'])
  expect(vm.templateLoading).toBe(false)
  expect(getAPI).toHaveBeenCalledWith('listTemplates', { templatefilter: 'all', forcks: true, isready: true })
})

test('user filter results remain loading until all responses arrive and duplicate templates are selectable once', async () => {
  const first = deferred()
  const last = deferred()
  getAPI.mockReturnValueOnce(first.promise).mockResolvedValueOnce(response([{ id: 'shared' }])).mockReturnValueOnce(last.promise)
  const vm = state(false)
  const done = CreateKubernetesCluster.methods.fetchCksTemplates.call(vm)
  first.resolve(response([{ id: 'private' }, { id: 'shared' }]))
  await Promise.resolve()
  expect(vm.templateLoading).toBe(true)
  last.resolve(response([{ id: 'shared' }, { id: 'public' }]))
  await done
  expect(vm.templates.map(t => t.id)).toEqual(['private', 'shared', 'public'])
  expect(getAPI.mock.calls.map(c => c[1].templatefilter)).toEqual(['self', 'featured', 'community'])
  expect(vm.templateLoading).toBe(false)
})

test('an empty successful response removes stale options and ends loading', async () => {
  getAPI.mockResolvedValue({ listtemplatesresponse: {} })
  const vm = state(true)
  vm.templates = [{ id: 'stale' }]
  await CreateKubernetesCluster.methods.fetchCksTemplates.call(vm)
  expect(vm.templates).toEqual([])
  expect(vm.templateLoading).toBe(false)
})

test('a rejected query does not leave stale templates or the loading spinner active', async () => {
  getAPI.mockRejectedValue(new Error('API unavailable'))
  const vm = state(true)
  vm.templates = [{ id: 'stale' }]
  await CreateKubernetesCluster.methods.fetchCksTemplates.call(vm)
  expect(vm.templates).toEqual([])
  expect(vm.templateLoading).toBe(false)
})
