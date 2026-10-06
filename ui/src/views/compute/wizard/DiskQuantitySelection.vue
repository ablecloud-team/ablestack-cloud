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
  <div class="disk-quantity">
    <a-row :gutter="16">
      <a-col :xs="24" :sm="12">
        <a-form-item :label="$t('label.disksize')">
          <a-input-number
            :value="offering.iscustomized ? size : offering.disksize"
            :disabled="!offering.iscustomized"
            :min="1"
            :precision="0"
            style="width: 160px"
            @change="value => $emit('update:size', value)" />
          <span class="disk-unit">GB</span>
          <a-tag v-if="!offering.iscustomized" class="disk-unit">{{ $t('label.vm.disk.fixed') }}</a-tag>
        </a-form-item>
      </a-col>
      <a-col :xs="24" :sm="12">
        <a-form-item :label="$t('label.vm.disk.count')">
          <a-input-number :value="count" :min="1" :precision="0" style="width: 160px" @change="value => $emit('update:count', value)" />
        </a-form-item>
      </a-col>
    </a-row>
    <p class="disk-total">{{ $t('label.vm.disk.total') }}: {{ totalSize }} GB</p>
    <p class="disk-help">{{ $t('message.vm.disk.after.creation') }}</p>
  </div>
</template>

<script>
export default {
  name: 'DiskQuantitySelection',
  props: {
    offering: { type: Object, required: true },
    size: { type: Number, default: undefined },
    count: { type: Number, default: 1 }
  },
  emits: ['update:size', 'update:count'],
  computed: {
    totalSize () { return (this.offering.iscustomized ? this.size || 0 : this.offering.disksize || 0) * (this.count || 0) }
  }
}
</script>

<style lang="less" scoped>
.disk-quantity { margin-top: 20px; }
.disk-unit { margin-left: 8px; }
.disk-total { margin: 0 0 8px; font-weight: 500; }
.disk-help { opacity: .75; margin-bottom: 16px; }
:deep(.ant-form-item) { margin-bottom: 12px; }
</style>
