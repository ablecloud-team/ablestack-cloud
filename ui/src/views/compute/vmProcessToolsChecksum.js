// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements. See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership. The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License. You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied. See the License for the
// specific language governing permissions and limitations
// under the License.

// Compare Cloud's ISO checksum at selection and attachment, without asking
// administrators to enter a checksum in the global setting.
const normalize = value => String(value || '').replace(/^\{SHA-(256|512)\}/i, '').replace(/^sha(256|512):/i, '').toLowerCase()

export function matchesToolsIsoChecksum (checksum, expected) {
  if (!expected) return true
  const value = normalize(expected)
  return (/^[0-9a-f]{64}$/.test(value) || /^[0-9a-f]{128}$/.test(value)) && normalize(checksum) === value
}
