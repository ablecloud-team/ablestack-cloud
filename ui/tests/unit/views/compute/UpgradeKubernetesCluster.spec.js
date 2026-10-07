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

import { reactive } from 'vue'
import { getAPI } from '@/api'
import UpgradeKubernetesCluster from '@/views/compute/UpgradeKubernetesCluster.vue'

jest.mock('@/api', () => ({ getAPI: jest.fn(), postAPI: jest.fn() }))
const version = (id, semanticversion, overrides = {}) => ({ id, semanticversion, name: id, state: 'Enabled', isostate: 'Ready', ...overrides })
const current = version('current', '1.34.9')
const state = () => reactive({
  resource: { kubernetesversionid: 'current' },
  form: { kubernetesversionid: 99 },
  kubernetesVersions: [{ id: 'stale' }],
  kubernetesVersionLoading: false,
  $notifyError: jest.fn(),
  ...UpgradeKubernetesCluster.methods
})
const response = versions => ({ listkubernetessupportedversionsresponse: { kubernetessupportedversion: versions } })
beforeEach(() => getAPI.mockReset())

test('r11 options retain patch and next minor but exclude skip, downgrade, equality and non-ready artifacts', async () => {
  getAPI.mockResolvedValue(response([
    current, version('patch', '1.34.12'), version('next', '1.35.9'),
    version('same', '1.34.9'), version('older', '1.34.2'), version('skip', '1.36.5'),
    version('dev-skip', '1.37.1'), version('major', '2.34.10'),
    version('disabled', '1.34.12', { state: 'Disabled' }), version('downloading', '1.35.9', { isostate: 'Downloading' })
  ]))
  const vm = state()
  await vm.fetchKubernetesVersionData()
  expect(vm.kubernetesVersions.map(v => v.id)).toEqual(['patch', 'next'])
  expect(vm.form.kubernetesversionid).toBe(0)
  expect(vm.kubernetesVersionLoading).toBe(false)
  expect(getAPI).toHaveBeenCalledWith('listKubernetesSupportedVersions', { minimumkubernetesversionid: 'current' })
})

test.each([
  [version('other', '1.35.9')],
  [version('current', undefined), version('next', '1.35.9')],
  [],
  [current, version('same', '1.34.9'), version('older', '1.34.2')]
])('missing current metadata or no valid targets clears a stale selection: %p', async (...versions) => {
  getAPI.mockResolvedValue(response(versions))
  const vm = state()
  await vm.fetchKubernetesVersionData()
  expect(vm.kubernetesVersions).toEqual([])
  expect(vm.form.kubernetesversionid).toBeUndefined()
  expect(vm.kubernetesVersionLoading).toBe(false)
})

test('an API failure ends loading, removes stale selection and reports the error', async () => {
  const error = new Error('metadata unavailable')
  getAPI.mockRejectedValue(error)
  const vm = state()
  await vm.fetchKubernetesVersionData()
  expect(vm.kubernetesVersions).toEqual([])
  expect(vm.form.kubernetesversionid).toBeUndefined()
  expect(vm.kubernetesVersionLoading).toBe(false)
  expect(vm.$notifyError).toHaveBeenCalledWith(error)
})
