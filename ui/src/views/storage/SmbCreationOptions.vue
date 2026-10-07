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
  <section class="smb-creation-options">
    <h4>{{ $t('label.storage.service.smb.creation.policy') }}</h4>
    <a-form-item :label="$t('label.smb.ownership.inheritance')">
      <a-select :value="form.ownershipinheritance" @change="patch({ ownershipinheritance: $event, inheritgroup: false })">
        <a-select-option value="AUTHENTICATED_USER">{{ $t('label.smb.ownership.authenticated') }}</a-select-option>
        <a-select-option value="INHERIT_PARENT_OWNER">{{ $t('label.smb.ownership.parent') }}</a-select-option>
      </a-select>
    </a-form-item>
    <a-form-item v-if="form.ownershipinheritance === 'INHERIT_PARENT_OWNER'" :label="$t('label.posix.directory.setgid')">
      <a-switch :checked="form.inheritgroup" @change="setParentGroup" />
    </a-form-item>
    <a-alert v-if="form.ownershipinheritance === 'INHERIT_PARENT_OWNER'" type="info" show-icon :message="$t('message.smb.ownership.parent.help')" />

    <a-alert type="info" show-icon :message="$t('message.storage.service.smb.creation.help')" />
    <a-form-item :label="$t('label.storage.service.smb.creation.preset')">
      <a-select :value="preset" @change="applyPreset">
        <a-select-option value="DEFAULT">0660 / 0770</a-select-option>
        <a-select-option value="EXACT_0775">0775 / 0775</a-select-option>
        <a-select-option value="CUSTOM">{{ $t('label.custom') }}</a-select-option>
      </a-select>
    </a-form-item>
    <a-form-item v-for="field in fields" :key="field" :label="$t('label.storage.service.smb.' + field)">
      <a-input :value="form[field]" :maxlength="4" @update:value="patch({ [field]: $event, confirmfileexecute: false })" />
    </a-form-item>
    <a-form-item :label="$t('label.storage.service.smb.inheritpermissions')">
      <a-switch :checked="form.inheritpermissions" @update:checked="patch({ inheritpermissions: $event })" />
    </a-form-item>
    <a-alert v-if="hasExecute" type="warning" show-icon :message="$t('message.storage.service.smb.execute.warning')" />
    <a-form-item v-if="hasExecute">
      <a-checkbox :checked="form.confirmfileexecute" @update:checked="patch({ confirmfileexecute: $event })">{{ $t('message.storage.service.smb.execute.confirm') }}</a-checkbox>
    </a-form-item>
  </section>
</template>
<script>
export default {
  name: 'SmbCreationOptions',
  emits: ['patch'],
  props: { form: { type: Object, required: true } },
  data: () => ({ fields: ['createmask', 'forcecreatemode', 'directorymask', 'forcedirectorymode'] }),
  computed: {
    hasExecute () { return /^[0-7]{3,4}$/.test(this.form.forcecreatemode || '') && (parseInt(this.form.forcecreatemode, 8) & 0o111) !== 0 },
    preset () {
      const values = this.fields.map(key => this.form[key]).join('/')
      return values === '0660/0000/0770/0000' ? 'DEFAULT' : (values === '0775/0775/0775/0775' ? 'EXACT_0775' : 'CUSTOM')
    }
  },
  methods: {
    setParentGroup (enabled) {
      const current = parseInt(this.form.directorymode || '0770', 8)
      if (Number.isNaN(current)) return
      const values = { inheritgroup: enabled }
      if (!this.form.posixpolicyid && enabled) values.directorymode = (current | 0o2000).toString(8).padStart(4, '0')
      this.patch(values)
    },
    patch (values) { this.$emit('patch', values) },
    applyPreset (preset) {
      if (preset === 'CUSTOM') return
      const values = preset === 'EXACT_0775' ? ['0775', '0775', '0775', '0775'] : ['0660', '0000', '0770', '0000']
      const changes = { inheritpermissions: false, confirmfileexecute: false }
      this.fields.forEach((key, index) => { changes[key] = values[index] })
      this.patch(changes)
    }
  }
}
</script>
<style scoped>
.smb-creation-options { margin-top: 20px; }
.smb-creation-options .ant-alert { margin-bottom: 16px; }
</style>
