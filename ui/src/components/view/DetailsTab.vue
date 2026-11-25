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
  <a-alert
    v-if="$route.meta.name === 'role' && dataResource.id && (dataResource.isdefault || dataResource.inuse === true)"
    show-icon
    type="info"
    :message="$t(dataResource.isdefault ? 'message.role.details.default' : 'message.role.details.linked')" />
  <VnfAccessNotice v-if="['vm', 'vnfapp'].includes($route.meta.name)" :resource="resource" />
  <PvlanSupportNotice v-if="guestPvlan" :zone-id="dataResource.zoneid" :physical-network-id="dataResource.physicalnetworkid || ''" />
  <a-alert
    v-if="l2Network"
    type="info"
    show-icon
    class="l2-details-notice"
    :message="$t(l2Network.configDrive ? 'message.l2.network.configdrive' : 'message.l2.network.basic')" />
  <router-link
    v-if="$route.meta.name === 'webhookdeliveries' && dataResource.webhookid"
    :to="{ path: '/webhook/' + dataResource.webhookid, query: { tab: 'recent.deliveries' } }">
    {{ $t('label.webhook.delivery.back') }}
  </router-link>
  <a-list
    class="resource-details-list"
    :class="{ 'template-details-list': ['template', 'iso'].includes($route.meta.name), 's2s-gateway-details': $route.meta.name === 's2svpn', 'vm-details-list': kmsRoute($route) || ['vm', 'vnfapp', 'publicip', 's2svpn', 'acllist', 'event', 'vpnuser', 'project', 'guestvlans', 'ipv4subnets', 'dnszone', 'dnsserver', 'zone', 'physicalnetwork', 'host', 'cluster', 'ldapsetting', 'oauthsetting', 'storagepool', 'imagestore', 'secondarystorageselector'].includes($route.meta.name) }"
    size="small"
    :dataSource="(fetchDetails() || []).concat(Object.keys(allocationDetails))">
    <template #renderItem="{item}">
      <a-list-item v-if="kmsRoute($route) && ['algorithm', 'keybits', 'vendorname', 'protocol', 'enabled', 'hsmzonescope', 'hsmownerscope', 'state'].includes(item)">
        <div class="kms-metadata">
          <strong>{{ $t(item === 'state' ? 'label.hsm.connection.state' : 'label.' + item) }}</strong>
          <template v-if="item === 'enabled'">
            <status v-if="typeof dataResource.enabled === 'boolean'" :text="String(dataResource.enabled)" :display-label="$t(dataResource.enabled ? 'label.enabled' : 'label.disabled')" display-text />
            <div v-else>{{ $t('label.unknown') }}</div>
          </template>
          <div v-else-if="item === 'hsmzonescope'">{{ $t(dataResource.zoneid ? 'label.hsm.create.zone.specific' : 'label.hsm.create.zone.global') }}</div>
          <div v-else-if="item === 'hsmownerscope'">{{ hsmOwnerScope(dataResource) ? $t('label.hsm.create.scope.' + hsmOwnerScope(dataResource)) : $t('label.unknown') }}</div>
          <div v-else-if="item === 'protocol'">{{ kmsProtocolOptions($store.getters.features, dataResource.protocol).find(provider => provider.id === dataResource.protocol)?.name || $t('label.unknown') }}</div>
          <div v-else-if="item === 'vendorname'">{{ dataResource.vendor || dataResource.vendorname || $t('label.unknown') }}</div>
          <div v-else>{{ dataResource[item] ?? $t(item === 'state' ? 'label.hsm.connection.unavailable' : 'label.unknown') }}</div>
        </div>
      </a-list-item>
      <a-list-item v-else-if="$route.meta.name === 'host' && ['state', 'resourcestate'].includes(item)">
        <div>
          <strong>{{ $t('label.' + item) }}</strong>
          <div><a-tag :color="hostStatusColor(dataResource[item])">{{ $t(hostStatusLabel(dataResource[item])) }}</a-tag></div>
        </div>
      </a-list-item>
      <a-list-item v-else-if="$route.meta.name === 'runtimebundle' && dataResource.id && ['state', 'validationstatus', 'serviceimpact', 'artifactsize', 'compatibility', 'eligibilityreason'].includes(item)">
        <div class="runtime-catalog-detail-field">
          <strong>{{ $t(['compatibility', 'eligibilityreason'].includes(item) ? 'label.runtime.catalog.' + item : 'label.' + item) }}</strong>
          <div><RuntimeCatalogValue :group="item" :value="dataResource[item]" /></div>
          <small v-if="item === 'compatibility'">{{ $t('message.runtime.catalog.profile.compatibility') }}</small>
        </div>
      </a-list-item>
      <a-list-item v-else-if="$route.meta.name === 'cluster' && ['allocationstate', 'managedstate', 'clustertype', 'haenable'].includes(item)">
        <div>
          <strong>{{ $t('label.' + item) }}</strong>
          <div>
            <status v-if="item === 'allocationstate'" :text="dataResource.allocationstate || ''" :display-label="$t(clusterStateLabel(item, dataResource[item]))" display-text />
            <status v-else-if="item === 'haenable'" :text="zoneFeatureState(dataResource.resourcedetails?.resourceHAEnabled ?? false)" :display-label="$t(zoneFeatureLabel(dataResource.resourcedetails?.resourceHAEnabled ?? false))" display-text />
            <span v-else>{{ $t(clusterStateLabel(item, dataResource[item])) }}</span>
          </div>
        </div>
      </a-list-item>
      <a-list-item v-else-if="$route.meta.name === 'zone' && ['allocationstate', 'haenable', 'localstorageenabled', 'securitygroupsenabled'].includes(item)">
        <div>
          <strong>{{ $t('label.' + item) }}</strong>
          <div>
            <status v-if="item === 'allocationstate'" :text="dataResource.allocationstate || ''" :display-label="dataResource.allocationstate ? '' : $t('label.unknown')" display-text />
            <status v-else-if="item === 'haenable'" :text="zoneFeatureState(dataResource.resourcedetails?.resourceHAEnabled ?? false)" :display-label="$t(zoneFeatureLabel(dataResource.resourcedetails?.resourceHAEnabled ?? false))" display-text />
            <status v-else :text="zoneFeatureState(dataResource[item])" :display-label="$t(zoneFeatureLabel(dataResource[item]))" display-text />
          </div>
        </div>
      </a-list-item>
      <a-list-item v-else-if="$route.meta.name === 'zone' && ['networkdomain', 'dnssearchorder'].includes(item)">
        <div>
          <strong>{{ $t('label.zone.' + item) }}</strong>
          <div>{{ (item === 'networkdomain' ? dataResource.domain : zoneDnsSearchOrder(dataResource).join(', ')) || '—' }}</div>
        </div>
      </a-list-item>
      <a-list-item v-else-if="$route.meta.name === 'dnszone' && item === 'dnsservername'">
        <div>
          <strong>{{ $t('label.dns.server') }}</strong>
          <div>
            <router-link v-if="dataResource.dnsserverid && $store.getters.apis?.listDnsServers" :to="{ path: '/dnsserver/' + dataResource.dnsserverid }">{{ dataResource.dnsservername }}</router-link>
            <span v-else>{{ dataResource.dnsservername }}</span>
          </div>
        </div>
      </a-list-item>
      <a-list-item v-else-if="$route.meta.name === 'dnszone' && item === 'associatednetworks' && (dataResource.associatednetworks?.length || dataResource.networkname)">
        <div>
          <strong>{{ $t('label.dns.zone.associated.networks') }}</strong>
          <div v-for="network in dataResource.associatednetworks || [dataResource]" :key="network.networkid">
            {{ network.networkname }}<span v-if="network.subdomain"> ({{ network.subdomain }})</span>
          </div>
        </div>
      </a-list-item>
      <a-list-item v-else-if="$route.meta.name === 'guestvlans' && ['allocationstate', 'taken', 'isdedicated'].includes(item)">
        <div>
          <strong>{{ $t(item === 'taken' ? 'label.guest.vlan.taken' : 'label.' + item) }}</strong>
          <div v-if="item === 'allocationstate'"><status :text="dataResource.allocationstate || ''" display-text /></div>
          <div v-else-if="item === 'taken'">{{ dataResource.taken ? $toLocaleDate(dataResource.taken) : '—' }}</div>
          <div v-else>{{ typeof dataResource.isdedicated === 'boolean' ? $t(dataResource.isdedicated ? 'label.yes' : 'label.no') : '—' }}</div>
        </div>
      </a-list-item>
      <a-list-item v-else-if="$route.meta.name === 'role' && ['isdefault', 'ispublic', 'state'].includes(item)">
        <div>
          <strong>{{ $t(item === 'isdefault' ? 'label.role.kind' : 'label.' + item) }}</strong><br />
          <status v-if="item === 'state'" :text="dataResource.state" :display-label="$t('label.' + dataResource.state)" display-text />
          <a-tag v-else-if="item === 'isdefault'">{{ $t(dataResource.isdefault ? 'label.default' : 'label.role.kind.custom') }}</a-tag>
          <a-tag v-else>{{ $t(dataResource.ispublic ? 'label.public' : 'label.role.visibility.private') }}</a-tag>
        </div>
      </a-list-item>
      <a-list-item v-else-if="$route.meta.name === 'vpnuser' && dataResource.id">
        <div>
          <strong>{{ $t(item === 'id' ? 'label.vpn.user.uuid' : getDetailTitle(item)) }}</strong><br />
          <VpnUserValue :resource="dataResource" :field="item" />
        </div>
      </a-list-item>
      <a-list-item v-else-if="$route.meta.name === 'acllist' && item === 'scope' && dataResource.id">
        <div><strong>{{ $t('label.scope') }}</strong><br/><AclScopeValue :resource="dataResource" /></div>
      </a-list-item>
      <a-list-item v-else-if="($route.meta.name !== 's2svpn' || item !== 'fordisplay' || $store.getters.userInfo?.roletype === 'Admin') && (($route.meta.name === 's2svpn' && (item !== 'fordisplay' || $store.getters.userInfo?.roletype === 'Admin')) || ($route.meta.name === 'publicip' && (item === 'virtualmachinename' || publicIpDetailFields.includes(item))) || (['vm', 'vnfapp'].includes($route.meta.name) && (['deleteprotection', 'ostypename', 'osdisplayname', 'state'].includes(item) || vmBooleanFields.has(item))) || allocationDetails[item] || ($route.meta.name === 'ipv4subnets' && ((item === 'networkname' && dataResource.networkid) || (item === 'vpcname' && dataResource.vpcid))) || (item in dataResource && !customDisplayItems.includes(item) && !($route.meta.name === 'dnszone' && item === 'associatednetworks')) || ($route.meta.name === 'ilb' && item === 'loadbalancerrule') || ($route.meta.name === 'guestnetwork' && (item === 'displaytext' || item === 'sharedscope' || (item === 'hideipaddressusage' && dataResource.type === 'Shared'))) || ($route.meta.name === 'computeoffering' && item === 'diskofferingcomputeonly' && dataResource.diskofferingid) || (offeringDetails.includes(item) && dataResource.serviceofferingdetails)|| ($route.path.includes('/alertRules') && ['summary', 'description'].includes(item)))">
        <div style="width: 100%">
          <strong>{{ allocationDetails[item] ? $t(allocationDetails[item].label) : item === 'service' ? $t('label.supportedservices') :
           $route.meta.name === 'cniconfiguration' && item === 'userdata' ? $t('label.' + String($route.meta.name).toLowerCase()) :
           $t(getDetailTitle(item)) }}</strong>
          <a-tooltip v-if="allocationDetails[item]?.help" :title="allocationDetails[item].help"><QuestionCircleOutlined style="margin-left: 8px;"/></a-tooltip>
          <a-tooltip v-if="['volume', 'snapshot', 'template', 'iso'].includes($route.meta.name) && item === 'usedfsbytes'"><template #title>{{ $t('message.usedfsbytes') }}</template><QuestionCircleOutlined style="margin-left: 8px;"/></a-tooltip>
          <a-tooltip v-if="['volume', 'snapshot', 'template', 'iso'].includes($route.meta.name) && item === 'savingrate'"><template #title>{{ $t('message.savingrate') }}</template><QuestionCircleOutlined style="margin-left: 8px;"/></a-tooltip>
          <a-tooltip v-if="$route.meta.name === 'storagepool' && item === 'disksizeallocated'" :title="$t('message.storage.allocated.meaning')"><info-circle-outlined style="margin-left: 8px" /></a-tooltip>
          <br/>
          <S2sGatewayValue v-if="$route.meta.name === 's2svpn'" :resource="dataResource" :field="item" />
          <public-ip-detail-value v-else-if="$route.meta.name === 'publicip' && publicIpDetailFields.includes(item)" :resource="dataResource" :field="item" />
          <iso-detail-value v-else-if="$route.meta.name === 'iso' && presentedIsoValue(item)" :field="item" :value="dataResource[item]" :label="$t(getDetailTitle(item))" />
          <template-detail-value
            v-else-if="$route.meta.name === 'template' && presentedTemplateValue(item)"
            :field="item"
            :value="dataResource[item]"
            :label="$t(getDetailTitle(item))" />
          <status v-else-if="['ipv4subnets', 'dnsserver', 'physicalnetwork'].includes($route.meta.name) && item === 'state'" :text="dataResource.state" display-text />
          <span v-else-if="$route.meta.name === 'ipv4subnets' && item === 'allocated'">{{ $toLocaleDate(dataResource.allocated) }}</span>
          <status v-else-if="$route.meta.name === 'event' && item === 'state'" :text="dataResource.state" display-text />
          <div v-else-if="$route.meta.name === 'event' && item === 'type'">{{ eventTypeLabel(dataResource.type, $t) }}</div>
          <status v-else-if="['vm', 'vnfapp'].includes($route.meta.name) && ['state', 'hostcontrolstate'].includes(item)" :text="String(dataResource[item] || '')" :display-label="dataResource[item] ? '' : $t('label.unknown')" display-text />
          <div v-else-if="['vm', 'vnfapp'].includes($route.meta.name) && ['ostypename', 'osdisplayname'].includes(item)">{{ creationVmOsName(dataResource, $t) || $t('label.unknown') }}</div>
          <div v-else-if="$route.meta.name === 'oauthsetting' && item === 'enabled'">
            <status :text="String(dataResource.enabled)" :display-label="$t(dataResource.enabled ? 'label.enabled' : 'label.disabled')" display-text />
            <div v-if="dataResource.scopeenabled === false">{{ $t('message.oauth.scope.disabled') }}</div>
            <div v-if="dataResource.pluginavailable === false">{{ $t('message.oauth.plugin.unavailable') }}</div>
          </div>
          <div v-else-if="$route.meta.name === 'webhook' && ['scope', 'state', 'sslverification'].includes(item)">
            {{ item === 'sslverification' ? $t(dataResource[item] ? 'label.yes' : 'label.no') : dataResource[item] ? $t('label.' + dataResource[item].toLowerCase()) : '—' }}
          </div>
          <div v-else-if="$route.meta.name === 'webhook' && item === 'payloadurl'" class="webhook-long-value">
            <span>{{ dataResource[item] }}</span>
            <tooltip-button v-if="dataResource[item]" :tooltip="$t('label.copy')" icon="copy-outlined" size="small" :copyResource="dataResource[item]" @copy-success="$message.success($t('label.copied.clipboard'))" />
          </div>
          <div v-else-if="$route.meta.name === 'webhook' && item === 'secretkeyconfigured'">{{ $t((dataResource.secretkeyconfigured ?? !!dataResource.secretkey) ? 'label.yes' : 'label.no') }}</div>
          <div v-else-if="$route.meta.name === 'oauthsetting' && ['secretkeyconfigured', 'configuredenabled'].includes(item)">{{ $t(dataResource[item] ? 'label.yes' : 'label.no') }}</div>
          <div v-else-if="['vm', 'vnfapp'].includes($route.meta.name) && item !== 'deleteprotection' && (vmBooleanFields.has(item) || typeof dataResource[item] === 'boolean')">{{ $t(vmBooleanLabel(dataResource[item])) }}</div>
          <div v-else-if="['vm', 'vnfapp'].includes($route.meta.name) && item === 'deleteprotection'">
            <a-tag :color="dataResource.deleteprotection === true ? 'warning' : dataResource.deleteprotection === false ? 'success' : 'default'">{{ $t('label.vm.protection.' + (resourceReadFailed() ? 'failed' : dataResource.deleteprotection === true ? 'protected' : dataResource.deleteprotection === false ? 'unprotected' : 'unknown')) }}</a-tag>
            <div>{{ $t('message.vm.delete.edit') }}</div>
          </div>
          <div v-else-if="$route.meta.name === 'extension' && ['pathready', 'isuserdefined', 'orchestratorrequirespreparevm'].includes(item)">{{ $t(dataResource[item] ? 'label.yes' : 'label.no') }}</div>
          <div v-else-if="$route.meta.name === 'extension' && ['path', 'id'].includes(item)" class="extension-long-value">
            <span>{{ dataResource[item] }}</span>
            <tooltip-button :tooltip="$t('label.copy')" icon="copy-outlined" size="small" :copyResource="dataResource[item]" @copy-success="$message.success($t('label.copied.clipboard'))" />
          </div>
          <a-tag v-else-if="$route.meta.name === 'acllist' && item === 'fordisplay'">{{ $t(aclDisplayLabel(dataResource.fordisplay)) }}</a-tag>
          <div v-else-if="['acllist', 'ldapsetting'].includes($route.meta.name) && item === 'id'" :class="{ 'acl-details-id': $route.meta.name === 'acllist' }">
            <span>{{ dataResource.id }}</span>
            <tooltip-button
              :tooltip="$t('label.copyid')"
              icon="copy-outlined"
              size="small"
              :copyResource="dataResource.id"
              @copy-success="$message.success($t('label.copied.clipboard'))" />
          </div>
          <template v-else-if="$route.meta.name === 'ldapsetting' && item === 'domainpath'">
            <router-link v-if="dataResource.domainid && 'listDomains' in $store.getters.apis" :to="{ path: '/domain/' + dataResource.domainid, query: { tab: 'details' } }">{{ dataResource.domainpath }}</router-link>
            <span v-else>{{ dataResource.domainid ? dataResource.domainpath : '—' }}</span>
          </template>
          <Ipv4SubnetResourceLink v-else-if="$route.meta?.name === 'ipv4subnets' && ['networkname', 'vpcname'].includes(item)" :record="dataResource" :field="item" />
          <network-ip-vm-link v-else-if="$route.meta.name === 'publicip' && item === 'virtualmachinename'" :record="dataResource" />
          <InternalLbRules v-else-if="$route.meta.name === 'ilb' && item === 'loadbalancerrule'" :record="dataResource" :loading="loading" />
          <div v-else-if="allocationDetails[item]">{{ allocationDetails[item].value }}</div>
          <div v-else-if="item === 'vlan' && guestPvlan">
            {{ guestPvlan.primary }}
            <div>{{ $t('label.isolatedpvlantype') }} · {{ $t('label.l2.pvlan.' + guestPvlan.type) }}</div>
            <div>{{ $t('label.isolatedpvlanid') }} · {{ guestPvlan.secondary }}</div>
          </div>
          <template v-else-if="item === 'sharedscope' && sharedScope">
            {{ $t(sharedScope) }}
            <div v-if="sharedScope === 'label.domain' && dataResource.subdomainaccess != null">
              {{ $t('label.subdomainaccess') }} · {{ $t(dataResource.subdomainaccess ? 'label.yes' : 'label.no') }}
            </div>
          </template>
          <GuestNetworkDescription v-else-if="$route.meta.name === 'guestnetwork' && item === 'displaytext'" :value="dataResource.displaytext" />
          <div v-else-if="$route.meta.name === 'guestnetwork' && item === 'hideipaddressusage'" class="network-ip-usage-detail">
            <span>{{ $t(networkIpUsageLabel(dataResource)) }}</span>
            <p>{{ $t('message.network.ip.usage.help') }}</p>
          </div>
          <status
            v-else-if="$route.meta.name === 'buckets' && item === 'credentialstate'"
            :text="dataResource[item] === 'Verified' ? 'ready' : 'error'"
            :display-label="$t(dataResource[item] === 'Verified' ? 'label.objectstore.credential.verified' : 'label.objectstore.credential.unverified')"
            display-text />
          <status
v-else-if="$route.meta.name === 'buckets' && item === 'quotaoperationstate' && dataResource[item]"
            :text="dataResource[item] === 'Succeeded' ? 'Running' : dataResource[item] === 'Failed' ? 'Error' : 'Pending'"
            :display-label="$t('state.bucket.quota.' + dataResource[item].toLowerCase())"
display-text />
          <span v-else-if="$route.meta.name === 'buckets' && item === 'usagestatus'">{{ $t('label.bucket.usage.' + (['Fresh', 'Stale', 'Error', 'Unknown'].includes(dataResource[item]) ? dataResource[item] : 'Unknown').toLowerCase()) }}</span>
          <span v-else-if="$route.meta.name === 'buckets' && ['usagelastupdated', 'usagelastattempt'].includes(item)">{{ dataResource[item] ? $toLocaleDate(dataResource[item]) : $t('label.unknown') }}</span>
          <span v-else-if="$route.meta.name === 'buckets' && item === 'quotaoperationupdated'">{{ dataResource[item] ? $toLocaleDate(dataResource[item]) : $t('label.unknown') }}</span>
          <span v-else-if="$route.meta.name === 'buckets' && item === 'credentialchecked'">{{ dataResource[item] ? $toLocaleDate(dataResource[item]) : $t('label.unknown') }}</span>
          <status v-else-if="$route.meta.name === 'buckets' && item === 'state'" :text="String(dataResource[item] || '')" display-text />
          <a-tag v-else-if="$route.meta.name === 'buckets' && ['encryption', 'versioning', 'objectlocking'].includes(item)">{{ $t(dataResource[item] === true || dataResource[item] === 'true' ? 'label.enabled' : dataResource[item] === false || dataResource[item] === 'false' ? 'label.disabled' : 'label.unknown') }}</a-tag>
          <span v-else-if="$route.meta.name === 'buckets' && item === 'policy'">{{ ['Public', 'Private'].includes(dataResource[item]) ? $t('label.bucket.policy.' + dataResource[item].toLowerCase()) : dataResource[item] }}</span>
          <!-- Object Store used_size retains the same legacy KiB unit as bucket.size. -->
          <span v-else-if="$route.meta.name === 'objectstore' && item === 'storageused'">{{ $bytesToHumanReadableSize(Number(dataResource[item] || 0) * 1024) }}</span>
          <span v-else-if="$route.meta.name === 'objectstore' && ['storagetotal', 'storageallocated'].includes(item)">{{ $bytesToHumanReadableSize(dataResource[item]) }}</span>
          <AffinityGroupPolicy v-else-if="$route.meta.name === 'affinitygroup' && item === 'type'" :type="dataResource.type" />
          <div v-else-if="$route.meta.name === 'kubernetes' && ['autoscalingenabled', 'csienabled'].includes(item)">{{ $t(dataResource[item] ? 'label.enabled' : 'label.disabled') }}</div>
          <div v-else-if="$route.meta.name === 'kubernetes' && item === 'clustertype'">{{ $t(dataResource[item] === 'ExternalManaged' ? 'label.external.managed' : 'label.cloud.managed') }}</div>
          <div v-else-if="$route.meta.name === 'vm' && item === 'vbmcport'">
            <span>{{ dataResource[item] === 'None' ? $t('label.vbmc.Unallocated') : dataResource[item] }}</span>
          </div>
          <div v-else-if="Array.isArray(dataResource[item]) && item === 'service'">
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
              <router-link v-if="!dataResource['vmbackupofferingremoved']" :to="{ path: '/volume/' + volume.uuid }">{{ volume.type }} - {{ volume.uuid }}</router-link>
              <span v-else>{{ volume.type }} - {{ volume.uuid }}</span> ({{ parseFloat(volume.size / (1024.0 * 1024.0 * 1024.0)).toFixed(1) }} GB)
            </div>
          </div>
          <div v-else-if="$route.meta.name === 'vm' && item === 'qemuagentversion'">
            {{ dataResource[item] === 'Not Installed' ? $t('label.state.qemuagentversion.notinstalled') : dataResource[item]}}
          </div>
          <div v-else-if="$route.meta.name === 'vm' && item === 'ipaddress'">
            {{ activeNicAddresses('ipaddress') }}
          </div>
          <div v-else-if="$route.meta.name === 'vm' && item === 'ip6address'">
            {{ activeNicAddresses('ip6address') }}
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
          <LinkedDiskOffering v-else-if="$route.meta.name === 'computeoffering' && item === 'diskofferingname'" :resource="dataResource" />
          <LinkedDiskOffering v-else-if="$route.meta.name === 'computeoffering' && item === 'diskofferingcomputeonly'" :resource="dataResource" connection />
          <div v-else-if="$route.meta.name === 'buckets' && item === 'size'">
            <div>
              {{ convertKB(dataResource.size) }}
            </div>
          </div>
          <div v-else-if="$route.meta.name === 'buckets' && item === 'quota'">
            <div>
              {{ Number(dataResource.quota) > 0 ? dataResource.quota + ' GiB' : $t('label.bucket.quota.legacy') }}
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
          <div v-else-if="$route.meta.name === 'imagestore' && ['disksizetotal', 'disksizeused'].includes(item)">
            <div>
              {{ $bytesToHumanReadableSize(dataResource[item]) }}
            </div>
          </div>
          <div v-else-if="$route.meta.name === 'guestnetwork' && item === 'type'">{{ $t(networkTypeLabel(dataResource[item])) }}</div>
          <div v-else-if="['name', 'type'].includes(item)">
            <span v-if="!($route.meta.name === 'ssh' && item === 'name') && ['USER.LOGIN', 'USER.LOGOUT', 'ROUTER.HEALTH.CHECKS', 'FIREWALL.CLOSE', 'ALERT.SERVICE.DOMAINROUTER'].includes(dataResource[item])">{{ $t(dataResource[item].toLowerCase()) }}</span>
            <span v-else>{{ dataResource[item] }}</span>
          </div>
          <div v-else-if="['created', 'sent', 'lastannotated', 'collectiontime', 'lastboottime', 'lastserverstart', 'lastserverstop', 'removed', 'effectiveDate', 'endDate', 'startdate', 'enddate'].includes(item)">
            {{ $toLocaleDate(dataResource[item]) }}
          </div>
          <code-highlight v-else-if="['activationRule'].includes(item)" language="javascript">
            {{ dataResource[item] }}
          </code-highlight>
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
          <div v-else-if="$route.meta.name === 'account' && item === 'apikeyaccess' && dataResource[item]">
            <a-tag>{{ $t(dataResource[item] === 'INHERIT' ? 'label.account.apikey.inherit' : 'label.' + dataResource[item].toLowerCase()) }}</a-tag>
          </div>
          <div v-else-if="$route.meta.name === 'account' && item === 'roletype' && dataResource[item]">
            {{ $t('label.account.role.' + dataResource[item].toLowerCase()) }}
          </div>
          <div v-else-if="item === 'usersource'">
            {{ $t(getUserSourceLabel(dataResource[item])) }}
          </div>
          <div v-else-if="$route.meta.name === 'kubernetes' && item === 'cniconfigname'">
              <router-link :to="{ path: '/cniconfiguration/' + dataResource.cniconfigurationid }">{{ dataResource.cniconfigname }}</router-link>
          </div>
          <div v-else-if="item === 'allowedroletypes' && Array.isArray(dataResource[item])">
            {{ dataResource[item].join(', ') }}
          </div>
          <div v-else-if="item === 'summary' || item === 'description'">
            <div :class="{ preline: $route.path.startsWith('/alertRules') }">
              {{ getSummaryOrDescriptionPlain(item) || ($route.meta.name === 'affinitygroup' ? '—' : '') }}
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
      <a-list-item v-else-if="item === 'ipsecpsk'">
        <div style="width: 100%">
          <strong>{{ $t('label.ipsecpsk') }}</strong><br/>
          <VpnPsk :value="dataResource[item]" :resource="resource" :permission="$route.meta.name === 'vpncustomergateway' ? 'listVpnCustomerGateways' : 'listVpnConnections'" />
        </div>
      </a-list-item>
      <a-list-item v-else-if="item === 'secretkey' && dataResource[item]">
        <div>
          <strong>{{ $t('label.secretkey') }}</strong>
          <tooltip-button
            tooltipPlacement="right"
            :tooltip="$t('label.copy') + ' ' + $t('label.secretkey')"
            icon="CopyOutlined"
            type="dashed"
            size="small"
            @onClick="$message.success($t('label.copied.clipboard'))"
            :copyResource="dataResource[item]" />
          <br/>
          <div>{{ dataResource[item].substring(0, 20) }}...</div>
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
      <a-list-item v-else-if="['startdate', 'enddate'].includes(item) && dataResource[item]">
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
      <a-list-item v-else-if="item === 'details' && ['extension', 'customaction', 'hsmprofile'].includes($route.meta.name) && dataResource[item] && Object.keys(dataResource[item]).length > 0">
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
      <a-list-item v-else-if="item === 'provider' && $route.path.includes('/dnsserver')">
        <div>
          <strong>{{ $t('label.provider') }}</strong>
          <br/>
          <div>{{ dataResource[item] }}</div>
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
      <div v-else-if="item === 'backupofferingdetails'">
        <a-list-item
          v-for="(value, key) in visibleBackupOfferingDetails"
          :key="key">
          <div>
            <strong>{{ $t('label.' + String(key).toLowerCase()) }}</strong>
            <br/>
            <div>
              {{ value }}
            </div>
          </div>
        </a-list-item>
      </div>
    </template>
    <HostInfo :resource="dataResource" v-if="$route.meta.name === 'host' && 'listHosts' in $store.getters.apis" />
    <DedicateData :resource="dataResource" v-if="dedicatedSectionActive" />
    <VmwareData :resource="dataResource" v-if="$route.meta.name === 'zone' && 'listVmwareDcs' in $store.getters.apis" />
  </a-list>
</template>

<script>
import RuntimeCatalogValue from '@/components/view/RuntimeCatalogValue'
import { hostStatusColor, hostStatusLabel } from '@/utils/hostPresentation'
import { clusterStateLabel } from '@/utils/clusterSearch'
import { zoneDnsSearchOrder, zoneFeatureLabel, zoneFeatureState } from '@/utils/zone'
import Ipv4SubnetResourceLink from '@/components/widgets/Ipv4SubnetResourceLink'
import AclScopeValue from '@/components/view/AclScopeValue'
import { aclDisplayLabel } from '@/utils/vpcAclAccess'
import { eventTypeLabel } from '@/utils/eventTimeline'
import S2sGatewayValue from '@/components/view/S2sGatewayValue'
import { kmsRoute, hsmOwnerScope, kmsProtocolOptions } from '@/utils/kmsSearch'
import { networkTypeLabel } from '@/utils/networkLifecycle'
import { l2ConfigDrive, l2PvlanDetails } from '@/utils/l2Network'
import { sharedScopeLabel } from '@/utils/sharedNetwork'
import Status from '@/components/widgets/Status'
import PublicIpDetailValue from '@/components/widgets/PublicIpDetailValue'
import VpnUserValue from '@/components/widgets/VpnUserValue'
import { publicIpDetailFields, publicIpFieldLabel } from '@/utils/publicIpDetails'
import TemplateDetailValue from '@/components/widgets/TemplateDetailValue'
import IsoDetailValue from '@/components/widgets/IsoDetailValue'
import { presentedIsoValue } from '@/utils/isoZoneOperations'
import { presentedTemplateValue } from '@/utils/templateDetails'
import NetworkIpVmLink from '@/components/widgets/NetworkIpVmLink'
import { creationVmDetails, creationVmOsName, vmBooleanLabel, vmBooleanFields } from '@/utils/creationVmPresentation'
import { getAblestackBackupOfferingVisibleDetails } from '@/utils/ablestackBackupPolicy'
import AffinityGroupPolicy from '@/views/compute/AffinityGroupPolicy'
import GuestNetworkDescription from '@/components/view/GuestNetworkDescription'
import { networkIpUsageLabel } from '@/utils/networkIpUsage'
import LinkedDiskOffering from '@/components/offering/LinkedDiskOffering'
import DedicateData from './DedicateData'
import HostInfo from '@/views/infra/HostInfo'
import VmwareData from './VmwareData'
import ObjectListTable from '@/components/view/ObjectListTable'
import ExternalConfigurationDetails from '@/views/extension/ExternalConfigurationDetails'
import TooltipButton from '@/components/widgets/TooltipButton'
import { genericCompare } from '@/utils/sort'
import { getAPI } from '@/api'
import CodeHighlight from 'vue-code-highlight/src/CodeHighlight.vue'
import 'vue-code-highlight/themes/prism-okaidia.css'

import { allocationDetailFields } from '@/utils/volumeAllocation'
import PvlanSupportNotice from '@/components/network/PvlanSupportNotice'
import VpnPsk from '@/components/network/VpnPsk'
import InternalLbRules from '@/components/network/InternalLbRules'
import VnfAccessNotice from '@/components/compute/VnfAccessNotice'

export default {
  name: 'DetailsTab',
  components: {
    RuntimeCatalogValue,
    Ipv4SubnetResourceLink,
    VpnUserValue,
    AclScopeValue,
    S2sGatewayValue,
    PublicIpDetailValue,
    VnfAccessNotice,
    InternalLbRules,
    TemplateDetailValue,
    IsoDetailValue,
    VpnPsk,
    PvlanSupportNotice,
    NetworkIpVmLink,
    Status,
    LinkedDiskOffering,
    AffinityGroupPolicy,
    GuestNetworkDescription,
    DedicateData,
    HostInfo,
    VmwareData,
    ObjectListTable,
    ExternalConfigurationDetails,
    TooltipButton,
    CodeHighlight
  },
  inject: { resourceReadFailed: { default: () => () => false } },
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
      vmBooleanFields,
      publicIpDetailFields,
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
    guestPvlan () {
      if (this.$route.meta.name !== 'guestnetwork' || !['L2', 'Shared'].includes(this.dataResource.type)) return null
      return l2PvlanDetails(this.dataResource.broadcasturi)
    },
    sharedScope () {
      return this.$route.meta.name === 'guestnetwork' && this.dataResource.type === 'Shared' && sharedScopeLabel(this.dataResource)
    },
    l2Network () {
      if (this.$route.meta.name !== 'guestnetwork' || this.dataResource.type !== 'L2') return null
      return { configDrive: l2ConfigDrive(this.dataResource) }
    },
    visibleBackupOfferingDetails () {
      return getAblestackBackupOfferingVisibleDetails(this.dataResource)
    },
    allocationDetails () {
      const name = this.$route.meta.name
      if (!['volume', 'diskoffering', 'computeoffering', 'systemoffering'].includes(name)) return {}
      return allocationDetailFields(this.resource, this.$t, value => this.$bytesToHumanReadableSize(value), {
        volume: name === 'volume',
        inheritance: ['computeoffering', 'systemoffering'].includes(name)
      })
    },
    customDisplayItems () {
      var items = ['ip4routes', 'ip6routes', 'privatemtu', 'publicmtu', 'provider', 'migrationip', 'details', 'parameters', 'secretkey', 'ipsecpsk', 'backupofferingdetails']
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
    offeringDetails () {
      return ['maxcpunumber', 'mincpunumber', 'minmemory', 'maxmemory']
    },
    ipV6Address () {
      if (this.dataResource.nic && this.dataResource.nic.length > 0) {
        return this.dataResource.nic.filter(e => e.enabled !== false && e.ip6address).map(e => e.ip6address).join(', ')
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
    this.dataResource = creationVmDetails(this.resource, this.$t)
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
        this.projectname = ''
        this.dataResource = creationVmDetails(newVal, this.$t)
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
    zoneDnsSearchOrder,
    clusterStateLabel,
    zoneFeatureState,
    hostStatusColor,
    hostStatusLabel,
    zoneFeatureLabel,
    eventTypeLabel,
    kmsRoute,
    hsmOwnerScope,
    kmsProtocolOptions,
    creationVmOsName,
    vmBooleanLabel,
    networkTypeLabel,
    networkIpUsageLabel,
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
    aclDisplayLabel,
    presentedTemplateValue,
    presentedIsoValue,
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

      detailsKeys = this.projectname && this.$route.meta.name !== 's2svpn' ? [...detailsKeys.filter(x => x !== 'account'), 'projectname'] : detailsKeys
      if (this.l2Network) {
        const l3Fields = ['vpcid', 'cidr', 'ip6cidr', 'netmask', 'gateway', 'asnumber', 'aclname', 'restartrequired',
          'reservediprange', 'redundantrouter', 'networkdomain', 'egressdefaultpolicy', 'ip4routing', 'ip6firewall',
          'ip6routing', 'ip6routes', 'dns1', 'dns2', 'ip6dns1', 'ip6dns2', 'publicmtu', 'dnszone', 'dnssubdomain',
          'keepmacaddressonpublicnic']
        detailsKeys = detailsKeys.filter(key => !l3Fields.includes(key))
      }
      if (this.sharedScope && !detailsKeys.includes('sharedscope')) detailsKeys.splice(Math.max(0, detailsKeys.indexOf('type') + 1), 0, 'sharedscope')
      return detailsKeys
    },
    getDetailTitle (detail) {
      if (this.$route.meta.name === 'webhook' && detail === 'secretkeyconfigured') return 'label.webhook.secret.configured'
      if (this.$route.meta.name === 's2svpn' && detail === 'fordisplay') return 'label.public.ip.display'
      if (this.$route.meta.name === 'publicip' && publicIpDetailFields.includes(detail)) return publicIpFieldLabel(detail)
      if (['vm', 'vnfapp'].includes(this.$route.meta.name) && ['osdisplayname', 'ostypename'].includes(detail)) return 'label.ostypename'
      if (['vm', 'vnfapp'].includes(this.$route.meta.name) && detail === 'name') return 'label.vm.name'
      if (['vm', 'vnfapp'].includes(this.$route.meta.name) && detail === 'displayname') return 'label.vm.displayname'
      if (this.$route.meta.name === 'template' && detail === 'format') return 'label.template.image.format'
      if (this.$route.meta.name === 'template' && detail === 'templatetype') return 'label.template.type'
      if (detail === 'sharedscope') return this.$t('label.shared.scope')
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
    activeNicAddresses (field) {
      if (!this.dataResource.nic || this.dataResource.nic.length === 0) {
        return this.dataResource[field]
      }

      return this.dataResource.nic.filter(e => e.enabled !== false && e[field]).map(e => e[field]).join(', ')
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
            page: 1, pagesize: 1, uid: routeKey
          })
          found = takeFirst(r1)
        }

        // 2) id로 조회 (콜론 포함 키 or 1단계 실패시)
        if (!found) {
          const r2 = await getAPI('listWallAlertRules', {
            page: 1, pagesize: 1, id: routeKey
          })
          found = takeFirst(r2)
        }

        // 3) 최종 폴백: 전체 받아서 프론트에서 uid/id/name 매칭
        if (!found) {
          const r3 = await getAPI('listWallAlertRules', {
            page: 1, pagesize: 2000
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

<style scoped src="@/style/components/view/resource-details-list.css" />

<style scoped>
.webhook-long-value { display: flex; gap: 8px; min-width: 0; overflow-wrap: anywhere; }
.network-ip-usage-detail p { color: var(--ui-text-secondary); margin: 4px 0 0; overflow-wrap: anywhere; }
:deep(.resource-details-list .ant-list-item) {
  padding: 12px 8px;
  color: var(--ui-text-secondary);
  background: transparent;
  border-color: var(--ui-border);
}

:deep(.resource-details-list .ant-list-item strong) {
  color: var(--ui-text-primary);
}

/* Same row rhythm as the VM InstanceTab details. */
.s2s-gateway-details :deep(.ant-list-item) { padding-top: 12px; padding-bottom: 12px; }
.preline {
  white-space: pre-line;
  word-break: break-word;
}
.l2-details-notice {
  margin-bottom: 16px;
  overflow-wrap: anywhere;
}
</style>

<style scoped>
.kms-metadata { min-width: 0; overflow-wrap: anywhere; }
</style>

<style scoped>
.extension-long-value { display: flex; align-items: flex-start; gap: 8px; }
.extension-long-value span { min-width: 0; overflow-wrap: anywhere; }
</style>
