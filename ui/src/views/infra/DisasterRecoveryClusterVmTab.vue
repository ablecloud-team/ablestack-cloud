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
  <a-spin :spinning="loading">
    <div class="container">
      <div class="search-container">
        <a-input-search
          v-if="showSearch"
          style="width: 25vw;float: right;margin-bottom: 10px; z-index: 8;"
          :placeholder="$t('label.search')"
          v-model:value="filter"
          @search="handleSearch"
          v-focus="true" />
      </div>
      <div class="table-container">
        <a-card class="card">
          <template #title>
            {{ cardTitleA }}
            <a-tooltip :title="tooltipTitleA">
              <info-circle-outlined />
            </a-tooltip>
          </template>
          <a-table
              class="table"
              size="small"
              :columns="firshDrVmColumns"
              :dataSource="this.disasterrecoveryclustervmlist"
              :rowKey="item => item.id"
              :pagination="false"
          >
            <template #name="{record}">
              <router-link :to="{ path: '/vm/' + record.id }">{{ record.name }}</router-link>
            </template>
            <template #state="{text}">
              <status :text="text ? text : ''" displayText />
            </template>
          </a-table>
        </a-card>
        <a-card class="card">
          <template #title>
            {{ cardTitleB }}
            <a-tooltip :title="tooltipTitleB">
              <info-circle-outlined />
            </a-tooltip>
          </template>
        <a-table
              class="table"
              size="small"
              :columns="secDrVmColumns"
              :dataSource="this.disasterrecoveryclustervmlistsecond"
              :rowKey="item => item.id"
              :pagination="false"
          >
            <!-- <template #name="{record}">
              <router-link :to="{ path: '/vm/' + record.id }">{{ record.name }}</router-link>
            </template> -->
            <template #state="{text}">
              <status :text="text ? text : ''" displayText />
            </template>
          </a-table>
        </a-card>
      </div>
    </div>
    <div v-if="!defaultPagination" style="display: block; text-align: right; margin-top: 10px;">
      <a-pagination
        size="small"
        :current="options.page"
        :pageSize="options.pageSize"
        :total="itemCount"
        :showTotal="total => `${$t('label.showing')} ${Math.min(total, 1+((options.page-1)*options.pageSize))}-${Math.min(options.page*options.pageSize, total)} ${$t('label.of')} ${total} ${$t('label.items')}`"
        :pagination="{showSizeChanger: true, total: total}"
        :pageSizeOptions="['10', '20', '40', '80', '100']"
        @change="handleTableChange"
        @showSizeChange="handlePageSizeChange"
        showSizeChanger>
        <template #buildOptionText="props">
          <span>{{ props.value }} / {{ $t('label.page') }}</span>
        </template>
      </a-pagination>
    </div>
  </a-spin>
</template>

<script>
import { api } from '@/api'
import { mixinDevice } from '@/utils/mixin.js'
import ResourceLayout from '@/layouts/ResourceLayout'
import Status from '@/components/widgets/Status'
import DetailsTab from '@/components/view/DetailsTab'
import DesktopNicsTable from '@/views/network/DesktopNicsTable'
import ListResourceTable from '@/components/view/ListResourceTable'
import TooltipButton from '@/components/widgets/TooltipButton'
import AnnotationsTab from '@/components/view/AnnotationsTab.vue'
import EventsTab from '@/components/view/EventsTab.vue'

export default {
  name: 'DisasterRecoveryTab',
  components: {
    EventsTab,
    AnnotationsTab,
    ResourceLayout,
    DetailsTab,
    DesktopNicsTable,
    Status,
    ListResourceTable,
    TooltipButton
  },
  mixins: [mixinDevice],
  props: {
    resource: {
      type: Object,
      default: () => {}
    },
    apiName: {
      type: String,
      default: ''
    },
    routerlinks: {
      type: Function,
      default: () => { return {} }
    },
    params: {
      type: Object,
      default: () => {}
    },
    columns: {
      type: Array,
      required: true
    },
    showSearch: {
      type: Boolean,
      default: true
    },
    items: {
      type: Array,
      default: () => []
    }
  },
  inject: ['parentFetchData'],
  data () {
    return {
      loading: false,
      vm: {},
      disasterrecoveryclustervmlist: [],
      disasterrecoveryclustervmlistsecond: [],
      cardTitleA: '',
      cardTitleB: '',
      tooltipTitleA: '',
      tooltipTitleB: '',
      annotations: [],
      instances: [],
      totalStorage: 0,
      itemCount: 0,
      currentTab: 'details',
      showAddIpModal: false,
      loadingNic: false,
      filter: '',
      defaultPagination: false,
      options: {
        page: 1,
        pageSize: 10,
        keyword: null
      },
      min: '',
      max: '',
      dataResource: {},
      firshDrVmColumns: [
        {
          title: this.$t('label.name'),
          dataIndex: 'name',
          slots: { customRender: 'name' }
        },
        {
          title: this.$t('label.state'),
          dataIndex: 'state',
          slots: { customRender: 'state' }
        }
      ],
      secDrVmColumns: [
        {
          title: this.$t('label.name'),
          dataIndex: 'name',
          slots: { customRender: 'name' }
        },
        {
          title: this.$t('label.state'),
          dataIndex: 'state',
          slots: { customRender: 'state' }
        },
        {
          title: this.$t('label.dr.volume.status'),
          dataIndex: 'state',
          slots: { customRender: 'state' }
        }
      ]
    }
  },
  beforeCreate () {
    // this.form = this.$form.createForm(this)
    // this.apiParams = this.$getApiParams('addDesktopClusterIpRanges')
  },
  created () {
    // const userInfo = this.$store.getters.userInfo
    // if (!['Admin'].includes(userInfo.roletype) &&
    //   (userInfo.account !== this.resource.account || userInfo.domain !== this.resource.domain)) {
    //   this.controlVmColumns = this.controlVmColumns.filter(col => { return col.dataIndex !== 'hostname' })
    //   this.controlVmColumns = this.controlVmColumns.filter(col => { return col.dataIndex !== 'instancename' })
    // }
    // this.vm = this.resource
    const self = this
    this.dataResource = this.resource
    this.vm = this.dataResource
    this.fetchData()
    window.addEventListener('popstate', function () {
      self.setCurrentTab()
    })
  },
  watch: {
    resource: {
      deep: true,
      handler (newData, oldData) {
        if (newData !== oldData) {
          this.dataResource = newData
          this.vm = this.resource
          this.fetchData()
        }
      }
    },
    items: {
      deep: true,
      handler (newItem) {
        if (newItem) {
          this.dataSource = newItem
        }
      }
    },
    '$i18n.global.locale' (to, from) {
      if (to !== from) {
        this.fetchData()
      }
    },
    $route: function (newItem, oldItem) {
      this.setCurrentTab()
    }

  },
  mounted () {
    this.setCurrentTab()
    this.updateCardTitle(true)
  },
  methods: {
    setCurrentTab () {
      this.currentTab = this.$route.query.tab ? this.$route.query.tab : 'details'
    },
    updateCardTitle (condition) {
      const clusterType = this.resource.drclustertype
      // 클러스터 타입에 따라 테이블 타이틀 변경, primary일 경우 secondary 클러스터에서 사용자가 보는 경우이기 때문에 secondary를 왼쪽으로 위치 변경.
      if (clusterType === 'primary') {
        this.cardTitleA = this.$t('label.secondary.cluster.vm') // Change title based on condition
        this.cardTitleB = this.$t('label.primary.cluster.vm') // Change title based on condition
        this.tooltipTitleA = this.$t('message.secondary.cluster.vm')
        this.tooltipTitleB = this.$t('message.primary.cluster.vm')
      } else {
        this.cardTitleA = this.$t('label.primary.cluster.vm') // Reset title to default value
        this.cardTitleB = this.$t('label.secondary.cluster.vm') // Reset title to default value
        this.tooltipTitleA = this.$t('message.primary.cluster.vm')
        this.tooltipTitleB = this.$t('message.secondary.cluster.vm')
      }
    },
    handleChangeTab (e) {
      this.currentTab = e
      const query = Object.assign({}, this.$route.query)
      query.tab = e
      history.replaceState(
        {},
        null,
        '#' + this.$route.path + '?' + Object.keys(query).map(key => {
          return (
            encodeURIComponent(key) + '=' + encodeURIComponent(query[key])
          )
        }).join('&')
      )
    },
    fetchData () {
      this.itemCount = 0
      if (this.items && this.items.length > 0) {
        this.dataSource = this.items
        this.defaultPagination = {
          showSizeChanger: true,
          pageSizeOptions: this.mixinDevice === 'desktop' ? ['20', '50', '100', '200'] : ['10', '20', '50', '100', '200']
        }
        return
      }
      this.disasterrecoveryclustervmlist = this.resource.disasterrecoveryclustervmlist || []
      const keyword = this.options.keyword
      if (keyword) {
        this.disasterrecoveryclustervmlist = this.disasterrecoveryclustervmlist.filter(entry => {
          return entry.name.includes(keyword)
        })
      }
      this.itemCount = this.disasterrecoveryclustervmlist.length
      this.min = (Math.min(this.itemCount, 1 + ((this.options.page - 1) * this.options.pageSize)) - 1)
      this.max = Math.min(this.options.page * this.options.pageSize, this.itemCount)
      this.disasterrecoveryclustervmlist = this.disasterrecoveryclustervmlist.slice(this.min, this.max)
      // 임시 코드
      this.disasterrecoveryclustervmlistsecond = this.disasterrecoveryclustervmlist
    },
    fetchComments () {
      this.fetchLoading = true
      api('listAnnotations', { entityid: this.resource.id, entitytype: 'DISASTER_RECOVERY', annotationfilter: 'all' }).then(json => {
        if (json.listannotationsresponse && json.listannotationsresponse.annotation) {
          this.annotations = json.listannotationsresponse.annotation
        }
      }).catch(error => {
        this.$notifyError(error)
      }).finally(() => {
        this.fetchLoading = false
      })
    },
    showAddModal () {
      this.showAddIpModal = true
      this.form.setFieldsValue({
        gateway: [],
        netmask: [],
        startip: [],
        endip: []
      })
    },
    handleSearch (value) {
      this.filter = value
      this.options.page = 1
      this.options.pageSize = 10
      this.options.keyword = this.filter
      this.fetchData()
    },
    handleTableChange (page, pagesize) {
      this.options.page = page
      this.options.pageSize = pagesize
      this.fetchData()
    },
    handlePageSizeChange (page, pagesize) {
      this.options.page = 1
      this.options.pageSize = pagesize
      this.fetchData()
    },
    filteredData () {
      return this.jsonData.filter(entry => {
        return entry.name.includes(this.filter)
      })
    },
    closeModals () {
      this.showAddIpModal = false
    }
  }
}
</script>

<style lang="scss" scoped>
.page-header-wrapper-grid-content-main {
  width: 100%;
  height: 100%;
  min-height: 100%;
  transition: 0.3s;
  .vm-detail {
    .svg-inline--fa {
      margin-left: -1px;
      margin-right: 8px;
    }
    span {
      margin-left: 10px;
    }
    margin-bottom: 8px;
  }
}

.list {
  margin-top: 20px;

  &__item {
    display: flex;
    flex-direction: column;
    align-items: flex-start;

    @media (min-width: 760px) {
      flex-direction: row;
      align-items: center;
    }
  }
}

.modal-form {
  display: flex;
  flex-direction: column;

  &__label {
    margin-top: 20px;
    margin-bottom: 5px;
    font-weight: bold;

    &--no-margin {
      margin-top: 0;
    }
  }
}

.actions {
  display: flex;
  flex-wrap: wrap;

  button {
    padding: 5px;
    height: auto;
    margin-bottom: 10px;
    align-self: flex-start;

    &:not(:last-child) {
      margin-right: 10px;
    }
  }

}

.label {
  font-weight: bold;
}

.attribute {
  margin-bottom: 10px;
}

.ant-tag {
  padding: 4px 10px;
  height: auto;
}

.title {
  display: flex;
  flex-wrap: wrap;
  justify-content: space-between;
  align-items: center;

  a {
    margin-right: 30px;
    margin-bottom: 10px;
  }

  .ant-tag {
    margin-bottom: 10px;
  }

  &__details {
    display: flex;
  }

  .tags {
    margin-left: 10px;
  }

}

.ant-list-item-meta-title {
  margin-bottom: -10px;
}

.divider-small {
  margin-top: 20px;
  margin-bottom: 20px;
}

.list-item {

  &:not(:first-child) {
    padding-top: 25px;
  }

}
</style>

<style scoped>
.wide-modal {
  min-width: 50vw;
}

/deep/ .ant-list-item {
  padding-top: 12px;
  padding-bottom: 12px;
}

.container {
  display: flex;
  flex-direction: column;
}
.search-container {
  float: right;
}
.table-container {
  display: flex;
  justify-content: space-between; /* Adjust as needed */
}
.table {
  overflow-y: auto;
}
.card {
  width: calc(50% - 5px);
}
.edge-bordered-table {
    border-collapse: collapse;
}
.edge-bordered-table td,
.edge-bordered-table th {
    border: 1px solid #e8e8e8; /* Adjust the color and width of the border as needed */
}
.edge-bordered-table tr:first-child td,
.edge-bordered-table tr:first-child th,
.edge-bordered-table tr:last-child td,
.edge-bordered-table tr:last-child th {
    border-top: none;
}
.edge-bordered-table tr td:first-child,
.edge-bordered-table tr th:first-child {
    border-left: none;
}
.edge-bordered-table tr td:last-child,
.edge-bordered-table tr th:last-child {
    border-right: none;
}

</style>
