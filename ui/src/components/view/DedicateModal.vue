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
  <component
    :is="zoneDialog ? 'MoldDialog' : 'a-modal'"
    v-bind="zoneDialog ? { size: 'md', formActions: true, focusGuard: true } : { footer: null }"
    :visible="dedicatedDomainModal"
    :title="label"
    :closable="true"
    :maskClosable="false"
    @cancel="closeModal">
    <div v-ctrl-enter="handleDedicateForm">
      <a-alert v-if="zoneError" type="error" show-icon :message="zoneError" />
      <DedicateDomain
        @domainChange="id => domainId = id"
        @accountChange="id => dedicatedAccount = id"
        :error="domainError" />
      <component :is="zoneDialog ? 'MoldDialogActions' : 'div'" :class="{ 'action-button': !zoneDialog }" v-bind="zoneDialog ? { busy: isSubmitted } : {}">
        <a-button @click="closeModal">{{ $t('label.cancel') }}</a-button>
        <a-button type="primary" ref="submit" @click="handleDedicateForm">{{ $t(zoneJob ? 'label.zone.update.recheck' : 'label.ok') }}</a-button>
      </component>
    </div>
  </component>
</template>

<script>
import { postAPI } from '@/api'
import DedicateDomain from './DedicateDomain'
import MoldDialog from './MoldDialog'
import MoldDialogActions from './MoldDialogActions'

export default {
  components: {
    DedicateDomain,
    MoldDialog,
    MoldDialogActions
  },
  inject: ['parentFetchData'],
  props: {
    active: {
      type: Boolean,
      required: true
    },
    label: {
      type: String,
      required: true
    },
    resource: {
      type: Object,
      required: true
    },
    fetchData: {
      type: Function,
      required: true
    }
  },
  data () {
    return {
      dedicatedDomainModal: false,
      domainId: null,
      dedicatedAccount: null,
      domainError: false,
      isSubmitted: false,
      zoneError: '',
      zoneJob: ''
    }
  },
  computed: {
    zoneDialog () { return this.$route.meta.name === 'zone' }
  },
  watch: {
    active () {
      this.dedicatedDomainModal = this.active
      if (this.active && this.zoneDialog) {
        this.zoneError = ''
        this.zoneJob = ''
      }
    }
  },
  mounted () {
    this.dedicatedDomainModal = this.active
  },
  methods: {
    fetchParentData () {
      this.fetchData()
    },
    closeModal () {
      this.$emit('close')
    },
    async dedicateZone () {
      this.zoneError = ''
      if (!this.domainId) {
        this.domainError = true
        this.isSubmitted = false
        return
      }
      try {
        const retry = !!this.zoneJob
        if (!retry) {
          const response = await postAPI('dedicateZone', {
            zoneId: this.resource.id,
            domainId: this.domainId,
            account: this.dedicatedAccount
          }, { preserveOnFailure: true })
          this.zoneJob = response.dedicatezoneresponse?.jobid
        }
        if (!this.zoneJob) throw new Error(this.$t('message.job.result.unknown'))
        const result = await this.$pollJob({ jobId: this.zoneJob, retry, showLoading: false, successMessage: this.$t('label.zone.dedicated'), errorMessage: this.$t('error.dedicate.zone.failed') })
        if (result.jobstatus === 1) {
          this.fetchParentData()
          this.closeModal()
        } else if (result.jobstatus === 2) {
          this.zoneJob = ''
          this.zoneError = this.$t('error.dedicate.zone.failed')
        } else {
          this.zoneError = this.$t('message.job.result.unknown')
        }
      } catch (error) {
        this.zoneError = Object.values(error.response?.data || {}).find(item => item?.errortext)?.errortext || error.message
      } finally {
        this.isSubmitted = false
      }
    },
    dedicatePod () {
      if (!this.domainId) {
        this.domainError = true
        return
      }
      postAPI('dedicatePod', {
        podId: this.resource.id,
        domainId: this.domainId,
        account: this.dedicatedAccount
      }).then(response => {
        this.$pollJob({
          jobId: response.dedicatepodresponse.jobid,
          title: this.$t('label.dedicate.pod'),
          description: `${this.$t('label.domain.id')} : ${this.domainId}`,
          successMessage: this.$t('label.pod.dedicated'),
          successMethod: () => {
            this.fetchParentData()
            this.dedicatedDomainId = this.domainId
            this.dedicatedDomainModal = false
            this.isSubmitted = false
          },
          errorMessage: this.$t('error.dedicate.pod.failed'),
          errorMethod: () => {
            this.fetchParentData()
            this.dedicatedDomainModal = false
            this.isSubmitted = false
          },
          loadingMessage: this.$t('message.dedicating.pod'),
          catchMessage: this.$t('error.fetching.async.job.result'),
          catchMethod: () => {
            this.parentFetchData()
            this.fetchParentData()
            this.dedicatedDomainModal = false
            this.isSubmitted = false
          }
        })
      }).catch(error => {
        this.$notifyError(error)
        this.dedicatedDomainModal = false
        this.isSubmitted = false
      })
    },
    dedicateCluster () {
      if (!this.domainId) {
        this.domainError = true
        return
      }
      postAPI('dedicateCluster', {
        clusterId: this.resource.id,
        domainId: this.domainId,
        account: this.dedicatedAccount
      }).then(response => {
        this.$pollJob({
          jobId: response.dedicateclusterresponse.jobid,
          title: this.$t('label.dedicate.cluster'),
          description: `${this.$t('label.domain.id')} : ${this.domainId}`,
          successMessage: this.$t('message.cluster.dedicated'),
          successMethod: () => {
            this.fetchParentData()
            this.dedicatedDomainId = this.domainId
            this.dedicatedDomainModal = false
            this.isSubmitted = false
          },
          errorMessage: this.$t('error.dedicate.cluster.failed'),
          errorMethod: () => {
            this.fetchParentData()
            this.dedicatedDomainModal = false
            this.isSubmitted = false
          },
          loadingMessage: this.$t('message.dedicating.cluster'),
          catchMessage: this.$t('error.fetching.async.job.result'),
          catchMethod: () => {
            this.parentFetchData()
            this.fetchParentData()
            this.dedicatedDomainModal = false
            this.isSubmitted = false
          }
        })
      }).catch(error => {
        this.$notifyError(error)
        this.dedicatedDomainModal = false
        this.isSubmitted = false
      })
    },
    dedicateHost () {
      if (!this.domainId) {
        this.domainError = true
        return
      }
      postAPI('dedicateHost', {
        hostId: this.resource.id,
        domainId: this.domainId,
        account: this.dedicatedAccount
      }).then(response => {
        this.$pollJob({
          jobId: response.dedicatehostresponse.jobid,
          title: this.$t('label.dedicate.host'),
          description: `${this.$t('label.domain.id')} : ${this.domainId}`,
          successMessage: this.$t('message.host.dedicated'),
          successMethod: () => {
            this.fetchParentData()
            this.dedicatedDomainId = this.domainId
            this.dedicatedDomainModal = false
            this.isSubmitted = false
          },
          errorMessage: this.$t('error.dedicate.host.failed'),
          errorMethod: () => {
            this.fetchParentData()
            this.dedicatedDomainModal = false
            this.isSubmitted = false
          },
          loadingMessage: this.$t('message.dedicating.host'),
          catchMessage: this.$t('error.fetching.async.job.result'),
          catchMethod: () => {
            this.parentFetchData()
            this.fetchParentData()
            this.dedicatedDomainModal = false
            this.isSubmitted = false
          }
        })
      }).catch(error => {
        this.$notifyError(error)
        this.dedicatedDomainModal = false
        this.isSubmitted = false
      })
    },
    handleDedicateForm () {
      if (this.isSubmitted) {
        return
      }
      this.isSubmitted = true
      if (this.$route.meta.name === 'zone') {
        this.dedicateZone()
      }
      if (this.$route.meta.name === 'pod') {
        this.dedicatePod()
      }
      if (this.$route.meta.name === 'cluster') {
        this.dedicateCluster()
      }
      if (this.$route.meta.name === 'host') {
        this.dedicateHost()
      }
    }
  }
}

</script>
