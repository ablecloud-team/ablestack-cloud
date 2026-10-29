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

<template>
  <a-modal
    :visible="true"
    :title="$t(currentAction.label)"
    :footer="null"
    :mask-closable="false"
    :closable="!running"
    :keyboard="!running"
    :width="kind === 'resume' ? 600 : 760"
    :body-style="kind === 'resume' ? undefined : { minHeight: kind === 'pause' || kind === 'silence' ? '400px' : undefined, padding: '28px 32px' }"
    centered
    @cancel="close"
  >
    <a-spin :spinning="fetching || running">
      <a-alert
        v-if="loadFailed"
        type="error"
        show-icon
        :message="$t(kind === 'unsilence' ? 'message.wall.silences.load.failed' : 'message.wall.rules.load.failed')"
      >
        <template #action><a-button size="small" @click="loadTargets">{{ $t('label.refresh') }}</a-button></template>
      </a-alert>
      <template v-else>
        <a-alert
          :type="isAll ? 'warning' : 'info'"
          show-icon
          :message="$t(kind === 'unsilence' ? (isAll ? 'message.wall.silences.scope.all' : 'message.wall.silences.scope.selected') : isAll ? 'message.wall.rules.scope.all' : 'message.wall.rules.scope.selected', { count: kind === 'unsilence' ? displayTargets.length : rules.length, targets: targets.length })"
          :description="kind === 'unsilence' ? $t('message.confirm.expire.silence.desc') : kind === 'resume' ? $t('message.wall.rules.effect.resume') : undefined"
        />
        <a-alert
          v-if="kind === 'pause' || kind === 'silence'"
          class="rule-impact"
          type="warning"
          show-icon
          :message="$t('label.' + kind + '.infoTitle')"
        >
          <template #description>
            <ul class="bullet">
              <li v-for="index in (kind === 'pause' ? 3 : 4)" :key="index">
                {{ $t('message.' + kind + '.info.' + index) }}
              </li>
            </ul>
          </template>
        </a-alert>
        <p v-if="rules.length > targets.length" class="rule-note">
          {{ $t('message.wall.rules.skipped', { count: rules.length - targets.length }) }}
        </p>
        <p v-if="!fetching && !targets.length" class="rule-note" role="status">{{ $t(kind === 'unsilence' ? 'message.wall.silences.no.targets' : 'message.wall.rules.no.targets') }}</p>
        <ul v-if="targets.length" class="rule-targets">
          <li v-for="target in displayTargets" :key="target.key">
            {{ target.name }}<template v-if="target.count > 1"> ({{ $t('label.wall.silences.count', { count: target.count }) }})</template>
          </li>
        </ul>
        <a-form layout="vertical">
          <a-form-item v-if="kind === 'silence'" :label="$t('label.silenceperiod.select')">
            <a-select v-model:value="duration" :disabled="running || attempted" style="width: 100%">
              <a-select-option v-for="option in durations" :key="option.value" :value="option.value">
                {{ $t(option.label, [option.amount]) }}
                <span class="option-sep"> — </span>
                <span class="option-sub">{{ $t('message.silence.until', { time: endTimeText(option.value) }) }}</span>
              </a-select-option>
              <a-select-option value="custom">{{ $t('label.silence.end.custom') }}</a-select-option>
            </a-select>
          </a-form-item>
          <a-form-item
            v-if="kind === 'silence' && duration === 'custom'"
            :label="$t('label.end.date.and.time')"
            :validate-status="timingError ? 'error' : undefined"
            :help="timingError || undefined"
          >
            <a-date-picker
              v-model:value="customEndsAt"
              show-time
              format="YYYY-MM-DD HH:mm:ss"
              :locale="datePickerLocale"
              :disabled-date="disabledEndDate"
              :disabled="running || attempted"
              :placeholder="$t('message.select.end.date.and.time')"
              @change="timingError = ''"
            />
          </a-form-item>
          <p v-else-if="kind === 'silence' && timingError" role="alert">{{ timingError }}</p>
          <a-checkbox v-model:checked="acknowledged" :disabled="running || !targets.length">
            {{ $t(kind === 'pause' ? 'label.pause.confirm.ack' : kind === 'silence' ? 'label.silence.confirm.ack' : 'message.wall.rules.confirm') }}
          </a-checkbox>
        </a-form>
        <a-progress v-if="running" :percent="progress" :status="'active'" />
        <a-alert
          v-if="failures.length"
          class="rule-result"
          type="error"
          show-icon
          :message="$t('message.wall.rules.partial.failure', { success: successCount, failed: failures.length })"
        >
          <template #description>
            <ul class="rule-failures">
              <li v-for="failure in failures" :key="ruleUid(failure.rule)">
                {{ failure.rule.name || ruleUid(failure.rule) }}
              </li>
            </ul>
          </template>
        </a-alert>
      </template>
      <div class="rule-actions">
        <a-button :disabled="running" @click="close">{{ $t('label.close') }}</a-button>
        <a-button
          type="primary"
          :danger="kind === 'pause'"
          :loading="running"
          :disabled="fetching || loadFailed || !acknowledged || !targets.length"
          @click="submit"
        >
          {{ $t(attempted ? (kind === 'unsilence' ? 'label.wall.silences.retry.failed' : 'label.wall.rules.retry.failed') : currentAction.label) }}
        </a-button>
      </div>
    </a-spin>
  </a-modal>
</template>

<script>
import dayjs from 'dayjs'
import 'dayjs/locale/ko'
import koKR from 'ant-design-vue/es/date-picker/locale/ko_KR'
import enUS from 'ant-design-vue/es/date-picker/locale/en_US'
import { getAPI, postAPI } from '@/api'
import eventBus from '@/config/eventBus'
import { applyRuleAction, eligibleRules, fetchActiveSilences, fetchAllRules, ruleIsSilenced, ruleUid, uniqueRules } from '@/utils/wallAlertRules'

export default {
  name: 'WallAlertRuleActionModal',
  emits: ['refresh-data', 'close-action'],
  props: {
    resource: { type: Object, default: () => ({}) },
    selectedItems: { type: Array, default: () => [] },
    currentAction: { type: Object, required: true }
  },
  data () {
    return {
      fetching: false,
      loadFailed: false,
      running: false,
      acknowledged: false,
      attempted: false,
      rules: [],
      failures: [],
      successCount: 0,
      completed: 0,
      batchSize: 0,
      duration: 30,
      customEndsAt: null,
      silenceEndsAt: null,
      timingError: '',
      durations: [
        { value: 30, label: 'label.silence.duration.minutes', amount: 30 },
        ...[1, 2, 6, 12, 24].map(n => ({ value: n * 60, label: 'label.silence.duration.hours', amount: n })),
        { value: 4320, label: 'label.silence.duration.days', amount: 3 },
        ...[1, 2].map(n => ({ value: n * 10080, label: 'label.silence.duration.weeks', amount: n })),
        { value: 43200, label: 'label.silence.duration.months', amount: 1 }
      ]
    }
  },
  computed: {
    kind () { return this.currentAction.wallRuleAction },
    isAll () { return this.currentAction.wallRuleScope === 'all' },
    datePickerLocale () { return this.$i18n?.locale === 'ko_KR' ? koKR : enUS },
    targets () {
      return this.attempted ? this.failures.map(failure => failure.rule) : eligibleRules(this.rules, this.kind)
    },
    displayTargets () {
      const groups = new Map()
      for (const target of this.targets) {
        const key = this.kind === 'unsilence' && target.ruleUid ? `rule:${target.ruleUid}` : `target:${ruleUid(target)}`
        if (groups.has(key)) groups.get(key).count++
        else groups.set(key, { key, name: target.name || ruleUid(target), count: 1 })
      }
      // Group the display only; every distinct silence ID remains an execution target.
      return [...groups.values()]
    },
    progress () { return this.batchSize ? Math.round(this.completed / this.batchSize * 100) : 0 }
  },
  created () { this.loadTargets() },
  methods: {
    ruleUid,
    disabledEndDate (current) { return current < dayjs().startOf('day') },
    endTimeText (minutes) {
      const now = new Date()
      const end = new Date(this.silenceEndsAt && minutes === this.duration ? this.silenceEndsAt : now.getTime() + minutes * 60000)
      const sameDay = end.getFullYear() === now.getFullYear() && end.getMonth() === now.getMonth() && end.getDate() === now.getDate()
      return sameDay
        ? end.toLocaleTimeString('ko-KR', { hour: '2-digit', minute: '2-digit' })
        : end.toLocaleString('ko-KR', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' })
    },
    async loadTargets () {
      this.fetching = true
      this.loadFailed = false
      try {
        const selectedRules = uniqueRules(this.currentAction.invokedAsGroupAction ? this.selectedItems : [this.resource]).map(rule => ({ ...rule }))
        if (this.kind === 'unsilence') {
          const [rules, silences] = await Promise.all([this.isAll ? fetchAllRules(getAPI) : Promise.resolve(selectedRules.filter(ruleIsSilenced)), fetchActiveSilences(getAPI)])
          const names = new Map(rules.map(rule => [ruleUid(rule), rule.name]))
          // A shared or regex silence may affect unselected rules; only expire
          // exact rule-UID silences when invoked for a selection.
          this.rules = silences.filter(silence => this.isAll || (silence.matchers || []).some(matcher =>
            matcher.name === '__alert_rule_uid__' && !matcher.isRegex && matcher.isEqual !== false && names.has(matcher.value)
          )).map(silence => ({ ...silence, name: names.get(silence.ruleUid) || silence.name }))
          return
        }
        const rules = this.isAll
          ? await fetchAllRules(getAPI)
          : selectedRules
        // Snapshot the targets so automatic list refreshes cannot change a confirmation.
        this.rules = uniqueRules(rules).map(rule => ({ ...rule }))
      } catch (error) {
        this.rules = []
        this.loadFailed = true
      } finally {
        this.fetching = false
      }
    },
    async submit () {
      if (this.running || this.fetching || this.loadFailed || !this.acknowledged || !this.targets.length) return
      if (this.kind === 'silence') {
        const end = this.silenceEndsAt
          ? Date.parse(this.silenceEndsAt)
          : this.duration === 'custom' ? this.customEndsAt?.valueOf() : Date.now() + this.duration * 60000
        if (!Number.isFinite(end) || end <= Date.now()) {
          this.timingError = this.$t('message.silence.end.future')
          return
        }
        // Freeze one end time for the entire batch, including failed-item retries.
        this.silenceEndsAt = new Date(end).toISOString()
        this.timingError = ''
      }
      this.running = true
      const targets = [...this.targets]
      this.completed = 0
      this.batchSize = targets.length
      try {
        const timing = this.kind === 'silence' ? { endsAt: this.silenceEndsAt } : undefined
        const result = await applyRuleAction(postAPI, targets, this.kind, timing, count => { this.completed = count })
        this.successCount += result.succeeded.length
        this.failures = result.failed
        this.attempted = true
        if (result.succeeded.length) {
          eventBus.emit('wall-alert-rules-changed')
          this.$emit('refresh-data')
        }
        if (!this.failures.length) {
          this.$message.success(this.$t(this.kind === 'unsilence' ? 'message.wall.silences.success' : 'message.wall.rules.success', { count: this.successCount }))
          this.$emit('close-action')
        }
      } finally {
        this.running = false
      }
    },
    close () {
      if (!this.running) this.$emit('close-action')
    }
  }
}
</script>

<style scoped>
.rule-impact { margin-top: 12px; }
.bullet { margin: 0; padding-left: 18px; }
.bullet li { font-size: 12px; color: var(--ui-text-secondary); }
.option-sep, .option-sub { opacity: 0.65; }
.rule-note { margin-top: 12px; color: var(--ui-text-secondary); }
.rule-targets { max-height: 180px; overflow: auto; margin: 16px 0; padding-left: 22px; }
.rule-result { margin-top: 16px; }
.rule-failures { max-height: 140px; overflow: auto; padding-left: 18px; margin: 0; }
.rule-actions { display: flex; justify-content: flex-end; gap: 8px; margin-top: 20px; }
</style>
