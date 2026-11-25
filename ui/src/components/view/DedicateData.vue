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
  <a-divider style="margin-top: 0px; margin-bottom: 0px;"/>
  <a-list-item v-if="dedicatedDomainId">
    <div>
      <div style="margin-bottom: 10px;">
        <strong>{{ $t('label.dedicated') }}</strong>
        <div>{{ $t('label.yes') }}</div>
      </div>
      <p>
        <strong>{{ $t('label.domainid') }}</strong><br/>
        <router-link :to="{ path: '/domain/' + dedicatedDomainId, query: { tab: 'details'} }" target="_blank">{{ dedicatedDomainId }}</router-link>
      </p>
      <p v-if="dedicatedAccountId">
        <strong>{{ $t('label.account') }}</strong><br/>
        <router-link :to="{ path: '/account/' + dedicatedAccountId }">{{ dedicatedAccountId }}</router-link>
      </p>
      <a-button ref="releaseTrigger" style="margin-top: 10px; margin-bottom: 10px;" type="primary" danger :disabled="releaseApi && !$store.getters.apis[releaseApi]" @click="handleRelease">
        {{ releaseButtonLabel }}
      </a-button>
    </div>
  </a-list-item>
  <a-list-item v-else>
    <div>
      <strong>{{ $t('label.dedicated') }}</strong>
      <div>{{ $t('label.no') }}</div>
      <a-button ref="dedicateTrigger" type="primary" style="margin-top: 10px; margin-bottom: 10px;" @click="modalActive = true" :disabled="!dedicateButtonAvailable">
        {{ dedicatedButtonLabel }}
      </a-button>
    </div>
    <DedicateModal
      :resource="resource"
      :active="modalActive"
      :label="dedicatedModalLabel"
      :after-close="restoreClusterFocus"
      @close="modalActive = false"
      :fetchData="fetchData" />
  </a-list-item>
  <mold-dialog
    v-if="releaseConfirmVisible || $route.meta.name === 'cluster'"
    :visible="releaseConfirmVisible"
    :title="releaseButtonLabel"
    size="md"
    form-actions
    focus-guard
    :initial-focus="$route.meta.name === 'cluster' ? '.ant-modal-footer .cluster-release-cancel' : ''"
    :after-close="restoreClusterFocus"
    @cancel="releaseConfirmVisible = false">
    <cluster-action-context v-if="$route.meta.name === 'cluster'" :resource="resource" impact="message.cluster.release.confirm" destructive />
    <template v-else>
      <p>{{ resource.name }}</p>
      <p>{{ $t($route.meta.name === 'host' ? 'message.host.release.confirm' : 'message.zone.release.confirm') }}</p>
    </template>
    <a-alert v-if="releaseError" type="error" show-icon :message="releaseError" />
    <mold-dialog-actions :busy="releaseBusy">
      <a-button class="cluster-release-cancel" @click="releaseConfirmVisible = false">{{ $t('label.cancel') }}</a-button>
      <a-button danger type="primary" @click="releaseInfrastructureDedication">{{ releaseJob ? $t($route.meta.name === 'host' ? 'label.host.operation.recheck' : 'label.zone.update.recheck') : releaseButtonLabel }}</a-button>
    </mold-dialog-actions>
  </mold-dialog>
</template>

<script>
import { listRefreshMixin } from '@/utils/listRefreshMixin'

import { getAPI, postAPI } from '@/api'
import DedicateModal from './DedicateModal'
import ClusterActionContext from '@/views/infra/ClusterActionContext.vue'
import MoldDialog from './MoldDialog'
import MoldDialogActions from './MoldDialogActions'

export default {
  mixins: [listRefreshMixin(['fetchDedicatedZones', 'fetchDedicatedPods', 'fetchDedicatedClusters', 'fetchDedicatedHosts'], { select: vm => ({ zone: ['fetchDedicatedZones'], pod: ['fetchDedicatedPods'], cluster: ['fetchDedicatedClusters'], host: ['fetchDedicatedHosts'] })[vm.$route.meta.name] || [] })],
  props: {
    resource: {
      type: Object,
      required: true
    }
  },
  components: {
    DedicateModal,
    ClusterActionContext,
    MoldDialog,
    MoldDialogActions
  },
  inject: ['parentFetchData'],
  created () {
    this.fetchData()
  },
  data () {
    return {
      modalActive: false,
      releaseConfirmVisible: false,
      releaseBusy: false,
      releaseError: '',
      releaseJob: '',
      dedicateButtonAvailable: true,
      dedicatedButtonLabel: this.$t('label.dedicate'),
      releaseButtonLabel: this.$t('label.release'),
      dedicatedModalLabel: this.$t('label.dedicate'),
      dedicatedDomainId: null,
      dedicatedAccountId: null
    }
  },
  computed: {
    releaseApi () {
      if (this.$route.meta.name === 'host') return 'releaseDedicatedHost'
      if (this.$route.meta.name === 'zone') return 'releaseDedicatedZone'
      if (this.$route.meta.name === 'cluster') return 'releaseDedicatedCluster'
      return ''
    }
  },
  watch: {
    resource: {
      deep: true,
      handler (newItem, oldItem) {
        if (this.resource && this.resource.id && newItem && newItem.id !== oldItem.id) {
          this.fetchData()
        }
      }
    }
  },
  methods: {
    restoreClusterFocus () {
      if (this.$route.meta.name !== 'cluster' || this.resource.id !== this.$route.params.id) return
      const trigger = this.$refs.releaseTrigger || this.$refs.dedicateTrigger
      if (trigger?.$el) trigger.$el.focus({ preventScroll: true })
    },
    fetchData () {
      this.dedicateButtonAvailable = true
      if (this.$route.meta.name === 'zone') {
        this.fetchDedicatedZones()
        this.releaseButtonLabel = this.$t('label.release.dedicated.zone')
        this.dedicateButtonAvailable = ('dedicateZone' in this.$store.getters.apis)
        this.dedicatedButtonLabel = this.$t('label.dedicate.zone')
        this.dedicatedModalLabel = this.$t('label.dedicate.zone')
      }
      if (this.$route.meta.name === 'pod') {
        this.fetchDedicatedPods()
        this.releaseButtonLabel = this.$t('label.release.dedicated.pod')
        this.dedicateButtonAvailable = ('dedicatePod' in this.$store.getters.apis)
        this.dedicatedButtonLabel = this.$t('label.dedicate.pod')
        this.dedicatedModalLabel = this.$t('label.dedicate.pod')
      }
      if (this.$route.meta.name === 'cluster') {
        this.fetchDedicatedClusters()
        this.releaseButtonLabel = this.$t('label.release.dedicated.cluster')
        this.dedicateButtonAvailable = ('dedicateCluster' in this.$store.getters.apis)
        this.dedicatedButtonLabel = this.$t('label.dedicate.cluster')
        this.dedicatedModalLabel = this.$t('label.dedicate.cluster')
      }
      if (this.$route.meta.name === 'host') {
        this.fetchDedicatedHosts()
        this.releaseButtonLabel = this.$t('label.release.dedicated.host')
        this.dedicateButtonAvailable = ('dedicateHost' in this.$store.getters.apis)
        this.dedicatedButtonLabel = this.$t('label.dedicate.host')
        this.dedicatedModalLabel = this.$t('label.dedicate.host')
      }
    },
    fetchDedicatedZones () {
      const listRequest = this.listRequestToken('fetchDedicatedZones')
      return getAPI('listDedicatedZones', {
        zoneid: this.resource.id
      }).then(response => {
        if (!this.isListRequestCurrent('fetchDedicatedZones', listRequest)) return
        this.dedicatedDomainId = null
        this.dedicatedAccountId = null
        if (response?.listdedicatedzonesresponse?.dedicatedzone?.length > 0) {
          this.dedicatedDomainId = response.listdedicatedzonesresponse.dedicatedzone[0].domainid
          this.dedicatedAccountId = response.listdedicatedzonesresponse.dedicatedzone[0].accountid
        }
      }).catch(error => {
        if (!this.isListRequestCurrent('fetchDedicatedZones', listRequest)) return
        listRequest.failed = true
        this.listRefreshFailed = true
        if (listRequest.loaded) return

        this.$notifyError(error)
      })
    },
    fetchDedicatedPods () {
      const listRequest = this.listRequestToken('fetchDedicatedPods')
      return getAPI('listDedicatedPods', {
        podid: this.resource.id
      }).then(response => {
        if (!this.isListRequestCurrent('fetchDedicatedPods', listRequest)) return
        this.dedicatedDomainId = null
        this.dedicatedAccountId = null
        if (response?.listdedicatedpodsresponse?.dedicatedpod?.length > 0) {
          this.dedicatedDomainId = response.listdedicatedpodsresponse.dedicatedpod[0].domainid
          this.dedicatedAccountId = response.listdedicatedpodsresponse.dedicatedpod[0].accountid
        }
      }).catch(error => {
        if (!this.isListRequestCurrent('fetchDedicatedPods', listRequest)) return
        listRequest.failed = true
        this.listRefreshFailed = true
        if (listRequest.loaded) return

        this.$notifyError(error)
      })
    },
    fetchDedicatedClusters () {
      const listRequest = this.listRequestToken('fetchDedicatedClusters')
      return getAPI('listDedicatedClusters', {
        clusterid: this.resource.id
      }).then(response => {
        if (!this.isListRequestCurrent('fetchDedicatedClusters', listRequest)) return
        this.dedicatedDomainId = null
        this.dedicatedAccountId = null
        if (response?.listdedicatedclustersresponse?.dedicatedcluster?.length > 0) {
          this.dedicatedDomainId = response.listdedicatedclustersresponse.dedicatedcluster[0].domainid
          this.dedicatedAccountId = response.listdedicatedclustersresponse.dedicatedcluster[0].accountid
        }
      }).catch(error => {
        if (!this.isListRequestCurrent('fetchDedicatedClusters', listRequest)) return
        listRequest.failed = true
        this.listRefreshFailed = true
        if (listRequest.loaded) return

        this.$notifyError(error)
      })
    },
    fetchDedicatedHosts () {
      const listRequest = this.listRequestToken('fetchDedicatedHosts')
      return getAPI('listDedicatedHosts', {
        hostid: this.resource.id
      }).then(response => {
        if (!this.isListRequestCurrent('fetchDedicatedHosts', listRequest)) return
        this.dedicatedDomainId = null
        this.dedicatedAccountId = null
        if (response?.listdedicatedhostsresponse?.dedicatedhost?.length > 0) {
          this.dedicatedDomainId = response.listdedicatedhostsresponse.dedicatedhost[0].domainid
          this.dedicatedAccountId = response.listdedicatedhostsresponse.dedicatedhost[0].accountid
        }
      }).catch(error => {
        if (!this.isListRequestCurrent('fetchDedicatedHosts', listRequest)) return
        listRequest.failed = true
        this.listRefreshFailed = true
        if (listRequest.loaded) return

        this.$notifyError(error)
      })
    },
    async releaseInfrastructureDedication () {
      const api = this.releaseApi
      if (this.releaseBusy || !api || !this.$store.getters.apis[api]) return
      const scope = this.$route.meta.name
      const idKey = scope + 'id'
      const failure = 'error.release.dedicate.' + scope
      const success = scope === 'zone' ? 'message.dedicated.zone.released' : 'message.' + scope + '.dedication.released'
      this.releaseBusy = true
      this.releaseError = ''
      try {
        const retry = !!this.releaseJob
        if (!retry) {
          const response = await postAPI(api, { [idKey]: this.resource.id }, { preserveOnFailure: true })
          this.releaseJob = response[api.toLowerCase() + 'response']?.jobid
        }
        if (!this.releaseJob) throw new Error(this.$t('message.job.result.unknown'))
        const result = await this.$pollJob({ jobId: this.releaseJob, retry, showLoading: false, successMessage: this.$t(success), errorMessage: this.$t(failure) })
        if (result.jobstatus === 1) {
          this.releaseConfirmVisible = false
          this.releaseJob = ''
          this.fetchData()
          this.parentFetchData()
        } else if (result.jobstatus === 2) {
          this.releaseJob = ''
          this.releaseError = this.$t(failure)
        } else {
          this.releaseError = this.$t('message.job.result.unknown')
        }
      } catch (error) {
        this.releaseError = Object.values(error.response?.data || {}).find(item => item?.errortext)?.errortext || error.message
      } finally {
        this.releaseBusy = false
      }
    },
    releaseDedidcatedPod () {
      postAPI('releaseDedicatedPod', {
        podid: this.resource.id
      }).then(response => {
        this.$pollJob({
          jobId: response.releasededicatedpodresponse.jobid,
          title: this.$t('label.release.dedicated.pod'),
          description: this.resource.id,
          successMessage: this.$t('message.pod.dedication.released'),
          successMethod: () => {
            this.dedicatedDomainId = null
          },
          errorMessage: this.$t('error.release.dedicate.pod'),
          loadingMessage: this.$t('message.releasing.dedicated.pod'),
          catchMessage: this.$t('error.fetching.async.job.result'),
          catchMethod: () => {
            this.parentFetchData()
          }
        })
      }).catch(error => {
        this.$notifyError(error)
      })
    },
    handleRelease () {
      this.modalActive = false
      if (this.releaseApi) {
        this.releaseError = ''
        this.releaseConfirmVisible = true
      }
      if (this.$route.meta.name === 'pod') {
        this.releaseDedidcatedPod()
      }
    }
  }
}
</script>
