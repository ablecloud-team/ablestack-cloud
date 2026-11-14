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

import { templateTypeOptions, templateTypeError, sharedFsRegistrationParams } from '@/utils/templateTypePolicy'
const root = { operation: 'register', user: { roletype: 'Admin', account: 'admin', domainid: 'root' }, apis: { registerTemplate: {}, getUploadParamsForTemplate: {}, updateTemplate: {}, getSharedFileSystemComputeStatus: {} }, project: {}, hypervisor: 'KVM', zones: ['zone'], allZoneValue: 'all' }
const shared = c => templateTypeOptions(c).find(o => o.id === 'SHAREDFS')
test('URL creation exposes its type before prerequisite selection and enables it only with KVM and explicit Zones', () => {
  expect(shared({ ...root, hypervisor: undefined, zones: [] })).toMatchObject({ disabled: true, reason: 'message.template.type.sharedfs.zone' })
  expect(shared({ ...root, zones: [] })).toMatchObject({ disabled: true, reason: 'message.template.type.sharedfs.zone' })
  expect(shared(root).disabled).toBe(false)
  expect(shared({ ...root, zones: ['all'] })).toMatchObject({ disabled: true })
  expect(shared({ ...root, zones: ['-1'] })).toMatchObject({ disabled: true })
})
test.each(['User', 'DomainAdmin', 'ResourceAdmin'])('role %s never receives a Root-only registration option', roletype => {
  const c = { ...root, user: { ...root.user, roletype } }; expect(shared(c)).toBeUndefined(); expect(templateTypeError(c, 'SHAREDFS')).toBeTruthy()
})
test.each([{ project: { id: 'project' } }, { ownerDomainId: 'other' }, { ownerAccount: 'other' }, { apiParams: { templatetype: {} } }, { hypervisor: 'VMware' }])('ineligible context %j cannot submit SHAREDFS', change => {
  const c = { ...root, ...change }; expect(shared(c).disabled).toBe(true); expect(templateTypeError(c, 'SHAREDFS')).toBeTruthy()
})
test('local upload and existing non-SHAREDFS conversion are unavailable with an explicit registration path', () => {
  expect(shared({ ...root, operation: 'upload' })).toMatchObject({ disabled: true, reason: 'message.template.type.sharedfs.upload' })
  for (const sourceType of ['USER', 'VNF', 'SYSTEM'])expect(templateTypeError({ ...root, operation: 'update', sourceType }, 'SHAREDFS')).toBe('message.template.type.sharedfs.new.registration')
})
test('existing SHAREDFS retains its value after feature discovery withdrawal and rejects conversion', () => {
  const c = { ...root, operation: 'update', sourceType: 'SHAREDFS', apis: { updateTemplate: {} } }; expect(templateTypeOptions(c).map(o => o.id)).toEqual(['SHAREDFS']); expect(templateTypeError(c, 'SHAREDFS')).toBe(''); expect(templateTypeError(c, 'USER')).toContain('immutable')
})
test('API withdrawal and missing feature capability block a previously valid type at submission', () => {
  expect(templateTypeError({ ...root, apis: {} }, 'SHAREDFS')).toBeTruthy(); expect(shared({ ...root, apis: { registerTemplate: {} } })).toBeUndefined()
})
test('normal registration and generic/search callers preserve their previous type inventories', () => {
  expect(templateTypeError(root, undefined)).toBe(''); expect(templateTypeError({ ...root, operation: 'upload' }, 'USER')).toBe('')
  expect(templateTypeOptions({ ...root, operation: undefined, hypervisor: undefined }).map(o => o.id)).toEqual(['USER', 'VNF', 'SYSTEM', 'BUILTIN', 'ROUTING'])
  expect(templateTypeOptions({ ...root, operation: undefined, hypervisor: 'External' }).map(o => o.id)).toEqual(['USER'])
})
test('private downloaded profile parameters preserve the draft and use the displayed calling owner', () => {
  const params = { name: 'qa', zoneids: 'zone', account: 'admin', domainid: 'root', arch: 'aarch64', format: 'RAW', ispublic: true, isfeatured: true, isextractable: true, directdownload: true }
  expect(sharedFsRegistrationParams(params)).toMatchObject({ name: 'qa', zoneids: 'zone', templatetype: 'SHAREDFS', hypervisor: 'KVM', arch: 'x86_64', format: 'QCOW2', isdynamicallyscalable: true, ispublic: false, isfeatured: false, isextractable: false, directdownload: false })
  expect(sharedFsRegistrationParams(params)).not.toHaveProperty('account'); expect(params.arch).toBe('aarch64')
  expect(sharedFsRegistrationParams(params, { isdynamicallyscalable: {} })).not.toHaveProperty('directdownload')
})
