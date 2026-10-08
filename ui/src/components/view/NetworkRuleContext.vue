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
  <a-descriptions class="mold-dialog-summary" :column="{ xs: 1, sm: 2 }" size="small" bordered>
    <a-descriptions-item :label="$t('label.ipaddress')">{{ resource.ipaddress || '—' }}</a-descriptions-item>
    <a-descriptions-item :label="$t('label.network')">{{ networkName || resource.networkname || resource.associatednetworkid || '—' }}</a-descriptions-item>
    <a-descriptions-item :label="$t('label.account')">{{ resource.account || '—' }}</a-descriptions-item>
    <a-descriptions-item :label="$t('label.domain')">{{ resource.domain || '—' }}</a-descriptions-item>
  </a-descriptions>
</template>
<script>
import { getAPI } from '@/api'
export default {
  name: 'NetworkRuleContext',
  props: { resource: { type: Object, required: true } },
  data () { return { networkName: '', request: 0 } },
  watch: { 'resource.associatednetworkid': { immediate: true, handler: 'fetchNetwork' } },
  beforeUnmount () { this.request++ },
  methods: {
    async fetchNetwork () {
      const token = ++this.request; this.networkName = ''
      if (!this.resource.associatednetworkid) return
      try {
        const response = await getAPI('listNetworks', { id: this.resource.associatednetworkid })
        if (token === this.request) this.networkName = response.listnetworksresponse?.network?.[0]?.name || ''
      } catch (error) { /* Keep the verified network id when its name is unavailable. */ }
    }
  }
}
</script>
