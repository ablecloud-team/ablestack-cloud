<!-- RuleSilenceModal.vue (기간 선택 드롭다운 + i18n 네임드 파라미터 적용) -->
<template>
  <div class="form-layout" v-ctrl-enter="$refs.submit?.$el?.click()">
    <a-spin :spinning="loading">
      <a-form layout="vertical">
        <a-alert
          type="info"
          :message="$t('label.rule') + ': ' + titleText"
          show-icon
          style="margin-bottom: 12px"
        />

        <!-- 사일런스 안내 -->
        <a-alert type="warning" show-icon style="margin-bottom: 12px">
          <template #message>{{ $t('label.silence.infoTitle') }}</template>
          <template #description>
            <ul class="bullet">
              <li>{{ $t('message.silence.info.1') }}</li>
              <li>{{ $t('message.silence.info.2') }}</li>
              <li>{{ $t('message.silence.info.3') }}</li>
              <li>{{ $t('message.silence.info.4') }}</li>
            </ul>
          </template>
        </a-alert>

        <!-- 기간 선택 -->
        <a-form-item :label="$t('label.silenceperiod.select')">
          <a-select
            v-model:value="form.duration"
            :getPopupContainer="getPopupContainer"
            style="width: 100%"
            :placeholder="$t('label.select')"
          >
            <a-select-option v-for="opt in presets" :key="opt" :value="opt">
              <!-- 좌: 'N분/시간/… 동안 사일런스', 우: '지금부터 HH:MM까지' -->
              {{ longLabel(opt) }}
              <span class="option-sep"> — </span>
              <span class="option-sub">
                {{ $t('message.silence.until', { time: endTimeText(opt) }) }}
              </span>
            </a-select-option>
          </a-select>
        </a-form-item>

        <!-- 동의 체크 -->
        <a-form-item>
          <a-checkbox v-model:checked="awareChecked">
            {{ $t('label.silence.confirm.ack') }}
          </a-checkbox>
        </a-form-item>

        <div class="actions">
          <a-button @click="closeAction">{{ $t('label.cancel') }}</a-button>
          <a-button
            ref="submit"
            type="primary"
            :loading="loading"
            :disabled="!awareChecked"
            @click="handleSubmit"
          >
            {{ $t('label.ok') }}
          </a-button>
        </div>
      </a-form>
    </a-spin>
  </div>
</template>

<script>
import { reactive, ref, computed } from 'vue'
import { api } from '@/api'

export default {
  name: 'RuleSilenceModal',
  props: {
    resource: { type: Object, default: null },
    selection: { type: Array, default: () => [] },
    records: { type: Array, default: () => [] }
  },

  /* this.$t를 쓰는 패턴(프로젝트 다른 파일과 동일) */
  methods: {
    // '30m' | '1h' | '2h' | '6h' | '12h' | '24h' | '3d' | '1w' | '2w' | '1M'
    longLabel (v) {
      const mm = String(v).match(/^(\d+)([a-zA-Z])$/)
      if (!mm) { return '' }
      const n = parseInt(mm[1], 10)
      const u = mm[2]
      if (u === 'm') { return this.$t('label.silence.duration.minutes', [n]) }
      if (u === 'h') { return this.$t('label.silence.duration.hours', [n]) }
      if (u === 'd') { return this.$t('label.silence.duration.days', [n]) }
      if (u === 'w') { return this.$t('label.silence.duration.weeks', [n]) }
      if (u === 'M') { return this.$t('label.silence.duration.months', [n]) }
      return ''
    }
  },

  setup (props, { emit }) {
    const loading = ref(false)
    const awareChecked = ref(false)
    const form = reactive({ duration: '30m' })
    const presets = ['30m', '1h', '2h', '6h', '12h', '24h', '3d', '1w', '2w', '1M']

    const titleText = computed(() => {
      const r = props.resource || {}
      return r.name || r.uid || r.id || '-'
    })

    const toMinutes = (s) => {
      const m = String(s || '').trim().match(/^(\d+)\s*([mhdwM])$/)
      if (!m) { return 0 }
      const n = parseInt(m[1], 10)
      const u = m[2]
      if (u === 'm') { return n }
      if (u === 'h') { return n * 60 }
      if (u === 'd') { return n * 60 * 24 }
      if (u === 'w') { return n * 60 * 24 * 7 }
      if (u === 'M') { return n * 60 * 24 * 30 }
      return 0
    }

    const endTimeText = (v) => {
      const minutes = toMinutes(v)
      if (!minutes) { return '' }
      const end = new Date(Date.now() + minutes * 60 * 1000)
      const now = new Date()
      const sameDay =
        end.getFullYear() === now.getFullYear() &&
        end.getMonth() === now.getMonth() &&
        end.getDate() === now.getDate()
      return sameDay
        ? end.toLocaleTimeString('ko-KR', { hour: '2-digit', minute: '2-digit' })
        : end.toLocaleString('ko-KR', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' })
    }

    const getUid = (r) => r?.metadata?.rule_uid || r?.uid || r?.id || null

    const uniqueByUid = (arr) => {
      const seen = new Set()
      const out = []
      for (let i = 0; i < arr.length; i += 1) {
        const uid = getUid(arr[i])
        if (!uid || seen.has(uid)) { continue }
        seen.add(uid)
        out.push(arr[i])
      }
      return out
    }

    const pickTargets = () => {
      if (props.resource && Object.keys(props.resource).length) {
        return uniqueByUid([props.resource])
      }
      if (Array.isArray(props.selection) && props.selection.length && Array.isArray(props.records)) {
        const out = []
        for (let i = 0; i < props.selection.length; i += 1) {
          const key = props.selection[i]
          const rec = props.records.find(r => r.id === key || r.uid === key)
          if (rec) { out.push(rec) }
        }
        return uniqueByUid(out)
      }
      return []
    }

    const handleSubmit = async () => {
      if (loading.value) { return }
      const minutes = toMinutes(form.duration)
      if (!minutes) { return }
      const targets = pickTargets()
      if (!targets.length) { return }

      loading.value = true
      try {
        for (let i = 0; i < targets.length; i += 1) {
          const rec = targets[i]
          const uid = getUid(rec)
          if (!uid) { continue }
          const params = {
            'labels[0].key': '__alert_rule_uid__',
            'labels[0].value': uid,
            durationMinutes: minutes,
            comment: `action:silence:${form.duration}`
          }
          await api('createWallAlertSilence', params)
        }
        emit('refresh-data')
        emit('close-action')
      } catch (e) {
        console.log('[RuleSilenceModal] createWallAlertSilence failed:', e)
      } finally {
        loading.value = false
      }
    }

    const closeAction = () => emit('close-action')
    const getPopupContainer = (trigger) => (trigger && trigger.parentNode) || document.body

    return {
      loading,
      awareChecked,
      form,
      presets,
      titleText,
      endTimeText,
      handleSubmit,
      closeAction,
      getPopupContainer
    }
  }
}
</script>

<style scoped>
.form-layout { width: 80vw; }
@media (min-width: 600px) { .form-layout { width: 420px; } }

.bullet { margin: 0; padding-left: 18px; }
.bullet.tight li { margin: 2px 0; }
.bullet li { font-size: 12px; color: rgba(0, 0, 0, 0.65); }

.list { display: flex; flex-direction: column; gap: 6px; }
.item { border: 1px solid #f0f0f0; border-radius: 6px; padding: 8px 10px; }
.item:hover { background: #fafafa; }

.row { display: flex; align-items: center; gap: 10px; }
.text { display: flex; flex-direction: column; }
.main { font-size: 14px; line-height: 1.2; }
.sub { margin-top: 2px; font-size: 12px; color: rgba(0, 0, 0, 0.45); }

.actions { display: flex; justify-content: flex-end; gap: 8px; }

/* 옵션 내 보조 텍스트(— 지금부터 ~까지) */
.option-sep { opacity: 0.65; }
.option-sub { opacity: 0.65; }

.form-layout { padding-right: 5px; }
.form-layout { padding-left: 13px; }
@media (max-width: 600px) { .form-layout { padding-right: 35px; } }
</style>

<style>
/* Ant Design Vue 모달 오버라이드(프로젝트 컨벤션 유지) */
.ant-modal .ant-modal-close { top: -10px; }
.ant-modal .ant-modal-close .ant-modal-close-x { padding-left: 20px; top: -10px; }
</style>
