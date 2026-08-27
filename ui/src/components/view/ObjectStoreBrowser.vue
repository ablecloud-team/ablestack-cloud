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
  <a-modal
    :visible="showCreateFolderModal"
    :closable="!createFolderLoading"
    :destroyOnClose="true"
    title="폴더 생성"
    :maskClosable="false"
    :cancelText="$t('label.cancel')"
    @cancel="closeCreateFolderModal"
    okText="생성"
    :confirmLoading="createFolderLoading"
    :okButtonProps="{ disabled: createFolderLoading || !createFolderName }"
    :cancelButtonProps="{ disabled: createFolderLoading }"
    @ok="createFolder()"
    centered
    >
    <tooltip-label bold title="폴더명" tooltip="현재 위치에 생성할 폴더 이름을 입력하세요."/>
    <br/>
    <a-input
      v-model:value="createFolderName"
      placeholder="예: aaa"
      :disabled="createFolderLoading"
      @pressEnter="createFolder()"/>
  </a-modal>

  <a-modal
    :visible="showUploadModal"
    :closable="!uploadLoading"
    :destroyOnClose="true"
    :title="$t('label.upload')"
    :maskClosable="false"
    :cancelText="$t('label.cancel')"
    @cancel="closeUploadModal"
    :okText="$t('label.upload')"
    :confirmLoading="uploadLoading"
    :okButtonProps="{ disabled: uploadLoading || uploadFileList.length === 0 }"
    :cancelButtonProps="{ disabled: uploadLoading }"
    @ok="uploadFiles()"
    centered
    >
    <a-upload-dragger
      :multiple="true"
      :fileList="uploadFileList"
      listType="picture"
      :disabled="uploadLoading"
      :beforeUpload="beforeUpload"
      @remove="removeUploadFile">
      <p class="ant-upload-drag-icon">
        <cloud-upload-outlined />
      </p>
      <p class="ant-upload-text">
        {{ $t('label.volume.volumefileupload.description') }}
      </p>
    </a-upload-dragger>
    <div v-if="uploadLoading || uploadTotalCount > 0" class="object-store-upload-status">
      <div class="object-store-upload-status-title">
        {{ uploadLoading ? '업로드 중' : '업로드 완료' }}
      </div>
      <div class="object-store-upload-status-summary">
        전체 {{ uploadTotalCount }}개 / 완료 {{ uploadCompletedCount }}개 / 실패 {{ uploadFailedCount }}개 / 대기 {{ uploadPendingCount }}개
      </div>
      <div v-if="uploadCurrentFileName" class="object-store-upload-current">
        <div>현재 처리 중:</div>
        <div class="object-store-upload-current-name">{{ uploadCurrentFileName }}</div>
      </div>
    </div>
    <a-divider dashed/>
    <tooltip-label bold :title="$t('label.upload.path')" :tooltip="$t('label.upload.description')"/>
    <br/>
    <a-input
      v-model:value="uploadDirectory"
      :placeholder="$t('label.upload.description')"
      :disabled="uploadLoading"
      enter-button/>
    <a-divider dashed/>
    <tooltip-label bold :title="$t('label.metadata')" :tooltip="$t('label.metadata.upload.description')"/>
    <KeyValuePairInput :pairs="uploadMetaData" @update-pairs="(pairs) => uploadMetaData = pairs" />
  </a-modal>

  <a-drawer
    :visible="showObjectDetails"
    :closable="true"
    :maskClosable="true"
    @close="() => showObjectDetails = false"
    :title="record.name"
    >
    <div>
      <a-row class="object-store-detail-row">
        <a-col :span="24">
          <tooltip-label :title="$t('label.name')" bold/>
        </a-col>
        <a-col :span="24" class="object-store-detail-value">
          {{ record.name.split('/').pop() }}
        </a-col>
      </a-row>
      <a-row class="object-store-detail-row">
        <a-col :span="24">
          <tooltip-label :title="$t('label.size')" bold/>
        </a-col>
        <a-col :span="24" class="object-store-detail-value">
          {{ convertBytes(record.size) }}
        </a-col>
      </a-row>
      <a-row class="object-store-detail-row">
        <a-col :span="24">
          <tooltip-label :title="$t('label.last.updated')" bold/>
        </a-col>
        <a-col :span="24" class="object-store-detail-value">
          {{ $toLocaleDate(record.lastModified) }}
        </a-col>
      </a-row>
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
              :min="1"
              :max="getPresignedUrlExpiryMaxValue()"
              :precision="0"
              :disabled="presignedUrlLoading"/>
            <a-select
              v-model:value="presignedUrlExpiryUnit"
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
        </a-col>
      </a-row>
        <a-divider>
          <tooltip-label :title="$t('label.metadata')" :tooltip="$t('label.metadata.description')"/>
        </a-divider>
        <template
          v-for="(value,key) in record.metadata"
          :key="key"
          >
          <a-row class="object-store-detail-row">
            <a-col :span="24">
              <tooltip-label :title="key" bold />
            </a-col>
            <a-col :span="24" class="object-store-detail-value">
              {{ value }}
            </a-col>
          </a-row>
        </template>
    </div>
  </a-drawer>

  <div>
    <a-card class="breadcrumb-card">
      <a-row>
        <a-breadcrumb>
          <a-breadcrumb-item>
            <a @click="openDir('')">
              <HomeOutlined />
              <span v-if="getObjectStorePathRoutes().length === 0" class="object-store-root-path">/</span>
            </a>
          </a-breadcrumb-item>
          <a-breadcrumb-item
            v-for="route in getObjectStorePathRoutes()"
            :key="route.path">
            <a @click="openDir(route.path)">
              {{ route.breadcrumbName }}
            </a>
          </a-breadcrumb-item>
        </a-breadcrumb>
      </a-row>
      <a-divider/>
      <a-row class="object-store-usage-row" :gutter="[16, 8]">
        <a-col>
          <span class="object-store-usage-label">사용량</span>
          <span>{{ convertKB(bucketUsageSize || 0) }}</span>
        </a-col>
        <a-col v-if="resource.quota">
          <span class="object-store-usage-label">총용량</span>
          <span>{{ resource.quota }} GiB</span>
        </a-col>
      </a-row>
      <a-row class="object-store-toolbar" :gutter="[10,10]" :wrap="false" align="middle">
        <a-col class="object-store-search-col" flex="auto">
          <a-input-search
            allowClear
            size="medium"
            v-model:value="searchPrefix"
            :placeholder="$t('label.objectstore.search')"
            :loading="loading"
            @search="listObjects()"
            :enter-button="$t('label.search')"/>
        </a-col>
        <a-col flex="none">
          <a-button
            :loading="loading"
            style="margin-bottom: 5px"
            shape="round"
            size="medium"
            @click="listObjects()">
            <reload-outlined />
            {{ $t('label.refresh') }}
          </a-button>
        </a-col>
        <a-col flex="none">
          <a-button
            :loading="loading"
            style="margin-bottom: 5px"
            shape="round"
            size="medium"
            type="primary"
            @click="openCreateFolderModal">
            <folder-add-outlined />
            폴더 생성
          </a-button>
        </a-col>
        <a-col flex="none">
          <a-button
            :loading="loading"
            style="margin-bottom: 5px"
            shape="round"
            size="medium"
            type="primary"
            @click="openUploadModal">
            <upload-outlined />
            {{ $t('label.upload') }}
          </a-button>
        </a-col>
        <a-col flex="none">
          <a-button
            v-if="selectedRows.length > 0"
            :loading="loading"
            style="margin-bottom: 5px"
            type="primary"
            shape="round"
            size="medium"
            danger
            @click="removeObjects()">
            <delete-outlined />
            {{ $t('label.delete') }}
          </a-button>
        </a-col>
      </a-row>
    </a-card>

    <div>
      <a-table
        :columns="columns"
        :row-key="record => record"
        :data-source="records"
        :loading="loading"
        :size="'small'"
        :pagination="{ current: page, pageSize: pageSize, total: total, showSizeChanger: false }"
        :row-selection="{ selectedRowsKeys: selectedRows, onChange: onSelectChange }"
        @change="handleTableChange">
        <template #bodyCell="{ column, record }">
          <template v-if="column.key == 'name'">
            <template v-if="record.name === undefined && record.prefix">
              <a @click="openDir(record.prefix)">
                <folder-outlined /> {{ record.prefix.replace(this.browserPath, '').replace('/', '') }}
              </a>
            </template>
            <template v-else>
              <a @click="showObjectDescription(record)">
                {{ record.name.split('/').pop() }}
              </a>
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
      </a-table>
    </div>
  </div>

</template>

<script>
import * as Minio from 'minio'
import { api } from '@/api'
import { genericCompare } from '@/utils/sort.js'
import InfoCard from '@/components/view/InfoCard'
import TooltipButton from '@/components/widgets/TooltipButton'
import TooltipLabel from '@/components/widgets/TooltipLabel'
import KeyValuePairInput from '@/components/KeyValuePairInput'

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
  name: 'ObjectStoreBrowser',
  components: {
    InfoCard,
    TooltipButton,
    TooltipLabel,
    KeyValuePairInput
  },
  emits: ['change-resource'],
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
    var columns = [
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
    return {
      client: null,
      loading: false,
      records: [],
      browserPath: normalizeObjectStorePath(this.$route.query.browserPath),
      pageSize,
      page: 1,
      pageStartAfterMap: { 1: '' },
      total: 0,
      columns: columns,
      selectedRows: [],
      searchPrefix: '',
      showUploadModal: false,
      uploadFileList: [],
      uploadDirectory: normalizeObjectStorePath(this.$route.query.browserPath),
      uploadMetaData: {},
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
      presignedUrlExpiryValue: 1,
      presignedUrlExpiryUnit: 'hours',
      presignedUrlLoading: false,
      record: {},
      showObjectDetails: false,
      fetching: false
    }
  },
  computed: {
    uploadPendingCount () {
      const uploadingCount = this.uploadLoading && this.uploadCurrentFileName ? 1 : 0
      return Math.max(this.uploadTotalCount - this.uploadCompletedCount - this.uploadFailedCount - uploadingCount, 0)
    }
  },
  watch: {
    'resource.size' (size) {
      this.bucketUsageSize = size
    }
  },
  created () {
    this.fetchData()
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
        this.createFolderLoading = false
        if (err) {
          this.loading = false
          return this.$notification.error({
            message: this.$t('error.execute.api.failed'),
            description: err.message
          })
        }
        this.$notification.success({
          message: '폴더 생성',
          description: `${objectName} 생성 완료`
        })
        this.showCreateFolderModal = false
        this.createFolderName = ''
        this.listObjects()
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
      this.uploadMetaData = {}
      this.resetUploadProgress()
    },
    handleTableChange (pagination, filters, sorter) {
      if (this.page !== pagination.current) {
        this.page = pagination.current
      }
    },
    fetchData () {
      this.loading = true
      this.records = []
      this.$router.replace(
        {
          query: {
            ...this.$route.query,
            browserPath: this.browserPath
          }
        }
      )
      if (!this.client) {
        this.initMinioClient()
      } else {
        this.listObjects()
      }
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
      if (this.fetching) {
        return
      }
      this.fetching = true
      this.records = []
      const currentPath = normalizeObjectStorePath(this.browserPath)
      var stream = this.client.extensions.listObjectsV2WithMetadata(this.resource.name, currentPath + this.searchPrefix, false, '')
      stream.on('data', obj => {
        if (this.isCurrentDirectoryMarker(obj, currentPath)) {
          return
        }
        this.records.push(obj)
      })
      stream.on('end', obj => {
        this.total = this.records.length
        this.loading = false
        this.fetching = false
      })
      stream.on('error', err => {
        console.log(err)
        this.loading = false
        this.fetching = false
      })
    },
    isCurrentDirectoryMarker (obj, currentPath) {
      if (!currentPath || obj.prefix || !obj.name) {
        return false
      }
      return obj.name === currentPath && Number(obj.size || 0) === 0
    },
    async removeObjects () {
      this.loading = true
      this.page = 1
      this.pageStartAfterMap = { 1: '' }
      const objectsToDelete = this.selectedRows.filter((row) => row.name).map((row) => row.name)
      const directoriesToDelete = this.selectedRows.filter((row) => row.prefix).map((row) => row.prefix)
      this.selectedRows = []

      try {
        let deletedCount = 0
        deletedCount += await this.removeObjectsInBatches(objectsToDelete)
        for (const directory of directoriesToDelete) {
          deletedCount += await this.removeDirectoryObjects(directory)
        }

        this.$notification.success({
          message: this.$t('label.delete'),
          description: this.$t('message.success.remove.objectstore.objects.count', { count: deletedCount })
        })
        await this.syncBucketUsage()
        this.listObjects()
      } catch (err) {
        this.loading = false
        this.$notification.error({
          message: this.$t('error.execute.api.failed'),
          description: err.message
        })
      }
    },
    async removeObjectsInBatches (objectNames) {
      let deletedCount = 0
      for (let i = 0; i < objectNames.length; i += deleteBatchSize) {
        const objectBatch = objectNames.slice(i, i + deleteBatchSize)
        await this.removeObjectBatch(objectBatch)
        deletedCount += objectBatch.length
      }
      return deletedCount
    },
    removeObjectBatch (objectNames) {
      return new Promise((resolve, reject) => {
        if (objectNames.length === 0) {
          resolve()
          return
        }
        this.client.removeObjects(this.resource.name, objectNames, err => {
          if (err) {
            reject(err)
            return
          }
          resolve()
        })
      })
    },
    removeDirectoryObjects (directory) {
      return new Promise((resolve, reject) => {
        let deletedCount = 0
        let objectBatch = []
        let deleteChain = Promise.resolve()
        const stream = this.client.listObjectsV2(this.resource.name, directory, true, '')
        const flushBatch = () => {
          if (objectBatch.length === 0) {
            return
          }
          const batch = objectBatch
          objectBatch = []
          if (typeof stream?.pause === 'function') {
            stream.pause()
          }
          deleteChain = deleteChain.then(() => this.removeObjectBatch(batch)).then(() => {
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
    async syncBucketUsage () {
      if (!this.resource?.id) {
        return
      }
      try {
        const json = await api('syncBucketUsage', { id: this.resource.id })
        const response = json?.syncbucketusageresponse || {}
        const size = response.size ?? response.bucket?.size
        if (size !== undefined && size !== null) {
          this.bucketUsageSize = size
          this.$emit('change-resource', { ...this.resource, size })
        }
      } catch (error) {
        console.warn('Failed to synchronize bucket usage', error)
      }
    },
    initMinioClient () {
      if (!this.client) {
        const url = /https?:\/\/([^/]+)\/?/.exec(this.resource.url.split(this.resource.name)[0])[1]
        const isHttps = /^https/.test(this.resource.url)
        this.client = new Minio.Client({
          endPoint: url.split(':')[0],
          port: url.split(':').length > 1 ? parseInt(url.split(':')[1]) : isHttps ? 443 : 80,
          useSSL: isHttps,
          accessKey: this.resource.accesskey,
          secretKey: this.resource.usersecretkey
        })
        this.listObjects()
      }
    },
    onSelectChange (selectedRow) {
      this.selectedRows = selectedRow
    },
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
      const index = this.uploadFileList.indexOf(file)
      if (index < 0) {
        return true
      }
      const newFileList = this.uploadFileList.slice()
      newFileList.splice(index, 1)
      this.uploadFileList = newFileList
      return true
    },
    updateUploadFileStatus (file, status, error) {
      const index = this.uploadFileList.findIndex(uploadFile => uploadFile === file || uploadFile.uid === file.uid)
      if (index < 0) {
        return
      }
      const newFileList = this.uploadFileList.slice()
      newFileList[index].status = status
      newFileList[index].error = error
      this.uploadFileList = newFileList
    },
    async uploadFiles () {
      if (this.uploadLoading) {
        return
      }
      const files = [...this.uploadFileList]
      if (files.length === 0) {
        return
      }
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
      const metadata = { ...this.uploadMetaData }
      try {
        for (const file of files) {
          const objectName = this.uploadDirectory + file.name
          this.uploadCurrentFileName = file.name
          this.updateUploadFileStatus(file, 'uploading')
          try {
            await this.asyncUploadFile(file, objectName, metadata)
            this.updateUploadFileStatus(file, 'done')
            this.uploadCompletedCount++
          } catch (error) {
            this.updateUploadFileStatus(file, 'error', error)
            this.uploadFailedCount++
            this.uploadFailedFiles.push({
              name: file.name,
              message: error?.message || String(error)
            })
          }
        }
        this.uploadCurrentFileName = ''
        if (this.uploadFailedCount > 0) {
          const failedNames = this.uploadFailedFiles.slice(0, 3).map(file => file.name).join(', ')
          const extraFailedCount = Math.max(this.uploadFailedFiles.length - 3, 0)
          const failedDescription = extraFailedCount > 0 ? `${failedNames} 외 ${extraFailedCount}개` : failedNames
          this.$notification.error({
            message: this.$t('message.upload.failed'),
            description: `전체 ${files.length}개 중 성공 ${this.uploadCompletedCount}개, 실패 ${this.uploadFailedCount}개. 실패 파일: ${failedDescription}`
          })
          if (this.uploadCompletedCount > 0) {
            await this.syncBucketUsage()
            this.listObjects()
          }
          return
        }
        this.$notification.success({
          message: this.$t('message.success.upload'),
          description: `전체 ${files.length}개 파일 업로드 완료`
        })
        this.showUploadModal = false
        this.resetUploadForm()
        await this.syncBucketUsage()
        this.listObjects()
      } finally {
        this.uploadLoading = false
        this.uploadCurrentFileName = ''
        if (this.uploadFailedCount > 0) {
          this.loading = false
        }
      }
    },
    asyncUploadFile (file, objectName, metadata) {
      return new Promise((resolve, reject) => {
        file.arrayBuffer().then((buffer) => {
          this.client.putObject(this.resource.name, objectName, Buffer.from(buffer), file.size, metadata, err => {
            if (err) {
              return reject(err)
            }
            return resolve(objectName)
          })
        }).catch(reject)
      })
    },
    showObjectDescription (record) {
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
      if (expirySeconds <= 0) {
        return
      }
      if (expirySeconds > 7 * 24 * 60 * 60) {
        return this.$notification.error({
          message: this.$t('error.execute.api.failed'),
          description: 'Presigned URL expiry cannot exceed 7 days.'
        })
      }
      this.presignedUrlLoading = true
      this.client.presignedGetObject(this.resource.name, this.record.name, expirySeconds, (err, presignedUrl) => {
        this.presignedUrlLoading = false
        if (err) {
          return this.$notification.error({
            message: this.$t('error.execute.api.failed'),
            description: err.message
          })
        }
        this.$copyText(presignedUrl)
        this.$message.success({
          content: this.$t('label.copied.clipboard')
        })
      })
    },
    updateMetadata () {
      this.client.copyObject(
        new Minio.CopySourceOptions({ Bucket: this.resource.name, Object: this.record.name }),
        new Minio.CopyDestinationOptions({ Bucket: this.resource.name, Object: this.record.name, MetadataDirective: 'REPLACE', UserMetadata: this.record.metadata }),
        err => {
          if (err) {
            this.$notification.error({
              message: this.$t('error.execute.api.failed'),
              description: err.message
            })
          }
          this.$notification.success({
            message: this.$t('label.metadata'),
            description: this.$t('message.update.success')
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
  color: rgba(0, 0, 0, 0.65);
  font-weight: 600;
  margin-right: 6px;
}

.object-store-toolbar {
  overflow-x: auto;
}

.object-store-search-col {
  min-width: 240px;
}

:deep(.ant-upload-list-picture .ant-upload-list-item-done) {
  border-color: #1890ff;
}

.object-store-upload-status {
  background: #fafafa;
  border: 1px solid #f0f0f0;
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
  color: rgba(0, 0, 0, 0.65);
  font-size: 12px;
}

.object-store-upload-current {
  margin-top: 8px;
}

.object-store-upload-current-name {
  color: rgba(0, 0, 0, 0.85);
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
  color: rgba(0, 0, 0, 0.45);
  font-size: 12px;
  margin-top: 2px;
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
  color: rgba(0, 0, 0, 0.85);
  margin-top: 2px;
  padding-left: 12px;
  text-align: left;
  word-break: break-all;
}
</style>
