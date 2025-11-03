<!-- RuleSilenceModal.vue (요약 문구/팝업 모두 제거, 체크 후 OK 즉시 적용) -->
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
        <a-alert
          type="warning"
          show-icon
          style="margin-bottom: 12px"
        >
          <template #message>
            {{ tOr('label.silence.infoTitle', '사일런스 적용 시 영향') }}
          </template>
          <template #description>
            <ul class="bullet">
              <li>{{ tOr('message.silence.info.1', '설정한 기간 동안 해당 경고의 알림(배너/토스트/통지)이 차단됩니다.') }}</li>
              <li>{{ tOr('message.silence.info.2', '경고 평가와 상태는 계속 갱신되며 규칙 자체는 바뀌지 않습니다.') }}</li>
              <li>{{ tOr('message.silence.info.3', '기간이 끝나면 사일런스가 자동 해제됩니다.') }}</li>
              <li>{{ tOr('message.silence.info.4', '활성 사일런스 시 버튼이 숨겨져 중복 생성이 방지됩니다.') }}</li>
            </ul>
          </template>
        </a-alert>

        <!-- 기간 선택 -->
        <a-form-item :label="$t('label.action.silence') || 'Silence 기간 선택'">
          <a-radio-group v-model:value="form.duration" style="width: 100%">
            <div class="list">
              <label v-for="opt in presets" :key="opt" class="item">
                <div class="row">
                  <a-radio :value="opt" />
                  <div class="text">
                    <div class="main">{{ longLabel(opt) }}</div>
                    <div class="sub">{{ tOr('message.silence.until', '지금부터 {time}까지').replace('{time}', endTimeText(opt)) }}</div>
                  </div>
                </div>
              </label>
            </div>
          </a-radio-group>
        </a-form-item>

        <!-- 동의 체크 -->
        <a-form-item>
          <a-checkbox v-model:checked="awareChecked">
            {{ tOr('label.silence.confirm.ack', '위 내용을 확인했으며 사일런스를 적용합니다.') }}
          </a-checkbox>
        </a-form-item>

        <div class="actions">
          <a-button @click="closeAction">{{ $t('label.cancel') || 'Cancel' }}</a-button>
          <a-button
            ref="submit"
            type="primary"
            :loading="loading"
            :disabled="!awareChecked"
            @click="handleSubmit"
          >
            {{ $t('label.ok') || 'OK' }}
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
  setup (props, { emit }) {
    const loading = ref(false)
    const awareChecked = ref(false)
    const form = reactive({ duration: '30m' })
    const presets = ['30m', '1h', '2h', '6h', '12h', '24h', '3d', '1w', '2w', '1M']

    const titleText = computed(() => {
      const r = props.resource || {}
      return r.name || r.uid || r.id || '-'
    })

    const tOr = (key, fallback) => {
      // i18n 폴백
      // eslint-disable-next-line no-undef
      const v = (typeof window !== 'undefined' && window.__app__ && window.__app__.$t)
        ? window.__app__.$t(key)
        : key
      return v && v !== key ? v : fallback
    }

    const toMinutes = (s) => {
      const m = String(s || '').trim().match(/^(\d+)\s*([mhdwM])$/)
      if (!m) return 0
      const n = parseInt(m[1], 10)
      const u = m[2]
      if (u === 'm') return n
      if (u === 'h') return n * 60
      if (u === 'd') return n * 60 * 24
      if (u === 'w') return n * 60 * 24 * 7
      if (u === 'M') return n * 60 * 24 * 30
      return 0
    }

    const longLabel = (v) => {
      const m = String(v).match(/^(\d+)([a-zA-Z])$/)
      if (!m) return v
      const n = parseInt(m[1], 10)
      const u = m[2]
      if (u === 'm') return `${n}분 동안 무음`
      if (u === 'h') return `${n}시간 동안 무음`
      if (u === 'd') return `${n}일 동안 무음`
      if (u === 'w') return `${n}주 동안 무음`
      if (u === 'M') return `${n}개월 동안 무음`
      return v
    }

    const endTimeText = (v) => {
      const minutes = toMinutes(v)
      if (!minutes) return ''
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

    // UID 기준으로 대상 중복 제거
    const uniqueByUid = (arr) => {
      const seen = new Set()
      const out = []
      for (let i = 0; i < arr.length; i += 1) {
        const uid = getUid(arr[i])
        if (!uid || seen.has(uid)) continue
        seen.add(uid)
        out.push(arr[i])
      }
      return out
    }

    // 단일 행 우선 → 없으면 selection 사용
    const pickTargets = () => {
      if (props.resource && Object.keys(props.resource).length) {
        return uniqueByUid([props.resource])
      }
      if (Array.isArray(props.selection) && props.selection.length && Array.isArray(props.records)) {
        const out = []
        for (let i = 0; i < props.selection.length; i += 1) {
          const key = props.selection[i]
          const rec = props.records.find(r => r.id === key || r.uid === key)
          if (rec) out.push(rec)
        }
        return uniqueByUid(out)
      }
      return []
    }

    const handleSubmit = async () => {
      // 재진입 가드
      if (loading.value) return

      const minutes = toMinutes(form.duration)
      if (!minutes) return
      const targets = pickTargets()
      if (!targets.length) return

      loading.value = true
      try {
        for (let i = 0; i < targets.length; i += 1) {
          const rec = targets[i]
          const uid = getUid(rec)
          if (!uid) continue
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
        // eslint-disable-next-line no-console
        console.log('[RuleSilenceModal] createWallAlertSilence failed:', e)
      } finally {
        loading.value = false
      }
    }

    const closeAction = () => emit('close-action')

    return {
      loading,
      awareChecked,
      form,
      presets,
      titleText,
      longLabel,
      endTimeText,
      handleSubmit,
      closeAction,
      tOr
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
</style>
