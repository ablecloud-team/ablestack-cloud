// Licensed under the Apache License, Version 2.0. See NOTICE.
import { shallowMount, flushPromises } from '@vue/test-utils'
import SharedFSDeletion from '@/views/storage/SharedFSDeletion'
import actualWire from '../../../fixtures/sharedfs-1461-operation-wire.json'
import { getAPI, postAPI } from '@/api'
jest.mock('@/api', () => ({ getAPI: jest.fn(), postAPI: jest.fn() }))
const clone = value => JSON.parse(JSON.stringify(value))
const envelope = value => {
  const wire = clone(actualWire)
  wire.getsharedfilesystemdeletionoperationresponse.sharedfsdeletion.result = value
  return wire
}
const native = () => clone(actualWire.getsharedfilesystemdeletionoperationresponse.sharedfsdeletion.result)
describe('real SharedFS deletion component with API serializer fixture', () => {
  let wrapper, observed, jobStatus
  const start = async (state = 'NOT_FOUND', api = 'destroySharedFileSystem') => {
    observed = { ...native(), state }; jobStatus = 0
    getAPI.mockImplementation(async command => command === 'getSharedFileSystemDeletionImpact'
      ? envelope({ fingerprint: 'approved', volumes: native().plan.volumes, success: true })
      : command === 'queryAsyncJobResult' ? { queryasyncjobresultresponse: { jobstatus: jobStatus } } : envelope(observed))
    postAPI.mockResolvedValue({ destroysharedfilesystemresponse: { jobid: 'async-job' } })
    wrapper = shallowMount(SharedFSDeletion, {
      props: { resource: { id: 'service-id', name: 'service' }, currentAction: { api } },
      global: { mocks: { $t: key => key, $router: { push: jest.fn() } }, stubs: { MoldDialogActions: { template: '<footer><slot /></footer>' }, ASpin: { template: '<main><slot /></main>' }, ADescriptions: { template: '<dl><slot /></dl>' }, ADescriptionsItem: { template: '<dd><slot /></dd>' } } }
    })
    await flushPromises()
  }
  beforeEach(() => { jest.useFakeTimers(); getAPI.mockReset(); postAPI.mockReset() })
  afterEach(() => { if (wrapper) wrapper.unmount(); jest.clearAllTimers(); jest.useRealTimers() })
  test('default policy preserves DATA and keeps expunge off; exact acknowledgement is required', async () => {
    await start(); expect(wrapper.vm.impact.volumes[0].uuid).toContain('1461')
    expect(wrapper.vm.policy).toBe('PRESERVE'); expect(wrapper.vm.expunge).toBe(false); expect(wrapper.vm.canSubmit).toBe(false)
    await wrapper.setData({ stopConfirmed: true, confirmName: 'wrong' }); expect(wrapper.vm.canSubmit).toBe(false)
    await wrapper.setData({ confirmName: 'service' }); expect(wrapper.vm.canSubmit).toBe(true)
  })
  test('preserve request submits no DATA delete IDs and reuses the approved fingerprint', async () => {
    await start(); await wrapper.setData({ stopConfirmed: true, confirmName: 'service' }); wrapper.vm.submit(); await flushPromises()
    expect(postAPI).toHaveBeenCalledWith('destroySharedFileSystem', expect.objectContaining({ volumepolicy: 'PRESERVE', deletevolumeids: undefined, expunge: false, planfingerprint: 'approved', confirmresource: 'service', forced: true }), { preserveOnFailure: true })
    expect(wrapper.vm.submitting).toBe(true)
  })
  test('selected deletion requires loss acknowledgement and transmits only selected UUIDs', async () => {
    await start(); await wrapper.setData({ policy: 'DELETE_SELECTED', selected: [wrapper.vm.volumes[0].uuid], stopConfirmed: true, confirmName: 'service', expunge: true })
    expect(wrapper.vm.canSubmit).toBe(false); await wrapper.setData({ lossConfirmed: true }); wrapper.vm.submit(); await flushPromises()
    expect(postAPI).toHaveBeenCalledWith('destroySharedFileSystem', expect.objectContaining({ deletevolumeids: native().plan.volumes[0].uuid, confirmdataloss: true, expunge: true }), { preserveOnFailure: true })
  })
  test('native recovery response preserves original failure and resumes the same operation', async () => {
    await start('RECOVERY_REQUIRED'); expect(wrapper.vm.operation.originalfailure.code).toBe('DATA_DETACH_UNCONFIRMED')
    await wrapper.vm.resume(); expect(postAPI).toHaveBeenCalledWith('resumeSharedFileSystemDeletion', { operationid: native().operationid, requestid: native().requestid }, { preserveOnFailure: true })
    expect(wrapper.vm.submitting).toBe(true) // old terminal observation cannot end a still-pending resume job
    observed = { ...native(), state: 'COMPLETED' }; jobStatus = 1; await wrapper.vm.observe()
    expect(wrapper.vm.operation.state).toBe('COMPLETED'); expect(wrapper.vm.submitting).toBe(false)
  })
  test('closing a recoverable destroy result cannot accidentally expunge', async () => {
    await start('SOFT_DESTROYED'); expect(wrapper.vm.showPlan).toBe(false); expect(wrapper.vm.expunge).toBe(false); expect(postAPI).not.toHaveBeenCalled()
  })
  test('explicit expunge reuses the immutable soft-destroy plan', async () => {
    await start('SOFT_DESTROYED', 'expungeSharedFileSystem'); expect(wrapper.vm.frozen).toBe(true)
    await wrapper.setData({ stopConfirmed: true, confirmName: 'service' }); wrapper.vm.submit(); await flushPromises()
    expect(postAPI).toHaveBeenCalledWith('resumeSharedFileSystemDeletion', { operationid: native().operationid, requestid: native().requestid }, { preserveOnFailure: true })
  })
  test('transport loss preserves request tracking without allowing a fresh destructive submission', async () => {
    await start(); postAPI.mockRejectedValue(new Error('transport lost')); await wrapper.setData({ stopConfirmed: true, confirmName: 'service' }); wrapper.vm.submit(); await flushPromises()
    expect(wrapper.vm.operation.state).toBe('OBSERVATION_PENDING'); expect(wrapper.vm.showPlan).toBe(false); expect(wrapper.vm.canSubmit).toBe(false)
    expect(wrapper.vm.requestId).toBeTruthy(); expect(postAPI).toHaveBeenCalledTimes(1)
  })
  test('unmount cancels UI polling without submitting cancellation or a second deletion', async () => {
    await start('RUNNING'); expect(jest.getTimerCount()).toBe(1); wrapper.unmount(); wrapper = null; expect(jest.getTimerCount()).toBe(0); expect(postAPI).not.toHaveBeenCalled()
  })
  test('a late POST response from a closed instance cannot start new polling', async () => {
    await start(); let finish
    postAPI.mockImplementation(() => new Promise(resolve => { finish = resolve }))
    await wrapper.setData({ stopConfirmed: true, confirmName: 'service' }); wrapper.vm.submit(); await flushPromises()
    const calls = getAPI.mock.calls.length; wrapper.unmount(); wrapper = null
    finish({ destroysharedfilesystemresponse: { jobid: 'late-job' } }); await flushPromises()
    expect(getAPI).toHaveBeenCalledTimes(calls); expect(jest.getTimerCount()).toBe(0)
  })
  test('group review advances only after one target completed and clears approvals', async () => {
    await start(); await wrapper.setData({ groupTargets: [{ id: 'one', name: 'one' }, { id: 'two', name: 'two' }], operation: { ...native(), state: 'COMPLETED' }, selected: ['old-volume'], confirmName: 'one', stopConfirmed: true, lossConfirmed: true, requestId: 'old-request' })
    observed = { state: 'NOT_FOUND' }; wrapper.vm.nextTarget(); await flushPromises()
    expect(wrapper.vm.target.id).toBe('two'); expect(wrapper.vm.selected).toEqual([]); expect(wrapper.vm.confirmName).toBe(''); expect(wrapper.vm.requestId).toBe(''); expect(wrapper.vm.canSubmit).toBe(false)
    expect(postAPI).not.toHaveBeenCalled()
  })

  test('a definite API input rejection preserves its actual error and requires a fresh impact review', async () => {
    await start(); postAPI.mockRejectedValue({ response: { status: 431, data: { errorresponse: { errortext: 'DELETION_PLAN_INVALID' } } } })
    await wrapper.setData({ stopConfirmed: true, confirmName: 'service' }); wrapper.vm.submit(); await flushPromises()
    expect(wrapper.vm.error).toBe('DELETION_PLAN_INVALID'); expect(wrapper.vm.operation).toBe(null); expect(wrapper.vm.requestId).toBe(''); expect(wrapper.vm.canSubmit).toBe(false)
  })
  test('a running record with no observed worker remains the same resumable operation', async () => {
    await start('RUNNING'); observed = { ...native(), state: 'RUNNING', workeractive: false, canresume: true }; await wrapper.vm.observe()
    expect(wrapper.vm.operation.operationid).toBe(native().operationid); expect(wrapper.vm.operation.canresume).toBe(true); expect(wrapper.vm.submitting).toBe(false); expect(postAPI).not.toHaveBeenCalled()
  })
})
