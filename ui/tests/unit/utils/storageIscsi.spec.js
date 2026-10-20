// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements. See the NOTICE file
// distributed with this work for additional information.
import { isValidIscsiIqn, isValidIscsiLun, iscsiTargetInput, defaultIscsiTargetIqn, iscsiFailurePresentation, iscsiGroupHasAcl, iscsiMappedGroupSupported } from '@/utils/storageIscsi'

describe('iSCSI shared ASCII naming contract', () => {
  it.each(['iqn.2026-05.local.storage:target01', 'iqn.2026-10.com.example:tenant.target-1', 'iqn.1999-12.org.vendor-name:vol_1:2', 'iqn.2026-10.COM.EXAMPLE:UppercaseSuffix'])('accepts generic IQN %s without using a naming suffix as ownership', name => {
    expect(isValidIscsiIqn(name)).toBe(true)
  })
  it.each(['', 'iqn.2026-10.com.example:', 'iqn.2026-10.com.example:target ', ' iqn.2026-10.com.example:target', 'iqn.2026-10.com.example:target\n', 'iqn.2026-10.com.example:대상', 'iqn.2026-10.com.example:target/1', 'nqn.2026-10.com.example:target', 'iqn.2026-1.com.example:target', 'iqn.2026-10.com.example:' + 'a'.repeat(223), null, true, []])('rejects malformed IQN without trimming or echoing it: %p', name => {
    expect(isValidIscsiIqn(name)).toBe(false)
  })
  it('uses exactly 223 ASCII characters and keeps generated defaults valid for localized or long service names', () => {
    const prefix = 'iqn.2026-10.com.example:'
    expect(isValidIscsiIqn(prefix + 'a'.repeat(223 - prefix.length))).toBe(true)
    for (const name of ['공유 파일 서비스', 'a'.repeat(400), '', 'service one']) {
      expect(isValidIscsiIqn(defaultIscsiTargetIqn(name, '1234'))).toBe(true)
    }
  })
  it.each([0, '0', '0001', '255', 255, '256', 65535])('accepts mapped LUN %p', value => expect(isValidIscsiLun(value)).toBe(true))
  it.each(['', null, false, [], -1, '65536', 65536, '1.2', '1\n', ' 1', '+1', Infinity])('rejects invalid mapped LUN %p', value => expect(isValidIscsiLun(value)).toBe(false))

  it('keeps large target LUNs but rejects unsupported mapped LUNs only when ACLs are present', () => {
    const target = { targetname: 'iqn.2026-10.com.example:target', lun: '65535', listenerports: [3260] }
    expect(iscsiTargetInput(target, [3260]).valid).toBe(true)
    expect(iscsiTargetInput(target, [3260], true).errors.lun).toBe('ISCSI_MAPPED_LUN_UNSUPPORTED')
    const targets = [{ id: 'first', targetname: target.targetname, lunornamespace: '0' }, { id: 'last', targetname: target.targetname, lunornamespace: '256' }]
    expect(iscsiGroupHasAcl(targets, [{ resourceid: 'first', state: 'Ready' }], target.targetname)).toBe(true)
    expect(iscsiMappedGroupSupported(targets, 'first')).toBe(false)
    expect(iscsiMappedGroupSupported([{ id: 'first', targetname: target.targetname, targetluns: '0,256' }], 'first')).toBe(false)
    expect(iscsiMappedGroupSupported([{ id: 'first', targetname: target.targetname, targetluns: '0,255' }], 'first')).toBe(true)
    expect(iscsiMappedGroupSupported([{ ...targets[0], state: 'Ready', targetluns: '0,256' }, { ...targets[1], state: 'Disabled', targetluns: '0,256' }], 'first')).toBe(true)
  })

  it('requires a complete integer selection from registered listener ports without inventing default 3260', () => {
    const target = { targetname: 'iqn.2026-10.com.example:target', lun: '0' }
    expect(iscsiTargetInput({ ...target, listenerports: [3261] }, [3261]).valid).toBe(true)
    for (const listenerports of [[], null, [3260], [3261, 3262], [3261, 'bad'], [3261.5], ['3261\n']]) {
      expect(iscsiTargetInput({ ...target, listenerports }, [3261]).valid).toBe(false)
    }
    expect(iscsiTargetInput({ ...target, listenerports: [3261, '3261'] }, [3261]).ports).toEqual([3261])
  })
})

describe('iSCSI closed public failure presentation', () => {
  it.each([
    ['ISCSI_TARGET_IQN_INVALID', 'target.name'],
    ['category=INITIATOR_IQN_INVALID', 'initiator.name'],
    ['ISCSI_TARGET_LUN_INVALID', 'target.lun'],
    ['ISCSI_MAPPED_LUN_UNSUPPORTED', 'mapped.lun'],
    ['ISCSI guard=AUTH; returnCode=UNAVAILABLE; category=OWNERSHIP_MISMATCH', 'ownership'],
    ['category=OWNERSHIP_INVALID', 'ownership'],
    ['category=AUTH_VALUE_INVALID', 'credential'],
    ['failurePhase=APPLYING; something private', 'apply'],
    ['failurePhase=VERIFYING | something private', 'verify']
  ])('extracts only the fixed public category or phase from %s', (errortext, category) => {
    const value = iscsiFailurePresentation({ jobresult: { errortext: errortext + ' synthetic-secret-value' } })
    expect(value.messageKey).toBe('message.storage.service.iscsi.failure.' + category)
    expect(JSON.stringify(value)).not.toContain('synthetic-secret-value')
  })
  it('distinguishes verified rollback from required recovery without inferring success from a rollback attempt', () => {
    expect(iscsiFailurePresentation({ errortext: 'ROLLED_BACK: category=AUTH_VALUE_INVALID' })).toMatchObject({ rolledBack: true, recoveryRequired: false })
    expect(iscsiFailurePresentation({ errortext: 'BLOCKED: category=OWNERSHIP_INVALID | rollback: failed' })).toMatchObject({ rolledBack: false, recoveryRequired: true })
    expect(iscsiFailurePresentation({ errortext: '| rollback: attempted' }).rolledBack).toBe(false)
  })
  it('keeps unknown text and nested HTTP error bodies private', () => {
    expect(iscsiFailurePresentation(new Error('synthetic-secret-value')).messageKey).toBeNull()
    const value = iscsiFailurePresentation({ response: { data: { createstorageiscsitargetresponse: { errortext: 'ISCSI_TARGET_IQN_INVALID synthetic-secret-value' } } } })
    expect(value.messageKey).toBe('message.storage.service.iscsi.failure.target.name')
    expect(JSON.stringify(value)).not.toContain('synthetic-secret-value')
  })
})
