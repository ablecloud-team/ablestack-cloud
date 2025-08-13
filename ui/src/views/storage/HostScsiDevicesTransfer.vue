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

<template>
  <a-spin :spinning="loading">
    <a-form
      class="form"
      layout="vertical"
      :ref="formRef"
      :model="form"
    >
      <a-alert type="warning">
        <template #message>
          <span v-html="$t('message.warning.host.devices')" />
        </template>
      </a-alert>
      <br>
      <a-form-item :label="$t('label.virtualmachine')" name="virtualmachineid" ref="virtualmachineid">
        <a-select
          v-focus="true"
          v-model:value="form.virtualmachineid"
          :placeholder="$t('label.select.vm')"
          showSearch
          optionFilterProp="label"
          :filterOption="filterOption"
        >
          <a-select-option v-for="vm in virtualmachines" :key="vm.id" :label="vm.name || vm.displayname">
            {{ vm.name || vm.displayname }}
          </a-select-option>
        </a-select>
        <div class="actions">
          <a-button @click="closeAction">{{ $t('label.cancel') }}</a-button>
          <a-button type="primary" ref="submit" @click="handleSubmit">{{ $t('label.ok') }}</a-button>
        </div>
      </a-form-item>
    </a-form>
  </a-spin>
</template>

<script>
import { reactive } from 'vue'
import { api } from '@/api'

export default {
  name: 'HostScsiDevicesTransfer',
  props: {
    resource: {
      type: Object,
      required: true
    }
  },
  data () {
    return {
      virtualmachines: [],
      loading: true,
      form: reactive({ virtualmachineid: null }),
      resourceType: 'UserVm'
    }
  },
  created () {
    this.fetchVMs()
  },
  watch: {
    showAddModal: {
      immediate: true,
      handler (newVal) {
        if (newVal) {
          this.fetchVMs()
        }
      }
    }
  },
  methods: {
    refreshVMList () {
      if (!this.resource || !this.resource.id) {
        this.loading = false
        return Promise.reject(new Error('Invalid resource'))
      }

      this.loading = true
      const params = { hostid: this.resource.id, details: 'all', listall: true }
      const vmStates = ['Running']

      return Promise.all([
        // 실행 중인 VM 목록 가져오기
        Promise.all(vmStates.map(state => {
          return api('listVirtualMachines', { ...params, state })
            .then(vmResponse => {
              const vms = vmResponse.listvirtualmachinesresponse?.virtualmachine || []
              return vms.map(vm => ({
                ...vm,
                instanceId: vm.instancename ? vm.instancename.split('-')[2] : null
              }))
            })
        })),
        // 현재 SCSI 디바이스 할당 상태 가져오기
        api('listHostScsiDevices', { id: this.resource.id })
      ]).then(([vmArrays, scsiResponse]) => {
        const vms = vmArrays.flat()
        const scsiDevices = scsiResponse.listhostscsidevicesresponse?.listhostscsidevices?.[0]
        const allocatedVmIds = new Set()

        // 모든 SCSI 디바이스에 할당된 VM ID 수집
        if (scsiDevices?.vmallocations) {
          Object.values(scsiDevices.vmallocations).forEach(vmId => {
            if (vmId) {
              allocatedVmIds.add(vmId.toString())
            }
          })
        }

        // 현재 디바이스에 할당된 VM ID가 있다면 제외
        if (this.resource.hostDevicesName && scsiDevices?.vmallocations) {
          const currentVmId = scsiDevices.vmallocations[this.resource.hostDevicesName]
          if (currentVmId) {
            allocatedVmIds.delete(currentVmId.toString())
          }
        }

        // 할당되지 않은 VM만 필터링
        this.virtualmachines = vms.filter(vm => !allocatedVmIds.has(vm.instanceId?.toString()))
      }).catch(error => {
        this.$notifyError(error.message || 'Failed to fetch VMs')
      }).finally(() => {
        this.loading = false
      })
    },

    fetchVMs () {
      this.form.virtualmachineid = undefined
      return this.refreshVMList()
    },

    async handleSubmit () {
      if (!this.form.virtualmachineid) {
        this.$notification.error({
          message: this.$t('message.error'),
          description: this.$t('message.please.select.vm')
        })
        return
      }

      this.loading = true
      try {
        // SCSI 디바이스의 상세 정보를 다시 조회
        let hostDevicesText = this.resource.hostDevicesText

        if (!hostDevicesText) {
          // hostDevicesText가 없으면 API를 통해 다시 조회
          const scsiResponse = await api('listHostScsiDevices', { id: this.resource.id })
          const scsiData = scsiResponse.listhostscsidevicesresponse?.listhostscsidevices?.[0]

          if (scsiData) {
            const deviceIndex = scsiData.hostdevicesname.indexOf(this.resource.hostDevicesName)
            if (deviceIndex !== -1) {
              hostDevicesText = scsiData.hostdevicestext[deviceIndex]
            }
          }
        }

        const xmlConfig = this.generateXmlConfig(this.resource.hostDevicesName, hostDevicesText)

        await api('updateHostScsiDevices', {
          hostid: this.resource.id,
          hostdevicesname: this.resource.hostDevicesName,
          virtualmachineid: this.form.virtualmachineid,
          xmlconfig: xmlConfig
        })

        this.$message.success(this.$t('message.success.allocate.device'))

        // 할당 완료 후 이벤트 발생 순서 조정
        this.$emit('device-allocated')
        this.$emit('allocation-completed')

        // 모달 닫기 전에 잠시 대기하여 데이터 로드 완료 보장
        setTimeout(() => {
          this.$emit('close-action')
        }, 500)
      } catch (error) {
        this.$notifyError(error)
      } finally {
        this.loading = false
      }
    },

    generateXmlConfig (hostDeviceName, hostDevicesText) {
      // hostDevicesText가 undefined인 경우 처리
      if (!hostDevicesText) {
        console.warn('hostDevicesText is undefined, using fallback values')
        return `
          <hostdev mode='subsystem' type='scsi' rawio='yes'>
            <source>
              <adapter name='scsi_host0'/>
              <address bus='0' target='0' unit='0'/>
            </source>
          </hostdev>
        `.trim()
      }

      // hostDevicesText에서 [host:bus:target:unit] 추출
      const match = hostDevicesText.match(/\[(\d+):(\d+):(\d+):(\d+)\]/)
      if (!match) {
        console.warn('SCSI 주소 패턴을 찾을 수 없습니다:', hostDevicesText)
        // 기본값 사용
        return `
          <hostdev mode='subsystem' type='scsi' rawio='yes'>
            <source>
              <adapter name='scsi_host0'/>
              <address bus='0' target='0' unit='0'/>
            </source>
          </hostdev>
        `.trim()
      }

      const host = match[1]
      const bus = match[2]
      const target = match[3]
      const unit = match[4]

      const adapterName = `scsi_host${host}`

      return `
        <hostdev mode='subsystem' type='scsi' rawio='yes'>
          <source>
            <adapter name='${adapterName}'/>
            <address bus='${bus}' target='${target}' unit='${unit}'/>
          </source>
        </hostdev>
      `.trim()
    },

    closeAction () {
      this.$emit('close-action')
    },

    filterOption (input, option) {
      return option.label.toLowerCase().indexOf(input.toLowerCase()) >= 0
    }
  }
}
</script>

<style lang="scss" scoped>
.form {
  width: 80vw;

  @media (min-width: 500px) {
    width: 475px;
  }
}
.actions {
  display: flex;
  justify-content: flex-end;
  margin-top: 20px;
  button {
    &:not(:last-child) {
      margin-right: 10px;
    }
  }
}
</style>
