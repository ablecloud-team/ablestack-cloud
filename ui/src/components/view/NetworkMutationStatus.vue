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
  <a-alert v-if="operation" class="network-mutation-status" :type="operation.status === 'failed' ? 'error' : operation.status === 'unknown' ? 'warning' : 'info'" show-icon :message="$t(operation.status === 'success' ? 'label.network.lifecycle.operation.success' : 'label.network.lifecycle.status.' + operation.status)" role="status">
    <template #description>
      <p v-if="operation.error" role="alert">{{ operation.error }}</p>
      <p v-if="operation.code">{{ $t('label.error.code') }}: {{ operation.code }}</p>
      <p v-if="operation.jobId">{{ $t('label.network.restart.job') }}: {{ operation.jobId }}</p>
      <a-button v-if="operation.status === 'unknown' && operation.jobId" @click="$emit('recheck')">{{ $t('label.network.lifecycle.recheck') }}</a-button>
    </template>
  </a-alert>
</template>
<script>
export default { name: 'NetworkMutationStatus', props: { operation: { type: Object, default: null } }, emits: ['recheck'] }
</script>
<style scoped lang="less">
.network-mutation-status { margin: 12px 0; font-size: 14px; overflow-wrap: anywhere; }
.network-mutation-status p { margin: 6px 0; font-size: 14px; }
</style>
