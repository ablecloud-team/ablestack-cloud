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
  <section v-if="resource || showActions" class="webhook-test-delivery">
    <a-space v-if="showActions" direction="vertical">
      <a-button
        html-type="button"
        :loading="loading"
        :disabled="disabled || !testTarget"
        @click.stop="testWebhookDelivery">
        {{ $t('label.webhook.test.send') }}
      </a-button>
      <div v-if="testTarget" class="test-target">
        <strong>{{ $t('label.payloadurl') }}</strong>
        {{ testTarget }}
      </div>
    </a-space>
    <div class="test-result" role="status" aria-live="polite">
      <div v-if="loading">{{ $t('message.webhook.test.sending') }}</div>
      <a-alert
        v-else-if="requestFailed"
        type="error"
        show-icon
        :message="$t('message.request.failed')"
        :description="requestStatus ? 'HTTP ' + requestStatus : undefined" />
      <template v-else-if="delivery">
        <status :text="delivery.success ? 'success' : 'error'" displayText />
        <div v-if="responseDuration !== null">{{ $t('label.duration') }} {{ responseDuration }}</div>
        <div v-if="delivery.managementservername">{{ $t('label.managementservername') }} {{ delivery.managementservername }}</div>
        <a-alert
          :type="delivery.success ? 'success' : 'error'"
          :message="$t('label.response')"
          :description="delivery.response || $t('message.webhook.test.empty.response')"
          show-icon />
      </template>
    </div>
  </section>
</template>

<script>
import { postAPI } from '@/api'
import Status from '@/components/widgets/Status'

export default {
  name: 'TestWebhookDeliveryView',
  components: { Status },
  props: {
    resource: Object,
    payload: String,
    payloadUrl: String,
    sslVerification: {
      type: Boolean,
      default: undefined
    },
    secretKey: String,
    showActions: Boolean,
    disabled: Boolean
  },
  emits: ['change-loading'],
  data () {
    return {
      delivery: null,
      loading: false,
      requestFailed: false,
      requestStatus: null,
      inputRevision: 0
    }
  },
  computed: {
    testTarget () {
      return this.resource?.payloadurl || this.payloadUrl?.trim()
    },
    responseDuration () {
      const { startdate, enddate } = this.delivery || {}
      if (!startdate || !enddate) return null
      const duration = Date.parse(enddate) - Date.parse(startdate)
      return Number.isFinite(duration) ? Math.max(duration, 0) / 1000 : null
    }
  },
  watch: {
    'resource.id': 'resetResult',
    testTarget: 'resetResult',
    payload: 'resetResult',
    sslVerification: 'resetResult',
    secretKey: 'resetResult'
  },
  beforeUnmount () {
    this.inputRevision++
  },
  methods: {
    resetResult () {
      this.inputRevision++
      this.delivery = null
      this.requestFailed = false
      this.requestStatus = null
    },
    async testWebhookDelivery () {
      if (this.disabled || this.loading || (!this.resource?.id && !this.testTarget)) return
      const revision = this.inputRevision
      this.delivery = null
      this.requestFailed = false
      this.requestStatus = null
      this.loading = true
      this.$emit('change-loading', true)
      const params = {}
      if (this.resource?.id) {
        params.webhookid = this.resource.id
        if (this.payload) params.payload = this.payload
      } else {
        params.payloadurl = this.testTarget
        if (this.sslVerification !== undefined) params.sslverification = this.sslVerification
        if (this.secretKey) params.secretkey = this.secretKey
      }
      try {
        const response = await postAPI('executeWebhookDelivery', params)
        if (revision === this.inputRevision) {
          this.delivery = response?.executewebhookdeliveryresponse?.webhookdelivery || null
          this.requestFailed = !this.delivery
        }
      } catch (error) {
        if (revision === this.inputRevision) {
          this.requestFailed = true
          this.requestStatus = error.response?.status || null
        }
      } finally {
        this.loading = false
        this.$emit('change-loading', false)
      }
    }
  }
}
</script>

<style scoped>
.test-target,
.test-result {
  overflow-wrap: anywhere;
}

.test-result:not(:empty) {
  margin-top: 8px;
}
</style>
