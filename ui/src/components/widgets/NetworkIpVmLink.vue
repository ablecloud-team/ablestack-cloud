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
  <span class="network-ip-vm-reference">
    <template v-if="reference.path">
      <desktop-outlined aria-hidden="true" />
      <router-link :to="{ path: reference.path }" :title="reference.label">{{ reference.label }}</router-link>
    </template>
    <span v-else>{{ reference.label }}</span>
  </span>
</template>

<script>
import { DesktopOutlined } from '@ant-design/icons-vue'
import { networkIpVmReference } from '@/utils/networkIpVmReference'

export default {
  name: 'NetworkIpVmLink',
  components: { DesktopOutlined },
  props: { record: { type: Object, required: true } },
  computed: {
    reference () { return networkIpVmReference(this.record) }
  }
}
</script>

<style scoped>
.network-ip-vm-reference { overflow-wrap: anywhere; }
.network-ip-vm-reference .anticon { margin-right: 4px; }
</style>
