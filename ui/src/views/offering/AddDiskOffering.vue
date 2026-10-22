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
    <a-spin :spinning="loading">
      <DiskOfferingForm
        ref="formRef"
        :initialValues="form"
        :apiParams="apiParams"
        :isAdmin="isAdmin"
        @submit="handleSubmit">
        <template #form-actions>
          <div :span="24" class="action-button">
            <a-button @click="closeAction">{{ $t('label.cancel') }}</a-button>
            <a-button :loading="loading" ref="submit" type="primary" @click="handleSubmit">{{ $t('label.ok') }}</a-button>
          </div>
        </template>
      </DiskOfferingForm>
    </a-spin>
  </div>
</template>

<script>
import DiskOfferingForm from '@/components/offering/DiskOfferingForm'
import { reactive } from 'vue'
import { postAPI } from '@/api'
import { performanceParams } from '@/utils/offeringQos'
import { allocationParams } from '@/utils/volumeAllocation'
import { isAdmin } from '@/role'

export default {
  name: 'AddDiskOffering',
  components: {
    DiskOfferingForm
  },
  data () {
    return {
      form: reactive({
        storagetype: 'shared',
        provisioningtype: 'thin',
        customdisksize: true,
        writecachetype: 'none',
        qostype: '',
        ispublic: isAdmin(),
        disksizestrictness: false,
        encryptdisk: false,
        kvdoenable: false,
        shareable: false
      }),
      loading: false
    }
  },
  beforeCreate () {
    this.apiParams = this.$getApiParams('createDiskOffering')
  },
  methods: {
    isAdmin () {
      return isAdmin()
    },
    handleSubmit (e) {
      if (e && e.preventDefault) {
        e.preventDefault()
      }
      if (this.loading) return

      this.$refs.formRef.validate().then((values) => {
        const params = {
          name: values.name,
          displaytext: values.displaytext,
          storagetype: values.storagetype,
          cachemode: values.writecachetype,
          customized: values.customdisksize,
          disksizestrictness: values.disksizestrictness,
          encrypt: values.encryptdisk,
          kvdoenable: values.kvdoenable,
          shareable: values.shareable
        }
        if (values.shareable === true) {
          params.cachemode = 'none'
        }
        if (values.customdisksize !== true) {
          params.disksize = values.disksize
        }
        Object.assign(params, performanceParams(values))
        if (values.tags != null && values.tags.length > 0) {
          params.tags = values.tags.join(',')
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
        postAPI('createDiskOffering', params).then(json => {
          this.$emit('publish-disk-offering-id', json?.creatediskofferingresponse?.diskoffering?.id)
          this.$message.success(`${this.$t('message.disk.offering.created')} ${values.name}`)
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

<style scoped lang="scss">
  .form-layout {
    width: 80vw;

    @media (min-width: 700px) {
      width: 550px;
    }
  }
</style>
