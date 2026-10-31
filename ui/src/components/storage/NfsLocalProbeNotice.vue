<!-- Licensed to the Apache Software Foundation (ASF) under one or more
contributor license agreements. See NOTICE. Apache License, Version 2.0.
http://www.apache.org/licenses/LICENSE-2.0 -->
<template>
  <a-alert
    v-if="pending.length"
    class="nfs-local-probe-notice"
    type="info"
    show-icon
    :message="$t('label.storage.nfs.external.check')"
    :description="$t('message.storage.nfs.external.check') + ' ' + pending.join(', ')" />
</template>
<script>
export default {
  name: 'NfsLocalProbeNotice',
  props: { shares: { type: Array, default: () => [] } },
  computed: {
    pending () {
      return this.shares.filter(row => row.localprobe?.externalclientrequired === true)
        .map(row => row.name + ' (' + (row.localprobe.ports || [])
          .filter(port => port.status === 'EXTERNAL_CLIENT_REQUIRED').map(port => port.port).join(', ') + ')')
    }
  }
}
</script>
<style scoped>
.nfs-local-probe-notice { margin-bottom: 16px; color: var(--text-color); }
</style>
