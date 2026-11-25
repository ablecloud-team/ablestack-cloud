<!-- Licensed to the Apache Software Foundation (ASF) under one or more
contributor license agreements. See the NOTICE file distributed with this work
for additional information regarding copyright ownership. The ASF licenses this
file to you under the Apache License, Version 2.0 (the "License"); you may not use
this file except in compliance with the License. You may obtain a copy at
http://www.apache.org/licenses/LICENSE-2.0 . Unless required by applicable law or
agreed to in writing, software distributed under the License is distributed on
an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND. See the License
for the specific language governing permissions and limitations. -->
<template>
  <div>
    <a-descriptions :column="1" size="small">
      <a-descriptions-item :label="$t('label.provider')">{{ target.provider }}</a-descriptions-item>
      <a-descriptions-item :label="$t('label.scope')">{{ scope }}</a-descriptions-item>
      <a-descriptions-item :label="$t('label.uuid')">{{ target.id }}</a-descriptions-item>
    </a-descriptions>
    <a-alert type="warning" show-icon :message="$t('message.action.delete.oauth.provider')" />
    <a-alert v-if="message" :type="uncertain ? 'warning' : 'error'" role="alert" show-icon :message="$t(message)" />
    <mold-dialog-actions :busy="busy">
      <a-button id="oauth-delete-cancel" @click="$emit('close-action')">{{ $t('label.cancel') }}</a-button>
      <a-button type="primary" danger :disabled="!ready" :loading="busy" @click="remove">{{ $t('label.action.delete.oauth.provider') }}</a-button>
    </mold-dialog-actions>
  </div>
</template>

<script>
import { getAPI, postAPI } from '@/api'
import MoldDialogActions from '@/components/view/MoldDialogActions'
import { oauthConfigApi } from '@/utils/oauthConfiguration'

export default {
  name: 'DeleteOAuthProvider',
  components: { MoldDialogActions },
  props: { currentAction: { type: Object, required: true } },
  emits: ['close-action'],
  data () { return { busy: false, uncertain: false, message: '' } },
  computed: {
    target () { return this.currentAction.oauthTarget },
    scope () { return this.target.domainid ? this.target.domainpath || this.target.domainid : this.$t('label.oauth.scope.global') },
    ready () { return this.currentAction.oauthCurrent?.() && this.currentAction.api in this.$store.getters.apis && oauthConfigApi in this.$store.getters.apis }
  },
  methods: {
    async reconcile () {
      if (!this.currentAction.oauthCurrent?.()) return null
      try {
        const result = await getAPI(oauthConfigApi, { id: this.target.id }, { preserveOnFailure: true, timeout: 15000 })
        const body = result.listoauthproviderconfigurationsresponse
        if (!body || body.errorcode) throw new Error(oauthConfigApi)
        if (!this.currentAction.oauthCurrent?.()) return null
        const exists = (body.oauthprovider || []).some(row => row.id === this.target.id)
        this.uncertain = false
        if (exists) {
          this.message = 'message.oauth.delete.present'
        } else {
          this.$message.success(this.$t('message.oauth.deleted'))
          this.$emit('close-action', { oauthRemoved: this.target.id })
        }
        return exists
      } catch {
        if (this.currentAction.oauthCurrent?.()) {
          this.uncertain = true
          this.message = 'message.oauth.delete.unknown'
        }
        return null
      }
    },
    async remove () {
      if (this.busy || !this.ready) return
      this.busy = true
      this.message = ''
      try {
        if (this.uncertain && await this.reconcile() !== true) return
        if (!this.ready) return
        await postAPI(this.currentAction.api, { id: this.target.id }, { preserveOnFailure: true, timeout: 25000 })
        await this.reconcile()
      } catch (error) {
        if (!this.currentAction.oauthCurrent?.()) return
        const status = error.response?.status
        if (!status || status >= 500 || [404, 431].includes(status)) await this.reconcile()
        else this.message = [401, 403].includes(status) ? 'message.oauth.action.denied' : 'message.oauth.delete.failed'
      } finally {
        this.busy = false
      }
    }
  }
}
</script>
