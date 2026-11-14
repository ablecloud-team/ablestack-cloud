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
  <a-spin class="vpc-tab" :spinning="fetchLoading">
    <a-alert v-if="listRefreshFailed" type="warning" show-icon :message="$t('message.list.refresh.stale')" class="vpc-query-notice"><template #description><a-button size="small" :disabled="fetchLoading" @click="handleFetchData">{{ $t('label.retry') }}</a-button></template></a-alert>
    <a-tabs
      :tabPosition="resourceTabPosition"
      :activeKey="currentTab"
      :animated="false"
      @change="handleChangeTab">
      <a-tab-pane :tab="$t('label.details')" key="details">
        <DetailsTab :resource="resource" :loading="loading" />
      </a-tab-pane>
      <a-tab-pane :tab="$t('label.networks')" key="tier">
        <VpcTiersTab :resource="resource" :loading="loading" />
      </a-tab-pane>
      <a-tab-pane :tab="$t('label.bgp.peers')" key="bgppeers" v-if="resource.ip4routing === 'Dynamic'">
        <BgpPeersTab :resource="resource" />
      </a-tab-pane>
      <a-tab-pane :tab="$t('label.public.ips')" key="ip" v-if="'listPublicIpAddresses' in $store.getters.apis">
        <IpAddressesTab :resource="resource" :loading="loading" />
      </a-tab-pane>
      <a-tab-pane :tab="$t('label.network.acls')" key="acl" v-if="'listNetworkACLLists' in $store.getters.apis">
        <a-button
          type="primary"
          class="vpc-tab-create"
          :disabled="!('createNetworkACLList' in $store.getters.apis)"
          @click="() => handleOpenModals('networkAcl')">
          <template #icon><plus-circle-outlined /></template>
          {{ $t('label.add.network.acl') }}
        </a-button>
        <a-table
          class="table"
          size="small"
          :columns="networkAclsColumns"
          :scroll="{ x: 480 }"
          :dataSource="networkAcls"
          :rowKey="item => item.id"
          :pagination="false"
        >
          <template #bodyCell="{ column, text, record }">
            <template v-if="column.key === 'name'">
              <router-link :to="{ path: '/acllist/' + record.id }">
                {{ text }}
              </router-link>
            </template>
          </template>
        </a-table>
        <a-pagination
          class="row-element pagination"
          size="small"
          :current="page"
          :pageSize="pageSize"
          :total="itemCounts.networkAcls"
          :showTotal="total => `${$t('label.total')} ${total} ${$t('label.items')}`"
          :pageSizeOptions="['10', '20', '40', '80', '100']"
          @change="changePage"
          @showSizeChange="changePageSize"
          showSizeChanger>
          <template #buildOptionText="props">
            <span>{{ props.value }} / {{ $t('label.page') }}</span>
          </template>
        </a-pagination>
        <MoldDialog
          size="md"
form-actions
focus-guard
          :closable="!aclSubmitting"
:keyboard="!aclSubmitting"
          :visible="modals.networkAcl"
          :title="$t('label.add.acl')"
          :maskClosable="false"
          @cancel="closeAcl">
          <VpcActionContext :resources="[resource]" />
          <a-alert v-if="aclError" type="error" show-icon :message="aclError" class="vpc-query-notice" />
          <a-form
            layout="vertical"
            :ref="formRef"
            :model="form"
            :rules="rules"
            @finish="handleNetworkAclFormSubmit"
            v-ctrl-enter="handleNetworkAclFormSubmit"
           >
            <a-form-item :label="$t('label.add.acl.name')" ref="name" name="name">
              <a-input
                v-model:value="form.name"
                v-focus="true"></a-input>
            </a-form-item>
            <a-form-item :label="$t('label.description')"  ref="description" name="description">
              <a-input v-model:value="form.description" />
            </a-form-item>

            <MoldDialogActions :busy="aclSubmitting">
              <a-button @click="closeAcl">{{ $t('label.cancel') }}</a-button>
              <a-button type="primary" :loading="aclSubmitting" @click="handleNetworkAclFormSubmit">{{ $t('label.ok') }}</a-button>
            </MoldDialogActions>
          </a-form>
        </MoldDialog>
      </a-tab-pane>
      <a-tab-pane :tab="$t('label.private.gateway')" key="pgw" v-if="'listPrivateGateways' in $store.getters.apis">
        <a-button
          type="primary"
          class="vpc-tab-create"
          :disabled="!('createPrivateGateway' in $store.getters.apis)"
          @click="gatewayCreate = true">
          <template #icon><plus-circle-outlined /></template>
          {{ $t('label.add.private.gateway') }}
        </a-button>
        <a-table
          class="table"
          size="small"
          :columns="privateGatewaysColumns"
          :scroll="{ x: 760 }"
          :dataSource="privateGateways"
          :rowKey="item => item.id"
          :pagination="false"
        >
          <template #bodyCell="{ column, text, record }">
            <template v-if="column.key === 'ipaddress'">
              <router-link :to="{ path: '/privategw/' + record.id }">{{ text }}</router-link>
            </template>
            <template v-if="column.key === 'state'">
              <status :text="record.state" displayText></status>
            </template>
          </template>
        </a-table>
        <a-pagination
          class="row-element pagination"
          size="small"
          :current="page"
          :pageSize="pageSize"
          :total="itemCounts.privateGateways"
          :showTotal="total => `${$t('label.total')} ${total} ${$t('label.items')}`"
          :pageSizeOptions="['10', '20', '40', '80', '100']"
          @change="changePage"
          @showSizeChange="changePageSize"
          showSizeChanger>
          <template #buildOptionText="props">
            <span>{{ props.value }} / {{ $t('label.page') }}</span>
          </template>
        </a-pagination>

      </a-tab-pane>
      <a-tab-pane :tab="$t('label.vpn.gateway')" key="vpngw" v-if="'listVpnGateways' in $store.getters.apis">
        <a-button
          v-if="vpnGateways.length === 0"
          type="primary"
          class="vpc-tab-create"
          :disabled="!('createVpnGateway' in $store.getters.apis)"
          @click="() => handleOpenModals('vpnGateway')">
          <template #icon><plus-circle-outlined /></template>
          {{ $t('label.create.site.vpn.gateway') }}
        </a-button>
        <a-list class="list">
          <a-list-item v-for="item in vpnGateways" :key="item.id">
            <div class="list__item">
              <div class="list__col">
                <div class="list__label">{{ $t('label.ip') }}</div>
                <div>
                  <router-link :to="{ path: '/s2svpn/' + item.id }">
                    {{ item.publicip }}
                  </router-link>
                </div>
              </div>
            </div>
          </a-list-item>
        </a-list>
      </a-tab-pane>
      <a-tab-pane :tab="$t('label.vpn.connection')" key="vpnc" v-if="'listVpnConnections' in $store.getters.apis">
        <a-button
          type="primary"
          class="vpc-tab-create"
          :disabled="!('createVpnConnection' in $store.getters.apis)"
          @click="handleOpenModals('vpnConnection')">
          <template #icon><plus-circle-outlined /></template>
          {{ $t('label.create.site.vpn.connection') }}
        </a-button>
        <a-table
          class="table"
          size="small"
          :columns="vpnConnectionsColumns"
          :scroll="{ x: 640 }"
          :dataSource="vpnConnections"
          :pagination="false"
          :rowKey="record => record.id">
          <template #bodyCell="{ column, text, record }">
            <template v-if="column.key === 'publicip'">
              <router-link :to="{ path: '/s2svpnconn/' + record.id }">
                {{ text }}
              </router-link>
            </template>
            <template v-if="column.key === 'ipsecpsk'"><VpnPsk :value="record.ipsecpsk" masked-only /></template>
            <template v-if="column.key === 'state'">
              <status :text="text ? text : ''" displayText />
            </template>
          </template>
        </a-table>
        <a-pagination
          class="row-element pagination"
          size="small"
          :current="page"
          :pageSize="pageSize"
          :total="itemCounts.vpnConnections"
          :showTotal="total => `${$t('label.total')} ${total} ${$t('label.items')}`"
          :pageSizeOptions="['10', '20', '40', '80', '100']"
          @change="changePage"
          @showSizeChange="changePageSize"
          showSizeChanger>
          <template #buildOptionText="props">
            <span>{{ props.value }} / {{ $t('label.page') }}</span>
          </template>
        </a-pagination>
      </a-tab-pane>
      <a-tab-pane :tab="$t('label.static.routes')" key="staticroutes">
        <StaticRoutesTab :resource="resource" :loading="loading" />
      </a-tab-pane>
      <a-tab-pane :tab="$t('label.virtual.routers')" key="vr" v-if="$store.getters.userInfo.roletype === 'Admin'">
        <RoutersTab :resource="resource" :loading="loading" />
      </a-tab-pane>
      <a-tab-pane :tab="$t('label.vnf.appliances')" key="vnf" v-if="'deployVnfAppliance' in $store.getters.apis">
        <VnfAppliancesTab :resource="resource" :loading="loading" />
      </a-tab-pane>
      <a-tab-pane :tab="$t('label.custom.actions')" key="customactions" v-if="'runCustomAction' in $store.getters.apis && 'listCustomActions' in $store.getters.apis && resource.service && resource.service.some(s => s.name === 'CustomAction')">
        <RunCustomAction :resource="resource" />
      </a-tab-pane>
      <a-tab-pane :tab="$t('label.events')" key="events" v-if="'listEvents' in $store.getters.apis">
        <events-tab :resource="resource" resourceType="Vpc" :loading="loading" />
      </a-tab-pane>
      <a-tab-pane :tab="$t('label.annotations')" key="comments" v-if="'listAnnotations' in $store.getters.apis">
        <AnnotationsTab
          :resource="resource"
          :items="annotations">
        </AnnotationsTab>
      </a-tab-pane>
    </a-tabs>
    <PrivateGatewayCreate v-if="gatewayCreate" :resource="resource" @cancel="gatewayCreate = false" @success="fetchPrivateGateways" />
    <VpcVpnCreate v-if="vpnCreate" :kind="vpnCreate" :resource="resource" @cancel="vpnCreate = ''" @success="onVpnCreated" />
  </a-spin>
</template>

<script>
import MoldDialog from '@/components/network/VpcDialog'
import MoldDialogActions from '@/components/view/MoldDialogActions'
import VpcActionContext from '@/components/network/VpcActionContext'
import { listRefreshMixin } from '@/utils/listRefreshMixin'
import { ref, reactive, toRaw } from 'vue'
import { getAPI, postAPI } from '@/api'
import { mixinDevice } from '@/utils/mixin.js'
import DetailsTab from '@/components/view/DetailsTab'
import Status from '@/components/widgets/Status'
import IpAddressesTab from './IpAddressesTab'
import RoutersTab from './RoutersTab'
import VpcTiersTab from './VpcTiersTab'
import VnfAppliancesTab from './VnfAppliancesTab'
import EventsTab from '@/components/view/EventsTab'
import AnnotationsTab from '@/components/view/AnnotationsTab'
import ResourceIcon from '@/components/view/ResourceIcon'
import BgpPeersTab from '@/views/infra/zone/BgpPeersTab.vue'
import StaticRoutesTab from './StaticRoutesTab'
import RunCustomAction from '@/views/extension/RunCustomAction'
import VpnPsk from '@/components/network/VpnPsk'
import VpcVpnCreate from './VpcVpnCreate'
import PrivateGatewayCreate from './PrivateGatewayCreate'
import { vpcVpnContext, vpcVpnOwner } from '@/utils/vpcVpn'

export default {
  mixins: [mixinDevice, listRefreshMixin(['fetchComments', 'fetchPrivateGateways', 'fetchVpnGateways', 'fetchVpnConnections', 'fetchAclList'], { select: vm => ({ vpngw: ['fetchVpnGateways'], pgw: ['fetchPrivateGateways'], vpnc: ['fetchVpnConnections'], acl: ['fetchAclList'], comments: ['fetchComments'] }[vm.currentTab] || []) })],
  name: 'VpcTab',
  components: {
    MoldDialog,
    MoldDialogActions,
    VpcActionContext,
    VpnPsk,
    VpcVpnCreate,
    PrivateGatewayCreate,
    BgpPeersTab,
    DetailsTab,
    Status,
    IpAddressesTab,
    RoutersTab,
    VpcTiersTab,
    VnfAppliancesTab,
    StaticRoutesTab,
    RunCustomAction,
    EventsTab,
    AnnotationsTab,
    ResourceIcon
  },
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
  data () {
    return {
      fetchLoading: false,
      aclSubmitting: false,
      aclError: '',
      privateGateways: [],
      vpnGateways: [],
      vpnCreate: '',
      gatewayCreate: false,
      vpnConnections: [],
      networkAcls: [],
      modals: {
        networkAcl: false
      },
      privateGatewaysColumns: [
        {
          key: 'ipaddress',
          title: this.$t('label.ip'),
          dataIndex: 'ipaddress'
        },
        {
          key: 'state',
          title: this.$t('label.state'),
          dataIndex: 'state'
        },
        {
          title: this.$t('label.gateway'),
          dataIndex: 'gateway'
        },
        {
          title: this.$t('label.netmask'),
          dataIndex: 'netmask'
        },
        {
          title: this.$t('label.vlan'),
          dataIndex: 'vlan'
        }
      ],
      vpnConnectionsColumns: [
        {
          key: 'publicip',
          title: this.$t('label.ip'),
          dataIndex: 'publicip'
        },
        {
          key: 'state',
          title: this.$t('label.state'),
          dataIndex: 'state'
        },
        {
          title: this.$t('label.gateway'),
          dataIndex: 'gateway'
        },
        {
          title: this.$t('label.ipsecpsk'),
          key: 'ipsecpsk',
          dataIndex: 'ipsecpsk'
        }
      ],
      networkAclsColumns: [
        {
          key: 'name',
          title: this.$t('label.name'),
          dataIndex: 'name'
        },
        {
          title: this.$t('label.description'),
          dataIndex: 'description'
        }
      ],
      itemCounts: {
        privateGateways: 0,
        vpnConnections: 0,
        networkAcls: 0
      },
      page: 1,
      pageSize: 10,
      currentTab: 'details',
      annotations: []
    }
  },
  watch: {
    loading (newData) {
      if (!newData && this.resource.id) {
        this.handleFetchData()
      }
    },
    '$route.fullPath': function () {
      this.setCurrentTab()
      this.handleFetchData()
    }
  },
  created () {
    this.initForm()
    this.setCurrentTab()
    this.handleFetchData()
  },
  methods: {
    initForm () {
      this.formRef = ref()
      this.form = reactive({})
      this.rules = reactive({})
    },
    isAdmin () {
      return ['Admin'].includes(this.$store.getters.userInfo.roletype)
    },
    setCurrentTab () {
      const apis = this.$store.getters.apis
      const available = ['details', 'tier', 'staticroutes', ...Object.entries({ ip: 'listPublicIpAddresses', acl: 'listNetworkACLLists', pgw: 'listPrivateGateways', vpngw: 'listVpnGateways', vpnc: 'listVpnConnections', events: 'listEvents', comments: 'listAnnotations', vnf: 'deployVnfAppliance' }).filter(([, api]) => api in apis).map(([tab]) => tab)]
      if (this.resource.ip4routing === 'Dynamic') available.push('bgppeers')
      if (this.isAdmin()) available.push('vr')
      if ('runCustomAction' in apis && 'listCustomActions' in apis && this.resource.service?.some(service => service.name === 'CustomAction')) available.push('customactions')
      const requested = this.$route?.query?.tab || 'details'
      this.currentTab = !this.resource.id || available.includes(requested) ? requested : 'details'
      if (this.resource.id && requested !== this.currentTab) this.$router.replace({ path: this.$route.path, query: { ...this.$route.query, tab: this.currentTab } })
    },
    handleChangeTab (e) {
      this.vpnCreate = ''
      this.gatewayCreate = false
      this.currentTab = e
      this.page = 1
      this.pageSize = 10
      this.handleFetchData()
      this.$router.push({ path: this.$route.path, query: { ...this.$route.query, tab: e } })
    },
    handleFetchData () {
      switch (this.currentTab) {
        case 'pgw':
          this.fetchPrivateGateways()
          break
        case 'vpngw':
          this.fetchVpnGateways()
          break
        case 'vpnc':
          this.fetchVpnConnections()
          break
        case 'acl':
          this.fetchAclList()
          break
        case 'comments':
          this.fetchComments()
          break
      }
    },
    fetchComments () {
      if (!this.resource.id) return
      const request = this.listRequestToken('fetchComments')
      this.fetchLoading = !request.loaded
      return getAPI('listAnnotations', { entityid: this.resource.id, entitytype: 'VPC', annotationfilter: 'all' }).then(json => {
        if (!this.isListRequestCurrent('fetchComments', request)) return
        this.annotations = json.listannotationsresponse?.annotation || []
      }).catch(error => {
        if (!this.isListRequestCurrent('fetchComments', request)) return
        request.failed = true
        this.listRefreshFailed = true
        if (!request.loaded) this.$notifyError(error)
      }).finally(() => {
        if (this.isListRequestCurrent('fetchComments', request)) this.fetchLoading = false
      })
    },
    fetchPrivateGateways () {
      if (!this.resource.id) return
      const request = this.listRequestToken('fetchPrivateGateways')
      this.fetchLoading = !request.loaded
      return getAPI('listPrivateGateways', { vpcid: this.resource.id, listall: true, page: this.page, pagesize: this.pageSize }).then(json => {
        if (!this.isListRequestCurrent('fetchPrivateGateways', request)) return
        this.privateGateways = json.listprivategatewaysresponse?.privategateway || []
        this.itemCounts.privateGateways = json.listprivategatewaysresponse?.count || 0
      }).catch(error => {
        if (!this.isListRequestCurrent('fetchPrivateGateways', request)) return
        request.failed = true
        this.listRefreshFailed = true
        if (!request.loaded) this.$notifyError(error)
      }).finally(() => {
        if (this.isListRequestCurrent('fetchPrivateGateways', request)) this.fetchLoading = false
      })
    },
    fetchVpnGateways () {
      if (!this.resource.id) return
      const request = this.listRequestToken('fetchVpnGateways')
      const scope = vpcVpnContext(this, 'gateway')
      const current = () => this.isListRequestCurrent('fetchVpnGateways', request) && scope === vpcVpnContext(this, 'gateway')
      this.fetchLoading = !request.loaded
      return getAPI('listVpnGateways', { vpcid: this.resource.id, ...vpcVpnOwner(this.resource), ignoreproject: true, listall: true }).then(json => {
        if (current()) this.vpnGateways = (json?.listvpngatewaysresponse?.vpngateway || []).filter(row => row.vpcid === this.resource.id)
      }).catch(error => {
        if (!current()) return
        request.failed = true; this.listRefreshFailed = true
        if (!request.loaded) this.$notifyError(error)
      }).finally(() => { if (current()) this.fetchLoading = false })
    },
    fetchVpnConnections () {
      if (!this.resource.id) return
      const request = this.listRequestToken('fetchVpnConnections')
      this.fetchLoading = !request.loaded
      return getAPI('listVpnConnections', { vpcid: this.resource.id, listall: true, page: this.page, pagesize: this.pageSize }).then(json => {
        if (!this.isListRequestCurrent('fetchVpnConnections', request)) return
        this.vpnConnections = json.listvpnconnectionsresponse?.vpnconnection || []
        this.itemCounts.vpnConnections = json.listvpnconnectionsresponse?.count || 0
      }).catch(error => {
        if (!this.isListRequestCurrent('fetchVpnConnections', request)) return
        request.failed = true
        this.listRefreshFailed = true
        if (!request.loaded) this.$notifyError(error)
      }).finally(() => {
        if (this.isListRequestCurrent('fetchVpnConnections', request)) this.fetchLoading = false
      })
    },
    fetchAclList () {
      if (!this.resource.id) return
      const request = this.listRequestToken('fetchAclList')
      this.fetchLoading = !request.loaded
      return getAPI('listNetworkACLLists', { vpcid: this.resource.id, listall: true, page: this.page, pagesize: this.pageSize }).then(json => {
        if (!this.isListRequestCurrent('fetchAclList', request)) return
        this.networkAcls = json.listnetworkacllistsresponse?.networkacllist || []
        this.itemCounts.networkAcls = json.listnetworkacllistsresponse?.count || 0
      }).catch(error => {
        if (!this.isListRequestCurrent('fetchAclList', request)) return
        request.failed = true
        this.listRefreshFailed = true
        if (!request.loaded) this.$notifyError(error)
      }).finally(() => {
        if (this.isListRequestCurrent('fetchAclList', request)) this.fetchLoading = false
      })
    },
    onVpnCreated () {
      this.fetchVpnGateways()
      this.fetchVpnConnections()
    },
    handleOpenModals (e) {
      if (e === 'vpnGateway' || e === 'vpnConnection') { this.vpnCreate = e === 'vpnGateway' ? 'gateway' : 'connection'; return }
      this.initForm()
      this.aclError = ''

      switch (e) {
        case 'networkAcl':
          this.rules = {
            name: [{ required: true, message: this.$t('label.required') }]
          }
          this.modals.networkAcl = true
          break
      }
    },
    closeAcl () { if (!this.aclSubmitting) this.modals.networkAcl = false },
    async handleNetworkAclFormSubmit () {
      if (this.aclSubmitting || !('createNetworkACLList' in this.$store.getters.apis)) return
      this.aclSubmitting = true
      this.aclError = ''
      let polling = false
      const complete = () => { this.aclSubmitting = false; this.modals.networkAcl = false; this.fetchAclList() }
      const failed = () => { this.aclSubmitting = false; this.aclError = this.$t('message.add.network.acl.failed'); this.fetchAclList() }
      try {
        await this.formRef.value.validate()
        const values = toRaw(this.form)
        const response = await postAPI('createNetworkACLList', { name: values.name, description: values.description, vpcid: this.resource.id })
        const body = response.createnetworkacllistresponse
        if (body.jobid) {
          polling = true
          this.$pollJob({ jobId: body.jobid, title: this.$t('message.success.add.network.acl'), description: values.name || values.description, successMethod: complete, errorMethod: failed, errorMessage: this.$t('message.add.network.acl.failed'), catchMethod: failed, catchMessage: this.$t('error.fetching.async.job.result'), loadingMessage: this.$t('message.add.network.acl.processing') })
          return
        }
        complete()
      } catch (error) {
        if (error.errorFields?.length) this.formRef.value.scrollToField(error.errorFields[0].name)
        else { this.aclError = error.response?.data?.createnetworkacllistresponse?.errortext || error.response?.data?.errorresponse?.errortext || error.message || this.$t('message.add.network.acl.failed'); this.$notifyError(error) }
      } finally { if (!polling) this.aclSubmitting = false }
    },
    changePage (page, pageSize) {
      this.page = page
      this.pageSize = pageSize
      this.handleFetchData()
    },
    changePageSize (currentPage, pageSize) {
      this.page = currentPage
      this.pageSize = pageSize
      this.handleFetchData()
    }
  }
}
</script>

<style lang="scss" scoped>
.vpc-tab { min-width: 0; }
.vpc-tab-create { margin-bottom: 16px; }
.vpc-query-notice { margin-bottom: 16px; }
:deep(.ant-table-cell) { overflow-wrap: anywhere; }
:deep(.ant-pagination) { display: flex; flex-wrap: wrap; gap: 4px; }

.list {

  &__item,
  &__row {
    display: flex;
    flex-wrap: wrap;
    width: 100%;
  }

  &__item {
    margin-bottom: -20px;
  }

  &__col {
    flex: 1;
    margin-right: 20px;
    margin-bottom: 20px;
  }

  &__label {
    font-weight: bold;
  }

}

.pagination {
  margin-top: 20px;
}

.table {
  margin-top: 20px;
  overflow-y: auto;
}
</style>
