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

import Autogen from '@/views/AutogenView'

const route = (id, query = {}) => ({ path: `/buckets/${id}`, fullPath: `/buckets/${id}?${new URLSearchParams(query)}`, params: { id }, query, meta: { name: 'buckets' } })
const context = () => ({ resetSelection: jest.fn(), clearAutoRefresh: jest.fn(), fetchData: jest.fn(), scheduleAutoRefresh: jest.fn(), switchProject: jest.fn(), showAction: false, page: 9 })

test('does not refetch bucket metadata for its own browser path normalization or folder query', () => {
  const vm = context()
  Autogen.watch.$route.call(vm, route('A', { tab: 'browser', browserPath: '' }), route('A', { tab: 'browser' }))
  Autogen.watch.$route.call(vm, route('A', { tab: 'browser', browserPath: 'folder/' }), route('A', { tab: 'browser', browserPath: '' }))
  expect(vm.fetchData).not.toHaveBeenCalled()
})

test('fetches fresh bucket metadata when a browser URL changes bucket id', () => {
  const vm = context()
  Autogen.watch.$route.call(vm, route('B', { tab: 'browser' }), route('A', { tab: 'browser' }))
  expect(vm.resetSelection).toHaveBeenCalledTimes(1)
  expect(vm.fetchData).toHaveBeenCalledTimes(1)
  expect(vm.scheduleAutoRefresh).toHaveBeenCalledTimes(1)
  expect(vm.page).toBe(1)
})

test('preserves normal detail navigation metadata refresh', () => {
  const vm = context()
  Autogen.watch.$route.call(vm, route('A', { tab: 'details' }), route('A', { tab: 'browser' }))
  expect(vm.fetchData).toHaveBeenCalledTimes(1)
})
