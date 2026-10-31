<!-- Licensed to the Apache Software Foundation (ASF) under one or more
contributor license agreements. See NOTICE. Apache License, Version 2.0.
http://www.apache.org/licenses/LICENSE-2.0 -->
<template>
  <section v-if="form.volumemode === 'CURRENT'" class="file-share-path">
    <a-form-item :label="$t('label.storage.path.custom')">
      <a-checkbox :checked="form.nestedpath" :disabled="guarded" @change="change('nestedpath', $event.target.checked)">{{ $t('label.storage.path.enable') }}</a-checkbox>
    </a-form-item>
    <template v-if="form.nestedpath">
      <a-form-item required :label="$t('label.storage.path.relative')" :validate-status="valid ? '' : 'error'" :help="valid ? $t('message.storage.path.relative') : $t('message.storage.path.invalid')">
        <a-input :value="path" :disabled="guarded" :maxlength="255" @update:value="change('relativepath', $event)" />
      </a-form-item>
      <a-descriptions bordered size="small" :column="1">
        <a-descriptions-item :label="$t('label.storage.path.physical')"><code>{{ root ? root + '/' + path : '-' }}</code></a-descriptions-item>
        <a-descriptions-item :label="$t('label.storage.directory.alias.path')"><code>{{ form.path || '-' }}</code></a-descriptions-item>
      </a-descriptions>
      <p>{{ $t('message.storage.path.preserve') }}</p>
    </template>
  </section>
</template>
<script>
import { relativePath, validRelativePath } from '@/utils/fileSharePaths'
export default {
  name: 'FileSharePathFields',
  props: { form: { type: Object, required: true }, root: String, guarded: Boolean },
  emits: ['change'],
  computed: { path () { return relativePath(this.form) }, valid () { return validRelativePath(this.path) } },
  methods: { change (key, value) { this.$emit('change', { key, value }) } }
}
</script>
<style scoped>
.file-share-path { margin-top: 16px; margin-bottom: 16px; color: var(--text-color); }
code { overflow-wrap: anywhere; white-space: normal; }
p { margin-top: 12px; color: var(--text-color-secondary); }
</style>
