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
  <div class="mold-form-dialog">
    <div class="mold-form-content"><KubernetesDialogContext :resource="resource" />
      <a-alert type="info" show-icon :message="$t('message.kubernetes.external.mapping')" class="mold-dialog-summary" />
      <a-alert v-if="failed" type="error" show-icon :message="$t('message.list.refresh.stale')" />
      <a-form layout="vertical">
        <a-form-item v-if="!removing" :label="$t('label.kubernetes.node.role')">
          <a-radio-group v-model:value="isControl" :disabled="busy"><a-radio :value="false">{{ $t('label.kubernetes.node.worker') }}</a-radio><a-radio :value="true">{{ $t('label.kubernetes.node.control') }}</a-radio></a-radio-group>
        </a-form-item>
        <div class="detail-tab-toolbar">
          <a-input-search v-model:value="search" :placeholder="$t('label.search')" allow-clear @search="page = 1; fetchVms()" />
          <a-button :loading="busy" @click="fetchVms"><template #icon><reload-outlined /></template>{{ $t('label.refresh') }}</a-button>
        </div>
        <a-table
size="small"
:loading="busy"
:columns="columns"
:data-source="vms"
:pagination="false"
row-key="id"
:scroll="{ x: 650 }"
          :row-selection="{ selectedRowKeys: selected, onChange: keys => { selected = keys } }" />
        <div class="detail-tab-pagination"><a-pagination size="small" :current="page" :total="count" :page-size="20" @change="value => { page = value; fetchVms() }" /></div>
      </a-form>
    </div>
    <div class="action-button"><a-button :disabled="busy" @click="$emit('close-action')">{{ $t('label.cancel') }}</a-button>
      <a-button type="primary" :loading="busy" :disabled="!selected.length || failed" @click="submit">{{ $t('label.ok') }}</a-button></div>
  </div>
</template>
<script>
import KubernetesDialogContext from '@/components/view/KubernetesDialogContext'
import { getAPI, postAPI } from '@/api'
export default {
  components: { KubernetesDialogContext },
  name: 'ExternalKubernetesNodes',
  props: { resource: { type: Object, required: true }, currentAction: { type: Object, default: () => ({}) } },
  emits: ['close-action'],
  inject: { parentFetchData: { default: () => {} } },
  data () { return { busy: false, failed: false, vms: [], count: 0, page: 1, search: '', selected: [], isControl: false, request: 0 } },
  computed: {
    removing () { return this.currentAction.api === 'removeVirtualMachinesFromKubernetesCluster' },
    columns () { return ['name', 'state', 'zonename'].map(field => ({ title: this.$t('label.' + field), dataIndex: field })) }
  },
  watch: { resource: { deep: true, handler () { this.selected = []; this.page = 1; this.fetchVms() } } },
  created () { this.fetchVms() },
  beforeUnmount () { this.request++ },
  methods: {
    async fetchVms () {
      const token = ++this.request
      this.busy = true; this.failed = false
      try {
        if (this.removing) {
          const matches = (this.resource.virtualmachines || []).filter(vm => !this.search || (vm.name || '').toLowerCase().includes(this.search.toLowerCase()))
          this.count = matches.length; this.vms = matches.slice((this.page - 1) * 20, this.page * 20)
          return
        }
        const scope = this.resource.projectid ? { projectid: this.resource.projectid } : { account: this.resource.account, domainid: this.resource.domainid }
        const response = await getAPI('listVirtualMachines', { ...scope, zoneid: this.resource.zoneid, networkid: this.resource.networkid, listall: true, page: this.page, pagesize: 20, keyword: this.search })
        if (token !== this.request) return
        const body = response.listvirtualmachinesresponse
        if (!body) throw new Error('Invalid VM inventory')
        const attached = new Set((this.resource.virtualmachines || []).map(vm => vm.id))
        this.vms = (body.virtualmachine || []).filter(vm => !attached.has(vm.id))
        this.count = body.count || 0
      } catch (error) { if (token === this.request) { this.failed = true; this.$notifyError(error) } } finally { if (token === this.request) this.busy = false }
    },
    async submit () {
      const api = this.removing ? 'removeVirtualMachinesFromKubernetesCluster' : 'addVirtualMachinesToKubernetesCluster'
      if (this.busy || !this.selected.length || !this.$store.getters.apis[api]) return
      this.busy = true
      try {
        const params = { id: this.resource.id, virtualmachineids: this.selected.join(',') }
        if (!this.removing) params.iscontrolnode = this.isControl
        const response = await postAPI(api, params)
        const body = response[api.toLowerCase() + 'response']
        if (!body || body.success === false || body.errorcode) throw new Error(body?.errortext || 'VM mapping failed')
        this.parentFetchData(); this.$emit('close-action')
      } catch (error) { this.$notifyError(error) } finally { this.busy = false }
    }
  }
}
</script>
