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

import Notes from '@/components/view/AnnotationsTab.vue'
import { postAPI } from '@/api'
jest.mock('@/api', () => ({ getAPI: jest.fn(), postAPI: jest.fn() }))
let vm
const deferred = () => { let release, fail; const promise = new Promise((resolve, reject) => { release = resolve; fail = reject }); return { promise, resolve: release, reject: fail } }
beforeEach(() => {
  postAPI.mockReset()
  vm = { ...Notes.methods, annotationReady: true, resource: { id: 'A' }, annotationType: 'USER_DATA', annotation: 'draft', annotationAdminsOnly: true, annotationVersion: 0, annotationSaving: false, notes: [1], itemCount: 1, page: 2, $store: { state: { user: { token: 'session' } }, getters: { userInfo: { id: 'user' }, apis: { listAnnotations: {}, addAnnotation: {} }, project: {} } }, $notifyError: jest.fn(), getAnnotations: jest.fn() }
  Object.defineProperty(vm, 'annotationContext', { get: () => Notes.computed.annotationContext.call(vm) })
})
test('failure retains the draft and visibility, and displays a real failure without inventing success', async () => {
  const error = new Error('503'); postAPI.mockRejectedValue(error); await Notes.methods.saveNote.call(vm)
  expect(vm.annotation).toBe('draft'); expect(vm.annotationAdminsOnly).toBe(true); expect(vm.annotationSaving).toBe(false); expect(vm.$notifyError).toHaveBeenCalledWith(error); expect(vm.getAnnotations).toHaveBeenCalledTimes(1)
})
test('successful submission clears only the submitted snapshot and resets the controlled visibility checkbox', async () => {
  postAPI.mockResolvedValue({}); await Notes.methods.saveNote.call(vm)
  expect(postAPI).toHaveBeenCalledWith('addAnnotation', { entityid: 'A', entitytype: 'USER_DATA', annotation: 'draft', adminsonly: true }, { preserveOnFailure: true, timeout: 20000 })
  expect(vm.annotation).toBe(''); expect(vm.annotationAdminsOnly).toBe(false)
})
test('new typing during a pending success is not discarded; duplicate sends share a lock', async () => {
  const wait = deferred(); postAPI.mockReturnValue(wait.promise); const pending = Notes.methods.saveNote.call(vm)
  await Notes.methods.saveNote.call(vm); expect(postAPI).toHaveBeenCalledTimes(1); vm.annotation = 'next draft'; wait.resolve({}); await pending; expect(vm.annotation).toBe('next draft')
})
test.each(['resource', 'user', 'project', 'session', 'unmount'])('late %s completion never clears or reports against the new context', async kind => {
  const wait = deferred(); postAPI.mockReturnValue(wait.promise); const pending = Notes.methods.saveNote.call(vm)
  if (kind === 'resource') vm.resource.id = 'B'
  if (kind === 'user') vm.$store.getters.userInfo.id = 'other'
  if (kind === 'project') vm.$store.getters.project.id = 'other'
  if (kind === 'session') vm.$store.state.user.token = 'other'
  if (kind === 'unmount') vm.listRefreshDisposed = true
  Notes.watch.annotationContext.call(vm); vm.annotation = 'new scope'; wait.reject(new Error('old failure')); await pending
  expect(vm.annotation).toBe('new scope'); expect(vm.notes).toEqual([]); expect(vm.page).toBe(1); expect(vm.$notifyError).not.toHaveBeenCalled(); expect(vm.getAnnotations).toHaveBeenCalledTimes(kind === 'unmount' ? 0 : 1)
})
