<template>
  <a-card :bordered="false">
    <div class="flex justify-between items-center mb-3">
      <div class="text-lg font-semibold">Wall Alert Rules</div>
      <a-space>
        <a-input-search v-model="keyword" placeholder="이름/설명 검색" @search="reload" allowClear />
        <a-button @click="reload" :loading="loading">새로고침</a-button>
      </a-space>
    </div>

    <a-table
      :data-source="rows"
      :columns="antdColumns"
      :row-key="r => r.id"
      :loading="loading"
      :pagination="{ pageSize: 20 }"
    />
  </a-card>
</template>

<script>
export default {
  name: 'WallAlertRules',
  data () {
    return {
      loading: false,
      keyword: '',
      rows: []
    }
  },
  computed: {
    // 테이블 컬럼 정의입니다.
    antdColumns () {
      return [
        {
          title: '상태',
          dataIndex: 'state',
          width: 110,
          customRender: ({ text }) => {
            const state = text || 'OK'
            const color = state === 'ALERTING' ? 'red' : (state === 'PENDING' ? 'orange' : 'green')
            return <a-tag color={color}>{state}</a-tag>
          }
        },
        { title: '이름', dataIndex: 'name' },
        { title: '종류', dataIndex: 'kind', width: 120 },
        { title: '연산자', dataIndex: 'operator', width: 90 },
        { title: '임계값', dataIndex: 'threshold', width: 90, align: 'right' },
        { title: 'for', dataIndex: 'for', width: 90 },
        { title: '발생 수', dataIndex: 'firingCount', width: 100, align: 'right' },
        { title: '최근 발생', dataIndex: 'lastTriggeredAt', width: 180 },
        { title: '그룹', dataIndex: 'rulegroup', width: 160, ellipsis: true },
        {
          title: '패널',
          dataIndex: 'panel',
          width: 90,
          customRender: ({ record }) => {
            const uid = record.annotations?.__dashboardUid__
            const pid = record.annotations?.__panelId__
            if (!uid || !pid) return '-'
            // 라우팅 규칙이 다를 수 있어 기본 a 링크로 둡니다.
            const href = `#/wall/d/${uid}?viewPanel=${pid}`
            return <a target="_blank" href={href}>보기</a>
          }
        }
      ]
    }
  },
  mounted () {
    this.reload()
  },
  methods: {
    // 목록 새로고침입니다.
    async reload () {
      this.loading = true
      try {
        // 1) 규칙 조회입니다.
        const ruleRes = await this.$api('listWallAlertRules', {
          keyword: this.keyword || undefined
        })
        const rules = this._extractRules(ruleRes).map(this._normalizeRule)

        if (rules.length === 0) {
          this.rows = []
          return
        }

        // 2) 규칙 id를 콤마로 전달해 인스턴스를 배치 조회합니다.
        const ids = rules.map(r => r.id).join(',')
        const instRes = await this.$api('listWallAlertInstances', { ruleids: ids })
        const instances = this._extractInstances(instRes)

        // 3) 인스턴스 집계 후 규칙과 합칩니다.
        const agg = this._aggregateByRuleId(instances)
        this.rows = rules.map(r => ({
          ...r,
          state: agg[r.id]?.state || 'OK',
          firingCount: agg[r.id]?.firingCount || 0,
          lastTriggeredAt: agg[r.id]?.lastTriggeredAt || '-'
        }))
      } catch (e) {
        console.error(e)
        this.$message.error('알림 규칙을 불러오는 중 오류가 발생했습니다')
      } finally {
        this.loading = false
      }
    },

    // === 유틸리티들입니다. ===============================================

    // 규칙 응답 파서입니다(방어적).
    _extractRules (res) {
      const r = res?.listwallalertrulesresponse
      if (!r) return []
      // 환경에 따라 키가 다를 수 있어 가장 그럴듯한 배열 키들을 시도합니다.
      return (
        r.rule || r.rules || r.wallalertrule || r.null || []
      )
    },

    // 규칙 표준화입니다.
    _normalizeRule (r) {
      return {
        id: r.id,
        name: r.name,
        for: r.for || '-',
        threshold: r.threshold,
        operator: r.operator,
        rulegroup: r.rulegroup || r.group || '-',
        kind: r.kind || r.labels?.kind || '-',
        annotations: r.annotations || {}
      }
    },

    // 인스턴스 응답 파서입니다(방어적).
    _extractInstances (res) {
      // 예: { listwallalertinstancesresponse: { count, instance: [...] } }
      const k = res?.listwallalertinstancesresponse || res?.listalertinstancesresponse || res
      if (!k) return []
      return k.instance || k.instances || k.alerts || []
    },

    // rule id 기준 집계입니다.
    _aggregateByRuleId (instances) {
      const map = {}
      for (const it of instances) {
        // rule id 후보들을 순서대로 시도합니다.
        const rid =
          it.ruleid ||
          it.ruleId ||
          it.labels?.ruleId ||
          it.labels?.ruleid ||
          it.labels?.ruleUid || // 혹시 uid만 내려오는 경우 대비
          it.uid
        if (!rid) continue

        if (!map[rid]) {
          map[rid] = { firingCount: 0, pendingCount: 0, lastTriggeredAt: null }
        }

        const state = String(it.status?.state || it.state || '').toUpperCase()
        const startsAt = it.startsAt || it.starts_at || it.activeAt

        if (state === 'FIRING' || state === 'ACTIVE') {
          map[rid].firingCount += 1
          map[rid].lastTriggeredAt = this._max(map[rid].lastTriggeredAt, startsAt)
        } else if (state === 'PENDING') {
          map[rid].pendingCount += 1
        }
      }

      // 최종 상태 산정입니다.
      for (const rid of Object.keys(map)) {
        const v = map[rid]
        v.state = v.firingCount > 0 ? 'ALERTING' : (v.pendingCount > 0 ? 'PENDING' : 'OK')
        v.lastTriggeredAt = v.lastTriggeredAt ? this._fmt(v.lastTriggeredAt) : null
      }
      return map
    },

    _max (a, b) {
      if (!a) return b
      if (!b) return a
      return new Date(a) > new Date(b) ? a : b
    },

    _fmt (iso) {
      try {
        const d = new Date(iso)
        const yyyy = d.getFullYear()
        const MM = String(d.getMonth() + 1).padStart(2, '0')
        const dd = String(d.getDate()).padStart(2, '0')
        const HH = String(d.getHours()).padStart(2, '0')
        const mm = String(d.getMinutes()).padStart(2, '0')

        return `${yyyy}-${MM}-${dd} ${HH}:${mm}`
      } catch {
        return iso
      }
    }
  }
}
</script>

<style>
/* Ant Design Vue 모달 크롬은 컴포넌트 스코프 밖에 렌더링되므로 전역에서 오버라이드합니다. */
.ant-modal .ant-modal-close { top: -10px;}
.ant-modal .ant-modal-close .ant-modal-close-x { padding-left: 20px; top: -10px;}
</style>
