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
  <a-spin :spinning="fetchLoading">
    <p v-if="listRefreshFailed" role="status">{{ $t('message.list.refresh.stale') }}</p>
    <resource-table-toolbar :loading="fetchLoading || listRefreshing > 0" @refresh="fetchData()">
      <a-button
        ref="dedicationTrigger"
        :disabled="posting || !('dedicateGuestVlanRange' in $store.getters.apis)"
        type="primary"
        @click="handleOpenModal">
        <template #icon><plus-outlined /></template>
        {{ $t('label.dedicate.vlan.vni.range') }}
      </a-button>
    </resource-table-toolbar>
    <detail-tab-table
      size="small"
      :scroll="{ x: 720 }"
      style="overflow-y: auto;"
      :loading="fetchLoading"
      :columns="columns"
      :dataSource="items"
      :pagination="false"
      :rowKey="record => record.id">
      <template #bodyCell="{ column, record }">
        <template v-if="column.key === 'actions'">
          <tooltip-button
            :tooltip="$t('label.delete.dedicated.vlan.range')"
            :disabled="posting || !('releaseDedicatedGuestVlanRange' in $store.getters.apis)"
            icon="delete-outlined"
            type="primary"
            danger
            @onClick="openRelease(record)" />
        </template>
      </template>
    </detail-tab-table>
    <detail-tab-pagination
      class="pagination"
      size="small"
      :current="page"
      :pageSize="pageSize"
      :total="totalCount"
      :showTotal="total => `${$t('label.total')} ${total} ${$t('label.items')}`"
      :pageSizeOptions="['10', '20', '40', '80', '100']"
      @change="handleChangePage"
      @showSizeChange="handleChangePageSize"
      showSizeChanger>
      <template #buildOptionText="props">
        <span>{{ props.value }} / {{ $t('label.page') }}</span>
      </template>
    </detail-tab-pagination>

    <MoldDialog
      :visible="modal"
      :title="$t('label.dedicate.vlan.vni.range')"
      size="md"
      focus-guard
      :closable="!posting"
      :keyboard="!posting"
      :after-close="restoreDialogFocus"
      :focus-trigger-after-close="false"
      @cancel="closeDialog">
      <a-spin :spinning="formLoading">
        <a-form
          ref="dedicationForm"
          :disabled="posting"
          :model="form"
          :rules="rules"
          @finish="handleSubmit"
          layout="vertical"
         >
          <p>{{ $t('label.physical.network') }} · {{ resource.name || resource.id }}</p>
          <a-form-item name="range" ref="range" :label="$t('label.vlanrange')">
            <a-input v-model:value="form.range" v-focus="true" />
          </a-form-item>

          <a-form-item name="scope" ref="scope" :label="$t('label.scope')">
            <a-select
              v-model:value="form.scope"
              @change="fetchOwners"
              showSearch
              optionFilterProp="label"
              :filterOption="(input, option) => {
                return option.label.toLowerCase().indexOf(input.toLowerCase()) >= 0
              }" >
              <a-select-option value="account" :label="$t('label.account')">{{ $t('label.account') }}</a-select-option>
              <a-select-option value="project" :label="$t('label.project')">{{ $t('label.project') }}</a-select-option>
            </a-select>
          </a-form-item>

          <a-form-item name="domain" ref="domain" :label="$t('label.domain')">
            <a-select
              @change="fetchOwners"
              v-model:value="form.domain"
              showSearch
              optionFilterProp="label"
              :filterOption="(input, option) => {
                return option.label.toLowerCase().indexOf(input.toLowerCase()) >= 0
              }" >
              <a-select-option v-for="domain in domains" :key="domain.id" :value="domain.id" :label="domain.path || domain.name || domain.description">
                <span>
                  <resource-icon v-if="domain && domain.icon" :image="domain.icon.base64image" size="1x" style="margin-right: 5px"/>
                  <block-outlined v-else style="margin-right: 5px" />
                  {{ domain.path || domain.name || domain.description }}
                </span>
              </a-select-option>
            </a-select>
          </a-form-item>

          <a-form-item name="account" ref="account" :label="$t('label.account')" v-if="form.scope === 'account'">
            <a-select
              v-model:value="form.account"
              showSearch
              optionFilterProp="value"
              :filterOption="(input, option) => {
                return option.value.toLowerCase().indexOf(input.toLowerCase()) >= 0
              }" >
              <a-select-option
                v-for="account in accounts"
                :key="account.id"
                :value="account.name">
                <span>
                  <resource-icon v-if="account && account.icon" :image="account.icon.base64image" size="1x" style="margin-right: 5px"/>
                  <team-outlined v-else style="margin-right: 5px" />
                  {{ account.name }}
                </span>
              </a-select-option>
            </a-select>
          </a-form-item>

          <a-form-item name="project" ref="project" :label="$t('label.project')" v-if="form.scope === 'project'">
            <a-select
              v-model:value="form.project"
              showSearch
              optionFilterProp="label"
              :filterOption="(input, option) => {
                return option.label.toLowerCase().indexOf(input.toLowerCase()) >= 0
              }" >
              <a-select-option
                v-for="project in projects"
                :key="project.id"
                :value="project.id"
                :label="project.name">
                <span>
                  <resource-icon v-if="project && project.icon" :image="project.icon.base64image" size="1x" style="margin-right: 5px"/>
                  <project-outlined v-else style="margin-right: 5px" />
                  {{ project.name }}
                </span>
              </a-select-option>
            </a-select>
          </a-form-item>

        </a-form>
      </a-spin>
      <template #footer>
        <a-button :disabled="posting" @click="closeDialog">{{ $t('label.cancel') }}</a-button>
        <a-button type="primary" :disabled="formLoading" :loading="posting" @click="handleSubmit">{{ $t('label.ok') }}</a-button>
      </template>
    </MoldDialog>
    <MoldDialog
      v-if="releaseTarget"
      :visible="releaseOpen"
      :title="$t('label.delete.dedicated.vlan.range')"
      size="md"
      focus-guard
      :closable="!posting"
      :keyboard="!posting"
      :after-close="finishReleaseDialog"
      :focus-trigger-after-close="false"
      @cancel="closeDialog">
      <a-descriptions :column="1" size="small" bordered>
        <a-descriptions-item :label="$t('label.physical.network')">{{ resource.name || resource.id }}</a-descriptions-item>
        <a-descriptions-item :label="$t('label.vlanrange')">{{ releaseTarget.guestvlanrange }}</a-descriptions-item>
        <a-descriptions-item :label="$t('label.domain')">{{ releaseTarget.domain || '—' }}</a-descriptions-item>
        <a-descriptions-item :label="$t(releaseTarget.project ? 'label.project' : 'label.account')">{{ releaseTarget.project || releaseTarget.account || '—' }}</a-descriptions-item>
      </a-descriptions>
      <template #footer>
        <a-button :disabled="posting" @click="closeDialog">{{ $t('label.cancel') }}</a-button>
        <a-button type="primary" danger :loading="posting" @click="handleDelete">{{ $t('label.delete.dedicated.vlan.range') }}</a-button>
      </template>
    </MoldDialog>
  </a-spin>
</template>

<script>
import ResourceTableToolbar from '@/components/view/ResourceTableToolbar'
import { listRefreshMixin } from '@/utils/listRefreshMixin'

import { getAPI, postAPI } from '@/api'
import TooltipButton from '@/components/widgets/TooltipButton'
import MoldDialog from '@/components/view/MoldDialog'
import ResourceIcon from '@/components/view/ResourceIcon'

export default {
  mixins: [listRefreshMixin(['fetchData'], { routeKey: vm => vm.$route.path })],
  name: 'DedicatedVLANTab',
  components: {
    ResourceTableToolbar,
    TooltipButton,
    MoldDialog,
    ResourceIcon
  },
  props: {
    resource: {
      type: Object,
      required: true
    },
    loading: {
      type: Boolean,
      default: false
    }
  },
  data () {
    return {
      fetchLoading: false,
      formLoading: false,
      posting: false,
      releaseOpen: false,
      releaseTarget: null,
      dialogTrigger: null,
      lookupVersion: 0,
      form: { scope: 'account' },
      rules: Object.fromEntries(['range', 'domain', 'account', 'project'].map(field => [field, [{ required: true, message: this.$t('label.required') }]])),
      items: [],
      domains: [],
      accounts: [],
      projects: [],
      modal: false,
      totalCount: 0,
      page: 1,
      pageSize: 10,
      columns: [
        {
          title: this.$t('label.vlanrange'),
          dataIndex: 'guestvlanrange'
        },
        {
          title: this.$t('label.domain'),
          dataIndex: 'domain'
        },
        {
          title: this.$t('label.account'),
          dataIndex: 'account'
        },
        {
          title: this.$t('label.project'),
          dataIndex: 'project'
        },
        {
          key: 'actions',
          title: this.$t('label.actions')
        }
      ]
    }
  },
  created () {
    this.fetchData()
  },
  watch: {
    loading (busy) {
      if (!busy && this.resource.id) this.fetchData()
    },
    'resource.id' () {
      this.page = 1
      this.items = []
      this.totalCount = 0
      this.closeDialog()
      if (this.resource.id) this.fetchData()
    }
  },
  methods: {
    async fetchData () {
      if (!this.resource.id) return
      const request = this.listRequestToken('fetchData')
      const current = () => this.isListRequestCurrent('fetchData', request)
      this.fetchLoading = !request.loaded
      try {
        const response = await getAPI('listDedicatedGuestVlanRanges', {
          physicalnetworkid: this.resource.id,
          page: this.page,
          pagesize: this.pageSize,
          ignoreproject: true
        }, { preserveOnFailure: true })
        if (!current()) return
        this.items = response.listdedicatedguestvlanrangesresponse.dedicatedguestvlanrange || []
        this.totalCount = response.listdedicatedguestvlanrangesresponse.count || 0
      } catch (error) {
        if (!current()) return
        request.failed = true
        this.listRefreshFailed = true
        if (!request.loaded) this.$notifyError(error)
      } finally {
        if (current()) this.fetchLoading = false
      }
    },
    async fetchOwners () {
      const version = ++this.lookupVersion
      const { domain, scope } = this.form
      const current = () => this.modal && version === this.lookupVersion
      this.accounts = []
      this.projects = []
      this.form.account = undefined
      this.form.project = undefined
      this.formLoading = !!domain
      if (!domain) return
      const isAccount = scope === 'account'
      const api = isAccount ? 'listAccounts' : 'listProjects'
      try {
        const json = await getAPI(api, { domainid: domain, details: 'min', showicon: true, listall: true, ignoreproject: true }, { preserveOnFailure: true })
        if (!current()) return
        if (isAccount) {
          this.accounts = json.listaccountsresponse.account || []
          this.form.account = this.accounts[0]?.name
        } else {
          this.projects = json.listprojectsresponse.project || []
          this.form.project = this.projects[0]?.id
        }
      } catch (error) {
        if (current()) this.$notifyError(error)
      } finally {
        if (current()) this.formLoading = false
      }
    },
    openRelease (item) {
      if (this.posting || !this.$store.getters.apis.releaseDedicatedGuestVlanRange) return
      this.dialogTrigger = document.activeElement
      this.releaseTarget = item
      this.releaseOpen = true
    },
    closeDialog () {
      if (this.posting) return
      this.modal = false
      this.releaseOpen = false
      this.lookupVersion++
      this.formLoading = false
    },
    restoreDialogFocus () {
      if (this.modal || this.releaseOpen) return
      const trigger = this.dialogTrigger
      this.dialogTrigger = null
      const target = trigger?.isConnected && !trigger.disabled ? trigger : this.$refs.dedicationTrigger?.$el
      if (target) target.focus()
    },
    finishReleaseDialog () {
      if (this.releaseOpen) return
      this.releaseTarget = null
      this.restoreDialogFocus()
    },
    async handleDelete () {
      const item = this.releaseTarget
      if (!item?.id || this.posting || !this.$store.getters.apis.releaseDedicatedGuestVlanRange) return
      this.posting = true
      try {
        const response = await postAPI('releaseDedicatedGuestVlanRange', { id: item.id, ignoreproject: true }, { preserveOnFailure: true })
        const job = await this.$pollJob({
          jobId: response.releasededicatedguestvlanrangeresponse.jobid,
          title: this.$t('label.delete.dedicated.vlan.range'),
          description: item.guestvlanrange,
          errorMessage: this.$t('label.deleting.failed')
        })
        if (job?.jobstatus === 1) this.releaseOpen = false
        await this.fetchData()
      } catch (error) {
        this.$notifyError(error)
      } finally {
        this.posting = false
      }
    },
    async handleSubmit () {
      if (this.formLoading || this.posting || !this.$store.getters.apis.dedicateGuestVlanRange) return
      this.posting = true
      try {
        const values = await this.$refs.dedicationForm.validate()
        await postAPI('dedicateGuestVlanRange', {
          physicalnetworkid: this.resource.id,
          vlanrange: values.range.trim(),
          domainid: values.domain,
          ...(values.scope === 'account' ? { account: values.account } : { projectid: values.project }),
          ignoreproject: true
        }, { preserveOnFailure: true })
        this.modal = false
        await this.fetchData()
      } catch (error) {
        if (error.errorFields?.length) this.$refs.dedicationForm.scrollToField(error.errorFields[0].name)
        else this.$notifyError(error)
      } finally {
        this.posting = false
      }
    },
    async handleOpenModal () {
      if (this.posting || !this.$store.getters.apis.dedicateGuestVlanRange) return
      const version = ++this.lookupVersion
      this.dialogTrigger = document.activeElement
      this.form = { scope: 'account' }
      this.domains = []
      this.modal = true
      this.formLoading = true
      try {
        const json = await getAPI('listDomains', { details: 'min', showicon: true, listall: true, ignoreproject: true }, { preserveOnFailure: true })
        if (!this.modal || version !== this.lookupVersion) return
        this.domains = json.listdomainsresponse.domain || []
        this.form.domain = this.domains[0]?.id
        await this.fetchOwners()
      } catch (error) {
        if (!this.modal || version !== this.lookupVersion) return
        this.formLoading = false
        this.$notifyError(error)
      }
    },
    handleChangePage (page, pageSize) {
      this.page = page
      this.pageSize = pageSize
      this.fetchData()
    },
    handleChangePageSize (currentPage, pageSize) {
      this.page = currentPage
      this.pageSize = pageSize
      this.fetchData()
    }
  }
}
</script>

<style scoped>
.pagination { margin-top: 20px; }
</style>
