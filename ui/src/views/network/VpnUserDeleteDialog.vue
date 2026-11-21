<script>
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

import { getAPI, postAPI } from '@/api'
import MoldDialog from '@/components/view/MoldDialog'
import VpnUserValue from '@/components/widgets/VpnUserValue'
import { createJobTracker } from '@/utils/jobTracker'
import { networkError } from '@/utils/networkLifecycle'
import { vpnUserDeleteParams, vpnUserDeleteTargetExists } from '@/utils/vpnUserDeletion'

const statusLabels = { running: 'label.in.progress', success: 'label.success', failed: 'label.failed', unknown: 'message.vpn.user.delete.unknown' }
const statusColors = { running: 'processing', success: 'success', failed: 'error', unknown: 'warning' }
const jobOptions = { preserveOnFailure: true, timeout: 15000 }

export default {
  name: 'VpnUserDeleteDialog',
  components: { MoldDialog, VpnUserValue },
  props: {
    targets: { type: Array, required: true },
    current: { type: Function, required: true },
    afterClose: { type: Function, required: true }
  },
  emits: ['close', 'completed'],
  data () {
    return {
      rows: this.targets.map(target => ({ ...target, status: '', error: '', jobId: '' })),
      running: false,
      started: false,
      statusLabels,
      statusColors
    }
  },
  computed: {
    canRecheck () { return this.rows.some(row => row.status === 'unknown' && row.jobId) }
  },
  created () {
    this.tracker = createJobTracker({
      query: async id => {
        const response = await getAPI('queryAsyncJobResult', { jobid: id, ignoreproject: true }, jobOptions)
        const job = response?.queryasyncjobresultresponse
        if (job?.jobid !== id) throw new Error('Unexpected VPN user delete job')
        return job
      },
      onState: () => {},
      maxDuration: 120000
    })
  },
  beforeUnmount () { this.tracker.clear() },
  methods: {
    close () {
      if (!this.running) this.$emit('close', this.started)
    },
    finish () {
      // Keep failures visible until dismissal. A detail refresh may navigate
      // away if another operator has already removed the target.
      if (!this.rows.every(row => row.status === 'success')) return
      this.$message.success(this.$t('label.success'))
      this.$emit('completed')
    },
    async track (row, retry = false) {
      const job = await this.tracker.track(row.jobId, {}, retry)
      if (!this.current()) return
      row.status = job.jobstatus === 1 ? 'success' : job.jobstatus === 2 ? 'failed' : 'unknown'
      row.error = job.jobstatus === 2 ? job.jobresult?.errortext || this.$t('message.request.failed') : ''
    },
    async submit () {
      if (this.started || !this.current()) return
      this.started = true
      this.running = true
      for (const row of this.rows) {
        if (!this.current()) return
        row.status = 'running'
        try {
          const exists = await vpnUserDeleteTargetExists(getAPI, row)
          if (!this.current()) return
          if (!exists) {
            row.status = 'failed'
            row.error = this.$t('message.vpn.user.delete.target.changed')
            continue
          }
        } catch (error) {
          if (!this.current()) return
          row.status = 'failed'
          row.error = networkError(error).text || this.$t('message.request.failed')
          continue
        }
        try {
          const response = await postAPI('removeVpnUser', vpnUserDeleteParams(row), jobOptions)
          if (!this.current()) return
          row.jobId = response?.removevpnuserresponse?.jobid
          if (!row.jobId) row.status = 'unknown'
          else await this.track(row)
        } catch (error) {
          if (!this.current()) return
          const detail = networkError(error)
          row.status = detail.definitive ? 'failed' : 'unknown'
          row.error = detail.definitive ? detail.text || this.$t('message.request.failed') : ''
        }
      }
      if (!this.current()) return
      this.running = false
      this.finish()
    },
    async recheck () {
      if (this.running || !this.canRecheck || !this.current()) return
      this.running = true
      await Promise.all(this.rows.filter(row => row.status === 'unknown' && row.jobId).map(row => this.track(row, true)))
      if (!this.current()) return
      this.running = false
      this.finish()
    }
  }
}
</script>

<template>
  <MoldDialog
    :visible="true"
    :title="$t('label.delete.vpn.user')"
    :after-close="afterClose"
    :focus-trigger-after-close="false"
    :closable="!running"
    :keyboard="!running"
    size="md"
    focus-guard
    @cancel="close">
    <p>{{ $t('message.action.delete.vpn.user') }}</p>
    <p v-if="rows.length > 1" class="vpn-user-delete-count">{{ $t('message.vpn.user.delete.count', [rows.length]) }}</p>
    <ul class="vpn-user-delete-targets" aria-live="polite">
      <li v-for="row in rows" :key="row.id">
        <strong class="vpn-user-delete-name">{{ row.username }}</strong>
        <VpnUserValue field="owners" :resource="row" plain />
        <div class="vpn-user-delete-status">
          <span>{{ $t('label.status') }} ·</span>
          <VpnUserValue field="state" :resource="row" />
        </div>
        <a-tag v-if="row.status" :color="statusColors[row.status]">{{ $t(statusLabels[row.status]) }}</a-tag>
        <div v-if="row.error">{{ row.error }}</div>
        <code v-if="row.status === 'unknown' && row.jobId">{{ row.jobId }}</code>
      </li>
    </ul>
    <template #footer>
      <a-button v-focus="true" :disabled="running" @click="close">{{ $t(started ? 'label.close' : 'label.cancel') }}</a-button>
      <a-button v-if="!started" type="primary" danger @click="submit">{{ $t('label.delete.vpn.user') }}</a-button>
      <a-button v-else-if="canRecheck" type="primary" :loading="running" @click="recheck">{{ $t('message.vpn.user.delete.recheck') }}</a-button>
    </template>
  </MoldDialog>
</template>

<style scoped>
.vpn-user-delete-targets {
  display: grid;
  gap: 12px;
  padding-inline-start: 20px;
  overflow-wrap: anywhere;
}

.vpn-user-delete-name {
  display: block;
}

.vpn-user-delete-status {
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  gap: 4px;
}
</style>
