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
  <mold-dialog size="xl" focus-guard :title="title" :closable="!busy" :keyboard="!busy" @cancel="close">
    <div
      ref="content"
      class="mold-form-dialog kubernetes-create-dialog resource-create-wizard"
      :class="{ 'resource-create-wizard--split': splitScroll }">
      <div
        v-if="splitScroll"
        ref="steps"
        class="resource-create-step-strip"
        role="region"
        :aria-label="title"
        tabindex="0"
        @wheel="scrollSteps">
        <ol>
          <li v-for="(stage, index) in stages" :key="stage.id" :aria-current="index === activeIndex ? 'step' : undefined">
            <button type="button" class="resource-create-step-button" :disabled="busy || validating" @click="navigateToStep(stage.id)">
              <span class="step-number">{{ index + 1 }}</span>
              <span>{{ $t(stage.label) }}</span>
            </button>
          </li>
        </ol>
      </div>
      <a-steps
        v-else
        ref="steps"
        class="kubernetes-wizard-steps resource-create-wizard-steps"
        size="small"
        :responsive="false"
        :current="activeIndex"
        :aria-label="title">
        <a-step v-for="stage in stages" :key="stage.id" :title="$t(stage.label)" />
      </a-steps>
      <div ref="body" class="mold-form-content kubernetes-wizard-body">
        <div
          ref="main"
          class="kubernetes-wizard-main"
          :tabindex="splitScroll ? 0 : undefined"
          :role="splitScroll ? 'region' : undefined"
          :aria-label="splitScroll ? title : undefined">
          <a-alert v-if="stepError" type="error" show-icon :message="$t(stepError)" class="resource-create-wizard-error" />
          <slot />
        </div>
        <slot name="summary" />
      </div>
      <details v-if="$slots.summary" class="kubernetes-summary-mobile">
        <summary>{{ $t(summaryTitleKey) }}</summary>
        <slot name="summary" />
      </details>
    </div>
    <template #footer>
      <a-button :disabled="busy" @click="close">{{ $t('label.cancel') }}</a-button>
      <a-button v-if="activeIndex > 0" :disabled="busy || validating" @click="moveTo(stages[activeIndex - 1].id)">{{ $t('label.previous') }}</a-button>
      <a-button v-if="!isLastStep" type="primary" :loading="validating" :disabled="busy || blocked" @click="next">
        {{ $t('label.next') }}
      </a-button>
      <slot v-else name="submit" />
    </template>
  </mold-dialog>
</template>

<script>
import MoldDialog from '@/components/view/MoldDialog.vue'

export default {
  name: 'ResourceCreateWizard',
  components: { MoldDialog },
  props: {
    title: { type: String, required: true },
    returnRoute: { type: String, required: true },
    step: { type: Number, required: true },
    stages: { type: Array, required: true },
    validateStep: { type: Function, required: true },
    splitScroll: Boolean,
    summaryTitleKey: { type: String, default: 'label.kubernetes.ui.summary' },
    busy: Boolean,
    blocked: Boolean
  },
  emits: ['update:step'],
  data () {
    return { validating: false, stepError: '' }
  },
  computed: {
    activeIndex () { return this.stages.findIndex(stage => stage.id === this.step) },
    isLastStep () { return this.activeIndex === this.stages.length - 1 }
  },
  watch: {
    stages: {
      immediate: true,
      handler (stages, previousStages) {
        if (this.activeIndex === -1 && stages.length) {
          const previousIndex = previousStages?.findIndex(stage => stage.id === this.step) ?? 0
          const fallback = stages[Math.min(Math.max(0, previousIndex), stages.length - 1)]
          this.$emit('update:step', fallback.id)
        }
        this.$nextTick(this.revealStep)
      }
    }
  },
  methods: {
    close () {
      if (this.busy) return
      return this.closeAfterSubmit()
    },
    closeAfterSubmit () {
      if (this.$router.options.history.state?.back) return this.$router.back()
      return this.$router.replace(this.returnRoute)
    },
    async moveTo (step) {
      this.stepError = ''
      this.$emit('update:step', step)
      await this.$nextTick()
      const body = this.splitScroll ? this.$refs.main : this.$refs.body
      if (body) body.scrollTop = 0
      this.revealStep()
    },
    revealStep () {
      const steps = this.$refs.steps?.$el || this.$refs.steps
      const active = steps?.querySelector(this.splitScroll ? '[aria-current="step"]' : '.ant-steps-item-process')
      if (!active || steps.scrollWidth <= steps.clientWidth) return
      if (!this.splitScroll) {
        steps.scrollLeft = active.offsetLeft
        return
      }
      const viewport = steps.getBoundingClientRect()
      const item = active.getBoundingClientRect()
      if (item.left < viewport.left) steps.scrollLeft += item.left - viewport.left
      if (item.right > viewport.right) steps.scrollLeft += item.right - viewport.right
    },
    scrollSteps (event) {
      const steps = this.$refs.steps
      if (event.ctrlKey || event.shiftKey || !event.deltaY || event.deltaX || steps.scrollWidth <= steps.clientWidth) return
      const unit = event.deltaMode === 1 ? 16 : (event.deltaMode === 2 ? steps.clientWidth : 1)
      steps.scrollLeft += event.deltaY * unit
      event.preventDefault()
    },
    async navigateToStep (step) {
      const targetIndex = this.stages.findIndex(stage => stage.id === step)
      if (targetIndex < 0 || this.activeIndex < 0 || this.step === step || this.busy || this.validating) return
      if (targetIndex < this.activeIndex) return this.moveTo(step)
      if (this.blocked) return

      const from = this.step
      const path = this.stages.slice(0, targetIndex + 1).map(stage => stage.id)
      this.validating = true
      this.stepError = ''
      try {
        // Recheck preceding steps because a change or a request may invalidate
        // selections the user previously completed.
        for (const prerequisite of path.slice(0, -1)) {
          const message = await this.validateStep(prerequisite)
          if (this.step !== from || this.busy || this.blocked || path.some((id, index) => this.stages[index]?.id !== id)) return
          if (!message) continue

          await this.moveTo(prerequisite)
          this.stepError = message
          await this.showInvalidField(prerequisite)
          return
        }
        await this.moveTo(step)
      } finally {
        this.validating = false
      }
    },
    async next () {
      if (this.activeIndex < 0 || this.isLastStep || this.busy || this.blocked || this.validating) return
      if (this.splitScroll) return this.navigateToStep(this.stages[this.activeIndex + 1].id)
      const current = this.step
      this.validating = true
      this.stepError = ''
      try {
        this.stepError = await this.validateStep(current) || ''
        if (!this.stepError && this.step === current && !this.busy && !this.blocked && !this.isLastStep) await this.moveTo(this.stages[this.activeIndex + 1].id)
      } finally {
        this.validating = false
      }
    },
    async showInvalidField (step) {
      // Keep every step mounted so the form can validate all registered fields at submission.
      await this.$nextTick()
      const content = step === undefined ? this.$refs.content : this.$refs.content?.querySelector('[data-wizard-step="' + step + '"]')
      const error = content?.querySelector('.ant-form-item-has-error')
      const stage = error?.closest('[data-wizard-step]')
      if (!stage) return
      const stepId = Number(stage.dataset.wizardStep)
      if (this.step !== stepId) await this.moveTo(stepId)
      error.scrollIntoView({ block: 'nearest' })
      const control = error.querySelector('input, select, textarea, button, [tabindex="0"]')
      if (control) control.focus({ preventScroll: true })
    }
  }
}
</script>

<style scoped>
.resource-create-wizard { max-height: min(680px, calc(100dvh - 180px)); }
.resource-create-wizard-steps { position: relative; overflow-x: auto; padding-bottom: 6px; }
.resource-create-wizard-steps :deep(.ant-steps-item) { flex: 0 0 auto; }
.resource-create-wizard-steps :deep(.ant-steps-item-title) { white-space: nowrap; }
.resource-create-wizard-error { margin-bottom: 16px; }
.resource-create-wizard :deep(.resource-create-section) { min-width: 0; }
.resource-create-wizard :deep(.resource-create-section-title) {
  color: var(--ui-text-primary);
  font-size: 15px;
  font-weight: 600;
  margin: 0 0 12px;
}
/* Input and summary share the viewport height and retain independent scroll positions. */
.resource-create-wizard--split { height: min(680px, calc(100dvh - 180px)); }
.resource-create-wizard--split .kubernetes-wizard-body {
  grid-template-rows: minmax(0, 1fr);
  align-items: stretch;
  overflow: hidden;
}
.resource-create-wizard--split .kubernetes-wizard-main,
.resource-create-wizard--split .kubernetes-wizard-body > :deep(.kubernetes-create-summary) {
  min-height: 0;
  overflow: auto;
  overscroll-behavior: contain;
}
.resource-create-wizard--split .kubernetes-wizard-main,
.resource-create-wizard--split :deep(.kubernetes-create-summary) {
  scrollbar-width: none;
}
.resource-create-wizard--split .kubernetes-wizard-main::-webkit-scrollbar,
.resource-create-wizard--split :deep(.kubernetes-create-summary::-webkit-scrollbar) {
  display: none;
}
.resource-create-wizard--split .kubernetes-wizard-main { padding-inline: 2px 8px; }
.resource-create-wizard--split .kubernetes-wizard-body > :deep(.kubernetes-create-summary) { padding-inline-end: 8px; }
.resource-create-wizard--split .kubernetes-wizard-main:focus-visible,
.resource-create-wizard--split .kubernetes-wizard-body > :deep(.kubernetes-create-summary:focus-visible),
.resource-create-step-strip:focus-visible {
  outline: 2px solid var(--ui-focus);
  outline-offset: -2px;
}
/* Use the same search colors in the compute, disk and storage selectors. */
.resource-create-wizard--split :deep(.ant-input-search .ant-input:not(:disabled)),
.resource-create-wizard--split :deep(.ant-input-search .ant-input-affix-wrapper:not(.ant-input-affix-wrapper-disabled)),
.resource-create-wizard--split :deep(.ant-input-search .ant-input-search-button:not(:disabled)) {
  background: var(--ui-bg-input);
  border-color: var(--ui-border-strong);
}
.resource-create-wizard--split :deep(.ant-input-search .ant-input:not(:disabled)) { color: var(--ui-text-primary); }
.resource-create-wizard--split :deep(.ant-input-search .ant-input::placeholder) { color: var(--ui-text-muted); opacity: 1; }
.resource-create-wizard--split :deep(.ant-input-search .ant-input:not(:disabled):is(:hover, :focus)),
.resource-create-wizard--split :deep(.ant-input-search .ant-input-affix-wrapper:not(.ant-input-affix-wrapper-disabled):is(:hover, :focus-within)),
.resource-create-wizard--split :deep(.ant-input-search .ant-input-search-button:not(:disabled):is(:hover, :focus)) {
  border-color: var(--ui-focus);
}
.resource-create-wizard--split :deep(.ant-input-search > .ant-input-group > .ant-input-group-addon:last-child .ant-input-search-button:not(.ant-btn-primary):not(:disabled)) {
  color: var(--ui-text-secondary) !important;
}
.resource-create-wizard--split :deep(.ant-input-search > .ant-input-group > .ant-input-group-addon:last-child .ant-input-search-button:not(.ant-btn-primary):not(:disabled):is(:hover, :focus-visible)) {
  color: var(--ui-link) !important;
}
.resource-create-step-strip {
  flex: 0 0 auto;
  margin-bottom: 16px;
  padding: 4px;
  overflow-x: auto;
  scrollbar-width: none;
}
.resource-create-step-strip::-webkit-scrollbar { display: none; }
.resource-create-step-strip ol {
  display: flex;
  margin: 0;
  padding: 0;
  width: max-content;
  list-style: none;
}
.resource-create-step-strip li {
  display: flex;
  align-items: center;
  gap: 8px;
  color: var(--ui-text-secondary);
  white-space: nowrap;
}
.resource-create-step-button {
  display: flex;
  align-items: center;
  gap: 8px;
  border: 0;
  border-radius: 4px;
  padding: 0;
  background: transparent;
  color: inherit;
  font: inherit;
  cursor: pointer;
}
.resource-create-step-button:not(:disabled):hover { color: var(--ui-link); }
.resource-create-step-button:focus-visible { outline: 2px solid var(--ui-focus); outline-offset: 2px; }
.resource-create-step-button:disabled { cursor: default; }
.resource-create-step-strip li + li::before {
  content: '';
  flex: 0 0 24px;
  height: 1px;
  margin-inline-start: 8px;
  background: var(--ui-border-strong);
}
.resource-create-step-strip .step-number {
  display: grid;
  place-items: center;
  min-width: 24px;
  min-height: 24px;
  border: 1px solid var(--ui-border-strong);
  border-radius: 50%;
  font-size: 12px;
}
.resource-create-step-strip [aria-current="step"] { color: var(--ui-text-primary); font-weight: 600; }
.resource-create-step-strip [aria-current="step"] .step-number {
  background: var(--ui-action-primary-bg);
  border-color: var(--ui-action-primary-bg);
  color: var(--ui-action-primary-text);
}
@media (max-width: 765px) {
  .resource-create-wizard--split .kubernetes-wizard-main { height: 100%; }
}
@media (max-width: 576px) {
  .resource-create-wizard { max-height: calc(100dvh - 155px); }
  .resource-create-wizard--split { height: calc(100dvh - 155px); }
}
</style>
