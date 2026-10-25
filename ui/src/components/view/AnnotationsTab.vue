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
  <div v-if="annotationType && 'listAnnotations' in $store.getters.apis" class="account-center-team" data-testid="annotations-tab">
    <div class="detail-tab-toolbar">
      <a-button v-if="'addAnnotation' in $store.getters.apis" ref="submit" type="primary" :disabled="!annotation.trim() || !annotationReady" :loading="annotationSaving" @click="saveNote">{{ $t('label.submit') }}</a-button>
      <a-button :disabled="annotationSaving" :loading="loadingAnnotations || listRefreshing > 0" @click="getAnnotations"><template #icon><reload-outlined /></template>{{ $t('label.refresh') }}</a-button>
    </div>
    <a-alert v-if="readError" type="error" show-icon class="detail-tab-notice" :message="$t(readDenied ? 'message.detail.tab.denied' : loadedContext ? 'message.list.refresh.stale' : 'error.fetching.data')">
      <template #description><p>{{ readError.startsWith('message.') ? $t(readError) : readError }}</p><a-button size="small" :disabled="loadingAnnotations || annotationSaving || listRefreshing > 0" @click="getAnnotations">{{ $t('label.retry') }}</a-button></template>
    </a-alert>
    <a-alert v-if="mutationError" type="error" show-icon class="detail-tab-notice" :message="$t('message.detail.tab.write.failed')" :description="mutationError.startsWith('message.') ? $t(mutationError) : mutationError" />
    <a-spin :spinning="loadingAnnotations">
      <a-comment v-if="'addAnnotation' in $store.getters.apis">
        <template #avatar><a-avatar><template #icon><edit-outlined /></template></a-avatar></template>
        <template #content><div>
          <a-textarea :rows="4" v-model:value="annotation" :placeholder="$t('label.add.note')" :aria-label="$t('label.add.note')" @keydown.ctrl.enter.prevent="saveNote" />
          <a-checkbox v-if="$store.getters.userInfo.roletype === 'Admin'" :checked="annotationAdminsOnly" :disabled="annotationSaving" class="annotation-visibility" @change="toggleNoteVisibility">{{ $t('label.adminsonly') }}</a-checkbox>
        </div></template>
      </a-comment>
      <h4 class="annotation-title">{{ $t('label.comments') }} ({{ itemCount }})</h4>
      <a-divider dashed />
      <a-list v-if="notes.length" :data-source="notes" item-layout="horizontal" :pagination="false" size="small">
        <template #renderItem="{ item }"><a-list-item :data-annotation-id="item.id">
          <a-comment class="comment" :content="annotationText(item)" :datetime="$toLocaleDate(item.created)" :author="item.username">
            <template #avatar><a-avatar><template #icon><message-outlined /></template></a-avatar></template>
            <template #actions>
              <a-popconfirm v-if="$store.getters.userInfo.roletype === 'Admin' && 'updateAnnotationVisibility' in $store.getters.apis" placement="topLeft" :disabled="!annotationReady" :title="$t('message.detail.tab.visibility.confirm', [$t(item.adminsonly ? 'label.annotation.everyone' : 'label.adminsonly')])" :ok-text="$t('label.yes')" :cancel-text="$t('label.no')" @confirm="updateVisibility(item)">
                <a-button type="link" size="small" :disabled="!annotationReady"><template #icon><eye-outlined /></template>{{ $t(item.adminsonly ? 'label.adminsonly' : 'label.annotation.everyone') }}</a-button>
              </a-popconfirm>
            </template>
          </a-comment>
          <template #actions><a-popconfirm v-if="'removeAnnotation' in $store.getters.apis && isAdminOrAnnotationOwner(item)" placement="topRight" :disabled="!annotationReady" :title="$t('message.remove.annotation')" :ok-text="$t('label.yes')" :cancel-text="$t('label.no')" @confirm="deleteNote(item)">
            <a-button type="primary" size="small" shape="circle" danger :disabled="!annotationReady" :aria-label="$t('label.detail.tab.comment.delete')"><template #icon><delete-outlined /></template></a-button>
          </a-popconfirm></template>
        </a-list-item></template>
      </a-list>
      <a-empty v-else-if="!loadingAnnotations && !readError" :description="$t('message.detail.tab.comments.empty')" />
      <a-pagination
v-if="loadedContext"
class="detail-tab-pagination"
size="small"
:current="page"
:pageSize="pageSize"
:total="itemCount"
:disabled="annotationSaving"
:showTotal="(total, range) => $t('message.detail.tab.total', [total, range[0], range[1]])"
:pageSizeOptions="['10']"
@change="changePage"
showQuickJumper>
        <template #buildOptionText="props"><span>{{ props.value }} / {{ $t('label.page') }}</span></template>
      </a-pagination>
    </a-spin>
  </div>
</template>
<script>
import { listRefreshMixin } from '@/utils/listRefreshMixin'
import { detailTabContext, detailTabDenied, detailTabError, readDetailTabPage } from '@/utils/detailTabData'
import { getAPI, postAPI } from '@/api'
import { Empty } from 'ant-design-vue'
const commands = ['listAnnotations', 'addAnnotation', 'removeAnnotation', 'updateAnnotationVisibility']
export default {
  mixins: [listRefreshMixin(['getAnnotations'], { routeKey: vm => vm.annotationContext, active: vm => !vm.annotationSaving })],
  name: 'AnnotationsTab',
  components: { AEmpty: Empty },
  props: { resource: { type: Object, required: true }, items: { type: Array, default: () => [] } },
  inject: ['parentFetchData'],
  data () { return { resourceType: '', loadingAnnotations: false, annotationSaving: false, annotationVersion: 0, notes: [], annotation: '', annotationType: '', annotationAdminsOnly: false, page: 1, pageSize: 10, itemCount: 0, loadedContext: '', readError: '', readDenied: false, mutationError: '' } },
  computed: {
    annotationContext () { return JSON.stringify([detailTabContext(this, commands), this.annotationType]) },
    annotationReady () { return this.loadedContext === this.annotationContext && !!this.resource.id && !this.loadingAnnotations && !this.annotationSaving && !this.listRefreshing && !this.readError && 'listAnnotations' in this.$store.getters.apis }
  },
  watch: {
    annotationContext () { this.annotationVersion++; this.annotationSaving = false; this.notes = []; this.annotation = ''; this.annotationAdminsOnly = false; this.page = 1; this.itemCount = 0; this.loadedContext = ''; this.readError = ''; this.readDenied = false; this.mutationError = ''; if (this.annotationType && !this.listRefreshDisposed) this.getAnnotations() },
    resource: { deep: true, handler () { this.fetchData() } }
  },
  created () { this.fetchData() },
  methods: {
    annotationText (item) { return this.$route.path?.startsWith('/cniconfiguration/') && item.annotation?.startsWith('MOLD_CNI_CLONE/1\n') ? this.$t('label.cni.clone.relation') : item.annotation },
    generateAnnotationType () {
      switch (this.resourceType) {
        case 'UserVm': return 'VM'
        case 'Domain': return 'DOMAIN'
        case 'Host': return 'HOST'
        case 'Volume': return 'VOLUME'
        case 'Snapshot': return 'SNAPSHOT'
        case 'VMSnapshot': return 'VM_SNAPSHOT'
        case 'VMInstanceGroup': return 'INSTANCE_GROUP'
        case 'SSHKeyPair': return 'SSH_KEYPAIR'
        case 'UserData': return 'USER_DATA'
        case 'KubernetesCluster': return 'KUBERNETES_CLUSTER'
        case 'Network': return 'NETWORK'
        case 'Vpc': return 'VPC'
        case 'PublicIpAddress': return 'PUBLIC_IP_ADDRESS'
        case 'VPNCustomerGateway': return 'VPN_CUSTOMER_GATEWAY'
        case 'Template': return 'TEMPLATE'
        case 'ISO': return 'ISO'
        case 'ServiceOffering': return 'SERVICE_OFFERING'
        case 'DiskOffering': return 'DISK_OFFERING'
        case 'NetworkOffering': return 'NETWORK_OFFERING'
        case 'Zone': return 'ZONE'
        case 'Pod': return 'POD'
        case 'Cluster': return 'CLUSTER'
        case 'PrimaryStorage': return 'PRIMARY_STORAGE'
        case 'SecondaryStorage': return 'SECONDARY_STORAGE'
        case 'SystemVm': return 'SYSTEM_VM'
        case 'VirtualRouter': return 'VR'
        case 'AutoScaleVmGroup': return 'AUTOSCALE_VM_GROUP'
        case 'ManagementServer': return 'MANAGEMENT_SERVER'
        case 'ObjectStorage': return 'OBJECT_STORAGE'
        case 'DisasterRecoveryCluster': return 'DISASTER_RECOVERY_CLUSTER'
        default: return ''
      }
    },
    fetchData () { this.resourceType = this.$route.meta.resourceType; this.annotationType = this.generateAnnotationType(); return this.getAnnotations() },
    changePage (page, pageSize) { if (this.annotationSaving) return; this.page = page; this.pageSize = pageSize; return this.getAnnotations() },
    async getAnnotations () {
      const request = this.listRequestToken('getAnnotations')
      if (!('listAnnotations' in this.$store.getters.apis) || !this.resource.id || !this.annotationType) { this.notes = []; this.itemCount = 0; this.loadedContext = ''; this.loadingAnnotations = false; return }
      const context = this.annotationContext
      this.loadingAnnotations = !request.loaded
      if (!request.loaded) { this.notes = []; this.itemCount = 0; this.loadedContext = '' }
      const params = { entityid: this.resource.id, entitytype: this.annotationType, annotationfilter: 'all', page: this.page, pagesize: this.pageSize, ...(this.resource.projectid ? { projectid: this.resource.projectid } : {}) }
      try {
        const json = await getAPI('listAnnotations', params, { preserveOnFailure: true, timeout: 20000 })
        if (!this.isListRequestCurrent('getAnnotations', request)) return
        const result = readDetailTabPage(json, 'listannotationsresponse', 'annotation', { page: this.page, pageSize: this.pageSize, resourceId: this.resource.id, entityType: this.annotationType })
        if (this.page > result.lastPage) { this.page = result.lastPage; return this.getAnnotations() }
        this.notes = result.rows; this.itemCount = result.count; this.loadedContext = context; this.readError = ''; this.readDenied = false; this.listRefreshFailed = false
      } catch (error) {
        if (!this.isListRequestCurrent('getAnnotations', request)) return
        request.failed = true; this.listRefreshFailed = true; this.readError = detailTabError(error); this.readDenied = detailTabDenied(error)
        if (!request.loaded || this.readDenied) { this.notes = []; this.itemCount = 0; this.loadedContext = '' }
        if (this.readDenied) { this.annotation = ''; this.annotationAdminsOnly = false }
      } finally { if (this.isListRequestCurrent('getAnnotations', request)) this.loadingAnnotations = false }
    },
    handleNoteChange (e) { this.annotation = e.target.value },
    toggleNoteVisibility () { this.annotationAdminsOnly = !this.annotationAdminsOnly },
    isAdminOrAnnotationOwner (item) { return this.$store.getters.userInfo.roletype === 'Admin' || this.$store.getters.userInfo.id === item.userid },
    saveNote () { if (!this.annotationReady || !this.annotation.trim() || !('addAnnotation' in this.$store.getters.apis)) return; return this.mutateAnnotation('addAnnotation', { entityid: this.resource.id, entitytype: this.annotationType, annotation: this.annotation, adminsonly: this.annotationAdminsOnly, ...(this.resource.projectid ? { projectid: this.resource.projectid } : {}) }) },
    deleteNote (item) { if (!this.annotationReady || !('removeAnnotation' in this.$store.getters.apis) || !this.isAdminOrAnnotationOwner(item) || !this.notes.some(row => row.id === item.id)) return; return this.mutateAnnotation('removeAnnotation', { id: item.id }) },
    updateVisibility (item) { if (!this.annotationReady || !('updateAnnotationVisibility' in this.$store.getters.apis) || this.$store.getters.userInfo.roletype !== 'Admin' || !this.notes.some(row => row.id === item.id)) return; return this.mutateAnnotation('updateAnnotationVisibility', { id: item.id, adminsonly: !item.adminsonly }) },
    async mutateAnnotation (command, args) {
      if (this.annotationSaving) return
      const context = this.annotationContext; const version = this.annotationVersion
      const current = () => !this.listRefreshDisposed && context === this.annotationContext && version === this.annotationVersion
      this.annotationSaving = true; this.mutationError = ''
      let succeeded = false
      try {
        await postAPI(command, args, { preserveOnFailure: true, timeout: 20000 })
        if (!current()) return
        succeeded = true
        if (command === 'addAnnotation' && this.annotation === args.annotation) { this.annotation = ''; this.annotationAdminsOnly = false }
      } catch (error) { if (current()) { this.mutationError = detailTabError(error); this.$notifyError(error) } } finally { if (current()) { this.annotationSaving = false; if (succeeded && command === 'addAnnotation') this.page = 1; this.getAnnotations() } }
    }
  }
}
</script>
<style lang="scss" scoped>
@import '@/style/components/view/DetailTab.scss';
.annotation-visibility { margin-top: 10px; }
.annotation-title { font-weight: bold; margin: 0 0 5px; color: inherit; }
.comment { min-width: 0; width: 100%; overflow-wrap: anywhere; }
.account-center-team :deep(.ant-list-item) { align-items: flex-start; }
.account-center-team :deep(.ant-comment) { min-width: 0; }
.account-center-team :deep(.ant-comment-content-detail) { white-space: pre-wrap; overflow-wrap: anywhere; }
@media (max-width: 575px) { .account-center-team :deep(.ant-list-item-action) { margin-left: 8px; } }
</style>
