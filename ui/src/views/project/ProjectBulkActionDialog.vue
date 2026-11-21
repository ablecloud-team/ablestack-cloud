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
  <MoldDialog
    :title="$t(currentAction.toolbarLabel)"
    :doc-help="currentAction.docHelp"
    :visible="!closed"
    :after-close="finishClose"
    :closable="!busy"
    :keyboard="!busy"
    size="md"
    form-actions
    focus-guard
    @cancel="close">
    <a-alert
      type="info"
      show-icon
      :message="$t('message.project.bulk.selection', [entries.length, applicableCount])" />
    <a-alert v-if="!allowed" type="warning" show-icon :message="$t('message.project.bulk.scope.changed')" class="project-bulk-section" />
    <a-alert
      v-if="attempted"
      :type="failed.length || unknownCount || skippedCount ? 'warning' : 'success'"
      show-icon
      :message="$t('message.project.bulk.result', [successCount, failed.length, unknownCount, skippedCount])"
      class="project-bulk-section" />
    <ul class="project-bulk-targets">
      <li v-for="entry in entries" :key="entry.target.id">
        <div>
          <strong>{{ entry.target.name }}</strong>
          <span v-if="entry.target.domain"> · {{ entry.target.domain }}</span>
          <a-tag>{{ $t('label.project.bulk.status.' + entry.status) }}</a-tag>
        </div>
        <div class="project-bulk-id">{{ entry.target.id }}</div>
        <p v-if="entry.reason || entry.detail">{{ entry.detail || $t(entry.reason) }}</p>
      </li>
    </ul>
    <template v-if="deleting">
      <a-checkbox v-if="supportsCleanup" v-model:checked="cleanup" :disabled="busy || attempted || !allowed">
        {{ $t('label.project.bulk.delete.cleanup') }}
      </a-checkbox>
      <a-alert
        :type="cleanup ? 'warning' : 'info'"
        show-icon
        :message="$t(cleanup ? 'message.project.bulk.delete.cleanup' : 'message.project.bulk.delete.keep')"
        class="project-bulk-section" />
    </template>
    <MoldDialogActions :busy="busy">
      <a-button :disabled="busy" @click="close">{{ $t(attempted ? 'label.close' : 'label.cancel') }}</a-button>
      <a-button v-if="checkable.length" :disabled="busy || !allowed" @click="checkJobs">{{ $t('label.job.check.result') }}</a-button>
      <a-button
        v-if="!attempted || failed.length"
        type="primary"
        :danger="deleting"
        :loading="busy"
        :disabled="busy || !allowed || (!attempted && !applicableCount)"
        @click="submit">
        {{ $t(attempted ? 'label.project.bulk.retry.failed' : currentAction.toolbarLabel) }}
      </a-button>
    </MoldDialogActions>
  </MoldDialog>
</template>

<script>
import { getAPI, postAPI } from '@/api'
import eventBus from '@/config/eventBus'
import MoldDialog from '@/components/view/MoldDialog'
import MoldDialogActions from '@/components/view/MoldDialogActions'
import { createJobTracker } from '@/utils/jobTracker'
import { currentProjectReason, projectBulkJobId, projectBulkJobSucceeded, projectBulkParams, projectBulkRequestError, supportsProjectCleanup } from '@/utils/projectBulk'

export default {
  name: 'ProjectBulkActionDialog',
  inheritAttrs: false,
  components: { MoldDialog, MoldDialogActions },
  props: {
    currentAction: { type: Object, required: true }
  },
  emits: ['close-action'],
  data () {
    return {
      entries: this.currentAction.projectBulkTargets.map(target => ({
        target, status: target.reason ? 'skipped' : 'ready', reason: target.reason, detail: '', jobId: ''
      })),
      cleanup: false,
      busy: false,
      attempted: false,
      closed: false,
      disposed: false
    }
  },
  computed: {
    getters () { return this.$store.getters },
    api () { return this.currentAction.api },
    deleting () { return this.api === 'deleteProject' },
    supportsCleanup () { return this.deleting && supportsProjectCleanup(this.getters) },
    allowed () { return !this.closed && !this.disposed && this.currentAction.projectBulkCurrent() },
    blocksNavigation () { return this.busy && this.allowed },
    applicableCount () { return this.entries.filter(entry => !entry.target.reason).length },
    failed () { return this.entries.filter(entry => entry.status === 'failed') },
    checkable () { return this.entries.filter(entry => entry.status === 'unknown' && entry.jobId) },
    successCount () { return this.entries.filter(entry => entry.status === 'success').length },
    unknownCount () { return this.entries.filter(entry => entry.status === 'unknown').length },
    skippedCount () { return this.entries.filter(entry => entry.status === 'skipped').length }
  },
  created () {
    this.tracker = createJobTracker({
      query: async jobid => {
        if (!this.allowed) throw new Error('Project scope changed')
        const response = await getAPI('queryAsyncJobResult', { jobid, ignoreproject: true }, {
          timeout: 15000, preserveOnFailure: true, backgroundJob: true
        })
        return response.queryasyncjobresultresponse
      },
      onState: () => {},
      maxDuration: 120000
    })
  },
  watch: {
    allowed (value) {
      if (!value) this.tracker.clear()
    }
  },
  beforeUnmount () {
    this.disposed = true
    this.tracker.clear()
  },
  methods: {
    close () {
      if (!this.busy) this.closed = true
    },
    finishClose () {
      if (this.closed) this.$emit('close-action', { refreshProjectList: this.attempted })
    },
    async submit () {
      if (!this.allowed || this.busy) return
      const entries = this.attempted ? this.failed : this.entries.filter(entry => entry.status === 'ready')
      if (!entries.length) return
      this.attempted = true
      const previousSuccess = this.successCount
      this.busy = true
      try {
        // The single-project commands are asynchronous. Keep targets and their results independent.
        for (const entry of entries) {
          if (!this.allowed) {
            entry.status = 'skipped'
            entry.reason = 'message.project.bulk.scope.changed'
            continue
          }
          await this.submitTarget(entry)
        }
      } finally {
        this.busy = false
        this.notifyProjectsUpdated(previousSuccess)
      }
    },
    async submitTarget (entry) {
      entry.status = 'working'
      entry.reason = ''
      entry.detail = ''
      entry.jobId = ''
      let latest
      try {
        const response = await getAPI('listProjects', {
          id: entry.target.id, listall: true, details: 'min', ignoreproject: true
        }, { timeout: 15000, preserveOnFailure: true })
        latest = response.listprojectsresponse?.project?.find(project => project.id === entry.target.id)
      } catch (error) {
        entry.status = 'failed'
        entry.detail = projectBulkRequestError(error).detail
        entry.reason = 'message.project.bulk.read.failed'
        return
      }
      if (!this.allowed) {
        entry.status = 'skipped'
        entry.reason = 'message.project.bulk.scope.changed'
        return
      }
      const reason = currentProjectReason(this.api, entry.target, latest, this.getters)
      if (reason) {
        entry.status = 'skipped'
        entry.reason = reason
        return
      }

      try {
        const response = await postAPI(this.api, projectBulkParams(this.api, entry.target, this.cleanup, this.supportsCleanup),
          { timeout: 30000, preserveOnFailure: true })
        entry.jobId = projectBulkJobId(this.api, response)
      } catch (error) {
        const { definitive, detail } = projectBulkRequestError(error)
        entry.status = definitive ? 'failed' : 'unknown'
        entry.detail = detail
        entry.reason = definitive ? 'message.request.failed' : 'message.project.bulk.result.unknown'
        return
      }
      if (!entry.jobId || !this.allowed) {
        entry.status = 'unknown'
        entry.reason = 'message.project.bulk.result.unknown'
        return
      }
      await this.track(entry)
    },
    async track (entry, retry = false) {
      entry.status = 'working'
      entry.reason = ''
      entry.detail = ''
      const result = await this.tracker.track(entry.jobId, {}, retry)
      if (projectBulkJobSucceeded(this.api, entry.target, result)) {
        entry.status = 'success'
      } else if (result.jobstatus === 2) {
        entry.status = 'failed'
        entry.detail = result.jobresult?.errortext || ''
        entry.reason = 'message.request.failed'
      } else {
        entry.status = 'unknown'
        entry.reason = 'message.project.bulk.result.unknown'
      }
    },
    async checkJobs () {
      if (!this.allowed || this.busy || !this.checkable.length) return
      const entries = this.checkable
      const previousSuccess = this.successCount
      this.busy = true
      try {
        for (const entry of entries) {
          if (!this.allowed) break
          await this.track(entry, true)
        }
      } finally {
        this.busy = false
        this.notifyProjectsUpdated(previousSuccess)
      }
    },
    notifyProjectsUpdated (previousSuccess) {
      if (this.allowed && this.successCount > previousSuccess) eventBus.emit('projects-updated', { action: this.api })
    }
  }
}
</script>

<style scoped>
.project-bulk-section { margin-top: 12px; }
.project-bulk-targets { padding-inline-start: 20px; margin: 16px 0; overflow-wrap: anywhere; }
.project-bulk-targets li { margin-bottom: 10px; }
.project-bulk-targets :deep(.ant-tag) { margin-inline-start: 8px; }
.project-bulk-id { font-family: monospace; }
.project-bulk-targets p { margin: 4px 0 0; }
</style>
