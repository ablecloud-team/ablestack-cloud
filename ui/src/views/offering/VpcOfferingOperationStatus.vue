<!--
Licensed to the Apache Software Foundation (ASF) under one
or more contributor license agreements.  See the NOTICE file
distributed with this work for additional information
regarding copyright ownership.  The ASF licenses this file
to you under the Apache License, Version 2.0 (the
"License"); you may not use this file except in compliance
with the License.  You may obtain a copy of the License at

  http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing,
software distributed under the License is distributed on an
"AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
KIND, either express or implied.  See the License for the
specific language governing permissions and limitations
under the License.
-->
<template>
  <a-alert v-if="operation && operation.status !== 'idle'" class="vpc-offering-operation" :type="type" show-icon role="status" aria-live="polite">
    <template #message>{{ $t('message.vpc.offering.operation.' + operation.status) }}</template>
    <template #description>
      <div>{{ $t('label.name') }}: {{ operation.params.name }}</div>
      <div v-if="operation.params.sourceofferingid">{{ $t('label.vpc.offering.operation.source') }}: {{ operation.params.sourceofferingid }}</div>
      <div v-if="operation.jobId">{{ $t('label.job.id') }}: {{ operation.jobId }}</div>
      <div v-else-if="operation.requestId">{{ $t('label.vpc.offering.operation.request') }}: {{ operation.requestId }}</div>
      <div v-if="operation.error">{{ operation.code ? operation.code + ': ' : '' }}{{ operation.error.startsWith('message.vpc.offering.') ? $t(operation.error) : operation.error }}</div>
      <div v-if="operation.status === 'unknown'">{{ $t('message.vpc.offering.operation.no.resubmit') }}</div>
      <a-button v-if="operation.status === 'unknown' && operation.jobId && operation.failure !== 'storage'" @click="$emit('recheck')">{{ $t('label.vpc.offering.operation.recheck') }}</a-button>
      <a-button v-if="operation.status === 'succeeded'" @click="$emit('confirm')">{{ $t('label.vpc.offering.operation.confirm') }}</a-button>
    </template>
  </a-alert>
</template>
<script>
export default {
  name: 'VpcOfferingOperationStatus',
  props: { operation: { type: Object, default: null } },
  emits: ['recheck', 'confirm'],
  computed: {
    type () { return ['failed', 'rejected'].includes(this.operation.status) ? 'error' : this.operation.status === 'unknown' ? 'warning' : this.operation.status === 'succeeded' ? 'success' : 'info' }
  }
}
</script>
<style scoped>
.vpc-offering-operation { margin-bottom: 16px; overflow-wrap: anywhere; }
.vpc-offering-operation .ant-btn { margin-top: 8px; }
</style>
