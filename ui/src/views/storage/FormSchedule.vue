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
  <a-spin :spinning="loading">
    <div class="form-layout" v-ctrl-enter="handleSubmit">
      <a-alert type="warning">
        <template #message>
          <div v-if="isEditing">{{ $t('message.snapshot.policy.edit.effect') }}</div>
          <div v-else v-html="$t('label.header.volume.snapshot')"></div>
        </template>
      </a-alert>
      <div class="form">
        <a-form
          :ref="formRef"
          :model="form"
          :rules="rules"
          layout="vertical"
          @finish="handleSubmit"
         >
          <slot name="resource-selection" />
          <a-row :gutter="12">
            <a-col :xs="24" :md="24" :lg="24">
              <a-form-item :label="$t('label.intervaltype')" name="intervaltype" ref="intervaltype">
                <a-radio-group
                  v-model:value="form.intervaltype"
                  :disabled="actionLoading || disabled"
                  buttonStyle="solid"
                  @change="handleChangeIntervalType">
                  <a-radio-button value="hourly" :disabled="isIntervalDisabled('hourly')">
                    {{ $t('label.hourly') }}
                  </a-radio-button>
                  <a-radio-button value="daily" :disabled="isIntervalDisabled('daily')">
                    {{ $t('label.daily') }}
                  </a-radio-button>
                  <a-radio-button value="weekly" :disabled="isIntervalDisabled('weekly')">
                    {{ $t('label.weekly') }}
                  </a-radio-button>
                  <a-radio-button value="monthly" :disabled="isIntervalDisabled('monthly')">
                    {{ $t('label.monthly') }}
                  </a-radio-button>
                </a-radio-group>
              </a-form-item>
            </a-col>
            <a-col :xs="24" :md="24" :lg="12" v-if="form.intervaltype==='hourly'">
              <a-form-item :label="$t('label.time')" name="time" ref="time">
                <a-tooltip
                  placement="right"
                  :title="$t('label.minute.past.hour')">
                  <a-input-number
                    style="width: 100%"
                    :disabled="isIntervalDisabled(form.intervaltype) || actionLoading || disabled"
                    v-model:value="form.time"
                    :step="1"
                    v-focus="true" />
                </a-tooltip>
                <template #extra>{{ $t('message.snapshot.policy.minute.range') }}</template>
              </a-form-item>
            </a-col>
            <a-col :xs="24" :md="24" :lg="12" v-if="['daily', 'weekly', 'monthly'].includes(form.intervaltype)">
              <a-form-item
                class="custom-time-select"
                :label="$t('label.time')"
                name="timeSelect"
                ref="timeSelect">
                <a-time-picker
                  popupClassName="snapshot-policy-time-dropdown"
                  use12Hours
                  format="h:mm A"
                  :disabled="isIntervalDisabled(form.intervaltype) || actionLoading || disabled"
                  v-model:value="form.timeSelect"
                  style="width: 100%;" />
              </a-form-item>
            </a-col>
            <a-col :xs="24" :md="24" :lg="12" v-if="form.intervaltype==='weekly'">
              <a-form-item :label="$t('label.day.of.week')" name="day-of-week" ref="day-of-week">
                <a-select
                  v-model:value="form['day-of-week']"
                  showSearch
                  :disabled="isIntervalDisabled(form.intervaltype) || actionLoading || disabled"
                  optionFilterProp="label"
                  :filterOption="(input, option) => {
                    return option.label.toLowerCase().indexOf(input.toLowerCase()) >= 0
                  }" >
                  <a-select-option v-for="(opt, optIndex) in dayOfWeek" :key="optIndex" :label="opt.name || opt.description">
                    {{ opt.name || opt.description }}
                  </a-select-option>
                </a-select>
              </a-form-item>
            </a-col>
            <a-col :xs="24" :md="24" :lg="12" v-if="form.intervaltype==='monthly'">
              <a-form-item :label="$t('label.day.of.month')" ref="day-of-month" name="day-of-month">
                <a-select
                  v-model:value="form['day-of-month']"
                  showSearch
                  :disabled="isIntervalDisabled(form.intervaltype) || actionLoading || disabled"
                  optionFilterProp="value"
                  :filterOption="filterDayOfMonth" >
                  <a-select-option v-for="opt in dayOfMonth" :key="opt.id" :value="opt.id" :label="String(opt.name)">
                    {{ opt.name }}
                  </a-select-option>
                </a-select>
              </a-form-item>
            </a-col>
            <a-col :xs="24" :md="24" :lg="12">
              <a-form-item :label="$t('label.keep')" name="maxsnaps" ref="maxsnaps">
                <a-tooltip
                  placement="right"
                  :title="$t('label.snapshots')">
                  <a-input-number
                    style="width: 100%"
                    v-model:value="form.maxsnaps"
                    :disabled="actionLoading || disabled"
                    :step="1" />
                </a-tooltip>
                <template #extra>
                  <div v-if="resource.id" class="policy-retention-help" role="status" aria-live="polite" data-testid="policy-retention-help">
                    <div v-if="limitsLoading">{{ $t('message.snapshot.policy.limits.loading') }}</div>
                    <template v-else>
                      <div>{{ $t('message.snapshot.policy.limits.interval', { max: limitText(policyLimits.maxima[form.intervaltype]) }) }}</div>
                      <div v-if="policyLimits.exempt === true">{{ $t('message.snapshot.policy.limits.admin') }}</div>
                      <template v-else>
                        <div>{{ $t(resource.projectid ? 'message.snapshot.policy.limits.project' : 'message.snapshot.policy.limits.account', { max: limitText(policyLimits.owner) }) }}</div>
                        <div>{{ $t('message.snapshot.policy.limits.domain', { max: limitText(policyLimits.domain) }) }}</div>
                      </template>
                      <div v-if="limitsIncomplete">{{ $t('message.snapshot.policy.limits.unavailable') }} <a-button type="link" size="small" @click="fetchPolicyLimits">{{ $t('label.retry') }}</a-button></div>
                      <div>{{ $t('message.snapshot.policy.limits.server') }}</div>
                    </template>
                  </div>
                </template>
              </a-form-item>
            </a-col>
            <a-col :xs="24" :md="24" :lg="24">
              <a-form-item :label="$t('label.timezone')" ref="timezone" name="timezone">
                <a-select
                  v-model:value="form.timezone"
                  :disabled="actionLoading || disabled"
                  :loading="fetching"
                  showSearch
                  optionFilterProp="label"
                  :filterOption="(input, option) => {
                    return option.label.toLowerCase().indexOf(input.toLowerCase()) >= 0
                  }" >
                  <a-select-option v-for="opt in timeZoneMap" :key="opt.id" :label="opt.name || opt.description">
                    {{ opt.name || opt.description }}
                  </a-select-option>
                </a-select>
              </a-form-item>
            </a-col>
            <a-col :xs="24" :md="24" :lg="24" v-if="resourceType === 'Volume'">
              <a-form-item ref="zoneids" name="zoneids" >
                <template #label>
                  <tooltip-label :title="$t('label.zones')" :tooltip="''"/>
                </template>
                <a-alert v-if="resource.id" type="info" style="margin-bottom: 2%">
                  <template #message>
                    <div v-html="formattedAdditionalZoneMessage"/>
                  </template>
                </a-alert>
                <a-select
                  id="zone-selection"
                  v-model:value="form.zoneids"
                  mode="multiple"
                  showSearch
                  optionFilterProp="label"
                  :filterOption="(input, option) => {
                    return  option.label.toLowerCase().indexOf(input.toLowerCase()) >= 0
                  }"
                  :loading="zoneLoading"
                  :disabled="zoneLoading || zoneError || actionLoading || disabled"
                  :notFoundContent="$t('message.snapshot.zones.empty')"
                  :placeholder="''">
                  <a-select-option v-for="opt in policyZoneOptions" :key="opt.id" :label="opt.name || opt.description" :disabled="opt.unavailable">
                    <span>
                      <resource-icon v-if="opt.icon" :image="opt.icon.base64image" size="1x" style="margin-right: 5px"/>
                      <global-outlined v-else style="margin-right: 5px"/>
                      {{ opt.name || opt.description }}
                    </span>
                  </a-select-option>
                </a-select>
                <a-alert v-if="zoneError" type="error" show-icon :message="$t('message.snapshot.zones.failed')">
                  <template #description><a-button size="small" @click="fetchZoneData">{{ $t('label.retry') }}</a-button></template>
                </a-alert>
              </a-form-item>
            </a-col>

          </a-row>
          <a-divider/>
          <div class="tagsTitle">{{ $t('label.tags') }}</div>
          <section class="snapshot-policy-tags" data-testid="snapshot-policy-tags">
            <div class="policy-tag-list">
              <a-tag v-for="tag in tags" :key="tag.key" :closable="canEditTags" @close.prevent="handleDeleteTag(tag)">
                {{ tag.key }} = {{ tag.value }}
              </a-tag>
            </div>
            <div v-if="inputVisible && tagPermission" class="policy-tag-editor" @keydown.enter.prevent.stop="handleInputConfirm" @keydown.esc.prevent.stop="cancelTagInput">
              <a-input ref="input" v-model:value="inputKey" :aria-label="$t('label.key')" :placeholder="$t('label.key')" :maxlength="255" :disabled="!canEditTags" />
              <a-input ref="tagValue" v-model:value="inputValue" :aria-label="$t('label.value')" :placeholder="$t('label.value')" :maxlength="255" :disabled="!canEditTags" />
              <div class="policy-tag-editor-actions">
                <a-button :aria-label="$t('label.ok')" :disabled="!canEditTags" @click="handleInputConfirm"><check-outlined /></a-button>
                <a-button :aria-label="$t('label.cancel')" :disabled="!canEditTags" @click="cancelTagInput"><close-outlined /></a-button>
              </div>
            </div>
            <a-button v-else-if="tagPermission" ref="tagAdd" type="dashed" size="small" :disabled="!canEditTags" @click="showInput">
              <plus-outlined /> {{ $t('label.new.tag') }}
            </a-button>
            <div v-if="tagError" role="alert" class="policy-tag-error">{{ $t(tagError) }}</div>
            <div v-if="tagPermission" class="policy-tag-hint">{{ $t('message.snapshot.policy.tags.draft') }}</div>
          </section>
          <MoldDialogActions v-if="actionsVisible" :span="24" :busy="actionLoading">
            <a-button
              :loading="actionLoading"
              @click="closeAction">
              {{ $t('label.cancel') }}
            </a-button>
            <a-button
              v-if="handleShowButton()"
              :disabled="disabled || limitsLoading"
              :loading="actionLoading"
              type="primary"
              ref="submit"
              @click="handleSubmit">
              {{ $t(isEditing ? 'label.save' : 'label.ok') }}
            </a-button>
          </MoldDialogActions>
        </a-form>
      </div>
    </div>
  </a-spin>
</template>

<script>
import MoldDialogActions from '@/components/view/MoldDialogActions'
import snapshotZones from '@/utils/snapshotZones'
import { ref, reactive, toRaw } from 'vue'
import { postAPI } from '@/api'
import TooltipLabel from '@/components/widgets/TooltipLabel'
import { timeZone } from '@/utils/timezone'
import { mixinForm } from '@/utils/mixin'
import storageDialogContext from '@/utils/storageDialogContext'
import debounce from 'lodash/debounce'
import { getAPI } from '@/utils/storageApi'
import { emptyPolicyLimits, readPolicyLimits, effectivePolicyMaximum, policyFieldErrors, encodePolicySchedule } from '@/utils/snapshotPolicyInput'
import { policyFormValues, extraPolicyZones, editingZoneParams } from '@/utils/snapshotPolicyEditing'

export default {
  name: 'FormSchedule',
  mixins: [mixinForm, storageDialogContext, snapshotZones],
  components: {
    MoldDialogActions,
    TooltipLabel
  },
  props: {
    policy: { type: Object, default: null },
    validateEdit: { type: Function, default: null },
    actionsVisible: { type: Boolean, default: true },
    disabled: { type: Boolean, default: false },
    loading: {
      type: Boolean,
      default: false
    },
    dataSource: {
      type: Array,
      required: true
    },
    resource: {
      type: Object,
      required: true
    },
    resourceType: {
      type: String,
      default: null
    }
  },
  data () {
    this.fetchTimeZone = debounce(this.fetchTimeZone, 800)

    return {
      actionLoading: false,
      volumeId: '',
      inputKey: '',
      inputVisible: false,
      inputValue: '',
      tagError: '',
      intervalType: 'hourly',
      intervalValue: 0,
      tags: [],
      dayOfWeek: [],
      dayOfMonth: [],
      timeZoneMap: [],
      fetching: false,
      policyLimits: emptyPolicyLimits(),
      limitsLoading: false,
      limitsVersion: 0,
      listDayOfWeek: ['sunday', 'monday', 'tuesday', 'wednesday', 'thursday', 'friday', 'saturday']
    }
  },
  beforeCreate () {
    this.apiParams = this.$getApiParams('createSnapshotPolicy')
  },
  created () {
    this.initForm()
    if (this.policy) {
      Object.assign(this.form, policyFormValues(this.policy), { zoneids: extraPolicyZones(this.policy, this.resource) })
      this.tags = (this.policy.tags || []).map(tag => ({ key: tag.key, value: tag.value }))
      this.handleChangeIntervalType()
    }
    this.volumeId = this.resource.id
    this.fetchTimeZone()
    this.fetchPolicyLimits()
  },
  mounted () {
    if (!this.isEditing && this.form.intervaltype && this.isIntervalDisabled(this.form.intervaltype)) {
      const nextAvailable = this.getNextAvailableIntervalType(this.form.intervaltype)
      if (nextAvailable) {
        this.form.intervaltype = nextAvailable
        this.handleChangeIntervalType()
      }
    }
  },
  computed: {
    isEditing () { return !!this.policy },
    preserveSnapshotZones () { return this.isEditing },
    policyZoneOptions () {
      const known = this.zones || []
      return [...known, ...(this.policy?.zone || []).filter(zone => zone.id !== this.resource.zoneid && !known.some(item => item.id === zone.id)).map(zone => ({ ...zone, unavailable: true }))]
    },
    policyLimitContext () {
      const apis = this.$store.getters.apis || {}
      return JSON.stringify([this.dialogScope(), this.resource.account, this.resource.domainid, this.resource.projectid,
        'listCapabilities' in apis, 'listAccounts' in apis, 'listResourceLimits' in apis])
    },
    policyMaximum () { return effectivePolicyMaximum(this.policyLimits, this.form.intervaltype) },
    limitsIncomplete () {
      return this.policyLimits.maxima[this.form.intervaltype] == null || this.policyLimits.exempt == null ||
        (this.policyLimits.exempt === false && (this.policyLimits.owner == null || this.policyLimits.domain == null))
    },
    tagPermission () {
      return 'createSnapshotPolicy' in this.$store.getters.apis && 'createTags' in this.$store.getters.apis
    },
    canEditTags () {
      return this.tagPermission && !this.actionLoading && !this.disabled && this.dialogCurrent()
    },
    formattedAdditionalZoneMessage () {
      return `${this.$t('message.snapshot.additional.zones').replace('%x', this.resource.zonename)}`
    }
  },
  watch: {
    actionLoading (busy) { this.$emit('busy', busy) },
    policyLimitContext () { this.fetchPolicyLimits() },
    tagPermission (allowed) {
      if (!allowed) { this.tags = (this.policy?.tags || []).map(tag => ({ key: tag.key, value: tag.value })); this.clearTagInput() }
    },
    dataSource: {
      handler () {
        if (this.isEditing) return
        if (this.form.intervaltype && this.getNextAvailableIntervalType && this.isIntervalDisabled(this.form.intervaltype)) {
          const nextAvailable = this.getNextAvailableIntervalType(this.form.intervaltype)
          if (nextAvailable) {
            this.form.intervaltype = nextAvailable
            this.handleChangeIntervalType()
          }
        }
      },
      deep: true
    }
  },
  methods: {
    limitText (value) { return value == null ? this.$t('message.snapshot.policy.limits.unknown') : value === -1 ? this.$t('label.unlimited') : String(value) },
    async fetchPolicyLimits () {
      const version = ++this.limitsVersion; const context = this.policyLimitContext
      this.policyLimits = emptyPolicyLimits(); this.limitsLoading = false
      if (!this.resource.id) return
      this.limitsLoading = true
      const result = await readPolicyLimits(getAPI, this.$store.getters.apis || {}, { ...this.resource })
      if (version !== this.limitsVersion || context !== this.policyLimitContext || !this.dialogCurrent()) return
      this.policyLimits = result; this.limitsLoading = false
    },
    initForm () {
      this.formRef = ref()
      this.form = reactive({
        intervaltype: 'hourly',
        time: undefined,
        timeSelect: undefined,
        'day-of-week': undefined,
        'day-of-month': undefined,
        maxsnaps: undefined,
        timezone: undefined
      })
      this.rules = reactive({
        time: [{ required: true, validator: () => this.validatePolicyField('time') }],
        timeSelect: [{ type: 'object', required: true, message: this.$t('message.error.time') }],
        'day-of-week': [{ required: true, validator: () => this.validatePolicyField('day-of-week') }],
        'day-of-month': [{ required: true, validator: () => this.validatePolicyField('day-of-month') }],
        maxsnaps: [{ required: true, validator: () => this.validatePolicyField('maxsnaps') }],
        timezone: [{ required: true, message: `${this.$t('message.error.select')}` }]
      })
      if (this.resourceType === 'Volume' && this.resource.id) {
        this.fetchZoneData()
      }
    },
    async validatePolicyField (field) {
      const error = policyFieldErrors(this.form, this.policyMaximum).find(error => error.name === field)
      if (error) throw new Error(this.$t(error.key, error.params))
    },
    fetchTimeZone (value) {
      this.timeZoneMap = []
      this.fetching = true

      timeZone(value).then(json => {
        this.timeZoneMap = json
        this.fetching = false
      })
    },
    fetchDayOfWeek () {
      this.dayOfWeek = []

      for (const index in this.listDayOfWeek) {
        const dayName = this.listDayOfWeek[index]
        this.dayOfWeek.push({
          id: dayName,
          name: this.$t('label.' + dayName)
        })
      }
    },
    filterDayOfMonth (input, option) {
      return option?.value != null && String(option.value).includes(String(input ?? ''))
    },
    fetchDayOfMonth () {
      this.dayOfMonth = []
      const maxDayOfMonth = 28
      for (let num = 1; num <= maxDayOfMonth; num++) {
        this.dayOfMonth.push({
          id: num,
          name: num
        })
      }
    },
    handleChangeIntervalType () {
      if (this.form.intervaltype === 'weekly') {
        this.fetchDayOfWeek()
      } else if (this.form.intervaltype === 'monthly') {
        this.fetchDayOfMonth()
      }
      this.intervalValue = this.getIntervalValue(this.form.intervaltype)
    },
    getIntervalValue (intervalType) {
      switch (intervalType) {
        case 'hourly':
          return 0
        case 'daily':
          return 1
        case 'weekly':
          return 2
        case 'monthly':
          return 3
      }
    },
    getNextAvailableIntervalType (currentIntervalType) {
      const intervalTypes = ['hourly', 'daily', 'weekly', 'monthly']
      const currentIndex = intervalTypes.indexOf(currentIntervalType)
      const startIndex = currentIndex >= 0 ? currentIndex : -1

      for (let i = 1; i <= intervalTypes.length; i++) {
        const nextIndex = (startIndex + i) % intervalTypes.length
        const nextIntervalType = intervalTypes[nextIndex]

        if (!this.isIntervalDisabled(nextIntervalType)) {
          return nextIntervalType
        }
      }
      return null
    },
    isIntervalDisabled (intervalType) {
      if (this.isEditing) return this.getIntervalValue(intervalType) !== Number(this.policy.intervaltype)
      const intervalValue = this.getIntervalValue(intervalType)
      if (this.dataSource.length === 0) {
        return false
      }
      const dataSource = this.dataSource.filter(item => item.intervaltype === intervalValue)
      if (dataSource && dataSource.length > 0) {
        return true
      }
      return false
    },
    handleShowButton () {
      if (this.isEditing) return true
      if (this.dataSource.length === 0) {
        return true
      }
      const dataSource = this.dataSource.filter(item => item.intervaltype === this.intervalValue)
      if (dataSource && dataSource.length > 0) {
        return false
      }
      return true
    },
    clearTagInput () {
      this.inputVisible = false
      this.inputKey = ''
      this.inputValue = ''
      this.tagError = ''
    },
    handleInputConfirm (event, focus = true) {
      if (!this.canEditTags || !this.inputVisible || event?.isComposing || event?.keyCode === 229) return false
      const key = this.inputKey.trim()
      if (!key || !this.inputValue.trim()) {
        this.tagError = 'message.tags.fields.required'
        this.$nextTick(() => (key ? this.$refs.tagValue : this.$refs.input)?.focus())
        return false
      }
      if (this.tags.some(tag => tag.key === key)) {
        this.tagError = 'message.snapshot.policy.tag.duplicate'
        this.$nextTick(() => this.$refs.input?.focus())
        return false
      }
      this.tags.push({ key, value: this.inputValue })
      this.clearTagInput()
      if (focus) this.focusTagAdd()
      return true
    },
    handleDeleteTag (tag) {
      if (!this.canEditTags) return
      this.tags = this.tags.filter(item => item.key !== tag.key)
      this.focusTagAdd()
    },
    focusTagAdd () {
      this.$nextTick(() => {
        const target = this.$refs.tagAdd?.$el
        if (this.canEditTags && target) target.focus({ preventScroll: true })
      })
    },
    cancelTagInput () {
      if (!this.canEditTags) return
      this.clearTagInput()
      this.focusTagAdd()
    },
    handleSubmit (e) {
      if (!this.volumeId || this.actionLoading || this.disabled || this.limitsLoading || !this.dialogCurrent() || !('createSnapshotPolicy' in this.$store.getters.apis)) return
      // Draft tags are part of this create request; they never call createTags/deleteTags.
      if (this.inputVisible && !this.handleInputConfirm(undefined, false)) return
      return this.formRef.value.validate().then(async () => {
        if (this.actionLoading || this.disabled || this.limitsLoading || !this.dialogCurrent() || !('createSnapshotPolicy' in this.$store.getters.apis)) return
        const formRaw = toRaw(this.form)
        const errors = policyFieldErrors(formRaw, this.policyMaximum)
        if (errors.length) {
          if (this.formRef.value.setFields) this.formRef.value.setFields(errors.map(error => ({ name: error.name, errors: [this.$t(error.key, error.params)] })))
          return
        }
        const values = this.handleRemoveFields(formRaw)

        let params = {}
        if (this.isEditing) {
          params.id = this.policy.id
          if (typeof this.policy.fordisplay === 'boolean' && this.$store.getters.userInfo?.roletype === 'Admin') params.fordisplay = this.policy.fordisplay
          Object.assign(params, editingZoneParams(this.policy, this.resource, values.zoneids, this.zoneLoading || this.zoneError))
        }
        params.volumeid = this.volumeId
        params.intervaltype = values.intervaltype
        params.timezone = values.timezone
        params.maxsnaps = values.maxsnaps
        params.useStorageReplication = false
        if (!this.isEditing && values.zoneids && values.zoneids.length > 0) {
          params.zoneids = values.zoneids.join()
        }
        params.schedule = encodePolicySchedule(values)
        const tagPermission = this.tagPermission
        const tags = tagPermission || this.isEditing ? this.tags : []
        for (let i = 0; i < tags.length; i++) {
          const formattedTagData = {}
          const tag = tags[i]
          formattedTagData['tags[' + i + '].key'] = tag.key
          formattedTagData['tags[' + i + '].value'] = tag.value
          params = Object.assign({}, params, formattedTagData)
        }
        this.actionLoading = true
        try {
          if (this.isEditing && this.validateEdit) await this.validateEdit()
          if (!this.dialogCurrent() || this.disabled || this.limitsLoading || !('createSnapshotPolicy' in this.$store.getters.apis)) return
          if (tagPermission !== this.tagPermission) throw new Error(this.$t('message.snapshot.policy.context.changed'))
          const json = await postAPI('createSnapshotPolicy', params, { preserveOnFailure: true })
          if (!this.dialogCurrent()) return
          if (this.isEditing && json.createsnapshotpolicyresponse?.snapshotpolicy?.id !== this.policy.id) throw new Error(this.$t('message.snapshot.policy.changed'))
          this.$emit('refresh')
          this.$notification.success({
            message: this.$t(this.isEditing ? 'label.snapshot.policy.edit' : 'label.action.recurring.snapshot'),
            description: this.$t(this.isEditing ? 'message.snapshot.policy.saved' : 'message.snapshot.policy.created')
          })
          if (!this.isEditing) this.resetForm()
        } catch (error) {
          if (this.dialogCurrent()) this.$notifyError(error)
        } finally {
          this.actionLoading = false
        }
      }).catch(error => {
        if (error instanceof Error && this.dialogCurrent()) this.$notifyError(new Error(error.message.startsWith('message.') ? this.$t(error.message) : error.message))
      })
    },
    showInput () {
      if (!this.canEditTags) return
      this.clearTagInput()
      this.inputVisible = true
      this.$nextTick(function () {
        if (this.canEditTags && this.$refs.input) this.$refs.input.focus()
      })
    },
    resetForm () {
      this.form.time = undefined
      this.form.timezone = undefined
      this.form.timeSelect = undefined
      this.form.maxsnaps = undefined
      this.form['day-of-week'] = undefined
      this.form['day-of-month'] = undefined
      this.tags = []
      this.clearTagInput()
    },
    closeAction () {
      this.$emit('close-action')
    }
  }
}
</script>

<style lang="less" scoped>
.form-layout {
  .ant-tag {
    margin-bottom: 10px;
  }

  :deep(.custom-time-select) .ant-time-picker {
    width: 100%;
  }

  :deep(.ant-divider-horizontal) {
    margin-top: 0;
  }
}

.form {
  margin: 10px 0;
}

.tagsTitle {
  font-weight: 500;
  margin-bottom: 12px;
}
.policy-tag-list { display: flex; flex-wrap: wrap; gap: 6px; margin-bottom: 8px; }
.policy-tag-list :deep(.ant-tag) { margin: 0; max-width: 100%; white-space: normal; overflow-wrap: anywhere; }
.policy-tag-list :deep(.ant-tag-close-icon) { color: inherit; }
.policy-tag-editor { display: grid; grid-template-columns: minmax(0, 1fr); gap: 6px; }
.policy-tag-editor-actions { display: flex; justify-content: flex-end; gap: 6px; }
.policy-tag-error { color: var(--ant-error-color, #ff4d4f); margin-top: 6px; }
.policy-tag-hint { color: var(--ui-text-secondary); font-size: 12px; margin-top: 6px; }
.policy-retention-help { color: var(--ui-text-secondary); font-size: 12px; overflow-wrap: anywhere; }
</style>
