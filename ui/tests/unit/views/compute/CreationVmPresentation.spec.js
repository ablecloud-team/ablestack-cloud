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

import { creationVmOsName, creationVmDetails } from '@/utils/creationVmPresentation'
const translate = key => key

test('unspecified execution OS does not advertise the internal Other profile', () => {
  const vm = { osdisplayname: 'Other (64-bit)', details: { 'vm.creation.source': 'true' } }
  expect(creationVmOsName(vm, translate)).toBe('label.creation.source.os.unspecified')
  expect(creationVmDetails(vm, translate).ostypename).toBe('label.creation.source.os.unspecified')
  expect(vm.ostypename).toBeUndefined()
})
test('declared OS uses the selected Guest OS catalogue display name', () => {
  const vm = { osdisplayname: 'Rocky Linux 9', details: { 'vm.creation.source': 'true', 'vm.creation.source.osuuid': 'os' } }
  expect(creationVmOsName(vm, translate)).toBe('Rocky Linux 9')
})
test('ordinary resources retain their original name and identity', () => {
  const vm = { ostypename: 'Windows Server 2022' }
  expect(creationVmOsName(vm, translate)).toBe(vm.ostypename)
  expect(creationVmDetails(vm, translate)).toBe(vm)
})
