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

import fs from 'fs'
import path from 'path'
import { bootStatuses, bootPhases, bootVerificationKinds } from '@/utils/storageBootReconcile'
import { networkMismatchCodes } from '@/utils/storageNetworkReadiness'
import { nfsProbeSkipReasons } from '@/utils/storageNfsProbe'
import { storagePreparationReasons } from '@/utils/storagePreparation'

const uiRoot = path.resolve(__dirname, '../../../..')
const sourceFiles = [
  'src/views/storage/SharedFSTab.vue',
  'src/views/storage/CreateSharedFS.vue',
  'src/views/storage/SmbCreationOptions.vue',
  'src/views/storage/PosixDirectoryPolicies.vue',
  'src/views/storage/PosixPolicyInheritance.vue',
  'src/views/storage/StorageServiceConfiguration.vue',
  'src/views/storage/StorageOperationHistory.vue',
  'src/views/storage/StorageVolumePreparation.vue',
  'src/views/storage/StorageNetworkStatus.vue',
  'src/views/storage/StorageProtocolAddressStatus.vue',
  'src/views/storage/StorageBootReconcileStatus.vue',
  'src/views/storage/StorageNfsProbeStatus.vue'
]
const localeFiles = {
  en: 'public/locales/en.json',
  ko_KR: 'public/locales/ko_KR.json'
}
const storagePrefixes = ['label.storage.boot.', 'message.storage.boot.', 'label.storage.service.', 'message.storage.service.', 'label.posix.directory.', 'message.posix.directory.', 'label.storage.config.', 'message.storage.config.', 'label.storage.operation.', 'message.storage.operation.', 'label.storage.volume.', 'message.storage.volume.']

const readUiFile = relativePath => fs.readFileSync(path.join(uiRoot, relativePath), 'utf8')
const isStorageKey = key => storagePrefixes.some(prefix => key.startsWith(prefix))

const literalStorageKeys = () => {
  const keyPattern = /\$t\(\s*['"]([^'"]+)['"]\s*[,)]/g
  const keys = new Set()

  sourceFiles.forEach(sourceFile => {
    const source = readUiFile(sourceFile)
    let match
    while ((match = keyPattern.exec(source)) !== null) {
      if (isStorageKey(match[1])) {
        keys.add(match[1])
      }
    }
  })

  return [...keys].sort()
}

const localeMessages = Object.fromEntries(
  Object.entries(localeFiles).map(([locale, localeFile]) => [
    locale,
    JSON.parse(readUiFile(localeFile))
  ])
)

describe('SharedFS runtime locale contract', () => {
  it.each(Object.keys(localeFiles))('defines every literal Storage Service key in %s', locale => {
    const messages = localeMessages[locale]
    const missing = literalStorageKeys().filter(key => {
      const value = messages[key]
      return typeof value !== 'string' || value.trim() === '' || value === key
    })

    expect(missing).toEqual([])
  })

  it('keeps English and Korean Storage Service key sets aligned', () => {
    const storageKeys = locale => Object.keys(localeMessages[locale])
      .filter(isStorageKey)
      .sort()

    expect(storageKeys('ko_KR')).toEqual(storageKeys('en'))
  })
})

describe('Managed operation terminal-state locale contract', () => {
  const states = ['RUNNING', 'COMPLETE', 'COMPLETE_NO_CONFIG_CHANGE', 'BLOCKED', 'CANCELLED', 'ROLLED_BACK', 'RECOVERY_REQUIRED']
  it.each(Object.keys(localeFiles))('defines every consumed managed operation state in %s', locale => {
    const missing = states.filter(state => {
      const key = 'label.storage.operation.state.' + state
      const value = localeMessages[locale][key]
      return typeof value !== 'string' || !value.trim() || value === key
    })
    expect(missing).toEqual([])
    expect(localeMessages[locale]['label.storage.operation.state.CANCELLED']).toBe(locale === 'ko_KR' ? '취소됨' : 'Cancelled')
  })
})

describe('Volume preparation phase locale contract', () => {
  const phases = ['WAITING_DEVICE', 'VERIFYING_IDENTITY', 'FORMATTING', 'VERIFYING_FILESYSTEM', 'FILESYSTEM_VERIFIED', 'MOUNTING', 'PERSISTING_MOUNT', 'APPLYING_SERVICE', 'COMPLETE', 'TIMED_OUT_PENDING_RECONCILE', 'RECOVERY_REQUIRED', 'RECONCILE_REQUIRED', 'NOT_STARTED', 'IDENTITY_MISMATCH', 'ERROR']
  it.each(Object.keys(localeFiles))('defines every preparation phase in %s', locale => {
    for (const phase of phases) expect(localeMessages[locale]['label.storage.volume.phase.' + phase]).toEqual(expect.any(String))
  })
})

describe('#913 network readiness locale coverage', () => {
  it.each(Object.keys(localeFiles))('defines every public mismatch and validation state in %s', locale => {
    const keys = [
      'message.sharedfs.static.gateway.invalid',
      ...Array.from(readUiFile('src/views/storage/StorageNetworkStatus.vue').matchAll(/\{ key: '([^']+)'/g), match => 'label.storage.service.network.' + match[1]),
      ...networkMismatchCodes.map(code => 'message.storage.service.network.reason.' + code),
      ...['OK', 'ERROR', 'UNKNOWN'].map(status => 'label.storage.service.network.status.' + status)
    ]
    for (const key of keys) expect(localeMessages[locale][key]).toEqual(expect.any(String))
    expect(keys.filter(key => !localeMessages[locale][key] || localeMessages[locale][key] === key)).toEqual([])
  })
})

describe('#914 boot reconciliation public state locale coverage', () => {
  it.each(Object.keys(localeFiles))('defines boot states, phases, application and probe labels in %s', locale => {
    const keys = [
      ...[...bootStatuses, 'UNKNOWN'].map(value => 'label.storage.boot.status.' + value),
      ...bootPhases.map(value => 'label.storage.boot.phase.' + value),
      ...bootVerificationKinds.map(value => 'label.storage.boot.verification.' + value),
      ...['PENDING', 'APPLYING', 'APPLIED', 'VERIFIED', 'FAILED', 'NOT_REQUIRED', 'HELD'].map(value => 'label.storage.boot.apply.' + value),
      ...['NOT_STARTED', 'PENDING', 'VERIFIED', 'FAILED', 'NOT_REQUIRED'].map(value => 'label.storage.boot.probe.' + value),
      ...Array.from(readUiFile('src/views/storage/StorageBootReconcileStatus.vue').matchAll(/['"]((?:label|message)\.storage\.boot\.[A-Za-z0-9_.]+)['"]/g), match => match[1]).filter(key => !key.endsWith('.'))
    ]
    expect(keys.filter(key => !localeMessages[locale][key] || localeMessages[locale][key] === key)).toEqual([])
  })
})

describe('#918 NFS local probe locale coverage', () => {
  it.each(Object.keys(localeFiles))('defines the separate mount, skip, dynamic and failure states in %s', locale => {
    const keys = [
      ...['VERIFIED', 'SKIPPED_ACL', 'SKIPPED_DYNAMIC', 'FAILED', 'UNKNOWN'].map(value => 'label.storage.service.nfs.probe.' + value),
      ...nfsProbeSkipReasons.map(value => 'message.storage.service.nfs.probe.' + value),
      'message.storage.service.nfs.probe.external.separate',
      'label.storage.service.nfs.applied.clients',
      'label.storage.service.nfs.local.verification'
    ]
    expect(keys.filter(key => !localeMessages[locale][key] || localeMessages[locale][key] === key)).toEqual([])
  })
})

describe('Preparation observation presentation locale coverage', () => {
  it.each(Object.keys(localeFiles))('defines the shared reason model and distinct checking states in %s', locale => {
    const keys = [
      ...storagePreparationReasons.map(reason => 'message.storage.service.preparation.reason.' + reason),
      'message.storage.service.preparation.reason.UNKNOWN',
      'label.storage.service.preparation.status.CHECKING',
      'label.storage.service.preparation.status.CONFIRMING',
      'message.storage.service.preparation.observation.expired',
      'message.storage.service.preparation.last.observed',
      'message.storage.service.preparation.update.required',
      'message.storage.service.create.preparation.services.not.started',
      'message.storage.service.create.preparation.stopped.before.services',
      'message.storage.service.create.preparation.creation.unconfirmed',
      'label.storage.service.preparation.diagnostics',
      'message.storage.service.preparation.diagnostics.first.failure',
      'message.storage.service.preparation.diagnostics.observation',
      ...['nativeBootBefore', 'nativeBootAfter', 'healthBootId', 'bootProgressBootId', 'healthOrigin', 'healthStatus', 'networkReadinessStatus', 'bootProgressStatus', 'failedObservation', 'healthSuccess', 'bootProgressPresent', 'healthGeneratedEpoch', 'healthAgeMillis', 'observationDurationMillis'].map(field => 'label.storage.service.preparation.diagnostic.' + field)
    ]
    expect(keys.filter(key => !localeMessages[locale][key] || localeMessages[locale][key] === key)).toEqual([])
  })
})

describe('iSCSI input and public failure locale coverage', () => {
  it.each(Object.keys(localeFiles))('defines every iSCSI pre-allocation and terminal failure message in %s', locale => {
    const keys = [
      ...['ISCSI_TARGET_IQN_INVALID', 'ISCSI_INITIATOR_IQN_INVALID', 'ISCSI_TARGET_LUN_INVALID', 'ISCSI_MAPPED_LUN_UNSUPPORTED', 'ISCSI_LISTENER_GROUP_REQUIRED', 'ISCSI_LISTENER_GROUP_INVALID', 'ISCSI_LISTENER_NOT_REGISTERED'].map(code => 'message.storage.service.iscsi.input.' + code),
      ...['target.name', 'initiator.name', 'target.lun', 'mapped.lun', 'ownership', 'credential', 'authentication', 'apply', 'verify', 'recovery.required', 'rolled.back'].map(code => 'message.storage.service.iscsi.failure.' + code)
    ]
    expect(keys.filter(key => !localeMessages[locale][key] || localeMessages[locale][key] === key)).toEqual([])
  })
})

describe('Protocol address and SOURCE failure locale coverage', () => {
  it.each(Object.keys(localeFiles))('defines current address evidence and each closed activation cause in %s', locale => {
    const keys = [
      ...['EMPTY', 'OBSERVED', 'NOT_OBSERVED', 'UNKNOWN', 'ERROR'].flatMap(status => ['label.storage.service.activation.address.' + status, 'message.storage.service.activation.address.' + status]),
      ...['source', 'address', 'runtime', 'source.restore', 'unsupported', 'recovery.required', 'rolled.back'].map(cause => 'message.storage.service.activation.failure.' + cause),
      'message.storage.service.activation.address.reuse',
      'message.storage.service.activation.address.new',
      'message.storage.service.activation.port.independent',
      'label.storage.service.activation.port',
      'label.storage.service.activation.address.observation'
    ]
    expect(keys.filter(key => !localeMessages[locale][key] || localeMessages[locale][key] === key)).toEqual([])
  })
})
