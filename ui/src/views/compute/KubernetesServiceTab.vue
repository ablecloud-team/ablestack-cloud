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
  <a-spin :spinning="networkLoading">
    <a-tabs
      :tabPosition="resourceTabPosition"
      :activeKey="currentTab"
      :animated="false"
      @change="handleChangeTab">
      <a-tab-pane :tab="$t('label.details')" key="details">
        <a-alert v-if="resource.cleanupstatus" type="warning" show-icon :message="$t('label.kubernetes.cleanup.status') + ': ' + resource.cleanupstatus">
          <template #description>
            <p>{{ $t('message.kubernetes.cleanup.retry') }}</p>
            <p>{{ $t('label.kubernetes.cleanup.phase') }}: {{ resource.cleanupphase }}</p>
            <p v-if="resource.cleanupremaining">{{ $t('label.kubernetes.cleanup.remaining') }}: {{ resource.cleanupremaining }}</p>
          </template>
        </a-alert>
        <DetailsTab :resource="resource" :loading="loading" />
      </a-tab-pane>
      <a-tab-pane v-if="resource.clustertype === 'CloudManaged'" :tab="$t('label.access')" key="access">
        <a-card :title="$t('label.kubeconfig.cluster')" :loading="versionLoading">
          <div v-if="clusterConfig !== ''">
            <a-textarea :value="clusterConfig" :rows="5" readonly />
            <div :span="24" class="action-button">
              <a-button @click="downloadKubernetesClusterConfig" type="primary">{{ $t('label.download.kubernetes.cluster.config') }}</a-button>
            </div>
          </div>
          <div v-else>
            <p>{{ $t('message.kubeconfig.cluster.not.available') }}</p>
          </div>
        </a-card>
        <a-card :title="$t('label.using.cli')" :loading="versionLoading">
          <a-timeline>
            <a-timeline-item>
              <p v-html="$t('label.download.kubeconfig.cluster')">
              </p>
            </a-timeline-item>
            <a-timeline-item>
              <p v-html="$t('label.download.kubectl')"></p>
              <p>
                {{ $t('label.linux') }}: <a :href="kubectlLinuxLink">{{ kubectlLinuxLink }}</a><br>
                {{ $t('label.macos') }}: <a :href="kubectlMacLink">{{ kubectlMacLink }}</a><br>
                {{ $t('label.windows') }}: <a :href="kubectlWindowsLink">{{ kubectlWindowsLink }}</a>
              </p>
            </a-timeline-item>
            <a-timeline-item>
              <p v-html="$t('label.use.kubectl.access.cluster')"></p>
              <p>
                <code><b>kubectl --kubeconfig /custom/path/kube.conf {COMMAND}</b></code><br><br>
                <em>{{ $t('label.list.pods') }}</em><br>
                <code>kubectl --kubeconfig /custom/path/kube.conf get pods --all-namespaces</code><br>
                <em>{{ $t('label.list.nodes') }}</em><br>
                <code>kubectl --kubeconfig /custom/path/kube.conf get nodes --all-namespaces</code><br>
                <em>{{ $t('label.list.services') }}</em><br>
                <code>kubectl --kubeconfig /custom/path/kube.conf get services --all-namespaces</code>
              </p>
            </a-timeline-item>
          </a-timeline>
        </a-card>
        <a-card :title="$t('label.kubernetes.dashboard')">
          <p>{{ $t('message.kubernetes.headlamp.intro') }}</p>
          <a-timeline>
            <a-timeline-item>
              <strong>{{ $t('label.kubernetes.headlamp.access') }}</strong>
              <p>{{ $t('message.kubernetes.headlamp.forward') }}</p>
              <code>kubectl --kubeconfig /custom/path/kube.conf port-forward -n kube-system service/headlamp 8080:80</code>
              <p><a :href="headlampDashboardUrl">{{ headlampDashboardUrl }}</a></p>
              <p>{{ $t('label.kubernetes.headlamp.locale') }}</p>
            </a-timeline-item>
            <a-timeline-item>
              <strong>{{ $t('label.kubernetes.headlamp.readonly') }}</strong>
              <p>{{ $t('message.kubernetes.headlamp.permissions') }}</p>
              <a-textarea :value="dashboardAccessCommands('kube-system', 'mold-headlamp-view')" :rows="5" readonly />
              <p>{{ $t('message.kubernetes.headlamp.expiry') }}</p>
              <p>{{ $t('label.kubernetes.headlamp.token.required') }}</p>
            </a-timeline-item>
            <a-timeline-item>
              <strong>{{ $t('label.kubernetes.dashboard.legacy') }}</strong>
              <p>{{ $t('message.kubernetes.dashboard.legacy') }}</p>
              <code>kubectl --kubeconfig /custom/path/kube.conf proxy</code>
              <p><a href="http://localhost:8001/api/v1/namespaces/kubernetes-dashboard/services/https:kubernetes-dashboard:/proxy/">http://localhost:8001/api/v1/namespaces/kubernetes-dashboard/services/https:kubernetes-dashboard:/proxy/</a></p>
              <a-textarea :value="dashboardAccessCommands('kubernetes-dashboard', 'mold-dashboard-view')" :rows="5" readonly />
            </a-timeline-item>
            <a-timeline-item>
              <strong>{{ $t('label.kubernetes.headlamp.cleanup') }}</strong>
              <p>{{ $t('message.kubernetes.headlamp.cleanup') }}</p>
              <a-textarea :value="dashboardCleanupCommands" :rows="4" readonly />
            </a-timeline-item>
          </a-timeline>
          <p>{{ $t('label.more.access.dashboard.ui') }}:
            <a href="https://headlamp.dev/docs/latest/">Headlamp</a> |
            <a href="https://kubernetes.io/docs/tasks/access-application-cluster/web-ui-dashboard/">Kubernetes Dashboard</a>
          </p>
        </a-card>
        <a-card :title="$t('label.access.kubernetes.nodes')">
          <p v-html="$t('label.kubernetes.access.details')"></p>
        </a-card>
      </a-tab-pane>
      <a-tab-pane :tab="$t('label.instances')" key="instances">
        <a-table
          class="table"
          size="small"
          :columns="vmColumns"
          :dataSource="virtualmachines"
          :rowKey="item => item.id"
          :pagination="false"
        >
          <template #bodyCell="{ column, text, record }">
            <template v-if="column.key === 'name'" :name="text">
              <router-link :to="{ path: '/vm/' + record.id }">{{ record.name }}</router-link>
            </template>
            <template v-if="column.key === 'state'">
              <status :text="text ? text : ''" displayText />
            </template>
            <template v-if="column.key === 'port'" :name="text" :record="record">
              {{ sshPortLabel(record) }}
            </template>
            <template v-if="column.key === 'kubernetesnodeversion'">
              <span> {{ text ? text : '' }} </span>
            </template>
            <template v-if="column.key === 'actions'">
              <a-tooltip placement="bottom" >
                <template #title>
                  {{ $t('label.action.delete.node') }}
                </template>
                <a-popconfirm
                  :title="$t('message.action.delete.node')"
                  @confirm="deleteNode(record)"
                  :okText="$t('label.yes')"
                  :cancelText="$t('label.no')"
                  :disabled="!['Created', 'Running'].includes(resource.state) || resource.autoscalingenabled"
                >
                  <a-button
                    type="danger"
                    shape="circle"
                    :disabled="!['Created', 'Running'].includes(resource.state) || resource.autoscalingenabled">
                    <template #icon><delete-outlined /></template>
                  </a-button>
                </a-popconfirm>
              </a-tooltip>
            </template>
          </template>
        </a-table>
      </a-tab-pane>
      <a-tab-pane :tab="$t('label.firewall')" key="firewall" v-if="publicIpAddress">
        <FirewallRules
          :resource="publicIpAddress"
          :loading="networkLoading"
          :protected-management-ports="kubernetesManagementPorts" />
      </a-tab-pane>
      <a-tab-pane :tab="$t('label.portforwarding')" key="portforwarding" v-if="publicIpAddress">
        <PortForwarding
          :resource="publicIpAddress"
          :loading="networkLoading"
          :protected-management-ports="kubernetesManagementPorts" />
      </a-tab-pane>
      <a-tab-pane :tab="$t('label.loadbalancing')" key="loadbalancing" v-if="resource.networkid">
        <KubernetesLoadBalancers :resource="resource" />
      </a-tab-pane>
      <a-tab-pane :tab="$t('label.events')" key="events" v-if="'listEvents' in $store.getters.apis">
        <events-tab :resource="resource" resourceType="KubernetesCluster" :loading="loading" />
      </a-tab-pane>
      <a-tab-pane :tab="$t('label.annotations')" key="comments" v-if="'listAnnotations' in $store.getters.apis">
        <AnnotationsTab
          :resource="resource"
          :items="annotations">
        </AnnotationsTab>
      </a-tab-pane>
    </a-tabs>
  </a-spin>
</template>

<script>
import { getAPI, postAPI } from '@/api'
import { isAdmin } from '@/role'
import { nodeSshPorts, clusterManagementPorts, listAllKubernetesPortRules } from '@/utils/kubernetesPorts'
import { mixinDevice } from '@/utils/mixin.js'
import DetailsTab from '@/components/view/DetailsTab'
import FirewallRules from '@/views/network/FirewallRules'
import PortForwarding from '@/views/network/PortForwarding'
import KubernetesLoadBalancers from '@/views/compute/KubernetesLoadBalancers'
import { clusterApiAddress } from '@/utils/kubernetesLoadBalancers'
import Status from '@/components/widgets/Status'
import AnnotationsTab from '@/components/view/AnnotationsTab'
import EventsTab from '@/components/view/EventsTab'

export default {
  name: 'KubernetesServiceTab',
  components: {
    DetailsTab,
    FirewallRules,
    PortForwarding,
    KubernetesLoadBalancers,
    Status,
    AnnotationsTab,
    EventsTab
  },
  mixins: [mixinDevice],
  inject: ['parentFetchData'],
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
      clusterConfigLoading: false,
      clusterConfig: '',
      versionLoading: false,
      kubernetesVersion: {},
      kubectlLinuxLink: 'https://storage.googleapis.com/kubernetes-release/release/v1.16.0/bin/linux/amd64/kubectl',
      kubectlMacLink: 'https://storage.googleapis.com/kubernetes-release/release/v1.16.0/bin/darwin/amd64/kubectl',
      kubectlWindowsLink: 'https://storage.googleapis.com/kubernetes-release/release/v1.16.0/bin/windows/amd64/kubectl.exe',
      instanceLoading: false,
      virtualmachines: [],
      vmColumns: [],
      networkLoading: false,
      network: null,
      publicIpAddress: null,
      currentTab: 'details',
      nodePortRules: [],
      nodePortRequest: 0,
      annotations: []
    }
  },
  created () {
    this.vmColumns = [
      {
        key: 'name',
        title: this.$t('label.name'),
        dataIndex: 'name'
      },
      {
        key: 'state',
        title: this.$t('label.state'),
        dataIndex: 'state'
      },
      {
        title: this.$t('label.instancename'),
        dataIndex: 'instancename'
      },
      {
        title: this.$t('label.ipaddress'),
        dataIndex: 'ipaddress'
      },
      {
        key: 'port',
        title: this.$t('label.ssh.port'),
        dataIndex: 'port'
      },
      {
        key: 'kubernetesnodeversion',
        title: this.$t('label.node.version'),
        dataIndex: 'kubernetesnodeversion'
      },
      {
        title: this.$t('label.zonename'),
        dataIndex: 'zonename'
      }
    ]
    if (!isAdmin()) {
      this.vmColumns = this.vmColumns.filter(x => x.dataIndex !== 'instancename')
    }
    if (this.resource.clustertype === 'ExternalManaged') {
      this.vmColumns = this.vmColumns.filter(x => x.dataIndex !== 'port')
    }
    this.handleFetchData()
    window.addEventListener('popstate', this.setCurrentTab)
  },
  beforeUnmount () {
    this.nodePortRequest++
    window.removeEventListener('popstate', this.setCurrentTab)
  },
  watch: {
    resource: {
      deep: true,
      handler (newData, oldData) {
        if (newData && newData !== oldData) {
          this.handleFetchData()
        }
      }
    },
    '$route.fullPath': function () {
      this.setCurrentTab()
    }
  },
  computed: {
    dashboardCleanupCommands () {
      return ['kubectl --kubeconfig /custom/path/kube.conf delete clusterrolebinding mold-headlamp-view mold-dashboard-view --ignore-not-found',
        'kubectl --kubeconfig /custom/path/kube.conf delete serviceaccount mold-headlamp-view -n kube-system --ignore-not-found',
        'kubectl --kubeconfig /custom/path/kube.conf delete serviceaccount mold-dashboard-view -n kubernetes-dashboard --ignore-not-found'].join('\n')
    },
    headlampDashboardUrl () {
      const locale = String(this.$i18n.locale || 'en').replace('_', '-').split('-')[0].toLowerCase()
      const supportedLocales = ['en', 'es', 'fr', 'pt', 'de', 'it', 'zh', 'ko', 'ja', 'hi', 'ta']
      return `http://localhost:8080/?lng=${supportedLocales.includes(locale) ? locale : 'en'}`
    },
    kubernetesManagementPorts () {
      return clusterManagementPorts([{ virtualmachines: this.virtualmachines }], this.nodePortRules)
    }
  },
  mounted () {
    if (this.$store.getters.apis.scaleKubernetesCluster?.params?.filter(x => x.name === 'nodeids').length > 0 && this.resource.clustertype === 'CloudManaged') {
      this.vmColumns.push({
        key: 'actions',
        title: this.$t('label.actions'),
        dataIndex: 'actions'
      })
    }
    this.handleFetchData()
    this.setCurrentTab()
  },
  methods: {
    dashboardAccessCommands (namespace, name) {
      const kubectl = 'kubectl --kubeconfig /custom/path/kube.conf'
      return [`${kubectl} create serviceaccount ${name} -n ${namespace} --dry-run=client -o yaml | ${kubectl} apply -f -`,
        `${kubectl} create clusterrolebinding ${name} --clusterrole=view --serviceaccount=${namespace}:${name} --dry-run=client -o yaml | ${kubectl} apply -f -`,
        `${kubectl} create token ${name} -n ${namespace} --duration=15m`].join('\n')
    },
    setCurrentTab () {
      this.currentTab = this.$route.query.tab ? this.$route.query.tab : 'details'
    },
    handleChangeTab (e) {
      this.currentTab = e
      const query = Object.assign({}, this.$route.query)
      query.tab = e
      history.pushState(
        {},
        null,
        '#' + this.$route.path + '?' + Object.keys(query).map(key => {
          return (
            encodeURIComponent(key) + '=' + encodeURIComponent(query[key])
          )
        }).join('&')
      )
    },
    isValidValueForKey (obj, key) {
      return key in obj && obj[key] != null
    },
    arrayHasItems (array) {
      return array !== null && array !== undefined && Array.isArray(array) && array.length > 0
    },
    isObjectEmpty (obj) {
      return !(obj !== null && obj !== undefined && Object.keys(obj).length > 0 && obj.constructor === Object)
    },
    handleFetchData () {
      this.fetchKubernetesClusterConfig()
      this.fetchKubernetesVersion()
      this.fetchInstances()
      this.fetchPublicIpAddress()
      this.fetchComments()
    },
    fetchComments () {
      this.clusterConfigLoading = true
      getAPI('listAnnotations', { entityid: this.resource.id, entitytype: 'KUBERNETES_CLUSTER', annotationfilter: 'all' }).then(json => {
        if (json.listannotationsresponse?.annotation) {
          this.annotations = json.listannotationsresponse.annotation
        }
      }).catch(error => {
        this.$notifyError(error)
      }).finally(() => {
        this.clusterConfigLoading = false
      })
    },
    fetchKubernetesClusterConfig () {
      this.clusterConfigLoading = true
      this.clusterConfig = ''
      if (!this.isObjectEmpty(this.resource)) {
        var params = {}
        params.id = this.resource.id
        params.refresh = this.resource.clustertype === 'CloudManaged' && this.resource.state === 'Running'
        getAPI('getKubernetesClusterConfig', params).then(json => {
          const config = json.getkubernetesclusterconfigresponse.clusterconfig
          if (!this.isObjectEmpty(config) &&
            this.isValidValueForKey(config, 'configdata') &&
            config.configdata !== '') {
            this.clusterConfig = config.configdata
          } else {
            this.$notification.error({
              message: this.$t('message.request.failed'),
              description: this.$t('message.error.retrieve.kubeconfig')
            })
          }
        }).catch(error => {
          this.$notifyError(error)
        }).finally(() => {
          this.clusterConfigLoading = false
          if (!this.isObjectEmpty(this.kubernetesVersion) && this.isValidValueForKey(this.kubernetesVersion, 'semanticversion')) {
            this.kubectlLinuxLink = 'https://dl.k8s.io/release/v' + this.kubernetesVersion.semanticversion + '/bin/linux/amd64/kubectl'
            this.kubectlMacLink = 'https://dl.k8s.io/release/v' + this.kubernetesVersion.semanticversion + '/bin/darwin/amd64/kubectl'
            this.kubectlWindowsLink = 'https://dl.k8s.io/release/v' + this.kubernetesVersion.semanticversion + '/bin/windows/amd64/kubectl.exe'
          }
        })
      }
    },
    fetchKubernetesVersion () {
      this.versionLoading = true
      if (!this.isObjectEmpty(this.resource) && this.isValidValueForKey(this.resource, 'kubernetesversionid') &&
        this.resource.kubernetesversionid !== '') {
        var params = {}
        params.id = this.resource.kubernetesversionid
        getAPI('listKubernetesSupportedVersions', params).then(json => {
          const versionObjs = json.listkubernetessupportedversionsresponse.kubernetessupportedversion
          if (this.arrayHasItems(versionObjs)) {
            this.kubernetesVersion = versionObjs[0]
          }
        }).catch(error => {
          this.$notifyError(error)
        }).finally(() => {
          this.versionLoading = false
          if (!this.isObjectEmpty(this.kubernetesVersion) && this.isValidValueForKey(this.kubernetesVersion, 'semanticversion')) {
            this.kubectlLinuxLink = 'https://dl.k8s.io/release/v' + this.kubernetesVersion.semanticversion + '/bin/linux/amd64/kubectl'
            this.kubectlMacLink = 'https://dl.k8s.io/release/v' + this.kubernetesVersion.semanticversion + '/bin/darwin/amd64/kubectl'
            this.kubectlWindowsLink = 'https://dl.k8s.io/release/v' + this.kubernetesVersion.semanticversion + '/bin/windows/amd64/kubectl.exe'
          }
        })
      }
    },
    fetchInstances () {
      this.instanceLoading = true
      const nodes = Array.isArray(this.resource.virtualmachines) ? this.resource.virtualmachines : []
      const defaultNodes = nodes.filter(x => !x.isexternalnode && !x.isetcdnode)
      const externalNodes = nodes.filter(x => x.isexternalnode)
      const etcdNodes = nodes.filter(x => x.isetcdnode)
      this.virtualmachines = defaultNodes.concat(externalNodes).concat(etcdNodes).map(node => {
        const nics = Array.isArray(node.nic) ? node.nic : []
        const nic = nics.find(nic => nic && nic.isdefault) || nics[0]
        return { ...node, ipaddress: nic?.ipaddress || '' }
      })
      this.instanceLoading = false
    },
    sshPortLabel (vm) {
      const ports = nodeSshPorts(vm, this.network, this.nodePortRules)
      return ports.length ? ports.join(', ') : this.$t('label.unknown')
    },
    async fetchPublicIpAddress () {
      const resource = { ...this.resource }
      const request = ++this.nodePortRequest
      const current = () => request === this.nodePortRequest && resource.id === this.resource.id
      this.networkLoading = true
      this.network = null
      this.publicIpAddress = null
      this.nodePortRules = []
      try {
        if (!resource.networkid) return
        const response = await getAPI('listNetworks', { listAll: true, id: resource.networkid })
        if (!current()) return
        this.network = response.listnetworksresponse?.network?.[0] || null
        if (!this.network || this.network.type === 'Shared' || this.network.ip4routing) return
        const params = { listAll: true, forvirtualnetwork: true, associatednetworkid: resource.networkid }
        if (resource.projectid) params.projectid = resource.projectid
        if (resource.ipaddressid) params.id = resource.ipaddressid
        const ips = await getAPI('listPublicIpAddresses', params)
        if (!current()) return
        this.publicIpAddress = ips.listpublicipaddressesresponse?.publicipaddress?.find(ip => resource.ipaddressid ? ip.id === resource.ipaddressid : ip.ipaddress === clusterApiAddress(resource)) || ips.listpublicipaddressesresponse?.publicipaddress?.find(ip => ip.issourcenat) || null
        if (!this.publicIpAddress || !this.$store.getters.apis.listPortForwardingRules) return
        const rules = await listAllKubernetesPortRules(getAPI, this.publicIpAddress.id)
        if (current()) this.nodePortRules = rules
      } catch (error) {
        if (current()) this.$notifyError(error)
      } finally {
        if (current()) this.networkLoading = false
      }
    },
    downloadKubernetesClusterConfig () {
      var blob = new Blob([this.clusterConfig], { type: 'text/plain' })
      var filename = 'kube.conf'
      if (window.navigator.msSaveOrOpenBlob) {
        window.navigator.msSaveBlob(blob, filename)
      } else {
        var elem = window.document.createElement('a')
        elem.href = window.URL.createObjectURL(blob)
        elem.download = filename
        document.body.appendChild(elem)
        elem.click()
        document.body.removeChild(elem)
      }
    },
    deleteNode (node) {
      const params = {
        id: this.resource.id,
        nodeids: node.id
      }
      postAPI('scaleKubernetesCluster', params).then(json => {
        const jobId = json.scalekubernetesclusterresponse.jobid
        console.log(jobId)
        this.$store.dispatch('AddAsyncJob', {
          title: this.$t('label.action.delete.node'),
          jobid: jobId,
          description: node.name,
          status: 'progress'
        })
        this.$pollJob({
          jobId,
          loadingMessage: `${this.$t('message.deleting.node')} ${node.name}`,
          catchMessage: this.$t('error.fetching.async.job.result'),
          successMessage: `${this.$t('message.success.delete.node')} ${node.name}`,
          successMethod: () => {
            this.parentFetchData()
          }
        })
      }).catch(error => {
        this.$notifyError(error)
      }).finally(() => {
        this.parentFetchData()
      })
    }
  }
}
</script>

<style lang="scss" scoped>
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
