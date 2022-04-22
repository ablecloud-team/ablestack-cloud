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
    <a-tabs
      :activeKey="currentTab"
      :tabPosition="device === 'mobile' ? 'top' : 'left'"
      :animated="false"
      @change="handleChangeTab">
      <a-tab-pane :tab="$t('label.details')" key="details">
        <DetailsTab :resource="resource" :loading="loading" />
      </a-tab-pane>
      <a-tab-pane :tab="$t('label.networks')" key="desktopnetworks" >
        <DesktopNicsTable :resource="desktopnetworks" :loading="loading"/>
      </a-tab-pane>
      <a-tab-pane :tab="$t('label.iprange')" key="iprange" v-if="'listDesktopClusterIpRanges' in $store.getters.apis && resource.networktype =='L2'">
        <a-button
          type="dashed"
          style="width: 100%; margin-bottom: 10px"
          @click="showAddModal"
          :loading="loadingNic"
          :disabled="!('addDesktopClusterIpRanges' in $store.getters.apis)">
          <PlusOutlined /> {{ $t('label.add.ip.range') }}
        </a-button>
        <a-table
          class="table"
          size="small"
          :columns="iprangeColumns"
          :dataSource="iprange"
          :rowKey="item => item.id"
          :pagination="false">
          <template #action="{record}">
            <a-popconfirm
              :title="$t('message.confirm.remove.ip.range')"
              @confirm="removeIpRange(record.id)"
              :okText="$t('label.yes')"
              :cancelText="$t('label.no')" >
              <tooltip-button
                tooltipPlacement="bottom"
                :tooltip="$t('label.action.delete.ip.range')"
                type="danger"
                icon="delete" />
            </a-popconfirm>
          </template>
        </a-table>
      </a-tab-pane>
      <a-tab-pane :tab="$t('label.controlvm')" key="instances">
        <a-table
          class="table"
          size="small"
          :columns="controlVmColumns"
          :dataSource="this.controlvms"
          :rowKey="item => item.id"
          :pagination="false"
        >
          <template #name="{record}">
            <router-link :to="{ path: '/vm/' + record.id }">{{ record.name }}</router-link>
          </template>
          <template #state="{text}">
            <status :text="text ? text : ''" displayText />
          </template>
          <template #instancename="{text}">
            <status :text="text ? text : ''" />{{ text }}
          </template>
          <template #hostname="{record}">
            <router-link :to="{ path: '/host/' + record.hostid }">{{ record.hostname }}</router-link>
          </template>
        </a-table>
      </a-tab-pane>
      <a-tab-pane :tab="$t('label.desktopvm')" key="desktops">
        <a-table
          class="table"
          size="small"
          :columns="desktopVmColumns"
          :dataSource="this.desktopvms"
          :rowKey="item => item.id"
          :pagination="false"
        ><template #name="{record}">
            <router-link :to="{ path: '/vm/' + record.id }">{{ record.name }}</router-link>
          </template>
          <template #state="{text}">
            <status :text="text ? text : ''" displayText />
          </template>
          <template #instancename="{text}">
            <status :text="text ? text : ''" />{{ text }}
          </template>
          <template #ipaddress="{text}">
            <status :text="text ? text : ''" />{{ text }}
          </template>
          <template #hostname="{record}">
            <router-link :to="{ path: '/host/' + record.hostid }">{{ record.hostname }}</router-link>
          </template>
        </a-table>
      </a-tab-pane>
    </a-tabs>

    <a-modal
      :visible="showAddIpModal"
      :title="$t('label.desktop.cluster.add.ip.range')"
      :closable="true"
      :maskClosable="false"
      :okText="$t('label.ok')"
      :cancelText="$t('label.cancel')"
      @cancel="closeModals"
      @ok="submitAddIp">
      <a-spin :spinning="loadingNic">
        <!-- <a-form :form="form" @submit="submitAddIp" layout="vertical">
          <a-form-item>
            <template #label>
              <tooltip-label :title="$t('label.gateway')" :tooltip="$t('placeholder.gateway')"/>
            </template>
            <a-input
              v-decorator="['gateway', {
                rules: [{ required: true, message: $t('message.error.required.input') }]
              }]"
              :placeholder="$t('placeholder.gateway')" />
          </a-form-item>
          <a-form-item>
            <template #label>
              <tooltip-label :title="$t('label.netmask')" :tooltip="$t('placeholder.netmask')"/>
            </template>
            <a-input
              v-decorator="['netmask', {
                rules: [{ required: true, message: $t('message.error.required.input') }]
              }]"
              :placeholder="$t('placeholder.netmask')" />
          </a-form-item>
          <a-form-item>
            <template #label>
              <tooltip-label :title="$t('label.startip')" :tooltip="$t('placeholder.startip')"/>
            </template>
            <a-input
              v-decorator="['startip', {
                rules: [{ required: true, message: $t('message.error.required.input') }]
              }]"
              :placeholder="$t('placeholder.startip')" />
          </a-form-item>
          <a-form-item>
            <template #label>
              <tooltip-label :title="$t('label.endip')" :tooltip="$t('placeholder.endip')"/>
            </template>
            <a-input
              v-decorator="['endip', {
                rules: [{ required: true, message: $t('message.error.required.input') }]
              }]"
              :placeholder="$t('placeholder.endip')" />
          </a-form-item>
        </a-form> -->
      </a-spin>
    </a-modal>
  </a-spin>
</template>

<script>
import { ref } from 'vue'
import { api } from '@/api'
import { mixinDevice } from '@/utils/mixin.js'
import ResourceLayout from '@/layouts/ResourceLayout'
import Status from '@/components/widgets/Status'
import DetailsTab from '@/components/view/DetailsTab'
import DesktopNicsTable from '@/views/network/DesktopNicsTable'
import ListResourceTable from '@/components/view/ListResourceTable'
import TooltipButton from '@/components/widgets/TooltipButton'

export default {
  name: 'AutomationTab',
  components: {
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
      required: true
    },
    loading: {
      type: Boolean,
      default: false
    }
  },
  inject: ['parentFetchData'],
  data () {
    return {
      vm: ref({}),
      desktopnetworks: [],
      instances: [],
      desktops: [],
      iprange: [],
      totalStorage: 0,
      currentTab: 'details',
      showAddIpModal: false,
      loadingNic: false,
      secondaryIPs: [],
      selectedNicId: '',
      controlVmColumns: [
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
          title: this.$t('label.instancename'),
          dataIndex: 'instancename'
        },
        {
          title: this.$t('label.ip'),
          dataIndex: 'ipaddress'
        },
        {
          title: this.$t('label.hostid'),
          dataIndex: 'hostname',
          slots: { customRender: 'hostname' }
        }
      ],
      editNicResource: {},
      desktopVmColumns: [
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
          title: this.$t('label.instancename'),
          dataIndex: 'instancename'
        },
        {
          title: this.$t('label.ip'),
          dataIndex: 'ipaddress'
        },
        {
          title: this.$t('label.hostid'),
          dataIndex: 'hostname',
          slots: { customRender: 'hostname' }
        }
      ],
      iprangeColumns: [
        {
          title: this.$t('label.gateway'),
          dataIndex: 'gateway',
          slots: { customRender: 'gateway' }
        },
        {
          title: this.$t('label.netmask'),
          dataIndex: 'netmask'
        },
        {
          title: this.$t('label.startip'),
          dataIndex: 'startip'
        },
        {
          title: this.$t('label.endip'),
          dataIndex: 'endip'
        },
        {
          title: '',
          slots: { customRender: 'action' }
        }
      ]
    }
  },
  beforeCreate () {
    // this.form = this.$form.createForm(this)
    this.apiParams = this.$getApiParams('addDesktopClusterIpRanges')
  },
  created () {
    const userInfo = this.$store.getters.userInfo
    if (!['Admin'].includes(userInfo.roletype) &&
      (userInfo.account !== this.resource.account || userInfo.domain !== this.resource.domain)) {
      this.desktopVmColumns = this.desktopVmColumns.filter(col => { return col.dataIndex !== 'hostname' })
      this.controlVmColumns = this.controlVmColumns.filter(col => { return col.dataIndex !== 'hostname' })
    }
    this.vm = this.resource
    this.fetchData()
  },
  watch: {
    resource: function (newItem, oldItem) {
      this.vm = newItem
      this.fetchData()
    },
    $route: function (newItem, oldItem) {
      this.setCurrentTab()
    }
  },
  mounted () {
    this.setCurrentTab()
  },
  methods: {
    setCurrentTab () {
      this.currentTab = this.$route.query.tab ? this.$route.query.tab : 'details'
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
      this.desktopvms = this.resource.desktopvms || []
      this.desktopvms.map(x => { x.ipaddress = x.nic[0].ipaddress })
      this.controlvms = this.resource.controlvms || []
      this.controlvms.map(x => { x.ipaddress = x.nic[0].ipaddress })
      this.desktopnetworks = []
      this.iprange = []
      if (!this.vm || !this.vm.id) {
        return
      }
      api('listNetworks', { id: this.resource.networkid }).then(json => {
        this.desktopnetworks = json.listnetworksresponse.network
        if (this.desktopnetworks) {
          this.desktopnetworks.sort((a, b) => { return a.deviceid - b.deviceid })
        }
        // this.$set(this.resource, 'desktopnetworks', this.desktopnetworks)
      }).finally(() => {
      })
      api('listDesktopClusterIpRanges', { listall: true, desktopclusterid: this.resource.id }).then(json => {
        this.iprange = json.listdesktopclusteriprangesresponse.desktopclusteriprange
        if (this.iprange) {
          this.iprange.sort((a, b) => { return a.deviceid - b.deviceid })
        }
        // this.$set(this.resource, 'iprange', this.iprange)
      })
    },
    removeIpRange (id) {
      api('deleteDesktopClusterIpRanges', { id: id }).then(json => {
      }).finally(() => {
        this.fetchData()
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
    closeModals () {
      this.showAddIpModal = false
    },
    submitAddIp (e) {
      e.preventDefault()
      this.form.validateFields((err, values) => {
        if (err) {
          return
        }
        const params = {}
        params.desktopclusterid = this.resource.id
        params.netmask = values.netmask
        params.gateway = values.gateway
        params.startip = values.startip
        params.endip = values.endip
        this.showAddIpModal = false
        this.loadingNic = true
        api('addDesktopClusterIpRanges', params).then(response => {
          this.$pollJob({
            jobId: response.adddesktopclusteriprangesresponse.jobid,
            successMessage: this.$t('message.success.add.desktop.ip'),
            successMethod: () => {
              this.loadingNic = false
              this.closeModals()
              this.parentFetchData()
            },
            errorMessage: this.$t('message.add.desktop.ip.failed'),
            errorMethod: () => {
              this.loadingNic = false
              this.closeModals()
              this.parentFetchData()
            },
            loadingMessage: this.$t('message.add.desktop.ip.processing'),
            catchMessage: this.$t('error.fetching.async.job.result'),
            catchMethod: () => {
              this.loadingNic = false
              this.closeModals()
              this.parentFetchData()
            }
          })
        }).catch(error => {
          this.$notifyError(error)
          this.loadingNic = false
        })
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

/deep/ .ant-list-item {
  padding-top: 12px;
  padding-bottom: 12px;
}
</style>
