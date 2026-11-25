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

import ScopedSettingsTab from '@/views/setting/ScopedSettingsTab.vue'

test('waits for the new resource when moving between infrastructure settings tabs', () => {
  const route = { meta: { name: 'imagestore' }, params: { id: 'image-id' } }
  const scope = ScopedSettingsTab.computed.scope
  expect(scope.call({ scopeType: 'imagestore', resource: { id: 'zone-id' }, $route: route })).toEqual({ type: 'imagestore', id: null })
  expect(scope.call({ scopeType: 'imagestore', resource: { id: 'image-id' }, $route: route })).toEqual({ type: 'imagestore', id: 'image-id' })
})

test.each(['/domain', '/domain/'])('uses the selected domain on the tree route %s', path => {
  const route = { meta: { name: 'domain' }, path, params: {} }
  const scope = ScopedSettingsTab.computed.scope
  expect(scope.call({ scopeType: 'domain', resource: {}, $route: route })).toEqual({ type: 'domain', id: null })
  expect(scope.call({ scopeType: 'domain', resource: { id: 'child-id' }, $route: route })).toEqual({ type: 'domain', id: 'child-id' })
})

test.each(['account', 'domain'])('matches the detail route before loading %s settings', type => {
  const route = { meta: { name: type }, path: '/' + type + '/new-id', params: { id: 'new-id' } }
  const scope = ScopedSettingsTab.computed.scope
  expect(scope.call({ scopeType: type, resource: { id: 'old-id' }, $route: route })).toEqual({ type, id: null })
  expect(scope.call({ scopeType: type, resource: { id: 'new-id' }, $route: route })).toEqual({ type, id: 'new-id' })
})

test('does not use a departing account record as a domain tree selection', () => {
  const route = { meta: { name: 'domain' }, path: '/domain', params: {} }
  const scope = ScopedSettingsTab.computed.scope
  expect(scope.call({ scopeType: 'account', resource: { id: 'account-id' }, $route: route })).toEqual({
    type: 'account',
    id: null
  })
})

test('retains the active search and dialog when the same resource is updated', () => {
  const domain = { type: 'domain', id: 'child-id' }
  const dialog = { mode: 'edit' }
  const tab = { search: 'timeout', dialog, refresh: jest.fn() }
  ScopedSettingsTab.watch.scope.handler.call(tab, { ...domain }, domain)
  expect(tab.search).toBe('timeout')
  expect(tab.dialog).toBe(dialog)
  expect(tab.refresh).not.toHaveBeenCalled()
})
