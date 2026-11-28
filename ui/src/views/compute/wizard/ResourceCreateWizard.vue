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
    <div ref="content" class="mold-form-dialog kubernetes-create-dialog resource-create-wizard">
      <a-steps
        ref="steps"
        class="kubernetes-wizard-steps resource-create-wizard-steps"
        size="small"
        :responsive="false"
        :current="activeIndex"
        :aria-label="title">
        <a-step v-for="stage in stages" :key="stage.id" :title="$t(stage.label)" />
      </a-steps>
      <div ref="body" class="mold-form-content kubernetes-wizard-body">
        <div class="kubernetes-wizard-main">
          <a-alert v-if="stepError" type="error" show-icon :message="$t(stepError)" class="resource-create-wizard-error" />
          <slot />
        </div>
        <slot name="summary" />
      </div>
      <details v-if="$slots.summary" class="kubernetes-summary-mobile">
        <summary>{{ $t('label.kubernetes.ui.summary') }}</summary>
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
      handler (stages) {
        if (this.activeIndex === -1) {
          const fallback = stages.find(stage => stage.id >= this.step) || stages[stages.length - 1]
          if (fallback) this.$emit('update:step', fallback.id)
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
      const body = this.$refs.body
      if (body) body.scrollTop = 0
      this.revealStep()
    },
    revealStep () {
      const steps = this.$refs.steps?.$el
      const active = steps?.querySelector('.ant-steps-item-process')
      if (steps && active && steps.scrollWidth > steps.clientWidth) steps.scrollLeft = active.offsetLeft
    },
    async next () {
      if (this.activeIndex < 0 || this.isLastStep || this.busy || this.blocked || this.validating) return
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
      await this.moveTo(Number(stage.dataset.wizardStep))
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
@media (max-width: 576px) {
  .resource-create-wizard { max-height: calc(100dvh - 155px); }
}
</style>
