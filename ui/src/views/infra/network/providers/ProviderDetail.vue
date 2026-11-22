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
  <dl class="provider-attributes">
    <div v-for="item in details" :key="item">
      <dt>{{ $t('label.' + item) }}</dt>
      <dd>
        <template v-if="item === 'state'">
          <span v-if="nsp.supported === false">{{ $t('label.zone.provider.unsupported') }}</span>
          <status v-else-if="nsp.state" :text="nsp.state" display-text />
          <span v-else>{{ $t('label.not.added') }}</span>
        </template>
        <span v-else-if="item === 'servicelist'">{{ nsp.servicelist?.join(', ') || '—' }}</span>
        <span v-else>{{ nsp[item] || '—' }}</span>
      </dd>
    </div>
  </dl>
</template>

<script>
import Status from '@/components/widgets/Status'

export default {
  name: 'ProviderDetail',
  components: {
    Status
  },
  props: {
    details: {
      type: Array,
      default: () => []
    },
    nsp: {
      type: Object,
      default: () => {}
    }
  }
}
</script>

<style scoped>
.provider-attributes {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(min(100%, 14rem), 1fr));
  gap: 12px 20px;
}
.provider-attributes dt { font-weight: 600; }
.provider-attributes dd {
  margin: 4px 0 0;
  overflow-wrap: anywhere;
}
</style>
