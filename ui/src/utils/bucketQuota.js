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

export const MAX_BUCKET_QUOTA_GIB = 2147483647
export function validBucketQuota (value) {
  return /^\d+$/.test(String(value ?? '')) && Number(value) > 0 && Number(value) <= MAX_BUCKET_QUOTA_GIB
}
export function pendingBucketQuota (resource) {
  return !!resource.quotaoperationid && !['Succeeded', 'Failed'].includes(resource.quotaoperationstate)
}
export function bucketChanges (form, resource, quotaTouched = false) {
  const result = {}
  if (quotaTouched || Number(form.quota) !== Number(resource.quota)) result.quota = Number(form.quota)
  for (const key of ['encryption', 'versioning', 'policy']) {
    if (form[key] !== resource[key]) result[key] = form[key]
  }
  return result
}
export function quotaErrorField (error) {
  const body = error?.response?.data
  const response = body?.updatebucketresponse || body?.createbucketresponse || {}
  return [431, 535].includes(Number(response.errorcode)) && /quota|capacity|usage|resource limit/i.test(response.errortext || '')
}
