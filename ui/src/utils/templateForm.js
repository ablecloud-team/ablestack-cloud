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

// Match the API checksum wire format. Algorithms outside the documented common
// set still reach the server, which owns provider/algorithm availability.
export function validTemplateChecksum (value) {
  if (value == null || value === '') return true
  const match = /^(?:\{([A-Za-z0-9-]+)\})?([a-fA-F0-9]+)$/.exec(value)
  if (!match) return false
  const lengths = { MD5: 32, 'SHA-1': 40, 'SHA-224': 56, 'SHA-256': 64, 'SHA-384': 96, 'SHA-512': 128 }
  const algorithm = match[1] || 'MD5'
  if (Object.keys(lengths).some(key => key.toLowerCase() === algorithm.toLowerCase() && key !== algorithm)) return false
  return !lengths[algorithm] || match[2].length === lengths[algorithm]
}
