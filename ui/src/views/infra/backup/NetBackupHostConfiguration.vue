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
  <div class="host-backup-integration">
    <a-alert type="info" show-icon :message="$t('label.backup.integration.netbackup')" :description="$t('message.backup.integration.flow')" />
    <a-steps size="small" :current="step" class="integration-steps">
      <a-step :title="$t('label.backup.integration.shared')" />
      <a-step :title="$t('label.backup.integration.host')" />
      <a-step :title="$t('label.backup.integration.assign')" />
      <a-step v-if="info.vmschedulebackup !== false" :title="$t('label.backup.integration.schedule')" />
    </a-steps>
    <a-alert v-if="error" type="error" show-icon :message="error" class="integration-block" />
    <a-spin :spinning="loading">
      <a-descriptions bordered size="small" :column="2" class="integration-block">
        <a-descriptions-item :label="$t('label.status')">{{ stateLabel }}<span v-if="info.step && !submitting"> · {{ stepLabel(info.step) }}</span></a-descriptions-item>
        <a-descriptions-item :label="$t('label.backup.integration.mode')">{{ $t(info.vmschedulebackup === false ? 'label.backup.integration.mode.policy' : 'label.backup.integration.mode.schedule') }}</a-descriptions-item>
        <a-descriptions-item :label="$t('label.backup.integration.server')">{{ info.netbackupurl || '—' }}</a-descriptions-item>
        <a-descriptions-item :label="$t('label.backup.integration.credentials')">{{ $t(info.credentialsconfigured ? 'label.backup.integration.registered' : 'label.backup.integration.required') }}</a-descriptions-item>
        <a-descriptions-item :label="$t('label.backup.integration.staging')">{{ info.stagingpath || '—' }}</a-descriptions-item>
        <a-descriptions-item :label="$t('label.backup.integration.components')">{{ $t(busy ? 'label.backup.integration.in.progress' : (info.hooksready && info.timeractive ? 'label.backup.integration.ready' : 'label.backup.integration.required')) }}</a-descriptions-item>
      </a-descriptions>
      <a-card :title="$t('label.backup.integration.settings')" size="small" class="integration-block">
        <p>{{ $t('message.backup.integration.inheritance') }}</p>
        <a-space wrap class="integration-block">
          <router-link :to="{ name: 'globalsetting' }">{{ $t('label.backup.integration.shared.settings') }}</router-link>
          <router-link :to="{ path: '/zone/' + resource.zoneid, query: { tab: 'settings' } }">{{ $t('label.backup.integration.zone.settings') }}</router-link>
          <router-link :to="{ path: '/cluster/' + resource.clusterid, query: { tab: 'settings' } }">{{ $t('label.backup.integration.cluster.settings') }}</router-link>
        </a-space>
        <a-table size="small" :columns="settingColumns" :data-source="settings" row-key="key" :pagination="false" :scroll="{ x: 800 }">
          <template #bodyCell="{ column, record }">
            <template v-if="column.key === 'setting'">
              <div>{{ record.title }}</div>
              <code>{{ record.key }}</code>
              <div class="setting-help">{{ record.help }}</div>
            </template>
            <template v-else-if="column.key === 'source'">
              <span v-if="record.source">{{ $t('label.backup.integration.source.' + record.source.toLowerCase()) }}</span>
              <span v-else>—</span>
            </template>
          </template>
        </a-table>
        <p class="integration-block">{{ $t('message.backup.integration.restart') }}</p>
      </a-card>
      <a-alert v-if="info.state && !prerequisitesReady" type="warning" show-icon :message="$t('message.backup.integration.prerequisites')" class="integration-block" />
      <a-alert v-if="info.state" :type="info.moldcredentialsavailable ? 'info' : 'warning'" show-icon :message="$t('message.backup.integration.mold.credentials')" class="integration-block" />
      <a-form layout="vertical" class="integration-form">
        <a-form-item :label="$t('label.backup.integration.policy')" :extra="$t('message.backup.integration.policy')">
          <a-input v-model:value="policyName" :disabled="busy" :maxlength="128" autocomplete="off" />
        </a-form-item>
        <a-form-item :label="$t('label.backup.integration.mold.url')" :extra="$t('message.backup.integration.mold.url')">
          <a-input v-model:value="moldUrl" :disabled="busy" autocomplete="off" />
        </a-form-item>
        <a-space wrap>
          <a-button :disabled="!canRun" @click="run('CHECK')">{{ $t('label.backup.integration.check') }}</a-button>
          <a-popconfirm :title="$t('message.backup.integration.apply')" :disabled="!canApply" @confirm="run('APPLY')">
            <a-button type="primary" :disabled="!canApply">{{ $t('label.backup.integration.apply') }}</a-button>
          </a-popconfirm>
          <a-button :disabled="submitting" :loading="loading" @click="load">{{ $t('label.refresh') }}</a-button>
        </a-space>
      </a-form>
      <a-alert v-if="busy && !inspectionFailed" type="info" show-icon :message="$t('message.backup.integration.running')" class="integration-block" />
      <a-alert v-else-if="info.message" :type="info.state === 'FAILED' ? 'error' : 'info'" show-icon :message="statusMessage" class="integration-block" />
      <a-space v-if="!submitting && info.check?.length" wrap class="integration-block">
        <a-tag v-for="check in info.check" :key="check.name" :color="check.state === 'OK' ? 'green' : 'orange'">{{ stepLabel(check.name) }} · {{ checkStateLabel(check.state) }}</a-tag>
      </a-space>
      <a-card v-if="info.bootstrappath" :title="$t('label.backup.integration.path.check')" size="small" class="integration-block">
        <p>{{ $t(pathState === 'mismatch' ? 'message.backup.integration.path.check' : 'message.backup.integration.path.' + pathState) }}</p>
        <a-descriptions :column="1" size="small">
          <a-descriptions-item :label="$t('label.backup.integration.path.expected')"><a-typography-paragraph copyable>{{ info.bootstrappath }}</a-typography-paragraph></a-descriptions-item>
          <a-descriptions-item :label="$t('label.backup.integration.path.actual')">{{ pathVerified ? ((info.policyselections || []).join(', ') || $t('label.backup.integration.path.empty')) : $t('label.backup.integration.path.' + pathState) }}</a-descriptions-item>
          <a-descriptions-item :label="$t('label.backup.integration.path.result')"><a-tag :color="pathColor">{{ $t('label.backup.integration.path.' + pathState) }}</a-tag></a-descriptions-item>
          <a-descriptions-item :label="$t('label.backup.integration.checked')">{{ pathVerified ? (info.checkedat ? $toLocaleDate(new Date(info.checkedat).toISOString()) : '—') : $t('label.backup.integration.path.' + pathState) }}</a-descriptions-item>
        </a-descriptions>
      </a-card>
      <a-alert v-if="integrationReady" type="success" show-icon :message="$t('label.backup.integration.ready')" :description="nextStep" class="integration-block" />
      <a-card :title="$t('label.backup.integration.targets')" size="small" class="integration-block">
        <p>{{ $t('message.backup.integration.targets') }}</p>
        <a-alert v-if="info.vmschedulebackup === false" type="info" show-icon :message="$t('label.backup.integration.policy.limits')" class="integration-block">
          <template #description>
            <ul class="integration-policy-limits">
              <li v-for="item in policyLimitItems" :key="item">{{ item }}</li>
            </ul>
          </template>
        </a-alert>
        <a-space wrap class="integration-block">
          <router-link :to="{ name: 'backupoffering' }">{{ $t('label.backup.offerings') }}</router-link>
          <router-link :to="{ name: 'vm' }">{{ $t('label.instances') }}</router-link>
        </a-space>
        <a-table size="small" :columns="columns" :data-source="info.virtualmachine || []" row-key="id" :pagination="{ pageSize: 10 }">
          <template #bodyCell="{ column, record }">
            <template v-if="column.key === 'name'"><router-link :to="{ path: '/vm/' + record.id }">{{ record.name || record.instancename }}</router-link></template>
            <template v-else-if="column.key === 'hasschedule'">{{ $t(record.hasschedule ? 'label.backup.integration.registered' : 'label.backup.integration.required') }}</template>
          </template>
        </a-table>
      </a-card>
    </a-spin>
  </div>
</template>

<script>
import { getAPI, postAPI } from '@/api'
import { netBackupHostMessage, netBackupHostStep, netBackupHostCheckState } from '@/utils/netbackupHostConfiguration'

export default {
  name: 'NetBackupHostConfiguration',
  props: { resource: { type: Object, required: true } },
  data () {
    return { info: {}, policyName: '', moldUrl: '', loading: false, submitting: false, inspectionFailed: false, error: '', checkedInput: '', timer: null, generation: 0, requestToken: 0 }
  },
  computed: {
    busy () { return this.submitting || this.info.state === 'RUNNING' },
    prerequisitesReady () { return this.info.frameworkenabled && this.info.providerenabled && this.info.stagingenabled && this.info.credentialsconfigured && !!this.info.netbackupurl },
    fingerprint () { return JSON.stringify([this.resource.id, this.policyName.trim(), this.moldUrl.trim()]) },
    canRun () {
      return !this.loading && !this.busy && !this.inspectionFailed && this.prerequisitesReady && this.info.moldcredentialsavailable && this.info.hoststate === 'Up' &&
        !!this.$store.getters.apis.configureNetBackupHost && /^[A-Za-z0-9_+][A-Za-z0-9._+-]{0,127}$/.test(this.policyName.trim()) &&
        !this.policyName.trim().endsWith('.') && /^https?:\/\/[^\s]+$/i.test(this.moldUrl.trim())
    },
    canApply () { return this.canRun && this.info.state === 'CHECKED' && this.checkedInput === this.fingerprint },
    nextStep () { return this.$t(this.info.vmschedulebackup === false ? 'message.backup.integration.next.policy' : 'message.backup.integration.next.schedule') },
    policyLimitItems () { return this.$t('message.backup.integration.policy.limits').split('\n') },
    stateLabel () { return this.$t('label.backup.integration.state.' + (this.busy ? 'RUNNING' : (this.info.state || 'UNKNOWN')).toLowerCase()) },
    statusMessage () { return this.localizeMessage(this.info.message) },
    integrationReady () { return !this.busy && !this.inspectionFailed && this.info.linkready },
    pathVerified () {
      return !this.busy && !this.inspectionFailed && (this.info.state === 'POLICY_PATH_MISMATCH' ||
        (this.info.check || []).some(check => check.name === 'POLICY_PATH' && ['OK', 'MISMATCH'].includes(check.state)))
    },
    pathState () {
      if (this.inspectionFailed) return 'unconfirmed'
      if (this.busy) return 'checking'
      if (!this.pathVerified) return 'unconfirmed'
      return this.info.policypathmatched ? 'matched' : 'mismatch'
    },
    pathColor () { return { matched: 'green', mismatch: 'orange', checking: 'blue' }[this.pathState] },
    step () {
      if (!this.prerequisitesReady) return 0
      if (this.busy || !this.integrationReady) return 1
      if (!(this.info.virtualmachine || []).length) return 2
      return this.info.vmschedulebackup === false ? 3 : (this.info.virtualmachine.every(vm => vm.hasschedule) ? 4 : 3)
    },
    columns () {
      const columns = [{ key: 'name', title: this.$t('label.name') }, { key: 'state', dataIndex: 'state', title: this.$t('label.state') }]
      if (this.info.vmschedulebackup !== false) columns.push({ key: 'hasschedule', title: this.$t('label.backup.integration.schedule') })
      return columns
    },
    settingColumns () {
      return [
        { key: 'setting', title: this.$t('label.backup.integration.setting'), width: '46%' },
        { key: 'scope', dataIndex: 'scope', title: this.$t('label.backup.integration.scope') },
        { key: 'source', title: this.$t('label.backup.integration.source') },
        { key: 'value', dataIndex: 'value', title: this.$t('label.backup.integration.value') }
      ]
    },
    settings () {
      const boolean = value => value === undefined ? '—' : String(value)
      const source = key => this.info.settingsource?.[key]
      const framework = source('backup.framework.enabled') === 'Zone' ? this.info.frameworkzoneenabled : this.info.frameworkglobalenabled
      return [
        { key: 'backup.framework.enabled', label: 'framework', scope: 'zone', source: source('backup.framework.enabled'), value: boolean(framework) },
        { key: 'backup.framework.provider.plugin', label: 'provider', scope: 'zone', source: source('backup.framework.provider.plugin'), value: this.info.providerenabled === undefined ? '—' : this.$t(this.info.providerenabled ? 'label.backup.integration.registered' : 'label.backup.integration.required') },
        { key: 'backup.plugin.netbackup.url', label: 'server', scope: 'zone', source: source('backup.plugin.netbackup.url'), value: this.info.netbackupurl || '—' },
        { key: 'backup.plugin.netbackup.apikey', label: 'credentials', scope: 'zone', source: source('backup.plugin.netbackup.apikey'), value: this.info.credentialsconfigured === undefined ? '—' : this.$t(this.info.credentialsconfigured ? 'label.backup.integration.registered' : 'label.backup.integration.required') },
        { key: 'backup.thirdparty.staging.*', label: 'staging', scope: 'global', source: 'Global', value: boolean(this.info.stagingenabled) },
        { key: 'backup.plugin.netbackup.vm.schedule.enabled', label: 'mode', scope: 'global', source: 'Global', value: boolean(this.info.vmschedulebackup) },
        { key: 'kvm.incremental.backup', label: 'incremental', scope: 'cluster', source: source('kvm.incremental.backup'), value: boolean(this.info.incrementalbackup) },
        { key: 'kvm.backup.chain.size', label: 'chain', scope: 'global', source: 'Global', value: this.info.backupchainsize ?? '—' }
      ].map(setting => ({
        ...setting,
        title: this.$t('label.backup.integration.' + setting.label),
        help: this.$t('message.backup.integration.setting.' + setting.label),
        scope: this.$t('label.backup.integration.scope.' + setting.scope)
      }))
    }
  },
  watch: { 'resource.id': { immediate: true, handler () { this.reset(); this.load() } } },
  beforeUnmount () { this.generation++; clearTimeout(this.timer) },
  methods: {
    localizeMessage (message) { return netBackupHostMessage(message, (key, params) => this.$t(key, params), this.info) },
    stepLabel (value) { return netBackupHostStep(value, key => this.$t(key)) },
    checkStateLabel (value) { return netBackupHostCheckState(value, key => this.$t(key)) },
    reset () {
      this.generation++; clearTimeout(this.timer)
      this.info = {}; this.error = ''; this.inspectionFailed = false; this.policyName = ''; this.checkedInput = ''; this.submitting = false
      this.moldUrl = typeof window === 'undefined' ? '' : window.location.origin + '/client/api'
    },
    failure (error) {
      const message = error?.jobresult?.errortext || error?.response?.data?.configurenetbackuphostresponse?.errortext ||
        error?.response?.data?.getnetbackuphostconfigurationresponse?.errortext || error?.response?.data?.errorresponse?.errortext || error?.message || this.$t('message.backup.integration.unconfirmed')
      return this.localizeMessage(message)
    },
    async load () {
      const generation = this.generation
      const token = ++this.requestToken
      clearTimeout(this.timer); this.loading = true
      try {
        const response = await getAPI('getNetBackupHostConfiguration', { hostid: this.resource.id })
        if (generation !== this.generation || token !== this.requestToken) return
        const info = response.getnetbackuphostconfigurationresponse?.netbackuphostconfiguration
        if (!info?.state) throw new Error(this.$t('message.backup.integration.unconfirmed'))
        if (this.inspectionFailed) this.error = ''
        this.inspectionFailed = false
        this.info = info
        if (!this.policyName) {
          this.policyName = info.policyname || ''
          if (info.moldurl) this.moldUrl = info.moldurl
        }
        if (info.action === 'CHECK' && info.state === 'CHECKED' && info.policyname === this.policyName.trim() && info.moldurl === this.moldUrl.trim()) this.checkedInput = this.fingerprint
        if (info.state === 'RUNNING') this.timer = setTimeout(() => this.load(), 5000)
      } catch (error) { if (generation === this.generation && token === this.requestToken) { this.inspectionFailed = true; this.error = this.failure(error) } } finally { if (generation === this.generation && token === this.requestToken) this.loading = false }
    },
    async run (action) {
      if (!['CHECK', 'APPLY'].includes(action) || !this.canRun || (action === 'APPLY' && !this.canApply)) return
      const generation = this.generation
      this.submitting = true; this.error = ''; this.checkedInput = ''
      const refreshStatus = () => {
        if (generation !== this.generation) return
        return this.load().finally(() => { if (generation === this.generation) this.submitting = false })
      }
      const failed = error => {
        if (generation !== this.generation) return
        this.error = this.failure(error)
        return refreshStatus()
      }
      try {
        const response = await postAPI('configureNetBackupHost', { hostid: this.resource.id, action, policyname: this.policyName.trim(), moldurl: this.moldUrl.trim() })
        const jobId = response.configurenetbackuphostresponse?.jobid
        if (!jobId) throw new Error(this.$t('message.backup.integration.unconfirmed'))
        await this.$pollJob({
          jobId,
          originalPage: this.$route.path,
          title: this.$t('label.backup.integration.netbackup'),
          resourceId: this.resource.id,
          showLoading: false,
          showSuccessMessage: false,
          successMethod: refreshStatus,
          errorMethod: failed,
          catchMethod: failed
        })
      } catch (error) { await failed(error) }
    }
  }
}
</script>

<style scoped>
.host-backup-integration { overflow-wrap: anywhere; }
.integration-steps { margin: 24px 0; }
.integration-block { margin: 16px 0; }
.integration-policy-limits { margin: 0; padding-inline-start: 20px; }
.integration-policy-limits li + li { margin-top: 6px; }
.integration-form { max-width: 720px; margin: 24px 0; }
.setting-help { opacity: 0.75; margin-top: 4px; }
</style>
