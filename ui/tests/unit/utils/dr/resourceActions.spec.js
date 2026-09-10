// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements. See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership. The ASF licenses this file
// to you under the Apache License, Version 2.0.

import { resolveDrActionAvailability } from '@/utils/dr/actionAvailability'
import { buildDrPlanActions } from '@/utils/dr/resourceActions'

function actionFor (key, currentRun = {}) {
  return buildDrPlanActions(currentRun).find(action => action.key === key)
}

describe('DR plan resource actions', () => {
  it('trusts backend eligibility when the latest historical run failed', () => {
    const resource = {
      state: 'READY',
      activeside: 'SOURCE',
      targetvmpresent: true,
      restorepointpresent: true,
      targetmaterialized: true,
      actioneligibility: {
        testFailover: true,
        failover: true
      },
      lastrun: {
        state: 'FAILED',
        runtimestate: 'ERROR',
        workerstate: 'FAILED',
        errorcode: 'DR_STATUS_CYCLE_SNAPSHOT_INCOHERENT'
      }
    }

    expect(actionFor('testfailover').disabled(resource)).toBe(false)
    expect(actionFor('failover').disabled(resource)).toBe(false)
  })

  it('disables an action when backend eligibility rejects it', () => {
    const resource = {
      state: 'READY',
      activeside: 'SOURCE',
      actioneligibility: {
        testFailover: false
      }
    }

    expect(actionFor('testfailover').disabled(resource)).toBe(true)
  })

  it('fails closed when eligibility is unavailable', () => {
    const ready = {
      state: 'READY',
      activeside: 'SOURCE',
      targetvmpresent: true,
      restorepointpresent: true,
      targetmaterialized: true,
      lastrun: {
        state: 'FAILED',
        errorcode: 'HISTORICAL_FAILURE'
      }
    }
    expect(actionFor('testfailover').disabled(ready)).toBe(true)
    expect(actionFor('testfailover').show(ready)).toBe(false)
  })

  it('uses typed backend authority without a second UI authority veto', () => {
    const resource = {
      state: 'READY',
      activeside: 'TARGET',
      actionavailability: {
        testFailover: {
          applicable: true,
          enabled: true
        }
      }
    }

    expect(actionFor('testfailover').disabled(resource)).toBe(false)
  })

  it('hides cleanup and failback when they do not apply to a ready source plan', () => {
    const resource = {
      actionavailability: {
        stopTestFailover: {
          applicable: false,
          enabled: false
        },
        failback: {
          applicable: false,
          enabled: false
        }
      }
    }

    expect(actionFor('stoptestfailover').show(resource)).toBe(false)
    expect(actionFor('failback').show(resource)).toBe(false)
  })

  it('enables cancel only for an active run', () => {
    const activeAction = actionFor('cancelrun', { id: 'run-1', state: 'RUNNING' })
    const completedAction = actionFor('cancelrun', { id: 'run-2', state: 'FAILED' })

    expect(activeAction.disabled({ actioneligibility: { cancelRun: true } })).toBe(false)
    expect(completedAction.disabled({ actioneligibility: { cancelRun: false } })).toBe(true)
  })

  it('binds test and real failover to distinct immutable run contracts', () => {
    expect(actionFor('testfailover').intent).toBe('TEST_FAILOVER')
    expect(actionFor('testfailover').expectedRunType).toBe('TEST_FAILOVER')
    expect(actionFor('failover').intent).toBe('FAILOVER')
    expect(actionFor('failover').expectedRunType).toBe('FAILOVER')
  })

  it('binds the manual sync action to an immediate full reseed', () => {
    const action = actionFor('sync')

    expect(action.label).toBe('label.dr.action.full.resync')
    expect(action.modal).toBe(true)
    expect(action.intent).toBe('SYNC')
  })
  it.each([
    {},
    { state: 'ERROR', actioneligibility: { delete: false } },
    { state: 'RUNNING', actionavailability: { delete: { applicable: false, enabled: false } } },
    { state: 'UNPROTECTED', adminstate: 'DISABLED' }
  ])('always opens the single delete dialog for plan %j', resource => {
    const actions = buildDrPlanActions({ id: 'active-run', state: 'RUNNING' })
    const deletes = actions.filter(action => action.api === 'deleteDrPlan')
    expect(deletes).toHaveLength(1)
    expect(deletes[0].key).toBe('delete')
    expect(deletes[0].show(resource)).toBe(true)
    expect(deletes[0].disabled(resource)).toBe(false)
    expect(resolveDrActionAvailability(deletes[0], resource, { state: 'RUNNING' })).toEqual({ applicable: true, enabled: true, reasonCode: '' })
  })

})
