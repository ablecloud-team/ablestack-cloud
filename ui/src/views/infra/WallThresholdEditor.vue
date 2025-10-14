<!--
Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements.
See the NOTICE file distributed with this work for additional information regarding copyright ownership.
The ASF licenses this file to you under the Apache License, Version 2.0 (the "License"); you may not use
this file except in compliance with the License. You may obtain a copy of the License at

  http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software distributed under the License is
distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and limitations under the License.
-->

<template>
  <div class="form-layout" v-ctrl-enter="handleSubmit">
    <a-spin :spinning="loading">
      <a-form
        ref="formRef"
        :model="form"
        :loading="loading"
        layout="vertical"
        @finish="handleSubmit">

        <a-alert
          type="info"
          :message="$t('label.rule') + ': ' + (resource?.name || resource?.id)"
          show-icon
          style="margin-bottom: 12px" />

        <a-form-item name="operator" :rules="[{ required: true, message: $t('message.error.required') }]">
          <template #label>
            <tooltip-label :title="$t('label.operator')" :tooltip="apiParams.operator?.description || ''" />
          </template>
          <a-select
            :value="form.operator"
            :getPopupContainer="getPopupContainer"
            :placeholder="$t('label.operator')"
            @change="onOperatorChange">
            <a-select-option
              v-for="opt in operatorOptions"
              :key="opt.value"
              :value="opt.value">{{ opt.text }}</a-select-option>
          </a-select>
        </a-form-item>

        <a-form-item name="threshold" :rules="[{ required: true, message: $t('message.error.required') }]">
          <template #label>
            <tooltip-label :title="$t('label.threshold')" :tooltip="apiParams.threshold?.description || ''" />
          </template>
          <a-input-number
            :value="form.threshold"
            :min="minValue"
            v-focus="true"
            style="width: 100%"
            :placeholder="apiParams.threshold?.description || 'value'"
            @update:value="onThresholdChange" />
        </a-form-item>

        <a-form-item v-if="isRange" name="threshold2" :rules="rangeRules">
          <template #label>
            <tooltip-label :title="$t('label.threshold.upper')" :tooltip="apiParams.threshold2?.description || ''" />
          </template>
          <a-input-number
            :value="form.threshold2"
            :min="minValue"
            style="width: 100%"
            @update:value="onThreshold2Change" />
        </a-form-item>

        <div class="action-button">
          <a-button @click="closeAction">{{ $t('label.cancel') }}</a-button>
          <a-button :loading="loading" ref="submit" type="primary" @click="handleSubmit">{{ $t('label.ok') }}</a-button>
        </div>
      </a-form>
    </a-spin>
  </div>
</template>

<script>
import { reactive } from 'vue'
import { api } from '@/api'
import TooltipLabel from '@/components/widgets/TooltipLabel'

export default {
  name: 'WallThresholdEditor',
  components: { TooltipLabel },
  props: { resource: { type: Object, required: true } },
  data () {
    return {
      loading: false,
      formRef: null,
      form: reactive({
        operator: 'gt',
        threshold: undefined,
        threshold2: undefined
      }),
      minValue: 0,
      opLabels: {
        gt: 'label.operator.above',
        lt: 'label.operator.below',
        between: 'label.operator.within',
        outside: 'label.operator.outside'
      }
    }
  },
  beforeCreate () {
    this.apiParams = this.$getApiParams('updateWallAlertRuleThreshold') || {}
  },
  created () {
    const r = this.resource || {}
    const op = this.normalizeOp(r.operator)

    this.form.operator = op
    this.form.threshold = this.toNum(r.threshold)
    this.form.threshold2 = this.isRangeOp(op)
      ? this.toNum(r.threshold2 || r.upper || r.thresholdUpper)
      : undefined
  },
  computed: {
    operatorOptions () {
      return ['gt', 'lt', 'between', 'outside'].map(v => ({
        value: v,
        text: this.$t(this.opLabels[v])
      }))
    },
    isRange () {
      return this.isRangeOp(this.form.operator)
    },
    rangeRules () {
      return [
        { required: true, message: this.$t('message.error.required') },
        {
          validator: (_, v) => {
            const a = Number(this.form.threshold)
            const b = Number(v)
            if (!Number.isFinite(a) || !Number.isFinite(b)) return Promise.reject(this.$t('message.error.number'))
            if (b < a) return Promise.reject(this.$t('message.error.range'))
            return Promise.resolve()
          }
        }
      ]
    }
  },
  methods: {
    normalizeOp (op) {
      if (op == null) return 'gt'
      const t = String(op).toLowerCase()
      if (t === 'within_range') return 'between'
      if (t === 'outside_range') return 'outside'
      if (t === 'gte') return 'gt'
      if (t === 'lte') return 'lt'
      return ['gt', 'lt', 'between', 'outside'].includes(t) ? t : 'gt'
    },

    isRangeOp (op) {
      const t = String(op || '').toLowerCase()
      return t === 'between' || t === 'outside'
    },

    toNum (x) {
      if (x === null || x === undefined || x === '') return undefined
      const n = Number(x)
      return Number.isFinite(n) ? n : undefined
    },
    getPopupContainer (trigger) {
      return trigger?.parentNode || document.body
    },
    onOperatorChange (val) {
      this.form.operator = val
      if (!this.isRange) this.form.threshold2 = undefined
    },
    onThresholdChange (val) {
      this.form.threshold = Number(val)
    },
    onThreshold2Change (val) {
      this.form.threshold2 = Number(val)
    },
    opLabelKey (op) {
      switch (op) {
        case 'gt':
        case 'gte':
          return 'label.operator.above'
        case 'lt':
        case 'lte':
          return 'label.operator.below'
        case 'between':
          return 'label.operator.within'
        case 'outside':
          return 'label.operator.outside'
        default:
          return 'label.operator.above'
      }
    },
    fmt (n) {
      const v = Number(n)
      if (!Number.isFinite(v)) return ''
      return String(v)
        .replace(/(\.\d*?[1-9])0+$/, '$1')
        .replace(/\.0+$/, '')
        .replace(/\.$/, '')
    },
    handleSubmit (e) {
      if (e && e.preventDefault) e.preventDefault()
      if (this.loading) return

      const formInst = this.$refs.formRef
      if (!formInst || !formInst.validate) return

      formInst.validate().then(() => {
        const op = this.form.operator || 'gt'
        const params = {
          id: this.resource.id,
          operator: op,
          threshold: Number(this.form.threshold)
        }
        if (op === 'between' || op === 'outside') {
          params.threshold2 = Number(this.form.threshold2)
        }

        this.loading = true
        api('updateWallAlertRuleThreshold', params).then(() => {
          const opKey = this.opLabelKey(op)
          const t1 = this.fmt(params.threshold)
          const t2 = this.fmt(params.threshold2)
          const desc = (op === 'between' || op === 'outside')
            ? `${this.$t(opKey)} ${t1} ~ ${t2}`
            : `${this.$t(opKey)} ${t1}`

          this.$emit('refresh-data')
          this.$notification.success({
            message: this.$t('message.threshold.updated'),
            description: desc
          })
          this.closeAction()
        }).catch(error => {
          this.$notification.error({
            message: this.$t('message.request.failed'),
            description: (error && error.response && error.response.headers && error.response.headers['x-description']) || error.message,
            duration: 0
          })
        }).finally(() => {
          this.loading = false
        })
      }).catch(err => {
        const first = err && err.errorFields && err.errorFields[0] && err.errorFields[0].name
        if (first && this.$refs.formRef && this.$refs.formRef.scrollToField) {
          this.$refs.formRef.scrollToField(first)
        }
      })
    },
    closeAction () {
      this.$emit('close-action')
    }
  }

}
</script>

<style scoped lang="less">
.form-layout {
  width: 80vw;
  @media (min-width: 600px) {
    width: 420px;
  }
}
.action-button {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}
</style>
