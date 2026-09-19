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

import { deploymentTpmParams } from '@/utils/tpm'

describe('TPM deployment request', () => {
  it('does not send an implicit NONE over an inherited template setting', () => {
    expect(deploymentTpmParams('KVM', { tpmversion: 'INHERIT' })).toEqual({})
    expect(deploymentTpmParams('KVM', {})).toEqual({})
  })
  it('keeps explicit disabled distinct from inheritance', () => {
    expect(deploymentTpmParams('KVM', { tpmversion: 'NONE', tpmmodel: 'tpm-crb' })).toEqual({ tpmversion: 'NONE' })
  })
  it('sends matching legacy and canonical version and model', () => {
    expect(deploymentTpmParams('KVM', { tpmversion: 'V2_0', tpmmodel: 'tpm-crb' })).toEqual({
      tpmversion: 'V2_0',
      'details[0].virtual.tpm.model': 'tpm-crb',
      'details[0].virtual.tpm.version': '2.0'
    })
  })
  it('uses TIS for legacy callers without a model', () => {
    expect(deploymentTpmParams('KVM', { tpmversion: 'V2_0' })['details[0].virtual.tpm.model']).toBe('tpm-tis')
  })
  it('uses only TIS for version 1.2, even after a previous CRB selection', () => {
    expect(deploymentTpmParams('KVM', { tpmversion: 'V1_2', tpmmodel: 'tpm-crb' })).toEqual({
      tpmversion: 'V1_2',
      'details[0].virtual.tpm.model': 'tpm-tis',
      'details[0].virtual.tpm.version': '1.2'
    })
  })
  it('leaves other hypervisor TPM conventions untouched', () => {
    expect(deploymentTpmParams('VMware', { tpmversion: 'V2_0' })).toEqual({})
  })
})
