<!-- WallAlertSolutionTab.vue (보기 모드에서 description을 마크다운 렌더링) -->
<template>
  <div class="p-2">
    <a-spin :spinning="loading">
      <div class="solution-top">
        <div class="solution-top__row1">
          <div class="solution-top__metaLine">
            <ClockCircleOutlined class="solution-top__metaIcon" />
            <span class="solution-top__metaText">{{ updatedAtText }}</span>
          </div>

          <div class="solution-top__actions">
            <a-button
              v-if="!editing"
              type="primary"
              :disabled="loading"
              @click="startEdit"
            >
              {{ $t('label.edit') || '편집' }}
            </a-button>

            <a-button
              v-else
              :disabled="saving"
              @click="cancelEdit"
            >
              {{ $t('label.cancel') || '취소' }}
            </a-button>

            <a-popconfirm
              v-if="editing"
              :ok-text="$t('label.ok') || 'OK'"
              :cancel-text="$t('label.cancel') || 'Cancel'"
              @confirm="save"
            >
              <template #title>
                <div class="pc-title">{{ $t('message.confirm.save.solution.title') || '해결 방안 저장' }}</div>
                <div class="pc-desc">{{ $t('message.confirm.save.solution.desc') || '저장하면 Wall(경보 규칙) annotations에 반영됩니다.' }}</div>
              </template>

              <a-button
                type="primary"
                :loading="saving"
                :disabled="saving"
              >
                {{ $t('label.save') || '저장' }}
              </a-button>
            </a-popconfirm>
          </div>
        </div>
      </div>

      <div class="panel">
        <!-- 보기 모드 -->
        <div v-show="!editing">
          <div v-if="!hasAnyText" class="empty">
            {{ $t('message.solution.empty') || '등록된 해결 방안이 없습니다. 편집을 눌러 내용을 추가할 수 있습니다.' }}
          </div>

          <div v-else class="viewer">
            <div v-if="solutionSummary" class="block">
              <div class="block-title">{{ $t('label.summary') || '요약' }}</div>
              <pre class="plain">{{ solutionSummary }}</pre>
            </div>

            <div v-if="solutionDescription" class="block">
              <div class="block-title">{{ $t('label.description') || '설명' }}</div>

              <!-- ✅ 여기만 바뀜: 마크다운 렌더링 출력 -->
              <div
                class="md-viewer"
                v-html="renderedSolutionHtml"
              />
            </div>
          </div>
        </div>

        <!-- 편집 모드 (그대로 유지) -->
        <div v-show="editing">
          <a-form layout="vertical">
            <a-form-item :label="$t('label.summary') || '요약'">
              <a-textarea
                :value="draft.summary"
                :auto-size="{ minRows: 2, maxRows: 6 }"
                allow-clear
                :placeholder="$t('message.solution.summary.placeholder') || '요약을 입력합니다.'"
                @input="onSummaryInput"
              />
            </a-form-item>

            <a-form-item :label="$t('label.description') || '설명(마크다운)'">
              <a-textarea
                :value="draft.description"
                :auto-size="{ minRows: 14, maxRows: 34 }"
                allow-clear
                :placeholder="$t('message.solution.description.placeholder') || '마크다운으로 해결 방안을 입력합니다.'"
                @input="onDescriptionInput"
              />
            </a-form-item>
          </a-form>

          <div class="hint">
            {{ $t('message.solution.hint') || '저장 시 요약/설명이 Wall(경보 규칙) annotations에 저장됩니다.' }}
          </div>

          <div class="preview-wrap">
            <div class="preview-title">{{ $t('label.preview') || '미리보기' }}</div>

            <div
              v-if="renderedDescriptionHtml"
              class="md-preview"
              v-html="renderedDescriptionHtml"
            />
            <div v-else class="empty-preview">
              {{ $t('message.solution.preview.empty') || '미리볼 내용이 없습니다.' }}
            </div>
          </div>
        </div>
      </div>
    </a-spin>
  </div>
</template>

<script>
import { api } from '@/api'
import { ClockCircleOutlined } from '@ant-design/icons-vue'
import MarkdownIt from 'markdown-it'
import DOMPurify from 'dompurify'

const md = new MarkdownIt({
  html: false,
  breaks: true,
  linkify: false
})

export default {
  name: 'WallAlertSolutionTab',
  props: {
    resource: { type: Object, default: () => ({}) },
    record: { type: Object, default: () => ({}) }
  },
  components: {
    ClockCircleOutlined
  },
  data () {
    return {
      loading: false,
      saving: false,
      editing: false,

      solutionSummary: '',
      solutionDescription: '',

      draft: {
        summary: '',
        description: ''
      },
      origin: {
        summary: '',
        description: ''
      },

      updatedAtText: '-'
    }
  },
  computed: {
    ruleUid () {
      const r = this.getCurrentRecord()
      const x = (r && r.rule) ? r.rule : r
      return String((x && (x.uid || x.id)) || '')
    },
    hasAnyText () {
      return Boolean((this.solutionSummary || '').trim() || (this.solutionDescription || '').trim())
    },

    // 편집 프리뷰(기존 그대로)
    renderedDescriptionHtml () {
      const raw = (this.draft.description || '').trim()
      if (!raw) return ''
      const html = md.render(raw)
      return DOMPurify.sanitize(html, { USE_PROFILES: { html: true } })
    },

    // ✅ 보기 모드 렌더링(새로 추가): 저장된 solutionDescription을 마크다운 렌더링
    renderedSolutionHtml () {
      const raw = (this.solutionDescription || '').trim()
      if (!raw) return ''
      const html = md.render(raw)
      return DOMPurify.sanitize(html, { USE_PROFILES: { html: true } })
    }
  },
  watch: {
    resource: { immediate: true, deep: false, handler () { this.initAndRefresh() } },
    record: { immediate: true, deep: false, handler () { this.initAndRefresh() } }
  },
  methods: {
    onSummaryInput (e) {
      const v = e && e.target ? e.target.value : ''
      this.draft.summary = v
    },
    onDescriptionInput (e) {
      const v = e && e.target ? e.target.value : ''
      this.draft.description = v
    },

    initAndRefresh () {
      const cur = this.getCurrentRecord()
      this.initFromAny(cur)

      if (!this.editing) {
        this.refreshFromServer()
      }
    },

    takeFirst () {
      for (let i = 0; i < arguments.length; i += 1) {
        const v = arguments[i]
        if (v != null && v !== '') return v
      }
      return ''
    },

    normalizeText (v) {
      if (v == null) return ''
      const s = String(v).replace(/\r\n/g, '\n').replace(/\r/g, '\n').trim()
      if (!s || s === '-') return ''
      return s
    },

    extractSummary (it) {
      const r = (it && it.rule) ? it.rule : it
      const a = (it && Array.isArray(it.alerts) && it.alerts.length) ? it.alerts[0] : null

      const annR = (r && r.annotations) ? r.annotations : {}
      const annA = (a && a.annotations) ? a.annotations : {}

      const summary = (r && r.summary)
        ? r.summary
        : this.takeFirst(
          annR.summary,
          annR.__summary__,
          annR.message,
          annA.summary,
          annA.__summary__,
          annA.message,
          ''
        )

      return this.normalizeText(summary)
    },

    extractDescription (it) {
      const r = (it && it.rule) ? it.rule : it
      const a = (it && Array.isArray(it.alerts) && it.alerts.length) ? it.alerts[0] : null

      const annR = (r && r.annotations) ? r.annotations : {}
      const annA = (a && a.annotations) ? a.annotations : {}

      const description = (r && r.description)
        ? r.description
        : this.takeFirst(
          annR.description,
          annR.__description__,
          annA.description,
          annA.__description__,
          ''
        )

      return this.normalizeText(description)
    },

    initFromAny (it) {
      const s = this.extractSummary(it || {})
      const d = this.extractDescription(it || {})

      if (s) this.solutionSummary = s
      if (d) this.solutionDescription = d

      if (!this.editing) {
        if (s) this.draft.summary = s
        if (d) this.draft.description = d
        if (s) this.origin.summary = s
        if (d) this.origin.description = d
      }
    },

    getCurrentRecord () {
      if (this.resource && Object.keys(this.resource).length) return this.resource
      if (this.record && Object.keys(this.record).length) return this.record
      return {}
    },

    coerceArray (v) {
      if (Array.isArray(v)) return v
      if (v && typeof v === 'object') return [v]
      return []
    },

    pickFirstRule (resp) {
      const r = resp && (resp.listwallalertrulesresponse || resp.listWallAlertRulesResponse)
      if (!r) {
        const directArr = this.coerceArray(resp && (resp.wallalertrule || resp.wallAlertRule || resp.lists))
        return directArr.length ? directArr[0] : null
      }

      const pools = [r.wallalertrule, r.wallAlertRule, resp && resp.lists]
      for (let i = 0; i < pools.length; i += 1) {
        const arr = this.coerceArray(pools[i])
        if (arr.length) return arr[0]
      }
      return null
    },

    nowText () {
      const dt = new Date()
      const pad = (n) => String(n).padStart(2, '0')
      return `${dt.getFullYear()}-${pad(dt.getMonth() + 1)}-${pad(dt.getDate())} ${pad(dt.getHours())}:${pad(dt.getMinutes())}`
    },

    async refreshFromServer () {
      const uid = String(this.ruleUid || '')
      if (!uid) return

      this.loading = true
      try {
        const resp = await api('listWallAlertRules', { uid })
        const rule = this.pickFirstRule(resp)

        if (rule) {
          const s = this.extractSummary(rule)
          const d = this.extractDescription(rule)

          if (s) this.solutionSummary = s
          if (d) this.solutionDescription = d

          if (!this.editing) {
            if (s) this.draft.summary = s
            if (d) this.draft.description = d
            if (s) this.origin.summary = s
            if (d) this.origin.description = d
          }

          this.updatedAtText = (rule && (rule.updatedAt || rule.lastUpdatedAt))
            ? String(rule.updatedAt || rule.lastUpdatedAt)
            : this.nowText()
        } else {
          this.updatedAtText = this.nowText()
        }
      } catch (e) {
        console.log('[WallAlertSolutionTab] refreshFromServer error:', e)
        this.updatedAtText = '-'
      } finally {
        this.loading = false
      }
    },

    startEdit () {
      const cur = this.getCurrentRecord()

      const s = this.takeFirst(
        (this.origin.summary || '').trim(),
        (this.solutionSummary || '').trim(),
        this.extractSummary(cur),
        (this.draft.summary || '').trim(),
        ''
      )

      const d = this.takeFirst(
        (this.origin.description || '').trim(),
        (this.solutionDescription || '').trim(),
        this.extractDescription(cur),
        (this.draft.description || '').trim(),
        ''
      )

      this.editing = true
      this.draft.summary = s
      this.draft.description = d
      this.origin.summary = s
      this.origin.description = d
    },

    cancelEdit () {
      this.editing = false
      this.draft.summary = this.solutionSummary || ''
      this.draft.description = this.solutionDescription || ''
    },

    isDirty () {
      return this.draft.summary !== this.origin.summary || this.draft.description !== this.origin.description
    },

    async save () {
      const uid = String(this.ruleUid || '')
      if (!uid) return

      if (!this.isDirty()) {
        this.editing = false
        return
      }

      this.saving = true
      try {
        await api(
          'updateWallAlertRuleAnnotations',
          { uid },
          'post',
          {
            summary: this.draft.summary,
            description: this.draft.description
          }
        )

        this.solutionSummary = this.draft.summary
        this.solutionDescription = this.draft.description
        this.origin.summary = this.draft.summary
        this.origin.description = this.draft.description
        this.updatedAtText = this.nowText()
        this.editing = false

        this.$emit('refresh-data')
        this.$notification?.success?.({
          message: this.$t('message.wall.alert.annotations.updated') || '해결 방안 수정 완료',
          description: uid
        })
      } catch (e) {
        this.$notification?.error?.({
          message: this.$t('message.request.failed') || '요청 실패',
          description: (e && e.response && e.response.headers && e.response.headers['x-description'])
            ? e.response.headers['x-description']
            : (e && e.message ? e.message : ''),
          duration: 0
        })
      } finally {
        this.saving = false
      }
    }
  }
}
</script>

<style scoped>
.p-2 { padding: 8px; }

.solution-top { margin-bottom: 12px; }
.solution-top__row1 { display: flex; align-items: center; gap: 12px; }
.solution-top__metaLine { display: flex; align-items: center; gap: 10px; min-width: 0; color: rgba(0, 0, 0, 0.55); font-size: 12px; line-height: 18px; }
.solution-top__metaIcon { font-size: 12px; color: rgba(0, 0, 0, 0.45); }
.solution-top__metaSep { color: rgba(0, 0, 0, 0.25); }
.solution-top__metaText { color: rgba(0, 0, 0, 0.55); white-space: nowrap; }
.solution-top__actions { display: inline-flex; align-items: center; gap: 8px; margin-left: auto; flex-shrink: 0; }
.mono { font-family: monospace; }

.panel { border: 1px solid #f0f0f0; border-radius: 10px; padding: 12px; background: #ffffff; }
.empty { padding: 12px; color: #8c8c8c; border: 1px dashed #d9d9d9; border-radius: 10px; background: #fafafa; }
.viewer { display: flex; flex-direction: column; gap: 12px; }
.block-title { font-weight: 600; margin-bottom: 8px; }

.plain { margin: 0; white-space: pre-wrap; word-break: break-word; font-family: inherit; border: 1px solid #f0f0f0; border-radius: 10px; padding: 10px; background: #fafafa; }

.hint { margin-top: 10px; color: #8c8c8c; }

.preview-wrap { margin-top: 12px; border-top: 1px solid #f0f0f0; padding-top: 10px; }
.preview-title { font-weight: 600; margin-bottom: 8px; }

.md-preview { border: 1px solid #f0f0f0; border-radius: 10px; padding: 10px; background: #fafafa; word-break: break-word; }
.empty-preview { padding: 10px; border: 1px dashed #d9d9d9; border-radius: 10px; background: #fafafa; color: #8c8c8c; }

/* ✅ 보기 모드 마크다운 렌더 영역 */
.md-viewer { border: 1px solid #f0f0f0; border-radius: 10px; padding: 10px; background: #fafafa; word-break: break-word; }

.pc-title { font-weight: 600; }
.pc-desc { margin-top: 4px; color: rgba(0, 0, 0, 0.65); white-space: normal; max-width: 360px; }
</style>
