<!-- Licensed to the Apache Software Foundation (ASF) under one or more
contributor license agreements. See NOTICE. Apache License, Version 2.0.
http://www.apache.org/licenses/LICENSE-2.0 -->
<template>
  <a-alert v-if="blockingKey" class="smb-publication-notice" type="error" show-icon :message="$t(blockingKey)" />
  <a-alert
    v-else-if="waiting.length"
    class="smb-publication-notice"
    type="info"
    show-icon
    :message="$t('label.storage.smb.waiting.acl')"
    :description="$t('message.storage.smb.waiting.acl') + ' ' + waiting.join(', ')" />
</template>
<script>
export default {
  name: 'SmbPublicationNotice',
  props: { report: { type: Object, default: null } },
  computed: {
    waiting () {
      return (this.report?.directorypreview?.smbpublication || [])
        .filter(row => row.state === 'UNPUBLISHED_WAITING_ACL').map(row => row.name)
    },
    blockingKey () {
      const code = this.report?.checks?.find(row => row.blocking)?.code
      return ({
        SMB_PUBLISHED_SECTION_MISSING: 'message.storage.smb.published.section.missing',
        SMB_PUBLICATION_STATE_MISMATCH: 'message.storage.smb.publication.mismatch',
        SMB_PUBLICATION_OBSERVATION_UNAVAILABLE: 'message.storage.smb.publication.unavailable'
      })[code] || ''
    }
  }
}
</script>
<style scoped>
.smb-publication-notice { margin-top: 16px; margin-bottom: 16px; color: var(--text-color); }
</style>
