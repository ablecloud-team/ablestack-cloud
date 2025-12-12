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
  <a-spin :spinning="loadingNic">
    <a-button
      type="primary"
      style="width: 100%; margin-bottom: 10px"
      @click="showAddNicModal"
      :loading="loadingNic"
      :disabled="!('addNicToVirtualMachine' in $store.getters.apis) || resource.hypervisor === 'External'">
      <template #icon><plus-outlined /></template> {{ $t('label.network.addvm') }}
    </a-button>
    <NicsTable :resource="resource" :loading="loading">
      <template #actions="record">
        <a-popconfirm
          v-if="!record.nic.isdefault && resource.hypervisor !== 'External'"
          :title="$t('message.set.default.nic')"
          @confirm="setAsDefault(record.nic)"
          :okText="$t('label.yes')"
          :cancelText="$t('label.no')"
          >
          <tooltip-button
            class="action-button"
            :shape="'round'"
            tooltipPlacement="bottom"
            :tooltip="$t('label.set.default.nic')"
            :disabled="!('updateDefaultNicForVirtualMachine' in $store.getters.apis)"
            icon="check-square-outlined" />
        </a-popconfirm>
        <tooltip-button
          class="action-button"
          :shape="'round'"
          tooltipPlacement="bottom"
          :tooltip="$t('label.change.ipaddress.or.macaddress')"
          icon="swap-outlined"
          :disabled="!('updateVmNicIp' in $store.getters.apis)"
          @onClick="onChangeIPAddress(record)" />
        <tooltip-button
          v-if="record.nic.type !== 'L2' && resource.hypervisor !== 'External'"
          class="action-button"
          :shape="'round'"
          tooltipPlacement="bottom"
          :tooltip="$t('label.edit.secondary.ips')"
          icon="environment-outlined"
          :disabled="(!('addIpToNic' in $store.getters.apis) && !('addIpToNic' in $store.getters.apis))"
          @onClick="onAcquireSecondaryIPAddress(record)" />
        <a-popconfirm
          :title="`${record.nic.linkstate ? $t('label.action.nic.linkstate.down') : $t('label.action.nic.linkstate.up')}`"
          @confirm="onChangeNicLinkState(record)"
          :okText="$t('label.yes')"
          :cancelText="$t('label.no')">
          <tooltip-button
            class="action-button"
            :shape="'round'"
            tooltipPlacement="bottom"
            :tooltip="$t('label.action.nic.linkstate')"
            :type="record.nic.linkstate ? 'primary' : ''"
            icon="wifi-outlined" />
        </a-popconfirm>
        <a-popconfirm
          :title="$t('message.network.removenic')"
          @confirm="removeNIC(record.nic)"
          :okText="$t('label.yes')"
          :cancelText="$t('label.no')"
          v-if="!record.nic.isdefault && resource.hypervisor !== 'External'"
        >
          <tooltip-button
            class="action-button"
            :shape="'round'"
            tooltipPlacement="bottom"
            :tooltip="$t('label.action.remove.nic')"
            :disabled="!('removeNicFromVirtualMachine' in $store.getters.apis)"
            type="primary"
            :danger="true"
            icon="delete-outlined" />
        </a-popconfirm>
      </template>
    </NicsTable>

    <a-modal
      :visible="showAddNetworkModal"
      :title="$t('label.network.addvm')"
      :maskClosable="false"
      :closable="true"
      :footer="null"
      @cancel="closeModals">
      <a-form
        class="form-layout"
        layout="vertical"
        @finish="submitAddNetwork"
        v-ctrl-enter="submitAddNetwork">
        <a-alert style="margin-bottom: 5px" type="info" show-icon>
          <template #message>
            <span v-html="$t('message.network.addvm.desc')" />
          </template>
        </a-alert><br>
        <a-form-item name="networkid">
          <template #label>
            <tooltip-label :title="$t('label.network')"/>
          </template>
          <a-select
            v-model:value="addNetworkData.network"
            v-focus="true"
            showSearch
            optionFilterProp="label"
            :filterOption="(input, option) => {
              return option.label.toLowerCase().indexOf(input.toLowerCase()) >= 0
            }" >
            <a-select-option
              v-for="network in addNetworkData.allNetworks"
              :key="network.id"
              :value="network.id"
              :label="network.name">
              <span>
                <resource-icon v-if="network.icon" :image="network.icon.base64image" size="1x" style="margin-right: 5px"/>
                <apartment-outlined v-else style="margin-right: 5px" />
                {{ network.name }}
              </span>
            </a-select-option>
          </a-select>
        </a-form-item>
        <a-form-item name="ipaddress">
          <template #label>
            <tooltip-label :title="$t('label.ipaddress')"/>
          </template>
          <a-input v-model:value="addNetworkData.ip"></a-input>
        </a-form-item>
        <a-form-item name="makedefault">
          <a-checkbox v-model:checked="addNetworkData.makedefault">
            {{ $t('label.make.default') }}
          </a-checkbox>
        </a-form-item>
        <div :span="24" class="action-button">
          <a-button @click="closeModals">{{ $t('label.cancel') }}</a-button>
          <a-button type="primary" ref="submit" @click="submitAddNetwork">{{ $t('label.ok') }}</a-button>
        </div>
      </a-form>
    </a-modal>

    <a-modal
      :visible="showUpdateIpMacModal"
      :title="$t('label.change.ipaddress.or.macaddress')"
      :maskClosable="false"
      :closable="true"
      :footer="null"
      @cancel="closeModals"
    >
      <a-form
        class="form-layout"
        layout="vertical"
        :ref="formRef"
        :model="form"
        :rules="rules"
        @finish="submitUpdateIPMac"
        v-ctrl-enter="submitUpdateIPMac">
        <a-alert style="margin-bottom: 5px" type="warning" show-icon>
          <template #message>
            <span v-html="$t('message.network.updateip.or.macaddress')" />
          </template>
        </a-alert><br>
        <a-form-item name="ipaddress" ref="ipaddress">
          <template #label>
            <tooltip-label :title="$t('label.ipaddress')"/>
          </template>
          <a-select
            v-if="editNicResource.type==='Shared'"
            v-model:value="form.ipaddress"
            :loading="listIps.loading"
            v-focus="editNicResource.type==='Shared'"
            showSearch
            optionFilterProp="value"
            :filterOption="(input, option) => {
              return option.value.toLowerCase().indexOf(input.toLowerCase()) >= 0
            }">
            <a-select-option v-for="ip in listIps.opts" :key="ip.ipaddress">
              {{ ip.ipaddress }}
            </a-select-option>
          </a-select>
          <a-tooltip :title="$t('message.ip.edit.l2.disabled')" v-else-if="editNicResource.type === 'L2'">
            <a-input
              v-model:value="form.ipaddress"
              :disabled="true"
              v-focus="editNicResource.type!=='Shared'"/>
          </a-tooltip>
          <a-input
            v-else
            v-model:value="form.ipaddress"
            v-focus="editNicResource.type!=='Shared'"/>
        </a-form-item>
        <a-form-item name="macaddress" ref="macaddress">
          <template #label>
            <tooltip-label :title="$t('label.macaddress')"/>
          </template>
          <a-input
            v-model:value="form.macaddress"
            :placeholder="$t('label.macaddress')"></a-input>
        </a-form-item>

        <div :span="24" class="action-button">
          <a-button @click="closeModals">{{ $t('label.cancel') }}</a-button>
          <a-button type="primary" ref="submit" @click="submitUpdateIPMac">{{ $t('label.ok') }}</a-button>
        </div>
      </a-form>
    </a-modal>

    <a-modal
      :visible="showSecondaryIpModal"
      :title="$t('label.acquire.new.secondary.ip')"
      :maskClosable="false"
      :footer="null"
      :closable="false"
      class="wide-modal"
    >
      <a-form
        class="form-layout"
        layout="vertical"
        @finish="submitSecondaryIP"
        v-ctrl-enter="submitSecondaryIP">
        <a-alert style="margin-bottom: 5px" type="info" show-icon>
          <template #message>
            <span v-html="$t('message.network.secondaryip')" />
          </template>
        </a-alert><br>
        <a-form-item name="secondaryip">
          <template #label>
            <tooltip-label :title="$t('label.ipaddress')"/>
          </template>
          <a-select
            v-if="editNicResource.type==='Shared'"
            v-model:value="newSecondaryIp"
            :loading="listIps.loading"
            v-focus="editNicResource.type==='Shared'"
            showSearch
            optionFilterProp="value"
            :filterOption="(input, option) => {
              return option.value.toLowerCase().indexOf(input.toLowerCase()) >= 0
            }">
            <a-select-option v-for="ip in listIps.opts" :key="ip.ipaddress">
              {{ ip.ipaddress }}
            </a-select-option>
          </a-select>
          <a-input
            v-else
            :placeholder="$t('label.new.secondaryip.description')"
            v-model:value="newSecondaryIp"
            v-focus="editNicResource.type!=='Shared'"></a-input>
        </a-form-item>
        <div :span="24" class="action-button">
          <a-button @click="closeModals">{{ $t('label.cancel') }}</a-button>
          <a-button type="primary" ref="submit" @click="submitSecondaryIP">{{ $t('label.add.secondary.ip') }}</a-button>
        </div>
      </a-form>

      <a-divider />
      <a-list itemLayout="vertical">
        <a-list-item v-for="(ip, index) in secondaryIPs" :key="index">
          <a-popconfirm
            :title="$t('message.action.release.ip')"
            @confirm="removeSecondaryIP(ip.id)"
            :okText="$t('label.yes')"
            :cancelText="$t('label.no')"
          >
            <tooltip-button
              tooltipPlacement="bottom"
              :tooltip="$t('label.action.release.ip')"
              type="primary"
              :danger="true"
              icon="delete-outlined" />
            {{ ip.ipaddress }}
          </a-popconfirm>
        </a-list-item>
      </a-list>
    </a-modal>
  </a-spin>
</template>

<script>
import { getAPI, postAPI } from '@/api'
import { ref, reactive } from 'vue'
import NicsTable from '@/views/network/NicsTable'
import TooltipButton from '@/components/widgets/TooltipButton'
import ResourceIcon from '@/components/view/ResourceIcon'
import TooltipLabel from '@/components/widgets/TooltipLabel'

export default {
  name: 'NicsTab',
  components: {
    NicsTable,
    TooltipButton,
    ResourceIcon,
    TooltipLabel
  },
  props: {
    resource: {
      type: Object,
      required: true
    },
    loading: {
      type: Boolean,
      default: false
    }
  },
  inject: ['parentFetchData'],
  data () {
    return {
      vm: {},
      nic: {},
      showAddNetworkModal: false,
      showUpdateIpMacModal: false,
      showSecondaryIpModal: false,
      addNetworkData: {
        allNetworks: [],
        network: '',
        ip: '',
        makedefault: false
      },
      loadingNic: false,
      editIpAddressNic: '',
      formRef: 'updateNicForm',
      form: {
        ipaddress: '',
        macaddress: ''
      },
      rules: {},
      editNetworkId: '',
      secondaryIPs: [],
      selectedNicId: '',
      newSecondaryIp: '',
      editNicResource: {},
      listIps: {
        loading: false,
        opts: []
      }
    }
  },
  beforeCreate () {
    this.apiParams = this.$getApiParams('updateVmNicIp')
  },
  created () {
    this.vm = this.resource
  },
  methods: {
    listNetworks () {
      getAPI('listNetworks', {
        listAll: 'true',
        showicon: true,
        zoneid: this.vm.zoneid
      }).then(response => {
        this.addNetworkData.allNetworks = response.listnetworksresponse.network.filter(network => !this.vm.nic.map(nic => nic.networkid).includes(network.id))
        this.addNetworkData.network = this.addNetworkData.allNetworks[0].id
      })
    },
    fetchSecondaryIPs (nicId) {
      this.showSecondaryIpModal = true
      this.selectedNicId = nicId
      getAPI('listNics', {
        nicId: nicId,
        keyword: '',
        virtualmachineid: this.vm.id
      }).then(response => {
        this.secondaryIPs = response.listnicsresponse.nic[0].secondaryip
      })
    },
    fetchPublicIps (networkid) {
      this.listIps.loading = true
      this.listIps.opts = []
      getAPI('listPublicIpAddresses', {
        networkid: networkid,
        allocatedonly: false,
        forvirtualnetwork: false
      }).then(json => {
        const listPublicIps = json.listpublicipaddressesresponse.publicipaddress || []
        listPublicIps.forEach(item => {
          if (item.state === 'Free') {
            this.listIps.opts.push({
              ipaddress: item.ipaddress
            })
          }
        })
        this.listIps.opts.sort(function (a, b) {
          const currentIp = a.ipaddress.replaceAll('.', '')
          const nextIp = b.ipaddress.replaceAll('.', '')
          if (parseInt(currentIp) < parseInt(nextIp)) { return -1 }
          if (parseInt(currentIp) > parseInt(nextIp)) { return 1 }
          return 0
        })
      }).finally(() => {
        this.listIps.loading = false
      })
    },
    showAddNicModal () {
      this.showAddNetworkModal = true
      this.listNetworks()
    },
    closeModals () {
      this.showAddNetworkModal = false
      this.showUpdateIpMacModal = false
      this.showSecondaryIpModal = false
      this.addNetworkData.network = ''
      this.addNetworkData.ip = ''
      this.addNetworkData.makedefault = false
      this.form = {
        ipaddress: '',
        macaddress: ''
      }
      this.formRef = 'updateNicForm'
      this.rules = {}
      this.newSecondaryIp = ''
    },
    onChangeIPAddress (record) {
      this.formRef = ref()
      this.form = reactive({
        ipaddress: record.nic.ipaddress,
        macaddress: record.nic.macaddress
      })
      this.rules = reactive({})
      this.editNicResource = record.nic
      this.editIpAddressNic = record.nic.id
      this.showUpdateIpMacModal = true
      if (record.nic.type === 'Shared') {
        this.fetchPublicIps(record.nic.networkid)
      }
    },
    onAcquireSecondaryIPAddress (record) {
      if (record.nic.type === 'Shared') {
        this.fetchPublicIps(record.nic.networkid)
      } else {
        this.listIps.opts = []
      }

      this.editNicResource = record.nic
      this.editNetworkId = record.nic.networkid
      this.fetchSecondaryIPs(record.nic.id)
    },
    submitAddNetwork () {
      if (this.loadingNic) return
      const params = {}
      params.virtualmachineid = this.vm.id
      params.networkid = this.addNetworkData.network
      if (this.addNetworkData.ip) {
        params.ipaddress = this.addNetworkData.ip
      }
      this.showAddNetworkModal = false
      this.loadingNic = true
      postAPI('addNicToVirtualMachine', params).then(response => {
        this.$pollJob({
          jobId: response.addnictovirtualmachineresponse.jobid,
          successMessage: this.$t('message.success.add.network'),
          successMethod: async () => {
            if (this.addNetworkData.makedefault) {
              try {
                this.nic = await this.getNic(params.networkid, params.virtualmachineid)
                if (this.nic) {
                  this.setAsDefault(this.nic)
                } else {
                  this.$notifyError('NIC data not found.')
                }
              } catch (error) {
                this.$notifyError('Failed to fetch NIC data.')
              }
            }
            this.loadingNic = false
            this.closeModals()
          },
          errorMessage: this.$t('message.add.network.failed'),
          errorMethod: () => {
            this.loadingNic = false
            this.closeModals()
          },
          loadingMessage: this.$t('message.add.network.processing'),
          catchMessage: this.$t('error.fetching.async.job.result'),
          catchMethod: () => {
            this.loadingNic = false
            this.closeModals()
            this.$emit('refresh')
          }
        })
      }).catch(error => {
        this.$notifyError(error)
        this.loadingNic = false
      })
    },
    getNic (networkid, virtualmachineid) {
      const params = {}
      params.virtualmachineid = virtualmachineid
      params.networkid = networkid
      return getAPI('listNics', params).then(response => {
        return response.listnicsresponse.nic[0]
      })
    },
    setAsDefault (item) {
      this.loadingNic = true
      postAPI('updateDefaultNicForVirtualMachine', {
        virtualmachineid: this.vm.id,
        nicid: item.id
      }).then(response => {
        this.$pollJob({
          jobId: response.updatedefaultnicforvirtualmachineresponse.jobid,
          successMessage: `${this.$t('label.success.set')} ${item.networkname} ${this.$t('label.as.default')}. ${this.$t('message.set.default.nic.manual')}.`,
          successMethod: () => {
            this.loadingNic = false
          },
          errorMessage: `${this.$t('label.error.setting')} ${item.networkname} ${this.$t('label.as.default')}`,
          errorMethod: () => {
            this.loadingNic = false
          },
          loadingMessage: `${this.$t('label.setting')} ${item.networkname} ${this.$t('label.as.default')}...`,
          catchMessage: this.$t('error.fetching.async.job.result'),
          catchMethod: () => {
            this.loadingNic = false
            this.$emit('refresh')
          }
        })
      }).catch(error => {
        this.$notifyError(error)
        this.loadingNic = false
      })
    },
    submitUpdateIPMac () {
      if (this.loadingNic) return
      this.loadingNic = true
      this.showUpdateIpMacModal = false
      const params = {
        nicId: this.editIpAddressNic
      }
      if (this.form && this.form.ipaddress) {
        params.ipaddress = this.form.ipaddress
      }
      if (this.form && this.form.macaddress) {
        params.macaddress = this.form.macaddress
      }
      postAPI('updateVmNicIp', params).then(response => {
        this.$pollJob({
          jobId: response.updatevmnicipresponse.jobid,
          successMessage: this.$t('message.success.update.ipaddress'),
          successMethod: () => {
            this.loadingNic = false
            this.closeModals()
          },
          errorMessage: this.$t('label.error'),
          errorMethod: () => {
            this.loadingNic = false
            this.closeModals()
          },
          loadingMessage: this.$t('message.update.ipaddress.processing'),
          catchMessage: this.$t('error.fetching.async.job.result'),
          catchMethod: () => {
            this.loadingNic = false
            this.closeModals()
            this.$emit('refresh')
          }
        })
      })
        .catch(error => {
          this.$notifyError(error)
          this.loadingNic = false
        })
    },
    removeNIC (item) {
      this.loadingNic = true

      postAPI('removeNicFromVirtualMachine', {
        nicid: item.id,
        virtualmachineid: this.vm.id
      }).then(response => {
        this.$pollJob({
          jobId: response.removenicfromvirtualmachineresponse.jobid,
          successMessage: this.$t('message.success.remove.nic'),
          successMethod: () => {
            this.loadingNic = false
          },
          errorMessage: this.$t('message.error.remove.nic'),
          errorMethod: () => {
            this.loadingNic = false
          },
          loadingMessage: this.$t('message.remove.nic.processing'),
          catchMessage: this.$t('error.fetching.async.job.result'),
          catchMethod: () => {
            this.loadingNic = false
            this.$emit('refresh')
          }
        })
      })
        .catch(error => {
          this.$notifyError(error)
          this.loadingNic = false
        })
    },
    submitSecondaryIP () {
      if (this.loadingNic) return
      this.loadingNic = true

      const params = {}
      params.nicid = this.selectedNicId
      if (this.newSecondaryIp) {
        params.ipaddress = this.newSecondaryIp
      }

      postAPI('addIpToNic', params).then(response => {
        this.$pollJob({
          jobId: response.addiptovmnicresponse.jobid,
          successMessage: this.$t('message.success.add.secondary.ipaddress'),
          successMethod: () => {
            this.loadingNic = false
            this.fetchSecondaryIPs(this.selectedNicId)
          },
          errorMessage: this.$t('message.error.add.secondary.ipaddress'),
          errorMethod: () => {
            this.loadingNic = false
            this.fetchSecondaryIPs(this.selectedNicId)
          },
          loadingMessage: this.$t('message.add.secondary.ipaddress.processing'),
          catchMessage: this.$t('error.fetching.async.job.result'),
          catchMethod: () => {
            this.loadingNic = false
            this.fetchSecondaryIPs(this.selectedNicId)
            this.$emit('refresh')
          }
        })
      }).catch(error => {
        this.$notifyError(error)
        this.loadingNic = false
      }).finally(() => {
        this.newSecondaryIp = null
        this.fetchPublicIps(this.editNetworkId)
      })
    },
    removeSecondaryIP (id) {
      this.loadingNic = true

      postAPI('removeIpFromNic', { id }).then(response => {
        this.$pollJob({
          jobId: response.removeipfromnicresponse.jobid,
          successMessage: this.$t('message.success.remove.secondary.ipaddress'),
          successMethod: () => {
            this.loadingNic = false
            this.fetchSecondaryIPs(this.selectedNicId)
            this.fetchPublicIps(this.editNetworkId)
          },
          errorMessage: this.$t('message.error.remove.secondary.ipaddress'),
          errorMethod: () => {
            this.loadingNic = false
            this.fetchSecondaryIPs(this.selectedNicId)
          },
          loadingMessage: this.$t('message.remove.secondary.ipaddress.processing'),
          catchMessage: this.$t('error.fetching.async.job.result'),
          catchMethod: () => {
            this.loadingNic = false
            this.fetchSecondaryIPs(this.selectedNicId)
          }
        })
      }).catch(error => {
        this.$notifyError(error)
        this.loadingNic = false
        this.fetchSecondaryIPs(this.selectedNicId)
      })
    },
    onChangeNicLinkState (record) {
      console.log('record.nic.id :>> ', record.nic.id)
      console.log('record.nic.id :>> ', record.nic.linkstate)
      const params = {}
      params.virtualmachineid = this.vm.id
      params.nicid = record.nic.id
      params.linkstate = !record.nic.linkstate
      getAPI('UpdateVmNicLinkState', params).then(response => {
        this.$pollJob({
          jobId: response.updatevmniclinkstateresponse.jobid,
          successMessage: this.$t('message.success.update.nic.linkstate'),
          successMethod: () => {
            this.loadingNic = false
          },
          errorMessage: this.$t('label.error'),
          errorMethod: () => {
            this.loadingNic = false
          },
          loadingMessage: this.$t('message.update.nic.linkstate.processing'),
          catchMessage: this.$t('error.fetching.async.job.result'),
          catchMethod: () => {
            this.loadingNic = false
            this.parentFetchData()
          }
        })
      })
        .catch(error => {
          this.$notifyError(error)
          this.loadingNic = false
        })
    }
  }
}
</script>

<style scoped>
.form-layout {
  width: 80vw;

  @media (min-width: 600px) {
    width: 450px;
  }

  .action-button {
    text-align: right;
    margin-top: 20px;

    button {
      margin-right: 5px;
    }
  }
}

.modal-form {
  display: flex;
  flex-direction: column;

  &__label {
    margin-top: 20px;
    margin-bottom: 5px;
    font-weight: bold;

    &--no-margin {
      margin-top: 0;
    }
  }
}

.wide-modal {
  min-width: 50vw;
}

:deep(.ant-list-item) {
  padding-top: 12px;
  padding-bottom: 12px;
}
</style>
