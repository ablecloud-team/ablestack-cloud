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
  <div class="form-layout" v-ctrl-enter="handleSubmitForm">
    <a-spin :spinning="loading">
      <a-form
        :ref="formRef"
        :model="form"
        :loading="loading"
        :rules="rules"
        layout="vertical"
        @submit="handleSubmitForm">
        <a-alert v-if="createdHost" class="host-save-alert" type="success" show-icon :message="$t('message.host.add.created', [createdHost.name || createdHost.id])" />
        <a-alert v-if="submitStage" class="host-save-alert" type="info" show-icon :message="$t(submitStage)" />
        <a-alert v-if="submitError" class="host-save-alert" :type="createUnknown || dedicateUnknown ? 'warning' : 'error'" show-icon :message="$t(submitError)" />
        <fieldset :disabled="submitting || !!createdHost || createUnknown">
        <a-form-item name="zoneid" ref="zoneid">
          <template #label>
            <tooltip-label :title="$t('label.zonenamelabel')" :tooltip="placeholder.zoneid"/>
          </template>
          <a-select
            v-focus="true"
            v-model:value="form.zoneid"
            :placeholder="placeholder.zoneid"
            showSearch
            optionFilterProp="label"
            :filterOption="(input, option) => {
              return option.label.toLowerCase().indexOf(input.toLowerCase()) >= 0
            }"
            @change="fetchPods">
            <a-select-option
              v-for="zone in zonesList"
              :value="zone.id"
              :key="zone.id"
              :label="zone.name">
              <span>
                <resource-icon v-if="zone.icon" :image="zone.icon.base64image" size="1x" style="margin-right: 5px"/>
                <global-outlined v-else style="margin-right: 5px" />
                {{ zone.name }}
              </span>
            </a-select-option>
          </a-select>
        </a-form-item>
        <a-form-item name="podid" ref="podid">
          <template #label>
            <tooltip-label :title="$t('label.podname')" :tooltip="placeholder.podid"/>
          </template>
          <a-select
            v-model:value="form.podid"
            :placeholder="placeholder.podid"
            showSearch
            optionFilterProp="label"
            :filterOption="(input, option) => {
              return option.label.toLowerCase().indexOf(input.toLowerCase()) >= 0
            }"
            @change="fetchClusters">
            <a-select-option
              v-for="pod in podsList"
              :value="pod.id"
              :key="pod.id"
              :label="pod.name">
              {{ pod.name }}
            </a-select-option>
          </a-select>
        </a-form-item>
        <a-form-item name="clusterid" ref="clusterid">
          <template #label>
            <tooltip-label :title="$t('label.clustername')" :tooltip="placeholder.clusterid"/>
          </template>
          <a-select
            v-model:value="form.clusterid"
            :placeholder="placeholder.clusterid"
            showSearch
            optionFilterProp="label"
            :filterOption="(input, option) => {
              return option.label.toLowerCase().indexOf(input.toLowerCase()) >= 0
            }"
            @change="handleChangeCluster">
            <a-select-option
              v-for="cluster in clustersList"
              :value="cluster.id"
              :key="cluster.id"
              :label="cluster.name">
              {{ cluster.name }}
            </a-select-option>
          </a-select>
        </a-form-item>
        <a-form-item name="hostname" ref="hostname">
          <template #label>
            <tooltip-label
              :title="selectedClusterHyperVisorType === 'VMware' ? $t('label.esx.host') : $t('label.hostnamelabel')"
              :tooltip="placeholder.url"/>
          </template>
          <a-input
            v-model:value="form.hostname"
            :placeholder="placeholder.url"></a-input>
        </a-form-item>
        <a-form-item name="username" ref="username" v-if="!['VMware', 'External'].includes(selectedClusterHyperVisorType)">
          <template #label>
            <tooltip-label :title="$t('label.username')" :tooltip="placeholder.username"/>
          </template>
          <a-input
            v-model:value="form.username"
            :placeholder="placeholder.username"></a-input>
        </a-form-item>
        <a-form-item name="authmethod" ref="authmethod" v-if="!['VMware', 'External'].includes(selectedClusterHyperVisorType)">
          <template #label>
            <tooltip-label :title="$t('label.authentication.method')" :tooltip="$t('label.authentication.method')"/>
          </template>
          <a-radio-group
            v-model:value="form.authmethod"
            buttonStyle="solid"
            @change="selected => { handleAuthMethodChange(selected.target.value) }">
            <a-radio-button value="password">
              {{ $t('label.password') }}
            </a-radio-button>
            <a-radio-button value="sshkey" v-if="selectedClusterHyperVisorType === 'KVM'">
              {{ $t('label.authentication.sshkey') }}
            </a-radio-button>
          </a-radio-group>
          <div v-if="authMethod === 'sshkey'">
            <a-alert type="warning">
              <template #message>
                <span style="display:block;width:300px;word-wrap:break-word;" v-html="$t('message.add.host.sshkey')" />
              </template>
            </a-alert>
          </div>
        </a-form-item>
        <a-form-item name="password" ref="password" v-if="!['VMware', 'External'].includes(selectedClusterHyperVisorType) && authMethod === 'password'">
          <template #label>
            <tooltip-label :title="$t('label.password')" :tooltip="placeholder.password"/>
          </template>
          <a-input-password
            v-model:value="form.password"
            :placeholder="placeholder.password" />
        </a-form-item>
        <a-form-item name="agentusername" ref="agentusername" v-if="selectedClusterHyperVisorType === 'Ovm3'">
          <template #label>
            <tooltip-label :title="$t('label.agent.username')" :tooltip="$t('label.agent.username')"/>
          </template>
          <a-input
            v-model:value="form.agentusername"
            :placeholder="$t('label.agent.username')" />
        </a-form-item>
        <a-form-item name="agentpassword" ref="agentpassword" v-if="selectedClusterHyperVisorType === 'Ovm3'">
          <template #label>
            <tooltip-label :title="$t('label.agent.password')" :tooltip="$t('label.agent.password')"/>
          </template>
          <a-input
            v-model:value="form.agentpassword"
            :placeholder="$t('label.agent.password')" />
        </a-form-item>
        <a-form-item name="agentport" ref="agentport" v-if="selectedClusterHyperVisorType === 'Ovm3'">
          <template #label>
            <tooltip-label :title="$t('label.agentport')" :tooltip="$t('label.agentport')"/>
          </template>
          <a-input
            v-model:value="form.agentport"
            :placeholder="$t('label.agentport')" />
        </a-form-item>
        <a-form-item name="baremetalcpucores" ref="baremetalcpucores" v-if="selectedClusterHyperVisorType === 'BareMetal'">
          <template #label>
            <tooltip-label :title="$t('label.baremetalcpucores')" :tooltip="$t('label.baremetalcpucores')"/>
          </template>
          <a-input
            v-model:value="form.baremetalcpucores"
            :placeholder="$t('label.baremetalcpucores')" />
        </a-form-item>
        <a-form-item name="baremetalcpu" ref="baremetalcpu" v-if="selectedClusterHyperVisorType === 'BareMetal'">
          <template #label>
            <tooltip-label :title="$t('label.baremetalcpu')" :tooltip="$t('label.baremetalcpu')"/>
          </template>
          <a-input
            v-model:value="form.baremetalcpu"
            :placeholder="$t('label.baremetalcpu')" />
        </a-form-item>
        <a-form-item name="baremetalmemory" ref="baremetalmemory" v-if="selectedClusterHyperVisorType === 'BareMetal'">
          <template #label>
            <tooltip-label :title="$t('label.baremetalmemory')" :tooltip="$t('label.baremetalmemory')"/>
          </template>
          <a-input
            v-model:value="form.baremetalmemory"
            :placeholder="$t('label.baremetalmemory')" />
        </a-form-item>
        <a-form-item name="baremetalmac" ref="baremetalmac" v-if="selectedClusterHyperVisorType === 'BareMetal'">
          <template #label>
            <tooltip-label :title="$t('label.baremetalmac')" :tooltip="$t('label.baremetalmac')"/>
          </template>
          <a-input
            v-model:value="form.baremetalmac"
            :placeholder="$t('label.baremetalmac')" />
        </a-form-item>
        <a-form-item name="hosttags" ref="hosttags">
          <template #label>
            <tooltip-label :title="$t('label.hosttags')" :tooltip="placeholder.hosttags"/>
          </template>
          <a-select
            mode="tags"
            showSearch
            optionFilterProp="value"
            :filterOption="(input, option) => {
              return option.value.toLowerCase().indexOf(input.toLowerCase()) >= 0
            }"
            v-model:value="form.hosttags"
            :placeholder="placeholder.hosttags">
            <a-select-option v-for="tag in hostTagsList" :key="tag.name">{{ tag.name }}</a-select-option>
          </a-select>
        </a-form-item>
        <a-form-item v-if="apiParams.allocationstate" name="allocationstate" :label="$t('label.allocationstate')" html-for="host-add-allocation-state">
          <a-select
            id="host-add-allocation-state"
            v-model:value="form.allocationstate"
            allow-clear
            :placeholder="$t('label.default')"
            :options="[{ value: 'Enabled', label: $t('label.enabled') }, { value: 'Disabled', label: $t('label.disabled') }]" />
        </a-form-item>
        <a-form-item v-if="apiParams.storageaccessgroups" name="storageaccessgroups" :label="$t('label.storageaccessgroups')" html-for="host-add-storage-groups">
          <a-select
            id="host-add-storage-groups"
            v-model:value="form.storageaccessgroups"
            mode="tags"
            :options="storageAccessGroups"
            :loading="storageAccessGroupsLoading" />
        </a-form-item>
        <a-form-item name="externaldetails" ref="externaldetails" v-if="selectedClusterHyperVisorType === 'External'">
          <template #label>
            <tooltip-label :title="$t('label.configuration.details')" :tooltip="apiParams.externaldetails.description"/>
          </template>
          <a-switch v-model:checked="externalDetailsEnabled" @change="onExternalDetailsEnabledChange"/>
          <a-card v-if="externalDetailsEnabled" style="margin-top: 10px">
            <div style="margin-bottom: 10px">{{ $t('message.add.extension.resource.details') }}</div>
            <details-input
              v-model:value="form.externaldetails" />
          </a-card>
        </a-form-item>
        <a-form-item v-if="canDedicate" name="isdedicated" ref="isdedicated">
          <template #label>
            <tooltip-label :title="$t('label.isdedicated')"/>
          </template>
          <a-checkbox @change="toggleDedicated"></a-checkbox>
        </a-form-item>
        <template v-if="showDedicated">
          <DedicateDomain
            @domainChange="id => { dedicatedDomainId = id; dedicatedAccount = null; domainError = false }"
            @accountChange="id => dedicatedAccount = id"
            :error="domainError" />
        </template>

        </fieldset>

        <mold-dialog-actions :busy="submitting">
          <a-button :disabled="submitting" @click="closeAction">{{ $t('label.cancel') }}</a-button>
          <a-button :loading="submitting" :disabled="createUnknown || loading" @click="handleSubmitForm" ref="submit" type="primary">{{ $t(dedicateUnknown ? 'label.host.operation.recheck' : submitError ? 'label.retry' : 'label.ok') }}</a-button>
        </mold-dialog-actions>
      </a-form>
    </a-spin>
  </div>
</template>

<script>
import MoldDialogActions from '@/components/view/MoldDialogActions'
import { ref, reactive, toRaw } from 'vue'
import { getAPI, postAPI } from '@/api'
import { mixinForm } from '@/utils/mixin'
import DedicateDomain from '../../components/view/DedicateDomain'
import ResourceIcon from '@/components/view/ResourceIcon'
import TooltipLabel from '@/components/widgets/TooltipLabel'
import DetailsInput from '@/components/widgets/DetailsInput'
import { createHostJobTracker, hostRequestRejected, hostStorageGroups } from '@/utils/hostOperations'

export default {
  name: 'HostAdd',
  mixins: [mixinForm],
  components: {
    MoldDialogActions,
    DedicateDomain,
    ResourceIcon,
    TooltipLabel,
    DetailsInput
  },
  props: {
    resource: {
      type: Object,
      required: true
    }
  },
  inject: ['parentFetchData', 'parentToggleLoading'],
  data () {
    return {
      loading: false,
      submitting: false,
      createdHost: null,
      submitStage: '',
      submitError: '',
      createUnknown: false,
      dedicateUnknown: false,
      dedicateJobId: '',
      storageAccessGroups: [],
      storageAccessGroupsLoading: false,
      zonesList: [],
      clustersList: [],
      podsList: [],
      hostTagsList: [],
      authMethod: 'password',
      selectedCluster: null,
      selectedClusterHyperVisorType: null,
      showDedicated: false,
      dedicatedDomainId: null,
      dedicatedAccount: null,
      domainError: false,
      params: [],
      placeholder: {
        zoneid: null,
        podid: null,
        clusterid: null,
        url: null,
        username: null,
        password: null,
        hosttags: null,
        isdedicated: null
      },
      externalDetailsEnabled: false
    }
  },
  computed: {
    canDedicate () {
      const apis = this.$store.getters.apis || {}
      return Boolean(apis.dedicateHost && apis.queryAsyncJobResult)
    },
    hostTagRules () {
      let rules = []
      if (this.selectedClusterHyperVisorType === 'BareMetal') {
        rules = [{ required: true, message: this.$t('message.error.select') }]
      }

      return rules
    }
  },
  beforeCreate () {
    this.apiParams = this.$getApiParams('addHost')
  },
  created () {
    this.initForm()
    this.fetchData()
    if (this.apiParams.storageaccessgroups) this.fetchStorageAccessGroups()
    this.dedicationTracker = createHostJobTracker()
  },
  beforeUnmount () {
    if (this.dedicationTracker) this.dedicationTracker.clear()
  },
  methods: {
    initForm () {
      this.formRef = ref()
      this.form = reactive({
        authmethod: this.authMethod,
        storageaccessgroups: []
      })
      this.rules = reactive({
        zoneid: [{ required: true, message: this.$t('message.error.select') }],
        podid: [{ required: true, message: this.$t('message.error.select') }],
        clusterid: [{ required: true, message: this.$t('message.error.select') }],
        hostname: [{ required: true, message: this.$t('message.error.required.input') }],
        username: [{ required: true, message: this.$t('message.error.required.input') }],
        password: [{ required: true, message: this.$t('message.error.required.input') }],
        baremetalcpucores: [{ required: true, message: this.$t('message.error.required.input') }],
        baremetalcpu: [{ required: true, message: this.$t('message.error.required.input') }],
        baremetalmemory: [{ required: true, message: this.$t('message.error.required.input') }],
        baremetalmac: [{ required: true, message: this.$t('message.error.required.input') }]
      })
    },
    fetchData () {
      this.fetchZones()
      this.fetchHostTags()
      this.params = this.$store.getters.apis.addHost.params
      Object.keys(this.placeholder).forEach(item => { this.returnPlaceholder(item) })
    },
    fetchZones () {
      this.loading = true
      getAPI('listZones', { showicon: true }).then(response => {
        this.zonesList = response.listzonesresponse.zone || []
        this.form.zoneid = this.zonesList[0]?.id || null
        this.fetchPods(this.form.zoneid)
      }).catch(error => {
        this.$notifyError(error)
      }).finally(() => {
        this.loading = false
      })
    },
    fetchPods (zoneId) {
      this.form.zoneid = zoneId
      this.podsList = []
      this.form.podid = null
      this.form.clusterid = null
      this.clustersList = []
      this.selectedCluster = null
      this.selectedClusterHyperVisorType = null
      if (!zoneId) return
      this.loading = true
      getAPI('listPods', {
        zoneid: this.form.zoneid
      }).then(response => {
        if (this.form.zoneid !== zoneId) return
        this.podsList = response.listpodsresponse.pod || []
        this.form.podid = this.podsList[0]?.id || null
        this.fetchClusters(this.form.podid)
      }).catch(error => {
        this.$notifyError(error)
        this.podsList = []
        this.form.podid = ''
      }).finally(() => {
        this.loading = false
      })
    },
    fetchClusters (podId) {
      this.form.clusterid = null
      this.clustersList = []
      this.selectedCluster = null
      this.selectedClusterHyperVisorType = null
      if (!podId) return
      this.podId = podId
      this.loading = true
      getAPI('listClusters', {
        podid: this.form.podid
      }).then(response => {
        if (this.form.podid !== podId) return
        this.clustersList = response.listclustersresponse.cluster || []
        this.form.clusterid = this.clustersList[0]?.id || null
        if (this.form.clusterid) {
          this.handleChangeCluster(this.form.clusterid)
        }
      }).catch(error => {
        this.$notifyError(error)
        this.clustersList = []
        this.form.clusterid = null
      }).finally(() => {
        this.loading = false
      })
    },
    fetchHostTags () {
      this.loading = true
      getAPI('listHostTags').then(response => {
        const listTagExists = []
        const hostTagsList = response.listhosttagsresponse.hosttag || []
        hostTagsList.forEach(tag => {
          if (listTagExists.includes(tag.name)) {
            return true
          }

          listTagExists.push(tag.name)
          this.hostTagsList.push(tag)
        })
      }).catch(error => {
        this.$notifyError(error)
        this.hostTagsList = []
      }).finally(() => {
        this.loading = false
      })
    },
    handleChangeCluster (value) {
      this.form.clusterid = value
      this.selectedCluster = this.clustersList.find(i => i.id === this.form.clusterid)
      this.selectedClusterHyperVisorType = this.selectedCluster?.hypervisortype
    },
    toggleDedicated () {
      this.dedicatedDomainId = null
      this.dedicatedAccount = null
      this.showDedicated = !this.showDedicated
    },
    handleAuthMethodChange (val) {
      this.authMethod = val
    },
    async fetchStorageAccessGroups () {
      if (!this.$store.getters.apis?.listStorageAccessGroups) return
      this.storageAccessGroupsLoading = true
      try {
        const result = await getAPI('listStorageAccessGroups', {}, { preserveOnFailure: true })
        const groups = result.liststorageaccessgroupsresponse?.storageaccessgroup || []
        this.storageAccessGroups = [...new Set(groups.map(group => group.name))].map(name => ({ value: name, label: name }))
      } catch (error) {
        this.$notifyError(error)
      } finally {
        this.storageAccessGroupsLoading = false
      }
    },
    async handleSubmitForm () {
      if (this.loading || this.submitting || this.createUnknown) return
      if (this.showDedicated && !this.dedicatedDomainId) {
        this.domainError = true
        return
      }
      this.submitting = true
      this.submitError = ''
      try {
        if (!this.createdHost) {
          await this.formRef.value.validate()
          if (!this.selectedCluster || this.selectedCluster.id !== this.form.clusterid) return
          if (!(await this.registerHost())) return
        }
        if (this.showDedicated && !(await this.dedicateHost(this.createdHost.id))) return
        this.parentFetchData()
        this.$emit('close-action')
      } catch (error) {
        if (error.errorFields?.[0]) this.formRef.value.scrollToField(error.errorFields[0].name)
        else this.$notifyError(error)
      } finally {
        this.submitStage = ''
        this.submitting = false
      }
    },
    async registerHost () {
      const formRaw = toRaw(this.form)
      const values = this.handleRemoveFields(formRaw)

      let url = values.hostname
      if (values.hostname.indexOf('http://') === -1 && this.selectedClusterHyperVisorType !== 'External') {
        url = `http://${values.hostname}`
      }

      if (this.selectedClusterHyperVisorType === 'External') {
        values.username = 'external'
        values.password = 'external'
      }

      const args = {
        zoneid: values.zoneid,
        podid: values.podid,
        clusterid: values.clusterid,
        hypervisor: this.selectedClusterHyperVisorType,
        clustertype: this.selectedCluster.clustertype,
        hosttags: values.hosttags ? values.hosttags.join() : null,
        username: values.username,
        password: this.authMethod !== 'password' ? '' : values.password,
        url: url,
        agentusername: values.agentusername,
        agentpassword: values.agentpassword,
        agentport: values.agentport
      }

      if (this.selectedClusterHyperVisorType === 'BareMetal') {
        args.cpunumber = values.baremetalcpucores
        args.cpuspeed = values.baremetalcpu
        args.memory = values.baremetalmemory
        args.hostmac = values.baremetalmac
      }
      if (values.externaldetails) {
        Object.entries(values.externaldetails).forEach(([key, value]) => {
          args['externaldetails[0].' + key] = value
        })
      }
      if (this.apiParams.allocationstate && formRaw.allocationstate) args.allocationstate = formRaw.allocationstate
      const groups = hostStorageGroups(formRaw.storageaccessgroups)
      if (this.apiParams.storageaccessgroups && groups) args.storageaccessgroups = groups
      Object.keys(args).forEach((key) => (args[key] == null) && delete args[key])
      this.submitStage = 'message.host.add.creating'
      try {
        const response = await postAPI('addHost', args, { preserveOnFailure: true })
        const host = response.addhostresponse?.host?.[0]
        if (host?.id) {
          this.createdHost = { id: host.id, name: host.name }
          this.$message.success(this.$t('message.host.add.created', [host.name || host.id]))
          return true
        }
      } catch (error) {
        if (hostRequestRejected(error)) {
          this.submitError = 'message.host.add.failed'
          this.$notifyError(error)
          return false
        }
      }
      this.createUnknown = true
      this.submitError = 'message.host.add.unknown'
      return false
    },
    async dedicateHost (hostId) {
      this.submitStage = 'message.dedicating.host'
      if (this.dedicateUnknown && !this.dedicateJobId) {
        if (await this.confirmDedication(hostId)) return true
        this.submitError = 'message.host.dedicate.unknown'
        return false
      }
      if (!this.dedicateJobId) {
        try {
          const response = await postAPI('dedicateHost', {
            hostid: hostId,
            domainid: this.dedicatedDomainId,
            account: this.dedicatedAccount
          }, { preserveOnFailure: true, timeout: 15000 })
          this.dedicateJobId = response.dedicatehostresponse?.jobid
        } catch (error) {
          if (hostRequestRejected(error)) {
            this.submitError = 'error.dedicate.host.failed'
            return false
          }
        }
      }
      if (this.dedicateJobId) {
        const result = await this.dedicationTracker.track(this.dedicateJobId, {}, this.dedicateUnknown)
        if (result.jobstatus === 1) {
          this.$message.success(this.$t('message.host.dedicated'))
          return true
        }
        if (result.jobstatus === 2) {
          this.dedicateJobId = ''
          this.dedicateUnknown = false
          this.submitError = 'error.dedicate.host.failed'
          return false
        }
      }
      this.dedicateUnknown = true
      this.submitError = 'message.host.dedicate.unknown'
      return false
    },
    async confirmDedication (hostid) {
      try {
        const result = await getAPI('listDedicatedHosts', { hostid }, { preserveOnFailure: true, timeout: 10000 })
        const host = result.listdedicatedhostsresponse?.dedicatedhost?.find(row => row.hostid === hostid)
        if (host?.domainid !== this.dedicatedDomainId) return false
        if (!this.dedicatedAccount) return !host.accountid
        const accounts = await getAPI('listAccounts', { id: host.accountid }, { preserveOnFailure: true })
        return accounts.listaccountsresponse?.account?.some(row => row.id === host.accountid && row.name === this.dedicatedAccount) ?? false
      } catch {
        return false
      }
    },
    returnPlaceholder (field) {
      this.params.find(i => {
        if (i.name === field) this.placeholder[field] = i.description
      })
    },
    closeAction () {
      if (this.submitting) return
      if (this.createdHost || this.createUnknown) this.parentFetchData()
      this.$emit('close-action')
    },
    onExternalDetailsEnabledChange (val) {
      if (val || !this.form.externaldetails) {
        return
      }
      this.form.externaldetails = undefined
    }
  }
}
</script>

<style lang="scss" scoped>
  .form-layout {
    fieldset {
      min-width: 0;
      padding: 0;
      border: 0;
    }

    .host-save-alert {
      margin-bottom: 12px;
    }

    width: 100%;
  min-width: 0;
  }

  .required {
    color: #ff0000;
    &-label {
      display: none;
      &--error {
        display: block;
      }
    }
  }
</style>
