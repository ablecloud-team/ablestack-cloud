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
  <div class="sharedfs-preparation">
    <a-space wrap class="preparation-actions">
      <a-button type="primary" :loading="busy" @click="$emit('update')"><reload-outlined />{{ $t('label.update') }}</a-button>
      <a-button :disabled="busy || !resumable" @click="$emit('resume')">{{ $t('label.sharedfs.preparation.resume') }}</a-button>
    </a-space>
    <a-alert show-icon :type="state.status === 'RECOVERY_REQUIRED' ? 'warning' : 'info'" :message="$t('label.sharedfs.preparation.phase.' + (state.phase || 'NOT_OBSERVED'))">
      <template #description>
        <div>{{ $t('message.sharedfs.preparation.summary') }}</div>
        <div v-if="!state.observationAvailable">{{ $t('message.sharedfs.preparation.unavailable') }}</div>
        <div v-if="state.reason || state.errorCode || state.blockedReason">{{ state.reason || state.errorCode || state.blockedReason }}</div>
      </template>
    </a-alert>
    <a-descriptions :column="1" bordered size="small" style="margin-top: 16px">
      <a-descriptions-item :label="$t('label.name')">{{ resource.name || '-' }}</a-descriptions-item>
      <a-descriptions-item :label="$t('label.state')"><a-spin v-if="state.status === 'RUNNING'" size="small" /> {{ state.status || '-' }}</a-descriptions-item>
      <a-descriptions-item :label="$t('label.id')">{{ state.operationId || '-' }}</a-descriptions-item>
      <a-descriptions-item :label="$t('label.volume')">{{ state.volumeUuid || '-' }}</a-descriptions-item>
      <a-descriptions-item :label="$t('label.filesystem')">{{ state.observedFilesystem || state.filesystem || '-' }}</a-descriptions-item>
      <a-descriptions-item :label="$t('label.sharedfs.preparation.elapsed')">{{ elapsed }}</a-descriptions-item>
      <a-descriptions-item :label="$t('label.sharedfs.preparation.deadline')">{{ time(state.deadlineAt) }}</a-descriptions-item>
      <a-descriptions-item :label="$t('label.sharedfs.preparation.heartbeat')">{{ time(state.heartbeatAt) }}</a-descriptions-item>
      <a-descriptions-item :label="$t('label.sharedfs.preparation.observed')">{{ time(state.lastObservedAt) }}</a-descriptions-item>
    </a-descriptions>
    <slot />
  </div>
</template>
<script>
import { canResumePreparation } from '@/utils/sharedFsPreparation'
export default {
  name: 'SharedFSPreparationPanel',
  props: { state: { type: Object, default: () => ({}) }, resource: { type: Object, required: true }, busy: Boolean, allowResume: { type: Boolean, default: true } },
  emits: ['update', 'resume'],
  computed: {
    resumable () { return this.allowResume && canResumePreparation(this.state) },
    elapsed () {
      if (!this.state.startedAt || !this.state.lastObservedAt) return '-'
      return Math.max(0, Math.floor(this.state.lastObservedAt - this.state.startedAt)) + ' s'
    }
  },
  methods: { time (epoch) { return epoch ? new Date(epoch * 1000).toLocaleString() : '-' } }
}
</script>
<style scoped>
.preparation-actions { margin-bottom: 16px; }
.sharedfs-preparation { overflow-wrap: anywhere; }
</style>
