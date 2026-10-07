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

import { isKubernetesUpgradeTarget } from '@/utils/kubernetesUpgrade'

test.each([
  ['1.34.9', '1.34.12', true],
  ['1.34.12', '1.35.1', true],
  ['1.35.9', '1.36.5', true],
  ['1.36.5', '1.37.1', true],
  ['1.34.9', '1.34.9', false],
  ['1.34.12', '1.34.9', false],
  ['1.35.9', '1.34.12', false],
  ['1.34.9', '1.36.5', false],
  ['1.34.9', '2.34.10', false],
  ['1.34.9', '1.35', false],
  ['1.34.9', '1.35.1-rc.1', false],
  ['1.34.9', '1.35.1junk', false],
  [undefined, '1.35.9', false],
  ['1.34.9', undefined, false],
  [34.9, '1.35.9', false],
  ['1.34.9', '1.35.9007199254740992', false]
])('upgrade %p to %p is selectable: %p', (current, target, expected) => {
  expect(isKubernetesUpgradeTarget(current, target)).toBe(expected)
})
