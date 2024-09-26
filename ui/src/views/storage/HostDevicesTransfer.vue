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
  name: 'HostDevicesTransfer',
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
  methods: {
    fetchVMs () {
      this.loading = true
      const params = { hostid: this.resource.id, details: 'min' }
      const vmStates = ['Running']

      vmStates.forEach((state) => {
        params.state = state
        api('listVirtualMachines', params).then(response => {
          this.virtualmachines = this.virtualmachines.concat(response.listvirtualmachinesresponse.virtualmachine || [])
        }).catch(error => {
          this.$notifyError(error.message || 'Failed to fetch VMs')
        }).finally(() => {
          this.loading = false
        })
      })
    },
    handleSubmit () {
      if (!this.form.virtualmachineid) {
        this.$notifyError(this.$t('message.error.select.vm'))
        return
      }

      this.loading = true

      const vmId = this.form.virtualmachineid
      const hostDevicesName = this.resource.hostDevicesName

      const xmlConfig = this.generateXmlConfig(hostDevicesName)

      const apiName = 'updateVirtualMachine'

      if (!(apiName in this.$store.getters.apis)) {
        this.$message.error({
          message: this.$t('error.execute.api.failed') + ' ' + apiName,
          description: this.$t('message.user.not.permitted.api')
        })
        this.loading = false
        return
      }

      api(apiName, {
        id: vmId,
        'details[0].extraconfig-1': xmlConfig
      }).then(() => {
        this.$message.success(this.$t('message.success.update.vm'))
        this.closeAction()
      }).catch(error => {
        this.$notifyError(error.message || 'Failed to update VM')
        this.formRef.value.scrollToField('virtualmachineid')
      }).finally(() => {
        this.loading = false
      })
    },

    generateXmlConfig (hostDevicesName) {
      const [pciAddress] = hostDevicesName.split(' ')
      const [bus, slotFunction] = pciAddress.split(':')
      const [slot, func] = slotFunction.split('.')

      return `
      <devices>
        <hostdev mode='subsystem' type='pci' managed='yes'>
        <source>
          <address domain='0x0000' bus='${parseInt(bus, 16)}' slot='0x${parseInt(slot, 16)}' function='0x${parseInt(func, 16)}'/>
        </source>
      </hostdev>
      </devices>
      `
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
