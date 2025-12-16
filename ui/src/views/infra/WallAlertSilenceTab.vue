<template>
  <div class="p-2">
    <div class="mb-2" style="display: flex; gap: 8px; align-items: center;">
      <a-select v-model:value="state" style="width: 160px" :options="stateOptions" />

      <!-- (변경) 일괄 종료: description prop 대신 title 슬롯 사용 -->
      <a-popconfirm
        :ok-text="$t('label.ok') || 'OK'"
        :cancel-text="$t('label.cancel') || 'Cancel'"
        @confirm="expireSelected"
      >
        <template #title>
          <div class="pc-title">{{ $t('message.confirm.expire.silence.title') || $t('message.confirm.expire.silence') || '사일런스 종료' }}</div>
          <div class="pc-desc">{{ $t('message.confirm.expire.silence.desc') || '사일런스를 종료하면 해당 규칙의 알림이 다시 활성화됩니다.' }}</div>
        </template>
        <a-button
          type="primary"
          danger
          :disabled="selectedRowKeys.length === 0 || loading"
          :loading="expiring"
        >
          {{ $t('label.action.expire.silence') }}
        </a-button>
      </a-popconfirm>

      <span style="margin-left: auto;">
        <a-tag>{{ $t('label.total') }}: {{ items.length }}</a-tag>
        <a-tag color="green">{{ $t('label.active') }}: {{ activeCount }}</a-tag>
      </span>
    </div>

    <a-table
      size="middle"
      :loading="loading"
      :columns="columns"
      :dataSource="items"
      :rowKey="record => record.id"
      :row-selection="rowSelection"
      :pagination="{ pageSize: 20, showSizeChanger: true }"
    >
      <template #bodyCell="{ column, record }">
        <template v-if="column.dataIndex === 'state'">
          <a-tag :color="stateColor(record.state)">{{ record.state }}</a-tag>
        </template>

        <!-- (유지) 시작/종료 시각: 초 단위, 'T'→공백, 'Z' 제거 -->
        <template v-else-if="column.dataIndex === 'startsAt'">
          <span>{{ fmtIsoSec(record.startsAt) }}</span>
        </template>
        <template v-else-if="column.dataIndex === 'endsAt'">
          <span>{{ fmtIsoSec(record.endsAt) }}</span>
        </template>

        <template v-else-if="column.dataIndex === 'duration'">
          <span>{{ formatDuration(record.startsAt, record.endsAt) }}</span>
        </template>

        <template v-else-if="column.dataIndex === 'actions'">
          <!-- (변경) 단건 종료: description prop 대신 title 슬롯 사용 -->
          <a-popconfirm
            :ok-text="$t('label.action.expire') || $t('label.ok') || 'OK'"
            :cancel-text="$t('label.cancel') || 'Cancel'"
            @confirm="expireOne(record)"
          >
            <template #title>
              <div class="pc-title">{{ $t('message.confirm.expire.silence.title') || $t('message.confirm.expire.silence') || '사일런스 종료' }}</div>
              <div class="pc-desc">{{ $t('message.confirm.expire.silence.desc') || '사일런스를 종료하면 해당 규칙의 알림이 다시 활성화됩니다. 즉시 알림이 재개됩니다.' }}</div>
            </template>
            <a-button size="small" danger :loading="expiring">{{ $t('label.action.expire') }}</a-button>
          </a-popconfirm>
        </template>
      </template>
    </a-table>
  </div>
</template>

<script>
import { getAPI, postAPI } from '@/api'

export default {
  name: 'WallAlertSilenceTab',
  props: {
    resource: { type: Object, default: () => ({}) },
    record: { type: Object, default: () => ({}) }
  },
  data () {
    return {
      loading: false,
      expiring: false,
      items: [],
      state: 'active', // active | pending | expired | ''(all)
      selectedRowKeys: [],
      columns: [],
      stateOptions: [
        { label: 'ACTIVE', value: 'active' },
        { label: 'PENDING', value: 'pending' },
        { label: 'EXPIRED', value: 'expired' },
        { label: 'ALL', value: '' }
      ]
    }
  },
  computed: {
    rowSelection () {
      return {
        selectedRowKeys: this.selectedRowKeys,
        onChange: (keys) => { this.selectedRowKeys = keys }
      }
    },
    activeCount () {
      return this.items.filter(x => (x.state || '').toLowerCase() === 'active').length
    }
  },
  watch: {
    resource: { deep: true, handler () { this.fetchSilences() } },
    record: { deep: true, handler () { this.fetchSilences() } },
    state () { this.fetchSilences() },
    '$i18n.locale' () { this.buildColumns() }
  },
  created () {
    this.buildColumns()
    this.fetchSilences()
  },
  methods: {
    buildColumns () {
      this.columns = [
        { key: 'id', title: this.$t('label.id'), dataIndex: 'id' },
        { key: 'state', title: this.$t('label.state'), dataIndex: 'state' },
        { key: 'startsAt', title: this.$t('label.startsat'), dataIndex: 'startsAt' },
        { key: 'endsAt', title: this.$t('label.endsat'), dataIndex: 'endsAt' },
        { key: 'duration', title: this.$t('label.duration.applied'), dataIndex: 'duration' },
        { key: 'comment', title: this.$t('label.comment'), dataIndex: 'comment' },
        { key: 'actions', title: this.$t('label.actions'), dataIndex: 'actions', width: 140 }
      ]
    },

    // ---------- 공통 유틸 ----------
    coerceArray (v) { if (Array.isArray(v)) return v; if (v && typeof v === 'object') return [v]; return [] },
    pickFirstRule (resp) {
      const r = resp?.listwallalertrulesresponse || resp?.listWallAlertRulesResponse
      if (!r) {
        const directArr = this.coerceArray(resp?.wallalertrule || resp?.wallAlertRule || resp?.lists)
        return directArr.length ? directArr[0] : null
      }
      const pools = [r.wallalertrule, r.wallAlertRule, resp?.lists]
      for (const p of pools) {
        const arr = this.coerceArray(p)
        if (arr.length) return arr[0]
      }
      return null
    },
    pickAlerts (rule) {
      if (!rule) return []
      return (
        (rule?.status && Array.isArray(rule.status.alerts) && rule.status.alerts) ||
        (Array.isArray(rule?.alerts) && rule.alerts) ||
        (Array.isArray(rule?.instances) && rule.instances) ||
        []
      )
    },
    buildMapParams (name, obj) {
      const out = {}
      if (!obj || typeof obj !== 'object') return out
      const entries = Object.entries(obj)
      for (let i = 0; i < entries.length; i += 1) {
        const [k, v] = entries[i]
        out[`${name}[${i}].key`] = k
        out[`${name}[${i}].value`] = v
      }
      return out
    },

    // (유지) 초 단위 포매터: 'YYYY-MM-DD HH:mm:ss' (T→공백, Z 제거)
    fmtIsoSec (d) {
      if (!d) return '-'
      const dt = new Date(d)
      if (isNaN(dt.getTime())) return '-'
      return dt.toISOString().slice(0, 19).replace('T', ' ')
    },

    fmt (d) {
      if (!d) return '-'
      const dt = new Date(d)
      if (isNaN(dt.getTime())) return '-'
      const pad = (n) => String(n).padStart(2, '0')
      return `${dt.getFullYear()}-${pad(dt.getMonth() + 1)}-${pad(dt.getDate())} ${pad(dt.getHours())}:${pad(dt.getMinutes())}`
    },
    formatDuration (start, end) {
      if (!start || !end) return '-'
      const s = new Date(start).getTime()
      const e = new Date(end).getTime()
      if (isNaN(s) || isNaN(e) || e < s) return '-'
      const diffMs = e - s
      const mins = Math.round(diffMs / 60000)
      const h = Math.floor(mins / 60)
      const m = mins % 60
      return h > 0 ? `${h}h ${m}m` : `${m}m`
    },
    stateColor (s) {
      const v = (s || '').toLowerCase()
      if (v === 'active') return 'green'
      if (v === 'pending') return 'gold'
      if (v === 'expired') return 'red'
      return 'default'
    },

    getCurrentRecord () {
      return this.resource && Object.keys(this.resource).length ? this.resource
        : (this.record && Object.keys(this.record).length ? this.record : {})
    },

    // ------- 라벨 우선 확보 -------
    async getLabels () {
      const rec = this.getCurrentRecord()
      const candidates0 = this.pickAlerts(rec)
      for (const a of candidates0) {
        if (a?.labels && typeof a.labels === 'object') return a.labels
      }
      if (rec?.uid) return { __alert_rule_uid__: rec.uid }
      if (rec?.ruleUid) return { __alert_rule_uid__: rec.ruleUid }
      if (rec?.metadata && rec.metadata.rule_uid) return { __alert_rule_uid__: rec.metadata.rule_uid }
      if (!rec?.id) return null

      try {
        const resp1 = await getAPI('listWallAlertRules', { id: rec.id })
        const rule1 = this.pickFirstRule(resp1)
        const alerts1 = this.pickAlerts(rule1)
        for (const a of alerts1) { if (a?.labels && typeof a.labels === 'object') return a.labels }
        const uid1 = rule1?.uid || rule1?.ruleUid || (rule1?.metadata && rule1.metadata.rule_uid)
        if (uid1) return { __alert_rule_uid__: uid1 }

        const resp2 = await getAPI('listWallAlertRules', { id: rec.id, includestatus: true })
        const rule2 = this.pickFirstRule(resp2)
        const alerts2 = this.pickAlerts(rule2)
        for (const a of alerts2) { if (a?.labels && typeof a.labels === 'object') return a.labels }
        const uid2 = rule2?.uid || rule2?.ruleUid || (rule2?.metadata && rule2.metadata.rule_uid)
        if (uid2) return { __alert_rule_uid__: uid2 }

        const resp3 = await getAPI('listWallAlertRules', { keyword: rec.id, includestatus: true })
        const r3 = resp3?.listwallalertrulesresponse || resp3?.listWallAlertRulesResponse
        let list3 = []
        if (r3) {
          const pools = [r3.wallalertrule, r3.wallAlertRule, resp3?.lists]
          for (const p of pools) list3 = list3.concat(this.coerceArray(p))
        } else {
          list3 = this.coerceArray(resp3?.wallalertrule || resp3?.wallAlertRule || resp3?.lists)
        }
        const byId = list3.find(x => x?.id === rec.id || x?.ruleId === rec.id)
        if (byId) {
          const alerts3 = this.pickAlerts(byId)
          for (const a of alerts3) { if (a?.labels && typeof a.labels === 'object') return a.labels }
          const u3 = byId?.uid || byId?.ruleUid || (byId?.metadata && byId.metadata.rule_uid)
          if (u3) return { __alert_rule_uid__: u3 }
        }
      } catch (e) {
        console.log('[WallAlertSilenceTab] getLabels failed:', e)
      }
      return null
    },

    // ---- 데이터 로드 ----
    async fetchSilences () {
      const rec = this.getCurrentRecord()
      const labelMap = await this.getLabels()

      if (!labelMap || Object.keys(labelMap).length === 0) {
        this.items = []
        this.applySilenceSummary(rec, [])
        console.log('[WallAlertSilenceTab] No labels/uid — request skipped. id=', rec?.id)
        return
      }

      this.loading = true
      try {
        const params = { ...this.buildMapParams('labels', labelMap), state: this.state || undefined }
        console.log('[WallAlertSilenceTab] >>> CALL listWallAlertSilences', params)

        const resp = await getAPI('listWallAlertSilences', params)

        const r0 = resp?.listwallalertsilencesresponse
        let rows =
          (Array.isArray(r0?.silence) && r0.silence) ||
          (Array.isArray(r0?.wallsilence) && r0.wallsilence) ||
          (Array.isArray(resp?.lists) && resp.lists) ||
          []
        if (!Array.isArray(rows)) rows = []

        if (this.state) {
          const want = String(this.state).toLowerCase()
          rows = rows.filter(r => (r.state || '').toLowerCase() === want)
        }

        this.items = rows.map(r => ({
          ...r,
          matchersText: r.matchersText || (Array.isArray(r.matchers)
            ? r.matchers.map(m => `${m.name}${m.isRegex ? '~=' : '='}${m.value}`).join(', ')
            : ''),
          startsAt: r.startsAt || r.start || r.since || null,
          endsAt: r.endsAt || r.until || null
        }))

        this.applySilenceSummary(rec, this.items)
      } catch (e) {
        console.log('[WallAlertSilenceTab] list error:', e)
      } finally {
        this.loading = false
      }
    },

    // 상세 탭 요약(사일런스 기간)
    applySilenceSummary (rec, list) {
      if (!rec) return
      const actives = list.filter(x => (x.state || '').toLowerCase() === 'active')
      if (actives.length > 0) {
        const starts = actives.map(x => new Date(x.startsAt).getTime()).filter(t => !isNaN(t))
        const ends = actives.map(x => new Date(x.endsAt).getTime()).filter(t => !isNaN(t))
        if (starts.length && ends.length) {
          const minStart = Math.min.apply(null, starts)
          const maxEnd = Math.max.apply(null, ends)
          rec.silenceStartsAt = this.fmt(minStart)
          rec.silenceEndsAt = this.fmt(maxEnd)
          rec.silencePeriod = `${this.fmt(minStart)} ~ ${this.fmt(maxEnd)} (${this.formatDuration(minStart, maxEnd)})`
        }
        return
      }
      const pendings = list
        .filter(x => (x.state || '').toLowerCase() === 'pending')
        .map(x => ({ ...x, ts: new Date(x.startsAt).getTime() }))
        .filter(x => !isNaN(x.ts))
        .sort((a, b) => a.ts - b.ts)
      if (pendings.length > 0) {
        const p = pendings[0]
        rec.silenceStartsAt = this.fmt(p.startsAt)
        rec.silenceEndsAt = this.fmt(p.endsAt)
        rec.silencePeriod = `${this.fmt(p.startsAt)} ~ ${this.fmt(p.endsAt)} (${this.formatDuration(p.startsAt, p.endsAt)})`
        return
      }
      rec.silenceStartsAt = '-'
      rec.silenceEndsAt = '-'
      rec.silencePeriod = '-'
    },

    // 만료
    async expireOne (row) {
      this.expiring = true
      try {
        await postAPI('expireWallAlertSilence', { id: row.id })
        const rec = this.getCurrentRecord()
        if (rec) {
          rec.silenceStartsAt = '-'
          rec.silenceEndsAt = '-'
          rec.silencePeriod = '-'
        }
        this.$emit('refresh-data')
        await this.fetchSilences()
      } catch (e) {
        console.log('[WallAlertSilenceTab] expire error:', e)
      } finally {
        this.expiring = false
      }
    },

    async expireSelected () {
      if (!this.selectedRowKeys.length) return
      this.expiring = true
      try {
        for (let i = 0; i < this.selectedRowKeys.length; i += 1) {
          const id = this.selectedRowKeys[i]
          await postAPI('expireWallAlertSilence', { id })
        }
        this.selectedRowKeys = []

        // 상세 레코드 낙관적 업데이트
        const rec = this.getCurrentRecord()
        if (rec) {
          rec.silenceStartsAt = '-'
          rec.silenceEndsAt = '-'
          rec.silencePeriod = '-'
        }

        this.$emit('refresh-data')
        await this.fetchSilences()
      } catch (e) {
        console.log('[WallAlertSilenceTab] bulk expire error:', e)
      } finally {
        this.expiring = false
      }
    }
  }
}
</script>

<style scoped>
.p-2 { padding: 8px; }
.mb-2 { margin-bottom: 8px; }

/* Popconfirm 텍스트 스타일(최소 추가) */
.pc-title { font-weight: 600; }
.pc-desc { margin-top: 4px; color: rgba(0, 0, 0, 0.65); white-space: normal; max-width: 360px; }
</style>
