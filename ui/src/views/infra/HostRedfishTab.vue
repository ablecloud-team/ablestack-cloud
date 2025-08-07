<template>
  <a-spin :spinning="spinning" :tip="$t('message.redfishdata.loading')">
  <a-tabs
    :activeKey="category"
    :tabPosition="device === 'mobile' ? 'top' : 'left'"
    :animated="true"
    @change="onTabChange">
    <a-tab-pane :tab="$t('label.details')" key="summary">
      <a-card :title="dataMap.model">
        <a-row v-for="(item, index) in dataMap['summary']" :key="index" :gutter="12" class="cus-row">
          <a-col :span="12"><strong>{{ $t('label.' + item.name) }}</strong></a-col>
          <a-col :span="12">
            <status class="status" :text="item.status" displayText/>
          </a-col>
        </a-row>
      </a-card>
    </a-tab-pane>
    <a-tab-pane :tab="$t('label.processor')" key="processor">
      <a-list :grid="{ gutter: 16, column: 2 }" :data-source="dataMap['processor']" >
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
    </a-tab-pane>
    <a-tab-pane :tab="$t('label.memory')" key="memory">
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
            style="overflow-y: auto"
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
        style="overflow-y: auto"
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
    </a-tab-pane>
    <a-tab-pane :tab="$t('label.network')" key="network">
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
                style="overflow-y: auto"
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
    </a-tab-pane>
    <a-tab-pane :tab="$t('label.storage')" key="storage">
      <a-tabs
        v-model:activeKey="storageDataType"
        @update:activeKey="changeStorageTab"
        type="card">
        <a-tab-pane
          v-for="item, index in dataMap['storage']"
          :key="index"
          :tab="$t('label.hardware.storage.' + index)">
          <a-table
            v-if="item"
            size="small"
            style="overflow-y: auto"
            :columns="storageColumns"
            :dataSource="item"
            :pagination="false"
            :rowKey="record => record.id"
            :expandRowByClick="true">
            <template #bodyCell="{ column, record }">
              <template v-if="column.key === 'status'">
                <status class="status" :text="record.status?.health || record.status?.state" displayText/>
              </template>
              <template v-if="column.key === 'capacitybytes'">
                {{ autoFormatBytes(record.capacitybytes) }}
              </template>
              <template v-if="column.key === 'location'">
                {{ PartLocation }}
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
        </a-tab-pane>
      </a-tabs>
    </a-tab-pane>
    <a-tab-pane :tab="$t('label.devices')" key="device">
      <a-table
        v-if="dataMap['device']"
        size="small"
        style="overflow-y: auto"
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
    </a-tab-pane>
    <a-tab-pane :tab="$t('label.firmware')" key="firmware">
      <a-table
        v-if="dataMap['firmware']"
        size="small"
        style="overflow-y: auto"
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
    </a-tab-pane>
    <a-tab-pane :tab="$t('label.log')" key="log">
      <a-tabs
        v-model:activeKey="logDataType"
        type="card">
        <a-tab-pane
          v-for="item, index in dataMap['log'].loglist"
          :key="index"
          :tab="camelToSentence(item.name)">
          <a-table
            v-if="item"
            size="small"
            style="overflow-y: auto"
            :columns="logColumns"
            :dataSource="item.members"
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
              <template v-if="column.key === 'category'">
                {{ record.categories?.join(', ') }}
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
        </a-tab-pane>
      </a-tabs>
    </a-tab-pane>
  </a-tabs>
  </a-spin>
</template>
<script>
import { api } from '@/api'
import dayjs from 'dayjs'
import Status from '@/components/widgets/Status'
export default {
  name: 'HostRedfishTab',
  components: {
    Status
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
    resource: {
      handler () {
        this.fetchData()
      }
    }
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
      dataMap: {
        model: '',
        summary: [],
        processor: [],
        memory: [],
        network: [],
        device: [],
        storage: [],
        firmware: [],
        log: []
      },
      storageColumns: [],
      storageData: [],
      logData: [],
      logDataType: 0,
      storageDataType: 'controllerlist',
      category: 'summary',
      spinning: false
    }
  },
  computed: {
    filteredColumns () {
      return this.columns.filter(col =>
        this.dataMap[this.category].some(row =>
          row[col.dataIndex] !== undefined && row[col.dataIndex] !== null && row[col.dataIndex] !== ''
        )
      )
    }
  },
  created () {
    this.fetchData()
  },
  methods: {
    onTabChange (tabkey) {
      this.category = tabkey
      if (!this.checkExsitData(tabkey)) {
        this.fetchData()
      }
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
    changeStorageTab (val) {
      switch (val) {
        case 'controllerlist':
          this.storageColumns = this.storageContollerColumns
          break
        case 'volumelist':
          this.storageColumns = this.storageVolumeColumns
          break
        case 'drivelist':
          this.storageColumns = this.storageDriveColumns
          break
        case 'enclosurelist':
          this.storageColumns = this.storageEncColumns
          break
        default:
          break
      }
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
    checkExsitData (param) {
      const data = this.dataMap[param]
      if (Array.isArray(data)) {
        return data.length > 0
      }
      // 객체 체크 (null 제외, 실제 프로퍼티가 1개 이상 있는 경우)
      if (data && typeof data === 'object') {
        // Object.keys(data).length > 0 이면 true
        return Object.keys(data).length > 0
      }
      return false
    },
    dateFmt (val) {
      return dayjs(val).format('YYYY-MM-DD HH:mm:ss')
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
    fetchData () {
      this.spinning = true
      this.dataMap[this.category] = []
      api('listHostRedfishData', { category: this.category, hostid: this.resource.id }).then(json => {
        var items = json.listhostredfishdataresponse.outofbandmanagement
        this.jsonObject = JSON.parse(items.redfishdata)
      }).finally(() => {
        this.makeData(this.jsonObject)
        this.spinning = false
      })
    },
    makeData (obj) {
      if (obj) {
        obj = this.keysToLowerCaseDeep(obj)
        // console.log('obj :>> ', obj)
        switch (this.category) {
          case 'summary':
            this.dataMap.model = obj.model
            if (obj?.oem?.hpe?.aggregatehealthstatus) {
              for (const [key, value] of Object.entries(obj?.oem?.hpe?.aggregatehealthstatus)) {
                if (value.status?.health) {
                  this.dataMap[this.category].push({ name: key, status: value.status?.health })
                } else {
                  this.dataMap[this.category].push({ name: key, status: value })
                }
              }
            } else if (obj?.oem?.dell?.dellsystem) {
              const dellSystem = obj?.oem?.dell?.dellsystem
              this.dataMap[this.category] = Object.entries(dellSystem)
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
              this.dataMap[this.category].push({
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
          case 'log':
            this.dataMap[this.category] = obj
            break
          case 'storage':
            this.storageColumns = this.storageContollerColumns
            this.dataMap[this.category] = obj
            break
          default:
            break
        }
      }
      // console.log(this.category + ' :>> ' + this.dataMap[this.category])
    }
  }
}
</script>

<style scoped>
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
