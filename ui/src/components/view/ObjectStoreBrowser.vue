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
    size="sm"
    :visible="showCreateFolderModal"
    :closable="!createFolderLoading"
    :destroy-on-close="true"
    :title="$t('label.objectstore.create.folder')"
    @cancel="closeCreateFolderModal">
    <a-form layout="vertical">
      <a-form-item :label="$t('label.objectstore.folder.name')">
        <a-input
          v-model:value="createFolderName"
          v-focus="true"
          :aria-label="$t('label.objectstore.folder.name')"
          :placeholder="$t('message.objectstore.folder.name')"
          :disabled="createFolderLoading"
          @pressEnter="createFolder()" />
      </a-form-item>
    </a-form>
    <template #footer>
      <a-button :disabled="createFolderLoading" @click="closeCreateFolderModal">{{ $t('label.cancel') }}</a-button>
      <a-button type="primary" :loading="createFolderLoading" :disabled="createFolderLoading || !getCreateFolderObjectName()" @click="createFolder()">{{ $t('label.create') }}</a-button>
    </template>
  </MoldDialog>

  <MoldDialog
    size="md"
    class="object-storage-dialog bucket-upload-dialog"
    :visible="showUploadModal"
    :closable="!uploadLoading"
    :destroy-on-close="true"
    :title="$t('label.upload')"
    @cancel="closeUploadModal">
    <a-upload-dragger
      :multiple="true"
      :fileList="uploadFileList"
      listType="picture"
      :disabled="uploadLoading"
      :beforeUpload="beforeUpload"
      @remove="removeUploadFile">
      <p class="ant-upload-drag-icon"><cloud-upload-outlined /></p>
      <p class="ant-upload-text">{{ $t('label.volume.volumefileupload.description') }}</p>
    </a-upload-dragger>
    <div v-if="uploadLoading || uploadTotalCount > 0" class="object-store-upload-status" role="status" aria-live="polite">
      <div class="object-store-upload-status-title">{{ $t(uploadLoading ? 'label.objectstore.uploading' : 'label.objectstore.upload.complete') }}</div>
      <p v-if="uploadFailedCount" class="object-store-upload-status-summary">{{ $t('message.objectstore.upload.retry.remaining') }}</p>
      <div class="object-store-upload-status-summary">{{ $t('message.objectstore.upload.counts', { total: uploadTotalCount, complete: uploadCompletedCount, failed: uploadFailedCount, pending: uploadPendingCount }) }}</div>
      <div v-if="uploadCurrentFileName" class="object-store-upload-current">
        {{ $t('label.objectstore.current.file') }}
        <div class="object-store-upload-current-name">{{ uploadCurrentFileName }}</div>
      </div>
    </div>
    <a-divider dashed/>
    <a-form layout="vertical">
      <a-form-item :label="$t('label.upload.path')">
        <a-input v-model:value="uploadDirectory" :aria-label="$t('label.upload.path')" :placeholder="$t('label.upload.description')" :disabled="uploadLoading" />
      </a-form-item>
      <a-form-item :label="$t('label.metadata')">
        <BucketUploadMetadata :pairs="uploadMetaData" :disabled="uploadLoading" @update-pairs="pairs => uploadMetaData = pairs" @validity="valid => uploadMetadataValid = valid" />
      </a-form-item>
    </a-form>
    <template #footer>
      <a-button :disabled="uploadLoading" @click="closeUploadModal">{{ $t('label.cancel') }}</a-button>
      <a-button type="primary" :loading="uploadLoading" :disabled="uploadLoading || uploadFileList.length === 0 || !uploadMetadataValid" @click="uploadFiles()">{{ $t('label.upload') }}</a-button>
    </template>
  </MoldDialog>

  <a-drawer
    v-if="showObjectDetails && $route.query.tab === 'browser'"
    class="object-store-drawer"
    :width="'min(480px, calc(100vw - 24px))'"
    :visible="showObjectDetails"
    :closable="true"
    :maskClosable="true"
    @close="closeObjectDetails"
    :title="record.name"
    >
    <div>
      <BucketObjectMetadata :resource="resource" :object-key="record.name || ''" :active="showObjectDetails && $route.query.tab === 'browser' && !objectListScopeChanged && objectStoreRouteMatchesResource" :context-key="objectStoreContextKey" :revision="objectMetadataRevision" :listing="record" />
      <a-row v-if="isPublicBucket()" class="object-store-detail-row">
        <a-col :span="24">
          <tooltip-label :title="$t('label.url')" :tooltip="$t('label.object.url.description')" bold/>
        </a-col>
        <a-col :span="24" class="object-store-detail-value">
          <div class="object-store-url-controls">
            <a-button type="primary" @click="openObjectUrl">
              {{ $t('label.object.open.url') }}
            </a-button>
          </div>
        </a-col>
      </a-row>
      <a-row v-if="!isPublicBucket()" class="object-store-presigned-url-row" justify="space-between">
        <a-col :span="24">
          <tooltip-label :title="$t('label.object.presigned.url')" :tooltip="$t('label.object.presigned.url.description')" bold />
        </a-col>
        <a-col :span="24" class="object-store-detail-value">
          <div class="object-store-presigned-url-description">
            {{ $t('label.object.presigned.url.expiry.description') }}
          </div>
          <div class="object-store-presigned-url-controls">
            <a-input-number
              v-model:value="presignedUrlExpiryValue"
              :aria-label="$t('label.objectstore.expiry.value')"
              :min="1"
              :max="getPresignedUrlExpiryMaxValue()"
              :precision="0"
              :disabled="presignedUrlLoading"/>
            <a-select
              v-model:value="presignedUrlExpiryUnit"
              :aria-label="$t('label.objectstore.expiry.unit')"
              :disabled="presignedUrlLoading">
              <a-select-option value="seconds">{{ $t('label.seconds') }}</a-select-option>
              <a-select-option value="minutes">{{ $t('label.minutes') }}</a-select-option>
              <a-select-option value="hours">{{ $t('label.hours') }}</a-select-option>
            </a-select>
          </div>
          <div class="object-store-presigned-url-action">
            <a-button
              type="primary"
              :loading="presignedUrlLoading"
              :disabled="!presignedUrlExpiryValue"
              @click="generatePresignedUrl">
              {{ $t('label.copy.share.url') }}
            </a-button>
          </div>
          <a-input
            v-if="presignedUrlPreview"
            class="object-store-share-preview"
            :value="presignedUrlPreview"
            :aria-label="$t('label.objectstore.generated.share.url')"
            readonly
            autocomplete="off" />
          <a-alert v-if="presignedUrlCopyFailed" type="warning" show-icon :message="$t('message.objectstore.share.copy.failed')" />
        </a-col>
      </a-row>
    </div>
  </a-drawer>

  <div>
    <a-card class="breadcrumb-card">
      <a-row>
        <a-breadcrumb>
          <a-breadcrumb-item>
            <button type="button" class="object-store-link" :aria-label="$t('label.objectstore.root')" @click="openDir('')">
              <HomeOutlined />
              <span v-if="getObjectStorePathRoutes().length === 0" class="object-store-root-path">/</span>
            </button>
          </a-breadcrumb-item>
          <a-breadcrumb-item
            v-for="route in getObjectStorePathRoutes()"
            :key="route.path">
            <button type="button" class="object-store-link" @click="openDir(route.path)">
              {{ route.breadcrumbName }}
            </button>
          </a-breadcrumb-item>
        </a-breadcrumb>
      </a-row>
      <a-divider/>
      <a-row class="object-store-usage-row" :gutter="[16, 8]">
        <a-col>
          <span class="object-store-usage-label">{{ $t('label.used.capacity') }}</span>
          <span>{{ convertKB(bucketUsageSize || 0) }}</span>
        </a-col>
        <a-col v-if="resource.quota">
          <span class="object-store-usage-label">{{ $t('label.total.allocated.capacity') }}</span>
          <span>{{ resource.quota }} GiB</span>
        </a-col>
      </a-row>
      <BucketUsageStatus :resource="usageSnapshot" />
      <div v-if="usageSyncing" class="bucket-usage-progress" role="status" aria-live="polite">{{ $t('label.bucket.usage.collecting') }}</div>
      <a-alert v-if="usageSyncError" class="bucket-usage-error" type="warning" show-icon :message="$t('message.bucket.usage.failed')">
        <template #description><p>{{ $t(usageSyncError) }}</p><a-button :loading="usageSyncing" :disabled="!canSyncUsage" @click="syncBucketUsage()">{{ $t('label.retry') }}</a-button></template>
      </a-alert>
      <div class="object-store-toolbar">
        <div class="object-store-actions">
          <a-button type="primary" :disabled="!client || loading || fetching || objectListScopeChanged || bucketPurgeActive || usageSyncing" @click="openCreateFolderModal">
            <template #icon><folder-add-outlined /></template>{{ $t('label.objectstore.create.folder') }}
          </a-button>
          <a-button :disabled="!client || loading || fetching || objectListScopeChanged || bucketPurgeActive || usageSyncing" @click="openUploadModal">
            <template #icon><upload-outlined /></template>{{ $t('label.upload') }}
          </a-button>
          <a-button v-if="selectedRows.length > 0" danger :disabled="loading || fetching || objectListScopeChanged || bucketPurgeActive || usageSyncing" @click="removeObjects()">
            <template #icon><delete-outlined /></template>{{ $t('label.delete') }}
          </a-button>
          <a-button v-if="fetching" @click="cancelObjectListing">{{ $t('label.cancel') }}</a-button>
          <a-button v-if="canSyncUsage" :loading="usageSyncing" :disabled="objectListScopeChanged || bucketPurgeActive" @click="syncBucketUsage()">
            <template #icon><reload-outlined /></template>{{ $t('label.bucket.usage.sync') }}
          </a-button>
          <a-button :loading="fetching" :disabled="!client || objectListScopeChanged" @click="listObjects()">
            <template #icon><reload-outlined /></template>{{ $t('label.bucket.list.refresh') }}
          </a-button>
        </div>
        <a-input-search
          class="object-store-search"
          allow-clear
          v-model:value="searchInput"
          :aria-label="$t('label.objectstore.search')"
          :placeholder="$t('label.objectstore.search')"
          :disabled="!client || objectListScopeChanged"
          @search="searchObjects()"
          :enter-button="$t('label.search')" />
      </div>
    </a-card>

    <div>
      <a-alert
        v-if="objectListError"
        type="error"
        show-icon
        :message="$t('message.objectstore.list.failed')">
        <template #description>
          <p>{{ objectListError.startsWith('message.') ? $t(objectListError) : objectListError }}</p>
          <a-button :disabled="objectListScopeChanged" @click="retryObjectListing">{{ $t('label.retry') }}</a-button>
        </template>
      </a-alert>
      <detail-tab-table
        :columns="columns"
        :row-key="objectStoreRowKey"
        :data-source="records"
        :loading="loading"
        :scroll="{ x: 620 }"
        :size="'small'"
        :pagination="{ current: page, pageSize: pageSize, total: total, showSizeChanger: false }"
        :row-selection="{ selectedRowKeys: selectedRows.map(objectStoreRowKey), onChange: onSelectChange }"
        @change="handleTableChange">
        <template #bodyCell="{ column, record }">
          <template v-if="column.key == 'name'">
            <template v-if="record.name === undefined && record.prefix">
              <button type="button" class="object-store-link" :aria-label="$t('label.objectstore.open.folder', { name: record.prefix })" @click="openDir(record.prefix)">
                <folder-outlined /> {{ record.prefix.replace(this.browserPath, '').replace('/', '') }}
              </button>
            </template>
            <template v-else>
              <button type="button" class="object-store-link" :aria-label="$t('label.objectstore.view.object', { name: record.name })" @click="showObjectDescription(record, $event)">
                {{ record.name.split('/').pop() }}
              </button>
            </template>
          </template>
          <template v-else-if="column.key == 'size'">
            <template v-if="record.name !== undefined && !record.prefix">
              {{ convertBytes(record.size) }}
            </template>
          </template>
          <template v-else-if="column.key == 'lastModified' && record.lastModified">
            {{ $toLocaleDate(record.lastModified) }}
          </template>
        </template>
      </detail-tab-table>
    </div>
  </div>

</template>

<script>
import { listRefreshMixin } from '@/utils/listRefreshMixin'
import { readObjectStoreListing } from '@/utils/objectStoreListing'
import { BucketS3Session } from '@/utils/bucketS3Session'
import { CopySourceOptions, CopyDestinationOptions } from 'minio'
import { getAPI, postAPI } from '@/api'
import { genericCompare } from '@/utils/sort.js'
import InfoCard from '@/components/view/InfoCard'
import BucketUsageStatus from '@/components/view/BucketUsageStatus'
import BucketObjectMetadata from '@/components/view/BucketObjectMetadata'
import MoldDialog from '@/components/view/MoldDialog'
import TooltipButton from '@/components/widgets/TooltipButton'
import TooltipLabel from '@/components/widgets/TooltipLabel'
import BucketUploadMetadata from '@/components/view/BucketUploadMetadata'
import { uploadMetadataHeaders } from '@/utils/bucketUploadMetadata'

const normalizeObjectStorePath = path => {
  if (!path || path === '/') {
    return ''
  }
  path = String(path).replace(/^\/+/, '')
  return path.endsWith('/') ? path : `${path}/`
}

const pageSize = 20
const deleteBatchSize = 1000

export default {
  mixins: [listRefreshMixin(['listObjects'], {
    interval: 60000,
    active: vm => !!vm.client && !vm.objectListScopeChanged,
    // Only actual S3 query/identity inputs belong here, not tab URL formatting or UI pagination.
    scopeKey: vm => [vm.objectStoreContextKey, vm.objectStoreConnectionKey, vm.browserPath, vm.searchPrefix]
  })],
  name: 'ObjectStoreBrowser',
  components: {
    InfoCard,
    BucketUsageStatus,
    BucketObjectMetadata,
    MoldDialog,
    TooltipButton,
    TooltipLabel,
    BucketUploadMetadata
  },
  emits: ['change-resource', 'refresh'],
  props: {
    resource: {
      type: Object,
      required: true
    },
    resourceType: {
      type: String,
      required: true
    }
  },
  data () {
    return {
      client: null,
      loading: false,
      records: [],
      browserPath: normalizeObjectStorePath(this.$route.query.browserPath),
      pageSize,
      page: 1,
      pageStartAfterMap: { 1: '' },
      total: 0,
      selectedRows: [],
      searchInput: '',
      searchPrefix: '',
      objectListError: '',
      objectListScopeChanged: false,
      showUploadModal: false,
      uploadFileList: [],
      uploadDirectory: normalizeObjectStorePath(this.$route.query.browserPath),
      uploadMetaData: {},
      uploadMetadataValid: true,
      uploadLoading: false,
      uploadTotalCount: 0,
      uploadCompletedCount: 0,
      uploadFailedCount: 0,
      uploadFailedFiles: [],
      uploadCurrentFileName: '',
      showCreateFolderModal: false,
      createFolderName: '',
      createFolderLoading: false,
      bucketUsageSize: this.resource.size,
      usageSnapshot: { ...this.resource },
      usageSyncing: false,
      usageSyncError: '',
      usageSyncGeneration: 0,
      presignedUrlExpiryValue: 1,
      presignedUrlExpiryUnit: 'hours',
      presignedUrlLoading: false,
      presignedUrlPreview: '',
      presignedUrlCopyFailed: false,
      presignedUrlGeneration: 0,
      record: {},
      showObjectDetails: false,
      objectMetadataRevision: 0,
      fetching: false
    }
  },
  computed: {
    canSyncUsage () { return !!this.$store?.getters?.apis?.syncBucketUsage },
    usageScopeKey () { return JSON.stringify([this.objectStoreContextKey, this.resource.id, this.canSyncUsage]) },
    bucketPurgeActive () { return ['PURGE', 'PROTECTION'].includes(this.resource.quotaoperationtype) && !['Succeeded', 'Failed'].includes(this.resource.quotaoperationstate) },
    columns () {
      return [
        {
          key: 'name',
          title: this.$t('label.name'),
          sorter: (a, b) => genericCompare(a?.name || '', b?.name || '')
        },
        {
          key: 'size',
          title: this.$t('label.size'),
          sorter: (a, b) => genericCompare(a?.size || '', b?.size || '')
        },
        {
          key: 'lastModified',
          title: this.$t('label.last.updated'),
          sorter: (a, b) => genericCompare(a?.lastModified || '', b?.lastModified || '')
        }
      ]
    },
    objectStoreContextKey () {
      return JSON.stringify([this.$route?.path, this.$store?.getters.project?.id,
        this.$store?.getters.userInfo?.id, this.$store?.getters.userInfo?.roleid,
        this.$store?.getters.userInfo?.roletype, this.$store?.getters.project?.state,
        this.$store?.state?.user?.token, !!this.$store?.getters?.apis?.syncBucketUsage])
    },
    objectStoreRouteMatchesResource () {
      return !this.$route.params?.id || String(this.resource.id).toLowerCase() === String(this.$route.params.id).toLowerCase()
    },
    objectStoreConnectionKey () {
      return JSON.stringify([this.resource.id, this.resource.name, this.resource.url, this.resource.credentialstate,
        this.resource.credentialrevision])
    },
    uploadPendingCount () {
      const uploadingCount = this.uploadLoading && this.uploadCurrentFileName ? 1 : 0
      return Math.max(this.uploadTotalCount - this.uploadCompletedCount - this.uploadFailedCount - uploadingCount, 0)
    }
  },
  watch: {
    'resource.size' () { this.adoptUsage(this.resource) },
    'resource.usagestatus' () { this.adoptUsage(this.resource) },
    'resource.usagelastupdated' () { this.adoptUsage(this.resource) },
    'resource.usagelastattempt' () { this.adoptUsage(this.resource) },
    objectStoreContextKey () {
      // Never reuse the previous identity's S3 client while the parent is authorizing new props.
      this.objectListScopeChanged = true
      this.resetObjectStoreClient(false)
    },
    objectStoreConnectionKey () {
      if (!this.objectStoreRouteMatchesResource) {
        this.objectListScopeChanged = true
        return this.resetObjectStoreClient(false)
      }
      this.objectListScopeChanged = false
      this.resetObjectStoreClient()
    },
    resource (value, previous) {
      const adopted = this.adoptUsage(value)
      if (adopted === false && !this.objectListScopeChanged && this.objectStoreRouteMatchesResource && this.usageSnapshot.id === value.id) {
        this.$emit('change-resource', { ...value, ...this.usageSnapshot })
      }
      if (this.objectListScopeChanged && value !== previous && value.id && this.objectStoreRouteMatchesResource) {
        this.objectListScopeChanged = false
        this.resetObjectStoreClient()
      }
    },
    '$route.query.tab' (tab) { if (tab !== 'browser') this.closeObjectDetails(false) },
    '$route.query' () {
      if (this.$route.query.tab !== 'browser' || this.objectListScopeChanged) return
      const path = normalizeObjectStorePath(this.$route.query.browserPath)
      if (path === this.browserPath) return
      this.browserPath = path
      this.uploadDirectory = path
      this.page = 1
      this.fetchData()
    }
  },
  created () {
    this.objectListing = null
    this.usageSyncPromise = null
    this.objectStoreFetchGeneration = 0
    this.fetchData()
  },
  deactivated () { this.closeObjectDetails(false) },
  beforeUnmount () {
    ++this.usageSyncGeneration
    this.usageSyncPromise = null
    this.clearPresignedUrl()
    this.bucketS3Session && this.bucketS3Session.dispose()
    this.client = null
    this.cancelObjectListing()
    this.objectListing = null
  },
  methods: {
    openCreateFolderModal () {
      if (this.createFolderLoading) {
        return
      }
      this.createFolderName = ''
      this.showCreateFolderModal = true
    },
    closeCreateFolderModal () {
      if (this.createFolderLoading) {
        return
      }
      this.showCreateFolderModal = false
      this.createFolderName = ''
    },
    getCreateFolderObjectName () {
      const folderName = String(this.createFolderName || '').trim().replace(/^\/+/, '').replace(/\/+$/, '')
      if (!folderName) {
        return ''
      }
      return normalizeObjectStorePath(`${this.browserPath}${folderName}`)
    },
    createFolder () {
      const mutationScope = this.usageScopeKey
      if (this.createFolderLoading) {
        return
      }
      const objectName = this.getCreateFolderObjectName()
      if (!objectName) {
        return
      }
      this.createFolderLoading = true
      this.loading = true
      this.client.putObject(this.resource.name, objectName, Buffer.from(''), 0, {}, err => {
        if (mutationScope !== this.usageScopeKey || this.listRefreshDisposed) return
        this.createFolderLoading = false
        if (err) {
          this.loading = false
          return this.$notification.error({
            message: this.$t('error.execute.api.failed'),
            description: err.message
          })
        }
        this.$notification.success({
          message: this.$t('label.objectstore.create.folder'),
          description: this.$t('message.objectstore.folder.created', { name: objectName })
        })
        this.showCreateFolderModal = false
        this.createFolderName = ''
        this.listObjects()
        this.syncBucketUsage('mutation')
      })
    },
    openUploadModal () {
      if (this.uploadLoading) {
        return
      }
      this.resetUploadForm()
      this.showUploadModal = true
    },
    closeUploadModal () {
      if (this.uploadLoading) {
        return
      }
      this.showUploadModal = false
      this.resetUploadForm()
    },
    resetUploadProgress () {
      this.uploadTotalCount = 0
      this.uploadCompletedCount = 0
      this.uploadFailedCount = 0
      this.uploadFailedFiles = []
      this.uploadCurrentFileName = ''
    },
    resetUploadForm () {
      this.uploadFileList = []
      this.uploadDirectory = this.browserPath
      this.uploadMetaData = {}; this.uploadMetadataValid = true
      this.resetUploadProgress()
    },
    handleTableChange (pagination, filters, sorter) {
      if (this.page !== pagination.current) {
        this.page = pagination.current
      }
    },
    retryObjectListing () {
      if (this.objectListScopeChanged || (this.resource.credentialstate && this.resource.credentialstate !== 'Verified')) {
        this.$emit('refresh')
        return
      }
      return this.fetchData()
    },
    async fetchData () {
      if (this.objectListScopeChanged || !this.objectStoreRouteMatchesResource || !this.resource.id) return
      const generation = ++this.objectStoreFetchGeneration
      const path = this.browserPath
      const context = this.objectStoreContextKey
      try {
        if (this.$route.query.browserPath !== path) {
          await this.$router.replace({ query: { ...this.$route.query, browserPath: path } })
        }
        if (generation !== this.objectStoreFetchGeneration || this.listRefreshDisposed || this.objectListScopeChanged ||
            context !== this.objectStoreContextKey || path !== this.browserPath) return
        if (this.resource.credentialstate && this.resource.credentialstate !== 'Verified') {
          throw new Error('message.objectstore.credential.unverified')
        }
        if (!this.client) {
          this.loading = true
          this.fetching = true
          await this.initMinioClient()
        } else {
          return this.listObjects()
        }
      } catch (error) {
        if (generation !== this.objectStoreFetchGeneration || this.listRefreshDisposed) return
        this.loading = false
        this.fetching = false
        this.objectListError = error.message === 'message.objectstore.credential.unverified' || error.message?.startsWith('message.bucket.credentials.') ? error.message : this.$t('message.objectstore.list.failed')
      }
    },
    resetObjectStoreClient (reload = true) {
      ++this.usageSyncGeneration
      this.usageSyncPromise = null
      this.usageSyncing = false
      this.usageSyncError = ''
      this.usageSnapshot = {}
      this.bucketUsageSize = undefined
      if (!this.objectListScopeChanged && this.objectStoreRouteMatchesResource) this.adoptUsage(this.resource)
      this.clearPresignedUrl()
      this.bucketS3Session && this.bucketS3Session.dispose()
      this.bucketS3Session = null
      ++this.objectStoreFetchGeneration
      this.cancelObjectListing()
      this.objectListing = null
      this.client = null
      this.records = []
      this.selectedRows = []
      this.total = 0
      this.page = 1
      this.loading = false
      this.fetching = false
      this.showObjectDetails = false
      this.record = {}
      this.searchInput = ''
      this.searchPrefix = ''
      this.browserPath = normalizeObjectStorePath(this.$route.query.browserPath)
      this.uploadDirectory = this.browserPath
      this.objectListError = reload ? '' : this.$t('message.objectstore.list.context.changed')
      if (reload && !this.listRefreshDisposed) this.fetchData()
    },
    cancelObjectListing () {
      if (this.objectListing) this.objectListing.cancel()
    },
    searchObjects () {
      this.searchPrefix = this.searchInput
      this.page = 1
      return this.listObjects()
    },
    getObjectStorePathRoutes () {
      let path = ''
      const routeList = []
      for (const route of this.browserPath.split('/')) {
        if (route) {
          path = `${path}${route}/`
          routeList.push({
            path: path,
            breadcrumbName: route
          })
        }
      }
      return routeList
    },
    convertBytes (val) {
      if (val < 1024 * 1024) return `${(val / 1024).toFixed(2)} KB`
      if (val < 1024 * 1024 * 1024) return `${(val / 1024 / 1024).toFixed(2)} MB`
      if (val < 1024 * 1024 * 1024 * 1024) return `${(val / 1024 / 1024 / 1024).toFixed(2)} GB`
      if (val < 1024 * 1024 * 1024 * 1024 * 1024) return `${(val / 1024 / 1024 / 1024 / 1024).toFixed(2)} TB`
      return val
    },
    convertKB (val) {
      if (val < 1024) return `${Number(val).toFixed(2)} KB`
      if (val < 1024 * 1024) return `${(val / 1024).toFixed(2)} MB`
      if (val < 1024 * 1024 * 1024) return `${(val / 1024 / 1024).toFixed(2)} GB`
      if (val < 1024 * 1024 * 1024 * 1024) return `${(val / 1024 / 1024 / 1024).toFixed(2)} TB`
      return val
    },
    openDir (name) {
      const normalizedName = normalizeObjectStorePath(name)
      this.browserPath = normalizedName
      this.uploadDirectory = normalizedName
      this.page = 1
      this.fetchData()
    },
    listObjects () {
      if (!this.client || this.objectListScopeChanged || !this.objectStoreRouteMatchesResource) return
      const request = this.listRequestToken('listObjects')
      this.cancelObjectListing()
      this.loading = !request.loaded
      this.fetching = true
      this.objectListError = ''
      if (!request.loaded) {
        this.records = []
        this.selectedRows = []
        this.total = 0
      }
      const currentPath = normalizeObjectStorePath(this.browserPath)
      const operation = readObjectStoreListing(() =>
        this.client.listObjectsV2(this.resource.name, currentPath + this.searchPrefix, false, ''))
      this.objectListing = operation
      return operation.promise.then(result => {
        if (this.objectListing !== operation) return
        this.objectListing = null
        // Release this operation's spinner even if its scope was invalidated without a replacement.
        this.loading = false
        this.fetching = false
        if (!this.isListRequestCurrent('listObjects', request) || this.objectListScopeChanged) return
        if (result.status === 'success') {
          this.records = result.records.filter(obj => !this.isCurrentDirectoryMarker(obj, currentPath))
          const selected = new Set(this.selectedRows.map(this.objectStoreRowKey))
          this.selectedRows = this.records.filter(row => selected.has(this.objectStoreRowKey(row)))
          this.total = this.records.length
          this.listRefreshFailed = false
        } else {
          request.failed = true
          this.listRefreshFailed = true
          if ([401, 403].includes(result.error?.statusCode) || ['AccessDenied', 'InvalidAccessKeyId', 'SignatureDoesNotMatch', 'ExpiredToken'].includes(result.error?.code)) {
            this.bucketS3Session && this.bucketS3Session.dispose()
            this.bucketS3Session = null
            this.client = null
            this.objectListError = 'message.bucket.credentials.auth.failed'
          } else {
            this.objectListError = this.$t(`message.objectstore.list.${result.status}`)
          }
        }
      })
    },
    isCurrentDirectoryMarker (obj, currentPath) {
      if (!currentPath || obj.prefix || !obj.name) {
        return false
      }
      return obj.name === currentPath && Number(obj.size || 0) === 0
    },
    async removeObjects () {
      ++this.objectMetadataRevision
      const mutationScope = this.usageScopeKey
      this.loading = true
      this.page = 1
      this.pageStartAfterMap = { 1: '' }
      const objectsToDelete = this.selectedRows.filter((row) => row.name).map((row) => row.name)
      const directoriesToDelete = this.selectedRows.filter((row) => row.prefix).map((row) => row.prefix)
      this.selectedRows = []

      try {
        let deletedCount = 0
        deletedCount += await this.removeObjectsInBatches(objectsToDelete, mutationScope)
        for (const directory of directoriesToDelete) {
          deletedCount += await this.removeDirectoryObjects(directory, mutationScope)
        }

        if (mutationScope !== this.usageScopeKey || this.listRefreshDisposed) return
        this.$notification.success({
          message: this.$t('label.delete'),
          description: this.$t('message.success.remove.objectstore.objects.count', { count: deletedCount })
        })
        this.listObjects()
        await this.syncBucketUsage('mutation')
      } catch (err) {
        if (mutationScope !== this.usageScopeKey || this.listRefreshDisposed) return
        this.loading = false
        this.$notification.error({
          message: this.$t('error.execute.api.failed'),
          description: err.message
        })
      }
    },
    async removeObjectsInBatches (objectNames, scope = this.usageScopeKey) {
      let deletedCount = 0
      for (let i = 0; i < objectNames.length; i += deleteBatchSize) {
        if (scope !== this.usageScopeKey || this.listRefreshDisposed) throw new Error('Operation context changed')
        const objectBatch = objectNames.slice(i, i + deleteBatchSize)
        await this.removeObjectBatch(objectBatch)
        deletedCount += objectBatch.length
      }
      return deletedCount
    },
    removeObjectBatch (objectNames, client = this.client, bucket = this.resource.name) {
      return new Promise((resolve, reject) => {
        if (objectNames.length === 0) {
          resolve()
          return
        }
        client.removeObjects(bucket, objectNames, err => {
          if (err) {
            reject(err)
            return
          }
          resolve()
        })
      })
    },
    removeDirectoryObjects (directory, scope = this.usageScopeKey) {
      const client = this.client; const bucket = this.resource.name
      return new Promise((resolve, reject) => {
        let deletedCount = 0
        let objectBatch = []
        let deleteChain = Promise.resolve()
        const stream = client.listObjectsV2(bucket, directory, true, '')
        const flushBatch = () => {
          if (objectBatch.length === 0) {
            return
          }
          const batch = objectBatch
          objectBatch = []
          if (typeof stream?.pause === 'function') {
            stream.pause()
          }
          deleteChain = deleteChain.then(() => {
            if (scope !== this.usageScopeKey || this.listRefreshDisposed) throw new Error('Operation context changed')
            return this.removeObjectBatch(batch, client, bucket)
          }).then(() => {
            deletedCount += batch.length
            if (typeof stream?.resume === 'function') {
              stream.resume()
            }
          })
        }
        stream.on('data', (obj) => {
          if (!obj.name) {
            return
          }
          objectBatch.push(obj.name)
          if (objectBatch.length >= deleteBatchSize) {
            flushBatch()
          }
        })
        stream.on('error', reject)
        stream.on('end', () => {
          flushBatch()
          deleteChain.then(() => resolve(deletedCount)).catch(reject)
        })
      })
    },
    adoptUsage (value) {
      if (this.objectListScopeChanged || !this.objectStoreRouteMatchesResource || value.id !== this.resource.id) return false
      const previous = Number.isSafeInteger(this.usageSnapshot.usagelastattemptms) ? this.usageSnapshot.usagelastattemptms : Date.parse(this.usageSnapshot.usagelastattempt || '')
      const incoming = Number.isSafeInteger(value.usagelastattemptms) ? value.usagelastattemptms : Date.parse(value.usagelastattempt || '')
      if (this.usageSnapshot.id === value.id && Number.isFinite(previous) && (!Number.isFinite(incoming) || incoming < previous)) return false
      this.usageSnapshot = { ...value }
      this.bucketUsageSize = value.size
      return true
    },
    syncBucketUsage (reason = 'manual') {
      if (this.usageSyncPromise) return this.usageSyncPromise
      if (!this.resource?.id || !this.canSyncUsage || this.objectListScopeChanged || !this.objectStoreRouteMatchesResource || this.bucketPurgeActive) return Promise.resolve(false)
      const id = this.resource.id; const scope = this.usageScopeKey; const generation = ++this.usageSyncGeneration
      const current = () => generation === this.usageSyncGeneration && scope === this.usageScopeKey && !this.listRefreshDisposed && !this.objectListScopeChanged && this.objectStoreRouteMatchesResource
      this.usageSyncing = true; this.usageSyncError = ''
      if (reason === 'mutation') {
        this.usageSnapshot = { ...this.usageSnapshot, usagestatus: 'Stale' }
        this.$emit('change-resource', { ...this.resource, ...this.usageSnapshot })
      }
      this.usageSyncPromise = (async () => {
        try {
          const json = await getAPI('syncBucketUsage', { id }, { preserveOnFailure: true, timeout: 65000 })
          if (!current()) return false
          const body = json?.syncbucketusageresponse
          const response = body?.bucket || body
          if (response?.id !== id || !Number.isSafeInteger(response.size) || response.size < 0 ||
              !Number.isFinite(Date.parse(response.usagelastupdated || ''))) throw new Error('Incomplete usage result')
          const value = { ...this.resource, ...response }
          if (this.adoptUsage(value)) this.$emit('change-resource', value)
          return true
        } catch (_) {
          if (!current()) return false
          this.usageSyncError = 'message.bucket.usage.preserved'
          // Read the persisted failure status without replacing files or inventing a client timestamp.
          try {
            const json = await getAPI('listBuckets', { id }, { preserveOnFailure: true, timeout: 20000 })
            if (!current()) return false
            const value = json?.listbucketsresponse?.bucket?.find(bucket => bucket.id === id)
            if (value && this.adoptUsage(value)) this.$emit('change-resource', { ...this.resource, ...value })
          } catch (_) { /* A read failure also preserves the last successful snapshot. */ }
          if (reason === 'mutation' && current()) {
            this.$notification.warning({
              message: this.$t('message.bucket.usage.object.completed'), description: this.$t('message.bucket.usage.preserved')
            })
          }
          return false
        } finally {
          if (current()) { this.usageSyncing = false; this.usageSyncPromise = null }
        }
      })()
      return this.usageSyncPromise
    },
    async initMinioClient () {
      if (!this.bucketS3Session || this.bucketS3Session.disposed) {
        const id = this.resource.id
        this.bucketS3Session = new BucketS3Session(() => postAPI('getBucketCredentials', { id, reveal: true }, { preserveOnFailure: true }), () => {
          this.clearPresignedUrl()
          this.showObjectDetails = false
          this.record = {}
          this.objectListScopeChanged = true
          this.cancelObjectListing()
          this.client = null
          this.records = []
          this.selectedRows = []
          this.loading = false
          this.fetching = false
          this.objectListError = 'message.bucket.credentials.context.changed'
        })
      }
      const session = this.bucketS3Session
      const context = this.objectStoreContextKey
      const client = await session.getClient()
      if (session.disposed || this.bucketS3Session !== session || context !== this.objectStoreContextKey ||
          this.listRefreshDisposed || this.objectListScopeChanged || !this.objectStoreRouteMatchesResource) return
      this.client = client
      return this.listObjects()
    },
    objectStoreRowKey (row) { return row.prefix ? `prefix:${row.prefix}` : `object:${row.name}` },
    onSelectChange (keys, rows) { this.selectedRows = rows || [] },
    beforeUpload (file) {
      if (this.uploadLoading) {
        return false
      }
      this.uploadFileList = [...this.uploadFileList, file]
      return false
    },
    removeUploadFile (file) {
      if (this.uploadLoading) {
        return false
      }
      const index = this.uploadFileList.findIndex(item => item === file || (file.uid != null && item.uid === file.uid))
      if (index < 0) {
        return true
      }
      const newFileList = this.uploadFileList.slice()
      newFileList.splice(index, 1)
      this.uploadFileList = newFileList
      return true
    },
    updateUploadFileStatus (file, status, error) {
      const index = this.uploadFileList.findIndex(uploadFile => uploadFile === file || (file.uid != null && uploadFile.uid === file.uid))
      if (index < 0) {
        return
      }
      const newFileList = this.uploadFileList.slice()
      newFileList[index].status = status
      newFileList[index].error = error
      this.uploadFileList = newFileList
    },
    async uploadFiles () {
      const mutationScope = this.usageScopeKey
      if (this.uploadLoading) {
        return
      }
      if (!this.uploadMetadataValid) return
      let metadata
      try { metadata = uploadMetadataHeaders(this.uploadMetaData) } catch (_) { this.uploadMetadataValid = false; return }
      const files = this.uploadFileList.filter(file => file.status !== 'done')
      if (files.length === 0) {
        return
      }
      ++this.objectMetadataRevision
      this.uploadDirectory = normalizeObjectStorePath(this.uploadDirectory)
      if (this.uploadDirectory && !this.uploadDirectory.endsWith('/')) {
        this.uploadDirectory = this.uploadDirectory + '/'
      }
      this.uploadLoading = true
      this.loading = true
      this.uploadTotalCount = files.length
      this.uploadCompletedCount = 0
      this.uploadFailedCount = 0
      this.uploadFailedFiles = []
      this.uploadCurrentFileName = ''
      try {
        for (const file of files) {
          if (mutationScope !== this.usageScopeKey || this.listRefreshDisposed) return
          const objectName = this.uploadDirectory + file.name
          this.uploadCurrentFileName = file.name
          this.updateUploadFileStatus(file, 'uploading')
          try {
            await this.asyncUploadFile(file, objectName, metadata, mutationScope)
            if (mutationScope !== this.usageScopeKey || this.listRefreshDisposed) return
            this.updateUploadFileStatus(file, 'done')
            this.uploadCompletedCount++
          } catch (error) {
            if (mutationScope !== this.usageScopeKey || this.listRefreshDisposed) return
            this.updateUploadFileStatus(file, 'error', error)
            this.uploadFailedCount++
            this.uploadFailedFiles.push({
              name: file.name,
              message: error?.message || String(error)
            })
          }
        }
        if (mutationScope !== this.usageScopeKey || this.listRefreshDisposed) return
        this.uploadCurrentFileName = ''
        if (this.uploadFailedCount > 0) {
          const failedNames = this.uploadFailedFiles.slice(0, 3).map(file => file.name).join(', ')
          const extraFailedCount = Math.max(this.uploadFailedFiles.length - 3, 0)
          const failedDescription = extraFailedCount > 0 ? this.$t('message.objectstore.upload.more.files', { files: failedNames, count: extraFailedCount }) : failedNames
          this.$notification.error({
            message: this.$t('message.upload.failed'),
            description: this.$t('message.objectstore.upload.failed.summary', { total: files.length, complete: this.uploadCompletedCount, failed: this.uploadFailedCount, files: failedDescription })
          })
          if (this.uploadCompletedCount > 0) {
            this.listObjects()
            await this.syncBucketUsage('mutation')
          }
          return
        }
        this.$notification.success({
          message: this.$t('message.success.upload'),
          description: this.$t('message.objectstore.upload.success.summary', { total: files.length })
        })
        this.showUploadModal = false
        this.resetUploadForm()
        this.listObjects()
        await this.syncBucketUsage('mutation')
      } finally {
        if (mutationScope === this.usageScopeKey && !this.listRefreshDisposed) {
          this.uploadLoading = false
          this.uploadCurrentFileName = ''
          if (this.uploadFailedCount > 0) this.loading = false
        }
      }
    },
    asyncUploadFile (file, objectName, metadata, scope = this.usageScopeKey) {
      const client = this.client; const bucket = this.resource.name
      return new Promise((resolve, reject) => {
        file.arrayBuffer().then((buffer) => {
          if (scope !== this.usageScopeKey || this.listRefreshDisposed) { reject(new Error('Operation context changed')); return }
          const uploadMetadata = {
            ...metadata,
            'Content-Type': file.type || 'binary/octet-stream'
          }

          client.putObject(bucket, objectName, Buffer.from(buffer), file.size, uploadMetadata, err => {
            if (err) {
              return reject(err)
            }
            return resolve(objectName)
          })
        }).catch(reject)
      })
    },
    clearPresignedUrl () {
      ++this.presignedUrlGeneration
      this.presignedUrlPreview = ''
      this.presignedUrlCopyFailed = false
      this.presignedUrlLoading = false
    },
    closeObjectDetails (restoreFocus = true) {
      this.clearPresignedUrl()
      this.showObjectDetails = false
      this.$nextTick(() => { if (restoreFocus && this.objectDetailsTrigger?.isConnected) this.objectDetailsTrigger.focus() })
    },
    showObjectDescription (record, event) {
      if (!record?.name || record.prefix || this.objectListScopeChanged) return
      this.clearPresignedUrl()
      this.objectDetailsTrigger = event?.currentTarget
      this.record = {
        ...record,
        url: this.resource.url + '/' + record.name
      }
      this.presignedUrlExpiryValue = 1
      this.presignedUrlExpiryUnit = 'hours'
      this.presignedUrlLoading = false
      this.showObjectDetails = true
    },
    isPublicBucket () {
      return String(this.resource?.policy || '').toLowerCase() === 'public'
    },
    openObjectUrl () {
      if (this.record?.url) {
        window.open(this.record.url, '_blank', 'noopener')
      }
    },
    getPresignedUrlExpirySeconds () {
      const value = Number(this.presignedUrlExpiryValue)
      if (!Number.isFinite(value) || value <= 0) {
        return 0
      }
      const multipliers = {
        seconds: 1,
        minutes: 60,
        hours: 60 * 60,
        days: 24 * 60 * 60
      }
      return Math.floor(value * (multipliers[this.presignedUrlExpiryUnit] || multipliers.hours))
    },
    getPresignedUrlExpiryMaxValue () {
      const maxExpirySeconds = 7 * 24 * 60 * 60
      const dividers = {
        seconds: 1,
        minutes: 60,
        hours: 60 * 60,
        days: 24 * 60 * 60
      }
      return Math.floor(maxExpirySeconds / (dividers[this.presignedUrlExpiryUnit] || dividers.hours))
    },
    generatePresignedUrl () {
      const expirySeconds = this.getPresignedUrlExpirySeconds()
      if (!this.client || !this.showObjectDetails || expirySeconds <= 0 || expirySeconds > 7 * 24 * 60 * 60) return
      this.clearPresignedUrl()
      const generation = this.presignedUrlGeneration
      const client = this.client
      const context = this.objectStoreContextKey
      const connection = this.objectStoreConnectionKey
      const isCurrent = () => generation === this.presignedUrlGeneration && this.showObjectDetails &&
        client === this.client && context === this.objectStoreContextKey && connection === this.objectStoreConnectionKey
      this.presignedUrlLoading = true
      client.presignedGetObject(this.resource.name, this.record.name, expirySeconds, async (err, presignedUrl) => {
        if (!isCurrent()) return
        this.presignedUrlLoading = false
        if (err) {
          return this.$notification.error({
            message: this.$t('error.execute.api.failed'),
            description: this.$t('message.objectstore.share.failed')
          })
        }
        this.presignedUrlPreview = presignedUrl
        try {
          await this.$copyText(presignedUrl)
          if (isCurrent()) this.$message.success({ content: this.$t('label.copied.clipboard') })
        } catch (_) {
          if (isCurrent()) this.presignedUrlCopyFailed = true
        }
      })
    },
    updateMetadata () {
      ++this.objectMetadataRevision
      this.client.copyObject(
        new CopySourceOptions({ Bucket: this.resource.name, Object: this.record.name }),
        new CopyDestinationOptions({ Bucket: this.resource.name, Object: this.record.name, MetadataDirective: 'REPLACE', UserMetadata: this.record.metadata }),
        err => {
          if (err) {
            this.$notification.error({
              message: this.$t('error.execute.api.failed'),
              description: err.message
            })
          }
          this.$notification.success({
            message: this.$t('label.metadata'),
            description: this.$t('message.objectstore.metadata.updated')
          })
          this.listObjects()
        })
    }
  }
}
</script>

<style scoped>
.object-store-root-path {
  margin-left: 4px;
}

.object-store-usage-row {
  margin-bottom: 12px;
}

.object-store-usage-label {
  color: var(--ui-text-secondary);
  font-weight: 600;
  margin-right: 6px;
}

.object-store-toolbar { display: flex; flex-wrap: wrap; align-items: flex-start; gap: 12px; min-width: 0; }
.object-store-actions { display: flex; flex-wrap: wrap; gap: 8px; min-width: 0; }
.object-store-toolbar .object-store-search { flex: 1 1 200px; max-width: 280px; min-width: 0; margin-left: auto; }
.object-store-link { border: 0; background: transparent; color: var(--ui-link); padding: 0; font: inherit; text-align: left; cursor: pointer; overflow-wrap: anywhere; }
.object-store-link:hover { text-decoration: underline; }
.object-store-link:focus-visible { outline: 2px solid var(--ui-focus); outline-offset: 2px; border-radius: 2px; }
.breadcrumb-card :deep(.ant-card-body) { min-width: 0; }
.breadcrumb-card :deep(.ant-breadcrumb) { overflow-wrap: anywhere; }
@media (max-width: 768px) { .object-store-toolbar .object-store-search { width: 100%; max-width: 100%; flex-basis: 100%; } }

:deep(.ant-upload-list-picture .ant-upload-list-item-done) {
  border-color: var(--ui-link);
}

.object-store-upload-status {
  background: var(--ui-bg-elevated);
  border: 1px solid var(--ui-border);
  border-radius: 4px;
  margin-top: 16px;
  padding: 10px 12px;
}

.object-store-upload-status-title {
  font-weight: 600;
  margin-bottom: 4px;
}

.object-store-upload-status-summary,
.object-store-upload-current {
  color: var(--ui-text-secondary);
  font-size: 14px;
}

.object-store-upload-current {
  margin-top: 8px;
}

.object-store-upload-current-name {
  color: var(--ui-text-primary);
  margin-top: 2px;
  word-break: break-all;
}

.object-store-presigned-url-row {
  margin-top: 8px;
}

.object-store-presigned-url-controls {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
  gap: 6px;
  margin: 6px 0 8px;
  width: 100%;
}

.object-store-presigned-url-controls :deep(.ant-input-number) {
  width: 100%;
}

.object-store-presigned-url-controls :deep(.ant-select) {
  width: 100%;
}

.object-store-presigned-url-description {
  color: var(--ui-text-muted);
  font-size: 12px;
  margin-top: 2px;
}

.object-store-share-preview {
  margin-top: 12px;
}
.object-store-presigned-url-action {
  margin-bottom: 8px;
  width: 100%;
}

.object-store-presigned-url-action :deep(.ant-btn) {
  width: 100%;
}

.object-store-url-controls {
  display: flex;
  justify-content: flex-start;
  margin-top: 6px;
  width: 100%;
}

.object-store-url-controls :deep(.ant-btn) {
  width: 100%;
}

.object-store-detail-row {
  margin-bottom: 8px;
}

.object-store-detail-value {
  color: var(--ui-text-primary);
  margin-top: 2px;
  padding-left: 12px;
  text-align: left;
  word-break: break-all;
}
</style>

<style lang="scss" scoped>
.bucket-usage-progress { color: var(--ui-text-primary); margin-top: 8px; }
.bucket-usage-error { margin-top: 12px; }
.bucket-usage-error p { overflow-wrap: anywhere; }
</style>
