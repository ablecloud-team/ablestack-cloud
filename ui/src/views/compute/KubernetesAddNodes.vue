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
      :model="form"
      :ref="formRef"
      :rules="rules"
      @finish="handleSubmit"
      layout="vertical"
class="mold-form-dialog">
      <div class="mold-form-content"><KubernetesDialogContext :resource="resource" /><a-alert v-if="operationIncomplete" type="warning" show-icon class="mold-dialog-summary" :message="$t('message.kubernetes.ui.node.incomplete')" />
      <div v-if="vms.length > 0">
        <a-form-item name="nodeids" ref="nodeids">
          <template #label>
            <tooltip-label :title="$t('label.target.virtualmachines')" :tooltip="apiParams.nodeids.description"/>
          </template>
          <a-select
            v-model:value="form.nodeids"
            :placeholder="$t('label.target.virtualmachines')"
            mode="multiple"
            :loading="loading"
            showSearch
            optionFilterProp="label"
            :filterOption="(input, option) => {
              return option.label.toLowerCase().indexOf(input.toLowerCase()) >= 0
            }" >
            <a-select-option v-for="vm in vms" :key="vm.id" :label="vm.name">
              {{ vm.name }}
            </a-select-option>
          </a-select>
        </a-form-item>
        <a-form-item name="mountcksiso" ref="mountcksiso">
          <a-checkbox v-model:checked="form.mountcksiso">
            {{ $t('label.mount.cks.iso.on.vr') }}
          </a-checkbox>
        </a-form-item>
        <a-form-item name="manualupgrade" ref="manualupgrade">
          <a-checkbox v-model:checked="form.manualupgrade">
            {{ $t('label.cks.cluster.node.manual.upgrade') }}
          </a-checkbox>
        </a-form-item>
      </div>
      <p v-else v-html="$t('label.vms.empty')" />

      </div>
      <div :span="24" class="action-button">
        <a-button @click="closeAction">{{ $t('label.cancel') }}</a-button>
        <a-button :loading="loading" ref="submit" type="primary" @click="handleSubmit">{{ $t('label.ok') }}</a-button>
      </div>
    </a-form>
  </a-spin>
</template>

<script>
import { ref, reactive, toRaw } from 'vue'
import KubernetesDialogContext from '@/components/view/KubernetesDialogContext'
import { getAPI, postAPI } from '@/api'
import TooltipLabel from '@/components/widgets/TooltipLabel'

export default {
  name: 'AddNodesToKubernetesCluster',
  components: {
    KubernetesDialogContext,
    TooltipLabel
  },
  props: {
    resource: {
      type: Object,
      required: true
    }
  },
  data () {
    return {
      vms: [],
      pendingNodeJob: null,
      operationIncomplete: false,
      loading: false
    }
  },
  inject: ['parentFetchData'],
  beforeCreate () {
    this.apiParams = this.$getApiParams('addNodesToKubernetesCluster')
  },
  created () {
    this.formRef = ref()
    this.form = reactive({})
    this.rules = reactive({
      nodeids: [{ type: 'array', required: true, min: 1, message: this.$t('label.required') }]
    })
    this.fetchData()
  },
  methods: {
    fetchData () {
      this.fetchVms()
    },
    async fetchVms () {
      this.loading = true
      try {
        const attached = new Set((this.resource.virtualmachines || []).map(vm => vm.id))
        const scope = this.resource.projectid ? { projectid: this.resource.projectid } : { account: this.resource.account, domainid: this.resource.domainid }
        const response = await getAPI('listVirtualMachines', { ...scope, details: 'all', listall: true, networkid: this.resource.networkid, state: 'Running', pagesize: -1 })
        this.vms = (response.listvirtualmachinesresponse.virtualmachine || []).filter(vm => !attached.has(vm.id) && vm.state === 'Running' && (vm.nic || []).some(nic => nic.isdefault && nic.networkid === this.resource.networkid))
      } catch (error) { this.$notifyError(error) } finally { this.loading = false }
    },
    closeAction () {
      this.$emit('close-action')
    },
    handleSubmit (e) {
      if (e && e.preventDefault) e.preventDefault()
      if (this.loading) return
      return this.formRef.value.validate().then(async () => {
        const values = toRaw(this.form)
        const params = {
          id: this.resource.id
        }
        if (values.nodeids) {
          params.nodeids = values.nodeids.join(',')
        }
        if (values.mountcksiso) {
          params.mountcksisoonvr = values.mountcksiso
        }
        if (values.manualupgrade) {
          params.manualupgrade = values.manualupgrade
        }
        this.loading = true
        try {
          const jobId = this.pendingNodeJob || await this.addNodesToKubernetesCluster(params)
          this.pendingNodeJob = jobId
          this.operationIncomplete = false
          const result = await this.$pollJob({
            jobId,
            title: this.$t('label.action.add.nodes.to.kubernetes.cluster'),
            description: this.resource.name,
            loadingMessage: `${this.$t('message.adding.nodes.to.cluster')} ${this.resource.name}`,
            catchMessage: this.$t('error.fetching.async.job.result'),
            successMessage: `${this.$t('message.success.add.nodes.to.cluster')} ${this.resource.name}`,
            successMethod: () => {
              this.parentFetchData()
            },
            action: {
              isFetchData: false
            }
          })
          if (result.jobstatus === 1) { this.pendingNodeJob = null; this.closeAction() } else {
            this.operationIncomplete = true
            if (result.jobstatus === 2) this.pendingNodeJob = null
            this.parentFetchData()
          }
          this.loading = false
        } catch (error) {
          await this.$notifyError(error)
          this.operationIncomplete = true
          this.loading = false
        }
      })
    },
    addNodesToKubernetesCluster (params) {
      return new Promise((resolve, reject) => {
        postAPI('addNodesToKubernetesCluster', params).then(json => {
          const jobId = json.addnodestokubernetesclusterresponse.jobid
          return resolve(jobId)
        }).catch(error => {
          return reject(error)
        })
      })
    }
  }
}
</script>
<style lang="scss" scoped></style>
