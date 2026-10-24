// Licensed to the Apache Software Foundation (ASF) under one or more
// contributor license agreements. See NOTICE. Apache License, Version 2.0.
// http://www.apache.org/licenses/LICENSE-2.0
import { observeConfigurationOperation, decodeConfigurationOperation, configurationRetryAllowed } from '@/utils/storageConfigOperation'
describe('configuration operation observation contract', () => {
  test('real serializer named object envelope is accepted', () => {
    const wire = require('../fixtures/storage-configuration-operation-wire.json')
    expect(decodeConfigurationOperation(wire).state).toBe('ROLLED_BACK')
  })
  test('queued missing row is observed; rollback retains the original failure', async () => {
    const get = jest.fn()
      .mockResolvedValueOnce({ liststorageserviceconfigurationoperationsresponse: {} })
      .mockResolvedValueOnce({ queryasyncjobresultresponse: { jobstatus: 0 } })
      .mockResolvedValueOnce({ liststorageserviceconfigurationoperationsresponse: { configurationoperation: [{
        id: 'op', state: 'ROLLED_BACK', originalfailure: { code: 'VERIFY_FAILED' }, rollback: { state: 'SUCCEEDED' }
      }] } })
    const value = await observeConfigurationOperation({ get, instanceid: 'instance', requestid: 'key', jobid: 'job', sleep: async () => {} })
    expect(value.originalfailure.code).toBe('VERIFY_FAILED')
    expect(get.mock.calls.every(([name]) => ['listStorageServiceConfigurationOperations', 'queryAsyncJobResult'].includes(name))).toBe(true)
  })
  test('a stale response cannot update or close another form', async () => {
    let current = true
    const changed = jest.fn()
    const get = async () => { current = false; return { liststorageserviceconfigurationoperationsresponse: { configurationoperation: [{ id: 'op', state: 'COMMITTED' }] } } }
    expect(await observeConfigurationOperation({ get, current: () => current, changed })).toBeNull()
    expect(changed).not.toHaveBeenCalled()
  })
  test('unresolved and rollback failure cannot authorize another POST', () => {
    expect(configurationRetryAllowed({ state: 'RECOVERY_REQUIRED' })).toBe(false)
    expect(configurationRetryAllowed({ state: 'OBSERVATION_UNAVAILABLE' })).toBe(false)
    expect(configurationRetryAllowed({ state: 'ROLLED_BACK' })).toBe(true)
  })
})
