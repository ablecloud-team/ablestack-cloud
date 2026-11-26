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
  <div :class="{ 'autoscale-tab': resourceType === 'AutoScaleVmGroup' }">
    <a-space v-if="resourceType === 'AutoScaleVmGroup'" wrap class="autoscale-toolbar">
      <a-button type="primary" @click="createSchedule" :disabled="isSubmitted || !('createResourceSchedule' in $store.getters.apis)"><template #icon><plus-outlined /></template>{{ $t('label.schedule.add') }}</a-button>
      <a-button @click="fetchData" :disabled="isSubmitted" :loading="tabLoading || scheduleRefreshing"><template #icon><reload-outlined /></template>{{ $t('label.refresh') }}</a-button>
    </a-space>
    <a-alert v-if="resourceType === 'AutoScaleVmGroup'" type="info" show-icon :message="$t('message.autoscale.schedule.contract')" style="margin-bottom: 10px" />
    <p v-if="listRefreshFailed" role="status">{{ $t('message.list.refresh.stale') }}</p>
    <a-button
      v-if="resourceType !== 'AutoScaleVmGroup'"
      type="dashed"
      style="width: 100%; margin-bottom: 10px"
      @click="showAddModal"
      :loading="loading"
      :disabled="!('createResourceSchedule' in $store.getters.apis)"
    >
      <template #icon><plus-outlined /></template> {{ $t('label.schedule.add') }}
    </a-button>
    <list-view
detail-tab
      :loading="tabLoading"
      :columns="columns"
      :items="schedules"
      :columnKeys="columnKeys"
      :selectedColumns="selectedColumnKeys"
      ref="listview"
      @update-selected-columns="updateSelectedColumns"
      @refresh="this.fetchData"
    >
      <template #scheduleActions="{ record }">
        <div v-if="resourceType === 'AutoScaleVmGroup'" class="autoscale-row-actions">
          <a-dropdown :trigger="['click']" placement="bottomRight" overlay-class-name="autoscale-row-menu"><a-button size="small" :aria-label="$t('label.actions')"><template #icon><down-outlined /></template></a-button><template #overlay><a-menu>
            <a-menu-item key="edit" :disabled="tabLoading || isSubmitted || !('updateResourceSchedule' in $store.getters.apis)" @click="updateSchedule(record)">{{ $t('label.autoscale.schedule.edit') }}</a-menu-item>
            <a-menu-item key="delete" danger :disabled="tabLoading || isSubmitted || !('deleteResourceSchedule' in $store.getters.apis)" @click="prepareScheduleRemoval(record)">{{ $t('label.autoscale.schedule.remove') }}</a-menu-item>
          </a-menu></template></a-dropdown>
        </div>
        <template v-else>
        <tooltip-button
          :tooltip="$t('label.edit')"
          :disabled="!('updateResourceSchedule' in $store.getters.apis)"
          icon="edit-outlined"
          @onClick="updateSchedule(record)"
        />
        <a-popconfirm
          :title="$t('label.delete') + ' ' + $t('label.schedule') + '?'"
          :okText="$t('label.yes')"
          :cancelText="$t('label.no')"
          @confirm="removeSchedule(record)"
        >
          <tooltip-button
            :tooltip="$t('label.remove')"
            :disabled="!('deleteResourceSchedule' in $store.getters.apis)"
            icon="delete-outlined"
            :danger="true"
            type="primary"
          />
        </a-popconfirm>
        </template>
      </template>
    </list-view>
    <detail-tab-pagination
      class="row-element"
      style="margin-top: 10px"
      size="small"
      :current="page"
      :pageSize="pageSize"
      :total="totalCount"
      :showTotal="total => this.$localStorage.get('LOCALE') == 'ko_KR' ?
          `${$t('label.total')} ${total} ${$t('label.items')} ${$t('label.of')} ${Math.min(total, 1 + ((page - 1) * pageSize))}-${Math.min(page * pageSize, total)} ${$t('label.showing')}` :
          `${$t('label.showing')} ${Math.min(total, 1 + ((page - 1) * pageSize))}-${Math.min(page * pageSize, total)} ${$t('label.of')} ${total} ${$t('label.items')}`"
      :pageSizeOptions="pageSizeOptions"
      @change="changePage"
      @showSizeChange="changePage"
      showSizeChanger
      showQuickJumper
    >
      <template #buildOptionText="props">
        <span>{{ props.value }} / {{ $t('label.page') }}</span>
      </template>
    </detail-tab-pagination>
  </div>

  <a-modal
v-if="resourceType === 'AutoScaleVmGroup'"
class="mold-dialog autoscale-dialog"
centered
:width="520"
:visible="!!pendingScheduleRemoval"
:title="$t('label.autoscale.schedule.remove')"
:ok-text="$t('label.delete')"
      :ok-button-props="{ danger: true, disabled: tabLoading || isSubmitted }"
      @ok="confirmScheduleRemoval({ id: pendingScheduleRemoval?.id })"
      @cancel="pendingScheduleRemoval = null">
    <a-descriptions bordered :column="1" size="small"><a-descriptions-item :label="$t('label.description')">{{ pendingScheduleRemoval?.name }}</a-descriptions-item><a-descriptions-item :label="$t('label.id')">{{ pendingScheduleRemoval?.id }}</a-descriptions-item></a-descriptions>
  </a-modal>

  <a-modal
    :class="resourceType === 'AutoScaleVmGroup' ? 'mold-dialog autoscale-dialog' : undefined"
    :centered="resourceType === 'AutoScaleVmGroup'"
    :width="resourceType === 'AutoScaleVmGroup' ? 760 : 520"
    :visible="showModal"
    :title="$t('label.schedule')"
    :maskClosable="false"
    :closable="true"

      @cancel="closeModal"
    :confirmLoading="isSubmitted"

      @ok="submitForm"
  >
    <a-form
      layout="vertical"
      :ref="formRef"
      :model="form"
      :rules="rules"
      @finish="submitForm"
      v-ctrl-enter="submitForm"
    >
      <a-form-item
        name="description"
        ref="description"
        :wrapperCol="{ span: 24 }"
      >
        <template #label>
          <tooltip-label
            :title="$t('label.description')"
            :tooltip="apiParams.description.description"
          />
        </template>
        <a-input
          v-model:value="form.description"
          v-focus="true"
        />
      </a-form-item>
      <a-form-item
        v-if="actions.length > 1"
        name="action"
        ref="action"
        :wrapperCol="{ span: 24 }"
      >
        <template #label>
          <tooltip-label
            :title="$t('label.action')"
            :tooltip="apiParams.action.description"
          />
        </template>
        <a-radio-group
          v-model:value="form.action"
          button-style="solid"
          :disabled="isEdit"
        >
          <a-radio-button
            v-for="action in actions"
            :key="action.id"
            :value="action.value"
          >
            {{ $t(action.label) }}
          </a-radio-button>
        </a-radio-group>
      </a-form-item>
      <a-row
        v-if="resourceType === 'AutoScaleVmGroup'"
        justify="space-between"
      >
        <a-col :span="11">
          <a-form-item
            name="minMembers"
            ref="minMembers"
          >
            <template #label>
              <tooltip-label :title="$t('label.minmembers')" />
            </template>
            <a-input-number
              v-model:value="form.minMembers"
              :min="1"
              style="width: 100%"
            />
          </a-form-item>
        </a-col>
        <a-col :span="11">
          <a-form-item
            name="maxMembers"
            ref="maxMembers"
          >
            <template #label>
              <tooltip-label :title="$t('label.maxmembers')" />
            </template>
            <a-input-number
              v-model:value="form.maxMembers"
              :min="1"
              style="width: 100%"
            />
          </a-form-item>
        </a-col>
      </a-row>
      <a-form-item
        name="timezone"
        ref="timezone"
        :wrapperCol="{ span: 24 }"
      >
        <template #label>
          <tooltip-label
            :title="$t('label.timezone')"
            :tooltip="apiParams.timezone.description"
          />
        </template>
        <a-select
          showSearch
          v-model:value="form.timezone"
          optionFilterProp="label"
          :filterOption="(input, option) => {
            return option.label.toLowerCase().indexOf(input.toLowerCase()) >= 0
          }"
          :loading="fetching"
        >
          <a-select-option
            v-for="opt in timeZoneMap"
            :key="opt.id"
            :label="opt.name || opt.description"
          >
            {{ opt.name || opt.description }}
          </a-select-option>
        </a-select>
      </a-form-item>
      <a-row justify="space-between">
        <a-col>
          <a-form-item
            name="startDate"
            ref="startDate"
          >
            <template #label>
              <tooltip-label
                :title="$t('label.start.date.and.time')"
                :tooltip="apiParams.startdate.description"
              />
            </template>
            <a-date-picker
              v-model:value="form.startDate"
              show-time
              :locale="resourceType === 'AutoScaleVmGroup' ? datePickerLocale : this.$i18n.locale"
              :dropdown-class-name="resourceType === 'AutoScaleVmGroup' ? 'autoscale-schedule-picker' : undefined"
              :placeholder="$t('message.select.start.date.and.time')"
            />
          </a-form-item>
        </a-col>
        <a-col>
          <a-form-item
            name="endDate"
            ref="endDate"
          >
            <template #label>
              <tooltip-label
                :title="$t('label.end.date.and.time')"
                :tooltip="apiParams.enddate.description"
              />
            </template>
            <a-date-picker
              v-model:value="form.endDate"
              show-time
              :locale="resourceType === 'AutoScaleVmGroup' ? datePickerLocale : this.$i18n.locale"
              :dropdown-class-name="resourceType === 'AutoScaleVmGroup' ? 'autoscale-schedule-picker' : undefined"
              :placeholder="$t('message.select.end.date.and.time')"
            />
          </a-form-item>
        </a-col>
      </a-row>
      <a-form-item
        name="schedule"
        ref="schedule"
        :wrapperCol="{ span: 24 }"
      >
        <template #label>
          <tooltip-label
            :title="$t('label.schedule')"
            :tooltip="apiParams.schedule.description"
          />
        </template>
        <a-row
          style="margin-bottom: 15px; text-align: center;"
          justify="space-around"
          align="middle"
        >
          <cron-ant
            v-if="!form.useCronFormat"
            v-model="form.schedule"
            :periods="periods"
            :locale="resourceType === 'AutoScaleVmGroup' ? $i18n.locale.replace('_', '-') : undefined"
            :custom-locale="cronLocale"
            :button-props="{ type: resourceType === 'AutoScaleVmGroup' ? 'default' : 'primary', size: 'small', disabled: form.useCronFormat }"
            @error="error = $event"
          />
          <label
            v-if="form.useCronFormat">
            {{ generateHumanReadableSchedule(form.schedule) }}
          </label>
        </a-row>
        <a-row
          justify="space-between"
          align="middle"
        >
          <a-col>
            <label>{{ $t('label.cron.mode') }}</label>
          </a-col>
          <a-col>
            <a-switch v-model:checked="form.useCronFormat">
            </a-switch>
          </a-col>
          <a-col :span="12">
            <a-input
              :addonBefore="$t('label.cron')"
              v-model:value="form.schedule"
              :disabled="!form.useCronFormat"
              v-focus="true"
            />
          </a-col>
        </a-row>
      </a-form-item>
      <a-form-item
        name="enabled"
        ref="enabled"
        :wrapperCol="{ span: 24}"
      >
        <template #label>
          <tooltip-label
            :title="$t('label.enabled')"
            :tooltip="apiParams.enabled.description"
          />
        </template>
        <a-switch v-model:checked="form.enabled" />
      </a-form-item>
    </a-form>
  </a-modal>
</template>

<script>
import '@/style/components/view/autoscale.less'
import { autoScaleResourceScope } from '@/utils/autoScaleUi'
import { listRefreshMixin } from '@/utils/listRefreshMixin'

import { reactive, ref, toRaw, h } from 'vue'
import koKR from 'ant-design-vue/es/date-picker/locale/ko_KR'
import enUS from 'ant-design-vue/es/date-picker/locale/en_US'
import 'dayjs/locale/ko'
import { getAPI, postAPI } from '@/api'
import ListView from '@/components/view/ListView'
import Status from '@/components/widgets/Status'
import TooltipButton from '@/components/widgets/TooltipButton'
import TooltipLabel from '@/components/widgets/TooltipLabel'
import { mixinForm } from '@/utils/mixin'
import { timeZone } from '@/utils/timezone'
import debounce from 'lodash/debounce'
import cronstrue from 'cronstrue/i18n'
import dayjs from 'dayjs'
import utc from 'dayjs/plugin/utc'
import timezone from 'dayjs/plugin/timezone'
import { schedulePickerDate, autoScaleScheduleError } from '@/utils/autoScaleSchedule'

dayjs.extend(utc)
dayjs.extend(timezone)

export default {
  name: 'ResourceSchedules',
  mixins: [listRefreshMixin(['fetchSchedules']), mixinForm],
  components: {
    Status,
    ListView,
    TooltipButton,
    TooltipLabel
  },
  props: {
    resource: {
      type: Object,
      required: true
    },
    resourceType: {
      type: String,
      required: true
    },
    loading: {
      type: Boolean,
      default: false
    }
  },
  data () {
    this.fetchTimeZone = debounce(this.fetchTimeZone, 800)
    return {
      tabLoading: false,
      scheduleRefreshing: false,
      scheduleReadScope: null,
      columnKeys: ['action', 'enabled', 'description', 'schedule', 'timezone', 'startdate', 'enddate', 'created', 'scheduleActions'],
      selectedColumnKeys: [],
      columns: [],
      schedules: [],
      pendingScheduleRemoval: null,
      timeZoneMap: [],
      resourceActionsMap: {
        VirtualMachine: [
          { value: 'START', label: 'label.start' },
          { value: 'STOP', label: 'label.stop' },
          { value: 'REBOOT', label: 'label.reboot' },
          { value: 'FORCE_STOP', label: 'label.force.stop' },
          { value: 'FORCE_REBOOT', label: 'label.force.reboot' }
        ],
        AutoScaleVmGroup: [
          { value: 'UPDATE', label: 'label.update.members' }
        ]
      },
      periods: [
        { id: 'year', value: ['month', 'day', 'dayOfWeek', 'hour', 'minute'] },
        { id: 'month', value: ['day', 'dayOfWeek', 'hour', 'minute'] },
        { id: 'week', value: ['dayOfWeek', 'hour', 'minute'] },
        { id: 'day', value: ['hour', 'minute'] }
      ],
      page: 1,
      pageSize: 20,
      totalCount: 0,
      showModal: false,
      isSubmitted: false,
      isEdit: false,
      originalSchedule: null,
      error: '',
      pattern: 'YYYY-MM-DD HH:mm:ss'
    }
  },
  beforeCreate () {
    this.apiParams = this.$getApiParams('createResourceSchedule')
  },
  computed: {
    datePickerLocale () { return this.$i18n.locale === 'ko_KR' ? koKR : enUS },
    cronLocale () {
      if (this.resourceType !== 'AutoScaleVmGroup' || this.$i18n.locale !== 'ko_KR') return undefined
      return {
        '*': {
          prefix: '매',
          suffix: '',
          text: '',
          '*': { empty: { text: '매번' }, value: { text: '{{val.text}}' }, range: { text: '{{start.text}}–{{end.text}}' }, everyX: { text: '{{every.value}}마다' } },
          month: { '*': { prefix: '' }, empty: { text: '매월' }, value: { text: '{{val.alt}}' }, range: { text: '{{start.alt}}–{{end.alt}}' } },
          day: { '*': { prefix: '' }, empty: { text: '매일' }, value: { text: '{{val.text}}일' } },
          dayOfWeek: { '*': { prefix: '' }, empty: { text: '모든 요일' }, value: { text: '{{val.alt}}' }, range: { text: '{{start.alt}}–{{end.alt}}' } },
          hour: { '*': { prefix: '' }, empty: { text: '매시' }, value: { text: '{{val.text}}시' } },
          minute: { '*': { prefix: '' }, empty: { text: '매분' }, value: { text: '{{val.text}}분' } }
        },
        year: { text: '년', dayOfWeek: { '*': { prefix: '' } } },
        month: { text: '월', dayOfWeek: { '*': { prefix: '' } } },
        week: { text: '주' },
        day: { text: '일' }
      }
    },

    pageSizeOptions () {
      var sizes = [20, 50, 100, 200, this.$store.getters.defaultListViewPageSize]
      if (this.device !== 'desktop') {
        sizes.unshift(10)
      }
      return [...new Set(sizes)].sort(function (a, b) {
        return a - b
      }).map(String)
    },
    actions () {
      return this.resourceActionsMap[this.resourceType] || []
    }
  },
  created () {
    this.selectedColumnKeys = this.columnKeys
    if (this.resourceType === 'AutoScaleVmGroup') {
      this.columnKeys = ['enabled', 'description', 'schedule', 'minmembers',
        'maxmembers', 'timezone', 'startdate', 'enddate', 'created', 'scheduleActions']
      this.selectedColumnKeys = [...this.columnKeys]
    }
    this.updateColumns()
    this.pageSize = this.pageSizeOptions[0] * 1
    this.initForm()
    this.fetchData()
    this.fetchTimeZone()
  },
  watch: {
    resource: {
      deep: true,
      handler () {
        this.fetchSchedules()
      }
    }
  },
  methods: {
    initForm () {
      this.formRef = ref()
      this.form = reactive({
        action: this.actions.length > 0 ? this.actions[0].value : null,
        schedule: '* * * * *',
        description: '',
        timezone: 'UTC',
        minMembers: null,
        maxMembers: null,
        startDate: '',
        endDate: '',
        enabled: true,
        useCronFormat: false
      })
      this.rules = reactive({
        schedule: [{ type: 'string', required: true, message: this.$t('message.error.required.input') }],
        action: [{ type: 'string', required: true, message: this.$t('message.error.required.input') }],
        minMembers: [{ required: this.resourceType === 'AutoScaleVmGroup', message: this.$t('message.error.required.input') }],
        maxMembers: [{ required: this.resourceType === 'AutoScaleVmGroup', message: this.$t('message.error.required.input') }],
        timezone: [{ required: true, message: `${this.$t('message.error.select')}` }],
        startDate: [{ required: false, message: `${this.$t('message.error.select')}` }],
        endDate: [{ required: false, message: `${this.$t('message.error.select')}` }]
      })
    },
    createSchedule (schedule) {
      this.resetForm()
      this.showAddModal()
    },
    prepareScheduleRemoval (record) {
      this.pendingScheduleRemoval = { resourceId: this.resource.id, id: record?.id, name: record?.description }
    },
    confirmScheduleRemoval (record) {
      const target = this.pendingScheduleRemoval
      this.pendingScheduleRemoval = null
      if (record && record.id !== target?.id) {
        this.$message.warning(this.$t('message.autoscale.selection.stale'))
        return
      }
      const schedule = this.schedules.find(item => item.id === target?.id)
      if (this.tabLoading || this.isSubmitted || target?.resourceId !== this.resource.id || !schedule) {
        this.$message.warning(this.$t('message.autoscale.selection.stale'))
        return
      }
      return this.removeSchedule(schedule)
    },
    removeSchedule (schedule) {
      return postAPI('deleteResourceSchedule', {
        id: schedule.id,
        resourceid: this.resource.id,
        resourcetype: this.resourceType
      }).then(() => {
        if (this.totalCount - 1 === this.pageSize * (this.page - 1)) {
          this.page = this.page - 1 > 0 ? this.page - 1 : 1
        }
        const message = `${this.$t('label.removing')} ${schedule.description}`
        this.$message.success(message)
      }).catch(error => {
        console.error(error)
        this.$message.error(this.$t('message.error.remove.resource.schedule'))
        this.$notification.error({
          message: this.$t('label.error'),
          description: this.$t('message.error.remove.resource.schedule')
        })
      }).finally(() => {
        this.fetchData()
      })
    },
    updateSchedule (schedule) {
      this.resetForm()
      this.isEdit = true
      this.originalSchedule = { ...schedule }
      Object.assign(this.form, schedule)
      this.form.useCronFormat = true
      this.form.minMembers = schedule?.details?.minmembers ? Number(schedule.details.minmembers) : null
      this.form.maxMembers = schedule?.details?.maxmembers ? Number(schedule.details.maxmembers) : null
      this.form.startDate = schedulePickerDate(schedule.startdate, schedule.timezone)
      this.form.endDate = schedulePickerDate(schedule.enddate, schedule.timezone)
      this.showAddModal()
    },
    showAddModal () {
      this.showModal = true
    },
    submitForm () {
      if (this.isSubmitted) return
      this.isSubmitted = true
      this.formRef.value.validate().then(() => {
        const formRaw = toRaw(this.form)
        const values = this.handleRemoveFields(formRaw)
        if (this.resourceType === 'AutoScaleVmGroup') {
          const validationError = autoScaleScheduleError(values)
          if (validationError) {
            this.$message.error(this.$t(validationError))
            this.isSubmitted = false
            return
          }
        }
        const selectedAction = values.action || (this.actions.length === 1 ? this.actions[0].value : null)
        var params = {
          description: values.description,
          schedule: values.schedule,
          timezone: values.timezone,
          action: selectedAction,
          resourceid: this.resource.id,
          resourcetype: this.resourceType,
          enabled: values.enabled,
          startdate: (values.startDate) ? values.startDate.format(this.pattern) : null,
          enddate: (values.endDate) ? values.endDate.format(this.pattern) : null
        }
        if (this.resourceType === 'AutoScaleVmGroup') {
          params['details[0].minmembers'] = values.minMembers
          params['details[1].maxmembers'] = values.maxMembers
        }
        let command = null
        if (this.form.id === null || this.form.id === undefined) {
          command = 'createResourceSchedule'
        } else {
          params.id = this.form.id
          command = 'updateResourceSchedule'
          if (values.timezone === this.originalSchedule.timezone) {
            if (params.startdate === schedulePickerDate(this.originalSchedule.startdate, values.timezone)?.format(this.pattern)) delete params.startdate
            if (params.enddate === schedulePickerDate(this.originalSchedule.enddate, values.timezone)?.format(this.pattern)) delete params.enddate
          }
          if (this.originalSchedule.enddate && !values.endDate) params.clearenddate = true
        }

        postAPI(command, params).then(response => {
          this.$notification.success({
            message: this.$t('label.schedule'),
            description: this.$t('message.success.config.resource.schedule')
          })
          this.isSubmitted = false
          this.fetchData()
          this.closeModal()
        }).catch(error => {
          this.$notifyError(error)
          this.isSubmitted = false
        })
      }).catch(error => {
        if (this.resourceType !== 'AutoScaleVmGroup' || !error.errorFields) this.$notifyError(error)
        if (error.errorFields !== undefined) {
          this.formRef.value.scrollToField(error.errorFields[0].name)
        }
        this.isSubmitted = false
      })
    },
    resetForm () {
      this.isEdit = false
      if (this.formRef.value) {
        this.formRef.value.resetFields()
      }
    },
    fetchTimeZone (value) {
      this.timeZoneMap = []
      this.fetching = true

      timeZone(value).then(json => {
        this.timeZoneMap = json
        this.fetching = false
      })
    },
    closeModal () {
      this.resetForm()
      this.initForm()
      this.showModal = false
    },
    fetchData () {
      this.fetchSchedules()
    },
    fetchSchedules () {
      const listRequest = this.listRequestToken('fetchSchedules')
      const scope = autoScaleResourceScope(this)
      const same = this.scheduleReadScope === scope
      if (this.resourceType === 'AutoScaleVmGroup') { this.scheduleRefreshing = true; if (!same) this.schedules = [] }

      if (!this.resource.id) {
        return
      }
      const params = {
        page: this.page,
        pagesize: this.pageSize,
        resourceid: this.resource.id,
        resourcetype: this.resourceType,
        listall: true
      }
      this.tabLoading = this.resourceType === 'AutoScaleVmGroup' ? !same : !listRequest.loaded
      return getAPI('listResourceSchedule', params).then(json => {
        if (!this.isListRequestCurrent('fetchSchedules', listRequest)) return
        if (this.resourceType === 'AutoScaleVmGroup' && this.$refs.listview) this.$refs.listview.setSelection([])
        this.totalCount = json?.listresourcescheduleresponse?.count || 0
        const rawSchedules = json?.listresourcescheduleresponse?.resourceschedule || []
        if (this.resourceType === 'AutoScaleVmGroup') this.scheduleReadScope = scope
        this.schedules = rawSchedules.map(s => ({
          ...s,
          minmembers: s.details?.minmembers,
          maxmembers: s.details?.maxmembers
        }))
      }).catch(error => {
        if (!this.isListRequestCurrent('fetchSchedules', listRequest)) return
        listRequest.failed = true
        this.listRefreshFailed = true
        if (listRequest.loaded) return

        console.error(error)
        this.$notifyError(error)
      }).finally(() => {
        if (!this.isListRequestCurrent('fetchSchedules', listRequest)) return

        this.tabLoading = false
        this.scheduleRefreshing = false
      }).finally(() => {
        if (!this.isListRequestCurrent('fetchSchedules', listRequest)) return
        this.tabLoading = false
        this.scheduleRefreshing = false
      })
    },
    changePage (page, pageSize) {
      this.page = page
      this.pageSize = pageSize
      this.fetchData()
    },
    updateSelectedColumns (key) {
      if (this.selectedColumnKeys.includes(key)) {
        this.selectedColumnKeys = this.selectedColumnKeys.filter(x => x !== key)
      } else {
        this.selectedColumnKeys.push(key)
      }
      this.updateColumns()
    },
    generateHumanReadableSchedule (schedule) {
      return cronstrue.toString(schedule, { locale: this.$i18n.locale === 'ko_KR' ? 'ko' : this.$i18n.locale, throwExceptionOnParseError: false, verbose: true })
    },
    updateColumns () {
      this.columns = []
      const columnTitleMap = {
        enabled: this.$t('label.state'),
        startdate: this.$t('label.start.date.and.time'),
        enddate: this.$t('label.end.date.and.time')
      }
      if (this.resourceType === 'AutoScaleVmGroup') columnTitleMap.scheduleActions = this.$t('label.actions')
      for (var columnKey of this.columnKeys) {
        if (!this.selectedColumnKeys.includes(columnKey)) continue
        const column = {
          key: columnKey,
          title: columnTitleMap[columnKey] || this.$t('label.' + String(columnKey).toLowerCase()),
          dataIndex: columnKey
        }
        if (this.resourceType === 'AutoScaleVmGroup' && columnKey === 'enabled') {
          column.key = 'autoscaleScheduleState'
          column.customRender = ({ record }) => h(Status, { text: record.enabled ? 'Enabled' : 'Disabled', displayText: true, displayLabel: this.$t(record.enabled ? 'state.enabled' : 'state.disabled') })
        }
        if (this.resourceType === 'AutoScaleVmGroup' && columnKey === 'scheduleActions') Object.assign(column, { fixed: 'right', width: 90 })
        this.columns.push(column)
      }
      if (this.columns.length > 0) {
        this.columns[this.columns.length - 1].customFilterDropdown = true
      }
    }
  }
}
</script>
