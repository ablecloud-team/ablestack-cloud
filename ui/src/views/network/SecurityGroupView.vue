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
  <AutogenView v-if="availability === 'available'" />
  <a-spin v-else-if="availability === 'checking'" :tip="$t('label.loading')" role="status">
    <div class="security-group-discovery" />
  </a-spin>
  <a-alert
    v-else
    :type="availability === 'failed' ? 'error' : 'info'"
    :message="$t('message.security.group.availability.' + availability)"
    show-icon>
    <template v-if="availability === 'failed'" #description>
      <a-button size="small" @click="$store.dispatch('DiscoverSecurityGroups')">{{ $t('label.retry') }}</a-button>
    </template>
  </a-alert>
</template>

<script>
import AutogenView from '@/views/AutogenView.vue'

export default {
  name: 'SecurityGroupView',
  components: { AutogenView },
  computed: {
    availability () {
      return this.$store.getters.apis?.listSecurityGroups ? this.$store.getters.securityGroupAvailability : 'forbidden'
    }
  }
}
</script>

<style scoped>
.security-group-discovery { min-height: 8rem; }
</style>
