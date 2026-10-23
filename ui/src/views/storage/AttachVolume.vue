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
  <div class="volume-attach-dialog">
    <a-spin :spinning="loading">
      <div class="volume-attach-content">
        <a-alert v-if="virtualmachines.some(vm => volumeBackupReason(vm))" type="warning" show-icon :message="$t('message.vmvolume.backup.blocked')" style="margin-bottom: 16px" />
        <a-form
          class="form"
          layout="vertical"
          :ref="formRef"
          :model="form"
          :rules="rules"
          @finish="handleSubmit"
          v-ctrl-enter="handleSubmit"
         >
          <div style="margin-bottom: 10px">
            <a-alert type="warning">
              <template #message>
                <div v-html="$t('message.confirm.attach.disk')"></div>
              </template>
            </a-alert>
          </div>
          <a-form-item :label="$t('label.virtualmachinename')" name="virtualmachineid" ref="virtualmachineid">
            <a-select
              v-focus="true"
              v-model:value="form.virtualmachineid"
              :placeholder="$t('message.volume.attach.vm.search')"
              :disabled="loading || !!lookupError"
              showSearch
              optionLabelProp="label"
              :filterOption="filterVmOption" >
              <a-select-option v-for="vm in virtualmachines" :key="vm.id" :value="vm.id" :label="vmLabel(vm)" :disabled="!!volumeBackupReason(vm)" :title="vmLabel(vm)">
                <div class="volume-attach-vm-name">{{ vmDisplayName(vm) }}</div>
                <div class="volume-attach-vm-identity">{{ vmIdentity(vm) }}</div>
                <div v-if="volumeBackupReason(vm)" class="volume-attach-vm-identity">{{ $t(volumeBackupReason(vm)) }}</div>
              </a-select-option>
            </a-select>
            <a-alert v-if="lookupError" type="error" show-icon :message="lookupError" style="margin-top: 8px" />
            <a-button v-if="lookupError" size="small" :loading="loading" style="margin-top: 8px" @click="fetchData">{{ $t('label.retry') }}</a-button>
          </a-form-item >
          <a-form-item :label="$t('label.deviceid')">
            <div style="margin-bottom: 10px">
              <a-collapse>
                <a-collapse-panel header="More information about deviceID">
                  <a-alert type="warning">
                    <template #message>
                      <span v-html="apiParams.deviceid.description" />
                    </template>
                  </a-alert>
                </a-collapse-panel>
              </a-collapse>
            </div>
            <a-input-number
              v-model:value="form.deviceid"
              style="width: 100%;"
              :min="0"
              :placeholder="$t('label.deviceid')"
            />
          </a-form-item>
        </a-form>
      </div>
      <MoldDialogActions class="actions" :busy="loading">
        <a-button @click="closeAction">{{ $t('label.cancel') }}</a-button>
        <a-button type="primary" ref="submit" :disabled="!canSubmit" @click="handleSubmit">{{ $t('label.ok') }}</a-button>
      </MoldDialogActions>
    </a-spin>
  </div>
</template>
<script>
import MoldDialogActions from '@/components/view/MoldDialogActions'
import { ref, reactive, toRaw } from 'vue'
import { postAPI } from '@/api'
import { getAPI } from '@/utils/storageApi'
import { volumeActionReason, volumeBackupReason } from '@/utils/vmVolumeActions'
import { readAllocationList } from '@/utils/volumeAllocation'
import { vmDisplayName, vmIdentity, vmLabel, vmMatchesSearch } from '@/utils/volumeAttachVm'

export default {
  components: { MoldDialogActions },
  name: 'AttachVolume',
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
      lookupError: '',
      lookupToken: null
    }
  },
  computed: {
    vmQuery () {
      const query = { zoneid: this.resource.zoneid, details: 'min' }
      if (this.resource.hypervisor && this.resource.hypervisor !== 'None') query.hypervisor = this.resource.hypervisor
      if (this.resource.projectid) query.projectid = this.resource.projectid
      else { query.account = this.resource.account; query.domainid = this.resource.domainid }
      return query
    },
    lookupScope () {
      return JSON.stringify([this.resource.id, this.vmQuery, this.$store.getters.userInfo?.id, this.$store.getters.project?.id])
    },
    canSubmit () {
      return !this.loading && !this.lookupError && this.virtualmachines.some(vm => vm.id === this.form.virtualmachineid && !volumeBackupReason(vm))
    }
  },
  watch: {
    lookupScope () {
      this.virtualmachines = []
      this.form.virtualmachineid = undefined
      this.fetchData()
    }
  },
  beforeCreate () {
    this.apiParams = this.$getApiParams('attachVolume')
  },
  created () {
    this.initForm()
    this.fetchData()
  },
  methods: {
    volumeBackupReason,
    vmDisplayName,
    vmIdentity,
    vmLabel,
    filterVmOption (input, option) {
      return vmMatchesSearch(this.virtualmachines.find(vm => vm.id === option.value), input)
    },
    initForm () {
      this.formRef = ref()
      this.form = reactive({})
      this.rules = reactive({
        virtualmachineid: [{ required: true, message: this.$t('message.error.select') }],
        deviceid: [{ required: true, message: this.$t('message.error.select') }]
      })
    },
    async fetchData () {
      const token = Symbol('attach VM lookup')
      this.lookupToken = token
      const scope = this.lookupScope
      const current = () => this.lookupToken === token && this.lookupScope === scope && !this.$?.isUnmounted
      this.loading = true
      this.lookupError = ''
      try {
        const query = this.vmQuery
        const states = await Promise.all(['Running', 'Stopped'].map(state =>
          readAllocationList(getAPI, 'listVirtualMachines', 'virtualmachine', { ...query, state }, current)))
        if (!current() || states.some(rows => !rows)) return
        this.virtualmachines = [...new Map(states.flat().map(vm => [vm.id, vm])).values()]
        if (!this.virtualmachines.some(vm => vm.id === this.form.virtualmachineid)) this.form.virtualmachineid = undefined
      } catch (error) {
        if (current()) this.lookupError = error.response?.data?.listvirtualmachinesresponse?.errortext || error.message || this.$t('message.volume.attach.vm.lookup.failed')
      } finally {
        if (current()) this.loading = false
      }
    },
    closeAction () {
      this.$emit('close-action')
    },
    handleSubmit (e) {
      if (e) e.preventDefault()
      if (!this.canSubmit) return
      const scope = this.lookupScope
      this.loading = true
      this.formRef.value.validate().then(async () => {
        if (scope !== this.lookupScope || this.$?.isUnmounted) return
        const values = toRaw(this.form)

        this.loading = true
        try {
          const response = await getAPI('listVirtualMachines', { ...this.vmQuery, id: values.virtualmachineid }, { preserveOnFailure: true })
          if (scope !== this.lookupScope || this.$?.isUnmounted) return
          const vm = response.listvirtualmachinesresponse?.virtualmachine?.find(vm => vm.id === values.virtualmachineid)
          const reason = volumeActionReason('attachVolume', this.resource, vm)
          if (reason) throw new Error(this.$t(reason))
        } catch (error) {
          if (scope === this.lookupScope) { this.$notifyError(error); this.loading = false }
          return
        }
        postAPI('attachVolume', {
          id: this.resource.id,
          virtualmachineid: values.virtualmachineid,
          deviceid: values.deviceid
        }).then(response => {
          this.$pollJob({
            jobId: response.attachvolumeresponse.jobid,
            title: this.$t('label.action.attach.disk'),
            description: this.resource.id,
            errorMessage: `${this.$t('message.attach.volume.failed')}: ${this.resource.name || this.resource.id}`,
            loadingMessage: `${this.$t('message.attach.volume.progress')}: ${this.resource.name || this.resource.id}`,
            catchMessage: this.$t('error.fetching.async.job.result')
          })
          this.closeAction()
        }).catch(error => {
          this.$notifyError(error)
        }).finally(() => {
          this.loading = false
        })
      }).catch((error) => {
        this.loading = false
        if (error.errorFields?.length) this.formRef.value.scrollToField(error.errorFields[0].name)
      })
    }
  }
}
</script>
<style lang="scss" scoped>
.form {
  width: 100%;
}
.volume-attach-content {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
}
.actions {
  display: flex;
  flex-shrink: 0;
  justify-content: flex-end;
  gap: 8px;
  padding-top: 16px;
}
</style>
<style lang="scss">
.volume-attach-vm-name {
  font-weight: 500;
}
.volume-attach-vm-identity {
  font-size: 12px;
  color: inherit;
  opacity: 0.7;
  white-space: normal;
  overflow-wrap: anywhere;
}
.ant-modal-wrap:not(.mold-dialog-standard-wrap):has(.volume-attach-dialog) {
  display: flex;
  align-items: center;
  justify-content: center;
  .ant-modal {
    top: 0 !important;
    width: 560px !important;
    max-width: calc(100vw - 32px);
    margin: 0;
    padding-bottom: 0;
  }
  .ant-modal-body {
    max-height: calc(100dvh - 130px);
    overflow: hidden;
    display: flex;
    flex-direction: column;
  }
  .volume-attach-dialog,
  .volume-attach-dialog > .ant-spin-nested-loading,
  .volume-attach-dialog .ant-spin-container {
    display: flex;
    flex-direction: column;
    min-height: 0;
    max-height: calc(100dvh - 178px);
  }
}
</style>
