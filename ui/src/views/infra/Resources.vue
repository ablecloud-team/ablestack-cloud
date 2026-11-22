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
  <a-spin :spinning="fetchLoading" :class="{ 'zone-capacity': isZone }">
    <a-alert v-if="isZone && listRefreshFailed" type="error" show-icon :message="$t('message.zone.capacity.load.failed')">
      <template #description><a-button size="small" @click="fetchData">{{ $t('label.retry') }}</a-button></template>
    </a-alert>
    <p v-else-if="listRefreshFailed" role="status">{{ $t('message.list.refresh.stale') }}</p>
    <a-empty v-if="isZone && !resourcesList.length && !fetchLoading && !listRefreshFailed" />
    <a-list>
      <a-list-item v-for="item in resourcesList" :key="item.id || item.type" class="list-item" :data-capacity-type="item.type">
        <div class="list-item__container">
          <div class="list-item__data list-item__title">{{ returnCapacityTitle(item.type) }}</div>
          <div class="list-item__vals">
            <div class="list-item__data">
              {{ capacityDescription(item) }}
            </div>
            <a-progress
              v-if="showCapacityPercent(item)"
              status="normal"
              :percent="parseFloat(item.percentused)"
              :format="p => parseFloat(item.percentused).toFixed(2) + '%'" />
          </div>
          <a-collapse
              v-if="item.tagged"
              class="list-item__collapse"
              @change="handleCollapseChange(item.type)">
            <a-collapse-panel key="1" :header="$t('label.tagged') + ' ' + returnCapacityTitle(item.type) + (collpaseActive[item.type] ? ''  : ' - ' + item.tagsasstring)">
              <a-list
                size="small"
                :dataSource="item.tagged" >
                <template #renderItem="{ item }">
                  <a-list-item class="sub-list-item">
                    <div class="sub-list-item__container">
                      <div class="list-item__data list-item__title">{{ '#' + item.tag }}</div>
                      <div class="list-item__vals">
                        <div class="list-item__data">
                          {{ capacityDescription(item) }}
                        </div>
                        <a-progress
                          v-if="showCapacityPercent(item)"
                          status="normal"
                          :percent="parseFloat(item.percentused)"
                          :format="p => parseFloat(item.percentused).toFixed(2) + '%'" />
                      </div>
                    </div>
                  </a-list-item>
                </template>
              </a-list>
            </a-collapse-panel>
          </a-collapse>
        </div>
      </a-list-item>
    </a-list>
  </a-spin>
</template>

<script>
import { listRefreshMixin } from '@/utils/listRefreshMixin'

import { getAPI } from '@/api'

export default {
  mixins: [listRefreshMixin(['fetchData'])],
  name: 'Resources',
  props: {
    resource: {
      type: Object,
      required: true
    }
  },
  watch: {
    resource: {
      deep: true,
      handler (newItem, oldItem) {
        if (this.resource && this.resource.id) {
          this.fetchData()
        }
      }
    }
  },
  computed: {
    isZone () { return this.$route.meta.name === 'zone' }
  },
  data () {
    return {
      fetchLoading: false,
      resourcesList: [],
      collpaseActive: {}
    }
  },
  created () {
    this.fetchData()
  },
  methods: {
    fetchData () {
      const listRequest = this.listRequestToken('fetchData')
      const entity = this.$route.meta.name + 'id'
      const params = {}
      params[entity] = this.resource.id
      this.fetchLoading = !listRequest.loaded
      if (this.isZone && !listRequest.loaded) this.resourcesList = []
      return getAPI('listCapacity', params, { preserveOnFailure: this.isZone }).then(response => {
        if (!this.isListRequestCurrent('fetchData', listRequest)) return

        this.resourcesList = response.listcapacityresponse.capacity || []
        this.updateTaggedCapacities()
        if (!listRequest.loaded) this.animatePercentVals(this.resourcesList)
      }).catch(error => {
        if (!this.isListRequestCurrent('fetchData', listRequest)) return
        listRequest.failed = true
        this.listRefreshFailed = true
        if (listRequest.loaded || this.isZone) return

        this.$notifyError(error)
      }).finally(() => {
        if (!this.isListRequestCurrent('fetchData', listRequest)) return

        this.fetchLoading = false
      })
    },
    updateTaggedCapacities () {
      const resourcesListCopy = [...this.resourcesList]
      this.resourcesList = this.resourcesList.filter(x => !x.tag)
      this.resourcesList.forEach(resource => {
        const tagged = []
        const tags = []
        for (const x of resourcesListCopy) {
          if (resource.type === x.type && x.tag) {
            tagged.push(x)
            tags.push(x.tag)
          }
        }
        if (tagged.length > 0) {
          resource.tagged = tagged
          resource.tags = tags
          resource.tagsasstring = '#' + tags.join(', #')
        }
      })
    },
    animatePercentVals (resources) {
      resources.forEach(resource => {
        const percent = resource.percentused
        resource.percentused = 0
        setTimeout(() => {
          resource.percentused = percent
        }, 200)
      })
    },
    capacityNumber (value) {
      return value != null && value !== '' && Number.isFinite(Number(value)) && Number(value) >= 0
    },
    capacityDescription (item) {
      const { type, capacityused, capacitytotal } = item
      if (this.isZone) {
        if (!this.capacityNumber(capacityused) || !this.capacityNumber(capacitytotal)) return this.$t('label.zone.capacity.unmeasured')
        if (Number(type) === 19 && Number(capacitytotal) === 0) return this.$t('label.zone.capacity.unsupported')
      }
      const label = this.isZone ? this.$t([2, 6, 9, 11, 19].includes(Number(type)) ? 'label.used' : 'label.allocated') : 'Allocated'
      return label + ' ' + this.convertByType(type, capacityused) + ' / ' + this.convertByType(type, capacitytotal)
    },
    showCapacityPercent (item) {
      return !this.isZone || (this.capacityNumber(item.capacityused) && this.capacityNumber(item.capacitytotal) &&
        Number(item.capacitytotal) > 0 && this.capacityNumber(item.percentused))
    },
    convertBytes (val) {
      if (this.isZone) return this.$bytesToHumanReadableSize(Number(val))
      if (val < 1024 * 1024) return `${(val / 1024).toFixed(2)} KB`
      if (val < 1024 * 1024 * 1024) return `${(val / 1024 / 1024).toFixed(2)} MB`
      if (val < 1024 * 1024 * 1024 * 1024) return `${(val / 1024 / 1024 / 1024).toFixed(2)} GB`
      if (val < 1024 * 1024 * 1024 * 1024 * 1024) return `${(val / 1024 / 1024 / 1024 / 1024).toFixed(2)} TB`
      return val
    },
    convertHz (val) {
      if (this.isZone && val < 1000) return String(val) + ' MHz'
      if (val < 1000) return `${val} Mhz`
      return `${(val / 1000).toFixed(2)} GHz`
    },
    convertByType (type, val) {
      switch (Number(type)) {
        case 0: return this.convertBytes(val)
        case 1: return this.convertHz(val)
        case 2: return this.convertBytes(val)
        case 3: return this.convertBytes(val)
        case 6: return this.convertBytes(val)
        case 9: return this.convertBytes(val)
        case 11: return this.convertBytes(val)
        default: return val
      }
    },
    returnCapacityTitle (type) {
      switch (Number(type)) {
        case 0: return this.$t('label.memory')
        case 1: return this.$t('label.cpu')
        case 2: return this.$t('label.primary.storage.used')
        case 3: return this.$t('label.primary.storage.allocated')
        case 4: return this.$t('label.public.ips')
        case 5: return this.$t('label.management.ips')
        case 6: return this.$t('label.secondary.storage')
        case 7: return this.$t('label.vlan')
        case 8: return this.$t('label.direct.ips')
        case 9: return this.$t('label.local.storage')
        case 19: return this.$t('label.gpu')
        case 90: return this.$t('label.num.cpu.cores')
        default: return ''
      }
    },
    handleCollapseChange (type) {
      if (this.collpaseActive[type]) {
        this.collpaseActive[type] = null
        return
      }
      this.collpaseActive[type] = true
      var typeItems = this.resourcesList.filter(x => x.type === type)
      typeItems.forEach(resource => {
        this.animatePercentVals(resource.tagged)
      })
    }
  }
}
</script>

<style scoped lang="scss">
  .zone-capacity .list-item__data { white-space: normal; overflow-wrap: anywhere; }
  .list-item {

    &__container {
      max-width: 90%;
      width: 100%;

      @media (min-width: 760px) {
        max-width: 95%;
      }
    }

    &__title {
      font-weight: bold;
    }

    &__data {
      margin-right: 20px;
      white-space: nowrap;
    }

    &__vals {
      @media (min-width: 760px) {
        display: flex;
      }
    }
  }
  .sub-list-item {

    &__container {
      max-width: 90%;
      width: 100%;

      @media (min-width: 760px) {
        max-width: 95%;
      }
    }

    &__title {
      font-weight: bold;
    }

    &__data {
      margin-right: 20px;
      overflow-wrap: anywhere;
    }

    &__vals {
      margin-top: 10px;
      @media (min-width: 760px) {
        display: flex;
      }
    }
  }
</style>
