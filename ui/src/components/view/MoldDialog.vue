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
  <a-modal
    v-bind="$attrs"
    class="mold-dialog"
    :class="{ 'mold-dialog-standard': size }"
    :wrap-class-name="size ? 'mold-dialog-standard-wrap' : undefined"
    :mask-style="size ? { ...($attrs.maskStyle || {}), width: '100vw' } : $attrs.maskStyle"
    centered
    :mask-closable="false"
    :wrap-props="{ 'aria-modal': true }"
    :width="dialogWidth"
    :visible="visible"
    :closable="$attrs.closable !== false && !formBusy"
    :keyboard="$attrs.keyboard !== false && !formBusy"
    @cancel="!formBusy && $emit('cancel')">
    <template #title>
      <slot name="title">
        {{ title }}
        <a v-if="docHelp" class="mold-dialog-help" :href="documentationUrl($config.docBase, docHelp, $i18n.locale)" target="_blank" rel="noopener noreferrer" :aria-label="$t('label.open.documentation')">
          <question-circle-outlined />
        </a>
      </slot>
    </template>
    <div v-if="focusGuard" ref="focusContent" tabindex="-1" class="mold-dialog-focus-content"><slot /></div>
    <slot v-else />
    <template #footer><slot name="footer"><div v-if="formActions" ref="footerTarget" class="mold-dialog-form-actions" /><a-button v-else @click="$emit('cancel')">{{ $t('label.close') }}</a-button></slot></template>
  </a-modal>
</template>
<script>
import { computed, provide, reactive, shallowRef, watch, onBeforeUnmount } from 'vue'
import { documentationUrl } from '@/utils/cniFileImport'
export default {
  name: 'MoldDialog',
  inheritAttrs: false,
  props: { visible: { type: Boolean, default: true }, title: { type: String, default: '' }, docHelp: { type: String, default: '' }, width: { type: [Number, String], default: 760 }, size: { type: String, default: '', validator: value => ['', 'sm', 'md', 'lg', 'xl'].includes(value) }, formActions: { type: Boolean, default: false }, focusGuard: { type: Boolean, default: false }, initialFocus: { type: String, default: '' } },
  setup (props) {
    const footerTarget = shallowRef(null)
    const focusContent = shallowRef(null)
    let observer
    watch([focusContent, () => props.visible], ([node, visible]) => {
      if (observer) observer.disconnect()
      const owner = node?.closest('.ant-modal')
      if (!owner || !visible) return
      let focused = false
      const focusInitialControl = () => {
        if (focused || !props.initialFocus) return
        const control = owner.querySelector(props.initialFocus)
        if (!control?.getClientRects().length || control.disabled) return
        control.focus({ preventScroll: true })
        focused = document.activeElement === control
      }
      observer = new MutationObserver(() => {
        focusInitialControl()
        // Disabled or removed controls can drop focus to BODY while the modal is open.
        // Preserve focus in this opt-in dialog; never steal focus from a live control or portal.
        if (props.focusGuard && document.activeElement === document.body && owner.isConnected && owner.getClientRects().length && getComputedStyle(owner).display !== 'none') node.focus({ preventScroll: true })
      })
      observer.observe(owner, { childList: true, subtree: true, attributes: true, attributeFilter: ['disabled', 'aria-disabled', 'class', 'style'] })
      focusInitialControl()
    }, { flush: 'post' })
    onBeforeUnmount(() => { if (observer) observer.disconnect() })
    const busyOwners = reactive(new Set())
    provide('moldDialogFooterTarget', footerTarget)
    provide('moldDialogSetBusy', (owner, busy) => busy ? busyOwners.add(owner) : busyOwners.delete(owner))
    return { footerTarget, focusContent, formBusy: computed(() => busyOwners.size > 0) }
  },
  emits: ['cancel'],
  methods: { documentationUrl },
  computed: { dialogWidth () { return { sm: 480, md: 720, lg: 960, xl: 1120 }[this.size] || this.width } }
}
</script>

<style scoped>
.mold-dialog-help { margin-inline-start: 8px; color: var(--ui-link); }
.mold-dialog-help:focus-visible { outline: 2px solid var(--ui-focus); outline-offset: 2px; }
.mold-dialog-focus-content { min-width: 0; outline: none; }
</style>
