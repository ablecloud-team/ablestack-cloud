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
  <section class="host-oobm-content" :aria-label="$t('label.outofbandmanagement')">
    <resource-table-toolbar :loading="fetchLoading || listRefreshing > 0" @refresh="fetchData" />
    <a-alert v-if="listRefreshFailed" type="error" show-icon :message="$t('message.host.oobm.failed')" />
    <a-spin :spinning="fetchLoading">
      <a-list v-if="items.length" class="resource-details-list vm-details-list" size="small" :data-source="items" :row-key="item => item.label">
        <template #renderItem="{ item }">
          <a-list-item>
            <div>
              <strong>{{ $t(item.label) }}</strong>
              <div>
                <a-tag v-if="item.status" :color="hostStatusColor(item.value)">{{ $t(hostStatusLabel(item.value)) }}</a-tag>
                <template v-else>{{ item.value }}</template>
              </div>
            </div>
          </a-list-item>
        </template>
      </a-list>
      <a-empty v-else-if="!listRefreshFailed && !fetchLoading" :description="$t(host.hypervisor === 'External' ? 'message.host.oobm.unsupported' : 'message.host.oobm.unconfigured')" />
    </a-spin>
  </section>
</template>

<script>
import { listRefreshMixin } from '@/utils/listRefreshMixin'

import { getAPI } from '@/api'
import ResourceTableToolbar from '@/components/view/ResourceTableToolbar'
import { hostStatusColor, hostStatusLabel } from '@/utils/hostPresentation'

export default {
  mixins: [listRefreshMixin(['fetchData'])],
  name: 'OobmTab',
  components: { ResourceTableToolbar },
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
      host: {},
      fetchLoading: false
    }
  },
  created () {
    this.fetchData()
  },
  watch: {
    resource: {
      deep: true,
      handler (newItem, oldItem) {
        if (this.resource) {
          this.host = this.resource
          if (this.resource.id && newItem && newItem.id !== oldItem.id) {
            this.fetchData()
          }
        }
      }
    }
  },
  computed: {
    items () {
      const remote = this.host.outofbandmanagement
      if (!remote) return []

      const settings = this.host.details || {}
      const fields = [
        ['driver', remote.driver],
        ['address', remote.address],
        ['port', remote.port],
        ['username', remote.username],
        ['manageconsoleprotocol', settings.manageconsoleprotocol],
        ['manageconsoleport', settings.manageconsoleport]
      ]
      return [
        { label: 'label.outofbandmanagement.enable', value: remote.enabled, status: true },
        { label: 'label.powerstate', value: remote.powerstate, status: true },
        ...fields.filter(([, value]) => value !== undefined && value !== null && value !== '')
          .map(([field, value]) => ({ label: 'label.' + field, value }))
      ]
    }
  },
  methods: {
    hostStatusColor,
    hostStatusLabel,
    fetchData () {
      const listRequest = this.listRequestToken('fetchData')
      this.fetchLoading = !listRequest.loaded
      return getAPI('listHosts', { id: this.resource.id }, { preserveOnFailure: true }).then(json => {
        if (!this.isListRequestCurrent('fetchData', listRequest)) return

        const host = json.listhostsresponse?.host?.find(row => row.id === this.resource.id)
        if (!host) throw new Error(this.$t('message.host.oobm.failed'))
        this.host = host
        this.listRefreshFailed = false
      }).catch(error => {
        if (!this.isListRequestCurrent('fetchData', listRequest)) return
        listRequest.failed = true
        this.listRefreshFailed = true
        if (listRequest.loaded) return

        this.$notifyError(error)
      }).finally(() => {
        if (!this.isListRequestCurrent('fetchData', listRequest)) return
        this.fetchLoading = false
      })
    }
  }
}
</script>

<style scoped src="@/style/components/view/resource-details-list.css" />

<style scoped>
.host-oobm-content {
  min-width: 0;
}

.host-oobm-content :deep(.ant-alert) {
  margin-bottom: 12px;
}
</style>
