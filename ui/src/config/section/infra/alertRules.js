// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

import { shallowRef, defineAsyncComponent } from 'vue'
import { eligibleRules, ruleIsPaused, ruleIsSilenced } from '@/utils/wallAlertRules'

const ruleAction = (kind, api, icon, label) => ({
  api,
  permission: [api],
  icon,
  label,
  dataView: true,
  groupAction: true,
  popup: true,
  selfManagedDialog: true,
  wallRuleAction: kind,
  component: shallowRef(defineAsyncComponent(() => import('@/views/infra/WallAlertRuleActionModal.vue'))),
  show: record => eligibleRules([record], kind).length > 0,
  groupShow: records => eligibleRules(records, kind).length > 0
})

const allRulesAction = (kind, api, icon, label) => ({
  ...ruleAction(kind, api, icon, label),
  dataView: false,
  groupAction: false,
  contextOnly: true,
  menuGroup: 'ALL_RULES',
  wallRuleScope: 'all',
  show: () => true
})

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
    // 'id',
    'uid',
    'state',
    'ispaused',
    'threshold',
    'operator',
    'rulegroup',
    'kind',
    'lastEvaluation',
    'silencePeriod',
    'summary'
  ],
  dataMap: (item) => {
    const ann = item && item.annotations ? item.annotations : {}
    const summary = item && item.summary ? item.summary : (ann.summary || ann.__summary__ || ann.message || '')
    const description = item && item.description ? item.description : (ann.description || ann.__description__ || '')
    return {
      ...item,
      summary: summary || '-',
      description: description || '-',
      state: ruleIsPaused(item) ? 'stopped' : 'running',
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
  }, {
    name: 'solution',
    component: shallowRef(defineAsyncComponent(() => import('@/views/infra/WallAlertSolutionTab.vue')))
  }],
  actions: [
    {
      api: 'updateWallAlertRuleThreshold',
      permission: ['updateWallAlertRuleThreshold'],
      icon: 'edit-outlined',
      label: 'label.update.threshold',
      message: 'message.action.update.threshold',
      dataView: true,
      groupAction: false,
      popup: true,
      args: ['uid'],
      component: shallowRef(defineAsyncComponent(() => import('@/views/infra/WallThresholdEditor.vue')))
    },
    ruleAction('pause', 'pauseWallAlertRule', 'pause-circle-outlined', 'label.alert.rule.pause'),
    ruleAction('resume', 'pauseWallAlertRule', 'play-circle-outlined', 'label.action.resume.alert.rule'),
    ruleAction('silence', 'createWallAlertSilence', 'sound-outlined', 'label.action.silence'),
    {
      ...ruleAction('unsilence', 'expireWallAlertSilence', 'close-circle-outlined', 'label.action.expire.silence'),
      permission: ['expireWallAlertSilence', 'listWallAlertSilences'],
      dataView: false,
      show: () => false,
      groupShow: (records, getters) => 'listWallAlertSilences' in getters.apis && records.some(ruleIsSilenced)
    },
    allRulesAction('silence', 'createWallAlertSilence', 'sound-outlined', 'label.wall.rules.silence.all'),
    {
      ...allRulesAction('unsilence', 'expireWallAlertSilence', 'close-circle-outlined', 'label.wall.rules.unsilence.all'),
      permission: ['expireWallAlertSilence', 'listWallAlertSilences'],
      show: (_record, getters) => 'listWallAlertSilences' in getters.apis
    },
    allRulesAction('pause', 'pauseWallAlertRule', 'pause-circle-outlined', 'label.wall.rules.pause.all'),
    allRulesAction('resume', 'pauseWallAlertRule', 'play-circle-outlined', 'label.wall.rules.resume.all'),
    {
      api: 'expireWallAlertSilence',
      permission: ['expireWallAlertSilence'],
      icon: 'close-circle-outlined',
      label: 'label.action.expire.silence',
      message: 'message.action.expire.silence',
      dataView: true,
      groupAction: false,
      popup: true,
      // 기존 탭 컴포넌트를 모달로 띄워서 만료 실행 (새 파일 필요 없음)
      component: shallowRef(
        defineAsyncComponent(() => import('@/views/infra/WallAlertSilenceTab.vue'))
      ),
      show: record => ruleIsSilenced(record)
    }
  ]
}
