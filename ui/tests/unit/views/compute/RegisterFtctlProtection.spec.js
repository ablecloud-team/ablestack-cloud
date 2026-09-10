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

import { flushPromises, shallowMount } from '@vue/test-utils'
import RegisterFtctlProtection from '@/views/compute/RegisterFtctlProtection'
import { getAPI, postAPI } from '@/api'

jest.mock('@/api', () => ({
  getAPI: jest.fn(),
  postAPI: jest.fn()
}))

const createWrapper = () => {
  return shallowMount(RegisterFtctlProtection, {
    props: {
      resource: {
        id: 'vm-1',
        name: 'vm-name',
        state: 'Running',
        zoneid: 'zone-1',
        clusterid: 'cluster-1',
        hostid: 'host-1'
      }
    },
    global: {
      mocks: {
        $t: (key) => key,
        $message: {
          success: jest.fn(),
          error: jest.fn()
        },
        $pollJob: jest.fn(),
        $notifyError: jest.fn()
      },
      directives: {
        focus: () => {},
        'ctrl-enter': () => {}
      },
      stubs: {
        'a-spin': { template: '<div><slot /></div>' },
        'a-form': { template: '<form><slot /></form>' },
        'a-form-item': { template: '<div><slot /></div>' },
        'a-select': { template: '<select><slot /></select>' },
        'a-select-option': { template: '<option><slot /></option>' },
        'a-input': { template: '<input />' },
        'a-alert': { template: '<div />' },
        'a-checkbox': { template: '<input type="checkbox" />' },
        'a-input-password': { template: '<input />' },
        'a-radio-button': { template: '<button><slot /></button>' },
        'a-radio-group': { template: '<div><slot /></div>' },
        'a-button': { template: '<button><slot /></button>' }
      }
    }
  })
}

const mockGetApi = ({ hosts = [], storagePools = [] } = {}) => {
  getAPI.mockImplementation((command) => {
    if (command === 'listHosts') {
      return Promise.resolve({ listhostsresponse: { host: hosts } })
    }
    if (command === 'listStoragePools') {
      return Promise.resolve({ liststoragepoolsresponse: { storagepool: storagePools } })
    }
    return Promise.resolve({})
  })
}

describe('Views > compute > RegisterFtctlProtection.vue', () => {
  beforeEach(() => {
    jest.clearAllMocks()
    jest.spyOn(console, 'warn').mockImplementation(() => {})
  })

  it('fetches KVM hosts and auto-selects single peer host', async () => {
    mockGetApi({
      hosts: [
        { id: 'host-1', hypervisor: 'KVM', name: 'local-host' },
        { id: 'host-2', hypervisor: 'KVM', name: 'peer-host', ipaddress: '10.0.0.12' },
        { id: 'host-3', hypervisor: 'XenServer', name: 'ignored-host' }
      ]
    })

    const wrapper = createWrapper()
    await flushPromises()

    expect(getAPI).toHaveBeenCalledWith('listHosts', {
      zoneid: 'zone-1',
      type: 'Routing',
      state: 'Up',
      listall: true,
      details: 'all',
      clusterid: 'cluster-1'
    })
    expect(getAPI).toHaveBeenCalledWith('listStoragePools', {
      zoneid: 'zone-1',
      listall: true,
      page: 1,
      pagesize: 500,
      clusterid: 'cluster-1'
    })
    expect(getAPI).toHaveBeenCalledWith('listStoragePools', {
      zoneid: 'zone-1',
      listall: true,
      page: 1,
      pagesize: 500,
      hostid: 'host-2',
      clusterid: 'cluster-1'
    })
    expect(wrapper.vm.hosts).toHaveLength(1)
    expect(wrapper.vm.hosts[0].id).toBe('host-2')
    expect(wrapper.vm.form.peerhostid).toBe('host-2')
  })

  it('updates storage pools while keeping FT endpoints automatically allocated', async () => {
    mockGetApi({
      hosts: [
        {
          id: 'host-2',
          hypervisor: 'KVM',
          name: 'peer-host',
          ipaddress: '10.0.0.12',
          migrationip: '10.0.1.12',
          clusterid: 'cluster-1'
        }
      ],
      storagePools: [{ id: 'pool-1', name: 'pool-1', scope: 'HOST', state: 'Up', path: '/var/lib/libvirt/images' }]
    })

    const wrapper = createWrapper()
    await flushPromises()

    expect(wrapper.vm.form.targetstoragepoolid).toBe('pool-1')
    expect(wrapper.vm.form.targetstoragescope).toBe('secondary-local')
    expect(wrapper.vm.form.backendmode).toBe('remote-nbd')
    expect(wrapper.vm.form.secondarytargetdir).toBe('/var/lib/libvirt/images')
    expect(wrapper.vm.requiresRemoteNbdBackend).toBe(true)

    wrapper.vm.form.mode = 'ft'
    wrapper.vm.handleModeChange('ft')

    expect(wrapper.vm.form.targetstoragepoolid).toBe('pool-1')
    expect(wrapper.vm.form.targetstoragescope).toBe('secondary-local')
    expect(wrapper.vm.manualXcoloEndpoints).toBe(false)
    expect(wrapper.vm.form.xcoloproxyendpoint).toBeNull()
    expect(wrapper.vm.form.xcolonbdendpoint).toBeNull()
    expect(wrapper.vm.form.xcolomigrateuri).toBeNull()
    expect(wrapper.vm.showBackendFields).toBe(false)
    expect(wrapper.vm.showStorageFields).toBe(true)
  })

  it('updates conditional fields when mode and backend change', async () => {
    mockGetApi({
      storagePools: [{ id: 'pool-1', name: 'pool-1', scope: 'HOST', state: 'Up', path: '/var/lib/libvirt/images' }]
    })
    const wrapper = createWrapper()
    await flushPromises()

    wrapper.vm.form.mode = 'ft'
    wrapper.vm.handleModeChange('ft')
    expect(wrapper.vm.form.backendmode).toBe(null)
    expect(wrapper.vm.form.targetstoragescope).toBe('secondary-local')
    expect(wrapper.vm.showFtFields).toBe(true)

    wrapper.vm.form.mode = 'dr'
    wrapper.vm.handleModeChange('dr')
    wrapper.vm.form.backendmode = 'shared-blockcopy'
    wrapper.vm.handleBackendModeChange('shared-blockcopy')
    expect(wrapper.vm.form.targetstoragepoolid).toBe('pool-1')
    expect(wrapper.vm.form.targetstoragescope).toBe('secondary-local')
    expect(wrapper.vm.form.backendmode).toBe('remote-nbd')
    expect(wrapper.vm.showRemoteNbdFields).toBe(true)
  })

  it('submits registerFtctlProtection and emits local refresh events', async () => {
    mockGetApi({
      hosts: [{ id: 'host-2', hypervisor: 'KVM', name: 'peer-host', ipaddress: '10.0.0.12' }],
      storagePools: [{ id: 'pool-1', name: 'pool-1', scope: 'HOST', state: 'Up', path: '/var/lib/libvirt/images' }]
    })
    postAPI.mockResolvedValue({ registerftctlprotectionresponse: { success: true } })

    const wrapper = createWrapper()
    await flushPromises()

    wrapper.vm.form.mode = 'dr'
    wrapper.vm.form.targetstoragepoolid = 'pool-1'
    wrapper.vm.applySelectedStoragePool('pool-1')
    wrapper.vm.form.fencingpolicy = 'manual-block'
    wrapper.vm.form.peerhostid = 'host-2'
    wrapper.vm.form.secondaryvmname = 'vm-name-standby'
    wrapper.vm.form.networkids = ['network-1']
    wrapper.vm.form.secondarytargetdir = '/data/secondary'
    wrapper.vm.form.remotenbdexportaddr = '10.0.0.12:10809'
    wrapper.vm.formRef.value = {
      validate: jest.fn().mockResolvedValue(true),
      scrollToField: jest.fn()
    }

    await wrapper.vm.handleSubmit({ preventDefault: jest.fn() })
    await flushPromises()

    expect(postAPI).toHaveBeenCalledWith('registerFtctlProtection', {
      virtualmachineid: 'vm-1',
      mode: 'dr',
      drpeersitetype: 'local-mold',
      provisioningbackend: 'cloud-managed',
      fencingpolicy: 'manual-block',
      backendmode: 'remote-nbd',
      targetstoragescope: 'secondary-local',
      targetstoragepoolid: 'pool-1',
      peerhostid: 'host-2',
      secondaryvmname: 'vm-name-standby',
      networkids: 'network-1',
      secondarytargetdir: '/data/secondary',
      remotenbdexportaddr: '10.0.0.12:10809'
    })
    expect(wrapper.emitted('refresh-data')).toBeTruthy()
    expect(wrapper.emitted('close-action')).toBeTruthy()
  })

  it('starts async register job polling and closes modal after submission', async () => {
    mockGetApi({
      hosts: [{ id: 'host-2', hypervisor: 'KVM', name: 'peer-host', ipaddress: '10.0.0.12' }],
      storagePools: [{ id: 'pool-1', name: 'pool-1', scope: 'CLUSTER', state: 'Up' }]
    })
    postAPI.mockResolvedValue({ registerftctlprotectionresponse: { jobid: 'job-1' } })

    const wrapper = createWrapper()
    await flushPromises()

    wrapper.vm.form.mode = 'ha'
    wrapper.vm.form.targetstoragepoolid = 'pool-1'
    wrapper.vm.form.targetstoragescope = 'shared'
    wrapper.vm.form.peerhostid = 'host-2'
    wrapper.vm.form.secondaryvmname = 'vm-name-standby'
    wrapper.vm.formRef.value = {
      validate: jest.fn().mockResolvedValue(true),
      scrollToField: jest.fn()
    }

    await wrapper.vm.handleSubmit({ preventDefault: jest.fn() })
    await flushPromises()

    expect(wrapper.vm.$pollJob).toHaveBeenCalledWith(expect.objectContaining({
      jobId: 'job-1',
      title: 'label.ftctl.protection.configure',
      description: 'vm-name',
      resourceId: 'vm-1',
      successMessage: 'message.ftctl.protection.saved',
      errorMessage: 'label.error',
      catchMessage: 'label.error.caught'
    }))
    expect(wrapper.vm.$message.success).toHaveBeenCalledWith(expect.stringContaining('label.started'))
    expect(wrapper.emitted('refresh-data')).toBeTruthy()
    expect(wrapper.emitted('close-action')).toBeTruthy()
  })

  it('submits DR remote Mold registration without local peer host or storage IDs', async () => {
    mockGetApi()
    postAPI.mockResolvedValue({ registerftctlprotectionresponse: { success: true } })

    const wrapper = createWrapper()
    await flushPromises()

    wrapper.vm.form.mode = 'dr'
    wrapper.vm.form.drpeersitetype = 'remote-mold'
    wrapper.vm.handleDrPeerSiteTypeChange()
    wrapper.vm.form.remotemoldapiurl = 'https://remote.example/client/api'
    wrapper.vm.form.remotemoldapikey = 'api-key'
    wrapper.vm.form.remotemoldsecretkey = 'secret-key'
    wrapper.vm.form.remotepeerhostuuid = 'remote-host-1'
    wrapper.vm.form.remotepeerhostname = 'remote-host'
    wrapper.vm.form.remotepeerhostaddress = '10.20.0.12'
    wrapper.vm.form.remotepeerhostblockcopyaddress = '10.30.0.12'
    wrapper.vm.form.remotepeersshuser = 'root'
    wrapper.vm.form.remotepeersshport = '22'
    wrapper.vm.form.remotepeerlibvirturi = 'qemu+ssh://root@10.20.0.12:22/system'
    wrapper.vm.form.remotetargetstoragepooluuid = 'remote-pool-1'
    wrapper.vm.form.remotetargetstoragepoolname = 'remote-pool'
    wrapper.vm.form.remotetargetstoragepoolpath = '/remote/ftctl'
    wrapper.vm.form.remotetargetstoragepooltype = 'Filesystem'
    wrapper.vm.form.secondaryvmname = 'vm-name-standby'
    wrapper.vm.form.networkids = ['network-remote-1']
    wrapper.vm.form.secondarytargetdir = '/remote/ftctl'
    wrapper.vm.form.remotenbdexportaddr = '10.30.0.12:10809'
    wrapper.vm.formRef.value = {
      validate: jest.fn().mockResolvedValue(true),
      scrollToField: jest.fn()
    }

    await wrapper.vm.handleSubmit({ preventDefault: jest.fn() })
    await flushPromises()

    expect(postAPI).toHaveBeenCalledWith('registerFtctlProtection', expect.objectContaining({
      virtualmachineid: 'vm-1',
      mode: 'dr',
      drpeersitetype: 'remote-mold',
      provisioningbackend: 'cloud-managed',
      backendmode: 'remote-nbd',
      targetstoragescope: 'secondary-local',
      remotemoldapiurl: 'https://remote.example/client/api',
      remotemoldapikey: 'api-key',
      remotemoldsecretkey: 'secret-key',
      remotepeerhostuuid: 'remote-host-1',
      remotepeerhostaddress: '10.20.0.12',
      remotepeerhostblockcopyaddress: '10.30.0.12',
      remotepeersshuser: 'root',
      remotepeersshport: '22',
      remotepeersshoverride: false,
      remotepeersshautosetup: false,
      remotepeerlibvirturi: 'qemu+ssh://root@10.20.0.12:22/system',
      remotetargetstoragepooluuid: 'remote-pool-1',
      remotetargetstoragepoolpath: '/remote/ftctl',
      secondarytargetdir: '/remote/ftctl',
      remotenbdexportaddr: '10.30.0.12:10809'
    }))
    expect(postAPI.mock.calls[0][1]).not.toHaveProperty('peerhostid')
    expect(postAPI.mock.calls[0][1]).not.toHaveProperty('targetstoragepoolid')
  })

  it('prepares DR remote Mold SSH access before registration when auto setup is enabled', async () => {
    mockGetApi()
    postAPI.mockResolvedValue({ registerftctlprotectionresponse: { success: true } })

    const wrapper = createWrapper()
    await flushPromises()

    wrapper.vm.form.mode = 'dr'
    wrapper.vm.form.drpeersitetype = 'remote-mold'
    wrapper.vm.handleDrPeerSiteTypeChange()
    wrapper.vm.remotePeerSshAutoSetup = true
    wrapper.vm.form.remotemoldapiurl = 'https://remote.example/client/api'
    wrapper.vm.form.remotemoldapikey = 'api-key'
    wrapper.vm.form.remotemoldsecretkey = 'secret-key'
    wrapper.vm.form.remotepeerhostuuid = 'remote-host-1'
    wrapper.vm.form.remotepeerhostaddress = '10.20.0.12'
    wrapper.vm.form.remotepeerhostblockcopyaddress = '10.30.0.12'
    wrapper.vm.form.remotepeersshuser = 'root'
    wrapper.vm.form.remotepeersshport = '22'
    wrapper.vm.form.remotepeerlibvirturi = 'qemu+ssh://root@10.20.0.12:22/system'
    wrapper.vm.form.remotetargetstoragepooluuid = 'remote-pool-1'
    wrapper.vm.form.remotetargetstoragepoolpath = '/remote/ftctl'
    wrapper.vm.form.networkids = ['network-remote-1']
    wrapper.vm.form.secondarytargetdir = '/remote/ftctl'
    wrapper.vm.form.remotenbdexportaddr = '10.30.0.12:10809'
    wrapper.vm.formRef.value = {
      validate: jest.fn().mockResolvedValue(true),
      scrollToField: jest.fn()
    }

    await wrapper.vm.handleSubmit({ preventDefault: jest.fn() })
    await flushPromises()

    expect(getAPI).toHaveBeenCalledWith('prepareFtctlDrRemoteSshAccess', expect.objectContaining({
      virtualmachineid: 'vm-1',
      remotemoldapiurl: 'https://remote.example/client/api',
      remotemoldapikey: 'api-key',
      remotemoldsecretkey: 'secret-key',
      remotepeerhostuuid: 'remote-host-1',
      remotepeerhostaddress: '10.20.0.12',
      remotepeerlibvirturi: 'qemu+ssh://root@10.20.0.12:22/system',
      secondarytargetdir: '/remote/ftctl',
      remotenbdexportaddr: '10.30.0.12:10809'
    }))
    expect(postAPI).toHaveBeenCalledWith('registerFtctlProtection', expect.objectContaining({
      remotepeersshautosetup: true,
      remotemoldapikey: 'api-key',
      remotemoldsecretkey: 'secret-key'
    }))
  })

  it('auto-generates remote Mold SSH execution values and allows manual override', async () => {
    mockGetApi()
    const wrapper = createWrapper()
    await flushPromises()

    wrapper.vm.remoteMoldHosts = [{
      id: 'remote-host-1',
      name: 'remote-host',
      ipaddress: '10.20.0.12',
      migrationip: '10.30.0.12'
    }]

    wrapper.vm.handleRemotePeerHostChange('remote-host-1')

    expect(wrapper.vm.form.remotepeersshuser).toBe('root')
    expect(wrapper.vm.form.remotepeersshport).toBe('22')
    expect(wrapper.vm.form.remotepeerlibvirturi).toBe('qemu+ssh://root@10.20.0.12:22/system')
    expect(wrapper.vm.form.remotenbdexportaddr).toBe('10.30.0.12:10809')
    expect(wrapper.vm.remoteNbdExportAddressReadOnly).toBe(false)

    wrapper.vm.form.mode = 'dr'
    wrapper.vm.form.drpeersitetype = 'remote-mold'
    wrapper.vm.remotePeerSshOverride = true
    wrapper.vm.form.remotepeersshuser = 'admin'
    wrapper.vm.form.remotepeersshport = '2222'
    wrapper.vm.rebuildRemotePeerLibvirtUri()

    expect(wrapper.vm.form.remotepeerlibvirturi).toBe('qemu+ssh://admin@10.20.0.12:2222/system')
    expect(wrapper.vm.remoteNbdExportAddressReadOnly).toBe(false)

    wrapper.vm.remotePeerSshOverride = false
    wrapper.vm.handleRemotePeerSshOverrideChange()

    expect(wrapper.vm.form.remotepeersshuser).toBe('root')
    expect(wrapper.vm.form.remotepeersshport).toBe('22')
    expect(wrapper.vm.form.remotepeerlibvirturi).toBe('qemu+ssh://root@10.20.0.12:22/system')
    expect(wrapper.vm.remoteNbdExportAddressReadOnly).toBe(true)
  })

  it('submits FT registration with automatic XCOLO port allocation', async () => {
    mockGetApi({
      hosts: [{ id: 'host-2', hypervisor: 'KVM', name: 'peer-host', ipaddress: '10.0.0.12' }],
      storagePools: [{ id: 'pool-1', name: 'pool-1', scope: 'HOST', state: 'Up' }]
    })
    postAPI.mockResolvedValue({ registerftctlprotectionresponse: { success: true } })

    const wrapper = createWrapper()
    await flushPromises()

    wrapper.vm.form.mode = 'ft'
    wrapper.vm.handleModeChange('ft')
    wrapper.vm.form.targetstoragepoolid = 'pool-1'
    wrapper.vm.form.targetstoragescope = 'secondary-local'
    wrapper.vm.form.peerhostid = 'host-2'
    wrapper.vm.form.secondaryvmname = 'vm-name-standby'
    wrapper.vm.form.xcoloproxyendpoint = 'tcp:10.0.0.12:9000'
    wrapper.vm.form.xcolonbdendpoint = 'tcp:10.0.0.12:10809'
    wrapper.vm.form.xcolomigrateuri = 'tcp:10.0.0.12:9998'
    wrapper.vm.formRef.value = {
      validate: jest.fn().mockResolvedValue(true),
      scrollToField: jest.fn()
    }

    await wrapper.vm.handleSubmit({ preventDefault: jest.fn() })
    await flushPromises()

    expect(postAPI).toHaveBeenCalledWith('registerFtctlProtection', {
      virtualmachineid: 'vm-1',
      mode: 'ft',
      provisioningbackend: 'cloud-managed',
      fencingpolicy: 'manual-block',
      targetstoragescope: 'secondary-local',
      targetstoragepoolid: 'pool-1',
      peerhostid: 'host-2',
      secondaryvmname: 'vm-name-standby',
      xcoloportallocationmode: 'auto'
    })
    expect(postAPI.mock.calls[0][1]).not.toHaveProperty('xcoloproxyendpoint')
    expect(postAPI.mock.calls[0][1]).not.toHaveProperty('xcolonbdendpoint')
    expect(postAPI.mock.calls[0][1]).not.toHaveProperty('xcolomigrateuri')
  })
})
