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
  <Teleport :to="footerTarget || 'body'" :disabled="!footerTarget">
    <fieldset v-bind="$attrs" :disabled="busy" :class="{ 'action-button': !footerTarget, 'mold-dialog-actions': !!footerTarget }"><slot /></fieldset>
  </Teleport>
</template>
<script>
import { inject, onBeforeUnmount, ref, watch } from 'vue'
export default {
  name: 'MoldDialogActions',
  inheritAttrs: false,
  props: { busy: { type: Boolean, default: false } },
  setup (props) {
    const footerTarget = inject('moldDialogFooterTarget', ref(null))
    const setBusy = inject('moldDialogSetBusy', () => {})
    const owner = Symbol('dialog-actions')
    watch(() => props.busy, busy => setBusy(owner, busy), { immediate: true, flush: 'sync' })
    onBeforeUnmount(() => setBusy(owner, false))
    return { footerTarget }
  }
}
</script>
<style scoped>
fieldset { min-width: 0; border: 0; padding: 0; }
</style>
