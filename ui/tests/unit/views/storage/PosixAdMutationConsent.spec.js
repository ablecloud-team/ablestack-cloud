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
const id = '11111111-1111-4111-8111-111111111111'
const receipt = (extra = {}) => ({ success: true, sideEffects: false, joinState: 'JOINED', domain: 'example.local', realm: 'EXAMPLE.LOCAL', scope: { instanceUuid: id, operationUuid: '33333333-3333-4333-8333-333333333333', revision: 4 }, bootId: '22222222-2222-4222-8222-222222222222', generatedEpoch: Date.now() / 1000, trustVerified: true, identityVerified: true, dnsAliasesVerified: true, adSpnsVerified: true, adIdentity: true, ...extra })
const response = proof => ({ liststorageservicedomainstatusresponse: { storageidentitydomain: [{ instanceid: id, domainname: 'example.local', joinstate: 'JOINED', healthstate: 'OK', identityreceipt: proof }] } })
const context = (joined = true) => {
  const vm = {
    instanceId: id,
    instanceName: 'owned-service',
    domainStatus: joined ? { instanceid: id, domainname: 'example.local', joinstate: 'JOINED' } : {},
    adMutationConsent: { visible: false },
    adDisposed: false,
    $t: key => key,
    $getApiParams: () => ({ fresh: {}, admaintenancewindow: {}, adconfirmation: {} }),
    validPreview: () => true,
    previewV2: { previewToken: 'reviewed-preview' }
  }
  for (const name of ['resolved', 'requestAdMutationApproval', 'approveAdMutation', 'cancelAdMutation']) vm[name] = Widget.methods[name]
  vm.requestAdMutationApproval = jest.fn(async (instance, title, scope) => ({ scope, maintenancewindow: true, confirmation: instance.name }))
  return vm
}
const execute = (vm, preview = false, command = 'applyStoragePosixDirectoryPolicy') => vm.resolved(command, { id: 'own-policy', expectedpolicyrevision: 2, ...(preview ? { preview: true } : { previewtoken: 'reviewed-preview', applyconfirmation: true }) })

describe('POSIX preview and joined actual apply consent boundary', () => {
  beforeEach(() => { getAPI.mockReset().mockResolvedValue(response(receipt())); postAPI.mockReset().mockImplementation(async command => ({ [command.toLowerCase() + 'response']: { storageposixdirectorypolicy: { id: 'own-policy' } } })) })

  it('does not add approval fields or fresh reads to preview:true, even on a joined service', async () => {
    const vm = context(); await execute(vm, true)
    expect(getAPI).not.toHaveBeenCalled(); expect(vm.requestAdMutationApproval).not.toHaveBeenCalled()
    expect(postAPI.mock.calls[0][1]).toEqual({ id: 'own-policy', expectedpolicyrevision: 2, preview: true })
  })

  it('preserves ordinary non-AD actual apply parameters without a new dialog', async () => {
    const vm = context(false); await execute(vm)
    expect(getAPI).not.toHaveBeenCalled(); expect(vm.requestAdMutationApproval).not.toHaveBeenCalled()
    expect(postAPI.mock.calls[0][1]).not.toHaveProperty('admaintenancewindow')
  })

  it('adds explicit AD consent only after two fresh exact-instance observations', async () => {
    const vm = context(); await execute(vm)
    expect(getAPI).toHaveBeenCalledTimes(2)
    expect(postAPI.mock.calls[0][1]).toMatchObject({ admaintenancewindow: true, adconfirmation: 'owned-service', expectedrevision: 4, expectedpolicyrevision: 2, previewtoken: 'reviewed-preview' })
    expect(vm.adMutationConsent.resolve).toBeNull()
  })

  it('also requires consent for metadata deletion through the same actual writer boundary', async () => {
    const vm = context(); await vm.resolved('deleteStoragePosixDirectoryPolicy', { id: 'own-policy', expectedpolicyrevision: 2 })
    expect(vm.requestAdMutationApproval).toHaveBeenCalledTimes(1)
    expect(postAPI.mock.calls[0][1]).toMatchObject({ admaintenancewindow: true, adconfirmation: 'owned-service' })
  })

  it('blocks a foreign explicit instance or stale desired revision before actual apply', async () => {
    for (const extra of [{ instanceid: 'foreign' }, { expectedrevision: 3 }]) {
      const vm = context(); await expect(vm.resolved('applyStoragePosixDirectoryPolicy', { id: 'own-policy', ...extra })).rejects.toThrow()
      expect(postAPI).not.toHaveBeenCalled()
    }
  })

  it('does not use a different name or unsupported API metadata as approval', async () => {
    for (const change of [vm => { vm.instanceName = '' }, vm => { vm.$getApiParams = () => ({}) }, vm => { vm.domainStatus.instanceid = 'foreign' }]) {
      const vm = context(); change(vm); await expect(execute(vm)).rejects.toThrow()
      expect(postAPI).not.toHaveBeenCalled()
    }
  })

  it('rejects missing, stale and malformed receipts without config fallback', async () => {
    for (const proof of [undefined, receipt({ generatedEpoch: Date.now() / 1000 - 61 }), receipt({ trustVerified: 'true' })]) {
      getAPI.mockResolvedValue(response(proof)); await expect(execute(context())).rejects.toThrow()
      expect(postAPI).not.toHaveBeenCalled()
    }
  })

  it('does not POST after cancel, changed name or dispose during approval', async () => {
    for (const change of [vm => null, vm => { vm.instanceName = 'foreign'; return {} }, vm => { vm.adDisposed = true; return {} }]) {
      const vm = context(); vm.requestAdMutationApproval.mockImplementation(async () => change(vm)); await expect(execute(vm)).rejects.toThrow()
      expect(postAPI).not.toHaveBeenCalled()
    }
  })

  it('rechecks the signed preview immediately before the actual request', async () => {
    const vm = context(); vm.requestAdMutationApproval.mockImplementation(async (instance, title, scope) => { vm.validPreview = () => false; return { scope, maintenancewindow: true, confirmation: instance.name } })
    await expect(execute(vm)).rejects.toThrow(); expect(postAPI).not.toHaveBeenCalled()
  })

  it('ignores changed read nonce but rejects a changed stable native revision', async () => {
    const initial = receipt(); getAPI.mockResolvedValueOnce(response(initial)).mockResolvedValueOnce(response(receipt({ scope: { ...initial.scope, revision: 5 } })))
    await expect(execute(context())).rejects.toThrow(); expect(postAPI).not.toHaveBeenCalled()
  })

  it('settles pending approval on preview/form/service change and dispose', async () => {
    for (const invalidation of [vm => Widget.watch.formToken.call(vm), vm => Widget.watch.instanceName.call(vm), vm => Widget.beforeUnmount.call(vm)]) {
      const vm = context(); const pending = Widget.methods.requestAdMutationApproval.call(vm, { id, name: 'owned-service' }, 'apply', 'scope')
      invalidation(vm); expect(await pending).toBeNull(); expect(vm.adMutationConsent.resolve).toBeNull()
    }
  })

  it('awaits the existing actual async job and rejects failure without automatic retry', async () => {
    postAPI.mockResolvedValue({ applystorageposixdirectorypolicyresponse: { jobid: 'own-job' } })
    const vm = context(); getAPI.mockImplementation(async command => command === 'queryAsyncJobResult' ? { queryasyncjobresultresponse: { jobstatus: 2, jobresult: { errortext: 'controlled failure' } } } : response(receipt()))
    await expect(execute(vm)).rejects.toThrow('message.storage.service.ad.receipt.unverified')
    expect(postAPI).toHaveBeenCalledTimes(1); expect(vm.adMutationConsent.resolve).toBeNull()
  })

  it('does not forward raw transport body or signed token in joined error messages', async () => {
    postAPI.mockRejectedValue({ config: { data: 'synthetic-sensitive-body' }, response: { data: { errorresponse: { errortext: 'untrusted raw content' } } } })
    await expect(execute(context())).rejects.toThrow('message.storage.service.ad.receipt.unverified')
  })
})
