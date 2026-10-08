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

import CreateSharedFS from '@/views/storage/CreateSharedFS'
import { getAPI } from '@/api'
jest.mock('@/api', () => ({ getAPI: jest.fn(), postAPI: jest.fn() }))

const system = (extra = {}) => ({ id: 'system-a', name: 'system', templatetype: 'SYSTEM', isready: true, isdynamicallyscalable: true, hypervisor: 'KVM', arch: 'x86_64', zoneid: 'zone-a', ...extra })
const context = () => ({
  selectedZone: { id: 'zone-a' },
  owner: { domainid: 'domain-a', account: 'admin' },
  $store: { getters: { userInfo: { roletype: 'Admin' }, apis: { listTemplates: {} } } },
  $t: key => key,
  hasTemplateSelection: true,
  form: { templateid: '' },
  systemTemplates: [],
  templateRequestToken: 0,
  templateLoading: false,
  templateReadError: false,
  templateOwnerScope: CreateSharedFS.methods.templateOwnerScope,
  isSelectableSystemTemplate: CreateSharedFS.methods.isSelectableSystemTemplate,
  hasFormValue: CreateSharedFS.methods.hasFormValue,
  cleanParams: CreateSharedFS.methods.cleanParams,
  createSharedFsSize: () => undefined,
  isCustomizedDiskIOps: false,
  isStaticNetwork: false
})
const values = { name: 'sharedfs', zoneid: 'zone-a', serviceofferingid: 'so', diskofferingid: 'do', networkid: 'network', filesystem: 'XFS' }
const response = items => ({ listtemplatesresponse: { template: items } })
const build = (vm, fields = {}) => CreateSharedFS.methods.buildCreateSharedFsRequest.call(vm, { ...values, ...fields })

describe('SharedFS explicit SYSTEM template selection', () => {
  beforeEach(() => getAPI.mockReset())
  it('keeps the old unspecified default and never forwards internal artifact references', () => {
    const request = build(context(), { validationartifactuuid: 'internal-id', validationartifactsha256: 'internal-sha' })
    expect(request.templateid).toBeUndefined()
    expect(request.validationartifactuuid).toBeUndefined()
    expect(request.validationartifactsha256).toBeUndefined()
  })
  it('sends exactly the selected eligible template without internal foundation parameters', () => {
    const vm = context(); vm.systemTemplates = [system()]
    const request = build(vm, { templateid: 'system-a', validationartifactuuid: 'internal-id' })
    expect(request.templateid).toBe('system-a')
    expect(request.validationartifactuuid).toBeUndefined()
  })
  it('retains the explicit selection when the generic field filter omits it', () => {
    const vm = context(); vm.form.templateid = 'system-a'; vm.systemTemplates = [system()]
    expect(build(vm).templateid).toBe('system-a')
    expect(build(vm, { templateid: '' }).templateid).toBeUndefined()
  })
  it('rejects USER, not-ready, non-scalable, wrong-zone, wrong-hypervisor and wrong-architecture rows', () => {
    for (const extra of [{ templatetype: 'USER' }, { isready: false }, { isready: 'true' }, { isdynamicallyscalable: false }, { zoneid: 'zone-b' }, { hypervisor: 'VMware' }, { arch: 'aarch64' }]) {
      const vm = context(); vm.systemTemplates = [system(extra)]
      expect(() => build(vm, { templateid: 'system-a' })).toThrow('message.storage.template.read.failed')
    }
  })
  it('does not make a malformed listing identifier selectable', () => {
    for (const id of [null, 1, '', ' ']) expect(CreateSharedFS.methods.isSelectableSystemTemplate.call({}, system({ id }), 'zone-a')).toBe(false)
  })
  it('does not send a selection whose catalog is loading or failed', () => {
    for (const extra of [{ templateLoading: true }, { templateReadError: true }, { hasTemplateSelection: false }]) {
      const vm = { ...context(), ...extra, systemTemplates: [system()] }
      expect(() => build(vm, { templateid: 'system-a' })).toThrow('message.storage.template.read.failed')
    }
  })
  it('validates an explicit field and leaves an empty default legal', async () => {
    const vm = context(); vm.systemTemplates = [system()]
    await expect(CreateSharedFS.methods.validateSystemTemplate.call(vm, null, '')).resolves.toBeUndefined()
    await expect(CreateSharedFS.methods.validateSystemTemplate.call(vm, null, 'system-a')).resolves.toBeUndefined()
    await expect(CreateSharedFS.methods.validateSystemTemplate.call(vm, null, 'missing')).rejects.toBe('message.storage.template.read.failed')
  })
  it('filters a mixed admin response instead of treating system=true as a trusted category', async () => {
    getAPI.mockResolvedValue(response([system(), system({ id: 'user', templatetype: 'USER' }), system({ id: 'late', isready: false })]))
    const vm = context(); await CreateSharedFS.methods.fetchSystemTemplates.call(vm)
    expect(vm.systemTemplates.map(item => item.id)).toEqual(['system-a'])
    expect(getAPI.mock.calls[0][1]).toEqual({ zoneid: 'zone-a', hypervisor: 'KVM', system: true, templatefilter: 'all', listall: true, account: 'admin', domainid: 'domain-a' })
  })
  it('uses executable scope for regular users and keeps project ownership explicit', async () => {
    getAPI.mockResolvedValue(response([]))
    const vm = context(); vm.$store.getters.userInfo.roletype = 'User'; vm.owner = { projectid: 'project' }
    await CreateSharedFS.methods.fetchSystemTemplates.call(vm)
    expect(getAPI.mock.calls[0][1]).toEqual({ zoneid: 'zone-a', hypervisor: 'KVM', system: true, templatefilter: 'executable', projectid: 'project' })
  })
  it('ignores a stale previous-zone response', async () => {
    let finish
    getAPI.mockImplementation((command, params) => params.zoneid === 'zone-a'
      ? new Promise(resolve => { finish = resolve }) : Promise.resolve(response([system({ id: 'zone-b-system', zoneid: 'zone-b' })])))
    const vm = context(); const pending = CreateSharedFS.methods.fetchSystemTemplates.call(vm)
    vm.selectedZone = { id: 'zone-b' }; await CreateSharedFS.methods.fetchSystemTemplates.call(vm)
    finish(response([system()]))
    await pending
    expect(vm.systemTemplates.map(item => item.id)).toEqual(['zone-b-system'])
  })
  it('ignores an owner-changed response even if the zone did not change', async () => {
    let finish
    getAPI.mockImplementation(() => new Promise(resolve => { finish = resolve }))
    const vm = context(); const pending = CreateSharedFS.methods.fetchSystemTemplates.call(vm)
    vm.owner = { domainid: 'other', account: 'other' }; finish(response([system()])); await pending
    expect(vm.systemTemplates).toEqual([])
  })
  it('drops an unmounted dialog response', async () => {
    let finish
    getAPI.mockImplementation(() => new Promise(resolve => { finish = resolve }))
    const vm = { ...context(), serviceOfferingRequestToken: 0 }; const pending = CreateSharedFS.methods.fetchSystemTemplates.call(vm)
    CreateSharedFS.beforeUnmount.call(vm); finish(response([system()])); await pending
    expect(vm.systemTemplates).toEqual([])
  })
  it('preserves an explicit choice but blocks it when catalog loading fails', async () => {
    getAPI.mockRejectedValue(new Error('not authorized'))
    const vm = context(); vm.form.templateid = 'system-a'; await CreateSharedFS.methods.fetchSystemTemplates.call(vm)
    expect(vm.templateReadError).toBe(true); expect(vm.form.templateid).toBe('system-a')
    expect(() => build(vm)).toThrow('message.storage.template.read.failed')
  })
  it('makes no catalog request when the deployed API lacks the optional field', async () => {
    const vm = context(); vm.hasTemplateSelection = false
    await CreateSharedFS.methods.fetchSystemTemplates.call(vm)
    expect(getAPI).not.toHaveBeenCalled()
    expect(build(vm).templateid).toBeUndefined()
  })
})
