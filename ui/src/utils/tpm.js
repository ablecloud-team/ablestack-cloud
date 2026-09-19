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

// Keep legacy API clients compatible while sending one canonical KVM TPM device.
export function deploymentTpmParams (hypervisor, values) {
  if (hypervisor !== 'KVM' || !values.tpmversion || values.tpmversion === 'INHERIT') return {}
  if (values.tpmversion === 'NONE') return { tpmversion: 'NONE' }
  return {
    tpmversion: values.tpmversion,
    'details[0].virtual.tpm.model': values.tpmversion === 'V1_2' ? 'tpm-tis' : values.tpmmodel || 'tpm-tis',
    'details[0].virtual.tpm.version': values.tpmversion === 'V1_2' ? '1.2' : '2.0'
  }
}
