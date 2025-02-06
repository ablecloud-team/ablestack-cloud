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

      <a-tab-pane :tab="$t('label.packages')" key="units">
        <a-table
          class="table"
          size="small"
          :columns="deployedResourceColumns"
          :dataSource="this.deployedunitservices"
          :rowKey="item => item.id"
          :pagination="false"
        >
          <template #state="{text}">
            <status :text="text ? text : ''" displayText />
          </template>
          <template #created="{ text }">
            {{ $toLocaleDate(text) }}
          </template>
        </a-table>
      </a-tab-pane>

      <a-tab-pane :tab="$t('label.instances')" key="instances">
        <a-table
          class="table"
          size="small"
          :columns="vmColumns"
          :dataSource="this.deployedvms"
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
      </a-tab-pane>
    </a-tabs>
  </a-spin>
</template>

<script>
import { mixinDevice } from '@/utils/mixin.js'
import DetailsTab from '@/components/view/DetailsTab'
import Status from '@/components/widgets/Status'
export default {
  name: 'AutomationTab',
  components: {
    DetailsTab,
    Status
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
      currentTab: 'details',
      deployedResourceColumns: [
        {
          title: this.$t('label.serviceunitname'),
          dataIndex: 'serviceunitname',
          slots: { customRender: 'serviceunitname' }
        },
        {
          title: this.$t('label.state'),
          dataIndex: 'state',
          slots: { customRender: 'state' }
        },
        {
          title: this.$t('label.instance.name'),
          dataIndex: 'instancename',
          slots: { customRender: 'instancename' }
        },
        {
          title: this.$t('label.created'),
          dataIndex: 'created',
          slots: { customRender: 'created' }
        }
      ],
      vmColumns: [
        {
          title: this.$t('label.name'),
          dataIndex: 'name',
          slots: { customRender: 'name' }
        },
        {
          title: this.$t('label.displayname'),
          dataIndex: 'displayname',
          slots: { customRender: 'displayname' }
        },
        {
          title: this.$t('label.state'),
          dataIndex: 'state',
          slots: { customRender: 'state' }
        },
        {
          title: this.$t('label.ip'),
          dataIndex: 'nic.[0].ipaddress'
        }
      ]
    }
  },
  beforeCreate () {

  },
  created () {
    this.fetchData()
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
      this.deployedunitservices = this.resource.deployedunitservices || []
      this.deployedvms = this.resource.deployedvms || []
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
