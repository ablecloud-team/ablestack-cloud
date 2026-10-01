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

import { processDiagnostic, processOsLabel } from '@/views/compute/vmProcessDisplay'

describe('VM process diagnostics', () => {
  it('shows a host check failure without presenting unknown OS values or an ISO install action', () => {
    const capability = { readiness: 'CHECK_FAILED', os: { id: 'unknown', version: 'unknown' }, rpcs: { 'guest-exec': 'UNKNOWN' } }
    expect(processDiagnostic(capability, null, null)).toEqual({ kind: 'host' })
    expect(processOsLabel(capability.os)).toBe('')
  })

  it('explains a missing Windows guest adapter after a failed collection', () => {
    const capability = {
      readiness: 'TOOLS_REQUIRED',
      os: { id: 'mswindows', family: 'windows', productType: 'server', version: '2025' },
      rpcs: { 'guest-exec': 'ENABLED' }
    }
    expect(processDiagnostic(capability, null, { code: 'CHECK_FAILED' })).toEqual({ kind: 'tools', install: true })
    expect(processOsLabel(capability.os)).toBe('Windows Server 2025')
    expect(processDiagnostic(capability, null, null)).toEqual({ kind: 'tools', install: true })
    expect(processDiagnostic(capability, null, { code: 'BUSY' })).toEqual({ kind: 'tools', install: true })
  })

  it('labels each newly supported OS without confusing Windows products', () => {
    expect(processOsLabel({ id: 'debian', version: '12' })).toBe('Debian 12')
    expect(processOsLabel({ id: 'rhel', version: '9.6' })).toBe('Red Hat Enterprise Linux 9.6')
    expect(processOsLabel({ id: 'mswindows', family: 'windows', productType: 'client', version: '11' })).toBe('Windows 11')
  })

  it('keeps transient busy results separate from a missing Tools diagnosis', () => {
    const capability = { readiness: 'TOOLS_REQUIRED', rpcs: {} }
    expect(processDiagnostic(capability, 'snapshot-id', { code: 'BUSY' })).toEqual({ kind: 'busy' })
    expect(processDiagnostic(capability, 'snapshot-id', null)).toBeNull()
  })

  it('lists only confirmed disabled or unsupported RPCs', () => {
    const capability = { readiness: 'RPC_DISABLED', rpcs: { 'guest-exec': 'DISABLED', 'guest-file-open': 'UNSUPPORTED', 'guest-file-close': 'UNKNOWN' } }
    expect(processDiagnostic(capability, null, null)).toEqual({
      kind: 'rpc', missing: ['guest-exec', 'guest-file-open'], install: true
    })
  })
})
