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

  it('verifies fresh NIC identity after repair without treating a stale protocol view as failure', async () => {
    const context = {
      resource: { id: 'shared' },
      identityRepair: { eligible: true, runtimePrimaryIp: '10.10.13.240', aliases: ['10.10.13.241'], loading: false, visible: true },
      storageIdentityDrift: true,
      callStorageIdentityRepair: jest.fn().mockResolvedValueOnce({}).mockResolvedValueOnce({ resultjson: { reason: 'ALREADY_CONSISTENT', persistedPrimaryIp: '10.10.13.240', runtimePrimaryIp: '10.10.13.240', aliases: ['10.10.13.241'] } }),
      parseIdentityRepairEvidence: SharedFSTab.methods.parseIdentityRepairEvidence,
      fetchStorageServiceData: jest.fn().mockResolvedValue(),
      $t: value => value,
      $message: { success: jest.fn(), error: jest.fn() }
    }
    await SharedFSTab.methods.applyStorageIdentityRepair.call(context)
    expect(context.callStorageIdentityRepair.mock.calls).toEqual([[false, '10.10.13.240'], [true]])
    expect(context.identityRepair.visible).toBe(false)
    expect(context.$message.success).toHaveBeenCalled()
    expect(context.$message.error).not.toHaveBeenCalled()
  })
  it('does not publish repaired NIC evidence after switching to another SharedFS', async () => {
    const context = {
      resource: { id: 'shared' },
      identityRepair: { eligible: true, runtimePrimaryIp: '10.10.13.240', aliases: [], loading: false, visible: true },
      callStorageIdentityRepair: jest.fn().mockImplementation(async () => { context.resource.id = 'other'; return {} }),
      fetchStorageServiceData: jest.fn(),
      $message: { success: jest.fn(), error: jest.fn() }
    }
    await SharedFSTab.methods.applyStorageIdentityRepair.call(context)
    expect(context.callStorageIdentityRepair).toHaveBeenCalledTimes(1)
    expect(context.fetchStorageServiceData).not.toHaveBeenCalled()
    expect(context.$message.success).not.toHaveBeenCalled()
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

describe('SharedFS service-wide NFS owner mapping', () => {
  it('reads desired policy from the root detail component and preserves the legacy default', () => {
    expect(SharedFSTab.computed.nfsDesiredIdMode.call({
      storageService: { protocols: [{ protocol: 'NFS', enabled: true, idmappingmode: 'NUMERIC' }] }
    })).toBe('NUMERIC')
    expect(SharedFSTab.computed.nfsDesiredIdMode.call({ storageService: { protocols: [] } })).toBe('NAME_DOMAIN')
  })
  it('does not assume a runtime mode without an observed endpoint', () => {
    expect(SharedFSTab.computed.nfsRuntimeIdMode.call({ parsedHealth: {} })).toBe('UNKNOWN')
    expect(SharedFSTab.computed.nfsIdModeDrift.call({ nfsRuntimeIdMode: 'UNKNOWN', nfsDesiredIdMode: 'NUMERIC' })).toBe('UNKNOWN')
  })
  it('reports a mode mismatch instead of treating desired policy as runtime evidence', () => {
    expect(SharedFSTab.computed.nfsIdModeDrift.call({ nfsRuntimeIdMode: 'NAME_DOMAIN', nfsDesiredIdMode: 'NUMERIC' })).toBe('DRIFT')
    expect(SharedFSTab.computed.nfsIdModeDrift.call({ nfsRuntimeIdMode: 'NUMERIC', nfsDesiredIdMode: 'NUMERIC' })).toBe('CONSISTENT')
  })
})

describe('SharedFS nested backing paths', () => {
  it('allows explicit nested paths while rejecting traversal and absolute input', () => {
    const context = { $message: { error: jest.fn() }, $t: key => key }
    expect(SharedFSTab.methods.validateNestedSharePath.call(context, 'share/project-a')).toBe(true)
    for (const path of ['/share', '../share', 'share/../other', 'share/./other', 'share//other', '']) {
      expect(SharedFSTab.methods.validateNestedSharePath.call(context, path)).toBe(false)
    }
    expect(context.$message.error).toHaveBeenCalledTimes(6)
  })

  it('preserves the legacy name rule and permits independent names with explicit backing paths', () => {
    const context = {
      forms: { nfsExport: { name: 'project-a', path: '/export/share/project-a', relativepath: 'share/project-a' } },
      isValidNfsExportName: SharedFSTab.methods.isValidNfsExportName,
      validateNestedSharePath: SharedFSTab.methods.validateNestedSharePath,
      $message: { error: jest.fn() },
      $t: key => key
    }
    expect(SharedFSTab.methods.validateNfsExportNameAndPath.call(context)).toBe(true)
    context.forms.nfsExport.relativepath = ''
    expect(SharedFSTab.methods.validateNfsExportNameAndPath.call(context)).toBe(false)
    context.forms.nfsExport.path = '/export/project-a'
    expect(SharedFSTab.methods.validateNfsExportNameAndPath.call(context)).toBe(true)
  })
})

describe('SharedFS nested path choices', () => {
  it('limits suggestions to the selected volume and retains physical legacy paths', () => {
    const context = {
      storageService: {
        nfsExports: [{ volumeid: 'v1', name: 'parent', config: JSON.stringify({ volumeMountPath: '/srv/v1', backingPath: '/srv/v1/export/parent' }) }],
        smbShares: [{ volumeid: 'v1', name: 'child', volumerelativepath: 'export/parent/child' }, { volumeid: 'v2', name: 'unrelated', volumerelativepath: 'private' }]
      },
      parseStorageConfig: value => value ? JSON.parse(value) : {},
      defaultCurrentBackingVolumeId: () => 'v1'
    }
    const options = SharedFSTab.methods.nestedBackingPathOptions.call(context, { volumemode: 'CURRENT' })
    expect(options.map(option => option.value)).toEqual(['export/parent', 'export/parent/child'])
    expect(SharedFSTab.methods.nestedBackingPathOptions.call(context, { volumemode: 'EXISTING', volumeid: '' })).toEqual([])
  })
})

describe('SharedFS hidden backing volume scope', () => {
  it('loads a hidden UUID only after the visible UUID lookup is empty', async () => {
    const vm = { $store: { getters: { userInfo: { roletype: 'Admin' } } }, listApi: jest.fn().mockResolvedValueOnce([]).mockResolvedValueOnce([{ id: 'hidden', size: 10995116277760 }]) }
    const rows = await SharedFSTab.methods.listScopedBackingVolumes.call(vm, { id: 'hidden', listall: true, listsystemvms: true })
    expect(rows[0].size).toBe(10995116277760)
    expect(vm.listApi.mock.calls[1]).toEqual(['listVolumes', { id: 'hidden', listall: true, listsystemvms: true, displayvolume: false }, 'volume'])
  })
  it('includes hidden volumes beside visible volumes within the same service VM', async () => {
    const vm = { $store: { getters: { userInfo: { roletype: 'Admin' } } }, listApi: jest.fn().mockResolvedValueOnce([{ id: 'visible' }]).mockResolvedValueOnce([{ id: 'hidden' }]) }
    expect(await SharedFSTab.methods.listScopedBackingVolumes.call(vm, { virtualmachineid: 'service-vm' })).toEqual([{ id: 'visible' }, { id: 'hidden' }])
  })
  it('does not request root-only hidden data for a regular user or an unscoped list', async () => {
    const vm = { $store: { getters: { userInfo: { roletype: 'User' } } }, listApi: jest.fn().mockResolvedValue([]) }
    await SharedFSTab.methods.listScopedBackingVolumes.call(vm, { id: 'known' })
    expect(vm.listApi).toHaveBeenCalledTimes(1)
    await expect(SharedFSTab.methods.listScopedBackingVolumes.call(vm, {})).rejects.toThrow('scope')
    expect(vm.listApi).toHaveBeenCalledTimes(1)
  })
})

describe('SharedFS workflow tabs', () => {
  it('loads current service data for work tabs and limits wide view to protocols', () => {
    const vm = { protocolWideLayout: true, runtimeUpgradeVisible: true, fetchStorageServiceData: jest.fn(), isStorageProtocolTab: () => false, updateRouteQuery: jest.fn(), emitWideLayout: jest.fn() }
    SharedFSTab.methods.handleChangeTab.call(vm, 'operations')
    expect(vm.currentTab).toBe('operations')
    expect(vm.fetchStorageServiceData).toHaveBeenCalledTimes(1)
    expect(vm.protocolWideLayout).toBe(false)
    expect(vm.runtimeUpgradeVisible).toBe(false)
    expect(vm.updateRouteQuery).toHaveBeenCalledWith('operations')
  })
})

describe('SharedFS SMB backing volume references', () => {
  function rows (shares) {
    const volumes = {
      'volume-a': { id: 'volume-a', name: 'Sparse A', size: 20, provisioningtype: 'SPARSE' },
      'volume-b': { id: 'volume-b', name: 'Sparse B', size: 30, provisioningtype: 'SPARSE' }
    }
    const context = {
      storageService: { smbShares: shares },
      volumeForShare: share => volumes[share.volumeid || share.volumeId] || {},
      clientVisibleName: name => name,
      backingVolumeActionFields: () => ({}),
      formatCapacityValue: value => value,
      displayBackingVolumeFilesystem: () => 'xfs',
      fileShareVolumeMappingStatusLabel: value => value
    }
    return SharedFSTab.computed.smbVolumeRows.call(context)
  }

  it('shows all parent and child references once while keeping one capacity row per volume', () => {
    const result = rows([
      { id: 'parent', name: 'parent', volumeid: 'volume-a' },
      { id: 'child', name: 'child', volumeId: 'volume-a' },
      { id: 'child', name: 'child', volumeid: 'volume-a' }
    ])
    expect(result).toHaveLength(1)
    expect(result[0]).toMatchObject({ id: 'volume-a', size: 20, shareName: 'parent, child', shareId: 'parent' })
  })

  it('does not merge the same share name on different backing volume identities', () => {
    const result = rows([
      { id: 'a', name: 'same-relative', volumeid: 'volume-a' },
      { id: 'b', name: 'same-relative', volumeid: 'volume-b' },
      { id: 'b-child', name: 'child', volumeid: 'volume-b' }
    ])
    expect(result).toHaveLength(2)
    expect(result[0]).toMatchObject({ id: 'volume-a', shareName: 'same-relative', size: 20 })
    expect(result[1]).toMatchObject({ id: 'volume-b', shareName: 'same-relative, child', size: 30 })
  })
})

describe('SharedFS NVMe ACL authentication request preservation', () => {
  const supported = { kernelTargetSupported: true, configfsHostSupported: true, dhChapSupported: true, dhChapCtrlSupported: true }
  const actions = [
    ['createNvmeHostAcl', 'nvmeHostAcl', 'createStorageNvmeOfHostAcl', { subsystemid: 'subsystem-a' }],
    ['updateNvmeHostAcl', 'editNvmeHostAcl', 'updateStorageNvmeOfHostAcl', { id: 'acl-a' }]
  ]
  function context (capability = supported, fields = {}) {
    const vm = {
      forms: { nvmeHostAcl: { id: 'acl-a', subsystemid: 'subsystem-a', hostnqn: 'nqn.2014-08.org.nvmexpress:host-a', dhchapenabled: true, dhchapctrlenabled: true, dhchapkey: 'synthetic-host-input', dhchapctrlkey: 'synthetic-controller-input', ...fields } },
      nvmeCapability: capability,
      selectedNvmeHostAclAllowsAnyHost: false,
      $t: key => key,
      $notification: { error: jest.fn(), warning: jest.fn() },
      runStorageAction: jest.fn().mockResolvedValue({})
    }
    vm.validateNvmeHostAclForm = SharedFSTab.methods.validateNvmeHostAclForm
    vm.validateNvmeHostAclAuthentication = SharedFSTab.methods.validateNvmeHostAclAuthentication
    return vm
  }

  describe.each(actions)('%s boundary', (method, action, api, scope) => {
    it.each([
      ['unsupported host', { ...supported, dhChapSupported: false }],
      ['unknown capability', {}],
      ['string truthy capability', { ...supported, dhChapSupported: 'true' }],
      ['unprepared configfs', { ...supported, configfsHostSupported: false }],
      ['unsupported mutual authentication', { ...supported, dhChapCtrlSupported: false }]
    ])('preserves the requested authentication and sends no API for %s', async (_, capability) => {
      const vm = context(capability)
      const request = { ...vm.forms.nvmeHostAcl }
      await SharedFSTab.methods[method].call(vm)
      expect(vm.runStorageAction).not.toHaveBeenCalled()
      expect(vm.forms.nvmeHostAcl).toEqual(request)
      expect(vm.$notification.error).toHaveBeenCalledWith({ message: 'message.storage.service.nvme.auth.request.preserved.help' })
    })

    it.each([
      ['controller without host', { dhchapenabled: false }],
      ['string host flag', { dhchapenabled: 'true' }],
      ['numeric controller flag', { dhchapctrlenabled: 1 }]
    ])('blocks %s without silently weakening the request', async (_, fields) => {
      const vm = context(supported, fields)
      const request = { ...vm.forms.nvmeHostAcl }
      await SharedFSTab.methods[method].call(vm)
      expect(vm.runStorageAction).not.toHaveBeenCalled()
      expect(vm.forms.nvmeHostAcl).toEqual(request)
    })

    it.each([false, true])('passes exact supported host/mutual=%s values to the existing API and clears submitted key references', async controller => {
      const vm = context({ ...supported, dhChapCtrlSupported: controller }, { dhchapctrlenabled: controller })
      await SharedFSTab.methods[method].call(vm)
      expect(vm.runStorageAction).toHaveBeenCalledTimes(1)
      expect(vm.runStorageAction).toHaveBeenCalledWith(action, api, {
        ...scope,
        hostnqn: 'nqn.2014-08.org.nvmexpress:host-a',
        dhchapenabled: true,
        dhchapkey: 'synthetic-host-input',
        dhchapctrlenabled: controller,
        dhchapctrlkey: controller ? 'synthetic-controller-input' : ''
      }, expect.any(String))
      expect(vm.forms.nvmeHostAcl.dhchapenabled).toBe(true)
      expect(vm.forms.nvmeHostAcl.dhchapctrlenabled).toBe(controller)
      expect(vm.forms.nvmeHostAcl.dhchapkey).toBe('')
      expect(vm.forms.nvmeHostAcl.dhchapctrlkey).toBe('')
    })

    it.each([{}, { ...supported, dhChapSupported: false }])('keeps an explicit unauthenticated request available without authentication capability', async capability => {
      const vm = context(capability, { dhchapenabled: false, dhchapctrlenabled: false, dhchapkey: '', dhchapctrlkey: '' })
      await SharedFSTab.methods[method].call(vm)
      expect(vm.runStorageAction).toHaveBeenCalledTimes(1)
      expect(vm.runStorageAction).toHaveBeenCalledWith(action, api, {
        ...scope, hostnqn: 'nqn.2014-08.org.nvmexpress:host-a', dhchapenabled: false, dhchapkey: '', dhchapctrlenabled: false, dhchapctrlkey: ''
      }, expect.any(String))
      expect(vm.$notification.error).not.toHaveBeenCalled()
    })

    it('retains the existing invalid-scope and allow-any-host rejection before transport', async () => {
      for (const fields of [{ subsystemid: '' }, { hostnqn: '' }]) {
        const vm = context(supported, fields)
        await SharedFSTab.methods[method].call(vm)
        expect(vm.runStorageAction).not.toHaveBeenCalled()
      }
      const vm = context()
      vm.selectedNvmeHostAclAllowsAnyHost = true
      await SharedFSTab.methods[method].call(vm)
      expect(vm.runStorageAction).not.toHaveBeenCalled()
      expect(vm.forms.nvmeHostAcl.dhchapenabled).toBe(true)
    })

    it('preserves an active request on capability loss and blocks its subsequent submission', async () => {
      const vm = context({})
      const request = { ...vm.forms.nvmeHostAcl }
      SharedFSTab.watch.nvmeDhChapSupported.call(vm, false)
      expect(vm.forms.nvmeHostAcl).toEqual(request)
      expect(vm.$notification.warning).toHaveBeenCalledWith({ message: 'message.storage.service.nvme.auth.request.preserved.help' })
      await SharedFSTab.methods[method].call(vm)
      expect(vm.runStorageAction).not.toHaveBeenCalled()
      expect(vm.forms.nvmeHostAcl).toEqual(request)
    })
  })

  describe.each(actions)('%s modal submission', (method, action) => {
    function modalContext (capability) {
      const vm = context(capability)
      vm.actionModal = { visible: true, type: action, loading: false, context: {} }
      vm[method] = SharedFSTab.methods[method]
      vm.closeActionModal = jest.fn(function () { SharedFSTab.methods.closeActionModal.call(this) })
      return vm
    }

    it('keeps the actual submission dialog and requested form available after a capability rejection', async () => {
      const vm = modalContext({})
      const request = { ...vm.forms.nvmeHostAcl }
      await SharedFSTab.methods.submitActionModal.call(vm)
      expect(vm.runStorageAction).not.toHaveBeenCalled()
      expect(vm.closeActionModal).not.toHaveBeenCalled()
      expect(vm.actionModal).toMatchObject({ visible: true, type: action, loading: false })
      expect(vm.forms.nvmeHostAcl).toEqual(request)
    })

    it('keeps the existing successful submission close behavior', async () => {
      const vm = modalContext(supported)
      await SharedFSTab.methods.submitActionModal.call(vm)
      expect(vm.runStorageAction).toHaveBeenCalledTimes(1)
      expect(vm.closeActionModal).toHaveBeenCalledTimes(1)
      expect(vm.actionModal.visible).toBe(false)
    })
  })

  it('does not change the existing close behavior of other action types', async () => {
    const vm = context()
    vm.actionModal = { visible: true, type: 'enableProtocol', loading: false, context: {} }
    vm.enableProtocol = jest.fn().mockResolvedValue(false)
    vm.closeActionModal = jest.fn(function () { SharedFSTab.methods.closeActionModal.call(this) })
    await SharedFSTab.methods.submitActionModal.call(vm)
    expect(vm.enableProtocol).toHaveBeenCalledTimes(1)
    expect(vm.closeActionModal).toHaveBeenCalledTimes(1)
  })

  it('does not warn or alter an explicit noauth request when capability changes', () => {
    const vm = context({}, { dhchapenabled: false, dhchapctrlenabled: false, dhchapkey: '', dhchapctrlkey: '' })
    const request = { ...vm.forms.nvmeHostAcl }
    SharedFSTab.watch.nvmeDhChapSupported.call(vm, false)
    expect(vm.forms.nvmeHostAcl).toEqual(request)
    expect(vm.$notification.warning).not.toHaveBeenCalled()
  })

  it('retains explicit user-off cleanup without using capability loss to trigger it', () => {
    const vm = context()
    vm.forms.nvmeHostAcl.dhchapenabled = false
    SharedFSTab.watch['forms.nvmeHostAcl.dhchapenabled'].call(vm, false)
    expect(vm.forms.nvmeHostAcl.dhchapenabled).toBe(false)
    expect(vm.forms.nvmeHostAcl.dhchapctrlenabled).toBe(false)
    expect(vm.forms.nvmeHostAcl.dhchapkey).toBe('')
    expect(vm.forms.nvmeHostAcl.dhchapctrlkey).toBe('')
    expect(vm.runStorageAction).not.toHaveBeenCalled()
  })
})
