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

export const requiredRpcs = ['guest-exec', 'guest-exec-status', 'guest-file-open', 'guest-file-close', 'guest-file-read', 'guest-file-write', 'guest-file-seek', 'guest-file-flush']

export function missingProcessRpcs (capability) {
  const rpcs = capability?.rpcs
  return rpcs ? requiredRpcs.filter(rpc => ['DISABLED', 'UNSUPPORTED'].includes(rpcs[rpc])) : []
}

export function processOsLabel (os) {
  if (!os || !os.version || os.version === 'unknown') return ''
  if (os.id === 'rocky') return `Rocky Linux ${os.version}`
  if (os.id === 'rhel') return `Red Hat Enterprise Linux ${os.version}`
  if (os.id === 'ubuntu') return `Ubuntu ${os.version}`
  if (os.id === 'debian') return `Debian ${os.version}`
  if (os.family === 'windows' && os.id === 'mswindows') return `Windows ${os.productType === 'server' ? 'Server ' : ''}${os.version}`
  return ''
}

export function processDiagnostic (capability, snapshotId, snapshotFailure) {
  const missing = missingProcessRpcs(capability)
  if (missing.length) return { kind: 'rpc', missing, install: true }
  const failure = snapshotFailure?.code
  if (!snapshotId) {
    const readiness = capability?.readiness
    if (readiness === 'CHECK_FAILED') return { kind: 'host' }
    if (readiness === 'QGA_UNREACHABLE') return { kind: 'qga', install: true }
    if (readiness === 'HOST_TOOL_MISSING') return { kind: 'hostTool' }
    if (readiness === 'UNSUPPORTED_OS') return { kind: 'unsupportedOs' }
    if (readiness === 'RPC_UNSUPPORTED' || readiness === 'RPC_DISABLED') return { kind: 'rpc', missing: [], install: true }
    if (readiness === 'TOOLS_REQUIRED') return { kind: 'tools', install: true }
    if (failure === 'BUSY') return { kind: 'busy' }
    if (failure) return { kind: 'snapshot' }
  }
  if (failure === 'BUSY') return { kind: 'busy' }
  if (failure) return { kind: 'snapshot' }
  return null
}
