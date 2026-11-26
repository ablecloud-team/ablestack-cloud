// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements. See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership. The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License. You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied. See the License for the
// specific language governing permissions and limitations
// under the License.

<template>
  <a-tabs v-if="availableProviders.length" :key="resource.id" v-model:activeKey="provider">
    <a-tab-pane v-if="availableProviders.includes('netbackup')" key="netbackup" tab="NetBackup">
      <NetBackupHostConfiguration :resource="resource" />
    </a-tab-pane>
    <a-tab-pane v-if="availableProviders.includes('veeam')" key="veeam" tab="Veeam">
      <VeeamHostConfiguration :resource="resource" />
    </a-tab-pane>
  </a-tabs>
</template>

<script>
import NetBackupHostConfiguration from './backup/NetBackupHostConfiguration.vue'
import VeeamHostConfiguration from './backup/VeeamHostConfiguration.vue'
import { hostBackupIntegrationProviders } from '@/utils/hostBackupIntegration'

export default {
  name: 'HostBackupIntegrationTab',
  components: { NetBackupHostConfiguration, VeeamHostConfiguration },
  props: { resource: { type: Object, required: true } },
  data () {
    return { provider: 'netbackup' }
  },
  computed: {
    availableProviders () {
      return hostBackupIntegrationProviders(this.resource, this.$store?.getters?.apis).map(name => name.replace('ablestack-', ''))
    }
  },
  watch: {
    availableProviders: {
      immediate: true,
      handler (providers) {
        if (!providers.includes(this.provider)) this.provider = providers[0] || ''
      }
    }
  }
}
</script>
