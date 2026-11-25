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
  <Status v-if="status" :text="String(value ?? '')" :display-label="String(display)" :title="original" display-text />
  <span v-else :title="original">
    {{ display }}
    <code v-if="showCode && original" class="runtime-catalog-code">{{ original }}</code>
  </span>
</template>

<script>
import Status from '@/components/widgets/Status'
import { runtimeCatalogTranslationKey } from '@/utils/runtimeCatalog'

export default {
  name: 'RuntimeCatalogValue',
  components: { Status },
  props: {
    group: { type: String, default: '' },
    value: { type: [String, Number, Boolean], default: undefined },
    status: { type: Boolean, default: false },
    showCode: { type: Boolean, default: false }
  },
  computed: {
    display () {
      if (this.value == null || this.value === '') return '—'
      const key = runtimeCatalogTranslationKey(this.$i18n, this.group, this.value)
      return key ? this.$t(key) : this.value
    },
    original () {
      return this.value != null && this.display !== this.value ? String(this.value) : undefined
    }
  }
}
</script>

<style scoped>
.runtime-catalog-code { margin-inline-start: 6px; overflow-wrap: anywhere; }
</style>
