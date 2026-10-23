<!-- Licensed to the Apache Software Foundation (ASF) under one or more
contributor license agreements. See the NOTICE file distributed with this
work for additional information regarding copyright ownership. The ASF
licenses this file to you under the Apache License, Version 2.0 (the
"License"); you may not use this file except in compliance with the License.
You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0 .
Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
License for the specific language governing permissions and limitations. -->
<template>
  <section ref="root" :class="{ 'offering-form-sections': enabled }" data-testid="offering-form-sections">
    <template v-if="enabled">
      <a-tabs v-model:activeKey="active" :destroyInactiveTabPane="false">
        <a-tab-pane v-for="section in sections" :key="section" :tab="$t('label.offering.form.' + section)" :forceRender="true">
          <div :data-offering-section="section">
            <slot :name="section" />
          </div>
        </a-tab-pane>
      </a-tabs>
    </template>
    <template v-else>
      <slot name="basic" />
      <slot name="volume" />
      <slot name="advanced" />
    </template>
  </section>
</template>
<script>
export default {
  name: 'OfferingFormSections',
  props: { enabled: Boolean },
  data: () => ({ active: 'basic', sections: ['basic', 'volume', 'advanced'] }),
  methods: {
    fieldElement (root, field) {
      if (!root || !field) return
      const input = [...root.querySelectorAll('[id]')].find(el => el.id === field || el.id.endsWith('_' + field))
      if (input) return input
      // A control can supply its own id (for example zone-selection), while
      // FormItem keeps the model field in the label's for attribute.
      const label = [...root.querySelectorAll('label[for]')].find(el => el.htmlFor.endsWith('_' + field))
      return label?.closest('.ant-form-item')?.querySelector('input, textarea, select, button') || label
    },
    async reveal (section, field) {
      if (!this.enabled) return
      this.active = this.sections.includes(section) ? section : 'basic'
      await this.$nextTick()
      const pane = this.$refs.root.querySelector('[data-offering-section="' + this.active + '"]')
      if (!pane) return
      pane.querySelectorAll('details').forEach(details => { details.open = true })
      const input = this.fieldElement(pane, field)
      if (input) input.focus()
    },
    revealField (field) {
      const input = this.fieldElement(this.$refs.root, field)
      return this.reveal(input?.closest('[data-offering-section]')?.dataset.offeringSection || 'basic', field)
    }
  }
}
</script>
<style scoped lang="scss">
.offering-form-sections { min-width: 0; }
.offering-form-sections :deep(.ant-tabs-nav) { margin-bottom: 22px; }
.offering-form-sections :deep(.ant-form-item) { margin-bottom: 18px; min-width: 0; }
.offering-form-sections :deep(.ant-form-item-label) { white-space: normal; }
.offering-form-sections :deep(.ant-input), .offering-form-sections :deep(.ant-select) { width: 100%; min-width: 0; }
.offering-form-sections :deep(.offering-help) { color: var(--ui-text-secondary, #666); font-size: 12px; margin: 6px 0 16px; overflow-wrap: anywhere; }
.offering-form-sections :deep(.offering-placement) { border: 0; border-top: 1px solid var(--ui-border, #d9d9d9); padding: 16px 0 0; margin: 20px 0 0; min-width: 0; }
.offering-form-sections :deep(.offering-placement legend) { width: auto; font-size: 14px; color: var(--ui-text-secondary, #595959); border: 0; padding: 0 8px 0 0; }
.offering-form-sections :deep(.offering-placement-grid) { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 0 16px; }
.offering-form-sections :deep(.offering-root-summary) { display: grid; grid-template-columns: 120px minmax(0, 1fr); gap: 8px 12px; margin: 12px 0; }
.offering-form-sections :deep(.offering-root-summary dt) { color: var(--ui-text-secondary, #666); }
.offering-form-sections :deep(.offering-root-summary dd) { margin: 0; overflow-wrap: anywhere; }
@media (max-width: 600px) {
  .offering-form-sections :deep(.ant-row > .ant-col) { flex: 0 0 100%; max-width: 100%; }
  .offering-form-sections :deep(.offering-placement-grid), .offering-form-sections :deep(.offering-root-summary) { grid-template-columns: minmax(0, 1fr); }
  .offering-form-sections :deep(.ant-tabs-tab + .ant-tabs-tab) { margin-left: 20px; }
}
</style>
