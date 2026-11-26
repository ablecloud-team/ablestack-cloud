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

import { initialListLocation, listRouteQuery } from '@/utils/listNavigation'
import { restoreImportOptions } from '@/utils/importOptionNavigation'

const route = (name, api, query = {}) => ({ path: '/' + name, meta: { name, permission: [api] }, params: {}, query, hash: '' })
const getters = {
  userInfo: { id: 'account', roletype: 'Admin' },
  apis: { listTemplates: {}, listKubernetesSupportedVersions: {}, listSnapshotPolicies: {} },
  defaultListViewPageSize: 50
}

test('default list navigation and its first read share the same normalized query', () => {
  const destination = route('kubernetesiso', 'listKubernetesSupportedVersions')
  const location = initialListLocation(destination, getters, 'session')
  expect(location.query).toMatchObject({ page: '1', pagesize: '50' })
  expect(location.query.kisoowner).toMatch(/^[a-f0-9]{64}$/)
  expect(listRouteQuery({ ...destination, query: location.query }, 50, getters, 'session')).toEqual(location.query)
})

test('only the permitted list entry without explicit query defaults gets redirected', () => {
  const destination = route('template', 'listTemplates')
  expect(initialListLocation(destination, getters, 'session').query).toEqual({ page: '1', pagesize: '50' })
  expect(initialListLocation({ ...destination, query: { keyword: 'keep' } }, getters, 'session')).toBeUndefined()
  expect(initialListLocation({ ...destination, params: { id: 'resource' } }, getters, 'session')).toBeUndefined()
  expect(initialListLocation(destination, { ...getters, apis: {} }, 'session')).toBeUndefined()
  expect(initialListLocation(destination, getters, '')).toBeUndefined()
})

test('import option defaults are committed once before the dependent list read', async () => {
  const old = { fullPath: '/managevolumes', path: '/managevolumes', query: {}, hash: '' }
  const target = { fullPath: '/managevolumes?zoneid=z&poolid=p' }
  const router = { currentRoute: { value: old }, resolve: jest.fn(() => target), replace: jest.fn(async next => { router.currentRoute.value = next }) }
  const read = jest.fn(() => expect(router.currentRoute.value).toBe(target))
  await restoreImportOptions(router, async initial => {
    initial.query.zoneid = 'z'
    initial.query.poolid = 'p'
    initial.read = read
    expect(router.currentRoute.value).toBe(old)
  })
  expect(router.resolve.mock.calls[0][0].query).toEqual({ zoneid: 'z', poolid: 'p' })
  expect(router.replace).toHaveBeenCalledTimes(1)
  expect(read).toHaveBeenCalledTimes(1)
})

test('the previous import selection cannot rewrite a newer navigation', async () => {
  const old = { fullPath: '/managevolumes', path: '/managevolumes', query: {}, hash: '' }
  const router = { currentRoute: { value: old }, resolve: jest.fn(), replace: jest.fn() }
  const read = jest.fn()
  await restoreImportOptions(router, async initial => {
    initial.read = read
    router.currentRoute.value = { fullPath: '/vm' }
  })
  expect(router.replace).not.toHaveBeenCalled()
  expect(read).not.toHaveBeenCalled()
})
