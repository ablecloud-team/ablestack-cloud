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
  <a-alert type="error" v-if="['vm', 'systemvm', 'router', 'ilbvm'].includes($route.meta.name) && 'hostcontrolstate' in resource && resource.hostcontrolstate !== 'Enabled'">
    <template #message>
      <div class="title">
        {{ $t('message.host.controlstate') }} {{ resource.hostcontrolstate }}. {{ $t('message.host.controlstate.retry') }}
      </div>
    </template>
  </a-alert>
  <div v-if="['host'].includes($route.meta.name)  && licenseCode !== ''">
    <a-alert type="success" :showIcon="true" v-if="licenseCode == 'OK'" :message="$t('message.alert.licenseexpired') + ' : ' + dataResource.licenseStartDate + '~' + dataResource.licenseExpiryDate" :description="'(' + calculateDday(dataResource.licenseExpiryDate) + $t('message.license.days.left') + ')'" />
    <a-alert type="error" :showIcon="true" v-else-if="licenseCode == 'PASSED'" :message="$t('message.alert.licenseexpired') + ' : ' + dataResource.licenseStartDate + '~' + dataResource.licenseExpiryDate" :description="'(' + $t('message.license.renewal.required') + ')'" />
    <a-alert type="error" :showIcon="true" v-else-if="licenseCode == 'NOSTART'" :message="$t('message.alert.licenseexpired') + ' : ' + dataResource.licenseStartDate + '~' + dataResource.licenseExpiryDate" :description="'(' + $t('message.license.nostart') + ')'" />
    <a-alert type="error" :showIcon="true" v-else-if="licenseCode == 'NONE'" :message="$t('message.license.not.found1')" :description="$t('message.license.not.found2')"/>
  </div>
  <a-alert v-if="ip4routes" type="info" :showIcon="true" :message="$t('label.add.upstream.ipv4.routes')">
    <template #description>
      <p v-html="ip4routes" />
    </template>
  </a-alert>
  <a-alert v-if="ip6routes" type="info" :showIcon="true" :message="$t('label.add.upstream.ipv6.routes')">
    <template #description>
      <p v-html="ip6routes" />
    </template>
  </a-alert>
  <a-alert v-if="vnfAccessMethods" type="info" :showIcon="true" :message="$t('label.vnf.appliance.access.methods')">
    <template #description>
      <p v-html="vnfAccessMethods" />
    </template>
  </a-alert>
  <a-list
    size="small"
    :dataSource="fetchDetails()">
    <template #renderItem="{item}">
      <a-list-item v-if="(item in dataResource && !customDisplayItems.includes(item)) || (offeringDetails.includes(item) && dataResource.serviceofferingdetails)|| ($route.path.includes('/alertRules') && ['summary', 'description'].includes(item))">
        <div style="width: 100%">
          <strong>{{ item === 'service' ? $t('label.supportedservices') :
           $route.meta.name === 'cniconfiguration' && item === 'userdata' ? $t('label.' + String($route.meta.name).toLowerCase()) :
           $t(getDetailTitle(item)) }}</strong>
          <a-tooltip v-if="['volume', 'snapshot', 'template', 'iso'].includes($route.meta.name) && item === 'usedfsbytes'"><template #title>{{ $t('message.usedfsbytes') }}</template><QuestionCircleOutlined style="margin-left: 8px;"/></a-tooltip>
          <a-tooltip v-if="['volume', 'snapshot', 'template', 'iso'].includes($route.meta.name) && item === 'savingrate'"><template #title>{{ $t('message.savingrate') }}</template><QuestionCircleOutlined style="margin-left: 8px;"/></a-tooltip>
          <br/>
          <div v-if="Array.isArray(dataResource[item]) && item === 'service'">
            <div v-for="(service, idx) in dataResource[item]" :key="idx">
              {{ service.name }} : {{ service.provider.map(p => p.name).join(', ') }}
            </div>
          </div>
          <div v-else-if="$route.meta.name === 'backup' && (item === 'size' || item === 'virtualsize')">
            {{ $bytesToHumanReadableSize(dataResource[item]) }}
            <a-tooltip placement="right">
              <template #title>
                {{ dataResource[item] }} bytes
              </template>
              <QuestionCircleOutlined />
            </a-tooltip>
          </div>
          <div v-else-if="$route.meta.name === 'backup' && item === 'volumes'">
            <div v-for="(volume, idx) in JSON.parse(dataResource[item])" :key="idx">
              <router-link v-if="!dataResource['vmbackupofferingremoved']" :to="{ path: '/volume/' + volume.uuid }">{{ volume.type }} - {{ volume.path }}</router-link>
              <span v-else>{{ volume.type }} - {{ volume.path }}</span> ({{ parseFloat(volume.size / (1024.0 * 1024.0 * 1024.0)).toFixed(1) }} GB)
            </div>
          </div>
          <div v-else-if="$route.meta.name === 'vm' && item === 'qemuagentversion'">
            {{ dataResource[item] === 'Not Installed' ? $t('label.state.qemuagentversion.notinstalled') : dataResource[item]}}
          </div>
          <div v-else-if="$route.meta.name === 'controllertemplate' && item === 'dctemplate'">
            <div v-for="(dctemplate, idx) in dataResource[item]" :key="idx">
              <router-link :to="{ path: '/template/' + dctemplate.id }">{{ dctemplate.name }}</router-link>
            </div>
          </div>
          <div v-else-if="$route.meta.name === 'controllertemplate' && item === 'workstemplate'">
            <div v-for="(workstemplate, idx) in dataResource[item]" :key="idx">
              <router-link :to="{ path: '/template/' + workstemplate.id }">{{ workstemplate.name }}</router-link>
            </div>
          </div>
          <div v-else-if="$route.meta.name === 'mastertemplate' && item === 'templatename'">
            <router-link :to="{ path: '/template/' + dataResource.templateid }">{{ dataResource.templatename }} </router-link>
          </div>
          <div v-else-if="$route.meta.name === 'deployedresource' && item === 'accessinfo'">
            <div v-html="dataResource.accessinfo"></div>
          </div>
          <div v-else-if="$route.meta.name === 'computeoffering' && item === 'rootdisksize'">
            <div>
              {{ dataResource.rootdisksize }} GB
            </div>
          </div>
          <div v-else-if="$route.meta.name === 'buckets' && item === 'size'">
            <div>
              {{ convertKB(dataResource.size) }}
            </div>
          </div>
          <div v-else-if="['template', 'iso'].includes($route.meta.name) && item === 'size'">
            <div>
              {{ sizeInGiB(dataResource.size) }} GiB
            </div>
          </div>
          <div v-else-if="['volume', 'snapshot', 'template', 'iso'].includes($route.meta.name) && item === 'physicalsize'">
            <div>
              {{ sizeInGiB(dataResource.physicalsize) }} GiB
            </div>
          </div>
          <div v-else-if="['volume', 'snapshot', 'template', 'iso'].includes($route.meta.name) && item === 'virtualsize'">
            <div>
              {{ sizeInGiB(dataResource.virtualsize) }} GiB
            </div>
          </div>
          <div v-else-if="$route.meta.name === 'snapshot' && item === 'chainsize'">
            <div>
              {{ sizeInGiB(dataResource.chainsize) }} GiB
            </div>
          </div>
          <div v-else-if="['volume', 'snapshot', 'template', 'iso'].includes($route.meta.name) && item === 'usedfsbytes'">
            <div>
              {{ parseFloat(dataResource.usedfsbytes / (1024.0 * 1024.0 * 1024.0)).toFixed(2) }} GiB
            </div>
          </div>
          <div v-else-if="['name', 'type'].includes(item)">
            <span v-if="['USER.LOGIN', 'USER.LOGOUT', 'ROUTER.HEALTH.CHECKS', 'FIREWALL.CLOSE', 'ALERT.SERVICE.DOMAINROUTER'].includes(dataResource[item])">{{ $t(dataResource[item].toLowerCase()) }}</span>
            <span v-else>{{ dataResource[item] }}</span>
          </div>
          <div v-else-if="['created', 'sent', 'lastannotated', 'collectiontime', 'lastboottime', 'lastserverstart', 'lastserverstop', 'removed', 'effectiveDate', 'endDate'].includes(item)">
            {{ $toLocaleDate(dataResource[item]) }}
          </div>
          <div style="white-space: pre-wrap;" v-else-if="$route.meta.name === 'quotatariff' && item === 'description'">{{ dataResource[item] }}</div>
          <div v-else-if="$route.meta.name === 'userdata' && item === 'userdata'">
            <div style="white-space: pre-wrap;"> {{ decodeUserData(dataResource.userdata)}} </div>
          </div>
          <div v-else-if="$route.meta.name === 'cniconfiguration' && item === 'userdata'">
            <div style="white-space: pre-wrap;"> {{ dataResource.userdata}} </div>
          </div>
          <div v-else-if="$route.meta.name === 'guestnetwork' && item === 'egressdefaultpolicy'">
            {{ dataResource[item]? $t('message.egress.rules.allow') : $t('message.egress.rules.deny') }}
          </div>
          <div v-else-if="item === 'securitygroup'">
            <div v-if="dataResource[item] && dataResource[item].length > 0">
              <span v-for="(securityGroup, idx) in dataResource[item]" :key="idx">
                {{ securityGroup.name }} &nbsp;
              </span>
            </div>
          </div>
          <div v-else-if="$route.meta.name === 'computeoffering' && offeringDetails.includes(item)">
            {{ dataResource.serviceofferingdetails[item] }}
          </div>
          <div v-else-if="item === 'headers'" style="white-space: pre-line;">
            {{ dataResource[item] }}
          </div>
          <div v-else-if="item === 'payload'" style="white-space: pre-wrap;">
            {{ JSON.stringify(JSON.parse(dataResource[item]), null, 4) || dataResource[item] }}
          </div>
          <div v-else-if="item === 'dedicatedresources'">
            <div v-for="(resource, idx) in sortDedicatedResourcesByName(dataResource[item])" :key="idx">
              <div>
                <router-link :to="getResourceLink(resource.resourcetype, resource.resourceid)">
                  {{ resource.resourcename }}
                </router-link>
              </div>
            </div>
          </div>
          <div v-else-if="item === 'usersource'">
            {{ $t(getUserSourceLabel(dataResource[item])) }}
          </div>
          <div v-else-if="$route.meta.name === 'kubernetes' && item === 'cniconfigname'">
              <router-link :to="{ path: '/cniconfiguration/' + dataResource.cniconfigurationid }">{{ dataResource.cniconfigname }}</router-link>
          </div>
          <div v-else-if="item === 'allowedroletypes' && Array.isArray(dataResource[item])">
            {{ dataResource[item].join(', ') }}
          <div v-else-if="item === 'summary' || item === 'description'">
            <div :class="{ preline: $route.path.startsWith('/alertRules') }">
              {{ getSummaryOrDescriptionPlain(item) }}
            </div>
          </div>
          <!-- 연산자 라벨(기호 X) -->
          <div v-else-if="$route.path.includes('/alertRules') && item === 'operator'">
            {{ formatOperatorLabel(dataResource.operator) }}
          </div>
          <!-- 임계치 라벨: 단일/범위 통합 표기 -->
          <div v-else-if="$route.path.includes('/alertRules') && item === 'threshold'">
            {{ formatThresholdLabel(dataResource.operator, dataResource.threshold, dataResource.threshold2) }}
          </div>
          <div v-else>{{ dataResource[item] }}</div>
        </div>
      </a-list-item>
      <a-list-item v-else-if="['cluster', 'zone'].includes($route.meta.name) && item === 'haenable'">
        <div>
          <strong>{{ $t('label.ha.enable') }}</strong>
          <br/>
          <div>{{ dataResource.resourcedetails?.resourceHAEnabled }}</div>
        </div>
      </a-list-item>
      <a-list-item v-else-if="item === 'ip6address' && ipV6Address && ipV6Address.length > 0">
        <div>
          <strong>{{ $t('label.' + String(item).toLowerCase()) }}</strong>
          <br/>
          <div>{{ ipV6Address }}</div>
        </div>
      </a-list-item>
      <a-list-item v-else-if="(item === 'privatemtu' && !['L2', 'Shared'].includes(dataResource['type'])) || (item === 'publicmtu' && dataResource['type'] !== 'L2')">
        <div>
          <strong>{{ $t('label.' + String(item).toLowerCase()) }}</strong>
          <br/>
          <div>{{ dataResource[item] }}</div>
        </div>
      </a-list-item>
      <a-list-item v-else-if="(item === 'zoneid' && $route.path.includes('/snapshotpolicy'))">
        <div>
          <strong>{{ $t('label.' + String(item).toLowerCase()) }}</strong>
          <br/>
          <div>{{ dataResource[item] }}</div>
        </div>
      </a-list-item>
      <a-list-item v-else-if="['startdate', 'enddate'].includes(item)">
        <div>
          <strong>{{ $t('label.' + item.replace('date', '.date.and.time'))}}</strong>
          <br/>
          <div>{{ $toLocaleDate(dataResource[item]) }}</div>
        </div>
      </a-list-item>
      <a-list-item v-else-if="item === 'leaseexpirydate' && dataResource[item]">
        <div>
          <strong>{{ $t('label.' + item.replace('date', '.date.and.time'))}}</strong>
          <br/>
          <div>{{ $toLocaleDate(dataResource[item]) }}</div>
        </div>
      </a-list-item>
      <a-list-item v-else-if="item === 'details' && $route.meta.name === 'storagepool' && dataResource[item].rbd_default_data_pool">
        <div>
          <strong>{{ $t('label.data.pool') }}</strong>
          <br/>
          <div>{{ dataResource[item].rbd_default_data_pool }}</div>
        </div>
      </a-list-item>
      <a-list-item v-else-if="item === 'details' && ['extension', 'customaction'].includes($route.meta.name) && dataResource[item] && Object.keys(dataResource[item]).length > 0">
        <div>
          <strong>{{ $t('label.configuration.details') }}</strong>
          <br/>
          <div>
            <object-list-table :data-map="dataResource[item]" />
          </div>
        </div>
      </a-list-item>
      <a-list-item v-else-if="item === 'parameters' && ['customaction'].includes($route.meta.name) && Array.isArray(dataResource[item]) && dataResource[item].length > 0">
        <div>
          <strong>{{ $t('label.' + String(item).toLowerCase()) }}</strong>
          <br/>
          <div>
            <object-list-table :showHeader="true" :data-array="dataResource[item]" />
          </div>
        </div>
      </a-list-item>
      <external-configuration-details
        v-else-if="item === 'externaldetails' && (['host', 'computeoffering'].includes($route.meta.name) || (['cluster'].includes($route.meta.name) && dataResource.extensionid))"
        :resource="dataResource" />
      <a-list-item v-else-if="['migrationip'].includes(item)">
        <div>
          <strong>{{ $t('label.migrationip') }}</strong>
          <br/>
          <div>{{ dataResource[item] }}&nbsp</div>
        </div>
      </a-list-item>
    </template>
    <HostInfo :resource="dataResource" v-if="$route.meta.name === 'host' && 'listHosts' in $store.getters.apis" />
    <DedicateData :resource="dataResource" v-if="dedicatedSectionActive" />
    <VmwareData :resource="dataResource" v-if="$route.meta.name === 'zone' && 'listVmwareDcs' in $store.getters.apis" />
  </a-list>
</template>

<script>
import DedicateData from './DedicateData'
import HostInfo from '@/views/infra/HostInfo'
import VmwareData from './VmwareData'
import ObjectListTable from '@/components/view/ObjectListTable'
import ExternalConfigurationDetails from '@/views/extension/ExternalConfigurationDetails'
import { genericCompare } from '@/utils/sort'
import { getAPI } from '@/api'

export default {
  name: 'DetailsTab',
  components: {
    DedicateData,
    HostInfo,
    VmwareData,
    ObjectListTable,
    ExternalConfigurationDetails
  },
  props: {
    resource: {
      type: Object,
      required: true
    },
    items: {
      type: Object,
      default: () => {}
    },
    loading: {
      type: Boolean,
      default: false
    },
    bordered: {
      type: Boolean,
      default: false
    },
    tab: {
      type: String,
      default: ''
    }
  },
  data () {
    return {
      dedicatedRoutes: ['zone', 'pod', 'cluster', 'host'],
      dedicatedSectionActive: false,
      projectname: '',
      dataResource: {},
      detailsTitles: [],
      licenseInfo: {
        expired: false,
        expiryDate: ''
      },
      licenseCode: ''
    }
  },
  mounted () {
    this.dedicatedSectionActive = this.dedicatedRoutes.includes(this.$route.meta.name)
  },
  computed: {
    customDisplayItems () {
      var items = ['ip4routes', 'ip6routes', 'privatemtu', 'publicmtu', 'provider', 'migrationip', 'details', 'parameters']
      if (this.$route.meta.name === 'webhookdeliveries' || this.$route.meta.name === 'quotasummary') {
        items.push('startdate')
        items.push('enddate')
      } else if (this.$route.meta.name === 'vm') {
        items.push('leaseexpirydate')
      } else if (['cluster', 'host', 'computeoffering'].includes(this.$route.meta.name)) {
        items.push('externaldetails')
      }
      return items
    },
    vnfAccessMethods () {
      if (this.resource.templatetype === 'VNF' && ['vm', 'vnfapp'].includes(this.$route.meta.name)) {
        const accessMethodsDescription = []
        const accessMethods = this.resource.vnfdetails?.access_methods || null
        const username = this.resource.vnfdetails?.username || null
        const password = this.resource.vnfdetails?.password || null
        const sshPort = this.resource.vnfdetails?.ssh_port || 22
        const sshUsername = this.resource.vnfdetails?.ssh_user || null
        const sshPassword = this.resource.vnfdetails?.ssh_password || null
        let httpPath = this.resource.vnfdetails?.http_path || ''
        if (!httpPath.startsWith('/')) {
          httpPath = '/' + httpPath
        }
        const httpPort = this.resource.vnfdetails?.http_port || null
        let httpsPath = this.resource.vnfdetails?.https_path || ''
        if (!httpsPath.startsWith('/')) {
          httpsPath = '/' + httpsPath
        }
        const httpsPort = this.resource.vnfdetails?.https_port || null
        const webUsername = this.resource.vnfdetails?.web_user || null
        const webPassword = this.resource.vnfdetails?.web_password || null

        const credentials = []
        if (username) {
          credentials.push(this.$t('label.username') + ' : ' + username)
        }
        if (password) {
          credentials.push(this.$t('label.password.default') + ' : ' + password)
        }
        if (webUsername) {
          credentials.push('Web ' + this.$t('label.username') + ' : ' + webUsername)
        }
        if (webPassword) {
          credentials.push('Web ' + this.$t('label.password.default') + ' : ' + webPassword)
        }
        if (sshUsername) {
          credentials.push('SSH ' + this.$t('label.username') + ' : ' + sshUsername)
        }
        if (sshPassword) {
          credentials.push('SSH ' + this.$t('label.password.default') + ' : ' + sshPassword)
        }

        const managementDeviceIds = []
        if (this.resource.vnfnics) {
          for (const vnfnic of this.resource.vnfnics) {
            if (vnfnic.management) {
              managementDeviceIds.push(vnfnic.deviceid)
            }
          }
        }
        const managementIps = []
        for (const nic of this.resource.nic) {
          if (managementDeviceIds.includes(parseInt(nic.deviceid)) && nic.ipaddress) {
            managementIps.push(nic.ipaddress)
            if (nic.publicip) {
              managementIps.push(nic.publicip)
            }
          }
        }

        if (accessMethods) {
          const accessMethodsArray = accessMethods.split(',')
          for (const accessMethod of accessMethodsArray) {
            if (accessMethod === 'console') {
              accessMethodsDescription.push('- VM Console.')
            } else if (accessMethod === 'ssh-password') {
              accessMethodsDescription.push('- SSH with password' + (sshPort ? ' (SSH port is ' + sshPort + ').' : '.'))
            } else if (accessMethod === 'ssh-key') {
              accessMethodsDescription.push('- SSH with key' + (sshPort ? ' (SSH port is ' + sshPort + ').' : '.'))
            } else if (accessMethod === 'http') {
              for (const managementIp of managementIps) {
                const url = 'http://' + managementIp + (httpPort ? ':' + httpPort : '') + httpPath
                accessMethodsDescription.push('- Webpage: <a href="' + url + '" target="_blank>">' + url + '</a>')
              }
            } else if (accessMethod === 'https') {
              for (const managementIp of managementIps) {
                const url = 'https://' + managementIp + (httpsPort ? ':' + httpsPort : '') + httpsPath
                accessMethodsDescription.push('- Webpage: <a href="' + url + '" target="_blank">' + url + '</a>')
              }
            }
          }
        } else {
          accessMethodsDescription.push('- VM Console.')
        }
        if (credentials) {
          accessMethodsDescription.push('<br>' + this.$t('message.vnf.credentials.in.template.vnf.details'))
        }
        return accessMethodsDescription.join('<br>')
      }
      return null
    },
    offeringDetails () {
      return ['maxcpunumber', 'mincpunumber', 'minmemory', 'maxmemory']
    },
    ipV6Address () {
      if (this.dataResource.nic && this.dataResource.nic.length > 0) {
        return this.dataResource.nic.filter(e => { return e.ip6address }).map(e => { return e.ip6address }).join(', ')
      }
      return null
    },
    ip4routes () {
      if (this.resource.ip4routes && this.resource.ip4routes.length > 0) {
        var routes = []
        for (var route of this.resource.ip4routes) {
          routes.push(route.subnet + ' via ' + route.gateway)
        }
        return routes.join('<br>')
      }
      return null
    },
    ip6routes () {
      if (this.resource.ip6routes && this.resource.ip6routes.length > 0) {
        var routes = []
        for (var route of this.resource.ip6routes) {
          routes.push(route.subnet + ' via ' + route.gateway)
        }
        return routes.join('<br>')
      }
      return null
    }
  },
  created () {
    this.dataResource = this.resource
    this.dedicatedSectionActive = this.dedicatedRoutes.includes(this.$route.meta.name)
    if (['host'].includes(this.$route.meta.name)) {
      this.fetchLicenseInfo()
    }
    if (this.$route.path.includes('/alertRules')) {
      this.ensureAlertRuleLoaded()
    }
  },
  watch: {
    resource: {
      deep: true,
      handler (newVal) {
        this.dataResource = newVal
        if ('account' in this.dataResource && this.dataResource.account.startsWith('PrjAcct-')) {
          this.projectname = this.dataResource.account.substring(this.dataResource.account.indexOf('-') + 1, this.dataResource.account.lastIndexOf('-'))
          this.dataResource.projectname = this.projectname
          this.ensureAlertRuleLoaded()
        }
      }
    },
    $route () {
      this.dedicatedSectionActive = this.dedicatedRoutes.includes(this.$route.meta.name)
      this.fetchProjectAdmins()
      this.ensureAlertRuleLoaded()
    }
  },
  methods: {
    decodeUserData (userdata) {
      const decodedData = Buffer.from(userdata, 'base64')
      return decodedData.toString('utf-8')
    },
    fetchProjectAdmins () {
      if (!this.dataResource.owner) {
        return false
      }
      var owners = this.dataResource.owner
      var projectAdmins = []
      for (var owner of owners) {
        projectAdmins.push(Object.keys(owner).includes('user') ? owner.account + '(' + owner.user + ')' : owner.account)
      }
      this.dataResource.account = projectAdmins.join()
    },
    fetchDetails () {
      let details = this.$route.meta.details

      if (!details) {
        return
      }

      if (typeof details === 'function') {
        details = details()
      }

      let detailsKeys = []
      for (const detail of details) {
        if (typeof detail === 'object') {
          const field = detail.field
          detailsKeys.push(field)
          this.detailsTitles[field] = detail.customTitle
        } else {
          detailsKeys.push(detail)
          this.detailsTitles[detail] = detail
        }
      }

      detailsKeys = this.projectname ? [...detailsKeys.filter(x => x !== 'account'), 'projectname'] : detailsKeys
      return detailsKeys
    },
    getDetailTitle (detail) {
      return `label.${String(this.detailsTitles[detail]).toLowerCase()}`
    },
    getResourceLink (type, id) {
      return `/${type.toLowerCase()}/${id}`
    },
    sortDedicatedResourcesByName (resources) {
      resources.sort((resource, otherResource) => {
        return genericCompare(resource.resourcename, otherResource.resourcename)
      })

      return resources
    },
    convertKB (val) {
      if (val < 1024) return `${(val).toFixed(2)} KB`
      if (val < 1024 * 1024) return `${(val / 1024).toFixed(2)} MB`
      if (val < 1024 * 1024 * 1024) return `${(val / 1024 / 1024).toFixed(2)} GB`
      if (val < 1024 * 1024 * 1024 * 1024) return `${(val / 1024 / 1024 / 1024).toFixed(2)} TB`
      return val
    },
    getUserSourceLabel (source) {
      if (source === 'saml2') {
        source = 'saml'
      } else if (source === 'saml2disabled') {
        source = 'saml.disabled'
      }

      return `label.${source}`
    },
    fetchLicenseInfo () {
      const today = new Date()
      today.setHours(0, 0, 0, 0)

      getAPI('licenseCheck', { hostid: this.resource.id }).then(response => {
        const licenseData = response?.null?.licensecheck
        if (licenseData) {
          var expiryDate = new Date(licenseData.expirydate)
          var issuedDate = new Date(licenseData.issueddate)
          expiryDate.setHours(0, 0, 0, 0)
          issuedDate.setHours(0, 0, 0, 0)

          this.dataResource.licenseStartDate = issuedDate.getFullYear() + '-' + this.leftPad(issuedDate.getMonth() + 1) + '-' + this.leftPad(issuedDate.getDate())
          this.dataResource.licenseExpiryDate = expiryDate.getFullYear() + '-' + this.leftPad(expiryDate.getMonth() + 1) + '-' + this.leftPad(expiryDate.getDate())
          this.dataResource.hostId = licenseData.hostid
          this.dataResource.hasLicense = licenseData.haslicense === 'true'
          this.dataResource.licenseValid = licenseData.success === 'true'

          if (today <= expiryDate && today >= issuedDate) {
            this.licenseCode = 'OK'
          } else if (today > expiryDate) {
            this.licenseCode = 'PASSED'
          } else if (today < issuedDate) {
            this.licenseCode = 'NOSTART'
          } else {
            this.licenseCode = 'NONE'
          }
        } else {
          this.dataResource.licenseStartDate = ''
          this.dataResource.licenseExpiryDate = ''
          this.dataResource.hasLicense = false
          this.dataResource.licenseValid = false
          this.licenseCode = 'NONE'
        }
      })
    },
    calculateDday (expiryDate) {
      const today = new Date()
      const expiry = new Date(expiryDate)
      const diffTime = expiry - today
      const diffDays = Math.ceil(diffTime / (1000 * 60 * 60 * 24))
      return diffDays
    },
    leftPad (value) {
      if (value >= 10) {
        return value
      }
      return `0${value}`
    },
    sizeInGiB (sizeInBytes) {
      if (!sizeInBytes || sizeInBytes === 0) {
        return '0.00'
      }
      return parseFloat(sizeInBytes / (1024.0 * 1024.0 * 1024.0)).toFixed(2)
    },
    formatOperatorLabel (op) {
      const o = op == null ? '' : String(op).toLowerCase()
      if (o === 'within_range') return this.$t('label.operator.within')
      if (o === 'outside_range') return this.$t('label.operator.outside')
      if (o === 'gte') return this.$t('label.operator.above')
      if (o === 'lte') return this.$t('label.operator.below')
      if (o === 'between') return this.$t('label.operator.within')
      if (o === 'outside') return this.$t('label.operator.outside')
      if (o === 'gt') return this.$t('label.operator.above')
      if (o === 'lt') return this.$t('label.operator.below')
      return o
    },

    formatThresholdLabel (op, t1, t2) {
      const o = op == null ? '' : String(op).toLowerCase()
      const v1 = t1 === null || t1 === undefined ? null : String(t1)
      // 범위 상한이 누락된 환경 대비 보강
      let v2 = t2 === null || t2 === undefined ? null : String(t2)
      if (!v2 && this.dataResource) {
        const alt = this.dataResource.upper || this.dataResource.thresholdUpper
        if (alt !== null && alt !== undefined) v2 = String(alt)
      }

      const above = this.$t('label.operator.above')
      const below = this.$t('label.operator.below')
      const within = this.$t('label.operator.within')
      const outside = this.$t('label.operator.outside')

      if (o === 'within_range' || o === 'between') {
        return v1 && v2 ? `${v1}-${v2} ${within}` : '—'
      }
      if (o === 'outside_range' || o === 'outside') {
        return v1 && v2 ? `${v1} ${below} 또는 ${v2} ${above}` : outside
      }

      if (!v1) return '—'
      if (o === 'gt' || o === 'gte') return `${v1} ${above}`
      if (o === 'lt' || o === 'lte') return `${v1} ${below}`

      return v2 ? `${v1}-${v2}` : v1
    },
    // ▼ 추가: uid로 들어온 경우 스스로 단건을 로드해 dataResource를 보정
    async ensureAlertRuleLoaded () {
      // /alertRules 상세 화면에서만 동작
      if (!this.$route.path.startsWith('/alertRules')) return

      const routeKey = String(this.$route.params.id || '')
      if (!routeKey) return

      // 이미 prop으로 받은 resource가 일치하면 그대로 사용
      const r = this.resource || {}
      const currUid = r?.metadata?.rule_uid || r?.uid
      if (r && (r.id === routeKey || currUid === routeKey || r.name === routeKey)) {
        this.dataResource = r
        return
      }

      const takeFirst = (res) => {
        const list =
          res?.listwallalertrulesresponse?.wallalertrule ||
          res?.listwallalertrulesresponse?.wallalertruleresponse || []
        return Array.isArray(list) && list.length ? list[0] : null
      }

      try {
        let found = null

        // 1) uid로 조회 (routeKey에 ':' 없으면 uid로 간주)
        if (!routeKey.includes(':')) {
          const r1 = await getAPI('listWallAlertRules', {
            listall: true, page: 1, pagesize: 1, uid: routeKey
          })
          found = takeFirst(r1)
        }

        // 2) id로 조회 (콜론 포함 키 or 1단계 실패시)
        if (!found) {
          const r2 = await getAPI('listWallAlertRules', {
            listall: true, page: 1, pagesize: 1, id: routeKey
          })
          found = takeFirst(r2)
        }

        // 3) 최종 폴백: 전체 받아서 프론트에서 uid/id/name 매칭
        if (!found) {
          const r3 = await getAPI('listWallAlertRules', {
            listall: true, page: 1, pagesize: 2000
          })
          const all =
            r3?.listwallalertrulesresponse?.wallalertrule ||
            r3?.listwallalertrulesresponse?.wallalertruleresponse || []
          found = all.find(x =>
            x?.uid === routeKey ||
            x?.metadata?.rule_uid === routeKey ||
            x?.id === routeKey ||
            x?.name === routeKey
          )
        }

        if (found) {
          this.dataResource = found
        } else {
          this.$notification.warning({
            message: '규칙을 찾지 못했습니다',
            description: `키=${routeKey} 에 해당하는 항목이 없습니다.`
          })
        }
      } catch (e) {
        // 조용히 실패 처리(콘솔만)
        /* eslint-disable no-console */
        console.warn('[DetailsTab] ensureAlertRuleLoaded failed:', e)
        /* eslint-enable no-console */
      }
    },
    getSummaryOrDescriptionPlain (key) {
      const hasValue = (v) => v !== null && v !== undefined && v !== ''
      const top = this.dataResource && this.dataResource[key]
      if (hasValue(top)) {
        return String(top)
      }
      const ann = this.dataResource && this.dataResource.annotations ? this.dataResource.annotations : {}
      if (key === 'summary') {
        const v = ann.summary || ann.__summary__ || ann.message || ''
        return hasValue(v) ? String(v) : '—'
      }
      if (key === 'description') {
        const v = ann.description || ann.__description__ || ''
        return hasValue(v) ? String(v) : '—'
      }
      return '—'
    }
  }
}
</script>

<style scoped>
.preline {
  white-space: pre-line;
  word-break: break-word;
}
</style>
