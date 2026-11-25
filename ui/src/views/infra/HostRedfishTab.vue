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
  <section class="host-redfish-content">
    <resource-table-toolbar :loading="spinning" @refresh="fetchData">
      <a-select id="host-redfish-category" class="redfish-select" :aria-label="$t('label.host.redfish.category')" :value="category" :options="categoryOptions" @change="chooseCategory" />
      <a-select v-if="sections.length" id="host-redfish-section" class="redfish-select" :aria-label="$t('label.host.redfish.section')" :value="selectedSection?.value" :options="sections" @change="chooseSection" />
    </resource-table-toolbar>
    <a-alert v-if="readError" type="error" show-icon>
      <template #message>
        <a-space wrap>
          <span>{{ $t('message.host.redfish.failed') }}</span>
          <a-button size="small" @click="fetchData">{{ $t('label.retry') }}</a-button>
        </a-space>
      </template>
    </a-alert>
    <a-spin :spinning="spinning && !hasContent" :tip="$t('message.redfishdata.loading')">
      <a-empty v-if="!hasContent && !spinning && !readError" />
    <section v-if="category === 'summary' && hasContent">
      <a-card :title="dataMap.model">
        <a-row v-for="(item, index) in dataMap['summary']" :key="index" :gutter="12" class="cus-row">
          <a-col :span="12"><strong>{{ $t('label.' + item.name) }}</strong></a-col>
          <a-col :span="12">
            <status class="status" :text="item.status" displayText/>
          </a-col>
        </a-row>
      </a-card>
    </section>
    <section v-if="category === 'processor' && hasContent">
      <a-list :grid="{ gutter: 16, xs: 1, sm: 1, lg: 2 }" :data-source="dataMap['processor']" >
        <template #renderItem="{ item }">
          <a-list-item>
            <a-card :title="item.name">
              <a-row class="cus-row">
                <a-col :span="12"><strong>{{ $t('label.manufacturer') }}</strong></a-col>
                <a-col :span="12">{{ item.manufacturer }}</a-col>
              </a-row>
              <a-row class="cus-row">
                <a-col :span="12"><strong>{{ $t('label.speed') }}</strong></a-col>
                <a-col :span="12">{{ item.speed }}</a-col>
              </a-row>
              <a-row class="cus-row">
                <a-col :span="12"><strong>{{ $t('label.status') }}</strong></a-col>
                <a-col :span="12">{{ item.status }}</a-col>
              </a-row>
              <a-row class="cus-row">
                <a-col :span="12"><strong>{{ $t('label.technology') }}</strong></a-col>
                <a-col :span="12">{{ item.technology }}</a-col>
              </a-row>
              <a-row class="cus-row">
                <a-col :span="12"><strong>{{ $t('label.l1cache') }}</strong></a-col>
                <a-col :span="12">{{ item.l1cache }}</a-col>
              </a-row>
              <a-row class="cus-row">
                <a-col :span="12"><strong>{{ $t('label.l2cache') }}</strong></a-col>
                <a-col :span="12">{{ item.l2cache }}</a-col>
              </a-row>
              <a-row class="cus-row">
                <a-col :span="12"><strong>{{ $t('label.l3cache') }}</strong></a-col>
                <a-col :span="12">{{ item.l3cache }}</a-col>
              </a-row>
            </a-card>
          </a-list-item>
        </template>
      </a-list>
    </section>
    <section v-if="category === 'memory' && hasContent">
      <div v-if="dataMap['memory'].memmorysummary">
        <a-divider>{{ $t('label.advanced.memory.protection') }}</a-divider>
        <a-row :gutter="[16,8]">
          <a-col :span="12">
            <a-card :title="$t('label.amp.status')">
              <a-row class="cus-row">
                <a-col :span="12"><strong>{{ $t('label.ampmodeactive') }}</strong></a-col>
                <a-col :span="12">{{ dataMap['memory'].memmorysummary?.ampmodeactive }}</a-col>
              </a-row>
              <a-row class="cus-row">
                <a-col :span="12"><strong>{{ $t('label.ampmodestatus') }}</strong></a-col>
                <a-col :span="12">{{ dataMap['memory'].memmorysummary?.ampmodestatus }}</a-col>
              </a-row>
            </a-card>
          </a-col>
          <a-col :span="12">
            <a-card :title="$t('label.supported.amp.modes')">
              <a-row v-for="(item, index) in dataMap['memory'].memmorysummary?.ampmodesupported" :key="index" :gutter="12" class="cus-row">
                <a-col :span="24">{{ item }}</a-col>
              </a-row>
            </a-card>
          </a-col>
        </a-row>
      </div>
      <div v-if="dataMap['memory'].memmorysummary">
        <a-divider>{{ $t('label.memory.summary') }}</a-divider>
          <a-table
            v-if="dataMap['memory']"
            size="small"
            :scroll="{ x: 'max-content' }"
            :columns="memorySummaryColumns"
            :dataSource="dataMap['memory'].memmorysummary?.memorylist"
            :pagination="false"
            :rowKey="record => record.id"
            :expandRowByClick="true">
            <template #bodyCell="{ column, record }">
              <template v-if="column.key === 'boardoperationalfrequency'">
                {{ attachSign(record.boardoperationalfrequency, 'MHz') }}
              </template>
              <template v-if="column.key === 'boardtotalmemorysize'">
                {{ attachSign((record.boardtotalmemorysize / 1024), 'GB') }}
              </template>
            </template>
          </a-table>
      </div>
      <a-divider>{{ $t('label.physical.memory') }}</a-divider>
      <a-table
        v-if="dataMap['memory']"
        size="small"
        :scroll="{ x: 'max-content' }"
        :columns="memoryListInfoColumns"
        :dataSource="dataMap['memory'].memlistinfo"
        :pagination="false"
        :rowKey="record => record.id"
        :expandRowByClick="true">
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'status'">
            <status class="status" :text="record.oem?.hpe?.dimmstatus || record.status?.health" displayText/>
          </template>
          <template v-if="column.key === 'maxoperatingspeedmts'">
            {{ attachSign((record.oem?.hpe?.maxoperatingspeedmts || record.operatingspeedmhz), 'MHz') }}
          </template>
          <template v-if="column.key === 'capacitymib'">
            {{ attachSign((record.capacitymib / 1024), 'GB') }}
          </template>
        </template>
         <template #expandedRowRender="{ record }">
          <a-card style="margin-left: 40px;">
            <a-row
              v-for="(value, key) in flattenObjToDotNotation(record)"
              :key="key"
              class="cus-row-2"
              justify="end"
              style="padding-top: 10px" >
              <a-col :span="12"><strong>{{ key }}</strong></a-col>
              <a-col :span="12">{{ value }}</a-col>
            </a-row>
          </a-card>
        </template>
      </a-table>
    </section>
    <section v-if="category === 'network' && hasContent">
      <a-list :grid="{ gutter: 8, column: 1 }" :dataSource="dataMap['network']">
        <template #renderItem="{item}">
          <a-card :title="item.model || 'UnKnown'" style="width: 99%; margin-bottom: 10px;" :bordered="true">
            <a-list-item>
              <a-row class="cus-row">
                <a-col :span="12"><strong>{{ $t('label.manufacturer') }}</strong></a-col>
                <a-col :span="12">{{ item.manufacturer }}</a-col>
              </a-row>
              <a-row class="cus-row">
                <a-col :span="12"><strong>{{ $t('label.location') }}</strong></a-col>
                <a-col :span="12">{{ item.location?.partlocation?.servicelabel }}</a-col>
              </a-row>
              <a-row class="cus-row">
                <a-col :span="12"><strong>{{ $t('label.partnumber') }}</strong></a-col>
                <a-col :span="12">{{ item.partnumber }}</a-col>
              </a-row>
              <a-row class="cus-row">
                <a-col :span="12"><strong>{{ $t('label.serialnumber') }}</strong></a-col>
                <a-col :span="12">{{ item.serialnumber }}</a-col>
              </a-row>
              <a-row class="cus-row">
                <a-col :span="12"><strong>{{ $t('label.firmware') }}</strong></a-col>
                <a-col :span="12">{{ item.controllers[0].firmwarepackageversion }}</a-col>
              </a-row>
              <a-row class="cus-row">
                <a-col :span="12"><strong>{{ $t('label.status') }}</strong></a-col>
                <a-col :span="12">
                  <status class="status" :text="item.status?.health" displayText/>
                </a-col>
              </a-row>
            </a-list-item>
            <a-list-item>
              <a-divider>{{ $t('label.port') }}</a-divider>
              <a-table
                size="small"
                :scroll="{ x: 'max-content' }"
                :columns="networkColumns"
                :dataSource="item.port"
                :pagination="false"
                :rowKey="record => record.id"
                :expandRowByClick="true">
                <template #bodyCell="{ column, record }">
                  <template v-if="column.key === 'associatednetworkaddresses'">
                    {{ record.ethernet?.associatedmacaddresses.join() || record.associatednetworkaddresses.join() }}
                  </template>
                  <template v-if="column.key === 'status'">
                    <status class="status" :text="record.status?.health || record.status?.healthrollup" displayText/>
                  </template>
                  <template v-if="column.key === 'category'">
                    {{ record.categories?.join(', ') }}
                  </template>
                </template>
              </a-table>
            </a-list-item>
          </a-card>
        </template>
      </a-list>
    </section>
    <section v-if="category === 'storage' && hasContent">
          <a-table
            v-if="selectedSection"
            size="small"
            :scroll="{ x: 'max-content' }"
            :columns="storageColumns"
            :dataSource="selectedSection.rows"
            :pagination="false"
            :rowKey="(record, index) => index"
            :expandRowByClick="true">
            <template #bodyCell="{ column, record }">
              <template v-if="column.key === 'status'">
                <status class="status" :text="record.status?.health || record.status?.state" displayText/>
              </template>
              <template v-if="column.key === 'capacitybytes'">
                {{ autoFormatBytes(record.capacitybytes) }}
              </template>
              <template v-if="column.key === 'location'">
                {{ typeof record.location === 'string' ? record.location : record.location?.partlocation?.servicelabel || '—' }}
              </template>
              <template v-if="column.key === 'predictedmedialifeleftpercent'">
                {{ attachSign(record.predictedmedialifeleftpercent, '%') }}
              </template>
            </template>
            <template #expandedRowRender="{ record }">
              <a-card style="margin-left: 40px;">
                <a-row
                  v-for="(value, key) in flattenObjToDotNotation(record)"
                  :key="key"
                  class="cus-row-2"
                  justify="end"
                  style="padding-top: 10px" >
                  <a-col :span="12"><strong>{{ key }}</strong></a-col>
                  <a-col :span="12">{{ value }}</a-col>
                </a-row>
              </a-card>
            </template>
          </a-table>
    </section>
    <section v-if="category === 'device' && hasContent">
      <a-table
        v-if="dataMap['device']"
        size="small"
        :scroll="{ x: 'max-content' }"
        :columns="deviceColumns"
        :dataSource="dataMap['device'].devicelist"
        :pagination="false"
        :rowKey="record => record.id"
        :expandRowByClick="true">
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'status'">
            <status class="status" :text="record.status?.health || record.status?.state" displayText/>
          </template>
          <template v-if="column.key === 'firmwareversion'">
            {{ record.firmwareversion?.current?.versionstring || record.firmwareversion}}
          </template>
        </template>
        <template #expandedRowRender="{ record }">
          <a-card style="margin-left: 40px;">
            <a-row
              v-for="(value, key) in flattenObjToDotNotation(record)"
              :key="key"
              class="cus-row-2"
              justify="end"
              style="padding-top: 10px" >
              <a-col :span="12"><strong>{{ key }}</strong></a-col>
              <a-col :span="12">{{ value }}</a-col>
            </a-row>
          </a-card>
        </template>
      </a-table>
    </section>
    <section v-if="category === 'firmware' && hasContent">
      <a-table
        v-if="dataMap['firmware']"
        size="small"
        :scroll="{ x: 'max-content' }"
        :columns="firmwareColumns"
        :dataSource="dataMap['firmware'].firmwarelist"
        :pagination="false"
        :rowKey="record => record.id"
        :expandRowByClick="true">
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'firmwareversion'">
            {{ record.lowestsupportedversion || record.version}}
          </template>
        </template>
        <template #expandedRowRender="{ record }">
          <a-card style="margin-left: 40px;">
            <a-row
              v-for="(value, key) in flattenObjToDotNotation(record)"
              :key="key"
              class="cus-row-2"
              justify="end"
              style="padding-top: 10px" >
              <a-col :span="12"><strong>{{ key }}</strong></a-col>
              <a-col :span="12">{{ value }}</a-col>
            </a-row>
          </a-card>
        </template>
      </a-table>
    </section>
    <section v-if="category === 'log' && hasContent">
          <a-table
            v-if="selectedSection"
            size="small"
            :scroll="{ x: 'max-content' }"
            :columns="logColumns"
            :dataSource="selectedSection.rows"
            :pagination="false"
            :rowKey="record => record.id"
            :expandRowByClick="true">
            <template #bodyCell="{ column, record }">
              <template v-if="column.key === 'associatednetworkaddresses'">
                {{ dateFmt(record.updated) }}
              </template>
              <template v-if="column.key === 'created'">
                {{ dateFmt(record.created) }}
              </template>
            </template>
            <template #expandedRowRender="{ record }">
              <a-card style="margin-left: 40px;">
                <a-row
                  v-for="(value, key) in flattenObjToDotNotation(record)"
                  :key="key"
                  class="cus-row-2"
                  justify="end"
                  style="padding-top: 10px" >
                  <a-col :span="12"><strong>{{ key }}</strong></a-col>
                  <a-col :span="12">{{ value }}</a-col>
                </a-row>
              </a-card>
            </template>
          </a-table>
    </section>
    </a-spin>
  </section>
</template>
<script>
import ResourceTableToolbar from '@/components/view/ResourceTableToolbar'

import { getAPI } from '@/api'
import Status from '@/components/widgets/Status'
const categories = {
  summary: 'label.details',
  processor: 'label.processor',
  memory: 'label.memory',
  network: 'label.network',
  storage: 'label.storage',
  device: 'label.devices',
  firmware: 'label.firmware',
  log: 'label.log'
}
const emptyData = () => ({ model: '', summary: [], processor: [], memory: {}, network: [], device: [], storage: {}, firmware: [], log: {} })

export default {
  name: 'HostRedfishTab',
  components: {
    Status,
    ResourceTableToolbar
  },
  props: {
    resource: {
      type: Object,
      required: true
    },
    resourceType: {
      type: String,
      required: true
    },
    loading: {
      type: Boolean,
      required: true
    }
  },
  watch: {
    category () { this.fetchData() },
    'resource.id' (value, previous) {
      if (value !== previous) {
        this.dataMap = emptyData()
        this.fetchData()
      }
    }
  },
  beforeUnmount () {
    this.readVersion++
  },
  data () {
    return {
      logColumns: [
        {
          key: 'id',
          title: this.$t('label.id'),
          dataIndex: 'id',
          width: '8%'
        },
        {
          key: 'message',
          title: this.$t('label.message'),
          dataIndex: 'message'
        },
        {
          key: 'created',
          title: this.$t('label.created'),
          dataIndex: 'created',
          width: '15%'
        }
      ],
      memorySummaryColumns: [
        {
          key: 'boardcpunumber',
          title: this.$t('label.boardcpunumber'),
          dataIndex: 'boardcpunumber'
        },
        {
          key: 'boardmemorytype',
          title: this.$t('label.boardmemorytype'),
          dataIndex: 'boardmemorytype'
        },
        {
          key: 'boardnumberofsockets',
          title: this.$t('label.boardnumberofsockets'),
          dataIndex: 'boardnumberofsockets'
        },
        {
          key: 'boardoperationalfrequency',
          title: this.$t('label.boardoperationalfrequency'),
          dataIndex: 'boardoperationalfrequency'
        },
        {
          key: 'boardtotalmemorysize',
          title: this.$t('label.boardtotalmemorysize'),
          dataIndex: 'boardtotalmemorysize'
        }
      ],
      memoryListInfoColumns: [
        {
          key: 'devicelocator',
          title: this.$t('label.devicelocator'),
          dataIndex: 'devicelocator'
        },
        {
          key: 'status',
          title: this.$t('label.status'),
          dataIndex: 'status',
          width: '10%'
        },
        {
          key: 'capacitymib',
          title: this.$t('label.capacitymib'),
          dataIndex: 'capacitymib'
        },
        {
          key: 'maxoperatingspeedmts',
          title: this.$t('label.maxoperatingspeedmts'),
          dataIndex: 'maxoperatingspeedmts'
        },
        {
          key: 'basemoduletype',
          title: this.$t('label.basemoduletype'),
          dataIndex: 'basemoduletype'
        },
        {
          key: 'manufacturer',
          title: this.$t('label.manufacturer'),
          dataIndex: 'manufacturer'
        },
        {
          key: 'memorydevicetype',
          title: this.$t('label.memorydevicetype'),
          dataIndex: 'memorydevicetype'
        }
      ],
      networkColumns: [
        {
          key: 'id',
          title: this.$t('label.id'),
          dataIndex: 'id'
        },
        {
          key: 'status',
          title: this.$t('label.status'),
          dataIndex: 'status',
          width: '10%'
        },
        {
          key: 'associatednetworkaddresses',
          title: this.$t('label.macaddress'),
          dataIndex: 'associatednetworkaddresses'
        },
        {
          key: 'linkstatus',
          title: this.$t('label.linkstatus'),
          dataIndex: 'linkstatus',
          width: '10%'
        }
      ],
      deviceColumns: [
        {
          key: 'manufacturer',
          title: this.$t('label.manufacturer'),
          dataIndex: 'manufacturer'
        },
        {
          key: 'name',
          title: this.$t('label.name'),
          dataIndex: 'name'
        },
        {
          key: 'firmwareversion',
          title: this.$t('label.firmware'),
          dataIndex: 'firmwareversion'
        },
        {
          key: 'status',
          title: this.$t('label.status'),
          dataIndex: 'status',
          width: '15%'

        }
      ],
      firmwareColumns: [
        {
          key: 'name',
          title: this.$t('label.name'),
          dataIndex: 'name'
        },
        {
          key: 'firmwareversion',
          title: this.$t('label.firmware'),
          dataIndex: 'firmwareversion'
        }
      ],
      storageContollerColumns: [
        {
          key: 'name',
          title: this.$t('label.name'),
          dataIndex: 'name'
        },
        {
          key: 'status',
          title: this.$t('label.status'),
          dataIndex: 'status',
          width: '10%'
        },
        {
          key: 'firmwareversion',
          title: this.$t('label.firmware'),
          dataIndex: 'firmwareversion'
        },
        {
          key: 'description',
          title: this.$t('label.description'),
          dataIndex: 'description'
        }
      ],
      storageVolumeColumns: [
        {
          key: 'name',
          title: this.$t('label.name'),
          dataIndex: 'name'
        },
        {
          key: 'status',
          title: this.$t('label.status'),
          dataIndex: 'status',
          width: '10%'
        },
        {
          key: 'capacitybytes',
          title: this.$t('label.capacity'),
          dataIndex: 'capacitybytes'
        },
        {
          key: 'volumetype',
          title: this.$t('label.volumetype'),
          dataIndex: 'volumetype'
        }
      ],
      storageDriveColumns: [
        {
          key: 'name',
          title: this.$t('label.name'),
          dataIndex: 'name'
        },
        {
          key: 'status',
          title: this.$t('label.status'),
          dataIndex: 'status',
          width: '10%'
        },
        {
          key: 'capacitybytes',
          title: this.$t('label.capacity'),
          dataIndex: 'capacitybytes'
        },
        {
          key: 'mediatype',
          title: this.$t('label.mediatype'),
          dataIndex: 'mediatype'
        },
        {
          key: 'predictedmedialifeleftpercent',
          title: this.$t('label.predictedmedialifeleftpercent'),
          dataIndex: 'predictedmedialifeleftpercent'
        }
      ],
      storageEncColumns: [
        {
          key: 'name',
          title: this.$t('label.name'),
          dataIndex: 'name'
        },
        {
          key: 'description',
          title: this.$t('label.description'),
          dataIndex: 'description'
        },
        {
          key: 'status',
          title: this.$t('label.status'),
          dataIndex: 'status',
          width: '10%'
        },
        {
          key: 'chassistype',
          title: this.$t('label.type'),
          dataIndex: 'chassistype'
        }
      ],
      dataMap: emptyData(),
      spinning: false,
      readError: false,
      readVersion: 0
    }
  },
  computed: {
    category () {
      const value = this.$route.query.hardware
      return typeof value === 'string' && Object.prototype.hasOwnProperty.call(categories, value) ? value : 'summary'
    },
    categoryOptions () {
      return Object.entries(categories).map(([value, label]) => ({ value, label: this.$t(label) }))
    },
    sections () {
      if (this.category === 'storage') {
        return Object.entries(this.dataMap.storage).filter(([, rows]) => Array.isArray(rows)).map(([value, rows]) => {
          const key = 'label.hardware.storage.' + value
          return { value, label: this.$te(key) ? this.$t(key) : this.camelToSentence(value), rows }
        })
      }
      if (this.category === 'log') {
        return (this.dataMap.log.loglist || []).map((item, index) => ({
          value: String(item.id ?? item.name ?? index),
          label: this.camelToSentence(item.name),
          rows: item.members || []
        }))
      }
      return []
    },
    selectedSection () {
      return this.sections.find(section => section.value === this.$route.query.hardwaresection) || this.sections[0]
    },
    storageColumns () {
      return {
        controllerlist: this.storageContollerColumns,
        volumelist: this.storageVolumeColumns,
        drivelist: this.storageDriveColumns,
        enclosurelist: this.storageEncColumns
      }[this.selectedSection?.value] || this.storageContollerColumns
    },
    hasContent () {
      if (['storage', 'log'].includes(this.category)) return this.sections.length > 0
      const data = this.dataMap[this.category]
      return (this.category === 'summary' && !!this.dataMap.model) || Object.keys(data || {}).length > 0
    }
  },
  created () { this.fetchData() },
  methods: {
    chooseCategory (hardware) {
      if (hardware === this.category) return
      const query = { ...this.$route.query, tab: 'hostredfishdata', hardware }
      delete query.hardwaresection
      this.$router.push({ query })
    },
    chooseSection (hardwaresection) {
      this.$router.push({ query: { ...this.$route.query, hardware: this.category, hardwaresection } })
    },
    camelToSentence (str) {
      if (!str) return str
      // 이미 공백(띄어쓰기)이 있으면 변환하지 않고 그대로 반환
      if (/\s/.test(str)) return str
      // CamelCase를 띄어쓰기 추가해서 변환
      const sentence = str
        .replace(/([a-z])([A-Z])/g, '$1 $2')
        .replace(/([A-Z])([A-Z][a-z])/g, '$1 $2')
        .toLowerCase()
      // 첫 글자만 대문자
      return sentence.charAt(0).toUpperCase() + sentence.slice(1)
    },
    flattenObjToDotNotation (obj, prefix = '') {
      const result = {}
      for (const [key, value] of Object.entries(obj)) {
        if (key.includes('@') || key.includes('#')) continue

        const newKey = prefix ? `${prefix}.${key}` : key

        if (Array.isArray(value)) {
          value.forEach((item, index) => {
            if (typeof item === 'object' && item !== null) {
              Object.assign(result, this.flattenObjToDotNotation(item, `${newKey}.${index}`))
            } else {
              result[`${newKey}.${index}`] = item
            }
          })
        } else if (typeof value === 'object' && value !== null) {
          Object.assign(result, this.flattenObjToDotNotation(value, newKey))
        } else {
          result[newKey] = value
        }
      }
      return result
    },
    autoFormatBytes (bytes) {
      if (typeof bytes !== 'number' || bytes < 0) return '0'
      const tb = bytes / (1024 ** 4)
      if (tb >= 1) {
        return Math.floor(tb) + ' TB'
      }
      const gb = bytes / (1024 ** 3)
      return Math.floor(gb) + ' GB'
    },
    attachSign (val, sign) {
      if (val &&
          val !== 0 &&
          val !== undefined &&
          val !== null &&
          val !== '') {
        return val + ' ' + sign
      }
      return ''
    },
    dateFmt (val) {
      // null, 빈문자, 타입체크
      if (!val || typeof val !== 'string' || val.trim() === '') return ''

      // 0000-00-00T00:00:00Z 등 빈 날짜 체크
      if (/^0{4}-0{2}-0{2}T/.test(val)) return ''

      const date = new Date(val)
      if (isNaN(date.getTime())) return ''

      // KST(+9) 변환
      const kstDate = new Date(date.getTime() + 9 * 60 * 60 * 1000)

      function pad (num) {
        return num.toString().padStart(2, '0')
      }

      const year = kstDate.getFullYear()
      const month = pad(kstDate.getMonth() + 1)
      const day = pad(kstDate.getDate())
      const hour = pad(kstDate.getHours())
      const min = pad(kstDate.getMinutes())
      const sec = pad(kstDate.getSeconds())

      return `${year}-${month}-${day} ${hour}:${min}:${sec}`
    },
    pad (num) {
      return num.toString().padStart(2, '0')
    },
    hasArrProp (arr, key, propName, ifnullval) {
      if (arr[key] &&
          arr[key][propName] !== undefined &&
          arr[key][propName] !== null &&
          arr[key][propName] !== '') {
        return arr[key][propName]
      }
      return ifnullval
    },
    hasObjProp (obj, propName, ifnullval) {
      if (obj &&
          obj[propName] !== undefined &&
          obj[propName] !== null &&
          obj[propName] !== '') {
        return obj[propName]
      }
      return ifnullval
    },
    keysToLowerCaseDeep (obj) {
      if (Array.isArray(obj)) {
        // 배열이면 배열 내부도 처리
        return obj.map(this.keysToLowerCaseDeep)
      } else if (obj && typeof obj === 'object') {
        return Object.keys(obj).reduce((acc, key) => {
          acc[key.toLowerCase()] = this.keysToLowerCaseDeep(obj[key])
          return acc
        }, {})
      }
      // 기본형(문자/숫자/불리언/null)은 그대로 반환
      return obj
    },
    async fetchData () {
      const hostid = this.resource.id
      if (!hostid || this.$route.query.tab !== 'hostredfishdata') return
      const category = this.category
      const version = ++this.readVersion
      const current = () => this.resource.id === hostid && this.category === category && this.readVersion === version
      this.spinning = true
      this.readError = false
      try {
        const response = await getAPI('listHostRedfishData', { category, hostid }, { preserveOnFailure: true })
        if (!current()) return
        const payload = response.listhostredfishdataresponse?.outofbandmanagement?.redfishdata
        const parsed = payload ? JSON.parse(payload) : {}
        const previous = this.dataMap[category]
        const previousModel = this.dataMap.model
        this.dataMap[category] = emptyData()[category]
        try {
          this.makeData(parsed, category)
        } catch (error) {
          this.dataMap[category] = previous
          this.dataMap.model = previousModel
          throw error
        }
      } catch {
        if (current()) this.readError = true
      } finally {
        if (current()) this.spinning = false
      }
    },
    makeData (obj, category) {
      if (obj) {
        obj = this.keysToLowerCaseDeep(obj)
        switch (category) {
          case 'summary':
            this.dataMap.model = obj.model
            if (obj?.oem?.hpe?.aggregatehealthstatus) {
              for (const [key, value] of Object.entries(obj?.oem?.hpe?.aggregatehealthstatus)) {
                if (value.status?.health) {
                  this.dataMap[category].push({ name: key, status: value.status?.health })
                } else {
                  this.dataMap[category].push({ name: key, status: value })
                }
              }
            } else if (obj?.oem?.dell?.dellsystem) {
              const dellSystem = obj?.oem?.dell?.dellsystem
              this.dataMap[category] = Object.entries(dellSystem)
                .filter(([key, value]) => key.toLowerCase().includes('status') && value !== null)
                .map(([key, value]) => ({ name: key, status: value }))
            }
            break
          case 'processor':
            for (const [key] of Object.entries(obj)) {
              let varL1cache
              let varL2cache
              let varL3cache
              if (obj[key].oem?.hpe?.cache) {
                varL1cache = this.hasObjProp(obj[key].oem?.hpe?.cache?.find(c => c.name === 'L1-Cache'), 'installedsizekb', '')
                varL2cache = this.hasObjProp(obj[key].oem?.hpe?.cache?.find(c => c.name === 'L2-Cache'), 'installedsizekb', '')
                varL3cache = this.hasObjProp(obj[key].oem?.hpe?.cache?.find(c => c.name === 'L3-Cache'), 'installedsizekb', '')
              } else if (obj[key].oem?.dell?.dellprocessor) {
                varL1cache = this.hasObjProp(obj[key].oem?.dell?.dellprocessor, 'cache1installedsizekb', '')
                varL2cache = this.hasObjProp(obj[key].oem?.dell?.dellprocessor, 'cache2installedsizekb', '')
                varL3cache = this.hasObjProp(obj[key].oem?.dell?.dellprocessor, 'cache3installedsizekb', '')
              }
              this.dataMap[category].push({
                name: this.hasArrProp(obj, key, 'model', 'Unknown'),
                manufacturer: this.hasArrProp(obj, key, 'manufacturer', 'Unknown'),
                status: this.hasObjProp(obj[key].status, 'health', 'Unknown'),
                speed: this.attachSign(this.hasArrProp(obj, key, 'maxspeedmhz', '0'), 'MHz'),
                technology: this.attachSign(this.hasArrProp(obj, key, 'totalcores', '0'), 'Cores;') + this.attachSign(this.hasArrProp(obj, key, 'totalthreads', '0'), 'threads'),
                l1cache: this.attachSign(varL1cache, 'KB'),
                l2cache: this.attachSign(varL2cache, 'KB'),
                l3cache: this.attachSign(varL3cache, 'KB')
              })
            }
            break
          case 'memory':
          case 'network':
          case 'device':
          case 'firmware':
            this.dataMap[category] = obj
            break
          case 'log':
            for (const item of obj.loglist || []) item.members = (item.members || []).sort((a, b) => b.id - a.id)
            this.dataMap[category] = obj
            break
          case 'storage':
            this.dataMap[category] = obj
            break
          default:
            break
        }
      }
    }
  }
}
</script>

<style scoped>
.host-redfish-content {
  min-width: 0;
  max-width: 100%;
}

.redfish-select {
  width: min(100%, 180px);
}

.host-redfish-content :deep(.ant-alert) {
  margin-bottom: 12px;
}

.cus-row {
  margin-bottom: 10px;
}

.cus-row-2 {
  margin-bottom: 2px;
}

.form {
  width: 80vw;

  @media (min-width: 500px) {
    min-width: 400px;
    width: 100%;
  }
}
</style>
