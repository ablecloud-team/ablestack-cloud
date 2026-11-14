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
  <a-spin :spinning="componentLoading" class="vpc-static-routes">
    <a-alert v-if="listRefreshFailed" type="warning" show-icon :message="$t('message.list.refresh.stale')" class="route-notice"><template #action><a-button size="small" :disabled="componentLoading" @click="fetchData">{{ $t('label.retry') }}</a-button></template></a-alert>
    <div class="route-toolbar"><a-button type="primary" :disabled="componentLoading || !('createStaticRoute' in $store.getters.apis)" @click="openCreate"><template #icon><plus-outlined /></template>{{ $t('label.add.route') }}</a-button></div>
    <a-table size="small" :scroll="{ x: 760 }" :loading="loading" :columns="columns" :dataSource="routes" :pagination="false" :rowKey="record => record.id">
      <template #bodyCell="{ column, text, record }">
        <template v-if="column.key === 'vpcgatewayip'">
          <router-link :to="{ path: '/privategw/' + record.vpcgatewayid }">{{ text }}</router-link>
        </template>
        <template v-if="column.key === 'actions'">
          <tooltip-button :tooltip="$t('label.edit.tags')" :disabled="!('listTags' in $store.getters.apis)" icon="tag-outlined" @onClick="() => openTagsModal(record)" />
          <tooltip-button :tooltip="$t('label.delete')" :disabled="componentLoading || !('deleteStaticRoute' in $store.getters.apis)" icon="delete-outlined" type="primary" :danger="true" @onClick="() => handleDelete(record)" />
        </template>
      </template>
    </a-table>
    <MoldDialog :visible="createVisible" size="md" form-actions focus-guard :title="$t('label.add.route')" @cancel="closeCreate">
      <VpcActionContext :resources="[resource]" />
      <a-alert v-if="routeError" type="error" show-icon :message="routeError" class="route-notice" />
      <a-form layout="vertical" @finish="handleAdd" v-ctrl-enter="handleAdd">
        <a-form-item :label="$t('label.cidr.destination.network')" required><a-input ref="routeInput" v-model:value="newRoute" v-focus="true" :disabled="routeSubmitting" :aria-label="$t('label.cidr.destination.network')" /></a-form-item>
        <a-form-item v-if="$route.fullPath.startsWith('/vpc')" :label="$t('label.nexthop')"><a-input v-model:value="nexthop" :disabled="routeSubmitting" :aria-label="$t('label.nexthop')" /></a-form-item>
        <MoldDialogActions :busy="routeSubmitting"><a-button @click="closeCreate">{{ $t('label.cancel') }}</a-button><a-button type="primary" :loading="routeSubmitting" @click="handleAdd">{{ $t('label.add.route') }}</a-button></MoldDialogActions>
      </a-form>
    </MoldDialog>
    <MoldDialog :visible="tagsModalVisible" size="md" form-actions focus-guard :title="$t('label.edit.tags')" @cancel="closeTags">
      <VpcActionContext :resources="[resource]" />
      <p class="route-identity">{{ selectedRule?.cidr }} · {{ selectedRule?.id }}</p>
      <a-alert v-if="tagError" type="error" show-icon :message="tagError" class="route-notice" />
      <a-spin :spinning="tagsLoading">
        <a-form :ref="formRef" :model="form" :rules="rules" class="route-tags-form" @finish="handleAddTag" v-ctrl-enter="handleAddTag">
          <a-form-item name="key" ref="key" :label="$t('label.key')"><a-input ref="tagKeyInput" v-focus="true" v-model:value="form.key" :disabled="tagsLoading" /></a-form-item>
          <a-form-item name="value" ref="value" :label="$t('label.value')"><a-input ref="tagValueInput" v-model:value="form.value" :disabled="tagsLoading" /></a-form-item>
          <a-button type="primary" :disabled="tagsLoading || !('createTags' in $store.getters.apis)" @click="handleAddTag">{{ $t('label.add') }}</a-button>
        </a-form>
        <a-divider />
        <div class="route-tags"><a-tag v-for="(tag, index) in tags" :key="index" :closable="!tagsLoading && 'deleteTags' in $store.getters.apis" @close="event => { event.preventDefault(); handleDeleteTag(tag) }">{{ tag.key }} = {{ tag.value }}</a-tag></div>
      </a-spin>
      <MoldDialogActions :busy="tagsLoading"><a-button @click="closeTags">{{ $t('label.close') }}</a-button></MoldDialogActions>
    </MoldDialog>
  </a-spin>
</template>

<script>
import MoldDialog from '@/components/network/VpcDialog'
import MoldDialogActions from '@/components/view/MoldDialogActions'
import VpcActionContext from '@/components/network/VpcActionContext'
import { listRefreshMixin } from '@/utils/listRefreshMixin'

import { ref, reactive, toRaw } from 'vue'
import { getAPI, postAPI } from '@/api'
import TooltipButton from '@/components/widgets/TooltipButton'
import TooltipLabel from '@/components/widgets/TooltipLabel.vue'

export default {
  mixins: [listRefreshMixin(['fetchData'])],
  name: 'StaticRoutesTab',
  components: {
    MoldDialog,
    MoldDialogActions,
    VpcActionContext,
    TooltipLabel,
    TooltipButton
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
      routes: [],
      createVisible: false,
      routeSubmitting: false,
      routeError: '',
      tagError: '',
      tagRequest: 0,
      componentLoading: false,
      selectedRule: null,
      tagsModalVisible: false,
      tags: [],
      tagsLoading: false,
      newRoute: null,
      nexthop: null,
      columns: [
        {
          title: this.$t('label.cidr.destination.network'),
          dataIndex: 'cidr',
          width: 180
        },
        {
          title: this.$t('label.vpc.gateway.ip'),
          key: 'vpcgatewayip',
          dataIndex: 'vpcgatewayip',
          width: 180
        },
        {
          title: this.$t('label.nexthop'),
          dataIndex: 'nexthop',
          width: 180
        },
        {
          title: this.$t('label.actions'),
          key: 'actions',
          width: 120
        }
      ]
    }
  },
  created () {
    this.initForm()
    this.fetchData()
  },
  computed: { routeContext () { const getters = this.$store.getters; return JSON.stringify([this.resource.id, this.resource.domainid, getters.project?.id, getters.userInfo?.id, getters.userInfo?.domainid, getters.userInfo?.roleid, getters.userInfo?.roletype]) } },
  watch: {
    routeContext () { this.createVisible = false; this.tagsModalVisible = false; this.routeSubmitting = false; this.tagsLoading = false; this.tagRequest++; this.tags = []; this.newRoute = null; this.nexthop = null },
    loading (newData, oldData) {
      if (!newData && this.resource.id) {
        this.fetchData()
      }
    }
  },
  methods: {
    initForm () {
      this.formRef = ref()
      this.form = reactive({
        start: true
      })
      this.rules = reactive({
        key: [{ required: true, message: this.$t('message.specify.tag.key') }],
        value: [{ required: true, message: this.$t('message.specify.tag.value') }]
      })
    },
    fetchData () {
      const listRequest = this.listRequestToken('fetchData')
      this.componentLoading = !listRequest.loaded
      var params = {
        listAll: true
      }
      if (this.$route.fullPath.startsWith('/vpc')) {
        params.vpcid = this.resource.id
      } else {
        params.gatewayid = this.resource.id
      }
      return getAPI('listStaticRoutes', params).then(json => {
        if (!this.isListRequestCurrent('fetchData', listRequest)) return

        this.routes = json.liststaticroutesresponse?.staticroute || []
      }).catch(error => {
        if (!this.isListRequestCurrent('fetchData', listRequest)) return
        listRequest.failed = true
        this.listRefreshFailed = true
        if (listRequest.loaded) return

        this.$notifyError(error)
      }).finally(() => {
        if (!this.isListRequestCurrent('fetchData', listRequest)) return

        this.componentLoading = false
      }).finally(() => {
        if (!this.isListRequestCurrent('fetchData', listRequest)) return
        this.componentLoading = false
      })
    },
    errorText (error) { return Object.values(error.response?.data || {}).find(value => value?.errortext)?.errortext || error.message || this.$t('error.fetching.data') },
    openCreate () { this.routeError = ''; this.createVisible = true },
    closeCreate () { if (!this.routeSubmitting) this.createVisible = false },
    closeTags () { if (!this.tagsLoading) { this.tagsModalVisible = false; this.tagRequest++ } },
    handleAdd () {
      if (this.routeSubmitting || !('createStaticRoute' in this.$store.getters.apis)) return
      if (!this.newRoute?.trim()) { this.routeError = this.$t('label.required'); if (this.$refs.routeInput) this.$refs.routeInput.focus(); return }
      const scope = this.routeContext
      const params = { cidr: this.newRoute }
      if (this.$route.fullPath.startsWith('/vpc')) { params.vpcid = this.resource.id; if (this.nexthop) params.nexthop = this.nexthop } else params.gatewayid = this.resource.id
      this.routeSubmitting = true
      this.routeError = ''
      const failed = error => {
        if (scope !== this.routeContext) return
        this.routeSubmitting = false
        this.routeError = error ? this.errorText(error) : this.$t('message.add.static.route.failed')
        this.fetchData()
      }
      return postAPI('createStaticRoute', params).then(response => {
        this.$pollJob({
          jobId: response.createstaticrouteresponse.jobid,
          title: this.$t('message.success.add.static.route'),
          description: params.cidr,
          successMethod: () => {
            if (scope !== this.routeContext) return
            this.routeSubmitting = false; this.createVisible = false; this.newRoute = null; this.nexthop = null; this.fetchData()
          },
          errorMessage: this.$t('message.add.static.route.failed'),
          errorMethod: failed,
          loadingMessage: this.$t('message.add.static.route.processing'),
          catchMessage: this.$t('error.fetching.async.job.result'),
          catchMethod: failed
        })
      }).catch(error => { if (scope === this.routeContext) { this.$notifyError(error); failed(error) } })
    },
    handleDelete (route) {
      this.componentLoading = true
      postAPI('deleteStaticRoute', {
        id: route.id
      }).then(response => {
        this.$pollJob({
          jobId: response.deletestaticrouteresponse.jobid,
          title: this.$t('message.success.delete.static.route'),
          description: route.id,
          successMethod: () => {
            this.fetchData()
            this.componentLoading = false
          },
          errorMessage: this.$t('message.delete.static.route.failed'),
          errorMethod: () => {
            this.fetchData()
            this.componentLoading = false
          },
          loadingMessage: this.$t('message.delete.static.route.processing'),
          catchMessage: this.$t('error.fetching.async.job.result'),
          catchMethod: () => {
            this.fetchData()
            this.componentLoading = false
          }
        })
      }).catch(error => {
        this.$notifyError(error)
        this.fetchData()
        this.componentLoading = false
      })
    },
    fetchTags (route) {
      const request = ++this.tagRequest
      this.tagsLoading = true
      this.tagError = ''
      return getAPI('listTags', { resourceId: route.id, resourceType: 'StaticRoute', listAll: true }).then(response => {
        if (request === this.tagRequest) this.tags = response.listtagsresponse?.tag || []
      }).catch(error => {
        if (request === this.tagRequest) { this.tagError = this.errorText(error); this.$notifyError(error) }
      }).finally(() => { if (request === this.tagRequest) this.tagsLoading = false })
    },
    saveTag (api, tag) {
      const scope = this.routeContext
      const route = { ...this.selectedRule }
      const verb = api === 'createTags' ? 'add' : 'delete'
      const current = () => scope === this.routeContext && this.selectedRule?.id === route.id
      this.tagError = ''
      const failed = error => { if (current()) { this.tagsLoading = false; this.tagError = error ? this.errorText(error) : this.$t('message.' + verb + '.tag.failed') } }
      return postAPI(api, {
        'tags[0].key': tag.key,
        'tags[0].value': tag.value,
        resourceIds: route.id,
        resourceType: 'StaticRoute'
      }).then(response => {
        this.$pollJob({
          jobId: response[api.toLowerCase() + 'response'].jobid,
          successMessage: this.$t('message.success.' + verb + '.tag'),
          successMethod: () => { if (current()) this.fetchTags(route) },
          errorMessage: this.$t('message.' + verb + '.tag.failed'),
          errorMethod: failed,
          loadingMessage: this.$t('message.' + verb + '.tag.processing'),
          catchMessage: this.$t('error.fetching.async.job.result'),
          catchMethod: failed
        })
      }).catch(error => { if (current()) { this.$notifyError(error); failed(error) } })
    },
    handleDeleteTag (tag) {
      if (this.tagsLoading || !('deleteTags' in this.$store.getters.apis)) return
      this.tagsLoading = true
      return this.saveTag('deleteTags', tag)
    },
    async handleAddTag () {
      if (this.tagsLoading || !('createTags' in this.$store.getters.apis)) return
      this.tagsLoading = true
      try {
        await this.formRef.value.validate()
        return this.saveTag('createTags', toRaw(this.form))
      } catch (error) {
        this.tagsLoading = false
        if (error.errorFields?.length) {
          this.formRef.value.scrollToField(error.errorFields[0].name)
          const field = error.errorFields[0].name[0] === 'value' ? 'tagValueInput' : 'tagKeyInput'
          this.$nextTick(() => { if (this.tagsModalVisible && this.$refs[field]?.focus) this.$refs[field].focus() })
        }
      }
    },
    openTagsModal (route) {
      this.initForm()
      this.tags = []
      this.selectedRule = route
      this.tagsModalVisible = true
      const scope = this.routeContext
      this.fetchTags(this.selectedRule).then(() => this.$nextTick(() => {
        if (scope === this.routeContext && this.tagsModalVisible && this.$refs.tagKeyInput?.focus) this.$refs.tagKeyInput.focus()
      }))
    }
  }
}
</script>

<style scoped>
.vpc-static-routes { min-width: 0; }
.route-toolbar, .route-notice { margin-bottom: 16px; }
.route-identity { overflow-wrap: anywhere; }
.route-tags-form { display: flex; flex-wrap: wrap; align-items: flex-end; gap: 12px; }
.route-tags-form :deep(.ant-form-item) { flex: 1; min-width: 140px; margin-bottom: 0; }
.route-tags :deep(.ant-tag) { white-space: normal; overflow-wrap: anywhere; margin-bottom: 8px; }
</style>
