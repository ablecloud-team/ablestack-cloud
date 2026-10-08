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

import { computed, reactive } from 'vue'
import { getAPI, postAPI } from '@/api'
import CreateKubernetesCluster from '@/views/compute/CreateKubernetesCluster.vue'

jest.mock('@/api', () => ({ getAPI: jest.fn(), postAPI: jest.fn() }))
jest.mock('@views/compute/wizard/UserDataSelection', () => ({}), { virtual: true })

const state = admin => reactive({
  templates: [],
  templateLoading: false,
  isAdminOrDomainAdmin: () => admin
})
const response = templates => ({ listtemplatesresponse: { template: templates } })
const deferred = () => {
  let complete
  const promise = new Promise(resolve => { complete = resolve })
  return { promise, resolve: complete }
}
beforeEach(() => getAPI.mockReset())

test('a delayed CKS template response updates the reactive selection options', async () => {
  const wait = deferred()
  getAPI.mockReturnValue(wait.promise)
  const vm = state(true)
  const options = computed(() => vm.templates.map(t => t.name))
  const done = CreateKubernetesCluster.methods.fetchCksTemplates.call(vm)
  expect(options.value).toEqual([])
  expect(vm.templateLoading).toBe(true)
  wait.resolve(response([{ id: 'prepared', name: 'Private prepared node' }]))
  await done
  expect(options.value).toEqual(['Private prepared node'])
  expect(vm.templateLoading).toBe(false)
  expect(getAPI).toHaveBeenCalledWith('listTemplates', { templatefilter: 'all', forcks: true, isready: true })
})

test('user filter results remain loading until all responses arrive and duplicate templates are selectable once', async () => {
  const first = deferred()
  const last = deferred()
  getAPI.mockReturnValueOnce(first.promise).mockResolvedValueOnce(response([{ id: 'shared' }])).mockReturnValueOnce(last.promise)
  const vm = state(false)
  const done = CreateKubernetesCluster.methods.fetchCksTemplates.call(vm)
  first.resolve(response([{ id: 'private' }, { id: 'shared' }]))
  await Promise.resolve()
  expect(vm.templateLoading).toBe(true)
  last.resolve(response([{ id: 'shared' }, { id: 'public' }]))
  await done
  expect(vm.templates.map(t => t.id)).toEqual(['private', 'shared', 'public'])
  expect(getAPI.mock.calls.map(c => c[1].templatefilter)).toEqual(['self', 'featured', 'community'])
  expect(vm.templateLoading).toBe(false)
})

test('an empty successful response removes stale options and ends loading', async () => {
  getAPI.mockResolvedValue({ listtemplatesresponse: {} })
  const vm = state(true)
  vm.templates = [{ id: 'stale' }]
  await CreateKubernetesCluster.methods.fetchCksTemplates.call(vm)
  expect(vm.templates).toEqual([])
  expect(vm.templateLoading).toBe(false)
})

test('a rejected query does not leave stale templates or the loading spinner active', async () => {
  getAPI.mockRejectedValue(new Error('API unavailable'))
  const vm = state(true)
  vm.templates = [{ id: 'stale' }]
  await CreateKubernetesCluster.methods.fetchCksTemplates.call(vm)
  expect(vm.templates).toEqual([])
  expect(vm.templateLoading).toBe(false)
})

test('wizard validates the current step and cannot send a create request early', async () => {
  const validateFields = jest.fn().mockResolvedValue({})
  const vm = { wizardStep: 0, formRef: { value: { validateFields } } }
  await CreateKubernetesCluster.methods.nextWizardStep.call(vm)
  expect(validateFields).toHaveBeenCalledWith(['name', 'zoneid', 'hypervisor', 'kubernetesversionid'])
  expect(vm.wizardStep).toBe(1)
  const nextWizardStep = jest.fn()
  CreateKubernetesCluster.methods.handleSubmit.call({ wizardStep: 3, nextWizardStep })
  expect(nextWizardStep).toHaveBeenCalledTimes(1)
})

test('invalid wizard input stays on its step with the focused field', async () => {
  const scrollToField = jest.fn()
  const vm = { wizardStep: 1, formRef: { value: { validateFields: jest.fn().mockRejectedValue({ errorFields: [{ name: ['size'] }] }), scrollToField } } }
  await CreateKubernetesCluster.methods.nextWizardStep.call(vm)
  expect(vm.wizardStep).toBe(1)
  expect(scrollToField).toHaveBeenCalledWith(['size'])
})

test('single KVM zone auto-selects an index and submits the default without manual re-selection', async () => {
  getAPI.mockResolvedValue({ listhypervisorsresponse: { hypervisor: [{ name: 'KVM' }] } })
  const vm = {
    wizardStep: 4,
    loading: false,
    form: { hypervisor: null },
    selectedZone: { id: 'zone' },
    formRef: { value: { validate: jest.fn().mockResolvedValue({}) } },
    handleRemoveFields: values => ({ ...values, name: 'test', zoneid: 0, kubernetesversionid: 0, serviceofferingid: 0, size: 1 }),
    zones: [{ id: 'zone' }],
    kubernetesVersions: [{ id: 'iso' }],
    serviceOfferings: [{ id: 'compute' }],
    owner: {},
    arrayHasItems: list => Boolean(list?.length),
    isValidValueForKey: () => false,
    $notifyError: jest.fn(),
    $pollJob: jest.fn(),
    $t: key => key,
    closeAction: jest.fn()
  }
  await CreateKubernetesCluster.methods.fetchZoneHypervisors.call(vm)
  expect(vm.form.hypervisor).toBe(0)
  postAPI.mockResolvedValue({ createkubernetesclusterresponse: { jobid: 'job' } })
  await CreateKubernetesCluster.methods.handleSubmit.call(vm)
  expect(postAPI).toHaveBeenCalledWith('createKubernetesCluster', expect.objectContaining({ hypervisor: 'kvm', zoneid: 'zone' }))
  expect(vm.$notifyError).not.toHaveBeenCalled()
})

test('a submission exception retains the dialog, restores loading and reports the actual error', async () => {
  const error = new Error('selection is unavailable')
  const vm = { wizardStep: 4, loading: false, form: {}, formRef: { value: { validate: jest.fn().mockRejectedValue(error) } }, $notifyError: jest.fn() }
  await CreateKubernetesCluster.methods.handleSubmit.call(vm)
  expect(vm.loading).toBe(false)
  expect(vm.$notifyError).toHaveBeenCalledWith(error)
})
