<!-- Licensed to the Apache Software Foundation (ASF) under one or more
contributor license agreements. See NOTICE. Apache License, Version 2.0.
http://www.apache.org/licenses/LICENSE-2.0 -->
<template>
  <section class="smb-parent-inheritance">
    <div class="section-heading">{{ $t('label.storage.inheritance.new.objects') }}</div>
    <a-form-item :label="$t('label.storage.inheritance.owner')">
      <a-switch :checked="value" :disabled="guarded || !parentReady" @change="$emit('update:value', $event)" />
      <span class="switch-label">{{ $t(value ? 'label.storage.inheritance.parent.uid' : 'label.storage.inheritance.creator.uid') }}</span>
    </a-form-item>
    <p class="secondary">{{ $t('message.storage.inheritance.owner') }}</p>
    <a-alert v-if="!parentReady" type="info" show-icon :message="$t('message.storage.inheritance.register')" />
    <a-descriptions :column="1" bordered size="small">
      <a-descriptions-item :label="$t('label.storage.inheritance.parent')">{{ parentReady ? parent.owneruid + ':' + parent.ownergid + ' / ' + parent.mode : '-' }}</a-descriptions-item>
      <a-descriptions-item :label="$t('label.storage.inheritance.group')">
        {{ $t(group === null ? 'label.unknown' : group ? 'label.storage.inheritance.enabled' : 'label.storage.inheritance.disabled') }}
        <a-button type="link" :disabled="guarded" @click="$emit('manage')">{{ $t('label.storage.directory.manage') }}</a-button>
      </a-descriptions-item>
      <a-descriptions-item :label="$t('label.storage.inheritance.new.uid')">{{ $t(value ? 'label.storage.inheritance.parent.uid' : 'label.storage.inheritance.creator.uid') }}</a-descriptions-item>
      <a-descriptions-item :label="$t('label.storage.inheritance.new.gid')">{{ $t(group === null ? 'label.unknown' : group ? 'label.storage.inheritance.parent.gid' : 'label.storage.inheritance.creator.gid') }}</a-descriptions-item>
    </a-descriptions>
    <p class="secondary">{{ $t('message.storage.inheritance.children') }}</p>
    <a-alert v-if="failure" type="error" show-icon :message="failure" />
  </section>
</template>
<script>
import { setgidEnabled } from '@/utils/smbParentInheritance'
export default {
  name: 'SmbParentInheritanceFields',
  props: { value: Boolean, guarded: Boolean, policy: Object, report: Object },
  emits: ['update:value', 'manage'],
  computed: {
    parent () { return this.policy?.observation || {} },
    parentReady () { return this.policy?.observationstatus === 'APPLIED' && this.parent.status === 'OBSERVED' },
    group () { return this.parentReady ? setgidEnabled(this.parent.mode) : null },
    failure () {
      const messages = {
        SMB_PARENT_INHERITANCE_CAPABILITY_REQUIRED: 'capability',
        SMB_PARENT_INHERITANCE_PROTECTION_REQUIRED: 'protection',
        SMB_PARENT_IDENTITY_CONFLICT: 'conflict'
      }
      for (const check of this.report?.checks || []) {
        const key = messages[check.code] || messages[check.message]
        if (key) return this.$t('message.storage.inheritance.' + key)
      }
      return ''
    }
  }
}
</script>
<style scoped>
.smb-parent-inheritance { margin-top: 16px; color: var(--ui-text-primary); }
.section-heading { font-weight: 600; margin: 16px 0 12px; }
.switch-label { margin-left: 12px; }
.secondary { color: var(--ui-text-secondary); margin: 8px 0 16px; line-height: 1.7; }
:deep(.ant-alert) { margin: 16px 0; }
</style>
