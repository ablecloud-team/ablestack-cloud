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

import { partialScaleRecoverySize } from '@/utils/kubernetesScaleRecovery'

const cluster = { clustertype: 'CloudManaged', state: 'Alert', size: 3, controlnodes: 1, etcdnodes: 0, virtualmachines: [{ state: 'Running' }, { state: 'Running' }, { state: 'Running' }] }

test('offers only the actual remaining worker count for a partial scale failure', () => {
  expect(partialScaleRecoverySize(cluster)).toBe(2)
})
test.each([
  [{ clustertype: 'ExternalManaged' }], [{ state: 'Scaling' }], [{ size: 1 }],
  [{ virtualmachines: [] }], [{ virtualmachines: [{ state: 'Stopped' }] }],
  [{ controlnodes: 3 }], [{ controlnodes: -1 }], [{ etcdnodes: 'invalid' }],
  [{ size: 2 }], [{ size: 2, scalenetworkcleanuppending: 'true' }]
])('rejects an unsafe recovery state %p', change => {
  expect(partialScaleRecoverySize({ ...cluster, ...change })).toBeNull()
})
test('permits an explicitly pending cleanup after totals already reconciled', () => {
  expect(partialScaleRecoverySize({ ...cluster, size: 2, scalenetworkcleanuppending: true })).toBe(2)
})
