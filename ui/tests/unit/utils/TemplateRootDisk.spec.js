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

import { strictTemplateCustomRoot } from '@/utils/templateRootDisk'
const service = { diskofferingstrictness: true, diskofferingid: 'gfs2' }
test('strict custom template disks require a size even when offering replacement is forbidden', () => {
  expect(strictTemplateCustomRoot('templateid', service, [{ id: 'gfs2', iscustomized: true }], {})).toBe(true)
  expect(strictTemplateCustomRoot('templateid', service, [], {})).toBe(true)
})
test('fixed size, deploy-as-is, ISO and unconstrained offerings retain their existing behavior', () => {
  expect(strictTemplateCustomRoot('templateid', { ...service, rootdisksize: 20 }, [], {})).toBe(false)
  expect(strictTemplateCustomRoot('templateid', service, [{ id: 'gfs2', iscustomized: false }], {})).toBe(false)
  expect(strictTemplateCustomRoot('templateid', service, [], { deployasis: true })).toBe(false)
  expect(strictTemplateCustomRoot('isoid', service, [], {})).toBe(false)
  expect(strictTemplateCustomRoot('templateid', { ...service, diskofferingstrictness: false }, [], {})).toBe(false)
})

test('strict custom root remains stable through the real form watcher', async () => {
  const { reactive, watch, nextTick } = require('vue')
  const DeployVM = require('@/views/compute/DeployVM.vue').default
  const offering = { id: 'strict', diskofferingid: 'root', diskofferingstrictness: true }
  const template = { id: 'image', size: 5 * 1024 ** 3 }
  const state = reactive({
    form: { templateid: 'image', computeofferingid: 'strict', rootdisksize: 20, rootdisksizeitem: true },
    vm: {},
    options: { templates: { all: { template: [template] } }, isos: {}, serviceOfferings: [offering], diskOfferings: [{ id: 'root', iscustomized: true }] },
    imageType: 'templateid',
    templateStrictCustomRoot: true,
    showRootDiskSizeChanger: true,
    showOverrideDiskOfferingOption: false,
    rootDiskOffering: {},
    dataDiskOffering: {},
    dataPreFill: { minrootdisksize: 5 }
  })
  state.changeRootDiskSizeOverride = value => DeployVM.methods.changeRootDiskSizeOverride.call(state, value)
  state.updateOverrideRootDiskShowParam = value => DeployVM.methods.updateOverrideRootDiskShowParam.call(state, value)
  let calls = 0
  const stop = watch(() => state.form, value => {
    if (++calls > 5) { stop(); return }
    DeployVM.watch.formModel.handler.call(state, value)
  }, { deep: true })
  state.form.name = 'strict-root-regression'
  await nextTick()
  stop()
  expect(calls).toBeLessThanOrEqual(2)
  expect(state.showRootDiskSizeChanger).toBe(true)
  expect(state.form.rootdisksizeitem).toBe(true)
  expect(state.form.rootdisksize).toBe(20)
  expect(state.serviceOffering.id).toBe('strict')
})
