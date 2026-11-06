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
      .mockResolvedValueOnce({
        liststorageserviceconfigurationoperationsresponse: {
          configurationoperation: [{
            id: 'op', state: 'ROLLED_BACK', originalfailure: { code: 'VERIFY_FAILED' }, rollback: { state: 'SUCCEEDED' }
          }]
        }
      })
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

const initialWire = require('../fixtures/storage-initial-configuration-wire.json')
describe('resumable observations and original async job lifetime', () => {
  const committing = initialWire.observations.find(w => w.liststorageserviceconfigurationoperationsresponse.configurationoperation[0].canresume)
  const committed = initialWire.observations.at(-1)
  test('an active original job is observed past COMMITTING/canresume until COMMITTED', async () => {
    const get = jest.fn().mockResolvedValueOnce(committing).mockResolvedValueOnce(initialWire.jobRunning).mockResolvedValueOnce(committed)
    const value = await observeConfigurationOperation({ get, instanceid: 'instance', requestid: 'key', jobid: 'job', sleep: async () => {} })
    expect(value.state).toBe('COMMITTED')
    expect(get.mock.calls.map(([api]) => api)).toEqual(['listStorageServiceConfigurationOperations', 'queryAsyncJobResult', 'listStorageServiceConfigurationOperations'])
  })
  test('reentry without an original job exposes the same resumable operation without any POST', async () => {
    const get = jest.fn().mockResolvedValue(committing)
    const value = await observeConfigurationOperation({ get, requestid: 'key' })
    expect(value).toBe(committing.liststorageserviceconfigurationoperationsresponse.configurationoperation[0])
    expect(get).toHaveBeenCalledTimes(1)
  })
  test.each([1, 2])('a finished original job %i leaves explicit same-operation recovery available', async jobstatus => {
    const get = jest.fn().mockResolvedValueOnce(committing).mockResolvedValueOnce({ queryasyncjobresultresponse: { jobstatus } }).mockResolvedValueOnce(committing)
    const snapshot = committing.liststorageserviceconfigurationoperationsresponse.configurationoperation[0]
    const value = await observeConfigurationOperation({ get, instanceid: snapshot.instanceid, requestid: snapshot.requestid, jobid: 'finished', sleep: async () => {} })
    expect(value.id).toBe(committing.liststorageserviceconfigurationoperationsresponse.configurationoperation[0].id)
    expect(value.canresume).toBe(true)
    expect(get).toHaveBeenCalledTimes(3)
  })
})
