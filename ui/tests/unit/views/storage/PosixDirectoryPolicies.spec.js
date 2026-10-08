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
import { getAPI, postAPI } from '@/api'
jest.mock('@/api', () => ({ getAPI: jest.fn(), postAPI: jest.fn() }))

describe('Common POSIX directory policy editor', () => {
  beforeEach(() => { getAPI.mockReset(); postAPI.mockReset() })
  it('shows the API validation reason without dumping the signed approval token', async () => {
    postAPI.mockRejectedValue({ message: 'Request failed with status code 431', response: { data: { createstorageposixdirectorypolicyresponse: { errortext: 'Approval token exceeds the allowed input limit', previewtoken: 'private-token' } } } })
    await expect(Widget.methods.resolved.call({}, 'createStoragePosixDirectoryPolicy', {})).rejects.toThrow('Approval token exceeds the allowed input limit')
    expect(getAPI).not.toHaveBeenCalled()
  })
  it('shows the policy actually approved for reapply instead of unrelated NFS recommendations', () => {
    const vm = { preview: { config: JSON.stringify({ applyOwner: true, ownerUid: 0, ownerGid: 0, directoryMode: '0770' }) }, previewCurrent: { uid: 0, gid: 0, mode: '0770' }, previewV2: { suggested: { applyowner: true, owneruid: 65534, ownergid: 65534, mode: '0775' } } }
    expect(Widget.computed.previewTarget.call(vm)).toEqual({ uid: 0, gid: 0, mode: '0770' })
  })
  it('shows observed owners when the signed policy preserves ownership', () => {
    const vm = { preview: { config: { applyOwner: false, ownerUid: 0, ownerGid: 0, directoryMode: '2775' } }, previewCurrent: { uid: 1002, gid: 1003, mode: '0770' } }
    expect(Widget.computed.previewTarget.call(vm)).toEqual({ uid: 1002, gid: 1003, mode: '2775' })
  })
  it('does not substitute unapproved draft values for a missing policy in the preview', () => {
    const vm = { preview: {}, form: { owneruid: 0, ownergid: 0, directorymode: '0777' }, previewCurrent: { uid: 1002, gid: 1003, mode: '0750' } }
    expect(Widget.computed.previewTarget.call(vm)).toEqual({ uid: 1002, gid: 1003, mode: '0750' })
  })
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
