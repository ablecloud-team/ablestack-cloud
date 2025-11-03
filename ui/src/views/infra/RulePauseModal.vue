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

        <!-- 일시중지 안내 -->
        <a-alert
          type="warning"
          show-icon
          style="margin-bottom: 12px"
        >
          <template #message>
            {{ $t('label.pause.infoTitle', '경고 규칙 일시 중지 시 영향') }}
          </template>
          <template #description>
            <ul class="bullet">
              <li>{{ $t('message.pause.info.1') }}</li>
              <li>{{ $t('message.pause.info.2') }}</li>
              <li>{{ $t('message.pause.info.3') }}</li>
            </ul>
          </template>
        </a-alert>

        <!-- 동의 체크 -->
        <a-form-item>
          <a-checkbox v-model:checked="ackChecked">
            {{ $t('label.pause.confirm.ack', '위 내용을 확인했으며 일시 중지를 적용합니다.') }}
          </a-checkbox>
        </a-form-item>

        <div class="actions">
          <a-button @click="closeAction">{{ $t('label.cancel') || 'Cancel' }}</a-button>
          <a-button
            ref="submit"
            type="primary"
            :loading="loading"
            :disabled="!ackChecked"
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
import { ref, computed } from 'vue'
import { api } from '@/api'

export default {
  name: 'RulePauseModal',
  props: {
    // 단건 대상
    resource: { type: Object, default: null },
    // 선택된 키 배열
    selection: { type: Array, default: () => [] },
    // 리스트 원본 레코드들
    records: { type: Array, default: () => [] }
  },
  setup (props, { emit }) {
    const loading = ref(false)
    const ackChecked = ref(false)

    const titleText = computed(() => {
      const r = props.resource || {}
      return r.name || r.uid || r.id || '-'
    })

    const tOr = (key, fallback) => {
      const hasApp = typeof window !== 'undefined' && window.__app__ && typeof window.__app__.$t === 'function'
      if (hasApp) {
        const v = window.__app__.$t(key)
        return v && v !== key ? v : fallback
      }
      return fallback
    }

    const getUidOrId = (r) => {
      if (!r || typeof r !== 'object') return null
      return r.uid || (r.metadata && r.metadata.rule_uid) || r.id || null
    }

    // UID 기준 중복 제거
    const uniqueByUid = (arr) => {
      const seen = new Set()
      const out = []
      for (let i = 0; i < arr.length; i += 1) {
        const u = getUidOrId(arr[i])
        if (!u || seen.has(u)) continue
        seen.add(u)
        out.push(arr[i])
      }
      return out
    }

    // 단건 우선 → 없으면 selection 매핑
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
      if (loading.value) return
      const targets = pickTargets()
      if (!targets.length) return

      loading.value = true
      try {
        for (let i = 0; i < targets.length; i += 1) {
          const rec = targets[i]
          const id = getUidOrId(rec)
          if (!id) continue
          await api('pauseWallAlertRule', { id, paused: true })
        }
        emit('refresh-data')
        emit('close-action')
      } catch (e) {
        // eslint-disable-next-line no-console
        console.log('[RulePauseModal] pauseWallAlertRule failed:', e)
      } finally {
        loading.value = false
      }
    }

    const closeAction = () => emit('close-action')

    return {
      loading,
      ackChecked,
      titleText,
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

.actions { display: flex; justify-content: flex-end; gap: 8px; }
</style>
