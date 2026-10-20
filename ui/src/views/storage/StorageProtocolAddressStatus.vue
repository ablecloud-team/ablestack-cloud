<!--
 Licensed to the Apache Software Foundation (ASF) under one
 or more contributor license agreements. See the NOTICE file
 distributed with this work for additional information.
-->
<template>
  <section class="storage-protocol-address-status">
    <a-alert type="info" show-icon :message="$t(mode === 'NEW' ? 'message.storage.service.activation.address.new' : 'message.storage.service.activation.address.reuse')" />
    <div class="storage-protocol-address-observation">
      <span>{{ $t('label.storage.service.activation.address.observation') }}: </span>
      <a-tag :color="color" role="status">{{ $t('label.storage.service.activation.address.' + observation.status) }}</a-tag>
    </div>
    <p class="storage-field-hint">{{ $t('message.storage.service.activation.address.' + observation.status) }}</p>
    <p v-if="observation.observedEpoch" class="storage-field-hint">{{ $t('label.storage.operation.observed.at') }}: {{ time(observation.observedEpoch) }}</p>
    <p class="storage-field-hint">{{ $t('message.storage.service.activation.port.independent') }}</p>
  </section>
</template>
<script>
import { protocolAddressObservation, protocolAddressObservationMaxAge } from '@/utils/storageProtocolActivation'
import { storageNetworkObservation } from '@/utils/storageNetworkReadiness'
export default {
  name: 'StorageProtocolAddressStatus',
  props: {
    readiness: { type: Object, default: null },
    currentBootId: { type: String, default: '' },
    ip: { type: String, default: '' },
    mode: { type: String, default: 'EXISTING' },
    stale: { type: Boolean, default: false },
    readFailed: { type: Boolean, default: false }
  },
  data: () => ({ clock: Date.now() / 1000 }),
  computed: {
    observation () { return protocolAddressObservation({ readiness: this.readiness, currentBootId: this.currentBootId, ip: this.ip, stale: this.stale, readFailed: this.readFailed, now: this.clock }) },
    color () { return { OBSERVED: 'green', NOT_OBSERVED: 'blue', ERROR: 'orange' }[this.observation.status] || 'default' }
  },
  watch: {
    readiness: { deep: true, handler () { this.observeClock() } },
    currentBootId () { this.observeClock() },
    stale () { this.observeClock() },
    readFailed () { this.observeClock() }
  },
  mounted () { this.observeClock() },
  beforeUnmount () { clearTimeout(this.expiryTimer) },
  methods: {
    time (epoch) { return new Date(epoch * 1000).toLocaleString() },
    observeClock () {
      clearTimeout(this.expiryTimer)
      this.clock = Date.now() / 1000
      const value = storageNetworkObservation(this.readiness)
      if (!value) return
      const remaining = (value.observedEpoch + protocolAddressObservationMaxAge - this.clock) * 1000
      if (remaining >= 0) this.expiryTimer = setTimeout(() => { this.clock = Date.now() / 1000 }, remaining + 1)
    }
  }
}
</script>
<style scoped>
.storage-protocol-address-observation { margin-top: 10px; }
.storage-protocol-address-status p { margin-top: 8px; margin-bottom: 0; }
</style>
