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

import {
  DR_ACTION_CUTOVER_NOT_READY,
  drActionReasonMessageKey,
  normalizeActionAvailability,
  requiresDisasterFailover,
  resolveDrActionAvailability
} from '@/utils/dr/actionAvailability'

describe('DR action availability', () => {
  it('normalizes camel-case backend keys and reason fields', () => {
    const normalized = normalizeActionAvailability({
      testFailover: {
        applicable: true,
        enabled: false,
        reasoncode: 'DR_ACTION_TARGET_NOT_READY',
        reasonargs: { target: 'vm' }
      }
    })

    expect(normalized.testfailover).toEqual({
      applicable: true,
      enabled: false,
      reasonCode: 'DR_ACTION_TARGET_NOT_READY',
      reasonArgs: { target: 'vm' }
    })
  })

  it('fails closed when neither typed nor legacy state is available', () => {
    expect(resolveDrActionAvailability({ key: 'failover' }, {})).toEqual({
      applicable: false,
      enabled: false,
      reasonCode: 'DR_ACTION_AVAILABILITY_MISSING'
    })
  })

  it('maps stable backend reason codes to locale keys', () => {
    expect(drActionReasonMessageKey('DR_ACTION_TARGET_NOT_READY'))
      .toBe('message.dr.action.target.not.ready')
  })

  it('keeps capability block reason and args for pre-action UI gating', () => {
    const state = resolveDrActionAvailability({ key: 'reprotect' }, {
      actionavailability: {
        reprotect: {
          applicable: true,
          enabled: false,
          reasoncode: 'DR_ACTION_REPROTECT_CONTRACT_UNSUPPORTED',
          reasonargs: { requiredVersion: 'current' }
        }
      }
    })

    expect(state).toEqual({
      applicable: true,
      enabled: false,
      reasonCode: 'DR_ACTION_REPROTECT_CONTRACT_UNSUPPORTED',
      reasonArgs: { requiredVersion: 'current' }
    })
    expect(drActionReasonMessageKey(state.reasonCode))
      .toBe('message.dr.action.reprotect.contract.unsupported')
  })

  it('opens failover as disaster-only when normal cutover is not ready', () => {
    const resource = {
      normalcutoverready: false,
      actioneligibility: { disasterFailover: true },
      actionavailability: {
        failover: {
          applicable: true,
          enabled: false,
          reasoncode: DR_ACTION_CUTOVER_NOT_READY
        }
      }
    }

    expect(requiresDisasterFailover(resource)).toBe(true)
    expect(resolveDrActionAvailability({ key: 'failover' }, resource)).toEqual({
      applicable: true,
      enabled: true,
      reasonCode: DR_ACTION_CUTOVER_NOT_READY,
      reasonArgs: {}
    })
  })

  it('does not bypass target readiness or other failover blockers', () => {
    const resource = {
      normalcutoverready: false,
      actionavailability: {
        failover: {
          applicable: true,
          enabled: false,
          reasoncode: 'DR_ACTION_TARGET_NOT_READY'
        }
      }
    }

    expect(requiresDisasterFailover(resource)).toBe(false)
    expect(resolveDrActionAvailability({ key: 'failover' }, resource).enabled).toBe(false)
  })

  it('does not bypass FTCTL control readiness', () => {
    const resource = {
      normalcutoverready: false,
      actioneligibility: { disasterFailover: false },
      actionavailability: {
        failover: {
          applicable: true,
          enabled: false,
          reasoncode: DR_ACTION_CUTOVER_NOT_READY
        }
      }
    }

    expect(requiresDisasterFailover(resource)).toBe(false)
    expect(resolveDrActionAvailability({ key: 'failover' }, resource).enabled).toBe(false)
  })

  it('never exposes cancel after the live run has reached terminal state', () => {
    const staleResource = {
      actionavailability: {
        cancelRun: { applicable: true, enabled: true }
      }
    }

    expect(resolveDrActionAvailability(
      { key: 'cancelrun' },
      staleResource,
      { id: 'terminal-run', state: 'SUCCEEDED' }
    )).toEqual({ applicable: false, enabled: false, reasonCode: '' })
  })
})
