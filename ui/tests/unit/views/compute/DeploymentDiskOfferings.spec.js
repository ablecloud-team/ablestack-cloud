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

import { getAPI } from '@/api'
import { fetchDeploymentDiskOfferings, deploymentDiskOfferingScope } from '@/utils/deploymentDiskOfferings'
import DeployVM from '@/views/compute/DeployVM.vue'
jest.mock('@/api', () => ({ getAPI: jest.fn(), postAPI: jest.fn() }))
jest.mock('@/store', () => ({ getters: {} }))
const context = () => ({
  $store: { getters: { userInfo: { id: 'user-A' } } },
  $t: key => key,
  params: { diskOfferings: { options: { zoneid: 'zone', account: 'account', domainid: 'domain', projectid: null, page: 3, pageSize: 10, keyword: 'old search' } } },
  options: { diskOfferings: [] },
  rowCount: {},
  loading: {},
  form: {},
  diskOfferingLookupScope: ''
})
const response = (rows, count = rows.length) => ({ listdiskofferingsresponse: { diskoffering: rows, count } })
beforeEach(() => jest.clearAllMocks())
test('parent loads every API page and retains scope while local search/page parameters stay out of API query', async () => {
  const vm = context()
  getAPI.mockImplementation((command, params) => Promise.resolve(response(Array.from({ length: params.page < 3 ? 100 : 5 }, (_, i) => ({ id: 'disk-' + ((params.page - 1) * 100 + i) })), 205)))
  const result = await DeployVM.methods.fetchOptions.call(vm, vm.params.diskOfferings, 'diskOfferings')
  expect(result.listdiskofferingsresponse.count).toBe(205)
  expect(vm.options.diskOfferings).toHaveLength(205)
  expect(vm.options.diskOfferings[204].id).toBe('disk-204')
  expect(getAPI).toHaveBeenCalledTimes(3)
  for (const [, params, opts] of getAPI.mock.calls) {
    expect(params).toMatchObject({ zoneid: 'zone', domainid: 'domain', account: 'account', projectid: null, listall: true, pagesize: 100 })
    expect(params).not.toHaveProperty('pageSize')
    expect(params).not.toHaveProperty('keyword')
    expect(opts.preserveOnFailure).toBe(true)
  }
})
test('a failed later page never publishes an incomplete candidate collection; same-scope retry preserves selection', async () => {
  const vm = context(); const old = [{ id: 'old' }]
  vm.options.diskOfferings = old; vm.form.diskofferingid = 'old'; vm.diskOffering = old[0]
  vm.diskOfferingLookupScope = deploymentDiskOfferingScope(vm.params.diskOfferings.options, vm.$store)
  getAPI.mockResolvedValueOnce(response([{ id: 'partial' }], 2)).mockRejectedValueOnce(new Error('page 2 unavailable'))
  await fetchDeploymentDiskOfferings(vm, vm.params.diskOfferings)
  expect(vm.options.diskOfferings).toBe(old)
  expect(vm.form.diskofferingid).toBe('old')
  expect(vm.diskOffering).toBe(old[0])
  expect(vm.diskOfferingsError).toContain('page 2 unavailable')
  expect(DeployVM.computed.diskPlanIncomplete.call(vm)).toBe(true)
  getAPI.mockResolvedValue(response(old))
  await fetchDeploymentDiskOfferings(vm, vm.params.diskOfferings)
  expect(vm.diskOfferingsError).toBe('')
  expect(vm.loading.diskOfferings).toBe(false)
  expect(vm.form.diskofferingid).toBe('old')
})
test('late owner response cannot overwrite current owner candidates or release its loading state', async () => {
  const vm = context(); let resolveOld
  getAPI.mockImplementationOnce(() => new Promise(resolve => { resolveOld = resolve })).mockResolvedValueOnce(response([{ id: 'current' }]))
  const previous = fetchDeploymentDiskOfferings(vm, vm.params.diskOfferings)
  vm.form.diskofferingid = 'foreign'; vm.diskOffering = { id: 'foreign' }
  vm.params.diskOfferings.options.account = 'owner-B'
  const current = fetchDeploymentDiskOfferings(vm, vm.params.diskOfferings)
  expect(vm.form.diskofferingid).toBeUndefined()
  expect(vm.diskOffering).toBe(null)
  await current; resolveOld(response([{ id: 'foreign' }]))
  await previous
  expect(vm.options.diskOfferings).toEqual([{ id: 'current' }])
  expect(vm.loading.diskOfferings).toBe(false)
})
test('an empty successful response clears candidates and count rather than preserving a stale page', async () => {
  const vm = context()
  getAPI.mockResolvedValue(response([]))
  await fetchDeploymentDiskOfferings(vm, vm.params.diskOfferings)
  expect(vm.options.diskOfferings).toEqual([])
  expect(vm.rowCount.diskOfferings).toBe(0)
  expect(vm.diskOfferingsError).toBe('')
})
test('an unmounted wizard ignores a completed response', async () => {
  const vm = context(); let finish
  getAPI.mockImplementation(() => new Promise(resolve => { finish = resolve }))
  const request = fetchDeploymentDiskOfferings(vm, vm.params.diskOfferings)
  vm.$ = { isUnmounted: true }; finish(response([{ id: 'ignored' }]))
  await request
  expect(vm.options.diskOfferings).toEqual([])
})
test('deployment stays blocked during lookup or after a lookup failure', () => {
  expect(DeployVM.computed.diskPlanIncomplete.call({ loading: { diskOfferings: true } })).toBe(true)
  expect(DeployVM.computed.diskPlanIncomplete.call({ diskOfferingsError: 'Failed' })).toBe(true)
})
