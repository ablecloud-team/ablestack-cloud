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
  <div>
    <p v-if="listRefreshFailed" role="status">{{ $t('message.list.refresh.stale') }}</p>
    <div class="ssl-cert-toolbar">
      <a-button v-if="canUpload" type="primary" :disabled="loading || uploading" @click="openUpload">
        <template #icon><plus-outlined /></template>{{ $t('label.upload.ssl.certificate') }}
      </a-button>
      <a-button :loading="loading" :disabled="!canRead || uploading" @click="fetchData">
        <template #icon><reload-outlined /></template>{{ $t('label.refresh') }}
      </a-button>
    </div>
    <a-row :gutter="12">
      <a-col :md="24" :lg="24">
        <mold-list-table
detail-tab
          size="small"
          :loading="loading"
          :columns="columns"
          :dataSource="dataSource"
          :rowKey="record => record.id"
          :context-actions="certificateActions"
          :scope="page"
          :pagination="{ current: page, pageSize, total, hideOnSinglePage: true }"
          @change="handleTableChange"
          :scroll="{ x: 680 }"
          v-if="!quickview"
        >
          <template #bodyCell="{ column, record }">
            <template v-if="column.key === 'actions'" class="cert-button-action">
              <tooltip-button
                tooltipPlacement="top"
                :tooltip="$t('label.quickview')"
                type="primary"
                icon="eye-outlined"
                size="small"
                @onClick="onQuickView(record.id)" />
              <tooltip-button
                tooltipPlacement="top"
                :tooltip="$t('label.delete.sslcertificate')"
                v-if="canDelete"
                type="primary"
                :danger="true"
                icon="delete-outlined"
                size="small"
                @onClick="onShowConfirm(record)" />
            </template>
          </template>
        </mold-list-table>

        <a-list size="small" :dataSource="detailColumn" v-if="quickview">
          <div class="close-quickview">
            <a-button @click="() => { quickview = false }">{{ $t('label.close') }}</a-button>
          </div>
          <template #renderItem="{item}">
            <a-list-item v-if="item in detail">
                <div>
                  <strong>{{ $t(item) }}</strong>
                  <br/>
                  <div class="list-item-content">
                    {{ detail[item] }}
                  </div>
                </div>
            </a-list-item>
          </template>
        </a-list>
      </a-col>
    </a-row>

    <MoldDialog
      v-if="showUploadForm"
      class="ssl-cert-upload-dialog"
      size="md"
      :focus-guard="$route.meta.name === 'account'"
      :title="$t('label.upload.ssl.certificate')"
      :closable="!uploading"
      :keyboard="!uploading"
      @cancel="closeUpload">

      <a-form
        layout="vertical"
        ref="uploadForm"
        :model="form"
        :rules="rules"
        @finish="uploadSslCert"
        v-ctrl-enter="uploadSslCert"
      >
        <a-form-item name="name" ref="name">
          <template #label>
            <tooltip-label :title="$t('label.name')" :tooltip="apiParams.name.description" tooltipPlacement="bottom"/>
          </template>
          <a-input
            id="name"
            :placeholder="apiParams.name.description"
            name="name"
            v-model:value="form.name"
          ></a-input>
        </a-form-item>

        <a-form-item name="certificate" ref="certificate" :required="true">
          <template #label>
            <tooltip-label :title="$t('label.certificate')" :tooltip="apiParams.certificate.description" tooltipPlacement="bottom"/>
          </template>
          <a-textarea
            id="certificate"
            rows="2"
            :placeholder="apiParams.certificate.description"
            v-focus="true"
            name="certificate"
            v-model:value="form.certificate"
          ></a-textarea>
        </a-form-item>

        <a-form-item name="privatekey" ref="privatekey" :required="true">
          <template #label>
            <tooltip-label :title="$t('label.privatekey')" :tooltip="apiParams.privatekey.description" tooltipPlacement="bottom"/>
          </template>
          <a-textarea
            id="privatekey"
            rows="2"
            :placeholder="apiParams.privatekey.description"
            name="privatekey"
            v-model:value="form.privatekey"
          ></a-textarea>
        </a-form-item>

        <a-form-item name="certchain" ref="certchain">
          <template #label>
            <tooltip-label :title="$t('label.certificate.chain')" :tooltip="apiParams.certchain.description" tooltipPlacement="bottom"/>
          </template>
          <a-textarea
            id="certchain"
            rows="2"
            :placeholder="apiParams.certchain.description"
            name="certchain"
            v-model:value="form.certchain"
          ></a-textarea>
        </a-form-item>

        <a-form-item name="password" ref="password">
          <template #label>
            <tooltip-label :title="$t('label.password')" :tooltip="apiParams.password.description" tooltipPlacement="bottom"/>
          </template>
          <a-input
            type="password"
            id="password"
            name="password"
            v-model:value="form.password"
          ></a-input>
        </a-form-item>

        <a-form-item name="enabledrevocationcheck" ref="enabledrevocationcheck">
          <template #label>
            <tooltip-label :title="$t('label.enabled.revocation.check')" :tooltip="apiParams.enabledrevocationcheck.description" tooltipPlacement="bottom"/>
          </template>
          <a-checkbox v-model:checked="form.enabledrevocationcheck"></a-checkbox>
        </a-form-item>

      </a-form>
      <template #footer>
        <a-button :disabled="uploading" @click="closeUpload">{{ $t('label.cancel') }}</a-button>
        <a-button type="primary" :loading="uploading" :disabled="!canUpload || uploading" @click="uploadSslCert">{{ $t('label.submit') }}</a-button>
      </template>
    </MoldDialog>
  </div>
</template>

<script>
import MoldListTable from '@/components/view/MoldListTable'
import { listRefreshMixin } from '@/utils/listRefreshMixin'

import { getAPI, postAPI } from '@/api'
import TooltipButton from '@/components/widgets/TooltipButton'
import TooltipLabel from '@/components/widgets/TooltipLabel.vue'
import { reactive, toRaw } from 'vue'
import MoldDialog from '@/components/view/MoldDialog'
import eventBus from '@/config/eventBus'

export default {
  mixins: [listRefreshMixin(['fetchData'], { scopeKey: vm => [vm.sslScope, vm.scopeSuspended, vm.page, vm.pageSize] })],
  name: 'SSLCertificate',
  components: {
    MoldListTable,
    TooltipLabel,
    TooltipButton,
    MoldDialog
  },
  data () {
    return {
      columns: [],
      dataSource: [],
      selectedRowKeys: [],
      detailColumn: [],
      detail: [],
      page: 1,
      pageSize: 10,
      total: 0,
      quickview: false,
      loading: false,
      uploading: false,
      showUploadForm: false,
      form: { enabledrevocationcheck: true },
      rules: {},
      uploadVersion: 0,
      scopeSuspended: false,
      disposed: false
    }
  },
  props: {
    resource: {
      type: Object,
      default () {
        return {}
      }
    },
    tab: {
      type: String,
      default () {
        return ''
      }
    }
  },
  computed: {
    ownerScopeValid () {
      const project = this.$store.getters.project?.id
      return this.$route.meta.name === 'account' || !project || project === '0' || this.resource.id === project
    },
    canRead () { return !this.disposed && !this.scopeSuspended && this.ownerScopeValid && 'listSslCerts' in this.$store.getters.apis },
    canUpload () { return this.canRead && 'uploadSslCert' in this.$store.getters.apis },
    canDelete () { return this.canRead && 'deleteSslCert' in this.$store.getters.apis },
    sslScope () {
      const user = this.$store.getters.userInfo || {}
      return JSON.stringify([this.$route.path, this.resource.id, this.$store.getters.project?.id, user.id, user.roleid,
        Object.keys(this.$store.getters.apis || {}).sort()])
    }
  },
  watch: {
    sslScope (value, oldValue) {
      const before = JSON.parse(oldValue); const after = JSON.parse(value)
      this.invalidateScope()
      if (before[0] !== after[0] || before[1] !== after[1]) {
        this.scopeSuspended = false
        if (this.ownerScopeValid) this.fetchData()
      }
    },
    tab (newValue, oldValue) {
      if (['certificate', 'certificates'].includes(newValue)) {
        this.quickview = false
        this.fetchData()
      }
    },
    resource: {
      deep: true,
      handler (newValue) {
        if (Object.keys(newValue).length > 0 &&
          newValue.id &&
          ['certificate', 'certificates'].includes(this.tab)
        ) {
          this.quickview = false
          this.fetchData()
        }
      }
    }
  },
  beforeCreate () {
    this.apiParams = this.$getApiParams('uploadSslCert')
  },
  created () {
    this.columns = [
      {
        width: 220,
        key: 'name',
        title: this.$t('label.name'),
        dataIndex: 'name'
      },
      {
        key: 'id',
        title: this.$t('label.certificateid'),
        dataIndex: 'id',
        width: 340
      },
      {
        key: 'actions',
        title: this.$t('label.actions'),
        dataIndex: 'actions',
        fixed: 'right',
        width: 80
      }
    ]
    this.detailColumn = ['name', 'certificate', 'certchain']
    this.initForm()
    eventBus.on('project-switch-start', this.invalidateScope)
    this.fetchData()
  },
  beforeUnmount () { this.disposed = true; this.invalidateScope(); eventBus.off('project-switch-start', this.invalidateScope) },
  methods: {
    certificateActions (record) {
      return [
        { key: 'details', label: 'label.quickview', icon: 'eye-outlined', handler: () => this.onQuickView(record.id) },
        ...(this.canDelete ? [{ key: 'delete', label: 'label.delete.sslcertificate', danger: true, icon: 'delete-outlined', handler: () => this.onShowConfirm(record) }] : [])
      ]
    },
    invalidateScope () {
      this.scopeSuspended = true; this.uploadVersion++; this.showUploadForm = false; this.uploading = false
      this.dataSource = []; this.total = 0; this.page = 1; this.detail = []; this.quickview = false; this.loading = false; this.initForm()
    },
    openUpload () { if (!this.canUpload || this.uploading) return; this.initForm(); this.showUploadForm = true },
    closeUpload () { if (this.uploading) return; this.showUploadForm = false; this.initForm() },
    initForm () {
      this.form = reactive({ enabledrevocationcheck: true })
      this.rules = reactive({
        certificate: [{ required: true, message: this.$t('label.required') }],
        privatekey: [{ required: true, message: this.$t('label.required') }]
      })
    },
    handleTableChange (pagination) { this.page = pagination.current; this.pageSize = pagination.pageSize; this.fetchData() },
    fetchData () {
      if (!this.canRead || !this.resource.id) return Promise.resolve()
      const listRequest = this.listRequestToken('fetchData')
      const params = { ignoreproject: true }
      params.page = this.page
      params.pageSize = this.pageSize
      if (this.$route.meta.name === 'account') {
        params.accountid = this.resource.id
        delete params.projectid
      } else { // project
        params.projectid = this.resource.id
      }

      this.loading = !listRequest.loaded

      return getAPI('listSslCerts', params).then(json => {
        if (!this.isListRequestCurrent('fetchData', listRequest)) return

        this.total = json.listsslcertsresponse.count || 0
        const listSslResponse = json.listsslcertsresponse.sslcert

        // check exists json response
        if (!listSslResponse || Object.keys(listSslResponse).length === 0) {
          this.dataSource = []
          return
        }

        this.dataSource = listSslResponse
      }).catch(error => {
        if (!this.isListRequestCurrent('fetchData', listRequest)) return
        listRequest.failed = true
        this.listRefreshFailed = true
        if (listRequest.loaded) return

        this.$notifyError(error)
      }).finally(() => {
        if (!this.isListRequestCurrent('fetchData', listRequest)) return

        this.loading = false
      })
    },
    onQuickView (id) {
      this.loading = true
      const detail = this.dataSource.filter(item => item.id === id)
      this.detail = detail[0]
      this.quickview = true
      this.loading = false
    },
    onDelete (row) {
      if (!this.canDelete || !this.dataSource.some(item => item.id === row.id)) return
      const params = {}
      params.id = row.id

      // show loading
      const message = `${this.$t('label.delete.certificate')} ${this.$t('label.in.progress.for')} ${row.name}`
      const loading = this.$message.loading(message, 0)

      postAPI('deleteSslCert', params).then(json => {
        const jsonResponse = json.deletesslcertresponse

        // hide loading
        setTimeout(loading)

        if (jsonResponse.success) {
          this.$message.success(this.$t('message.success.delete'), 3)
          this.fetchData()
        } else {
          this.$message.error(this.$t('message.delete.failed'), 3)
        }
      }).catch(error => {
        // hide loading
        setTimeout(loading)

        // show error
        this.$notifyError(error)
      })
    },
    onShowConfirm (row) {
      if (!this.canDelete) return
      const self = this
      const title = `${this.$t('label.deleteconfirm')} ${this.$t('label.certificate')}`

      this.$confirm({
        title,
        okText: this.$t('label.ok'),
        okType: 'danger',
        cancelText: this.$t('label.cancel'),
        onOk () {
          self.onDelete(row)
        }
      })
    },
    async uploadSslCert () {
      if (!this.canUpload || this.uploading) return false
      const version = this.uploadVersion; const scope = this.sslScope
      const active = () => !this.disposed && version === this.uploadVersion && scope === this.sslScope && this.canUpload
      this.uploading = true
      let validated = false
      try {
        await this.$refs.uploadForm.validate()
        validated = true
        if (!active()) return false
        const values = toRaw(this.form)
        const params = {
          name: values.name,
          certificate: values.certificate,
          privatekey: values.privatekey,
          enabledrevocationcheck: values.enabledrevocationcheck !== false
        }
        if (this.$route.meta.name === 'account') { params.account = this.resource.name; params.domainid = this.resource.domainid } else { params.projectid = this.resource.id }
        if (values.password) params.password = values.password
        if (values.certchain) params.certchain = values.certchain
        await postAPI('uploadSslCert', params, { preserveOnFailure: true })
        if (!active()) return false
        this.$notification.success({ message: this.$t('message.success.upload.ssl.cert') })
        this.showUploadForm = false
        this.initForm()
        await this.fetchData()
        return true
      } catch (error) {
        if (active() && validated) this.$notifyError(error)
        return false
      } finally { if (active()) this.uploading = false }
    }
  }
}
</script>

<style scoped>
.ssl-cert-toolbar { display: flex; flex-wrap: wrap; gap: 8px; margin-bottom: 12px; }
.ssl-cert-upload-dialog :deep(textarea) { font-family: monospace; }
@media (max-width: 575px) { .ssl-cert-toolbar > .ant-btn { flex: 1 1 auto; } }

:deep(.ant-table-fixed-right) {
  z-index: 5;
}

.cert-button-action button {
  margin-right: 5px;
}

.list-item-content {
  word-break: break-word;
}

.close-quickview {
  text-align: right;
  margin-top: 12px;
  line-height: 32px;
  height: 32px;
}
</style>
