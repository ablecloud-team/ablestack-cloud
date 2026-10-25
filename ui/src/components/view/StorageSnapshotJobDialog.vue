<!--
Licensed to the Apache Software Foundation (ASF) under one or more
contributor license agreements. See the NOTICE file distributed with
this work for additional information regarding copyright ownership.
The ASF licenses this file to You under the Apache License, Version 2.0
(the "License"); you may not use this file except in compliance with
the License. You may obtain a copy of the License at
http://www.apache.org/licenses/LICENSE-2.0
Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
-->
<template>
  <MoldDialog focus-guard v-if="entry" size="md" class="snapshot-standard-dialog" :title="$t('label.snapshot.job.details')" @cancel="close">
    <section class="snapshot-job-details" data-testid="snapshot-job-details">
      <a-descriptions :column="1" bordered size="small">
        <a-descriptions-item :label="$t('label.action')">{{ $t(operationLabel) }}</a-descriptions-item>
        <a-descriptions-item :label="$t('label.snapshot.job.target')">{{ entry.target.name || entry.target.id }}</a-descriptions-item>
        <a-descriptions-item v-if="entry.target.destinationnames?.length" :label="$t('label.snapshot.transfer.destinations')">{{ entry.target.destinationnames.join(', ') }}</a-descriptions-item>
        <a-descriptions-item :label="$t('label.state')">{{ $t('label.snapshot.action.status.' + entry.status) }}</a-descriptions-item>
        <a-descriptions-item :label="$t('label.created')"><time :datetime="entry.created">{{ formattedTime }}</time></a-descriptions-item>
      </a-descriptions>
      <a-alert v-if="entry.error" :type="entry.status === 'unknown' ? 'warning' : 'error'" show-icon :message="errorText" />
      <a-alert v-if="entry.api === 'copySnapshot' && entry.status === 'failed'" type="info" show-icon :message="$t('message.snapshot.transfer.retry.failed')" />
      <a-alert v-if="entry.status === 'unknown'" type="warning" show-icon :message="$t('message.snapshot.job.query.only')" />
      <a-alert v-if="forceResult" type="warning" show-icon :message="$t(forceResult.physicalcleanupcomplete ? 'message.snapshot.job.force.complete' : 'message.snapshot.job.force.residual')" />
      <template v-if="download">
        <a-alert type="success" show-icon :message="$t('message.snapshot.download.ready')" :description="$t('message.snapshot.download.bearer')" />
        <a-descriptions :column="1" bordered size="small">
          <a-descriptions-item :label="$t('label.filename')">{{ download.filename }}</a-descriptions-item>
          <a-descriptions-item :label="$t('label.format')">{{ download.format }}</a-descriptions-item>
          <a-descriptions-item :label="$t('label.virtualsize')">{{ capacity(download.size) }}</a-descriptions-item>
          <a-descriptions-item v-if="download.physicalsize > 0" :label="$t('label.snapshot.download.file.size')">{{ capacity(download.physicalsize) }}</a-descriptions-item>
        </a-descriptions>
        <a :href="download.url" target="_blank" rel="noopener noreferrer" :download="download.filename" data-testid="snapshot-download-link">{{ $t('label.snapshot.download.file') }}</a>
        <p>{{ $t('message.snapshot.download.expiry') }}</p>
      </template>
      <a-space wrap>
        <a-button v-if="entry.jobid || (entry.requestid && entry.status === 'unknown')" :loading="checking" @click="check">{{ $t('label.snapshot.recovery.check') }}</a-button>
        <router-link v-if="resultPath" :to="resultPath" @click="close">{{ $t('label.snapshot.job.result') }}</router-link>
        <router-link :to="{path: '/event', query: {resourceid: entry.target.id}}" @click="close">{{ $t('label.snapshot.action.history') }}</router-link>
      </a-space>
      <a-collapse :active-key="advanced" @change="advanced = $event">
        <a-collapse-panel key="identity" :header="$t('label.snapshot.job.identification')">
          <a-descriptions :column="1" size="small">
            <a-descriptions-item :label="$t('label.id')">{{ entry.target.id }}</a-descriptions-item>
            <a-descriptions-item :label="$t('label.snapshot.action.request.id')">{{ entry.requestid }}</a-descriptions-item>
            <a-descriptions-item v-if="entry.jobid" :label="$t('label.snapshot.recovery.jobid')">{{ entry.jobid }}</a-descriptions-item>
          </a-descriptions>
          <div v-if="entry.status === 'unknown' && !entry.jobid" class="snapshot-job-recovery">
            <label for="snapshot-existing-job">{{ $t('label.snapshot.action.job.id') }}</label>
            <a-input id="snapshot-existing-job" v-model:value="jobId" />
            <a-checkbox v-model:checked="jobConfirmed">{{ $t('message.snapshot.action.job.acknowledge') }}</a-checkbox>
            <a-button :disabled="!jobConfirmed || !jobId" :loading="checking" @click="attach">{{ $t('label.snapshot.action.job.attach') }}</a-button>
            <a-checkbox v-model:checked="confirmed">{{ $t('message.snapshot.action.release.acknowledge') }}</a-checkbox>
            <a-button :disabled="!confirmed || checking" @click="release">{{ $t('label.snapshot.action.release') }}</a-button>
          </div>
        </a-collapse-panel>
      </a-collapse>
      <a-alert v-if="error" type="error" show-icon :message="error" />
    </section>
  </MoldDialog>
</template>
<script>
import MoldDialog from '@/components/view/MoldDialog.vue'
import { storageSnapshotOperations, operationScopeKey, querySnapshotOperation, releaseUnknownSnapshotSubmission, attachSnapshotOperationJob } from '@/utils/storageSnapshotOperations'
import { snapshotSecurityScope, snapshotOperationResponse } from '@/utils/storageSnapshotContext'
import { snapshotOperationLabels, snapshotResultPath } from '@/utils/storageSnapshotNotices'
import { snapshotDownloadResult } from '@/utils/snapshotDownload'
export default {
  name: 'StorageSnapshotJobDialog',
  components: { MoldDialog },
  props: { entryId: { type: String, required: true } },
  emits: ['close'],
  data () { return { capturedScope: snapshotSecurityScope(this.$store), checking: false, confirmed: false, jobId: '', jobConfirmed: false, error: '', advanced: [] } },
  computed: {
    scope () { return snapshotSecurityScope(this.$store) },
    entry () { const entry = storageSnapshotOperations[this.entryId]; return this.scope === this.capturedScope && entry?.scope === operationScopeKey(this.scope) ? entry : null },
    errorText () { return this.$t(this.entry?.error === 'message.job.result.unknown' ? 'message.snapshot.job.result.unknown' : this.entry?.error || '') },
    operationLabel () { return snapshotOperationLabels[this.entry?.api] || 'label.snapshot.action.operations' },
    formattedTime () { return new Date(this.entry.created).toLocaleString(this.$i18n.locale.replace('_', '-')) },
    resultPath () { return this.entry ? snapshotResultPath(this.entry) : '' },
    download () { return this.entry?.api === 'extractSnapshot' && this.entry.status === 'complete' ? snapshotDownloadResult(this.entry.result?.jobresult) : null },
    forceResult () { const body = snapshotOperationResponse(this.entry?.api, this.entry?.result?.jobresult) || this.entry?.result?.jobresult; return body?.force && body.dbcleanupcomplete === true ? body : null }
  },
  watch: { scope () { this.close() } },
  mounted () {
    // Hydration stores only status, so read the SAME job to restore result links after F5.
    if (this.entry?.status === 'complete' && this.entry.jobid && !this.entry.result?.jobresult) this.check()
  },
  methods: {
    capacity (bytes) { return `${(bytes / 1024 ** 3).toLocaleString(this.$i18n.locale.replace('_', '-'), { maximumFractionDigits: 4 })} GiB (${Number(bytes).toLocaleString()} B)` },
    close () { this.$emit('close') },
    current () { return this.scope === this.capturedScope && !!this.entry },
    async check () {
      if (this.checking || !this.current()) return
      this.checking = true
      try { await querySnapshotOperation(this.entry, () => this.current()) } finally { this.checking = false }
    },
    async attach () {
      if (this.checking || !this.current()) return
      this.checking = true; this.error = ''
      try { await attachSnapshotOperationJob(this.entry, this.jobId, this.jobConfirmed, () => this.current()) } catch (error) { this.error = this.$t(error.message) } finally { this.checking = false }
    },
    release () { if (this.current()) releaseUnknownSnapshotSubmission(this.entry, this.confirmed); this.confirmed = false }
  }
}
</script>
<style scoped>
.snapshot-job-details { display: flex; flex-direction: column; gap: 16px; overflow-wrap: anywhere; }
.snapshot-job-recovery { display: flex; flex-direction: column; align-items: flex-start; gap: 12px; }
.snapshot-job-recovery .ant-btn { height: auto; white-space: normal; }
</style>
