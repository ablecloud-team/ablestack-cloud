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

const booleanValue = value => value === true || value === 'true'
const value = (config, upper, lower) => config[upper] ?? config[lower]
const numericId = input => input === undefined || input === null || input === '' ? 65534 : Number(input)

/** Recommendations only. Existing inode permissions require a separately reviewed apply. */
export function nfsPermissionPreset (config = {}) {
  const readOnly = booleanValue(value(config, 'readOnly', 'readonly'))
  const allSquash = booleanValue(value(config, 'allSquash', 'allsquash'))
  const rootValue = value(config, 'rootSquash', 'rootsquash')
  const rootSquash = rootValue === undefined ? true : booleanValue(rootValue)
  if (readOnly) return { policy: 'READ_ONLY_PRESERVE', owneruid: null, ownergid: null, mode: null, recursivepermission: false, applyowner: false }
  if (!rootSquash && !allSquash) return { policy: 'NO_ROOT_SQUASH', owneruid: 0, ownergid: 0, mode: '0770', recursivepermission: false, applyowner: true }
  return {
    policy: allSquash ? 'ALL_SQUASH' : 'ROOT_SQUASH',
    owneruid: numericId(value(config, 'anonUid', 'anonuid')),
    ownergid: numericId(value(config, 'anonGid', 'anongid')),
    mode: '0775',
    recursivepermission: false,
    applyowner: true
  }
}

export function recommendedNfsPermissionPatch (config = {}) {
  const preset = nfsPermissionPreset(config)
  if (!preset.applyowner || config.posixpolicyid) return {}
  return { owneruid: preset.owneruid, ownergid: preset.ownergid, mode: preset.mode, recursivepermission: false }
}
