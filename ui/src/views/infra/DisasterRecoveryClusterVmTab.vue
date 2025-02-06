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
            :columns="priDrVmColumns"
            :dataSource="this.priList"
            :rowKey="item => item.priVmName"
            :pagination="false"
          >
            <template #name="{record}">
              <router-link :to="{ path: '/vm/' + record.priVmId }">{{ record.priVmName }}</router-link>
            </template>
            <template #state="{text}">
              <status :text="text ? text : ''" displayText />
            </template>
            <template #expandedRowRender="{ record }">
              <a-table
                style="margin: 10px 0;"
                :columns="priColumns"
                :dataSource="this.priVol.filter((row) => row.priVmName === record.priVmName)"
                :pagination="false"
                :bordered="true"
                :rowKey="record.priVmName">
                <template #bodyCell="{ column, text }">
                  <template v-if="column.key === 'state'">
                    <status :text="text ? text : ''" displayText />
                  </template>
                </template>
              </a-table>
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
          :dataSource="this.secList"
          :rowKey="item => item.secVmName"
          :pagination="false">
            <template #state="{text}">
              <status :text="text ? text : ''" displayText />
            </template>
            <template #expandedRowRender="{ record }">
              <a-table
                style="margin: 10px 0;"
                :columns="secColumns"
                :dataSource="this.secVol.filter((row) => row.secVmName === record.secVmName)"
                :pagination="false"
                :bordered="true"
                :rowKey="record.secVmName">
                <template #bodyCell="{ column, text }">
                  <template v-if="column.key === 'state'">
                    <status :text="text ? text : ''" displayText />
                  </template>
                </template>
              </a-table>
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
import { mixinDevice } from '@/utils/mixin.js'
import { api } from '@/api'
import Status from '@/components/widgets/Status'

export default {
  name: 'DisasterRecoveryClusterVmTab',
  components: {
    Status
  },
  mixins: [mixinDevice],
  props: {
    resource: {
      type: Object,
      default: () => {}
    },
    columns: {
      type: Array,
      default: () => []
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
      priList: [],
      secList: [],
      drCluster: [],
      priVol: [],
      secVol: [],
      cardTitleA: '',
      cardTitleB: '',
      tooltipTitleA: '',
      tooltipTitleB: '',
      annotations: [],
      itemCount: 0,
      currentTab: 'details',
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
      priDrVmColumns: [
        {
          title: this.$t('label.name'),
          dataIndex: 'priVmName',
          slots: { customRender: 'name' }
        },
        {
          title: this.$t('label.state'),
          dataIndex: 'priVmStatus',
          slots: { customRender: 'state' }
        }
      ],
      secDrVmColumns: [
        {
          title: this.$t('label.name'),
          dataIndex: 'secVmName',
          slots: { customRender: 'name' }
        },
        {
          title: this.$t('label.state'),
          dataIndex: 'secVmStatus',
          slots: { customRender: 'state' }
        }
      ],
      priColumns: [
        {
          key: 'type',
          title: this.$t('label.dr.volume.type'),
          dataIndex: 'priVolType'
        },
        {
          key: 'state',
          title: this.$t('label.dr.volume.status'),
          dataIndex: 'priVolStatus'
        }
      ],
      secColumns: [
        {
          key: 'type',
          title: this.$t('label.dr.volume.type'),
          dataIndex: 'secVolType'
        },
        {
          key: 'state',
          title: this.$t('label.dr.volume.status'),
          dataIndex: 'secVolStatus'
        }
      ]
    }
  },
  created () {
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
    fetchData () {
      this.priVol = []
      this.secVol = []
      this.drCluster = []
      this.itemCount = 0
      if (this.items && this.items.length > 0) {
        this.dataSource = this.items
        this.defaultPagination = {
          showSizeChanger: true,
          pageSizeOptions: this.mixinDevice === 'desktop' ? ['20', '50', '100', '200'] : ['10', '20', '50', '100', '200']
        }
        return
      }
      this.loading = true
      api('getDisasterRecoveryClusterList', { name: this.resource.name }).then(json => {
        this.drCluster = json.getdisasterrecoveryclusterlistresponse.disasterrecoverycluster
        this.disasterrecoveryclustervmlist = this.drCluster[0].drclustervmmap || []
        for (const clusterVm of this.disasterrecoveryclustervmlist) {
          if (this.priList.length === 0) {
            this.priList.push({ priVmName: clusterVm.drclustervmname, priVmId: clusterVm.drclustervmid, priVmStatus: clusterVm.drclustervmstatus })
            this.secList.push({ secVmName: clusterVm.drclustermirrorvmname, secVmStatus: clusterVm.drclustermirrorvmstatus })
            this.priVol.push({ priVolType: clusterVm.drclustermirrorvmvoltype, priVolStatus: clusterVm.drclustervmvolstatus, priVmName: clusterVm.drclustervmname })
            this.secVol.push({ secVolType: clusterVm.drclustermirrorvmvoltype, secVolStatus: clusterVm.drclustermirrorvmvolstatus, secVmName: clusterVm.drclustermirrorvmname })
          } else {
            if (!this.priList.some(entry => entry.priVmName.includes(clusterVm.drclustervmname))) {
              this.priList.push({ priVmName: clusterVm.drclustervmname, priVmId: clusterVm.drclustervmid, priVmStatus: clusterVm.drclustervmstatus })
              this.secList.push({ secVmName: clusterVm.drclustermirrorvmname, secVmStatus: clusterVm.drclustermirrorvmstatus })
            }
            this.priVol.push({ priVolType: clusterVm.drclustermirrorvmvoltype, priVolStatus: clusterVm.drclustervmvolstatus, priVmName: clusterVm.drclustervmname })
            this.secVol.push({ secVolType: clusterVm.drclustermirrorvmvoltype, secVolStatus: clusterVm.drclustermirrorvmvolstatus, secVmName: clusterVm.drclustermirrorvmname })
          }
          this.itemCount = this.priList.length
          this.min = (Math.min(this.itemCount, 1 + ((this.options.page - 1) * this.options.pageSize)) - 1)
          this.max = Math.min(this.options.page * this.options.pageSize, this.itemCount)
          this.priList = this.priList.slice(this.min, this.max)
          this.secList = this.secList.slice(this.min, this.max)
          const keyword = this.options.keyword
          if (keyword) {
            this.priList = this.priList.filter(entry => {
              return entry.priVmName.includes(keyword)
            })
            this.secList = this.secList.filter(entry => {
              return entry.secVmName.includes(keyword)
            })
          }
        }
      }).finally(() => {
        this.loading = false
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

:deep(.ant-list-item) {
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
