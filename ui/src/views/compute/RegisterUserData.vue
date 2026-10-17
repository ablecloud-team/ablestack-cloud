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
  <MoldDialog
    class="userdata-registration-dialog"
    size="md"
    :closable="!busy"
    :keyboard="!busy"
    @cancel="closeAction">
    <template #title>
      {{ $t(cloneMode ? 'label.userdata.clone' : isUserData ? 'label.register.user.data' : 'label.register.cni.config') }}
      <a v-if="currentAction.docHelp && (!isUserData || $config?.docBase)" :href="documentationUrl($config?.docBase, currentAction.docHelp, $i18n.locale)" :aria-label="$t('label.help')" target="_blank" rel="noopener noreferrer"><question-circle-outlined /></a>
    </template>
    <div ref="content" class="userdata-registration-content" :class="{ 'userdata-registration-content--cni': !isUserData }" :aria-busy="busy">
      <template v-if="cloneMode">
        <a-alert type="info" show-icon :message="$t('message.userdata.clone.intro')" />
        <p v-if="source">{{ $t('label.userdata.clone.source') }}: {{ source.name }} ({{ source.id }})<br>{{ source.project || source.account }} / {{ source.domain }}</p>
        <p v-if="source" class="clone-usage-links"><a v-for="kind in ['vms', 'templates', 'isos']" :key="kind" :href="$router.resolve('/userdata/' + source.id + '?tab=userdata.usage.' + kind).href" :aria-disabled="busy" :tabindex="busy ? -1 : undefined" @click="openCloneRoute($event, '/userdata/' + source.id + '?tab=userdata.usage.' + kind)">{{ $t('label.userdata.usage.' + kind) }}</a></p>
        <a-button v-if="!source && !sourceLoading" @click="loadCloneSource">{{ $t('label.retry') }}</a-button>
      </template>
      <template v-if="createdVersion">
        <a-alert type="success" show-icon role="status" :message="$t('message.userdata.clone.created')" />
        <p><a :href="$router.resolve('/userdata/' + createdVersion.id + '?tab=userdata.versions').href" :aria-disabled="busy" :tabindex="busy ? -1 : undefined" @click="openCloneRoute($event, '/userdata/' + createdVersion.id + '?tab=userdata.versions')">{{ createdVersion.name }} ({{ createdVersion.id }})</a></p>
        <a-alert :type="noteSaved ? 'success' : 'warning'" show-icon role="status" :message="$t(noteSaved ? 'message.userdata.clone.note.saved' : 'message.userdata.clone.note.pending')" />
        <details v-if="!noteSaved"><summary>{{ $t('label.userdata.clone.note') }}</summary><UserDataContent :resource="createdVersion" tab="userdata.content" :preview-encoded="notePreview" /></details>
      </template>
      <a-spin v-else :spinning="busy">
        <p v-if="!cloneMode && isUserData" v-html="$t('message.desc.register.user.data')"></p>
        <a-form
          v-if="!cloneMode || source"
          id="userdata-registration-form"
          @keydown.ctrl.enter.prevent="handleSubmit"
          :ref="formRef"
          :model="form"
          :rules="rules"
          @finish="handleSubmit"
          @finishFailed="focusValidationError"
          layout="vertical">
          <a-form-item name="name" ref="name" :validate-first="!isUserData">
            <template #label>
              <tooltip-label :title="$t('label.name')" :tooltip="isUserData ? apiParams.name.description : $t('message.cni.validation.name')"/>
            </template>
            <a-input
              ref="nameInput"
              @keydown.enter.prevent="handleSubmit"
              v-model:value="form.name"
              :disabled="busy || !!cloneAttempt"
              :placeholder="isUserData ? apiParams.name.description : undefined"
              v-focus="true" />
          </a-form-item>
          <div v-if="isUserData">
            <a-form-item name="userdata" ref="userdata">
              <template #label>
                <tooltip-label :title="$t('label.user.data')" :tooltip="$t('label.register.user.data.details')"/>
              </template>
              <a-textarea
                ref="userdataInput"
                v-model:value="form.userdata"
                :disabled="busy || !!cloneAttempt"
                :placeholder="$t('label.register.user.data.details')"/>
            </a-form-item>
          </div>
          <div v-else>
            <CniFileImport :disabled="loading || sourceLoading || variablesReading || !allowedApi" :max-length="maxEncodedLength" :context="cniScope" @reading="fileReading = $event" @apply="applyCniFile">
              <CniVariableTools mode="extract" :encoded="cniDraftEncoded" :params="form.params" :scope="cniScope" :disabled="loading || sourceLoading || fileReading || !allowedApi" @reading="variablesReading = $event" @apply="keys => { form.params = keys }" />
            </CniFileImport>
            <a-form-item name="cniconfig" ref="cniconfig" validate-first>
              <template #label>
                <tooltip-label :title="$t('label.cniconfiguration')" :tooltip="$t('message.cni.validation.content.help') + (limitLoaded ? ' ' + $t('message.cni.validation.limit', { max: maxEncodedLength.toLocaleString() }) : '')"/>
              </template>
              <a-textarea
                ref="cniconfigInput"
                v-model:value="form.cniconfig"
                :disabled="busy"
                :auto-size="{ minRows: 6, maxRows: 12 }"/>
            </a-form-item>
          </div>
          <a-form-item name="isbase64" ref="isbase64" :label="$t('label.is.base64.encoded')">
            <a-checkbox v-model:checked="form.isbase64" :disabled="busy || !!cloneAttempt"></a-checkbox>
            <template v-if="cloneMode">
              <p>{{ $t('message.userdata.clone.encoding') }}</p>
              <a-button :disabled="busy || !!cloneAttempt || !draft?.text || !form.isbase64" @click="editCloneText">{{ $t('label.userdata.clone.edit.text') }}</a-button>
            </template>
          </a-form-item>
          <a-form-item name="params" ref="params">
            <template #label>
              <tooltip-label
                :title="isUserData ? $t('label.userdataparams') : $t('label.cniconfigparams')"
                :tooltip="$t(isUserData ? 'message.userdata.validation.help' : 'message.cni.validation.help')"/>
            </template>
            <a-select
              ref="paramsInput"
              mode="tags"
              v-model:value="form.params"
              :disabled="busy || !!cloneAttempt"
              showSearch
              optionFilterProp="value"
              :filterOption="(input, option) => {
                return option.value.toLowerCase().indexOf(input.toLowerCase()) >= 0
              }"
              :placeholder="isUserData ? apiParams.params.description : undefined">
            </a-select>
          </a-form-item>
          <a-form-item name="domainid" ref="domainid" v-if="!cloneMode && isAdminOrDomainAdmin() && !cniProject">
            <template #label>
              <tooltip-label :title="$t('label.domainid')" :tooltip="apiParams.domainid.description"/>
            </template>
            <a-select
              id="domain-selection"
              v-model:value="form.domainid"
              :disabled="loading"
              showSearch
              optionFilterProp="label"
              :filterOption="(input, option) => {
                return option.label.toLowerCase().indexOf(input.toLowerCase()) >= 0
              }"
                :loading="domainLoading"
              :placeholder="apiParams.domainid.description"
              @change="val => { handleDomainChanged(domains[val]) }">
              <a-select-option
                v-for="(opt, optIndex) in domains"
                :key="optIndex"
                :label="opt.path || opt.name || opt.description || ''">
                {{ opt.path || opt.name || opt.description }}
              </a-select-option>
            </a-select>
          </a-form-item>
          <a-form-item name="account" ref="account" v-if="!cloneMode && isAdminOrDomainAdmin() && !cniProject && !isObjectEmpty(selectedDomain) && selectedDomain.id !== null">
            <template #label>
              <tooltip-label :title="$t('label.account')" :tooltip="apiParams.account.description"/>
            </template>
            <a-input
              v-model:value="form.account"
              @keydown.enter.prevent="handleSubmit"
              :disabled="loading"
              :placeholder="apiParams.account.description"/>
          </a-form-item>

          <a-form-item v-if="cniProject" :label="$t('label.project')"><span>{{ cniProject.name || cniProject.id }}</span></a-form-item>
          <!-- Native Enter submits; textarea and select keep their own Enter behavior. -->
          <template v-if="cloneMode && source">
            <a-alert v-if="difference" :type="difference.changed ? 'info' : 'warning'" show-icon role="status" :message="$t(difference.changed ? 'message.userdata.clone.changed' : 'message.userdata.clone.duplicate')" />
            <p v-if="difference">{{ $t('message.userdata.clone.diff', difference) }}<br>{{ $t('label.userdataparams') }}: {{ source.params || '-' }} → {{ draftParams || '-' }}</p>
            <details><summary>{{ $t('label.userdata.clone.source.body') }}</summary><UserDataContent :resource="source" tab="userdata.content" :preview-encoded="source.userdata" /></details>
            <details v-if="draft"><summary>{{ $t('label.userdata.clone.draft.body') }}</summary><UserDataContent :resource="source" tab="userdata.content" :preview-encoded="draft.encoded" /></details>
          </template>
          <button type="submit" hidden aria-hidden="true" tabindex="-1"></button>
        </a-form>
      </a-spin>
      <p v-if="loading" role="status">{{ $t(isUserData ? 'message.userdata.submitting' : 'message.cni.submitting') }}</p>
      <a-alert v-if="error" class="mold-dialog-section" role="alert" type="error" show-icon :message="error" />
    </div>
    <template #footer>
      <a-button ref="closeButton" :disabled="busy" @click="closeAction">{{ $t(createdVersion ? 'label.close' : 'label.cancel') }}</a-button>
      <a-button
        ref="submit"
        form="userdata-registration-form"
        html-type="submit"
        v-if="!createdVersion || !noteSaved"
        :loading="busy"
        :disabled="!allowedApi || (cloneMode && !source)"
        type="primary"
        @click="handleSubmit"
        @keydown.ctrl.enter.prevent="handleSubmit">{{ $t(createdVersion ? 'label.userdata.clone.note.retry' : cloneAttempt ? 'label.userdata.clone.reconcile' : cloneMode ? 'label.userdata.clone.create' : isUserData ? 'label.register.userdata.action' : 'label.register.cni.action') }}</a-button>
    </template>
  </MoldDialog>
</template>

<script>
import { ref, reactive, toRaw } from 'vue'
import { getAPI, postAPI } from '@/api'
import TooltipLabel from '@/components/widgets/TooltipLabel'
import MoldDialog from '@/components/view/MoldDialog'
import { canonicalUserData, userDataName, userDataParams, isUserDataRoute } from '@/utils/userDataRegistration'
import { canonicalCni, cniName, cniParams } from '@/utils/cniRegistration'
import { userDataContent } from '@/utils/userDataContent'
import { cloneApis, cloneOwner, readCloneSource, findCloneAttempt, cloneDifference, cloneRecord, saveCloneNote } from '@/utils/userDataVersions'
import UserDataContent from './UserDataContent'
import CniFileImport from './CniFileImport'
import CniVariableTools from './CniVariableTools'
import { documentationUrl } from '@/utils/cniFileImport'

export default {
  name: 'registerUserData',
  props: { currentAction: { type: Object, default: () => ({}) }, resource: { type: Object, default: () => ({}) } },
  emits: ['refresh-data', 'close-action'],
  components: {
    TooltipLabel, MoldDialog, UserDataContent, CniFileImport, CniVariableTools
  },
  data () {
    return {
      fileReading: false,
      variablesReading: false,
      domains: [],
      domainLoading: false,
      selectedDomain: {},
      loading: false,
      error: '',
      disposed: false,
      source: null,
      sourceLoading: false,
      sourceVersion: 0,
      cloneAttempt: null,
      createdVersion: null,
      noteSaved: false,
      note: '',
      limitLoaded: false,
      maxEncodedLength: 1048576
    }
  },
  computed: {
    isUserData () { return isUserDataRoute(this.$route) },
    apiName () { return this.isUserData ? 'registerUserData' : 'registerCniConfiguration' },
    cniProject () {
      const project = this.$store.getters.project
      return !this.isUserData && project?.id && project.id !== '-1' && !this.$store.getters.listAllProjects ? project : null
    },
    cniScope () { return JSON.stringify([this.apiName, this.$route.path, this.$store.getters.project?.id, this.$store.getters.listAllProjects, this.$store.getters.userInfo?.id, this.form?.domainid, this.form?.account, this.allowedApi]) },
    cloneMode () { return this.isUserData && !!this.currentAction?.cloneUserData },
    allowedApi () { return this.cloneMode ? cloneApis.every(api => api in this.$store.getters.apis) : this.apiName in this.$store.getters.apis },
    busy () { return this.loading || this.sourceLoading || this.fileReading || this.variablesReading },
    cniDraftEncoded () { try { return canonicalCni(this.form.cniconfig, this.form.isbase64, this.maxEncodedLength) } catch (_) { return '' } },
    blocksNavigation () { return this.busy },
    cloneScope () { return JSON.stringify([this.resource?.id, this.resource?.name, this.resource?.account, this.resource?.domainid, this.resource?.projectid, this.$route.path, this.$store.getters.project?.id, this.$store.getters.listAllProjects, this.$store.getters.userInfo?.id, this.$store.state?.user?.token, this.allowedApi]) },
    draft () { try { return userDataContent(canonicalUserData(this.form.userdata, this.form.isbase64, this.maxEncodedLength)) } catch (error) { return null } },
    draftParams () { try { return userDataParams(this.form.params) } catch (error) { return null } },
    difference () { return this.source && this.draft ? cloneDifference(this.source, this.draft.encoded, this.draftParams) : null },
    notePreview () { return btoa(unescape(encodeURIComponent(this.note))) }
  },
  watch: {
    cloneScope () {
      if (!this.cloneMode) return
      this.sourceVersion++; this.source = null; this.sourceLoading = false; this.cloneAttempt = null; this.createdVersion = null; this.noteSaved = false; this.note = ''; this.error = ''
      this.form.name = ''; this.form.userdata = ''; this.form.params = []
      this.loadCloneSource()
    }
  },
  beforeCreate () {
    this.apiParams = isUserDataRoute(this.$route) ? this.$getApiParams('registerUserData') : this.$getApiParams('registerCniConfiguration')
  },
  created () {
    this.initForm()
    this.domains = [
      {
        id: null,
        name: ''
      }
    ]
    this.fetchData()
  },
  mounted () { window.addEventListener('beforeunload', this.beforeUnload) },
  beforeUnmount () { this.disposed = true; window.removeEventListener('beforeunload', this.beforeUnload) },
  methods: {
    documentationUrl,
    applyCniFile (encoded) {
      if (this.isUserData || this.busy || !this.allowedApi) return
      this.form.cniconfig = canonicalCni(encoded, true, this.maxEncodedLength)
      this.form.isbase64 = true
      if (this.formRef.value?.clearValidate) this.formRef.value.clearValidate('cniconfig')
      this.$nextTick(() => this.$refs.cniconfigInput?.focus())
    },
    initForm () {
      this.formRef = ref()
      this.form = reactive({
        isbase64: false
      })
      this.rules = reactive({
        name: [{ required: true, message: this.$t('message.error.name') }],
        userdata: [{ required: true, message: this.$t('message.error.user.data') }],
        cniconfig: [{ required: true, message: this.$t('message.cni.validation.empty') }]
      })
      const validate = fn => async () => {
        try { fn() } catch (error) { throw new Error(this.$t(error.message)) }
      }
      this.rules.name.push({ validator: validate(() => this.isUserData ? userDataName(this.form.name) : cniName(this.form.name)) })
      const field = this.isUserData ? 'userdata' : 'cniconfig'
      this.rules[field].push({
        validator: validate(() => this.isUserData
          ? canonicalUserData(this.form.userdata, this.form.isbase64, this.maxEncodedLength)
          : canonicalCni(this.form.cniconfig, this.form.isbase64, this.maxEncodedLength))
      })
      this.rules.params = [{ validator: validate(() => this.isUserData ? userDataParams(this.form.params) : cniParams(this.form.params)) }]
    },
    fetchData () {
      getAPI('listCapabilities', {}, { preserveOnFailure: true }).then(json => {
        const max = json.listcapabilitiesresponse?.capability?.userdatamaxlength
        if (!this.disposed && Number.isInteger(max) && max > 0) { this.maxEncodedLength = Math.min(max, 1048576); this.limitLoaded = true }
      }).catch(() => { /* The server still enforces its current configured limit. */ })
      if (this.cloneMode) this.loadCloneSource()
      else if (this.isAdminOrDomainAdmin()) {
        this.fetchDomainData()
      }
    },
    isAdminOrDomainAdmin () {
      return ['Admin', 'DomainAdmin'].includes(this.$store.getters.userInfo.roletype)
    },
    isValidValueForKey (obj, key) {
      return key in obj && obj[key] != null
    },
    arrayHasItems (array) {
      return array !== null && array !== undefined && Array.isArray(array) && array.length > 0
    },
    isObjectEmpty (obj) {
      return !(obj !== null && obj !== undefined && Object.keys(obj).length > 0 && obj.constructor === Object)
    },
    fetchDomainData () {
      const params = {}
      this.domainLoading = true
      getAPI('listDomains', params, { preserveOnFailure: true }).then(json => {
        if (!this.disposed) this.domains = this.domains.concat(json.listdomainsresponse.domain || [])
      }).catch(error => {
        if (!this.disposed) this.error = this.errorMessage(error)
      }).finally(() => {
        if (this.disposed) return
        this.domainLoading = false
        if (this.arrayHasItems(this.domains)) {
          this.form.domainid = 0
          this.handleDomainChanged(this.domains[0])
        }
      })
    },
    handleDomainChanged (domain) {
      this.selectedDomain = domain
    },
    formPosition () {
      const body = this.$refs.content?.closest('.ant-modal-body')
      return { focus: document.activeElement, body, top: body?.scrollTop || 0 }
    },
    restoreFormPosition (position) {
      this.$nextTick(() => {
        if (this.disposed) return
        if (position.focus?.isConnected) position.focus.focus({ preventScroll: true })
        else if (this.createdVersion) {
          const close = this.$refs.closeButton?.$el || this.$refs.closeButton
          if (close?.focus) close.focus({ preventScroll: true })
        }
        if (position.body) position.body.scrollTop = position.top
      })
    },
    errorMessage (error) {
      const response = Object.values(error?.response?.data || {}).find(value => value?.errortext)
      return response?.errortext || error?.message || this.$t('message.network.error')
    },
    focusValidationError (error) {
      const field = error?.errorFields?.[0]?.name
      if (!field || this.disposed) return
      this.$nextTick(() => {
        this.formRef.value.scrollToField(field)
        const input = this.$refs[field[0] + 'Input']
        if (input) input.focus()
      })
    },
    handleSubmit (e) {
      if (e && typeof e.preventDefault === 'function') e.preventDefault()
      if (this.busy || this.disposed) return
      if (!this.allowedApi) { this.error = this.$t('label.forbidden'); return }
      if (this.cloneMode) return this.submitClone()
      if (!this.isUserData && this.$store.getters.listAllProjects) { this.error = this.$t('message.cni.scope.changed'); return }
      const scope = this.cniScope
      const position = this.formPosition()
      let invalidField
      this.loading = true
      this.error = ''
      return this.formRef.value.validate().then(() => {
        if (this.disposed || !this.allowedApi) return
        if (!this.isUserData && scope !== this.cniScope) throw new Error('message.cni.scope.changed')
        const values = toRaw(this.form)
        const params = {
          name: values.name
        }
        if (this.isValidValueForKey(values, 'domainid') &&
          this.arrayHasItems(this.domains) &&
          this.domains[values.domainid].id !== null) {
          params.domainid = this.domains[values.domainid].id
        }
        if (this.isValidValueForKey(values, 'account') && values.account.length > 0) {
          params.account = values.account
        }

        const apiName = this.apiName

        if (apiName === 'registerUserData') {
          params.name = userDataName(values.name)
          params.userdata = canonicalUserData(values.userdata, values.isbase64, this.maxEncodedLength)
        } else {
          params.name = cniName(values.name)
          params.cniconfig = canonicalCni(values.cniconfig, values.isbase64, this.maxEncodedLength)
          params.ignoreproject = true
          if (this.cniProject) {
            params.projectid = this.cniProject.id
            delete params.account
            delete params.domainid
          }
        }
        if (values.params != null && values.params.length > 0) {
          const userdataparams = apiName === 'registerUserData' ? userDataParams(values.params) : cniParams(values.params)
          params.params = userdataparams
        }

        return postAPI(apiName, params, { preserveOnFailure: true }).then(json => {
          if (this.disposed) return
          if (!this.isUserData && scope !== this.cniScope) throw new Error(this.$t('message.cni.scope.result.changed'))
          if (!this.isUserData && (!json?.registercniconfigurationresponse?.cniconfig?.id || json.registercniconfigurationresponse.cniconfig.name !== params.name)) {
            throw new Error(this.$t('message.cni.response.invalid'))
          }
          this.$message.success(this.$t(this.isUserData ? 'message.success.register.user.data' : 'message.success.register.cni.config') + ' ' + params.name)
          this.$emit('refresh-data')
          this.$emit('close-action')
        }).catch(error => {
          if (!this.disposed) this.error = this.errorMessage(error)
        })
      }).catch(error => {
        if (error?.errorFields?.length) invalidField = error.errorFields[0].name
        else if (!this.disposed) this.error = this.$t(error.message || 'message.network.error')
      }).finally(() => {
        this.loading = false
        if (this.disposed) return
        if (invalidField) {
          this.focusValidationError({ errorFields: [{ name: invalidField }] })
        } else this.restoreFormPosition(position)
      })
    },
    async loadCloneSource () {
      if (!this.cloneMode || this.sourceLoading || this.disposed) return
      if (!this.allowedApi) { this.error = this.$t('label.forbidden'); return }
      const version = ++this.sourceVersion; const scope = this.cloneScope
      const current = () => !this.disposed && this.sourceVersion === version && this.cloneScope === scope
      this.sourceLoading = true; this.error = ''
      try {
        const source = await readCloneSource(getAPI, Object.freeze({ ...this.resource }))
        if (!current()) return
        this.source = source
        this.form.name = source.name.slice(0, 247).replace(/[\uD800-\uDBFF]$/, '') + '-v2'
        this.form.userdata = source.userdata; this.form.isbase64 = true
        this.form.params = source.params ? source.params.split(',') : []
        this.$nextTick(() => { if (this.$refs.nameInput) this.$refs.nameInput.focus() })
      } catch (error) { if (current()) this.error = this.cloneError(error) } finally { if (current()) this.sourceLoading = false }
    },
    cloneError (error) { return error?.message?.startsWith('message.userdata.') ? this.$t(error.message) : this.errorMessage(error) },
    openCloneRoute (event, path) {
      if (this.busy) { event.preventDefault(); return }
      if (event.button > 0 || event.ctrlKey || event.metaKey || event.shiftKey || event.altKey) return
      event.preventDefault()
      // Resource detail routes reuse AutogenView. Close the current action
      // before changing its resource so a receipt cannot become another clone.
      this.$emit('close-action')
      this.$router.push(path)
    },
    editCloneText () {
      if (this.busy || this.cloneAttempt || !this.draft?.text) return
      this.form.userdata = this.draft.text.replace(/\r\n?/g, '\n'); this.form.isbase64 = false
      this.$nextTick(() => this.$refs.userdataInput?.focus())
    },
    async submitClone () {
      const position = this.formPosition(); const scope = this.cloneScope
      const current = () => !this.disposed && this.cloneScope === scope && this.allowedApi
      this.loading = true; this.error = ''
      try {
        if (!this.createdVersion) {
          if (!this.source) return
          await this.formRef.value.validate()
          if (!current()) return
          // Re-read the source within the frozen owner scope before every mutation.
          const source = await readCloneSource(getAPI, this.source)
          if (!current()) return
          if (source.userdata !== this.source.userdata || (source.params || '') !== (this.source.params || '')) throw new Error('message.userdata.clone.source.changed')
          let target
          if (this.cloneAttempt) {
            target = await findCloneAttempt(getAPI, source, this.cloneAttempt)
            if (!current()) return
            if (!target) { this.cloneAttempt = null; throw new Error('message.userdata.clone.notfound') }
          } else {
            const params = { ...cloneOwner(source), name: userDataName(this.form.name), userdata: canonicalUserData(this.form.userdata, this.form.isbase64, this.maxEncodedLength), params: userDataParams(this.form.params) }
            if (params.userdata === source.userdata) throw new Error('message.userdata.clone.duplicate')
            if (await findCloneAttempt(getAPI, source, params)) throw new Error('message.userdata.clone.name.conflict')
            if (!current()) return
            this.cloneAttempt = Object.freeze({ ...params })
            try {
              const json = await postAPI('registerUserData', params, { preserveOnFailure: true, timeout: 60000 })
              if (!current()) return
              const row = json?.registeruserdataresponse?.userdata
              // Never trust a malformed success or a result with a different owner/body.
              if (!row?.id) throw new Error('message.userdata.clone.uncertain')
              target = await findCloneAttempt(getAPI, source, params)
              if (!target || target.id !== row.id) throw new Error('message.userdata.clone.uncertain')
            } catch (error) {
              if (!current()) return
              // Preserve the submitted snapshot. The next click reconciles; it never blindly POSTs again.
              throw new Error(this.cloneError(error) + ' ' + this.$t('message.userdata.clone.uncertain'))
            }
          }
          if (!current()) return
          this.createdVersion = target; this.note = cloneRecord(source, target)
          this.$emit('refresh-data')
        }
        if (!current() || this.noteSaved) return
        await saveCloneNote(getAPI, postAPI, this.createdVersion, this.note, current)
        if (!current()) return
        this.noteSaved = true
      } catch (error) {
        if (!current()) return
        // A confirmed different item owns the name. Retain the draft and let
        // the user choose a new name; never POST automatically after a conflict.
        if (error.message === 'message.userdata.clone.name.conflict') this.cloneAttempt = null
        if (error?.errorFields?.length) this.focusValidationError(error)
        else this.error = this.cloneError(error)
      } finally { this.loading = false; if (current()) this.restoreFormPosition(position) }
    },
    beforeUnload (event) { if (this.busy) { event.preventDefault(); event.returnValue = '' } },
    closeAction () {
      if (this.busy) return
      this.$emit('close-action')
    }
  }
}
</script>

<style scoped lang="less">
  .userdata-registration-content { min-width: 0; overflow-wrap: anywhere; overflow-anchor: none; }
  .userdata-registration-content :deep(.ant-form .ant-form-item) { margin-bottom: 20px; }
  .userdata-registration-content > .ant-alert + p, .userdata-registration-content > .ant-spin-nested-loading { margin-top: 16px; }
  .userdata-registration-content--cni > :deep(.ant-spin-nested-loading) { margin-top: 0; }
  .userdata-registration-content details { margin: 12px 0; }
  .userdata-registration-content summary { cursor: pointer; }
  .userdata-registration-content :deep(.content-code) { max-height: 240px; }
  .clone-usage-links { display: flex; flex-wrap: wrap; gap: 12px; }
</style>
