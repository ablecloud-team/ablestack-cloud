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

export function buildConfigurationHierarchy (records) {
  const nodes = new Map(records.map(record => [record.name, { ...record, attachedToParent: false }]))
  const roots = []

  for (const node of nodes.values()) {
    const parent = nodes.get(node.parent)
    if (!parent || !canAttach(node, nodes)) {
      roots.push(node)
      continue
    }
    node.attachedToParent = true
    if (!parent.children) parent.children = []
    parent.children.push(node)
  }
  return roots
}

function canAttach (node, nodes) {
  const visited = new Set()
  for (let current = node; current; current = nodes.get(current.parent)) {
    if (visited.has(current.name)) return false
    visited.add(current.name)
  }
  return true
}
