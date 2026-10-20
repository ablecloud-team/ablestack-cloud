<!--
 Licensed to the Apache Software Foundation (ASF) under one
 or more contributor license agreements. See the NOTICE file
 distributed with this work for additional information.
-->
<template>
  <a-collapse v-if="safeObservation" :bordered="false" class="storage-preparation-diagnostics" :destroyInactivePanel="true">
    <a-collapse-panel key="diagnostics" :header="$t('label.storage.service.preparation.diagnostics')">
      <p>{{ $t(firstFailure ? 'message.storage.service.preparation.diagnostics.first.failure' : 'message.storage.service.preparation.diagnostics.observation') }}</p>
      <a-descriptions bordered size="small" :column="1">
        <a-descriptions-item :label="$t('label.storage.operation.observed.at')">{{ safeObservation.observedatmillis ? new Date(safeObservation.observedatmillis).toLocaleString() : '—' }}</a-descriptions-item>
        <a-descriptions-item :label="$t('label.state')"><code>{{ safeObservation.status }}</code></a-descriptions-item>
        <a-descriptions-item :label="$t('label.storage.service.preparation.reason')"><code>{{ safeObservation.reason }}</code></a-descriptions-item>
        <a-descriptions-item v-for="(value, key) in safeObservation.diagnostics" :key="key" :label="$t('label.storage.service.preparation.diagnostic.' + key)"><code>{{ value === null ? '—' : String(value) }}</code></a-descriptions-item>
      </a-descriptions>
    </a-collapse-panel>
  </a-collapse>
</template>
<script>
import { storagePreparationDiagnosticSnapshot } from '@/utils/storagePreparation'
export default {
  name: 'StoragePreparationDiagnostics',
  props: {
    observation: { type: Object, default: null },
    firstFailure: { type: Boolean, default: false }
  },
  computed: {
    safeObservation () { return storagePreparationDiagnosticSnapshot(this.observation) }
  }
}
</script>
<style scoped>
.storage-preparation-diagnostics code { overflow-wrap: anywhere; }
</style>
