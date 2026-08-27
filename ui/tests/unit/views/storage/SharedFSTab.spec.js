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

import SharedFSTab from '@/views/storage/SharedFSTab'

describe('SharedFSTab protocol listener inventory', () => {
  it('uses API effective endpoints for wildcard listeners', () => {
    const context = {
      $t: key => key,
      serviceEndpoints: ['10.10.254.10', '10.10.22.201'],
      isWildcardListenIp: value => value === '0.0.0.0',
      protocolListenerEntries: () => [{
        listenIp: '0.0.0.0',
        port: 2049,
        listenerType: 'WILDCARD',
        effectiveEndpoints: [
          { ipaddress: '10.10.254.10', port: 2049 },
          { ipaddress: '10.10.22.201', port: 2049 }
        ],
        linkedResourceCount: 2,
        runtimeState: 'READY',
        raw: { id: 'listener-id', protocol: 'NFS' }
      }]
    }

    const rows = SharedFSTab.methods.protocolListenerRows.call(context, 'NFS')

    expect(rows).toHaveLength(1)
    expect(rows[0].effectiveEndpoints).toBe('10.10.254.10:2049, 10.10.22.201:2049')
    expect(rows[0].linkedResourceCount).toBe(2)
    expect(rows[0].canDelete).toBe(false)
  })

  it('does not fabricate a listener when the API returns no rows', () => {
    const context = {
      storageService: { protocols: [] },
      defaultProtocolPort: () => 2049,
      boolValue: value => Boolean(value)
    }

    const entries = SharedFSTab.methods.protocolListenerEntries.call(context, 'NFS')

    expect(entries).toEqual([])
  })

  it('collapses a dedicated listener covered by an equivalent wildcard listener', () => {
    const context = {
      storageService: {
        protocols: [
          { id: 'wildcard', protocol: 'NFS', listenip: '0.0.0.0', port: 2049, state: 'Ready', linkedresourcecount: 2 },
          { id: 'primary', protocol: 'NFS', listenip: '10.10.254.10', port: 2049, state: 'Ready', linkedresourcecount: 2 }
        ]
      },
      defaultProtocolPort: () => 2049,
      boolValue: value => Boolean(value),
      isWildcardListenIp: value => value === '0.0.0.0'
    }

    const entries = SharedFSTab.methods.protocolListenerEntries.call(context, 'NFS')

    expect(entries).toHaveLength(1)
    expect(entries[0].listenIp).toBe('0.0.0.0')
  })

  it('keeps SMB dedicated listeners scoped to API effective endpoints', () => {
    const context = {
      serviceEndpoint: '10.10.254.10',
      serviceEndpoints: ['10.10.254.10', '10.10.22.201'],
      smbEffectivePorts: [445],
      isWildcardListenIp: value => value === '0.0.0.0',
      protocolListenerEntries: () => [{
        listenIp: '10.10.22.201',
        port: 445,
        effectiveEndpoints: [{ ipaddress: '10.10.22.201', port: 445 }]
      }]
    }

    const pairs = SharedFSTab.computed.smbEffectiveEndpointPairs.call(context)

    expect(pairs).toEqual([{ key: '10.10.22.201:445', ip: '10.10.22.201', port: 445 }])
  })

  it('uses canonical iSCSI endpoints in the status summary', () => {
    const context = {
      iscsiListenerRows: [{ effectiveEndpoints: '10.10.22.202:3261' }],
      storageService: { iscsiTargets: [] },
      serviceEndpoint: '10.10.254.10',
      protocolEndpointValues: SharedFSTab.methods.protocolEndpointValues,
      parseStorageConfig: () => ({}),
      normalizeListenerPorts: () => [],
      formatIscsiListenerGroupEndpoints: () => '-',
      defaultProtocolPort: () => 3260
    }

    const summary = SharedFSTab.computed.iscsiEndpointSummary.call(context)

    expect(summary).toBe('10.10.22.202:3261')
    expect(summary).not.toContain('10.10.254.10:3260')
  })

  it('never exposes the NFS wildcard address in connection commands', () => {
    const context = {
      $t: key => key,
      nfsListenerRows: [{ effectiveEndpoints: '10.10.254.10:2049, 10.10.22.201:2049' }],
      serviceEndpoints: ['10.10.254.10', '10.10.22.201'],
      protocolEndpointValues: SharedFSTab.methods.protocolEndpointValues,
      splitEndpointValue: SharedFSTab.methods.splitEndpointValue,
      nfsRuntimeProtocolEntries: () => [{ listenIp: '0.0.0.0', port: 2049 }],
      nfsRuntimeProtocolMode: () => 'V4_ONLY',
      nfsRuntimePort: () => 2049,
      defaultProtocolPort: () => 2049,
      isWildcardListenIp: value => value === '0.0.0.0'
    }

    const commands = SharedFSTab.computed.nfsConnectionCommands.call(context)

    expect(commands).toHaveLength(1)
    expect(commands[0]).not.toContain('0.0.0.0')
    expect(commands[0]).toContain('<label.storage.service.endpoint.ip.placeholder>')
    expect(commands[0]).toContain('port=2049')
  })
})

describe('SharedFSTab operational evidence', () => {
  const sessionContext = sessions => ({
    $t: key => key,
    protocolSessions: () => sessions,
    possibleSessionValues: () => '',
    booleanLabel: SharedFSTab.methods.booleanLabel,
    iscsiMappingStatusLabel: value => value,
    iscsiEndpointMappingStatusLabel: value => value,
    iscsiAuthVerificationLabel: SharedFSTab.methods.iscsiAuthVerificationLabel
  })

  it('renders schema v2 CHAP evidence as verified without exposing secrets', () => {
    const context = sessionContext([{
      sessionId: 'session-1',
      chapConfigured: true,
      authVerification: 'VERIFIED',
      authenticated: true
    }])

    const rows = SharedFSTab.computed.iscsiSessionRows.call(context)

    expect(rows[0].chapConfiguredLabel).toBe('label.yes')
    expect(rows[0].authVerification).toBe('VERIFIED')
    expect(rows[0].authVerificationLabel).toBe('label.storage.service.authentication.verified')
    expect(JSON.stringify(rows[0])).not.toMatch(/password|secret|chapkey/i)
  })

  it('keeps legacy negative authentication observations unknown', () => {
    const context = sessionContext([{ sessionId: 'session-v1', authenticated: false }])

    const rows = SharedFSTab.computed.iscsiSessionRows.call(context)

    expect(rows[0].authVerification).toBe('UNKNOWN')
    expect(rows[0].authVerificationLabel).toBe('label.storage.service.authentication.unknown')
  })

  it('shows the guarded NIC repair only when drift and API capability are both present', () => {
    const visible = SharedFSTab.computed.canRepairStorageIdentity.call({
      storageIdentityDrift: true,
      storageService: { instance: { id: 'instance-1' } },
      $store: { getters: { apis: { repairStorageServiceNicIdentity: {} } } }
    })
    const hidden = SharedFSTab.computed.canRepairStorageIdentity.call({
      storageIdentityDrift: false,
      storageService: { instance: { id: 'instance-1' } },
      $store: { getters: { apis: { repairStorageServiceNicIdentity: {} } } }
    })

    expect(visible).toBe(true)
    expect(hidden).toBe(false)
  })
})

describe('SharedFSTab iSCSI CHAP validation', () => {
  const validationContext = iscsiAcl => {
    const errors = []
    return {
      errors,
      context: {
        $t: key => key,
        $message: { error: message => errors.push(message) },
        forms: { iscsiAcl }
      }
    }
  }

  it('uses the one-way CHAP credential message without exposing entered values', () => {
    const { context, errors } = validationContext({
      chapenabled: true,
      chapusername: 'private-user',
      chapsecret: '',
      mutualchapenabled: false
    })

    expect(SharedFSTab.methods.validateIscsiChapForm.call(context)).toBe(false)
    expect(errors).toEqual(['message.storage.service.iscsi.chap.credential.required'])
    expect(JSON.stringify(errors)).not.toContain('private-user')
  })

  it('uses the mutual CHAP credential message without exposing entered secrets', () => {
    const { context, errors } = validationContext({
      chapenabled: true,
      chapusername: 'ablecloud',
      chapsecret: 'one-way-secret',
      mutualchapenabled: true,
      mutualchapusername: 'controller-user',
      mutualchapsecret: ''
    })

    expect(SharedFSTab.methods.validateIscsiChapForm.call(context)).toBe(false)
    expect(errors).toEqual(['message.storage.service.iscsi.mutual.chap.credential.required'])
    expect(JSON.stringify(errors)).not.toMatch(/ablecloud|one-way-secret|controller-user/)
  })
})

describe('SharedFSTab protocol-scoped presentation', () => {
  const translate = (key, params = {}) => `${key}:${params.count ?? ''}`

  it('does not leak an NVMe-oF warning into an exactly mapped iSCSI session', () => {
    const warning = SharedFSTab.computed.iscsiSessionRuntimeWarning.call({
      $t: translate,
      sessionsRuntime: {
        status: 'degraded',
        observedIscsiTcpCount: 1,
        warnings: ['NVMe-oF controller mapping is ambiguous.']
      },
      protocolSessions: protocol => protocol === 'ISCSI' ? [{ mappingStatus: 'EXACT' }] : []
    })

    expect(warning).toBe('')
  })

  it('shows a translated iSCSI warning when transport exists without a logical row', () => {
    const warning = SharedFSTab.computed.iscsiSessionRuntimeWarning.call({
      $t: translate,
      sessionsRuntime: {
        status: 'degraded',
        observedIscsiTcpCount: 2,
        warnings: ['NVMe-oF controller mapping is ambiguous.']
      },
      protocolSessions: () => []
    })

    expect(warning).toBe('message.storage.service.iscsi.sessions.incomplete:2')
    expect(warning).not.toContain('NVMe-oF')
  })

  it('shows a translated iSCSI warning for an unmapped iSCSI row', () => {
    const warning = SharedFSTab.computed.iscsiSessionRuntimeWarning.call({
      $t: translate,
      sessionsRuntime: { observedIscsiTcpCount: 1 },
      protocolSessions: () => [{ mappingStatus: 'UNMAPPED' }]
    })

    expect(warning).toBe('message.storage.service.iscsi.sessions.incomplete:1')
  })

  it('projects only volumes referenced by NFS exports', () => {
    const nfsVolume = { id: 'volume-nfs', uuid: 'uuid-nfs' }
    const duplicateNfsVolume = { id: 'volume-nfs', uuid: 'uuid-nfs-copy' }
    const smbVolume = { id: 'volume-smb', uuid: 'uuid-smb' }
    const iscsiVolume = { id: 'volume-iscsi', uuid: 'uuid-iscsi' }
    const volumes = SharedFSTab.computed.nfsBackingVolumes.call({
      storageService: {
        nfsExports: [{ volumeUuid: 'uuid-nfs' }]
      },
      currentBackingVolumes: [nfsVolume, duplicateNfsVolume, smbVolume, iscsiVolume]
    })

    expect(volumes).toEqual([nfsVolume])
  })

  it('uses authoritative NFS filesystem evidence and leaves unknown values blank', () => {
    const context = {
      nfsExportsForVolume: () => [{
        config: JSON.stringify({ lastInspection: { filesystem: 'EXT4' } }),
        filesystem: 'xfs'
      }],
      parseStorageConfig: value => JSON.parse(value)
    }
    const detected = SharedFSTab.methods.nfsBackingVolumeFilesystem.call(context, { filesystem: 'xfs' })
    const unknown = SharedFSTab.methods.nfsBackingVolumeFilesystem.call({
      nfsExportsForVolume: () => [],
      parseStorageConfig: () => ({})
    }, {})

    expect(detected).toBe('ext4')
    expect(unknown).toBe('-')
  })

  it('distinguishes unavailable runtime evidence from an unmapped volume', () => {
    const context = { $t: key => key }

    expect(SharedFSTab.methods.fileShareVolumeMappingStatusLabel.call(context, 'UNAVAILABLE'))
      .toBe('label.storage.service.volume.mapping.unavailable')
    expect(SharedFSTab.methods.fileShareVolumeMappingStatusLabel.call(context, 'UNMAPPED'))
      .toBe('label.storage.service.volume.mapping.unmapped')
  })
})
