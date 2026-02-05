<!-- WallAlertAnnotationsEditor.vue (해결방안(summary/description) 편집) -->
<!--
Licensed to the Apache Software Foundation (ASF) ...
-->
<template>
  <div
    class="form-layout"
    style="width: 500px;"
    v-ctrl-enter="$refs.submit && $refs.submit.$el && $refs.submit.$el.click()"
  >
    <a-spin :spinning="loading">
      <a-form
        ref="formRef"
        :model="form"
        :loading="loading"
        layout="vertical"
        :validate-messages="validateMessages"
        @finish="submit">
        <div style="margin-bottom: 12px; color: rgba(0, 0, 0, 0.65);">
          <div style="font-weight: 600;">
            {{ ruleTitle }}
          </div>
          <div style="font-size: 12px;">
            UID: <span style="font-family: monospace;">{{ ruleUid }}</span>
          </div>
        </div>

        <a-form-item name="summary">
          <template #label>
            <span style="font-weight: 600;">{{ $t('label.summary') || '요약' }}</span>
          </template>
          <a-textarea
            v-model:value="form.summary"
            :auto-size="{ minRows: 2, maxRows: 6 }"
            allow-clear
            placeholder="요약을 입력합니다." />
        </a-form-item>

        <a-form-item name="description">
          <template #label>
            <span style="font-weight: 600;">{{ $t('label.description') || '설명' }}</span>
          </template>
          <a-textarea
            v-model:value="form.description"
            :auto-size="{ minRows: 6, maxRows: 14 }"
            allow-clear
            placeholder="설명을 입력합니다." />
        </a-form-item>

        <div style="display: flex; justify-content: flex-end; gap: 8px;">
          <a-button @click="closeAction">
            {{ $t('label.cancel') || '취소' }}
          </a-button>
          <a-button
            html-type="submit"
            :loading="loading || submitting"
            ref="submit"
            type="primary">
            {{ $t('label.ok') || 'OK' }}
          </a-button>
        </div>
      </a-form>
    </a-spin>
  </div>
</template>

<script>
import { reactive } from 'vue'
import { api } from '@/api'

export default {
  name: 'WallAlertAnnotationsEditor',
  props: {
    // AutogenView 액션 모달이 내려주는 표준 prop
    resource: { type: Object, required: true }
  },
  data () {
    return {
      loading: false,
      submitting: false,
      formRef: null,
      form: reactive({
        summary: '',
        description: ''
      }),
      origin: {
        summary: '',
        description: ''
      },
      // 전역 기본문구로 인한 영어 required 노출 방지: 비워둡니다.
      validateMessages: {},
      apiParams: {}
    }
  },
  computed: {
    ruleUid () {
      const r = this.resource || {}
      return String(r.uid || r.id || '')
    },
    ruleTitle () {
      const r = this.resource || {}
      return String(r.name || r.title || r.uid || r.id || '')
    }
  },
  beforeCreate () {
    // 다른 액션 컴포넌트와 동일한 방식으로 API 파라미터 메타를 로드합니다.
    this.apiParams = this.$getApiParams('updateWallAlertRuleAnnotations') || {}
  },
  watch: {
    resource: {
      immediate: true,
      deep: false,
      handler (r) {
        this.initFromResource(r)
      }
    }
  },
  methods: {
    // ===== 유틸 =====
    tOr (key, fallback) {
      const v = this.$t(key)
      return v && v !== key ? v : fallback
    },
    normalizeText (v) {
      if (v == null) return ''
      const s = String(v)
        .replace(/\r\n/g, '\n')
        .replace(/\r/g, '\n')
        .trim()
      return s === '-' ? '' : s
    },
    extractSummary (r) {
      const ann = r && r.annotations ? r.annotations : {}
      const v = (r && r.summary) ? r.summary : (ann.summary || ann.__summary__ || ann.message || '')
      return this.normalizeText(v)
    },
    extractDescription (r) {
      const ann = r && r.annotations ? r.annotations : {}
      const v = (r && r.description) ? r.description : (ann.description || ann.__description__ || '')
      return this.normalizeText(v)
    },
    initFromResource (r) {
      const x = r || {}
      const s = this.extractSummary(x)
      const d = this.extractDescription(x)
      this.form.summary = s
      this.form.description = d
      this.origin.summary = s
      this.origin.description = d
    },

    // ===== 액션 =====
    closeAction () {
      // AutogenView 액션 모달 닫기 시그널
      this.$emit('close-action')
    },

    isDirty () {
      return this.form.summary !== this.origin.summary || this.form.description !== this.origin.description
    },

    async submit () {
      const uid = this.ruleUid
      if (!uid) {
        this.$notification.error({
          message: this.tOr('message.request.failed', '요청 실패'),
          description: 'uid가 없습니다.',
          duration: 0
        })
        this.closeAction()
        return
      }

      if (!this.isDirty()) {
        this.$notification.info({
          message: this.tOr('message.notice', '안내'),
          description: '변경 내용이 없습니다.'
        })
        this.closeAction()
        return
      }

      this.submitting = true
      this.loading = true
      try {
        const payload = {
          uid,
          summary: this.form.summary,
          description: this.form.description
        }

        const res = await api('updateWallAlertRuleAnnotations', payload)

        /* eslint-disable no-console */
        console.log('[WallAlertAnnotationsEditor] UPDATE res <-', res)
        /* eslint-enable no-console */

        this.$emit('refresh-data')
        this.$notification.success({
          message: this.tOr('message.wall.alert.annotations.updated', '해결 방안 수정 완료'),
          description: this.ruleTitle || uid
        })
        this.closeAction()
      } catch (error) {
        this.$notification.error({
          message: this.tOr('message.request.failed', '요청 실패'),
          description: (error?.response?.headers?.['x-description']) || error.message,
          duration: 0
        })
      } finally {
        this.loading = false
        this.submitting = false
      }
    }
  }
}
</script>
