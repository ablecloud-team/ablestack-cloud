<!-- Licensed to the Apache Software Foundation (ASF) under one or more
contributor license agreements. See NOTICE. Apache License, Version 2.0.
http://www.apache.org/licenses/LICENSE-2.0 -->
<template>
  <div class="file-identity">
    <a-form-item :label="$t('label.storage.file.identity.mode')">
      <a-radio-group :value="value.mode" :disabled="disabled" @change="change('mode', $event.target.value)">
        <a-radio value="AUTHENTICATED">{{ $t('label.storage.file.identity.authenticated') }}</a-radio>
        <a-radio value="FIXED">{{ $t('label.storage.file.identity.fixed') }}</a-radio>
      </a-radio-group>
      <p class="help">{{ $t('message.storage.file.identity.separation') }}</p>
    </a-form-item>
    <template v-if="value.mode==='FIXED'">
      <a-row :gutter="16">
        <a-col v-for="key in ['uid','gid']" :key="key" :xs="24" :sm="12">
          <a-form-item :label="$t('label.storage.file.identity.'+key)">
            <a-input-number
              :value="value[key]"
              :aria-label="$t('label.storage.file.identity.'+key)"
              :disabled="disabled"
              :min="1000"
              :max="65533"
              :precision="0"
              style="width:100%"
              @change="change(key,$event)" />
          </a-form-item>
        </a-col>
      </a-row>
      <a-alert class="spaced" type="info" show-icon :message="$t('message.storage.file.identity.protection')" />
      <a-alert v-if="conflict" class="spaced" type="error" show-icon :message="$t('message.storage.file.identity.'+conflict)" />
      <a-descriptions v-if="preview" bordered size="small" :column="2" class="spaced">
        <a-descriptions-item :label="$t('label.storage.file.identity.directory')">{{ preview.directory ? preview.directory.uid+':'+preview.directory.gid+' / '+preview.directory.mode : '-' }}</a-descriptions-item>
        <a-descriptions-item :label="$t('label.storage.file.identity.effective')">{{ preview.uid }}:{{ preview.gid }}</a-descriptions-item>
        <a-descriptions-item :label="$t('label.storage.file.identity.readiness')">{{ $t('label.storage.file.identity.'+(preview.publicationrequired?'published':'waiting')) }}</a-descriptions-item>
        <a-descriptions-item :label="$t('label.storage.file.identity.existing')">{{ $t('message.storage.file.identity.preserve') }}</a-descriptions-item>
      </a-descriptions>
    </template>
  </div>
</template>
<script>
import { validateIdentity } from '@/utils/smbFileIdentity'
export default {
  name: 'SmbFileIdentityFields',
  props: { value: { type: Object, required: true }, disabled: Boolean, inheritOwner: Boolean, guestOk: Boolean, preview: Object },
  emits: ['update:value'],
  computed: { conflict () { if (this.inheritOwner) return 'SMB_PARENT_IDENTITY_CONFLICT'; if (this.guestOk) return 'SMB_FIXED_IDENTITY_GUEST_FORBIDDEN'; try { validateIdentity(this.value); return '' } catch (e) { return e.message } } },
  methods: { change (key, value) { this.$emit('update:value', { ...this.value, [key]: value }) } }
}
</script>
<style scoped>
.spaced { margin: 16px 0; }
.help { color: var(--ui-text-secondary); font-size: 12px; line-height: 1.7; margin: 8px 0; }
</style>
