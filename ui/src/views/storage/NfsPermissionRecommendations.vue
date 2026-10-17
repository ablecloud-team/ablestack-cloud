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
  <section class="nfs-permission-recommendations">
    <a-alert type="info" show-icon :message="$t(helpKey)" />
    <a-descriptions v-if="!form.readonly" size="small" :column="1">
      <a-descriptions-item :label="$t('label.nfs.permission.recommended')">{{ preset.owneruid }}:{{ preset.ownergid }} · {{ preset.mode }}</a-descriptions-item>
    </a-descriptions>
    <a-button v-if="!form.readonly" :disabled="!!form.posixpolicyid" @click="recommend">{{ $t('label.nfs.permission.use.recommendation') }}</a-button>
    <a-alert v-if="form.posixpolicyid" type="info" show-icon :message="$t('message.posix.directory.inherited')" />
    <a-alert v-if="form.recursivepermission" type="warning" show-icon :message="$t('message.nfs.permission.recursive.separate')" />
  </section>
</template>
<script>
import { nfsPermissionPreset, recommendedNfsPermissionPatch } from '@/utils/storageNfsPermissions'
export default {
  name: 'NfsPermissionRecommendations',
  props: { form: { type: Object, required: true } },
  emits: ['patch'],
  computed: {
    preset () { return nfsPermissionPreset(this.form) },
    helpKey () {
      return {
        READ_ONLY_PRESERVE: 'message.nfs.permission.readonly.preserve',
        NO_ROOT_SQUASH: 'message.nfs.permission.no.root',
        ROOT_SQUASH: 'message.nfs.permission.root',
        ALL_SQUASH: 'message.nfs.permission.all'
      }[this.preset.policy]
    }
  },
  methods: {
    recommend () { this.$emit('patch', recommendedNfsPermissionPatch(this.form)) }
  }
}
</script>
<style scoped>
.nfs-permission-recommendations { margin-bottom: 16px; }
.nfs-permission-recommendations > * + * { margin-top: 8px; }
</style>
