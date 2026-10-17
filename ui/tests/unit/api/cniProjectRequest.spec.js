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

import { axios as service } from '@/utils/request'
import { vueProps } from '@/vue-app'
import { cniLibraryParams } from '@/utils/userDataContent'
jest.mock('@/router', () => ({ currentRoute: { value: { path: '/cniconfiguration', fullPath: '/cniconfiguration' } }, push: jest.fn() }))
jest.mock('@/store', () => ({ state: { user: {} }, getters: {}, commit: jest.fn(), dispatch: jest.fn() }))
jest.mock('@/locales', () => ({ i18n: { global: { t: k => k } } }))
jest.mock('@/vue-app', () => ({ vueProps: { $localStorage: { get: jest.fn() } } }))
const projectid = '12345678-1234-1234-1234-123456789abc'
const prepare = service.interceptors.request.handlers[0].fulfilled
beforeEach(() => { vueProps.$localStorage.get.mockReturnValue({ id: projectid }) })
test.each(['listCniConfiguration', 'listUserData', 'listVirtualMachines'].flatMap(command => ['GET', 'POST'].map(method => [command, method])))('%s %s retains the selected project through the common interceptor', (command, method) => {
  const params = command === 'listCniConfiguration' ? cniLibraryParams({ keyword: 'part', sortkey: 'name', page: 1, pagesize: 20 }) : { keyword: 'part', page: 1, pagesize: 20 }
  const config = prepare(method === 'GET' ? { params: { command, ...params } } : { data: new URLSearchParams({ command, ...params }) })
  const wire = method === 'GET' ? config.params : Object.fromEntries(config.data)
  expect(wire).toMatchObject({ command, keyword: 'part', projectid }); expect(wire.sortkey).toBeUndefined()
})
test.each(['-1', 'other-project'])('explicit project %s takes precedence over the active project', explicit => {
  const config = prepare({ params: { command: 'listCniConfiguration', ...cniLibraryParams({ projectid: explicit }) } })
  expect(config.params.projectid).toBe(explicit)
})
test('basic view without an active project does not inject an unrelated project', () => {
  vueProps.$localStorage.get.mockReturnValue({}); const config = prepare({ params: { command: 'listCniConfiguration', ...cniLibraryParams({ keyword: 'part' }) } }); expect(config.params.projectid).toBeUndefined()
})
