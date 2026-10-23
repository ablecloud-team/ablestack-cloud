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

import { mount } from '@vue/test-utils'
import Selection from '@/views/compute/wizard/DiskOfferingSelection.vue'

test.each([false, true])('renders and selects allocation intent in the real data/root offering table (root=%s)', async isRootDiskOffering => {
  const items = [
    { id: 'full', name: 'RAW Full offering', provisioningtype: 'fat', disksize: 1, allocation: { contractversion: '1', requestedpolicy: 'full', requestedformat: 'raw' } },
    { id: 'legacy', name: 'Legacy Fat offering', provisioningtype: 'fat', disksize: 2 }
  ]
  const w = mount(Selection, { props: { items, rowCount: 2, zoneId: 'z', isRootDiskOffering }, global: { mocks: { $t: key => key } } })
  expect(w.find('thead').text()).toContain('label.diskoffering')
  expect(w.find('thead').text()).not.toContain('RAW Full offering')
  const rows = w.findAll('tbody tr')
  const full = rows.find(row => row.text().includes('RAW Full offering'))
  expect(full.text()).toContain('label.allocation.policy.full')
  expect(full.text()).toContain('RAW')
  expect(rows.find(row => row.text().includes('Legacy Fat offering')).text()).toContain('label.allocation.legacy: fat')
  await full.trigger('click')
  expect(w.emitted('select-disk-offering-item').slice(-1)[0]).toEqual(['full'])
  w.unmount()
})
