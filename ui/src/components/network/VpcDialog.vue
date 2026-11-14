<!--
Licensed to the Apache Software Foundation (ASF) under one
or more contributor license agreements. See the NOTICE file
distributed with this work for additional information
regarding copyright ownership. The ASF licenses this file
to you under the Apache License, Version 2.0 (the
"License"); you may not use this file except in compliance
with the License. You may obtain a copy of the License at

  http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing,
software distributed under the License is distributed on an
"AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
KIND, either express or implied. See the License for the
specific language governing permissions and limitations
under the License.
-->
<template>
  <MoldDialog v-bind="$attrs" :visible="visible" :after-close="afterClosed">
    <template v-for="name in Object.keys($slots).filter(name => name !== 'default')" #[name]="slotProps"><slot :name="name" v-bind="slotProps || {}" /></template>
    <div class="vpc-dialog-body" @keydown.esc.capture="rememberPopup" @keydown.esc="popupEscape"><slot /></div>
  </MoldDialog>
</template>
<script>
import MoldDialog from '@/components/view/MoldDialog'

const popupEscapes = new WeakSet()

export default {
  name: 'VpcDialog',
  inheritAttrs: false,
  components: { MoldDialog },
  props: { visible: { type: Boolean, default: true }, afterClose: { type: Function, default: null } },
  data: () => ({ focusTrigger: null, focusRoute: '' }),
  beforeMount () { if (this.visible) this.captureFocus() },
  watch: { visible (value) { if (value) this.captureFocus() } },
  beforeUnmount () { this.returnFocus() },
  methods: {
    captureFocus () { this.focusTrigger = document.activeElement; this.focusRoute = this.$route?.fullPath || window.location.hash },
    returnFocus () {
      const trigger = this.focusTrigger
      const route = this.focusRoute
      this.$nextTick(() => {
        if (!trigger?.isConnected || route !== (this.$route?.fullPath || window.location.hash)) return
        if (Array.from(document.querySelectorAll('.ant-modal')).some(modal => modal.getClientRects().length && getComputedStyle(modal).display !== 'none')) return
        if (document.activeElement === document.body || !document.activeElement?.getClientRects().length) trigger.focus({ preventScroll: true })
      })
    },
    afterClosed () { if (this.afterClose) this.afterClose(); this.returnFocus() },
    rememberPopup (event) { if (event.target.closest?.('.ant-select-open')) popupEscapes.add(event) },
    popupEscape (event) {
      // Let Select handle Escape first; the same key must not also close its form.
      if (popupEscapes.has(event)) event.stopPropagation()
    }
  }
}
</script>
<style scoped>
.vpc-dialog-body { min-width: 0; }
.vpc-dialog-body :deep(.supported-services-container) { height: auto; overflow: visible; }
</style>
