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
  <div class="form-layout" v-ctrl-enter="handleSubmit">
    <a-spin :spinning="loading">
      <a-alert
        v-if="!loading && maxSelections === 0"
        type="warning"
        showIcon
        :message="$t('label.iso.name') + ': max reached'"
        style="margin-bottom: 12px;" />
      <a-form
        :ref="formRef"
        :model="form"
        :rules="rules"
        layout="vertical"
        @finish="handleSubmit">
        <a-form-item
          :label="$t('label.iso.name') + ' (' + form.ids.length + ' / ' + maxSelections + ')'"
          ref="ids"
          name="ids">
          <a-select
            mode="multiple"
            :loading="loading"
            v-model:value="form.ids"
            v-focus="true"
            :disabled="maxSelections === 0"
            showSearch
            optionFilterProp="label"
            :filterOption="(input, option) => {
              return option.label.toLowerCase().indexOf(input.toLowerCase()) >= 0
            }">
            <a-select-option
              v-for="iso in isos"
              :key="iso.id"
              :label="iso.displaytext || iso.name"
              :disabled="form.ids.length >= maxSelections && !form.ids.includes(iso.id)">
              {{ iso.displaytext || iso.name }}
            </a-select-option>
          </a-select>
        </a-form-item>
        <a-form-item
          :label="$t('label.forced')"
          v-if="resource && resource.hypervisor === 'VMware'"
          ref="forced"
          name="forced">
          <a-switch v-model:checked="form.forced" v-focus="true" />
        </a-form-item>
      </a-form>
      <div :span="24" class="action-button">
        <a-button @click="closeAction">{{ $t('label.cancel') }}</a-button>
        <a-button :loading="loading" type="primary" @click="handleSubmit" ref="submit">{{ $t('label.ok') }}</a-button>
      </div>
    </a-spin>
  </div>
</template>
<script>
import { ref, reactive, toRaw } from 'vue'
import { getAPI, postAPI } from '@/api'
import _ from 'lodash'
import { detachIsoBatch } from '@/utils/detachIsoBatch'
import { attachedIsos } from '@/utils/vmIsoActions'

export default {
  name: 'AttachIso',
  props: {
    resource: {
      type: Object,
      required: true
    }
  },
  data () {
    return {
      loading: false,
      isos: [],
      maxSelections: 1
    }
  },
  created () {
    this.initForm()
    this.computeMaxSelections()
    this.fetchData()
  },
  watch: {
    'form.ids' (newVal) {
      if (newVal && newVal.length > this.maxSelections) {
        this.form.ids = newVal.slice(0, this.maxSelections)
        this.$message.warning(this.$t('label.iso.name') + ': max ' + this.maxSelections)
      }
    }
  },
  methods: {
    computeMaxSelections () {
      // Server pre-computes the effective cap (cluster-scoped vm.iso.max.count clamped to
      // the hypervisor's own limit) and exposes it on the VM as isomaxcount.
      const effectiveCap = this.resource.isomaxcount != null
        ? this.resource.isomaxcount
        : (this.resource.hypervisor === 'KVM' ? 2 : 1)
      const alreadyAttached = (this.resource.isos && this.resource.isos.length) ||
        (this.resource.isoid ? 1 : 0)
      this.maxSelections = Math.max(0, effectiveCap - alreadyAttached)
    },
    initForm () {
      this.formRef = ref()
      this.form = reactive({ ids: [] })
      this.rules = reactive({
        ids: [{
          required: true,
          type: 'array',
          min: 1,
          message: `${this.$t('label.required')}`
        }]
      })
    },
    fetchData () {
      const isoFiters = ['featured', 'community', 'selfexecutable']
      this.loading = true
      const promises = []
      isoFiters.forEach((filter) => {
        promises.push(this.fetchIsos(filter))
      })
      Promise.all(promises).then(() => {
        this.isos = _.uniqBy(this.isos, 'id').filter(iso => !attachedIsos(this.resource).some(row => row.id === iso.id))
      }).catch((error) => {
        console.log(error)
      }).finally(() => {
        this.loading = false
      })
    },
    fetchIsos (isoFilter) {
      const params = {
        listall: true,
        zoneid: this.resource.zoneid,
        isofilter: isoFilter,
        isready: true
      }
      return new Promise((resolve, reject) => {
        getAPI('listIsos', params).then((response) => {
          const isos = response.listisosresponse.iso || []
          this.isos.push(...isos)
          resolve(response)
        }).catch((error) => {
          reject(error)
        })
      })
    },
    closeAction () {
      this.$emit('close-action')
    },
    async handleSubmit (e) {
      if (e && typeof e.preventDefault === 'function') e.preventDefault()
      if (this.loading) return
      try { await this.formRef.value.validate() } catch (error) { if (error.errorFields?.length) this.formRef.value.scrollToField(error.errorFields[0].name); return }
      const values = toRaw(this.form); const ids = [...values.ids]
      const vmId = this.resource.id
      const scope = () => [this.$store.state.user.token, this.$store.getters.userInfo?.id, this.$store.getters.project?.id, this.resource.id].join('|')
      const original = scope()
      this.loading = true
      try {
        const results = await detachIsoBatch({
          ids,
          isCurrent: () => scope() === original,
          submit: id => postAPI('attachIso', { id, virtualmachineid: vmId, ...(values.forced ? { forced: true } : {}) }).then(r => r.attachisoresponse),
          poll: (jobId, id) => this.$pollJob({ jobId, title: this.$t('label.action.attach.iso'), description: this.isos.find(i => i.id === id)?.name || id, resourceId: vmId, action: { api: 'attachIso', isFetchData: true } }),
          onProgress: () => this.$emit('refresh-data')
        })
        if (scope() !== original) return
        const success = results.filter(i => i.jobstatus === 1).length
        const failed = results.filter(i => i.jobstatus === 2).length
        const unknown = results.filter(i => i.trackingStatus).length
        this.$notification.info({ message: this.$t('label.vmiso.progress'), description: this.$t('message.vmiso.summary', { success, failed, unknown, pending: ids.length - results.length, running: 0 }), duration: 0 })
        this.form.ids = results.filter(i => i.jobstatus === 2).map(i => i.id)
        this.computeMaxSelections()
        this.$emit('refresh-data')
        if (success === ids.length || unknown) this.closeAction()
      } finally { this.loading = false }
    }
  }
}
</script>
<style lang="scss" scoped>
.form-layout {
  width: 80vw;
  @media (min-width: 700px) {
    width: 600px;
  }
}

.form {
  margin: 10px 0;
}
</style>
