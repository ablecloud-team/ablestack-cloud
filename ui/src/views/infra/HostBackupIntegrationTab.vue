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
  <a-tabs :key="resource.id" v-model:activeKey="provider">
    <a-tab-pane key="netbackup" tab="NetBackup">
      <NetBackupHostConfiguration :resource="resource" />
    </a-tab-pane>
    <a-tab-pane key="veeam" tab="Veeam">
      <VeeamHostConfiguration :resource="resource" />
    </a-tab-pane>
  </a-tabs>
</template>

<script>
import NetBackupHostConfiguration from './backup/NetBackupHostConfiguration.vue'
import VeeamHostConfiguration from './backup/VeeamHostConfiguration.vue'

export default {
  name: 'HostBackupIntegrationTab',
  components: { NetBackupHostConfiguration, VeeamHostConfiguration },
  props: { resource: { type: Object, required: true } },
  data () {
    return { provider: 'netbackup' }
  }
}
</script>
