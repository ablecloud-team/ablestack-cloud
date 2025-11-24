import { shallowRef, defineAsyncComponent } from 'vue'

export default {
  name: 'alertRules',
  title: 'label.alertRules',
  icon: 'SoundOutlined',
  permission: ['listWallAlertRules'],
  resourceType: 'WallAlertRule',
  filters: () => {
    const filters = ['alerting', 'OK', 'pending', 'nodata']
    return filters
  },
  searchFilters: ['name', 'state', 'kind'],
  columns: () => [
    'name', // 규칙명
    'ispaused',
    'silenceEndsAt',
    { field: 'state', customTitle: 'alert.state' },
    'threshold',
    'rulegroup',
    'kind', // HOST/STORAGE/CLOUD/USER
    'lastEvaluation'
  ],
  details: [
    'name',
    'id',
    'uid',
    'state',
    'ispaused',
    'threshold',
    'operator',
    'rulegroup',
    'kind',
    'lastEvaluation',
    'silencePeriod',
    'summary',
    'description'
  ],
  dataMap: (item) => {
    const ann = item && item.annotations ? item.annotations : {}
    const summary = item && item.summary ? item.summary : (ann.summary || ann.__summary__ || ann.message || '')
    const description = item && item.description ? item.description : (ann.description || ann.__description__ || '')
    return {
      ...item,
      summary: summary || '-',
      description: description || '-',
      state: item && item.ispaused ? 'stopped' : 'running',
      silenceStartsAt: item && item.silenceStartsAt ? item.silenceStartsAt : '-',
      silenceEndsAt: item && item.silenceEndsAt ? item.silenceEndsAt : '-',
      silencePeriod: item && item.silencePeriod ? item.silencePeriod : '-'
    }
  },
  tabs: [{
    name: 'details',
    component: shallowRef(defineAsyncComponent(() => import('@/components/view/DetailsTab.vue')))
  }, {
    name: 'silence',
    component: shallowRef(defineAsyncComponent(() => import('@/views/infra/WallAlertSilenceTab.vue')))
  }],
  actions: [
    {
      api: 'updateWallAlertRuleThreshold',
      permission: ['updateWallAlertRuleThreshold'],
      icon: 'edit-outlined',
      label: 'label.update.threshold',
      message: 'message.action.update.threshold',
      dataView: true,
      groupAction: true,
      popup: true,
      args: ['uid'],
      component: shallowRef(defineAsyncComponent(() => import('@/views/infra/WallThresholdEditor.vue')))
    },
    {
      api: 'pauseWallAlertRule',
      permission: ['pauseWallAlertRule'],
      icon: 'pause-circle-outlined',
      label: 'label.alert.rule.pause',
      message: 'message.confirm.pause.rule.title',
      dataView: true,
      groupAction: true,
      popup: true,
      component: shallowRef(
        defineAsyncComponent(() => import('@/views/infra/RulePauseModal.vue'))
      ),
      show: (record, selection, records) => {
        const notPaused = (rec) => rec && rec.isPaused !== true
        if (record) return notPaused(record)
        if (Array.isArray(selection) && selection.length && Array.isArray(records)) {
          return selection.some(id => {
            const rec = records.find(r => r.id === id || r.uid === id)
            return notPaused(rec)
          })
        }
        return true
      }
    },
    {
      api: 'pauseWallAlertRule',
      icon: 'play-circle-outlined',
      label: 'label.action.resume.alert.rule',
      message: 'message.confirm.resume.rule.desc',
      dataView: true,
      groupAction: true,
      popup: false,
      args: ['id', 'paused'],
      mapping: {
        id: { value: (record) => record.id },
        paused: { value: () => false }
      },
      groupMap: (selection, _values, records) => {
        return selection.map(x => {
          const rec = records.find(r => r.id === x)
          return { id: rec?.id || x, paused: false }
        })
      },
      show: (record) => record && record.isPaused === true
    },
    {
      api: 'createWallAlertSilence',
      permission: ['createWallAlertSilence'],
      icon: 'sound-outlined',
      label: 'label.action.silence',
      message: 'message.action.silence',
      dataView: true,
      // 중복 생성 방지: 리스트의 '그룹 액션' 경로 비활성화(행 액션만 사용)
      groupAction: false,
      popup: true, // 모달 사용(모달이 직접 API 호출)
      component: shallowRef(
        defineAsyncComponent(() => import('@/views/infra/RuleSilenceModal.vue'))
      ),
      show: (record, selection, records) => {
        const active = (rec) => {
          const end = rec?.silenceEndsAt
          const t = end ? Date.parse(end) : 0
          return Number.isFinite(t) && t > Date.now()
        }
        if (record) return !active(record)
        if (Array.isArray(selection) && selection.length && Array.isArray(records)) {
          return selection.some(id => {
            const rec = records.find(r => r.id === id || r.uid === id)
            return rec && !active(rec)
          })
        }
        return true
      }
    },
    {
      api: 'expireWallAlertSilence',
      permission: ['expireWallAlertSilence'],
      icon: 'close-circle-outlined',
      label: 'label.action.expire.silence',
      message: 'message.action.expire.silence',
      dataView: true,
      groupAction: true,
      popup: true,
      // 기존 탭 컴포넌트를 모달로 띄워서 만료 실행 (새 파일 필요 없음)
      component: shallowRef(
        defineAsyncComponent(() => import('@/views/infra/WallAlertSilenceTab.vue'))
      ),
      show: (record, selection, records) => {
        const active = (rec) => {
          const end = rec && rec.silenceEndsAt
          const t = end ? Date.parse(end) : 0
          return Number.isFinite(t) && t > Date.now()
        }
        if (record) return active(record)
        if (Array.isArray(selection) && selection.length && Array.isArray(records)) {
          return selection.some(id => {
            const rec = records.find(r => r.id === id || r.uid === id)
            return rec && active(rec)
          })
        }
        return false
      }
    }
  ]
}
