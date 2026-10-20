// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements. See the NOTICE file
// distributed with this work for additional information.
import { readStoragePreparation, readyAtSameBoot, storagePreparationWaitable, readStoragePreparationDiagnostics, storagePreparationDiagnosticSnapshot } from '@/utils/storagePreparation'

const expected = { sharedfilesystemid: 'fs-1', instanceid: 'instance-1', purpose: 'INITIAL_CONFIG' }
const now = 100000
const receipt = overrides => ({
  id: 'instance-1',
  sharedfilesystemid: 'fs-1',
  purpose: 'INITIAL_CONFIG',
  ready: true,
  status: 'READY',
  bootid: 'boot-1',
  observedatmillis: now - 1000,
  expiresatmillis: now + 29000,
  ...overrides
})
const envelope = value => ({ getstorageservicepreparationstatusresponse: { storageservicepreparationstatus: value } })

describe('Storage preparation receipt admission', () => {
  it('accepts READY without an existing configuration generation or protocol inventory', () => {
    expect(readStoragePreparation(envelope(receipt({ configurationstate: 'UNCONFIGURED' })), expected, now).ready).toBe(true)
  })

  it.each([
    { sharedfilesystemid: 'other-fs' }, { id: 'other-instance' }, { purpose: 'CONFIG_RESTORE' },
    { ready: 'true' }, { ready: false }, { status: 'Running' }, { bootid: '' },
    { observedatmillis: now + 5001 }, { observedatmillis: '99000' }, { expiresatmillis: now },
    { expiresatmillis: now + 90000 }, { expiresatmillis: null }, { errorcode: 530 }
  ])('rejects mismatched, stale or malformed receipt %j', change => {
    expect(() => readStoragePreparation(envelope(receipt(change)), expected, now)).toThrow('STORAGE_PREPARATION_UNVERIFIED')
  })

  it.each([{}, null, [], { storageservicepreparationstatus: receipt() }, { getstorageservicepreparationstatusresponse: receipt() }])('requires the exact registered response envelope %j', response => {
    expect(() => readStoragePreparation(response, expected, now)).toThrow('STORAGE_PREPARATION_UNVERIFIED')
  })

  it('accepts the new SharedFS waiting response before an instance UUID exists', () => {
    const value = readStoragePreparation(envelope(receipt({ id: undefined, status: 'WAITING_VM', ready: false, bootid: undefined, reason: 'INSTANCE_NOT_ATTACHED' })), { sharedfilesystemid: 'fs-1', purpose: 'INITIAL_CONFIG' }, now)
    expect(value.ready).toBe(false)
    expect(storagePreparationWaitable(value.status)).toBe(true)
  })

  it.each(['WAITING_VM', 'WAITING_BOOT', 'WAITING_WRITER'])('retains an observed waiting state without admitting a mutation: %s', status => {
    const value = readStoragePreparation(envelope(receipt({ status, ready: false, bootid: undefined })), expected, now)
    expect(value.ready).toBe(false)
    expect(storagePreparationWaitable(value.status)).toBe(true)
    expect(readyAtSameBoot(value, receipt(), now)).toBe(false)
  })

  it.each(['BLOCKED', 'OBSERVATION_UNAVAILABLE'])('stops polling for operator/error state %s', status => {
    const value = readStoragePreparation(envelope(receipt({ status, ready: false })), expected, now)
    expect(storagePreparationWaitable(value.status)).toBe(false)
  })

  it('requires distinct fresh observations from the same instance, purpose and BOOT', () => {
    const first = receipt()
    const next = receipt({ observedatmillis: now })
    expect(readyAtSameBoot(first, next, now)).toBe(true)
    expect(readyAtSameBoot(first, first, now)).toBe(false)
    for (const change of [{ bootid: 'boot-2' }, { id: 'instance-2' }, { sharedfilesystemid: 'fs-2' }, { purpose: 'RUNTIME_UPGRADE' }, { expiresatmillis: now }, { ready: false }]) {
      expect(readyAtSameBoot(first, { ...next, ...change }, now)).toBe(false)
    }
    expect(readyAtSameBoot({ ...first, expiresatmillis: now }, next, now)).toBe(false)
  })
})

describe('Bounded public preparation diagnostics', () => {
  const boot = 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa'
  it('retains only the closed public schema and never stores raw health, configuration or authentication fields', () => {
    const value = readStoragePreparation(envelope(receipt({
      diagnostics: JSON.stringify({ nativeBootBefore: boot, nativeBootAfter: boot, healthOrigin: 'LIVE', healthSuccess: true, healthStatus: 'ok', bootProgressPresent: false, healthAgeMillis: 150, observationDurationMillis: 250, rawHealth: { password: 'synthetic-hidden-value' }, secret: 'synthetic-hidden-value', failedObservation: 'HEALTH' }),
      rawHealth: { password: 'synthetic-hidden-value' },
      message: 'synthetic-hidden-value'
    })), expected, now)
    expect(value.diagnostics).toEqual({ nativeBootBefore: boot, nativeBootAfter: boot, healthOrigin: 'LIVE', healthSuccess: true, healthStatus: 'ok', bootProgressPresent: false, healthAgeMillis: 150, observationDurationMillis: 250, failedObservation: 'HEALTH' })
    expect(JSON.stringify(value)).not.toContain('synthetic-hidden-value')
    expect(value.rawHealth).toBeUndefined()
    expect(value.message).toBeUndefined()
  })

  it.each([null, [], 'broken JSON', '{"healthOrigin":"' + 'a'.repeat(8192) + '"}'])('discards optional malformed diagnostics without rejecting a valid preparation receipt %p', diagnostics => {
    const value = readStoragePreparation(envelope(receipt({ diagnostics })), expected, now)
    expect(value.ready).toBe(true)
    expect(value.diagnostics).toEqual({})
  })

  it('drops wrong types and arbitrary diagnostic enum strings without retaining raw values', () => {
    expect(readStoragePreparationDiagnostics({ nativeBootBefore: 'synthetic-hidden-value', healthOrigin: 'synthetic-hidden-value', healthSuccess: 'true', healthAgeMillis: {}, observationDurationMillis: -1, bootProgressPresent: [], healthStatus: { raw: 'synthetic-hidden-value' } })).toEqual({})
  })

  it('projects diagnostic snapshots separately from the API receipt and drops unknown reason text', () => {
    const snapshot = storagePreparationDiagnosticSnapshot(receipt({ diagnostics: JSON.stringify({ healthOrigin: 'LIVE', raw: 'synthetic-hidden-value' }), reason: 'synthetic-hidden-value', raw: 'synthetic-hidden-value' }))
    expect(snapshot.reason).toBe('UNKNOWN')
    expect(snapshot.diagnostics).toEqual({ healthOrigin: 'LIVE' })
    expect(JSON.stringify(snapshot)).not.toContain('synthetic-hidden-value')
  })
})
