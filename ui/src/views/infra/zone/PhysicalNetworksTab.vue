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
  <div class="physical-networks-tab">
    <p v-if="listRefreshFailed" role="status">{{ $t('message.list.refresh.stale') }}</p>
    <resource-table-toolbar :loading="fetchLoading || listRefreshing > 0" @refresh="fetchData()">
      <a-button
        type="primary"
        @click="showAddPhyNetModal"
        :loading="loading"
        :disabled="!('createPhysicalNetwork' in $store.getters.apis)">
        <template #icon><plus-outlined /></template> {{ $t('label.add.physical.network') }}
      </a-button>
    </resource-table-toolbar>
    <detail-tab-table
      size="small"
      row-key="id"
      :columns="columns"
      :data-source="networks"
      :loading="fetchLoading"
      :pagination="{ defaultPageSize: 10, showSizeChanger: true }"
      :scroll="{ x: 740 }">
      <template #bodyCell="{ column, record, text }">
        <router-link v-if="column.key === 'name'" :to="{ path: '/physicalnetwork/' + record.id, query: zoneListQuery($route.query) }">{{ text }}</router-link>
        <status v-else-if="column.key === 'state'" :text="text" display-text />
        <span v-else>{{ text || '—' }}</span>
      </template>
    </detail-tab-table>
    <mold-dialog
      size="md"
      focus-guard
      form-actions
      :visible="addPhyNetModal"
      :title="$t('label.add.physical.network')"
      :maskClosable="false"
      :closable="true"
      @cancel="closeModals">
      <a-form
        :ref="formRef"
        :model="form"
        :rules="rules"
        @finish="handleAddPhyNet"
        v-ctrl-enter="handleAddPhyNet"
        layout="vertical"
        class="form"

      >
        <a-form-item name="name" ref="name">
          <template #label>
            <tooltip-label :title="$t('label.name')" :tooltip="apiParams.name.description"/>
          </template>
          <a-input v-model:value="form.name" :placeholder="apiParams.name.description" />
        </a-form-item>
        <a-form-item name="isolationmethods" ref="isolationmethods">
          <template #label>
            <tooltip-label :title="$t('label.isolationmethods')" :tooltip="apiParams.isolationmethods.description"/>
          </template>
          <a-select
            v-model:value="form.isolationmethods"
            showSearch
            optionFilterProp="label"
            :filterOption="(input, option) => {
              return option.label.toLowerCase().indexOf(input.toLowerCase()) >= 0
            }"
            v-focus="true"
            :placeholder="apiParams.isolationmethods.description">
            <a-select-option v-for="i in isolationMethods" :key="i" :value="i" :label="i">{{ i }}</a-select-option>
          </a-select>
        </a-form-item>
        <a-form-item name="vlan" ref="vlan">
          <template #label>
            <tooltip-label :title="$t('label.vlan')" :tooltip="apiParams.vlan.description"/>
          </template>
          <a-input v-model:value="form.vlan" :placeholder="apiParams.vlan.description" />
        </a-form-item>
        <a-form-item name="tags" ref="tags">
          <template #label>
            <tooltip-label :title="$t('label.tags')" :tooltip="apiParams.tags.description"/>
          </template>
          <a-select
            mode="tags"
            v-model:value="form.tags"
            :placeholder="apiParams.tags.description">
          </a-select>
        </a-form-item>
        <a-form-item name="networkspeed" ref="networkspeed">
          <template #label>
            <tooltip-label :title="$t('label.networkspeed')" :tooltip="apiParams.networkspeed.description"/>
          </template>
          <a-input v-model:value="form.networkspeed" :placeholder="apiParams.networkspeed.description" />
        </a-form-item>
        <a-form-item name="broadcastdomainrange" ref="broadcastdomainrange">
          <template #label>
            <tooltip-label :title="$t('label.broadcastdomainrange')" :tooltip="apiParams.broadcastdomainrange.description"/>
          </template>
          <a-input v-model:value="form.broadcastdomainrange" :placeholder="apiParams.broadcastdomainrange.description" />
        </a-form-item>

        <mold-dialog-actions>
          <a-button @click="closeModals">{{ $t('label.cancel') }}</a-button>
          <a-button type="primary" ref="submit" @click="handleAddPhyNet">{{ $t('label.ok') }}</a-button>
        </mold-dialog-actions>
      </a-form>
    </mold-dialog>
  </div>
</template>

<script>
import ResourceTableToolbar from '@/components/view/ResourceTableToolbar'
import MoldDialog from '@/components/view/MoldDialog'
import MoldDialogActions from '@/components/view/MoldDialogActions'
import { detailTabRouteKey, listRefreshMixin } from '@/utils/listRefreshMixin'

import { ref, reactive, toRaw } from 'vue'
import { getAPI, postAPI } from '@/api'
import { zoneListQuery } from '@/utils/zone'
import Status from '@/components/widgets/Status'
import TooltipLabel from '@/components/widgets/TooltipLabel'

export default {
  mixins: [listRefreshMixin(['fetchData'], { routeKey: vm => detailTabRouteKey(vm.$route) })],
  name: 'PhysicalNetworksTab',
  components: {
    ResourceTableToolbar,
    MoldDialog,
    MoldDialogActions,
    Status,
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
  data () {
    return {
      networks: [],
      fetchLoading: false,
      addPhyNetModal: false
    }
  },
  beforeCreate () {
    this.apiParams = this.$getApiParams('createPhysicalNetwork')
  },
  created () {
    this.fetchData()
    this.initAddPhyNetForm()
  },
  watch: {
    'resource.id' () {
      this.networks = []
      this.fetchData()
    }
  },
  computed: {
    columns () {
      return ['name', 'state', 'isolationmethods', 'vlan', 'traffictype', 'broadcastdomainrange']
        .map(key => ({ key, dataIndex: key, title: this.$t('label.' + key) }))
    },
    isolationMethods () {
      return ['VLAN', 'VXLAN', 'GRE', 'STT', 'BCF_SEGMENT', 'SSP', 'ODL', 'L3VPN', 'VCS', 'NSX', 'NETRIS']
    }
  },
  methods: {
    zoneListQuery,
    async fetchData () {
      if (!this.resource.id) return
      const request = this.listRequestToken('fetchData')
      const current = () => this.isListRequestCurrent('fetchData', request)
      this.fetchLoading = !request.loaded
      try {
        const json = await getAPI('listPhysicalNetworks', { zoneid: this.resource.id }, { preserveOnFailure: true })
        if (!current()) return
        const networks = json.listphysicalnetworksresponse.physicalnetwork || []
        const previous = new Map(this.networks.map(network => [network.id, network.traffictype]))
        this.networks = networks.map(network => ({ ...network, traffictype: previous.get(network.id) ?? network.traffictype }))
        request.loaded = true
        this.fetchLoading = false
        if (!('listTrafficTypes' in this.$store.getters.apis)) return

        const labels = await Promise.allSettled(networks.map(async network => {
          const result = await getAPI('listTrafficTypes', { physicalnetworkid: network.id }, { preserveOnFailure: true })
          if (!current()) return
          const label = (result.listtraffictypesresponse.traffictype || []).map(type => type.traffictype).filter(Boolean).join(', ')
          this.networks = this.networks.map(row => row.id === network.id ? { ...row, traffictype: label } : row)
        }))
        if (!current()) return
        request.failed = labels.some(result => result.status === 'rejected')
        if (request.failed) this.listRefreshFailed = true
      } catch (error) {
        if (!current()) return
        request.failed = true
        this.listRefreshFailed = true
        if (!request.loaded) this.$notifyError(error)
      } finally {
        if (current()) this.fetchLoading = false
      }
    },
    showAddPhyNetModal () {
      this.addPhyNetModal = true
    },
    initAddPhyNetForm () {
      this.formRef = ref()
      this.form = reactive({})
      this.rules = reactive({
        name: [{ required: true, message: this.$t('label.required') }]
      })
    },
    handleAddPhyNet () {
      this.formRef.value.validate().then(() => {
        const values = toRaw(this.form)
        values.zoneid = this.resource.id
        if (values.tags) {
          values.tags = values.tags.join()
        }
        postAPI('createPhysicalNetwork', values).then(response => {
          this.$pollJob({
            jobId: response.createphysicalnetworkresponse.jobid,
            successMessage: this.$t('message.success.add.physical.network'),
            successMethod: () => {
              this.fetchData()
              this.closeModals()
            },
            errorMessage: this.$t('message.add.physical.network.failed'),
            errorMethod: () => {
              this.fetchData()
              this.closeModals()
            },
            loadingMessage: this.$t('message.add.physical.network.processing'),
            catchMessage: this.$t('error.fetching.async.job.result'),
            catchMethod: () => {
              this.fetchData()
              this.closeModals()
            }
          })
        }).catch(error => {
          this.$notifyError(error)
        })
      })
    },
    closeModals () {
      this.addPhyNetModal = false
    }
  }
}
</script>
