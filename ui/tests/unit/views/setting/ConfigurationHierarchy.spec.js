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

import { buildConfigurationHierarchy } from '@/views/setting/configurationHierarchy'

test('keeps a child visible when its parent was filtered out', () => {
  const child = { name: 'agent.load.threshold', parent: 'agent.lb.enabled' }
  expect(buildConfigurationHierarchy([child])).toEqual([{ ...child, attachedToParent: false }])
  expect(child).not.toHaveProperty('attachedToParent')
})

test('attaches each descendant to its parent once, independent of response order', () => {
  const records = [{ name: 'leaf', parent: 'middle' }, { name: 'root' }, { name: 'middle', parent: 'root' }]
  const [root] = buildConfigurationHierarchy(records)
  expect(root.name).toBe('root')
  expect(root.children.map(child => child.name)).toEqual(['middle'])
  expect(root.children[0].children).toEqual([{ name: 'leaf', parent: 'middle', attachedToParent: true }])
  expect(records[1]).not.toHaveProperty('children')
})

test('preserves affected rows when a malformed parent chain contains a cycle', () => {
  const records = [{ name: 'a', parent: 'b' }, { name: 'b', parent: 'a' }, { name: 'c', parent: 'a' }]
  expect(buildConfigurationHierarchy(records)).toEqual(records.map(record => ({ ...record, attachedToParent: false })))
})
