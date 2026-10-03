<!-- Licensed to the Apache Software Foundation (ASF) under one or more
contributor license agreements. See the NOTICE file distributed with this work
for additional information regarding copyright ownership. The ASF licenses this
file to you under the Apache License, Version 2.0 (the "License"); you may not use
this file except in compliance with the License. You may obtain a copy at
http://www.apache.org/licenses/LICENSE-2.0 . Unless required by applicable law or
agreed to in writing, software distributed under the License is distributed on
an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND. See the License
for the specific language governing permissions and limitations. -->
<template>
  <a-modal v-bind="$attrs" class="mold-dialog" centered :mask-closable="false" :width="width" :visible="visible" @cancel="$emit('cancel')">
    <template #title><slot name="title">{{ title }}</slot></template>
    <slot />
    <template #footer><slot name="footer"><a-button @click="$emit('cancel')">{{ $t('label.close') }}</a-button></slot></template>
  </a-modal>
</template>
<script>
export default {
  name: 'MoldDialog',
  inheritAttrs: false,
  props: { visible: { type: Boolean, default: true }, title: { type: String, default: '' }, width: { type: [Number, String], default: 760 } },
  emits: ['cancel']
}
</script>
