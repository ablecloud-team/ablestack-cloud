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

const TERMINAL_STATES = ['SUCCEEDED', 'FAILED', 'CANCELED']
const TRANSFER_RUN_TYPES = ['SYNC', 'RECOVER_SYNC', 'FAILBACK']

const clampPercent = value => Math.max(0, Math.min(100, Math.round(value)))

export const drStateProgress = stateValue => {
  const state = String(stateValue || '').toUpperCase()
  if (state === 'QUEUED') return 5
  if (state === 'PREPARING') return 10
  if (state === 'DISPATCHING') return 15
  if (state === 'RETRYING') return 25
  if (state === 'ACCEPTED') return 35
  if (state === 'RUNNING' || state === 'CANCEL_REQUESTED') return 60
  if (TERMINAL_STATES.includes(state)) return 100
  return 0
}

export const hasDrTransferProgress = transferValue => {
  return Number(transferValue?.transferprogressschemaversion || 0) >= 2 &&
    Number(transferValue?.transferbytestotal || 0) > 0
}

export const drTransferPercent = transferValue => {
  const value = Number(transferValue?.transferpercent)
  if (Number.isFinite(value)) return clampPercent(value)
  const total = Number(transferValue?.transferbytestotal || 0)
  const processed = Number(transferValue?.transferbytesprocessed || transferValue?.transferpayloadbytes || 0)
  return total > 0 ? clampPercent(processed * 100 / total) : 0
}

export const drTransferWorkflowProgress = (run, transferValue = run) => {
  const runType = String(run?.runtype || run?.runType || '').toUpperCase()
  const runState = String(run?.state || '').toUpperCase()
  if (!TRANSFER_RUN_TYPES.includes(runType) ||
    !hasDrTransferProgress(transferValue) || TERMINAL_STATES.includes(runState)) {
    return 0
  }
  return 70 + Math.round(drTransferPercent(transferValue) * 25 / 100)
}

export const drOperationProgress = (run, transferValue = run) => {
  const state = String(run?.state || '').toUpperCase()
  const fallback = drStateProgress(state)
  const value = Number(run?.progresspercent)
  const authoritative = Number.isFinite(value) ? clampPercent(value) : fallback
  const transferProgress = drTransferWorkflowProgress(run, transferValue)

  if (!TERMINAL_STATES.includes(state) && authoritative >= 100) {
    return Math.max(fallback, transferProgress)
  }
  return Math.max(authoritative, transferProgress)
}
