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
  <a-list :class="{ 'configuration-value-editor--dialog': dialogMode }" :data-list-editing="(hasChanges || valueLoading) ? 'true' : null">
    <a-list-item>
      <template v-if="dialogMode !== 'reset'">
        <span v-if="configrecord.type ==='Boolean'">
          <a-tooltip :title="editableValue?'true':'false'">
            <a-switch
              :disabled="!canUpdate"
              v-model:checked="editableValue"
              @keydown.esc="cancelEditConfigurationValue"
              @pressEnter="updateConfigurationValue(configrecord)"
            />
           </a-tooltip>
        </span>
        <span v-else-if="configrecord.type ==='Number'">
          <a-tooltip :title="editableValue">
            <a-input-number
              style="width: var(--configuration-value-width, 20vw);"
              :disabled="!canUpdate"
              v-model:value="editableValue"
              @keydown.esc="cancelEditConfigurationValue"
              @pressEnter="updateConfigurationValue(configrecord)"
              @keydown="e => handleInputNumberKeyDown(e, false)"
            />
          </a-tooltip>
        </span>
        <span v-else-if="configrecord.type ==='Decimal'">
          <a-tooltip :title="editableValue">
            <a-input-number
              style="width: var(--configuration-value-width, 20vw)"
              :disabled="!canUpdate"
              v-model:value="editableValue"
              @keydown.esc="cancelEditConfigurationValue"
              @pressEnter="updateConfigurationValue(configrecord)"
              @keydown="e => handleInputNumberKeyDown(e, true)"
            />
          </a-tooltip>
        </span>
        <span v-else-if="configrecord.type ==='Range'">
          <a-row>
            <a-col>
              <a-tooltip :title="editableValue">
                <a-slider
                  style="width: var(--configuration-slider-width, 13vw)"
                  class="config-slider-value"
                  :min="0"
                  :max="100"
                  :disabled="!canUpdate"
                  v-model:value="editableValue"
                  @keydown.esc="cancelEditConfigurationValue"
                  @pressEnter="updateConfigurationValue(configrecord)"
                />
              </a-tooltip>
            </a-col>
            <a-col>
              <a-tooltip :title="editableValue">
                <a-input-number
                  style="width: var(--configuration-slider-input-width, 5vw); margin-left: 10px; float: right"
                  class="config-slider-text"
                  :min="0"
                  :max="100"
                  :disabled="!canUpdate"
                  :formatter="(value) => `${value}%`"
                  v-model:value="editableValue"
                  @keydown.esc="cancelEditConfigurationValue"
                  @pressEnter="updateConfigurationValue(configrecord)"
                  @keydown="e => handleInputNumberKeyDown(e, true)"
                />
              </a-tooltip>
            </a-col>
          </a-row>
        </span>
        <span v-else-if="configrecord.type === 'Select'">
          <a-tooltip :title="editableValue">
            <a-select
              style="width: var(--configuration-value-width, 20vw)"
              :disabled="!canUpdate"
              v-model:value="editableValue"
              @keydown.esc="cancelEditConfigurationValue"
              @pressEnter="updateConfigurationValue(configrecord)">
              <a-select-option
                v-for="value of configrecord.options.split(',')"
                :key="value">
                {{ value }}
              </a-select-option>
            </a-select>
          </a-tooltip>
        </span>
        <span v-else-if="configrecord.type === 'Order' || configrecord.type === 'WhitespaceSeparatedListWithOptions'">
          <a-tooltip :title="editableValue.join(', ')">
            <b v-if="configrecord.type === 'Order'">
              {{ $t('message.select.deselect.to.sort') }}
            </b>
            <b v-else>
              {{ $t('message.select.deselect.desired.options') }}
            </b>
            <br />
            <a-select
              style="width: var(--configuration-value-width, 20vw)"
              mode="multiple"
              :disabled="!canUpdate"
              v-model:value="editableValue"
              @keydown.esc="cancelEditConfigurationValue"
              @pressEnter="updateConfigurationValue(configrecord)">
              <a-select-option
                v-for="value of configrecord.options.split(',')"
                :key="value">
                {{ value }}
              </a-select-option>
            </a-select>
          </a-tooltip>
        </span>
        <span v-else-if="configrecord.type === 'CSV'">
          <a-tooltip :title="editableValue.join(', ')">
            <b>{{ $t('message.type.values.to.add') }}</b>
            <br />
            <a-select
              style="width: var(--configuration-value-width, 20vw)"
              mode="tags"
              :disabled="!canUpdate"
              v-model:value="editableValue"
              @keydown.esc="cancelEditConfigurationValue"
              @pressEnter="updateConfigurationValue(configrecord)">
            </a-select>
          </a-tooltip>
        </span>
        <span v-else-if="dialogMode && isSensitiveConfiguration(configrecord)">
          <a-input-password
            v-model:value="editableValue"
            :visibility-toggle="false"
            :disabled="!canUpdate"
            :aria-label="$t('label.global.setting.current')"
            @keydown.esc="cancelEditConfigurationValue"
            @press-enter="updateConfigurationValue(configrecord)" />
        </span>
        <span v-else>
          <a-tooltip :title="editableValue">
            <a-textarea
              style="width: var(--configuration-value-width, 20vw); word-break: break-all"
              :disabled="!canUpdate"
              v-model:value="editableValue"
              @keydown.esc="cancelEditConfigurationValue"
              @pressEnter="updateConfigurationValue(configrecord)"
            />
          </a-tooltip>
        </span>
      </template>
      <mold-dialog-actions v-if="dialogMode" :busy="valueLoading">
        <a-button @click="$emit('cancel')">{{ $t('label.cancel') }}</a-button>
        <a-button
          v-if="dialogMode === 'reset'"
          type="primary"
          :disabled="!canReset"
          @click="confirmResetConfiguration(configrecord)">
          {{ $t('label.ok') }}
        </a-button>
        <a-button
          v-else
          type="primary"
          :disabled="!canUpdate || !hasChanges"
          @click="updateConfigurationValue(configrecord)">
          {{ $t('label.ok') }}
        </a-button>
      </mold-dialog-actions>
      <span v-else class="actions">
        <tooltip-button
          :tooltip="$t('label.cancel')"
          @onClick="cancelEditConfigurationValue"
          v-if="hasChanges"
          iconType="CloseCircleTwoTone"
          iconTwoToneColor="#f5222d"
          :disabled="valueLoading" />
        <tooltip-button
          :tooltip="$t('label.ok')"
          @onClick="updateConfigurationValue(configrecord)"
          v-if="hasChanges"
          iconType="CheckCircleTwoTone"
          iconTwoToneColor="#52c41a"
          :disabled="!canUpdate" />
        <tooltip-button
          :tooltip="$t('label.reset.config.value')"
          @onClick="confirmResetConfiguration(configrecord)"
          v-else
          icon="reload-outlined"
          :disabled="!canReset" />
      </span>
    </a-list-item>
  </a-list>
</template>
<script>
import { postAPI } from '@/api'
import TooltipButton from '@/components/widgets/TooltipButton'
import { netBackupScheduleModeKey, netBackupHookTimeoutKey, netBackupScheduleModeError } from '@/utils/netbackupScheduleMode'
import MoldDialogActions from '@/components/view/MoldDialogActions'
import { isSensitiveConfiguration } from './configurationPresentation'
import { configurationScopeParams } from './configurationScope'

export default {
  name: 'ConfigurationValue',
  components: {
    TooltipButton,
    MoldDialogActions
  },
  emits: ['change-config', 'refresh', 'saved', 'cancel'],
  props: {
    dialogMode: {
      type: String,
      default: '',
      validator: mode => ['', 'edit', 'reset'].includes(mode)
    },
    scope: {
      type: Object,
      default: null
    },
    configrecord: {
      type: Object,
      required: true
    },
    loading: {
      type: Boolean,
      default: false
    },
    configDisabled: {
      type: Boolean,
      default: false
    },
    actions: {
      type: Array,
      default: () => []
    },
    resource: {
      type: Object,
      required: false
    }
  },
  data () {
    return {
      valueLoading: this.loading,
      actualValue: null,
      editableValue: null
    }
  },
  computed: {
    hasChanges () {
      if (Array.isArray(this.actualValue) && Array.isArray(this.editableValue)) {
        return this.actualValue.length !== this.editableValue.length ||
          this.actualValue.some((value, index) => value !== this.editableValue[index])
      }
      return !Object.is(this.actualValue, this.editableValue)
    },
    canUpdate () {
      return 'updateConfiguration' in this.$store.getters.apis && !this.configDisabled && !this.valueLoading &&
        this.requestScope('updateConfiguration') !== null
    },
    canReset () {
      return 'resetConfiguration' in this.$store.getters.apis && !this.configDisabled && !this.valueLoading &&
        this.requestScope('resetConfiguration') !== null && !this.hasChanges &&
        (this.scope !== null || this.configrecord.value !== this.configrecord.defaultvalue)
    },
    isNetBackupScheduleMode () { return this.configrecord.name === netBackupScheduleModeKey },
    isNetBackupHookTimeout () { return this.configrecord.name === netBackupHookTimeoutKey }
  },
  created () {
    this.setConfigData()
  },
  watch: {
    configrecord: {
      handler (record, previous) {
        if (!this.valueLoading) {
          const keepDraft = this.hasChanges && record.name === previous?.name && record.type === previous?.type
          this.setConfigData(record, keepDraft)
        }
      },
      deep: true
    }
  },
  methods: {
    isSensitiveConfiguration,
    requestScope (command) {
      const { type, id } = this.scope || {
        type: this.$route.meta.name,
        id: this.$route.params?.id || this.resource?.id
      }
      return configurationScopeParams(type, id, command)
    },
    setConfigData (record = this.configrecord, keepDraft = false) {
      this.actualValue = this.getEditableValue(record)
      if (!keepDraft) {
        this.restoreValue()
      }
    },
    restoreValue () {
      this.editableValue = Array.isArray(this.actualValue) ? [...this.actualValue] : this.actualValue
    },
    confirmScheduleMode (enabled, action, cancel) {
      return this.$confirm({
        title: this.$t('message.netbackup.schedule.mode.confirm'),
        content: this.$t(enabled ? 'message.netbackup.schedule.mode.enable' : 'message.netbackup.schedule.mode.disable'),
        okText: this.$t('label.ok'),
        cancelText: this.$t('label.cancel'),
        onOk: action,
        onCancel: cancel
      })
    },
    confirmResetConfiguration (configrecord) {
      if (this.isNetBackupScheduleMode) {
        return this.confirmScheduleMode(configrecord.defaultvalue === 'true', () => this.resetConfigurationValue(configrecord))
      }
      if (this.dialogMode === 'reset') return this.resetConfigurationValue(configrecord)
      return this.$resetConfigurationValueConfirm(configrecord, this.resetConfigurationValue)
    },
    updateConfigurationValue (configrecord, confirmed = false) {
      if (!this.canUpdate || !this.hasChanges) return
      if (this.isNetBackupScheduleMode && !confirmed && this.editableValue !== this.actualValue) {
        return this.confirmScheduleMode(this.editableValue, () => this.updateConfigurationValue(configrecord, true), () => this.cancelEditConfigurationValue(configrecord))
      }
      let configRecordEntry = this.configrecord
      let saved = false
      this.valueLoading = true
      var newValue = this.editableValue
      if (configrecord.type === 'Range') {
        newValue = newValue / 100
      }
      if (['Order', 'CSV'].includes(configrecord.type)) {
        newValue = newValue.join(',')
      }
      if (configrecord.type === 'WhitespaceSeparatedListWithOptions') {
        newValue = newValue.join(' ')
      }
      const scope = this.requestScope('updateConfiguration')
      const params = { ...scope, name: configrecord.name, value: newValue }
      return postAPI('updateConfiguration', params).then(json => {
        // The updateConfiguration response can return a stale value for
        // non-global scopes (server-side issue). Trust the value the user
        // just submitted — the API returned success, so we know it was persisted.
        const apiRecord = json.updateconfigurationresponse.configuration
        configRecordEntry = Object.keys(scope).length ? { ...apiRecord, value: String(newValue) } : apiRecord
        this.setConfigData(configRecordEntry)
        saved = true
        this.$emit('change-config', { value: newValue })
        this.$store.dispatch('RefreshFeatures')
        this.$messageConfigSuccess(`${this.$t('message.setting.updated')} ${configrecord.name}`, configrecord)
        this.$notifyConfigurationValueChange(configRecordEntry)
      }).catch(error => {
        this.restoreValue()
        console.error(error)
        this.$message.error(this.$t('message.error.save.setting'))
        this.$notification.error({
          message: this.$t('label.error'),
          description: netBackupScheduleModeError(error, (key, params) => this.$t(key, params), this.$t('message.error.save.setting'))
        })
      }).finally(() => {
        this.valueLoading = false
        this.$emit('refresh', configrecord.name, configRecordEntry)
        if (saved) this.$emit('saved')
      })
    },
    resetConfigurationValue (configrecord) {
      if (!this.canReset) return
      let configRecordEntry = this.configrecord
      let saved = false
      this.valueLoading = true
      const params = { ...this.requestScope('resetConfiguration'), name: configrecord.name }
      return postAPI('resetConfiguration', params).then(json => {
        configRecordEntry = json.resetconfigurationresponse.configuration
        this.setConfigData(configRecordEntry)
        saved = true
        var newValue = this.editableValue
        if (configrecord.type === 'Range') {
          newValue = newValue / 100
        }
        this.$emit('change-config', { value: newValue })
        this.$store.dispatch('RefreshFeatures')
        this.$messageConfigSuccess(`${this.$t('label.setting')} ${configrecord.name} ${this.$t('label.reset.config.value')}`, configrecord)
        this.$notifyConfigurationValueChange(json?.resetconfigurationresponse?.configuration || null)
      }).catch(error => {
        this.restoreValue()
        console.error(error)
        this.$message.error(this.$t('message.error.reset.config'))
        this.$notification.error({
          message: this.$t('label.error'),
          description: netBackupScheduleModeError(error, (key, params) => this.$t(key, params), this.$t('message.error.reset.config'))
        })
      }).finally(() => {
        this.valueLoading = false
        this.$emit('refresh', configrecord.name, configRecordEntry)
        if (saved) this.$emit('saved')
      })
    },
    getEditableValue (configrecord) {
      if (configrecord.type === 'Range') {
        return Number(configrecord.value) * 100 || 0
      }
      if (configrecord.type === 'Boolean') {
        return configrecord.value === 'true'
      }
      if (configrecord.type === 'Number' || configrecord.type === 'Decimal') {
        if (configrecord.value) {
          return Number(configrecord.value)
        } else if (configrecord.defaultvalue) {
          return Number(configrecord.defaultvalue)
        }
        return 0
      }
      if (['Order', 'CSV'].includes(configrecord.type)) {
        if (configrecord.value && configrecord.value.length > 0) {
          return String(configrecord.value).split(',')
        } else {
          return []
        }
      }
      if (configrecord.type === 'WhitespaceSeparatedListWithOptions') {
        if (configrecord.value && configrecord.value.length > 0) {
          return String(configrecord.value).split(' ')
        }

        return []
      }
      if (configrecord.value) {
        return String(configrecord.value)
      }
      if (configrecord.defaultvalue) {
        return String(configrecord.defaultvalue)
      }
      return ''
    },
    cancelEditConfigurationValue () {
      if (!this.valueLoading) {
        this.restoreValue()
      }
    },
    handleInputNumberKeyDown (event, isDecimal) {
      if (event.isComposing || event.ctrlKey || event.metaKey || event.altKey || event.key.length !== 1) return

      const allowed = isDecimal ? /^[0-9.-]$/ : /^[0-9-]$/
      if (!allowed.test(event.key)) {
        event.preventDefault()
      }
    }
  }
}
</script>

<style lang="scss" scoped>
.actions {
    margin-left: 10px;
    width: 100px;
}
</style>
