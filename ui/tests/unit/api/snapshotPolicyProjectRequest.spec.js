// Licensed to the Apache Software Foundation (ASF) under one or more
// contributor license agreements. See NOTICE for copyright ownership.
// Licensed under the Apache License, Version 2.0 (the "License");
// http://www.apache.org/licenses/LICENSE-2.0 . Distributed AS IS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND.
import { axios as service } from '@/utils/request'
import { vueProps } from '@/vue-app'
jest.mock('@/router', () => ({ currentRoute: { value: { path: '/snapshotpolicy' } }, push: jest.fn() }))
jest.mock('@/store', () => ({ state: { user: {} }, getters: {}, commit: jest.fn(), dispatch: jest.fn() }))
jest.mock('@/locales', () => ({ i18n: { global: { t: k => k } } }))
jest.mock('@/vue-app', () => ({ vueProps: { $localStorage: { get: jest.fn() } } }))
const prepare = service.interceptors.request.handlers[0].fulfilled
const request = (method, args) => prepare(method === 'GET' ? { params: args } : { data: new URLSearchParams(args) })
const wire = (method, config) => method === 'GET' ? config.params : Object.fromEntries(config.data)
beforeEach(() => { vueProps.$localStorage.get.mockReturnValue({ id: 'active-project' }) })
test.each(['GET', 'POST'])('%s carries the policy personal-scope parameter instead of dropping it', method => {
  const params = wire(method, request(method, { command: 'listSnapshotPolicies', ignoreproject: true, id: 'policy' }))
  expect(String(params.ignoreproject)).toBe('true'); expect(params.projectid).toBeUndefined()
})
test.each(['GET', 'POST'])('%s retains an explicit policy project', method => {
  expect(wire(method, request(method, { command: 'listSnapshotPolicies', projectid: 'chosen' })).projectid).toBe('chosen')
})
test.each(['listVolumes', 'listCniConfiguration', 'listVirtualMachines'].flatMap(command => ['GET', 'POST'].map(method => [command, method])))('%s %s keeps the existing client-only suppression behavior', (command, method) => {
  const params = wire(method, request(method, { command, ignoreproject: true }))
  expect(params.ignoreproject).toBeUndefined(); expect(params.projectid).toBeUndefined()
})
