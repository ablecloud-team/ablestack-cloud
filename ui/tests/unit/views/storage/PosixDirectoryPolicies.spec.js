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

import Widget from '@/views/storage/PosixDirectoryPolicies'
import { getAPI } from '@/api'
jest.mock('@/api', () => ({ getAPI: jest.fn(), postAPI: jest.fn() }))

describe('Common POSIX directory policy editor', () => {
  beforeEach(() => getAPI.mockReset())
  it('preserves known policies when a read fails', async () => {
    const vm = { instanceId: 'a', generation: 0, policies: [{ id: 'known' }], loading: false, readError: false }
    getAPI.mockRejectedValue(new Error('timeout'))
    await Widget.methods.refresh.call(vm)
    expect(vm.policies).toEqual([{ id: 'known' }]); expect(vm.readError).toBe(true)
  })
  it('ignores delayed responses from a previous instance', async () => {
    let complete
    getAPI.mockImplementation(() => new Promise(resolve => { complete = resolve }))
    const vm = { instanceId: 'a', generation: 0, policies: [{ id: 'known' }] }
    const pending = Widget.methods.refresh.call(vm); vm.instanceId = 'b'; vm.generation++
    complete({ liststorageposixdirectorypoliciesresponse: { storageposixdirectorypolicy: [{ id: 'old' }] } })
    await pending; expect(vm.policies).toEqual([{ id: 'known' }])
  })
  it('does not save when preview no longer matches edited input', async () => {
    const vm = { preview: {}, previewToken: 'before', formToken: 'after', resolved: jest.fn(), validPreview: () => false }
    await Widget.methods.save.call(vm); expect(vm.resolved).not.toHaveBeenCalled()
  })
  it('keeps recursive changes disabled and exposes owner change only when explicit', () => {
    const vm = { editId: '', instanceId: 'a', form: { volumeid: 'v', relativepath: 'shared', owneruid: 0, ownergid: 0, applyowner: false, directorymode: '2775', access: [], defaults: [] } }
    const values = Widget.methods.params.call(vm)
    expect(values.recursive).toBe(false); expect(values.owneruid).toBeUndefined(); expect(values.ownergid).toBeUndefined()
  })
  it('requires explicit confirmation, unexpired signed preview and unchanged form', () => {
    const vm = { previewV2: { schemaVersion: 2, previewToken: 'server-bound-token', expiresAt: Date.now() + 60000 }, previewToken: 'same-form', formToken: 'same-form', confirmed: false }
    expect(Widget.methods.validPreview.call(vm)).toBe(false)
    vm.confirmed = true; expect(Widget.methods.validPreview.call(vm)).toBe(true)
    vm.formToken = 'changed'; expect(Widget.methods.validPreview.call(vm)).toBe(false)
    vm.formToken = 'same-form'; vm.previewV2.expiresAt = Date.now() - 1; expect(Widget.methods.validPreview.call(vm)).toBe(false)
  })
  it('only persists a policy with the server token and confirmed input', async () => {
    const vm = { instanceId: 'same', previewGeneration: 0, validPreview: () => true, editorCommand: 'updateStoragePosixDirectoryPolicy', params: () => ({ id: 'policy' }), previewV2: { previewToken: 'server-token' }, resolved: jest.fn().mockResolvedValue({}), clearPreview: jest.fn(), refresh: jest.fn(), $emit: jest.fn() }
    await Widget.methods.save.call(vm)
    expect(vm.resolved).toHaveBeenCalledWith('updateStoragePosixDirectoryPolicy', { id: 'policy', previewtoken: 'server-token', applyconfirmation: true })
  })
  it('updates setgid without changing remaining permission bits', () => {
    const vm = { form: { directorymode: '0775' }, clearPreview: jest.fn() }
    Widget.methods.setGroupInheritance.call(vm, true); expect(vm.form.directorymode).toBe('2775')
    Widget.methods.setGroupInheritance.call(vm, false); expect(vm.form.directorymode).toBe('0775')
  })
})
