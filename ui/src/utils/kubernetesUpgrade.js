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

// Mirror the backend patch/next-minor policy; unknown version metadata is not selectable.
export function isKubernetesUpgradeTarget (currentVersion, targetVersion) {
  const parse = version => {
    if (typeof version !== 'string' || !/^\d+\.\d+\.\d+$/.test(version)) return null
    const parts = version.split('.').map(Number)
    return parts.every(Number.isSafeInteger) ? parts : null
  }
  const current = parse(currentVersion)
  const target = parse(targetVersion)
  if (!current || !target || target[0] !== current[0]) return false
  return target[1] === current[1] + 1 ||
    (target[1] === current[1] && target[2] > current[2])
}
