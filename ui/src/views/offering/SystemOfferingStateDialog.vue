<!-- Licensed to the Apache Software Foundation (ASF) under one or more
contributor license agreements. See the NOTICE file distributed with this
work for additional information regarding copyright ownership. The ASF
licenses this file to you under the Apache License, Version 2.0 (the
"License"); you may not use this file except in compliance with the License.
You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
License for the specific language governing permissions and limitations
under the License. -->
<template>
  <MoldDialog
    :title="$t(currentAction.label)"
    :doc-help="currentAction.docHelp"
    :visible="!closed"
    :closable="!saving"
    :keyboard="!saving"
    :after-close="finishClose"
    size="md"
    focus-guard
    form-actions
    @cancel="close">
    <a-alert v-if="!attempted" :message="$t('message.system.offering.state.confirm')" type="info" show-icon />
    <a-spin :spinning="loading">
      <ul class="system-offering-targets" aria-live="polite">
        <li v-for="item in entries" :key="item.id">
          <strong>{{ item.name }}</strong>
          <a-tag v-if="item.defaultUse">{{ $t('label.system.offering.default') }}</a-tag>
          <a-tag :color="item.status === 'success' ? 'green' : item.status === 'failed' ? 'red' : undefined">
            {{ $t('label.account.action.' + item.status) }}
          </a-tag>
          <p v-if="item.detail">{{ item.detail }}</p>
        </li>
      </ul>
    </a-spin>
    <MoldDialogActions :busy="saving">
      <a-button :disabled="saving" @click="close">{{ $t(attempted ? 'label.close' : 'label.cancel') }}</a-button>
      <a-button v-if="!attempted" type="primary" :disabled="loading || !canSubmit" @click="submit">{{ $t('label.ok') }}</a-button>
    </MoldDialogActions>
  </MoldDialog>
</template>

<script>
import { getAPI, postAPI } from '@/api'
import MoldDialog from '@/components/view/MoldDialog'
import MoldDialogActions from '@/components/view/MoldDialogActions'

export default {
  name: 'SystemOfferingStateDialog',
  components: { MoldDialog, MoldDialogActions },
  inheritAttrs: false,
  props: {
    currentAction: { type: Object, required: true },
    afterClose: { type: Function, default: null }
  },
  emits: ['close-action', 'refresh-data'],
  data () {
    return {
      entries: this.currentAction.systemOfferingTargets.map(target => ({ ...target, status: 'working' })),
      loading: true,
      attempted: false,
      saving: false,
      closed: false
    }
  },
  computed: {
    requestedState () { return this.currentAction.systemOfferingState },
    canSubmit () { return this.entries.some(item => item.status === 'ready') },
    blocksNavigation () { return this.saving }
  },
  async mounted () {
    try {
      await Promise.all(this.entries.map(item => this.check(item)))
    } finally {
      this.loading = false
    }
  },
  methods: {
    close () { if (!this.saving) this.closed = true },
    finishClose () {
      this.$emit('close-action')
      this.afterClose?.()
    },
    async check (item) {
      try {
        const json = await getAPI('listServiceOfferings', { id: item.id, issystem: true, state: 'all', listall: true }, { preserveOnFailure: true })
        const offering = json.listserviceofferingsresponse?.serviceoffering?.find(row => row.id === item.id)
        if (!offering) throw new Error('missing offering')
        item.name = offering.name
        item.defaultUse = offering.defaultuse === true
        let detail = ''
        if (offering.state === this.requestedState) detail = this.$t('message.system.offering.state.already')
        else if (this.requestedState === 'Inactive') {
          if (item.defaultUse) detail = this.$t('message.system.offering.state.default')
          else if (!Number.isInteger(offering.systemofferingvmcount)) detail = this.$t('message.system.offering.state.unavailable')
          else if (offering.systemofferingvmcount > 0) detail = this.$t('message.system.offering.state.in.use', [offering.systemofferingvmcount])
        }
        item.status = detail ? 'skipped' : 'ready'
        item.detail = detail
      } catch {
        item.status = 'skipped'
        item.detail = this.$t('message.system.offering.state.unavailable')
      }
      return item.status === 'ready'
    },
    async submit () {
      if (this.loading || this.saving || this.attempted || !this.canSubmit || !this.currentAction.systemOfferingCurrent()) return
      this.attempted = true
      this.saving = true
      try {
        for (const item of this.entries.filter(item => item.status === 'ready')) {
          if (!await this.check(item)) continue
          if (!this.currentAction.systemOfferingCurrent()) break
          item.status = 'working'
          try {
            const response = await postAPI('updateServiceOffering', { id: item.id, state: this.requestedState }, { preserveOnFailure: true })
            const saved = response.updateserviceofferingresponse?.serviceoffering
            item.status = saved?.state === this.requestedState ? 'success' : 'unknown'
          } catch (error) {
            item.status = error?.response ? 'failed' : 'unknown'
            item.detail = Object.values(error?.response?.data || {}).find(value => value?.errortext)?.errortext || ''
          }
        }
        this.$emit('refresh-data', { irefresh: true })
      } finally {
        this.saving = false
      }
    }
  }
}
</script>

<style scoped>
.system-offering-targets { padding: 12px 0 0; list-style: none; }
.system-offering-targets li + li { margin-top: 8px; }
.system-offering-targets .ant-tag { margin-inline-start: 8px; }
</style>
