<!-- WallThresholdEditor.vue (퍼센트 Outside 0~100 금지 + 자연어 메시지) -->
<!--
Licensed to the Apache Software Foundation (ASF) ...
-->
<template>
  <div class="form-layout" v-ctrl-enter="$refs.submit && $refs.submit.$el && $refs.submit.$el.click()">
    <a-spin :spinning="loading">
      <a-form
        ref="formRef"
        :model="form"
        :loading="loading"
        layout="vertical"
        :validate-messages="validateMessages"
        @finish="handleFinish">

        <a-alert
          type="info"
          :message="$t('label.rule') + ': ' + (resource?.name || resource?.id)"
          show-icon
          style="margin-bottom: 12px" />

        <!-- 연산자 -->
        <a-form-item name="operator">
          <template #label>
            <tooltip-label :title="$t('label.operator')" :tooltip="apiParams.operator?.description || ''" />
          </template>
          <a-select
            :value="form.operator"
            :getPopupContainer="getPopupContainer"
            :placeholder="$t('label.operator')"
            @update:value="onOperatorChange">
            <a-select-option
              v-for="opt in operatorOptions"
              :key="opt.value"
              :value="opt.value">{{ opt.text }}</a-select-option>
          </a-select>
        </a-form-item>

        <!-- 단일 임계치 -->
        <a-form-item
          v-if="!isRange"
          name="threshold"
          :label="$t('label.threshold')"
          :rules="thresholdLowerRules"
          :dependencies="['operator']"
          validateTrigger="change">
          <template #label>
            <tooltip-label :title="$t('label.threshold')" :tooltip="apiParams.threshold?.description || ''" />
          </template>
          <a-input-number
            :value="form.threshold"
            :min="minValue"
            :max="maxValue"
            v-focus="true"
            style="width: 100%"
            :placeholder="isPercentRule ? '0 ~ 100' : (apiParams.threshold?.description || 'value')"
            @update:value="onLowerChange" />
        </a-form-item>

        <!-- 범위 임계치: 한 줄 20 ~ 50 형태 -->
        <a-form-item
          v-else
          name="thresholdRange"
          :label="rangeLabel"
          :rules="rangeBothRules"
          :dependencies="['threshold','threshold2','operator']"
          validateTrigger="change"
          :preserve="false">
          <template #label>
            <tooltip-label :title="rangeLabel" :tooltip="rangeTooltip" />
          </template>
          <a-input-group compact>
            <a-input-number
              :key="'t1-' + String(form.operator)"
              :value="form.threshold"
              :min="minValue"
              :max="maxValue"
              v-focus="true"
              style="width: calc(50% - 12px)"
              :placeholder="isPercentRule ? $t('label.threshold.lower') + ' (0~100)' : $t('label.threshold.lower')"
              @update:value="onLowerChange" />
            <span class="range-sep">~</span>
            <a-input-number
              :key="'t2-' + String(form.operator)"
              :value="form.threshold2"
              :min="minValue"
              :max="maxValue"
              style="width: calc(50% - 12px)"
              :placeholder="isPercentRule ? $t('label.threshold.upper') + ' (0~100)' : $t('label.threshold.upper')"
              @update:value="onUpperChange" />
          </a-input-group>
        </a-form-item>

        <div class="action-button">
          <a-button
            html-type="submit"
            :loading="loading || submitting"
            ref="submit"
            type="primary">
            {{ $t('label.ok') }}
          </a-button>
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
  props: {
    resource: { type: Object, required: true },
    allRules: { type: Array, default: () => [] }
  },
  data () {
    return {
      loading: false,
      submitting: false,
      formRef: null,
      form: reactive({
        operator: 'gt',
        threshold: undefined,
        threshold2: undefined
      }),
      minValue: 0,
      apiParams: {},
      opLabels: {
        gt: 'label.operator.above',
        lt: 'label.operator.below',
        between: 'label.operator.within',
        outside: 'label.operator.outside'
      },
      // 전역 기본문구로 인한 영어 required 노출 방지: 비워둡니다.
      validateMessages: {},
      serverPrefersUnaryGteLte: false,
      rawOperator: null
    }
  },
  beforeCreate () {
    this.apiParams = this.$getApiParams('updateWallAlertRuleThreshold') || {}
  },
  watch: {
    resource: {
      immediate: true,
      deep: false,
      handler (r) {
        const x = r || {}
        this.rawOperator = String(x.operator || '').toLowerCase()
        this.serverPrefersUnaryGteLte = /(^|_)(gte|lte)($|_)/.test(this.rawOperator)

        const op = this.normalizeOp(x.operator)
        this.form.operator = op || 'gt'
        this.form.threshold = this.toNum(x.threshold)
        this.form.threshold2 = this.isRangeOp(op)
          ? this.toNum(x.threshold2 || x.upper || x.thresholdUpper)
          : undefined

        this.$nextTick(() => this.$refs.formRef?.clearValidate?.())
      }
    }
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
    // 퍼센트 규칙 여부: 이름/타이틀/표시명/라벨 힌트까지 폭넓게 탐지
    isPercentRule () {
      const pieces = [
        this.resource?.name,
        this.resource?.title,
        this.resource?.displayName,
        this.resource?.labels?.unit,
        this.resource?.annotations?.unit
      ].map(x => String(x || ''))
      const text = pieces.join(' ')
      return /(사용률|[%％]|퍼센트|percent|percentage)/i.test(text)
    },
    // 퍼센트 규칙이면 UI 상한값 100, 아니면 제한 해제
    maxValue () {
      return this.isPercentRule ? 100 : undefined
    },
    rangeLabel () {
      const s = this.$t('label.threshold.range')
      const fallback = `${this.$t('label.threshold.lower')} ~ ${this.$t('label.threshold.upper')}`
      return s && s !== 'label.threshold.range' ? s : fallback
    },
    rangeTooltip () {
      const a = this.apiParams?.threshold?.description || 'lower'
      const b = this.apiParams?.threshold2?.description || 'upper'
      return `${a} ~ ${b}`
    },
    // 단일(하한) 검증
    thresholdLowerRules () {
      return [
        {
          validator: (_, v) => {
            const a = Number(v)
            if (!Number.isFinite(a)) {
              return Promise.reject(new Error(this.$t('error.number.required')))
            }
            if (this.isPercentRule && (a < 0 || a > 100)) {
              return Promise.reject(new Error(this.tOr('error.percent.range', '0~100 범위여야 합니다')))
            }
            return Promise.resolve()
          }
        }
      ]
    },
    // 범위(하한+상한) 통합 검증
    rangeBothRules () {
      return [
        {
          validator: () => {
            const a = Number(this.form.threshold)
            const b = Number(this.form.threshold2)
            if (!Number.isFinite(a) || !Number.isFinite(b)) {
              return Promise.reject(new Error(this.$t('error.number.required')))
            }
            if (this.isPercentRule && (a < 0 || a > 100 || b < 0 || b > 100)) {
              return Promise.reject(new Error(this.tOr('error.percent.range', '0~100 범위여야 합니다')))
            }
            // 퍼센트 + outside + 0~100 전체 범위는 모순 → 금지
            if (this.isPercentRule && this.form.operator === 'outside' && a <= 0 && b >= 100) {
              return Promise.reject(new Error(this.tOr('error.percent.outside.full', '0~100 전체 범위를 벗어난 조건은 만들 수 없습니다. 범위를 좁혀주세요.')))
            }
            if (b < a) {
              return Promise.reject(new Error(this.$t('error.range.invalid')))
            }
            return Promise.resolve()
          }
        }
      ]
    }
  },
  methods: {
    // ===== 유틸 =====
    tOr (key, fallback) {
      const v = this.$t(key)
      return v && v !== key ? v : fallback
    },
    keyOf (r) {
      return (r?.metadata?.rule_uid) || r?.uid || r?.id
    },
    normalizeOp (op) {
      if (op == null) return 'gt'
      const t = String(op).toLowerCase()
      if (t === 'within_range') return 'between'
      if (t === 'outside_range') return 'outside'
      if (t === 'gte') return 'gt'
      if (t === 'lte') return 'lt'
      return ['gt', 'lt', 'between', 'outside'].includes(t) ? t : 'gt'
    },
    toServerOp (uiOp) {
      const t = String(uiOp || '').toLowerCase()
      if (t === 'between') return 'within_range'
      if (t === 'outside') return 'outside_range'
      if (t === 'gt') return this.serverPrefersUnaryGteLte ? 'gte' : 'gt'
      if (t === 'lt') return this.serverPrefersUnaryGteLte ? 'lte' : 'lt'
      return t
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
    // 입력값을 숫자 또는 undefined로 정규화 + 퍼센트면 즉시 0~100으로 클램프
    coerceNumber (val) {
      if (val === '' || val == null) return undefined
      const n = Number(val)
      if (!Number.isFinite(n)) return undefined
      return this.isPercentRule ? Math.min(100, Math.max(0, n)) : n
    },
    onLowerChange (val) {
      this.form.threshold = this.coerceNumber(val)
    },
    onUpperChange (val) {
      this.form.threshold2 = this.coerceNumber(val)
    },
    getPopupContainer (trigger) {
      return trigger?.parentNode || document.body
    },
    onOperatorChange (val) {
      this.form.operator = val
      if (!this.isRange) {
        this.form.threshold2 = undefined
        this.$nextTick(() => {
          this.$refs.formRef?.clearValidate?.(['threshold', 'thresholdRange'])
        })
      } else {
        this.$nextTick(() => {
          this.$refs.formRef?.clearValidate?.(['thresholdRange'])
          this.$refs.formRef?.validateFields?.(['thresholdRange']).catch(() => {})
        })
      }
    },
    // 성공 메시지 자연어 빌드(단위 포함)
    buildDesc (uiOp, t1, t2) {
      const unit = this.isPercentRule ? '%' : ''
      if (uiOp === 'gt') return `${t1}${unit} 이상`
      if (uiOp === 'lt') return `${t1}${unit} 이하`
      if (uiOp === 'between') return `${t1}${unit} ~ ${t2}${unit} 사이`
      if (uiOp === 'outside') return `${t1}${unit} 미만 또는 ${t2}${unit} 초과`
      return `${t1}${unit}`
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
        case 'within_range':
          return 'label.operator.within'
        case 'outside':
        case 'outside_range':
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

    // ===== 제출 전 페이로드(제출시에만 호출) =====
    buildUpdatePayload () {
      const uiOp = this.form.operator || 'gt'
      const isRange = this.isRangeOp(uiOp)

      let lower = this.toNum(this.form.threshold)
      let upper = this.toNum(this.form.threshold2)

      // 퍼센트 규칙은 서버 전송 직전에 한 번 더 0~100 클램프
      if (this.isPercentRule) {
        if (Number.isFinite(lower)) lower = Math.min(100, Math.max(0, lower))
        if (Number.isFinite(upper)) upper = Math.min(100, Math.max(0, upper))
      }

      if (isRange && Number.isFinite(lower) && Number.isFinite(upper) && upper < lower) {
        const t = lower; lower = upper; upper = t
      }

      // ★ uid 우선, 없으면 id로 폴백
      const uid = this.resource?.metadata?.rule_uid || this.resource?.uid
      const payload = { operator: this.toServerOp(uiOp) }
      if (uid) payload.uid = uid
      else payload.id = this.resource.id

      if (isRange) {
        payload.threshold = lower
        payload.threshold2 = upper
        payload.lower = lower
        payload.upper = upper
        payload.thresholdUpper = upper
      } else {
        payload.threshold = lower
        payload.lower = lower
        // 상한 키들을 명시적으로 비워서 “되돌림” 방지
        payload.threshold2 = null
        payload.upper = null
        payload.thresholdUpper = null
      }

      return payload
    },

    // ===== @finish 전용 제출 핸들러 =====
    async handleFinish () {
      const currKey = this.keyOf(this.resource)
      if (currKey && Array.isArray(this.allRules) && this.allRules.length) {
        const dups = this.allRules.filter(r => this.keyOf(r) === currKey)
        if (dups.length > 1) {
          this.$notification.warning({
            message: this.$t('message.duplicate.rules.detected'),
            description: this.$t('message.duplicate.rules.description', { key: currKey, count: dups.length })
          })
          return
        }
      }
      this.submitting = true
      this.loading = true
      try {
        const payload = this.buildUpdatePayload()
        const res = await api('updateWallAlertRuleThreshold', payload)

        /* eslint-disable no-console */
        console.log('[WallThresholdEditor] UPDATE res <-', res)
        /* eslint-enable no-console */

        const uiOp = this.form.operator || 'gt'
        const t1 = this.fmt(payload.threshold)
        const t2 = this.fmt(payload.threshold2)
        const desc = this.buildDesc(uiOp, t1, t2)

        // ✅ 성공 알림: 규칙명 + 자연어 임계치 설명 병기 (i18n 키 변경 없음)
        const ruleName =
          String(this.resource?.name || this.resource?.title || this.resource?.uid || this.resource?.id || '')
        this.$emit('refresh-data')
        this.$notification.success({
          message: this.$t('message.threshold.updated'),
          description: ruleName ? `${ruleName}: ${desc}` : desc
        })
        this.closeAction()
      } catch (error) {
        this.$notification.error({
          message: this.$t('message.request.failed'),
          description: (error?.response?.headers?.['x-description']) || error.message,
          duration: 0
        })
      } finally {
        this.loading = false
        this.submitting = false
      }
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
.range-sep {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 24px;
  line-height: 32px;
  height: 32px;
  user-select: none;
}
</style>
