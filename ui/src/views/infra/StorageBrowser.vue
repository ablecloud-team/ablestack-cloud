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
    <p v-if="listRefreshFailed" :role="hasBrowserData ? 'status' : 'alert'">
      {{ $t(browserErrorLabel) }}
    </p>
    <a-card class="breadcrumb-card">
      <a-row>
        <a-col :span="24" style="padding-left: 12px">
          <a-breadcrumb :routes="getRoutes()" class="storage-browser-path">
            <template #itemRender="{ route }">
              <a v-if="route.breadcrumbName === 'root'" href="#" :aria-label="$t('label.home')" @click.prevent="openDir('')">
                <home-outlined />
              </a>
              <a v-else href="#" @click.prevent="openDir(route.path)">{{ route.breadcrumbName }}</a>
            </template>
          </a-breadcrumb>
        </a-col>
        <a-divider />
        <a-col :span="24" class="storage-browser-toolbar">
          <a-input-search
            v-if="isPrimaryStorage"
            v-model:value="keywordDraft"
            class="storage-browser-search"
            allow-clear
            :placeholder="$t('label.objectstore.search')"
            :loading="loading"
            :enter-button="$t('label.search')"
            @search="searchPrimaryStore" />
          <a-button v-if="canImportVolume" ref="importVolumeButton" class="storage-browser-action" type="primary" @click="openVolumeImport('')">
            {{ $t('label.creation.source.import') }}
          </a-button>
          <a-tooltip placement="bottom" :title="$t('label.refresh')">
            <a-button
              ref="browserRefreshButton"
              class="storage-browser-action"
              :loading="loading || listRefreshing > 0"
              shape="round"
              @click="fetchData">
              <template #icon><reload-outlined /></template>
              {{ $t('label.refresh') }}
            </a-button>
          </a-tooltip>
          <a-button
            v-if="canCreateRbdImage"
            ref="createImageButton"
            class="storage-browser-action"
            type="primary"
            shape="round"
            :loading="loading"
            @click="showAddVolModal">
            <template #icon><plus-outlined /></template>
            {{ $t('label.create.rbd.image') }}
          </a-button>
        </a-col>
      </a-row>
    </a-card>

    <mold-dialog
      v-if="canCreateRbdImage"
      :visible="showAddVolumeModal"
      :title="$t('label.create.rbd.image')"
      size="md"
      form-actions
      focus-guard
      initial-focus="#primary-rbd-image-name"
      :after-close="restoreBrowserFocus"
      @cancel="closeModals">
      <create-rbd-image v-if="showAddVolumeModal" :resource="resource" @close-action="closeModals" />
    </mold-dialog>
    <mold-dialog
      v-if="canImportVolume"
      :visible="showImportVolumeModal"
      :title="$t('label.creation.source.import')"
      size="md"
      form-actions
      focus-guard
      initial-focus="#import-volume-path"
      :after-close="restoreBrowserFocus"
      @cancel="showImportVolumeModal = false">
      <import-storage-volume v-if="showImportVolumeModal" :key="importVolumePath" :resource="resource" :initial-path="importVolumePath" @close-action="showImportVolumeModal = false" />
    </mold-dialog>
    <mold-dialog
      v-if="canDeleteRbdImage"
      :visible="!!rbdDeleteTarget"
      :title="$t('label.delete.rbd.image')"
      size="md"
      focus-guard
      initial-focus=".rbd-delete-cancel"
      :closable="!deletingRbd"
      :keyboard="!deletingRbd"
      :after-close="restoreBrowserFocus"
      @cancel="cancelRbdDelete">
      <p><strong>{{ rbdDeleteTarget?.name }}</strong></p>
      <p>{{ $t('message.primary.storage.rbd.delete.confirm') }}</p>
      <template #footer>
        <a-button class="rbd-delete-cancel" :disabled="deletingRbd" @click="cancelRbdDelete">{{ $t('label.cancel') }}</a-button>
        <a-button danger type="primary" :loading="deletingRbd" @click="deleteRbdImage(rbdDeleteTarget)">{{ $t('label.delete') }}</a-button>
      </template>
    </mold-dialog>

    <a-modal
      :title="$t('message.data.migration')"
      :visible="showMigrateModal"
      :maskClosable="true"
      :confirmLoading="migrateModalLoading"
      @cancel="closeMigrationModal"
      :footer="null"
      width="50%"
      :okText="$t('label.ok')"
      :cancelText="$t('label.cancel')">
      <div>
        <migrate-image-store-resource
          v-if="showMigrateModal"
          :sourceImageStore="resource"
          :templateIdsToMigrate="templateIdsToMigrate"
          :snapshotIdsToMigrate="snapshotIdsToMigrate"
          @refresh-data="fetchData"
          @close-action="closeMigrationModal"
        />
      </div>
    </a-modal>

    <div v-if="!listRefreshFailed || hasBrowserData">
      <detail-tab-table
        :columns="visibleColumns"
        :loading="resourceType === 'ImageStore' && loading"
        :scroll="primaryTableScroll"
        :row-key="record => record.name"
        :data-source="dataSource"
        :pagination="false" >
        <template #bodyCell="{ column, record }">
          <template v-if="column.key == 'name'">
            <template v-if="record.isdirectory">
              <a-button v-if="isPrimaryStorage" class="storage-browser-directory" type="link" @click="openDir(browserPath + record.name + '/')">
                <folder-outlined /> {{ record.name }}
              </a-button>
              <a v-else href="#" @click.prevent="openImageFolder(record)">
                <folder-outlined /> {{ record.name }}
              </a>
            </template>
            <template v-else-if="resourceType === 'ImageStore'">
              <component
                :is="canDownloadImage ? 'a' : 'span'"
                :href="canDownloadImage ? '#' : undefined"
                @click.prevent="downloadFile(record)">
                <template v-if="record.snapshotid">
                  <build-outlined/>
                </template>
                <template v-else-if="record.volumeid">
                    <hdd-outlined/>
                </template>
                <template v-else-if="record.templateid">
                    <usb-outlined v-if="record.format === 'ISO'"/>
                    <save-outlined v-else />
                </template>
                {{ record.name }}
              </component>
            </template>
            <template v-else>
              <template v-if="record.snapshotid">
                  <build-outlined/>
                </template>
                <template v-else-if="record.volumeid">
                    <hdd-outlined/>
                </template>
                <template v-else-if="record.templateid">
                    <usb-outlined v-if="record.format === 'ISO'"/>
                    <save-outlined v-else />
                </template>
                {{ record.name }}
            </template>
          </template>
          <template v-if="column.key == 'size'">
            <template v-if="!record.isdirectory">
            {{ convertBytes(record.size) }}
            </template>
          </template>
          <template v-if="column.key == 'lastupdated'">
            {{ $toLocaleDate(record.lastupdated) }}
          </template>
          <template v-if="column.key == 'associatedResource'">
            <template v-if="record.snapshotid">
              <router-link :to="{ path: '/snapshot/' + record.snapshotid }" target='_blank' >
                {{ record.snapshotname }}
              </router-link>
            </template>
            <template v-else-if="record.volumeid">
              <router-link :to="{ path: '/volume/' + record.volumeid }" target='_blank' >
                {{ record.volumename }}
              </router-link>
            </template>
            <template v-else-if="record.templateid">
              <router-link v-if="record.format === 'ISO'" :to="{ path: '/iso/' + record.templateid }" target='_blank' >
                {{ record.templatename }}
              </router-link>
              <router-link v-else :to="{ path: '/template/' + record.templateid }" target='_blank'>
                {{ record.templatename }}
              </router-link>
            </template>
            <template v-else>
              {{ $t('label.unknown') }}
            </template>
          </template>
          <template v-else-if="column.key === 'actions' && canMigrateImage && (record.templateid || record.snapshotid)">
              <tooltip-button
              tooltipPlacement="top"
              :tooltip="$t('label.migrate.data.from.image.store')"
              icon="arrows-alt-outlined"
              @onClick="openMigrationModal(record)" />
            </template>
            <template v-else-if="column.key === 'deleteactions' && eligibleRbdObject(record)">
              <a-space>
                <a-tooltip v-if="canImportVolume" :title="$t('label.action.create.volume')">
                  <a-button
                    type="primary"
                    shape="circle"
                    :aria-label="$t('label.action.create.volume')"
                    :data-rbd-target="record.name"
                    data-rbd-action="import"
                    :loading="loading"
                    @click="openVolumeImport(browserPath + record.name, record.name)">
                    <template #icon><plus-outlined /></template>
                  </a-button>
                </a-tooltip>
                <a-tooltip v-if="canDeleteRbdImage" :title="$t('label.delete')">
                  <a-button
                    type="primary"
                    shape="circle"
                    danger
                    :aria-label="$t('label.delete')"
                    :data-rbd-target="record.name"
                    data-rbd-action="delete"
                    :loading="deletingRbd && rbdDeleteTarget?.name === record.name"
                    @click="openRbdDelete(record)">
                    <template #icon><delete-outlined /></template>
                  </a-button>
                </a-tooltip>
              </a-space>
            </template>
          </template>
      </detail-tab-table>
      <detail-tab-pagination
        class="row-element"
        style="margin-top: 10px"
        size="small"
        :current="page"
        :pageSize="pageSize"
        :total="total"
        :showTotal="total => this.$localStorage.get('LOCALE') == 'ko_KR' ?
          `${$t('label.total')} ${total} ${$t('label.items')} ${$t('label.of')} ${Math.min(total, 1+((page-1)*pageSize))}-${Math.min(page*pageSize, total)} ${$t('label.showing')}` :
          `${$t('label.showing')} ${Math.min(total, 1+((page-1)*pageSize))}-${Math.min(page*pageSize, total)} ${$t('label.of')} ${total} ${$t('label.items')}`"
        :pageSizeOptions="pageSizeOptions"
        @change="changePage"
        @showSizeChange="changePage"
        showSizeChanger>
        <template #buildOptionText="props">
          <span>{{ props.value }} / {{ $t('label.page') }}</span>
        </template>
      </detail-tab-pagination>
      <a-modal
        :visible="showAddTypeModal"
        :title="$t('label.volumetype')"
        @cancel="closeModals"
        @ok="createVolume()">
        <div class="title">{{ $t('label.create.type.rbd.image') }}</div>
        <a-form-item ref="volumetype" name="volumetype">
          <a-select v-model:value="volumetype" @change="volumeTypeChange">
            <a-select-option value='ROOT'>{{ $t('label.rootdisk') }}</a-select-option>
            <a-select-option value='DATADISK'>{{ $t('label.data.disk') }}</a-select-option>
          </a-select>
        </a-form-item>
      </a-modal>
    </div>
  </div>

</template>

<script>
import { listRefreshMixin } from '@/utils/listRefreshMixin'

import { getAPI, postAPI } from '@/api'
import { ref, reactive } from 'vue'
import InfoCard from '@/components/view/InfoCard'
import TooltipButton from '@/components/widgets/TooltipButton'
import MoldDialog from '@/components/view/MoldDialog'
import MigrateImageStoreResource from '@/views/storage/MigrateImageStoreResource'
import CreateRbdImage from '@/views/storage/CreateRbdImage'
import ImportStorageVolume from '@/views/storage/ImportStorageVolume'

function positiveInteger (value, fallback) {
  const number = Number(value)
  return Number.isSafeInteger(number) && number > 0 ? number : fallback
}

function browserQuery (query, primary) {
  return {
    browserPath: typeof query.browserPath === 'string' ? query.browserPath : '',
    page: positiveInteger(query.browserPage, 1),
    pageSize: positiveInteger(query.browserPageSize, 10),
    keyword: primary && typeof query.browserKeyword === 'string' ? query.browserKeyword.trim() : ''
  }
}

export default {
  mixins: [listRefreshMixin(['fetchImageStoreObjects', 'fetchPrimaryStoreObjects'], {
    interval: 60000,
    active: vm => vm.$route.query?.tab === 'browser',
    select: vm => vm.resourceType === 'ImageStore' ? ['fetchImageStoreObjects'] : vm.resourceType === 'PrimaryStorage' ? ['fetchPrimaryStoreObjects'] : [],
    // URL normalization must not invalidate a response for the same listing.
    scopeKey: vm => [vm.$route?.path, vm.$store?.state?.user?.token,
      vm.$store?.getters.userInfo?.id, vm.$store?.getters.project?.id,
      vm.resourceType, vm.resource.id, vm.browserPath, vm.page, vm.pageSize, vm.keyword]
  })],
  name: 'StorageBrowser',
  components: {
    InfoCard,
    MigrateImageStoreResource,
    TooltipButton,
    CreateRbdImage,
    ImportStorageVolume,
    MoldDialog
  },
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
        title: this.$t('label.name')
      },
      {
        key: 'size',
        title: this.$t('label.size')
      },
      {
        key: 'lastupdated',
        title: this.$t('label.last.updated')
      },
      {
        key: 'associatedResource',
        title: this.$t('label.associated.resource')
      }
    ]
    if (this.resourceType === 'ImageStore') {
      columns.push({
        key: 'actions',
        title: this.$t('label.actions')
      })
    }
    if (this.resourceType === 'PrimaryStorage' && this.resource.type === 'RBD') {
      columns.push({
        key: 'deleteactions',
        title: this.$t('label.actions')
      })
    }
    const query = this.$route.query
    const browser = browserQuery(query, this.resourceType === 'PrimaryStorage')
    return {
      loading: false,
      dataSource: [],
      ...browser,
      keywordDraft: browser.keyword,
      hasPrimaryData: false,
      hasImageData: false,
      downloadingImage: false,
      deletingRbd: false,
      rbdDeleteTarget: null,
      browserFocus: null,
      total: 0,
      columns: columns,
      migrateModalLoading: false,
      showMigrateModal: false,
      templateIdsToMigrate: [],
      snapshotIdsToMigrate: [],
      showAddVolumeModal: false,
      showImportVolumeModal: false,
      importVolumePath: '',
      showAddTypeModal: false,
      rootSwitch: true,
      dataDiskSwitch: false,
      volumetype: 'ROOT',
      targetName: '',
      targetSize: ''
    }
  },
  computed: {
    isPrimaryStorage () {
      return this.resourceType === 'PrimaryStorage'
    },
    isRbdPool () {
      return this.isPrimaryStorage && this.resource.type === 'RBD'
    },
    canImportVolume () {
      return this.isPrimaryStorage && this.resource.state === 'Up' && this.resource.hypervisor === 'KVM' &&
        ['SharedMountPoint', 'RBD', 'NetworkFilesystem'].includes(this.resource.type) && !!this.$store.getters.apis.importVolume
    },
    canCreateRbdImage () {
      return this.isRbdPool && this.resource.state === 'Up' && !!this.$store.getters.apis.createRbdImage
    },
    canDeleteRbdImage () {
      return this.isRbdPool && this.resource.state === 'Up' && !!this.$store.getters.apis.deleteRbdImage
    },
    canDownloadImage () {
      return this.resourceType === 'ImageStore' && !!this.$store.getters.apis.downloadImageStoreObject
    },
    canMigrateImage () {
      return this.resourceType === 'ImageStore' && !!this.$store.getters.apis.migrateResourceToAnotherSecondaryStorage
    },
    hasBrowserData () { return this.isPrimaryStorage ? this.hasPrimaryData : this.hasImageData },
    browserErrorLabel () {
      if (this.hasBrowserData) return 'message.list.refresh.stale'
      return this.isPrimaryStorage ? 'message.primary.storage.browser.load.failed' : 'message.secondary.storage.browser.load.failed'
    },
    visibleColumns () {
      const columns = this.columns.filter(column => (column.key !== 'deleteactions' || this.canImportVolume || this.canDeleteRbdImage) &&
        (column.key !== 'actions' || this.canMigrateImage))
      if (!this.isPrimaryStorage) return columns
      const widths = { name: 224, size: 92, lastupdated: 144, associatedResource: 148, deleteactions: 112 }
      return columns.map(column => ({ ...column, width: widths[column.key] }))
    },
    primaryTableScroll () {
      return this.isPrimaryStorage ? { x: this.visibleColumns.reduce((total, column) => total + column.width, 0) } : undefined
    },
    pageSizeOptions () {
      var sizes = [20, 50, 100, 200, this.$store.getters.defaultListViewPageSize]
      if (this.device !== 'desktop') {
        sizes.unshift(10)
      }
      return [...new Set(sizes)].sort(function (a, b) {
        return a - b
      }).map(String)
    }
  },
  watch: {
    '$route.query' (query) {
      if (this.$route.params.id !== this.resource.id || query.tab !== 'browser') return
      const next = browserQuery(query, this.isPrimaryStorage)
      if (Object.keys(next).every(key => this[key] === next[key])) return
      Object.assign(this, next, { keywordDraft: next.keyword })
      this.fetchData()
    }
  },
  created () {
    this.initForm()
    this.fetchData()
  },
  methods: {
    eligibleRbdObject (record) {
      return this.isRbdPool && !!record.name && !record.isdirectory && !record.templateid &&
        !record.volumeid && !record.snapshotid && !/MOLD-DR|MOLD-AC|MOLD-HB|ccvm/i.test(record.name)
    },
    openVolumeImport (path, rowName) {
      if (!this.canImportVolume) return
      this.browserFocus = { id: this.resource.id, rowName, action: 'import', ref: 'importVolumeButton' }
      this.importVolumePath = path
      this.showImportVolumeModal = true
    },
    restoreBrowserFocus () {
      const previous = this.browserFocus
      this.browserFocus = null
      if (!previous || previous.id !== this.$route.params?.id || previous.id !== this.resource.id) return
      const target = previous.rowName
        ? Array.from(this.$el?.querySelectorAll('button[data-rbd-action]') || []).find(button =>
          button.dataset.rbdTarget === previous.rowName && button.dataset.rbdAction === previous.action)
        : this.$refs[previous.ref]?.$el
      const anchor = target || this.$refs.browserRefreshButton?.$el
      if (anchor?.isConnected) anchor.focus({ preventScroll: true })
    },
    openRbdDelete (record) {
      if (!this.canDeleteRbdImage || !this.eligibleRbdObject(record)) return
      this.browserFocus = { id: this.resource.id, rowName: record.name, action: 'delete' }
      this.rbdDeleteTarget = record
    },
    cancelRbdDelete () {
      if (!this.deletingRbd) this.rbdDeleteTarget = null
    },
    navigateBrowser (changes) {
      const state = { browserPath: this.browserPath, page: this.page, pageSize: this.pageSize, keyword: this.keyword, ...changes }
      if (Object.keys(state).every(key => this[key] === state[key])) return this.fetchData()
      return this.$router.push({
        query: {
          ...this.$route.query,
          tab: 'browser',
          browserPath: state.browserPath || undefined,
          browserPage: String(state.page),
          browserPageSize: String(state.pageSize),
          browserKeyword: this.isPrimaryStorage ? state.keyword || undefined : undefined
        }
      })
    },
    searchPrimaryStore (keyword) {
      this.keywordDraft = (keyword || '').trim()
      return this.navigateBrowser({ keyword: this.keywordDraft, page: 1 })
    },
    initForm () {
      this.formRef = ref()
      this.form = reactive({
      })
      this.rules = reactive({})
    },
    openMigrationModal (record) {
      if (!this.$store.getters.apis.migrateResourceToAnotherSecondaryStorage) return
      this.snapshotIdsToMigrate = record.snapshotid ? [record.snapshotid] : []
      this.templateIdsToMigrate = !record.snapshotid && record.templateid ? [record.templateid] : []
      this.showMigrateModal = true
    },
    closeMigrationModal () {
      this.showMigrateModal = false
      this.snapshotIdsToMigrate = []
      this.templateIdsToMigrate = []
    },
    changePage (page, pageSize) {
      const size = positiveInteger(pageSize, this.pageSize)
      return this.navigateBrowser({ page: size === this.pageSize ? positiveInteger(page, this.page) : 1, pageSize: size })
    },
    volumeTypeChange (val) {
      this.volumetype = val
    },
    createVolume () {
      getAPI('listDiskOfferings', { listall: true }).then(json => {
        this.offerings = json.listdiskofferingsresponse.diskoffering || []
        this.customDiskOffering = this.offerings.filter(x => x.iscustomized === true)
        getAPI('createVolume', {
          diskofferingid: this.customDiskOffering[0].id,
          size: this.targetSize / (1024 * 1024 * 1024),
          name: this.targetName,
          zoneid: this.resource.zoneid
        }).then(response => {
          this.$pollJob({
            jobId: response.createvolumeresponse.jobid,
            title: this.$t('message.success.create.volume'),
            successMessage: this.$t('message.success.create.volume'),
            successMethod: (result) => {
              getAPI('updateVolume', {
                id: result.jobresult.volume.id,
                path: this.targetName,
                storageid: this.resource.id,
                state: 'Ready',
                type: this.volumetype
              }).then(json => {
              }).catch(error => {
                this.$notifyError(error)
              }).finally(() => {
                this.fetchData()
                this.loading = false
              })
            },
            errorMessage: this.$t('message.create.volume.failed'),
            loadingMessage: this.$t('message.create.volume.processing'),
            catchMessage: this.$t('error.fetching.async.job.result')
          })
        }).catch(error => {
          this.$notifyError(error)
        })
      }).catch(error => {
        this.$notifyError(error)
      })
    },
    async deleteRbdImage (record) {
      if (!record || !this.canDeleteRbdImage || this.deletingRbd) return
      const current = this.dataSource.find(item => item.name === record.name)
      if (!current || !this.eligibleRbdObject(current)) return
      const id = this.resource.id
      this.deletingRbd = true
      try {
        await postAPI('deleteRbdImage', { name: record.name, id }, { preserveOnFailure: true })
        if (this.resource.id === id) {
          await this.fetchPrimaryStoreObjects()
          this.rbdDeleteTarget = null
        }
      } catch (error) {
        this.$notifyError(error)
      } finally {
        this.deletingRbd = false
      }
    },
    async fetchImageStoreObjects () {
      if (!this.$store.getters.apis.listImageStoreObjects || !this.resource.id) return
      const listRequest = this.listRequestToken('fetchImageStoreObjects')
      this.loading = !listRequest.loaded
      if (!listRequest.loaded) {
        this.hasImageData = false
        this.dataSource = []
        this.total = 0
        this.listRefreshFailed = false
      }
      try {
        const json = await getAPI('listImageStoreObjects', {
          path: this.browserPath,
          id: this.resource.id,
          page: this.page,
          pagesize: this.pageSize
        }, { preserveOnFailure: true })
        if (!this.isListRequestCurrent('fetchImageStoreObjects', listRequest)) return
        this.dataSource = json.listimagestoreobjectsresponse.datastoreobject || []
        this.total = json.listimagestoreobjectsresponse.count || 0
        this.hasImageData = true
      } catch {
        if (!this.isListRequestCurrent('fetchImageStoreObjects', listRequest)) return
        listRequest.failed = true
        this.listRefreshFailed = true
      } finally {
        if (this.isListRequestCurrent('fetchImageStoreObjects', listRequest)) this.loading = false
      }
    },
    async fetchPrimaryStoreObjects () {
      if (!this.$store.getters.apis.listStoragePoolObjects || !this.resource.id) return
      const listRequest = this.listRequestToken('fetchPrimaryStoreObjects')
      this.loading = !listRequest.loaded
      if (!listRequest.loaded) {
        this.hasPrimaryData = false
        this.dataSource = []
        this.total = 0
        this.listRefreshFailed = false
      }
      try {
        const json = await getAPI('listStoragePoolObjects', {
          path: this.browserPath,
          id: this.resource.id,
          page: this.page,
          pagesize: this.pageSize,
          keyword: this.keyword
        }, { preserveOnFailure: true })
        if (!this.isListRequestCurrent('fetchPrimaryStoreObjects', listRequest)) return
        this.dataSource = json.liststoragepoolobjectsresponse.datastoreobject || []
        this.total = json.liststoragepoolobjectsresponse.count || 0
        this.hasPrimaryData = true
      } catch {
        if (!this.isListRequestCurrent('fetchPrimaryStoreObjects', listRequest)) return
        listRequest.failed = true
        this.listRefreshFailed = true
      } finally {
        if (this.isListRequestCurrent('fetchPrimaryStoreObjects', listRequest)) this.loading = false
      }
    },
    deleteDetail (index) {
      this.details.splice(index, 1)
      this.runApi()
    },
    showAddVolModal () {
      if (!this.canCreateRbdImage) return
      this.browserFocus = { id: this.resource.id, ref: 'createImageButton' }
      this.showAddVolumeModal = true
    },
    showAddTyModal (name, size) {
      this.showAddTypeModal = true
      this.targetName = name
      this.targetSize = size
    },
    closeModals () {
      this.showAddVolumeModal = false
      this.showAddTypeModal = false
      this.fetchData()
    },
    fetchData () {
      if (this.isPrimaryStorage) return this.fetchPrimaryStoreObjects()
      if (this.resourceType === 'ImageStore') return this.fetchImageStoreObjects()
    },
    getRoutes () {
      let path = ''
      const routeList = [{
        path: path,
        breadcrumbName: 'root'
      }]
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
    openDir (name) {
      return this.navigateBrowser({ browserPath: name, page: 1 })
    },
    openImageFolder (record) {
      if (this.loading || !this.hasImageData || !this.dataSource.includes(record)) return
      return this.openDir(this.browserPath + record.name + '/')
    },
    async downloadFile (record) {
      if (!this.canDownloadImage || this.downloadingImage || this.loading || !this.dataSource.includes(record)) return
      this.downloadingImage = true
      const id = this.resource.id
      try {
        const response = await postAPI('downloadImageStoreObject', { id, path: this.browserPath + record.name }, { preserveOnFailure: true })
        const jobId = response.downloadimagestoreobjectresponse?.jobid
        if (!jobId) throw new Error()
        const result = await this.$pollJob({ jobId, showSuccessMessage: false })
        if (result.jobstatus !== 1 || this.resource.id !== id || this.$route.params.id !== id || this.listRefreshDisposed) return
        const download = result.jobresult?.downloadimagestoreobjectresponse
        const url = new URL(download.url)
        if (!['http:', 'https:'].includes(url.protocol)) throw new Error()
        const link = document.createElement('a')
        link.href = url.href
        link.download = download.name || record.name
        link.target = '_blank'
        link.rel = 'noopener'
        document.body.appendChild(link)
        link.click()
        link.remove()
      } catch {
        this.$message.error(this.$t('message.secondary.storage.browser.download.failed'))
      } finally {
        this.downloadingImage = false
      }
    }
  }
}
</script>

<style scoped>
.storage-browser-toolbar {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
}

.storage-browser-path { min-width: 0; overflow-wrap: anywhere; }
.storage-browser-directory { padding-inline: 0; }

.storage-browser-search {
  flex: 1 1 240px;
  min-width: 0;
}

.storage-browser-action {
  flex-shrink: 0;
}
</style>
