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

import { cniParams } from './cniRegistration'

export const mergeCniVariables = (current, selected) => {
  const result = [...new Set([...(current || []), ...(selected || [])].map(key => cniParams([key])))]
  cniParams(result)
  return result
}
export function cniPreviewRequest ({ mode, encoded, resource, params, values, networkId, asNumber }) {
  const request = mode === 'extract' ? { cniconfig: encoded, params: cniParams(params || []) } : { id: resource.id }
  Object.entries(values || {}).filter(([, value]) => value !== undefined).forEach(([key, value], index) => { request[`cniconfigdetails[${index}].${key}`] = value })
  if (networkId) request.networkid = networkId
  if (asNumber !== undefined && asNumber !== null && asNumber !== '') request.asnumber = asNumber
  return request
}
