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
  <div class="form-layout" v-ctrl-enter="handleSubmit">
    <a-spin :spinning="loading || sourceLoading">
      <a-alert v-if="sourceError" type="error" :message="sourceError" show-icon><template #description><a-button @click="fetchSourceOffering">{{ $t('label.retry') }}</a-button></template></a-alert>
      <a-alert
        v-if="resource"
        type="info"
        style="margin-bottom: 16px">
        <template #message>
          <div style="display: block; width: 100%;">
            <div style="display: block; margin-bottom: 8px;">
              <strong>{{ $t('message.clone.offering.from') }}: {{ resource.name }}</strong>
            </div>
            <div style="display: block; font-size: 12px;">
              {{ $t('message.clone.offering.edit.hint') }}
            </div>
          </div>
        </template>
      </a-alert>
      <DiskOfferingForm
        ref="formRef"
        :initialValues="form"
        :apiParams="apiParams"
        :isAdmin="isAdmin"
        @submit="handleSubmit">
        <template #form-actions>
          <div :span="24" class="action-button">
            <a-button @click="closeAction">{{ $t('label.cancel') }}</a-button>
            <a-button :loading="loading" :disabled="sourceLoading || !sourceReady" ref="submit" type="primary" @click="handleSubmit">{{ $t('label.ok') }}</a-button>
          </div>
        </template>
      </DiskOfferingForm>
    </a-spin>
  </div>
</template>

<script>
import DiskOfferingForm from '@/components/offering/DiskOfferingForm'
import { reactive } from 'vue'
import { getAPI, postAPI } from '@/api'
import { performanceParams, qosForm } from '@/utils/offeringQos'
import { allocationParams, allocationForm } from '@/utils/volumeAllocation'
import { isAdmin } from '@/role'

export default {
  name: 'CloneDiskOffering',
  components: {
    DiskOfferingForm
  },
  props: {
    resource: {
      type: Object,
      required: true
    }
  },
  data () {
    return {
      formRef: null,
      form: reactive({}),
      sourceLoading: false,
      sourceReady: false,
      sourceError: '',
      loading: false
    }
  },
  beforeCreate () {
    this.apiParams = this.$getApiParams('cloneDiskOffering')
  },
  created () {
    this.fetchSourceOffering()
  },
  methods: {
    async fetchSourceOffering () {
      this.sourceLoading = true
      this.sourceReady = false
      this.sourceError = ''
      try {
        const response = await getAPI('listDiskOfferings', { id: this.resource.id, listall: true, state: 'all', details: 'all' })
        const source = response.listdiskofferingsresponse?.diskoffering?.find(row => row.id === this.resource.id)
        if (!source) throw new Error(this.$t('message.offering.not.found'))
        this.populateFormFromResource(source)
        this.sourceReady = true
      } catch (error) {
        this.sourceError = error?.response?.data?.errortext || error?.message || this.$t('message.offering.not.found')
      } finally { this.sourceLoading = false }
    },
    populateFormFromResource (sourceResource = this.resource) {
      if (!sourceResource) return

      const r = sourceResource
      Object.assign(this.form, allocationForm(r))
      const isTrueValue = value => value === true || value === 'true' || value === 1 || value === '1'
      this.form.name = r.name + ' - Clone'
      this.form.displaytext = r.displaytext

      if (r.storagetype) {
        this.form.storagetype = r.storagetype
      }
      if (r.provisioningtype) {
        this.form.provisioningtype = r.provisioningtype
      }
      if (r.iscustomized !== undefined) {
        this.form.customdisksize = r.iscustomized
      } else if (r.customized !== undefined) {
        this.form.customdisksize = r.customized
      }
      if (r.disksize) this.form.disksize = r.disksize

      if (r.cachemode) {
        this.form.writecachetype = r.cachemode
      }

      if (r.disksizestrictness !== undefined) {
        this.form.disksizestrictness = r.disksizestrictness
      }
      if (r.encrypt !== undefined) {
        this.form.encryptdisk = r.encrypt
      }
      if (r.kvdoenable !== undefined) {
        this.form.kvdoenable = isTrueValue(r.kvdoenable)
      }
      if (r.shareable !== undefined) {
        this.form.shareable = isTrueValue(r.shareable)
      }

      Object.assign(this.form, qosForm(r))
      const hasHypervisorQos = Object.values(qosForm(r)).some(value => value !== '' && Number(value) > 0)
      this.form.qostype = hasHypervisorQos ? 'hypervisor' : (isTrueValue(r.iscustomizediops) || Number(r.miniops) > 0 || Number(r.maxiops) > 0 ? 'storage' : '')
      this.form.iscustomizeddiskiops = isTrueValue(r.iscustomizediops)
      if (r.miniops !== undefined) this.form.diskiopsmin = String(r.miniops)
      if (r.maxiops !== undefined) this.form.diskiopsmax = String(r.maxiops)
      if (r.hypervisorsnapshotreserve !== undefined) this.form.hypervisorsnapshotreserve = String(r.hypervisorsnapshotreserve)

      if (r.tags) {
        this.form.tags = r.tags.split(',')
      }

      if (r.ispublic !== undefined) {
        this.form.ispublic = isTrueValue(r.ispublic)
      } else if (typeof r.domainid === 'string' && r.domainid.length > 0) {
        this.form.ispublic = false
      }
      if (typeof r.domainid === 'string' && r.domainid.length > 0) {
        this.form.domainid = r.domainid.split(',')
      }
      if (typeof r.zoneid === 'string' && r.zoneid.length > 0) {
        this.form.zoneid = r.zoneid.split(',')
      }
      if (r.vspherestoragepolicy) this.form.storagepolicy = r.vspherestoragepolicy
    },
    isAdmin () {
      return isAdmin()
    },
    handleSubmit (e) {
      if (e && e.preventDefault) {
        e.preventDefault()
      }
      if (this.loading || this.sourceLoading || !this.sourceReady) return

      this.$refs.formRef.validate().then((values) => {
        const params = {
          sourceofferingid: this.resource.id,
          name: values.name
        }

        if (values.displaytext) {
          params.displaytext = values.displaytext
        }
        if (values.storagetype) {
          params.storagetype = values.storagetype
        }
        if (values.writecachetype) {
          params.cachemode = values.writecachetype
        }
        if (values.customdisksize !== undefined) {
          params.customized = values.customdisksize
        }
        if (values.disksizestrictness !== undefined) {
          params.disksizestrictness = values.disksizestrictness
        }
        if (values.encryptdisk !== undefined) {
          params.encrypt = values.encryptdisk
        }

        if (values.customdisksize !== true && values.disksize) {
          params.disksize = values.disksize
        }

        Object.assign(params, performanceParams(values, false, true))
        if (values.tags != null && values.tags.length > 0) {
          const tags = values.tags.join(',')
          params.tags = tags
        }

        if (values.ispublic !== true) {
          const domainIndexes = values.domainid
          let domainId = null
          if (domainIndexes && domainIndexes.length > 0) {
            const domainIds = []
            const domains = this.$refs.formRef.domains
            for (let i = 0; i < domainIndexes.length; i++) {
              domainIds.push(domains[domainIndexes[i]].id)
            }
            domainId = domainIds.join(',')
          }
          if (domainId) {
            params.domainid = domainId
          }
        }

        const zoneIndexes = values.zoneid
        let zoneId = null
        if (zoneIndexes && zoneIndexes.length > 0) {
          const zoneIds = []
          const zones = this.$refs.formRef.zones
          for (let j = 0; j < zoneIndexes.length; j++) {
            zoneIds.push(zones[zoneIndexes[j]].id)
          }
          zoneId = zoneIds.join(',')
        }
        if (zoneId) {
          params.zoneid = zoneId
        }

        if (values.storagepolicy) {
          params.storagepolicy = values.storagepolicy
        }

        Object.assign(params, allocationParams(values))
        this.loading = true
        postAPI('cloneDiskOffering', params).then(json => {
          this.$message.success(`${this.$t('message.success.clone.disk.offering')} ${values.name}`)
          this.$emit('refresh-data')
          this.closeAction()
        }).catch(error => {
          this.$notifyError(error)
        }).finally(() => {
          this.loading = false
        })
      })
    },
    closeAction () {
      this.$emit('close-action')
    }
  }
}
</script>

<style scoped lang="less">
  .form-layout {
    width: 80vw;

    @media (min-width: 700px) {
      width: 550px;
    }
  }
</style>
