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
  <span class="breadcrumb-controls">
    <a-tooltip v-if="docUrl" placement="bottom" :title="$t('label.open.documentation')">
      <a :href="docUrl" :aria-label="$t('label.open.documentation')" target="_blank" rel="noopener noreferrer">
        <QuestionCircleOutlined />
      </a>
    </a-tooltip>
    <router-link v-if="parentLocation" :to="parentLocation" class="kms-parent-link">
      {{ $t('message.kms.related.back') }}
    </router-link>
    <slot name="end" />
  </span>
</template>

<script>
export default {
  name: 'BreadcrumbControls',
  props: {
    docUrl: { type: String, default: '' },
    parentLocation: { type: Object, default: null }
  }
}
</script>
