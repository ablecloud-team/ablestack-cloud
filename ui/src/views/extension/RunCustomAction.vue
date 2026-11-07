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
  <div class="form-layout" v-ctrl-enter="handleSubmit">
    <a-alert v-if="loadFailed" type="warning" show-icon :message="$t('message.guest.feature.load.failed')">
      <template #action><a-button size="small" @click="fetchCustomActions">{{ $t('label.refresh') }}</a-button></template>
    </a-alert>
    <a-empty v-if="loaded && !loadFailed && !customActions.length" :description="$t('message.guest.feature.empty.actions')" />
    <a-alert v-if="runFailed" type="error" show-icon :message="$t('message.guest.feature.action.failed')" />
    <a-form
      :ref="formRef"
      :model="form"
      :rules="rules"
      :loading="loading"
      layout="vertical"
      >
      <a-form-item name="customactionid" ref="customactionid">
        <template #label>
          <tooltip-label :title="$t('label.action')" :tooltip="apiParams?.customactionid?.description"/>
        </template>
        <a-select
          showSearch
          :disabled="loading || loadFailed || !customActions.length"
          v-model:value="form.customactionid"
          :placeholder="apiParams?.customactionid?.description"
          optionFilterProp="label"
          :filterOption="(input, option) => {
            return option.label.toLowerCase().indexOf(input.toLowerCase()) >= 0
          }"
          @change="onCustomActionChange" >
          <a-select-option v-for="opt in customActions" :key="opt.id" :label="opt.name || opt.id">
            {{ opt.name || opt.id }}
          </a-select-option>
        </a-select>
      </a-form-item>
      <a-card v-if="form.customactionid && (!!currentDescription || !!currentParameters && currentParameters.length > 0)" style="margin-bottom: 10px;">
        <div v-if="!!currentDescription">
          {{ currentDescription }}
          <a-divider v-if="!!currentParameters && currentParameters.length > 0"/>
        </div>
        <div v-for="(field, fieldIndex) in currentParameters" :key="fieldIndex">
          <a-form-item :name="field.name" :ref="field.name">
            <template #label>
              <tooltip-label :title="field.name" :tooltip="field.name"/>
            </template>
            <a-switch
              v-if="!secretField(field) && field.type === 'BOOLEAN'"
              v-model:checked="form[field.name]"
              :placeholder="field.name"
              v-focus="fieldIndex === 0"
            />
            <a-date-picker
              v-else-if="!secretField(field) && field.type === 'DATE'"
              show-time
              v-model:value="form[field.name]"
              :placeholder="field.name"
              v-focus="fieldIndex === 0"
            />
            <a-select
              v-else-if="!secretField(field) && field.valueoptions && field.valueoptions.length > 0"
              v-model:value="form[field.name]"
              :placeholder="field.name">
              <a-select-option v-for="t in field.valueoptions" :key="t" :value="t">{{ t }}</a-select-option>
            </a-select>
            <a-input-number
              v-else-if="!secretField(field) && ['NUMBER'].includes(field.type)"
              :precision="['DECIMAL'].includes(field.validationformat) ? 2 : 0"
              v-focus="fieldIndex === 0"
              v-model:value="form[field.name]"
              :placeholder="field.name" />
            <a-input-password
              autocomplete="new-password"
              v-else-if="secretField(field)"
              v-focus="fieldIndex === 0"
              v-model:value="form[field.name]"
              :placeholder="field.name" />
            <a-input
              v-else
              v-focus="fieldIndex === 0"
              v-model:value="form[field.name]"
              :placeholder="field.name" />
          </a-form-item>
        </div>
      </a-card>
      <div v-if="canRun" :span="24" class="action-button">
        <a-button @click="closeAction">{{ $t('label.cancel') }}</a-button>
        <a-button :loading="loading" :disabled="!currentAction || loadFailed" ref="submit" type="primary" @click="handleSubmit">{{ $t('label.ok') }}</a-button>
      </div>
    </a-form>
  </div>
</template>

<script>
import { ref, reactive, toRaw, h } from 'vue'
import { getAPI, postAPI } from '@/api'
import { canWriteGuestFeature } from '@/utils/conditionalGuestNetwork'
import TooltipLabel from '@/components/widgets/TooltipLabel'
import DetailsInput from '@/components/widgets/DetailsInput'
import JsonViewer from '@/views/extension/JsonViewer.vue'

export default {
  name: 'RunCustomAction',
  components: {
    TooltipLabel,
    DetailsInput
  },
  props: {
    resource: {
      type: Object,
      required: true
    }
  },
  data () {
    return {
      customActions: [],
      loading: false,
      loaded: false,
      loadFailed: false,
      runFailed: false,
      request: 0
    }
  },
  beforeCreate () {
    this.apiParams = this.$getApiParams('runCustomAction')
  },
  created () {
    this.initForm()
    this.fetchCustomActions()
  },
  computed: {
    canRun () {
      return this.$route.meta.name !== 'guestnetwork' || canWriteGuestFeature('custom.actions', 'runCustomAction', this.resource, this.$store.getters)
    },
    datePattern () {
      return 'YYYY-MM-DDTHH:mm:ssZ'
    },
    resourceType () {
      const metaResourceType = this.$route.meta.resourceType
      if (metaResourceType && !['UserVm', 'DomainRouter', 'SystemVm'].includes(metaResourceType)) {
        return metaResourceType
      }
      return 'VirtualMachine'
    },
    currentAction () {
      if (!this.customActions || this.customActions.length === 0 || !this.form.customactionid) {
        return null
      }
      return this.customActions.find(i => i.id === this.form.customactionid)
    },
    currentDescription () {
      return this.currentAction?.description || ''
    },
    currentParameters () {
      return this.currentAction?.parameters || []
    }
  },
  watch: {
    'resource.id' () {
      this.fetchCustomActions()
    }
  },
  methods: {
    secretField (field) {
      return field.validationformat === 'PASSWORD' || /password|passphrase|secret|(?:api|access|private)[_.-]?key|token/i.test(field.name)
    },
    initForm () {
      this.formRef = ref()
      this.form = reactive({})
      this.rules = reactive({
        customactionid: [{ required: true, message: `${this.$t('message.error.select')}` }]
      })
    },
    fetchCustomActions () {
      const request = ++this.request
      this.loading = true
      this.loaded = false
      this.loadFailed = false
      this.runFailed = false
      this.customActions = []
      this.form.customactionid = undefined
      this.onCustomActionChange()
      const params = {
        resourcetype: this.resourceType,
        resourceid: this.resource.id,
        enabled: true
      }
      return getAPI('listCustomActions', params).then(json => {
        if (request !== this.request) return
        this.customActions = json?.listcustomactionsresponse?.extensioncustomaction || []
        this.loaded = true
      }).catch(() => {
        if (request === this.request) this.loadFailed = true
      }).finally(() => {
        if (request === this.request) this.loading = false
      })
    },
    onCustomActionChange () {
      Object.keys(this.rules).forEach(key => {
        if (key !== 'customactionid') {
          delete this.rules[key]
        }
      })
      Object.keys(this.form).forEach(key => {
        if (key !== 'customactionid') {
          delete this.form[key]
        }
      })
      if (this.currentParameters && this.currentParameters.length > 0) {
        this.currentParameters.forEach(field => {
          const required = !!field.required
          const fieldRules = []
          if (field.type === 'BOOLEAN') {
            this.form[field.name] = false
          }
          // Required rule
          if (required) {
            fieldRules.push({
              required: true,
              message: `${this.$t('message.error.input.value', { field: field.name })}`
            })
          }
          if (field.type === 'STRING' && field.validationformat) {
            let validator
            switch (field.validationformat) {
              case 'EMAIL':
                validator = {
                  type: 'email',
                  message: this.$t('message.error.input.invalidemail')
                }
                break
              case 'URL':
                validator = {
                  type: 'url',
                  message: this.$t('message.error.input.invalidurl')
                }
                break
              case 'UUID':
                validator = {
                  pattern: /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i,
                  message: this.$t('message.error.input.invaliduuid')
                }
                break
            }
            if (validator) {
              fieldRules.push(validator)
            }
          }
          if (fieldRules.length > 0) {
            this.rules[field.name] = fieldRules
          }
        })
      }
    },
    handleSubmit (e) {
      if (e?.preventDefault) e.preventDefault()
      if (this.loading || this.loadFailed || !this.canRun || !this.currentAction) return
      this.loading = true
      this.runFailed = false
      const action = this.currentAction
      const sensitive = this.currentParameters.some(this.secretField)
      this.formRef.value.validate().then(() => {
        if (!this.canRun || action.id !== this.currentAction?.id) return
        const values = toRaw(this.form)
        const params = {
          resourcetype: this.resourceType,
          resourceid: this.resource.id,
          customactionid: values.customactionid
        }
        const keys = this.currentParameters.map(field => field.name).filter(key => key !== 'customactionid')
        for (const key of keys) {
          var value = values[key]
          if (value !== undefined && value != null &&
              (typeof value !== 'string' || (typeof value === 'string' && value.trim().length > 0))) {
            const fieldDef = this.currentParameters.find(f => f.name === key)
            if (fieldDef?.type === 'DATE') {
              value = value.format(this.datePattern)
            }
            params['parameters[0].' + key] = value
          }
        }
        return postAPI('runCustomAction', params).then(response => {
          return this.$pollJob({
            jobId: response.runcustomactionresponse.jobid,
            title: action.name || this.$t('label.run.custom.action'),
            description: action.description || action.name,
            showSuccessMessage: false,
            successMethod: (result) => {
              this.$emit('refresh-data')
              const actionResponse = result.jobresult?.customactionresult || {}
              const success = actionResponse.success || false
              const message = (!sensitive && actionResponse?.result?.message) || this.$t(success ? 'label.success' : 'message.guest.feature.action.failed')
              const details = sensitive ? null : actionResponse?.result?.details
              let printExtensionMessage = false
              let extensionMessage = null
              try {
                const parsedDetails = JSON.parse(details)
                printExtensionMessage = String(parsedDetails?.printmessage).toLowerCase() === 'true'
                extensionMessage = parsedDetails?.message
              } catch (_) {}
              const modalType = success ? this.$success : this.$error
              const contentElements = [h('p', `${message}`)]
              if (printExtensionMessage && extensionMessage !== null && extensionMessage !== undefined) {
                const isArray = Array.isArray(extensionMessage)
                // A "flat" array has only primitive values — safe to render as a table.
                const isFlatArray = isArray && extensionMessage.length > 0 &&
                  extensionMessage.every(row =>
                    typeof row === 'object' && row !== null &&
                    Object.values(row).every(v => v === null || typeof v !== 'object')
                  )
                if (isFlatArray) {
                  contentElements.push(
                    h('div', {
                      style: {
                        marginTop: '1em',
                        maxHeight: '50vh',
                        maxWidth: '100%',
                        overflow: 'auto',
                        backgroundColor: 'var(--ui-bg-surface)',
                        border: '1px solid var(--ui-border-color, currentColor)',
                        borderRadius: '4px',
                        display: 'block'
                      }
                    }, [
                      h('table', {
                        style: {
                          width: '100%',
                          minWidth: 'max-content',
                          borderCollapse: 'collapse',
                          whiteSpace: 'pre-wrap'
                        }
                      }, [
                        h('thead', [
                          h('tr', Object.keys(extensionMessage[0]).map(key =>
                            h('th', {
                              style: {
                                padding: '8px',
                                border: '1px solid var(--ui-border-color, currentColor)',
                                textAlign: 'left',
                                fontWeight: 'bold',
                                backgroundColor: 'var(--ui-bg-surface)'
                              }
                            }, key)
                          ))
                        ]),
                        h('tbody', extensionMessage.map(row =>
                          h('tr', Object.values(row).map(value =>
                            h('td', {
                              style: {
                                padding: '8px',
                                border: '1px solid var(--ui-border-color, currentColor)',
                                fontFamily: 'monospace'
                              }
                            }, String(value))
                          ))
                        ))
                      ])
                    ])
                  )
                } else if (typeof extensionMessage === 'string') {
                  contentElements.push(
                    h('div', {
                      style: {
                        marginTop: '1em',
                        maxHeight: '50vh',
                        padding: '10px',
                        overflowY: 'auto',
                        backgroundColor: 'var(--ui-bg-surface)',
                        border: '1px solid var(--ui-border-color, currentColor)',
                        borderRadius: '4px',
                        fontFamily: 'monospace',
                        whiteSpace: 'pre-wrap',
                        wordBreak: 'break-word'
                      }
                    }, extensionMessage)
                  )
                } else {
                  // Complex object or non-flat array — render as interactive JSON tree.
                  contentElements.push(
                    h('div', { style: { marginTop: '1em' } }, [
                      h(JsonViewer, { data: extensionMessage })
                    ])
                  )
                }
              }

              modalType({
                title: action.name || this.$t('label.run.custom.action'),
                content: h('div', contentElements),
                width: '700px',
                okText: this.$t('label.ok')
              })
            },
            errorMessage: action.name || this.$t('label.run.custom.action'),
            loadingMessage: this.$t('message.running.custom.action'),
            catchMessage: this.$t('error.fetching.async.job.result')
          }).then(result => {
            if (result.jobstatus === 1 && result.jobresult?.customactionresult?.success) this.$emit('close-action')
            else this.runFailed = true
          })
        }).catch(() => {
          this.runFailed = true
        }).finally(() => {
          this.currentParameters.filter(this.secretField).forEach(field => { delete this.form[field.name] })
        })
      }).catch(() => {
        // Form fields already expose validation errors without echoing input.
      }).finally(() => {
        this.loading = false
      })
    },
    closeAction () {
      if (!this.loading) this.$emit('close-action')
    }
  }
}
</script>

<style scoped lang="less">
.form-layout {
  max-width: 100%;
  width: 100%;
  @media (min-width: 600px) {
    width: 550px;
  }
}
</style>
