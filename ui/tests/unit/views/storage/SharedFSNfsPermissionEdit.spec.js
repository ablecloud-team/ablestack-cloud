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

import SharedFSTab from '@/views/storage/SharedFSTab'

const config = { readOnly: false, rootSquash: false, allSquash: false, sync: true, secure: false }
const makeContext = (original = config) => {
  const vm = {
    forms: { nfsExport: {} },
    actionModal: { type: 'editNfsExport', context: { raw: { id: 'export-a', name: 'nfs-a', path: '/export/nfs-a', volumeid: 'data-a', filesystem: 'ext4', config: JSON.stringify(original) } } },
    currentBackingVolumes: [{ id: 'data-a' }],
    capacityBytesToInput: () => ({ amount: null, unit: 'GiB' }),
    normalizeEndpointIps: () => [],
    nfsExportEndpointMode: () => 'LISTENER_GROUP',
    nfsExportListenerPorts: () => [2049],
    nfsRuntimeProtocolMode: () => 'V4_ONLY',
    clientVisibleName: name => name,
    boolValue: value => value === true || value === 'true',
    parseStorageConfig: text => JSON.parse(text || '{}'),
    selectedNfsListenerPorts: () => [2049],
    validateNfsExportNameAndPath: () => true,
    nfsExportImportMode: () => 'MOUNT_EXISTING',
    toCapacityBytes: () => undefined,
    runStorageAction: jest.fn(),
    $t: key => key
  }
  SharedFSTab.methods.populateNfsExportForm.call(vm, vm.actionModal.context.raw)
  return vm
}

describe('existing NFS export edit', () => {
  it('keeps missing configured POSIX values blank and sends only the requested read-only edit', () => {
    const vm = makeContext()
    expect(vm.forms.nfsExport).toMatchObject({ owneruid: null, ownergid: null, mode: '' })
    vm.forms.nfsExport.readonly = true
    SharedFSTab.methods.updateNfsExport.call(vm)
    const request = vm.runStorageAction.mock.calls[0][2]
    expect(request.readonly).toBe(true)
    for (const key of ['owneruid', 'ownergid', 'mode', 'recursivepermission']) expect(request).not.toHaveProperty(key)
  })
  it('does not send existing unchanged POSIX values on an ordinary update', () => {
    const vm = makeContext({ ...config, ownerUid: 0, ownerGid: 0, mode: '0755', recursivePermission: false })
    vm.forms.nfsExport.sync = false
    SharedFSTab.methods.updateNfsExport.call(vm)
    const request = vm.runStorageAction.mock.calls[0][2]
    expect(request.sync).toBe(false)
    for (const key of ['owneruid', 'ownergid', 'mode', 'recursivepermission']) expect(request).not.toHaveProperty(key)
  })
  it('forwards an explicit permission change so the existing server policy guard remains effective', () => {
    const vm = makeContext()
    vm.forms.nfsExport.mode = '0700'
    SharedFSTab.methods.updateNfsExport.call(vm)
    expect(vm.runStorageAction.mock.calls[0][2]).toMatchObject({ mode: '0700', posixpolicyid: undefined })
  })
  it('changes squash without silently applying the recommendation during edit', () => {
    const vm = makeContext()
    SharedFSTab.methods.updateNfsSquashOption.call(vm, 'rootsquash', true)
    expect(vm.forms.nfsExport).toMatchObject({ rootsquash: true, owneruid: null, ownergid: null, mode: '' })
  })
})
