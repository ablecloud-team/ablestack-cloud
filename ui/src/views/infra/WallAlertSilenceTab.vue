<template>
  <div class="p-2">
    <div class="mb-2" style="display: flex; gap: 8px; align-items: center;">
      <a-select v-model:value="state" style="width: 160px" :options="stateOptions" />
      <a-button :loading="loading" @click="fetchSilences">{{ $t('label.refresh') }}</a-button>
      <a-button
        type="primary"
        danger
        :disabled="selectedRowKeys.length === 0 || loading"
        :loading="expiring"
        @click="expireSelected"
      >
        {{ $t('label.action.expire.silence') }}
      </a-button>
      <span style="margin-left: auto;">
        <a-tag>{{ $t('label.total') }}: {{ items.length }}</a-tag>
        <a-tag color="green">{{ $t('label.active') }}: {{ activeCount }}</a-tag>
      </span>
    </div>

    <a-table
      size="middle"
      :loading="loading"
      :columns="columns"
      :data-source="items"
      :row-key="(r) => r.id"
      :row-selection="rowSelection"
      :pagination="{ pageSize: 20, showSizeChanger: true }"
    >
      <template #bodyCell="{ column, record }">
        <template v-if="column.dataIndex === 'state'">
          <a-tag :color="stateColor(record.state)">{{ record.state }}</a-tag>
        </template>
        <template v-else-if="column.dataIndex === 'duration'">
          <span>{{ formatDuration(record.startsAt, record.endsAt) }}</span>
        </template>
        <template v-else-if="column.dataIndex === 'actions'">
          <a-popconfirm
            :title="$t('message.confirm.expire.silence')"
            :ok-text="$t('label.ok')"
            :cancel-text="$t('label.cancel')"
            @confirm="expireOne(record)"
          >
            <a-button size="small" danger :loading="expiring">{{ $t('label.action.expire') }}</a-button>
          </a-popconfirm>
        </template>
      </template>
    </a-table>
  </div>
</template>

<script>
import { ref, computed, onMounted, watch } from 'vue'
import { api } from '@/api'

export default {
  name: 'WallAlertSilenceTab',
  props: {
    resource: { type: Object, default: null },
    record: { type: Object, default: null }
  },
  setup (props) {
    const loading = ref(false)
    const expiring = ref(false)
    const items = ref([])
    const state = ref('active') // active | pending | expired | ''(all)
    const selectedRowKeys = ref([])

    const stateOptions = [
      { label: 'active', value: 'active' },
      { label: 'pending', value: 'pending' },
      { label: 'expired', value: 'expired' },
      { label: 'all', value: '' }
    ]

    const rowSelection = computed(() => ({
      selectedRowKeys: selectedRowKeys.value,
      onChange: (keys) => { selectedRowKeys.value = keys }
    }))

    const columns = [
      { title: 'ID', dataIndex: 'id' },
      { title: 'Matchers', dataIndex: 'matchersText' },
      { title: 'State', dataIndex: 'state' },
      { title: 'Starts At', dataIndex: 'startsAt' },
      { title: 'Ends At', dataIndex: 'endsAt' },
      { title: 'Duration', dataIndex: 'duration' },
      { title: 'Created By', dataIndex: 'createdBy' },
      { title: 'Comment', dataIndex: 'comment' },
      { title: 'Actions', dataIndex: 'actions', width: 140 }
    ]

    const activeCount = computed(() => items.value.filter(x => (x.state || '').toLowerCase() === 'active').length)

    const getCurrentRecord = () => props.resource || props.record || {}

    // ---------- utils ----------
    const buildMapParams = (name, obj) => {
      const out = {}
      if (!obj || typeof obj !== 'object') return out
      const entries = Object.entries(obj)
      for (let i = 0; i < entries.length; i += 1) {
        const [k, v] = entries[i]
        out[`${name}[${i}].key`] = k
        out[`${name}[${i}].value`] = v
      }
      return out
    }

    const fmt = (d) => {
      if (!d) return '-'
      const dt = new Date(d)
      if (isNaN(dt.getTime())) return '-'
      const pad = (n) => String(n).padStart(2, '0')
      return `${dt.getFullYear()}-${pad(dt.getMonth() + 1)}-${pad(dt.getDate())} ${pad(dt.getHours())}:${pad(dt.getMinutes())}`
    }

    const formatDuration = (start, end) => {
      if (!start || !end) return '-'
      const s = new Date(start).getTime()
      const e = new Date(end).getTime()
      if (isNaN(s) || isNaN(e) || e < s) return '-'
      const mins = Math.floor((e - s) / 60000)
      const h = Math.floor(mins / 60)
      const m = mins % 60
      return h > 0 ? `${h}h ${m}m` : `${m}m`
    }

    const stateColor = (s) => {
      const v = (s || '').toLowerCase()
      if (v === 'active') return 'green'
      if (v === 'pending') return 'gold'
      if (v === 'expired') return 'red'
      return 'default'
    }

    // ---------- 공통 파서 ----------
    const coerceArray = (v) => {
      if (Array.isArray(v)) return v
      if (v && typeof v === 'object') return [v]
      return []
    }
    const pickFirstRule = (resp) => {
      const r = resp?.listwallalertrulesresponse || resp?.listWallAlertRulesResponse
      if (!r) {
        // 일부 api() 래퍼는 바로 배열을 넣어줄 수 있음
        const directArr = coerceArray(resp?.wallalertrule || resp?.wallAlertRule || resp?.lists)
        return directArr.length ? directArr[0] : null
      }
      const pools = [r.wallalertrule, r.wallAlertRule, resp?.lists]
      for (const p of pools) {
        const arr = coerceArray(p)
        if (arr.length) return arr[0]
      }
      return null
    }
    const pickAlerts = (rule) => {
      if (!rule) return []
      return (
        (rule?.status && Array.isArray(rule.status.alerts) && rule.status.alerts) ||
        (Array.isArray(rule?.alerts) && rule.alerts) ||
        (Array.isArray(rule?.instances) && rule.instances) ||
        []
      )
    }

    // ------- 라벨 우선 확보: 인스턴스 라벨 전체 → 없으면 UID만 -------
    const getLabels = async () => {
      const rec = getCurrentRecord()

      // 0) record에 이미 인스턴스 라벨이 있는 경우
      const candidates0 = pickAlerts(rec)
      for (const a of candidates0) {
        if (a?.labels && typeof a.labels === 'object') {
          return a.labels
        }
      }
      // UID가 record에 노출되어 있으면 바로 반환
      if (rec?.uid) return { __alert_rule_uid__: rec.uid }
      if (rec?.ruleUid) return { __alert_rule_uid__: rec.ruleUid }
      if (rec?.metadata && rec.metadata.rule_uid) return { __alert_rule_uid__: rec.metadata.rule_uid }
      if (!rec?.id) return null

      try {
        // 1) id로 룰 조회
        const resp1 = await api('listWallAlertRules', { id: rec.id })
        const rule1 = pickFirstRule(resp1)
        const alerts1 = pickAlerts(rule1)
        for (const a of alerts1) {
          if (a?.labels && typeof a.labels === 'object') {
            return a.labels
          }
        }
        const uid1 = rule1?.uid || rule1?.ruleUid || (rule1?.metadata && rule1.metadata.rule_uid)
        if (uid1) return { __alert_rule_uid__: uid1 }

        // 2) includestatus로 재시도
        const resp2 = await api('listWallAlertRules', { id: rec.id, includestatus: true })
        const rule2 = pickFirstRule(resp2)
        const alerts2 = pickAlerts(rule2)
        for (const a of alerts2) {
          if (a?.labels && typeof a.labels === 'object') {
            return a.labels
          }
        }
        const uid2 = rule2?.uid || rule2?.ruleUid || (rule2?.metadata && rule2.metadata.rule_uid)
        if (uid2) return { __alert_rule_uid__: uid2 }

        // 3) 폴백: keyword 검색 후 id 매칭
        const resp3 = await api('listWallAlertRules', { keyword: rec.id, includestatus: true })
        const r3 = resp3?.listwallalertrulesresponse || resp3?.listWallAlertRulesResponse
        let list3 = []
        if (r3) {
          const pools = [r3.wallalertrule, r3.wallAlertRule, resp3?.lists]
          for (const p of pools) list3 = list3.concat(coerceArray(p))
        } else {
          list3 = coerceArray(resp3?.wallalertrule || resp3?.wallAlertRule || resp3?.lists)
        }
        const byId = list3.find(x => x?.id === rec.id || x?.ruleId === rec.id)
        if (byId) {
          const alerts3 = pickAlerts(byId)
          for (const a of alerts3) {
            if (a?.labels && typeof a.labels === 'object') {
              return a.labels
            }
          }
          const u3 = byId?.uid || byId?.ruleUid || (byId?.metadata && byId.metadata.rule_uid)
          if (u3) return { __alert_rule_uid__: u3 }
        }
      } catch (e) {
        // eslint-disable-next-line no-console
        console.warn('[WallAlertSilenceTab] getLabels failed:', e)
      }
      return null
    }

    // ---- 데이터 로드 ----
    const fetchSilences = async () => {
      const rec = getCurrentRecord()
      const labelMap = await getLabels()

      if (!labelMap || Object.keys(labelMap).length === 0) {
        items.value = []
        applySilenceSummary(rec, [])
        console.warn('[WallAlertSilenceTab] No labels/uid — request skipped. id=', rec?.id)
        return
      }

      loading.value = true
      try {
        const params = { ...buildMapParams('labels', labelMap), state: state.value || undefined }
        console.debug('[WallAlertSilenceTab] >>> CALL listWallAlertSilences', params)

        const resp = await api('listWallAlertSilences', params)

        const r0 = resp?.listwallalertsilencesresponse
        let rows =
          (Array.isArray(r0?.silence) && r0.silence) ||
          (Array.isArray(r0?.wallsilence) && r0.wallsilence) ||
          (Array.isArray(resp?.lists) && resp.lists) ||
          []
        if (!Array.isArray(rows)) rows = []

        items.value = rows.map(r => ({
          ...r,
          matchersText: r.matchersText || (Array.isArray(r.matchers)
            ? r.matchers.map(m => `${m.name}${m.isRegex ? '~=' : '='}${m.value}`).join(', ')
            : ''),
          startsAt: r.startsAt || r.start || r.since || null,
          endsAt: r.endsAt || r.until || null
        }))

        applySilenceSummary(rec, items.value)
      } catch (e) {
        console.warn('[WallAlertSilenceTab] list error:', e)
      } finally {
        loading.value = false
      }
    }

    // 상세 탭 요약(사일런스 기간)
    const applySilenceSummary = (rec, list) => {
      if (!rec) return
      const actives = list.filter(x => (x.state || '').toLowerCase() === 'active')
      if (actives.length > 0) {
        const starts = actives.map(x => new Date(x.startsAt).getTime()).filter(t => !isNaN(t))
        const ends = actives.map(x => new Date(x.endsAt).getTime()).filter(t => !isNaN(t))
        if (starts.length && ends.length) {
          const minStart = Math.min.apply(null, starts)
          const maxEnd = Math.max.apply(null, ends)
          rec.silenceStartsAt = fmt(minStart)
          rec.silenceEndsAt = fmt(maxEnd)
          rec.silencePeriod = `${fmt(minStart)} ~ ${fmt(maxEnd)} (${formatDuration(minStart, maxEnd)})`
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
        rec.silenceStartsAt = fmt(p.startsAt)
        rec.silenceEndsAt = fmt(p.endsAt)
        rec.silencePeriod = `${fmt(p.startsAt)} ~ ${fmt(p.endsAt)} (${formatDuration(p.startsAt, p.endsAt)})`
        return
      }
      rec.silenceStartsAt = '-'
      rec.silenceEndsAt = '-'
      rec.silencePeriod = '-'
    }

    // 만료
    const expireOne = async (row) => {
      expiring.value = true
      try {
        await api('expireWallAlertSilence', { id: row.id })
        await fetchSilences()
      } catch (e) {
        console.warn('[WallAlertSilenceTab] expire error:', e)
      } finally {
        expiring.value = false
      }
    }

    const expireSelected = async () => {
      if (!selectedRowKeys.value.length) return
      expiring.value = true
      try {
        for (const id of selectedRowKeys.value) {
          await api('expireWallAlertSilence', { id })
        }
        selectedRowKeys.value = []
        await fetchSilences()
      } catch (e) {
        console.warn('[WallAlertSilenceTab] bulk expire error:', e)
      } finally {
        expiring.value = false
      }
    }

    onMounted(async () => { await fetchSilences() })
    watch(() => getCurrentRecord()?.id, async () => { await fetchSilences() })
    watch(state, async () => { await fetchSilences() })

    return {
      loading,
      expiring,
      items,
      state,
      stateOptions,
      selectedRowKeys,
      rowSelection,
      columns,
      activeCount,
      stateColor,
      formatDuration,
      fetchSilences,
      expireOne,
      expireSelected
    }
  }
}
</script>

<style scoped>
.p-2 { padding: 8px; }
.mb-2 { margin-bottom: 8px; }
</style>
