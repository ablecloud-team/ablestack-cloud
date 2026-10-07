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
  <section class="posix-policy-inheritance">
    <a-form-item :label="$t('label.posix.directory.policy')">
      <a-select :value="form.posixpolicyid" :loading="loading" allow-clear @change="select">
        <a-select-option v-for="policy in policies" :key="policy.id" :value="policy.id">{{ policy.relativepath }} · {{ owner(policy) }} · {{ mode(policy) }}</a-select-option>
      </a-select>
    </a-form-item>
    <a-alert v-if="form.posixpolicyid" type="info" show-icon :message="$t('message.posix.directory.inherited')" />
    <a-alert v-if="readError" type="warning" show-icon :message="$t('message.posix.directory.read.failed')" />
  </section>
</template>
<script>
import { getAPI } from '@/api'
const parse = value => { try { return typeof value === 'string' ? JSON.parse(value) : (value || {}) } catch (error) { return {} } }
export default {
  name: 'PosixPolicyInheritance',
  props: { instanceId: { type: String, required: true }, form: { type: Object, required: true }, protocol: { type: String, required: true } },
  emits: ['patch'],
  data: () => ({ policies: [], loading: false, readError: false, generation: 0 }),
  watch: { instanceId () { this.generation++; this.policies = []; this.refresh() } },
  mounted () { this.refresh() },
  beforeUnmount () { this.generation++ },
  methods: {
    owner (policy) { const effective = parse(policy.effective); return `${effective.effectiveUid ?? '-'}:${effective.effectiveGid ?? '-'}` },
    mode (policy) { return parse(policy.effective).effectiveMode || parse(policy.config).directoryMode || '-' },
    async refresh () {
      const token = ++this.generation; const instance = this.instanceId; this.loading = true
      try {
        const result = await getAPI('listStoragePosixDirectoryPolicies', { instanceid: instance }, { preserveOnFailure: true, timeout: 15000 }); if (token !== this.generation || instance !== this.instanceId) return
        this.policies = (result.liststorageposixdirectorypoliciesresponse?.storageposixdirectorypolicy || []).filter(policy => policy.state === 'Ready'); this.readError = false
      } catch (error) { if (token === this.generation) this.readError = true } finally { if (token === this.generation) this.loading = false }
    },
    select (id) {
      const policy = this.policies.find(policy => policy.id === id)
      if (!policy) { this.$emit('patch', { posixpolicyid: undefined }); return }
      const effective = parse(policy.effective)
      const patch = { posixpolicyid: id, volumeid: policy.volumeid, relativepath: policy.relativepath, volumemode: 'CURRENT' }
      if (this.protocol === 'NFS') Object.assign(patch, { owneruid: effective.effectiveUid, ownergid: effective.effectiveGid, mode: effective.effectiveMode, recursivepermission: false })
      else Object.assign(patch, { directorymode: effective.effectiveMode, crossprotocol: true })
      this.$emit('patch', patch)
    }
  }
}
</script>
<style scoped>
.posix-policy-inheritance { margin-bottom: 16px; }
</style>
