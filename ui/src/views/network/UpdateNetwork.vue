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
  <MoldDialog class="network-update-dialog" size="lg" :title="$t('label.update.network')" :closable="!loading" :keyboard="!loading" @cancel="closeAction">
    <div class="network-update-summary"><strong>{{ resource.name }}</strong><span>{{ resource.type }} · {{ resource.zonename }} · {{ resource.account }}</span></div>
    <a-alert v-if="operationError" ref="operationErrorAlert" type="error" show-icon :message="operationError" role="alert" />
    <a-alert v-if="pendingJobId" type="info" show-icon :message="$t('message.network.offering.job.pending')">
      <template #description><span>{{ pendingJobId }}</span><a-button v-if="!polling" size="small" @click="recheckUpdate">{{ $t('label.retry') }}</a-button></template>
    </a-alert>
    <a-spin :spinning="loading">
      <a-form
        v-ctrl-enter="handleSubmit"
        ref="networkForm"
        :model="form"
        :rules="rules"
        @finish="handleSubmit"
        layout="vertical"
       >
        <div v-show="!offeringReview" class="network-update-fields">
        <a-form-item name="name" ref="name">
          <template #label>
            <tooltip-label :title="$t('label.name')" :tooltip="apiParams.name.description"/>
          </template>
          <a-input
            v-model:value="form.name"
            v-focus="true"
            :placeholder="apiParams.name.description"
   />
        </a-form-item>
        <a-form-item name="displaytext" ref="displaytext">
          <template #label>
            <tooltip-label :title="$t('label.displaytext')" :tooltip="apiParams.displaytext.description"/>
          </template>
          <a-textarea
            v-model:value="form.displaytext"
            :auto-size="{ minRows: 2, maxRows: 5 }"
            :placeholder="apiParams.displaytext.description"
   />
        </a-form-item>
        <div v-if="setMTU">
          <a-row :gutter="12" v-if="resource.type !== 'L2'">
            <a-col :md="12" :lg="12">
              <a-form-item
              v-if="!resource.vpcid"
                ref="publicmtu"
                name="publicmtu">
                <template #label>
                  <tooltip-label :title="$t('label.publicmtu')" :tooltip="apiParams.publicmtu.description"/>
                </template>
                <a-input-number
                  style="width: 100%;"
                  v-model:value="form.publicmtu"
                  :defaultValue="resource.publicmtu"
                  :placeholder="apiParams.publicmtu.description"
                  @change="updateMtu(true)"/>
                <div style="color: red" v-if="errorPublicMtu" v-html="errorPublicMtu"></div>
              </a-form-item>
            </a-col>
            <a-col :md="12" :lg="12">
              <a-form-item
                ref="privatemtu"
                name="privatemtu">
                <template #label>
                  <tooltip-label :title="$t('label.privatemtu')" :tooltip="apiParams.privatemtu.description"/>
                </template>
                <a-input-number
                  v-model:value="form.privatemtu"
                  style="width: 100%;"
                  :defaultValue="resource.privatemtu"
                  :placeholder="apiParams.privatemtu.description"
                  @change="updateMtu(false)"/>
                <div style="color: red" v-if="errorPrivateMtu"  v-html="errorPrivateMtu"></div>
              </a-form-item>
            </a-col>
          </a-row>
        </div>
        <a-form-item v-if="sourceNatSupported" name="sourcenatipaddress" ref="sourcenatipaddress">
          <template #label>
            <tooltip-label :title="$t('label.sourcenatipaddress')" :tooltip="apiParams.sourcenatipaddress.description"/>
          </template>
          <span v-if="sourcenatchange">
            <a-alert type="warning">
              <template #message>
                <span v-html="$t('message.sourcenatip.change.warning')" />
              </template>
            </a-alert>
            <br/>
          </span>
          <a-input
            v-model:value="form.sourcenatipaddress"
            :placeholder="apiParams.sourcenatipaddress.description"

            @change="sourcenatchange = form.sourcenatipaddress.length > 0"/>
        </a-form-item>
        <a-form-item name="networkofferingid" ref="networkofferingid" v-if="isUpdatingIsolatedNetwork">
          <template #label>
            <tooltip-label :title="$t('label.networkofferingid')" :tooltip="apiParams.networkofferingid.description"/>
          </template>
          <a-alert v-if="networkOfferingError" type="error" show-icon :message="networkOfferingError">
            <template #description><a-button size="small" :loading="networkOfferingLoading" @click="fetchNetworkOfferingData">{{ $t('label.retry') }}</a-button></template>
          </a-alert>
          <div class="network-offering-picker">
            <a-select
              class="network-offering-select"
              id="offering-selection"
              v-model:value="form.networkofferingid"
              showSearch
              optionFilterProp="label"
              :loading="networkOfferingLoading"
              :disabled="networkOfferingLoading || !!networkOfferingError || !!pendingJobId"
              :placeholder="apiParams.networkofferingid.description"
              @change="selectNetworkOffering">
              <a-select-option v-for="opt in offeringOptions" :key="opt.id" :value="opt.id" :disabled="opt.unavailable" :label="opt.displaytext || opt.name || opt.id">
                {{ opt.displaytext || opt.name || opt.id }}{{ opt.id === resource.networkofferingid ? ' (' + $t('label.network.offering.current') + ')' : '' }}{{ opt.unavailable ? ' · ' + $t('label.network.offering.unavailable') : opt.state === 'Disabled' ? ' · ' + $t('state.disabled') : '' }}
              </a-select-option>
            </a-select>
            <a-tooltip :title="$t('label.network.offering.reload')">
              <a-button
                class="network-offering-refresh"
                shape="circle"
                :aria-label="$t('label.network.offering.reload')"
                :loading="networkOfferingLoading"
                :disabled="loading || networkOfferingLoading || !!pendingJobId"
                @click="fetchNetworkOfferingData">
                <template #icon><reload-outlined /></template>
              </a-button>
            </a-tooltip>
          </div>
          <p v-if="!networkOfferingLoading && !networkOfferingError && !networkOfferings.length" role="status">{{ $t('message.network.offering.no.candidates') }}</p>
          <network-offering-diagnostics ref="offeringDiagnostics" :offering="networkOffering" :zone-id="resource.zoneid" :physical-network-id="resource.physicalnetworkid" @state="providerCheck = $event" />
        </a-form-item>
        <a-form-item v-if="resource.type === 'Isolated'" name="guestvmcidr" ref="guestvmcidr">
          <template #label>
            <tooltip-label :title="$t('label.guestvmcidr')" :tooltip="apiParams.guestvmcidr.description"/>
          </template>
          <a-input
            v-model:value="form.guestvmcidr"
            :placeholder="apiParams.guestvmcidr.description"/>
          <p class="network-dns-help">{{ $t('message.network.operation.policy.reservation') }}</p>
        </a-form-item>
        <a-form-item name="networkdomain" ref="networkdomain" v-if="hasNetworkDomain">
          <template #label>
            <tooltip-label :title="$t('label.networkdomain')" :tooltip="apiParams.guestvmcidr.description"/>
          </template>
          <a-input
            v-model:value="form.networkdomain"
            :placeholder="apiParams.networkdomain.description"
   />
        </a-form-item>
        <a-form-item name="updateinsequence" ref="updateinsequence" v-if="resource.redundantrouter">
          <template #label>
            <tooltip-label :title="$t('label.updateinsequence')" :tooltip="apiParams.updateinsequence.description"/>
          </template>
          <a-switch v-model:checked="form.updateinsequence" />
        </a-form-item>
        <div v-if="dnsSupport.ipv4 || dnsSupport.ipv6">
          <p class="network-dns-help">{{ $t('message.network.dns.pair.help') }}</p>
          <a-row v-if="dnsSupport.ipv4" :gutter="12">
            <a-col :md="12" :lg="12">
              <a-form-item v-if="'dns1' in apiParams" name="dns1" ref="dns1">
                <template #label>
                  <tooltip-label :title="$t('label.dns1')" :tooltip="apiParams.dns1.description"/>
                </template>
                <a-input
                  v-model:value="form.dns1"
                  :placeholder="apiParams.dns1.description"/>
              </a-form-item>
            </a-col>
            <a-col :md="12" :lg="12">
              <a-form-item v-if="'dns2' in apiParams" name="dns2" ref="dns2">
                <template #label>
                  <tooltip-label :title="$t('label.dns2')" :tooltip="apiParams.dns2.description"/>
                </template>
                <a-input
                  v-model:value="form.dns2"
                  :placeholder="apiParams.dns2.description"/>
              </a-form-item>
            </a-col>
          </a-row>
          <a-row v-if="dnsSupport.ipv6" :gutter="12">
            <a-col :md="12" :lg="12">
              <a-form-item v-if="dnsSupport.ipv6" name="ip6dns1" ref="ip6dns1">
                <template #label>
                  <tooltip-label :title="$t('label.ip6dns1')" :tooltip="apiParams.ip6dns1.description"/>
                </template>
                <a-input
                  v-model:value="form.ip6dns1"
                  :placeholder="apiParams.ip6dns1.description"/>
              </a-form-item>
            </a-col>
            <a-col :md="12" :lg="12">
              <a-form-item v-if="dnsSupport.ipv6" name="ip6dns2" ref="ip6dns2">
                <template #label>
                  <tooltip-label :title="$t('label.ip6dns2')" :tooltip="apiParams.ip6dns2.description"/>
                </template>
                <a-input
                  v-model:value="form.ip6dns2"
                  :placeholder="apiParams.ip6dns2.description"/>
              </a-form-item>
            </a-col>
          </a-row>
        </div>
        <a-form-item v-if="usageSupported" name="hideipaddressusage" class="network-ip-usage-setting">
          <template #label>{{ $t('label.hideipaddressusage') }}</template>
          <p class="network-dns-help">{{ $t('message.network.ip.usage.help') }}</p>
          <a-alert v-if="usageError" type="error" show-icon :message="usageError" />
          <div class="network-ip-usage-control">
            <a-switch
              v-if="usageOriginal !== null"
              v-model:checked="form.hideipaddressusage"
              :disabled="usageLoading || !!usageError || loading || !!pendingJobId"
              :aria-label="$t('label.hideipaddressusage')" />
            <span v-else role="status">{{ $t(usageLoading ? 'label.loading' : 'label.unknown') }}</span>
            <span v-if="usageOriginal !== null" class="network-ip-usage-value" role="status">{{ $t(form.hideipaddressusage ? 'label.network.ip.usage.excluded' : 'label.network.ip.usage.included') }}</span>
            <a-tooltip :title="$t('label.network.ip.usage.reload')">
              <a-button
                shape="circle"
                :aria-label="$t('label.network.ip.usage.reload')"
                :loading="usageLoading"
                :disabled="loading || !!pendingJobId"
                @click="fetchUsageSetting">
                <template #icon><reload-outlined /></template>
              </a-button>
            </a-tooltip>
          </div>
        </a-form-item>
        <a-form-item name="displaynetwork" ref="displaynetwork" v-if="isAdmin()">
          <template #label>
            <tooltip-label :title="$t('label.displaynetwork')" :tooltip="apiParams.displaynetwork.description"/>
          </template>
          <a-switch v-model:checked="form.displaynetwork" />
        </a-form-item>
        <a-form-item name="keepMacAddressOnPublicNic" ref="keepMacAddressOnPublicNic" v-if="isAdmin() && isUpdatingIsolatedNetwork && !resource?.vpcid">
          <template #label>
            <tooltip-label
              :title="$t('label.keep.mac.address.on.public.nic')"
              :tooltip="apiParams.keepmacaddressonpublicnic?.description"
            />
          </template>
          <a-switch v-model:checked="form.keepMacAddressOnPublicNic" />
        </a-form-item>
        <network-operation-policy context="update" :network-type="resource.type" />

        </div>
        <section v-if="offeringChanged && offeringSelectionAvailable" class="network-offering-review" :aria-label="$t('label.network.offering.impact')">
          <a-alert type="warning" show-icon :message="$t('label.network.offering.impact')" :description="$t('message.network.offering.impact')" />
          <div class="network-offering-comparison">
            <section v-for="side in offeringComparison" :key="side.key">
              <h4>{{ $t(side.key) }}</h4>
              <dl><template v-for="fact in side.facts" :key="fact.key"><dt>{{ $t(fact.label) }}</dt><dd>{{ fact.value }}</dd></template></dl>
            </section>
          </div>
          <p v-if="offeringReview" role="status">{{ $t('message.network.offering.confirm') }}</p>
        </section>
      </a-form>
    </a-spin>
    <template #footer>
      <a-button :disabled="loading" @click="closeAction">{{ $t(pendingJobId ? 'label.close' : 'label.cancel') }}</a-button>
      <a-button v-if="offeringReview" :disabled="loading || !!pendingJobId" @click="offeringReview = null">{{ $t('label.back') }}</a-button>
      <a-button :loading="loading" :disabled="submitBlocked" ref="submit" type="primary" @click="event => handleSubmit(event, !!offeringReview)">
        {{ $t(offeringChanged ? (offeringReview ? 'label.network.offering.apply' : 'label.network.offering.review') : 'label.ok') }}
      </a-button>
    </template>
  </MoldDialog>
</template>

<script>
import { reactive, toRaw } from 'vue'
import { getAPI, postAPI } from '@/api'
import NetworkOperationPolicy from '@/components/view/NetworkOperationPolicy'
import { standardNetworkUpdateParams } from '@/utils/networkOperationPolicy'
import { isAdmin } from '@/role'
import { mixinForm } from '@/utils/mixin'
import TooltipLabel from '@/components/widgets/TooltipLabel'
import MoldDialog from '@/components/view/MoldDialog'
import { networkOfferingScope, readNetworkOfferingCandidates, networkOfferingFacts, networkOfferingFingerprint } from '@/utils/networkOfferingChange'
import { networkDnsFields, networkDnsSupport, buildNetworkDnsParams, networkDnsNeedsZone, networkDnsMatches } from '@/utils/networkDnsUpdate'
import NetworkOfferingDiagnostics from '@/components/network/NetworkOfferingDiagnostics.vue'
import { networkIpUsageValue } from '@/utils/networkIpUsage'

export default {
  name: 'UpdateNetwork',
  mixins: [mixinForm],
  components: {
    TooltipLabel, MoldDialog, NetworkOfferingDiagnostics, NetworkOperationPolicy
  },
  props: {
    resource: {
      type: Object,
      required: true
    }
  },
  data () {
    return {
      resourceValues: {},
      usageOriginal: null,
      usageLoading: false,
      usageError: '',
      usageRequestId: 0,
      providerCheck: { blocked: false },
      networkOfferings: [],
      networkOfferingLoading: false,
      currentOffering: null,
      networkOfferingError: '',
      offeringRequestId: 0,
      resourceGeneration: 0,
      disposed: false,
      offeringReview: null,
      selectedOffering: null,
      resourceConflict: false,
      operationError: '',
      pendingJobId: '',
      pendingUpdate: null,
      polling: false,
      cidrChanged: false,
      loading: false,
      privateMtuMax: 1500,
      publicMtuMax: 1500,
      minMTU: 68,
      errorPrivateMtu: '',
      errorPublicMtu: '',
      setMTU: false,
      sourcenatchange: false
    }
  },
  beforeCreate () {
    this.apiParams = this.$getApiParams('updateNetwork')
  },
  created () {
    this.resetResource()
  },
  beforeUnmount () {
    this.disposed = true
    this.resourceGeneration++
    this.offeringRequestId++
  },
  watch: {
    operationError (value) {
      if (value) this.$nextTick(() => this.$refs.operationErrorAlert?.$el?.scrollIntoView?.({ block: 'nearest', inline: 'nearest' }))
    },
    resourceScope () {
      // The global job refresh may arrive before our final verification read.
      // Retain only the exact offering change accepted by this dialog.
      if (this.pendingJobId && this.isPendingUpdateScope(this.pendingUpdate?.scope)) return
      this.resetResource()
    },
    usageAuthScope () {
      this.resetResource()
    }
  },
  computed: {
    sourceNatSupported () {
      return this.resource.type === 'Isolated' && !this.resource.vpcid && !this.resource.vpcname && !this.resource.ip4routing &&
        !!this.apiParams.sourcenatipaddress && (this.resource.service || []).some(service => service.name === 'SourceNat')
    },
    usageSupported () {
      return this.isUpdatingSharedNetwork && this.isAdmin() && 'hideipaddressusage' in this.apiParams && 'updateNetwork' in (this.$store?.getters?.apis || {})
    },
    usageAuthScope () {
      const getters = this.$store?.getters || {}
      return JSON.stringify([getters.userInfo?.id, getters.userInfo?.roletype, getters.sessionkey, this.usageSupported, 'listNetworks' in (getters.apis || {})])
    },
    usageChanged () {
      return this.usageSupported && this.usageOriginal !== null && typeof this.form?.hideipaddressusage === 'boolean' && this.form.hideipaddressusage !== this.usageOriginal
    },
    resourceScope () {
      return networkOfferingScope(this.resource, this.$store?.getters?.project?.id)
    },
    currentNetworkOffering () {
      return this.currentOffering || {
        id: this.resource.networkofferingid,
        name: this.resource.networkofferingname,
        guestiptype: this.resource.type,
        service: this.resource.service || []
      }
    },
    offeringOptions () {
      const options = [this.currentNetworkOffering, ...this.networkOfferings.filter(item => item.id !== this.resource.networkofferingid)]
      const selectedId = this.form.networkofferingid
      if (this.offeringChanged && !options.some(item => item.id === selectedId)) {
        options.push({ ...this.selectedOffering, id: selectedId, name: this.selectedOffering?.name || selectedId, unavailable: true })
      }
      return options
    },
    networkOffering () {
      return this.offeringOptions.find(item => item.id === this.form.networkofferingid) || this.currentNetworkOffering
    },
    offeringChanged () {
      return this.isUpdatingIsolatedNetwork && !!this.form.networkofferingid && this.form.networkofferingid !== this.resource.networkofferingid
    },
    offeringSelectionAvailable () {
      return this.networkOfferings.some(item => item.id === this.form.networkofferingid)
    },
    submitBlocked () {
      return this.loading || this.resourceConflict || !!this.pendingJobId || (this.usageChanged && (this.usageLoading || !!this.usageError)) || (this.offeringChanged && (this.providerCheck.blocked || this.networkOfferingLoading || !!this.networkOfferingError || !this.currentOffering || !this.offeringSelectionAvailable))
    },
    offeringComparison () {
      const labels = {
        name: 'label.name',
        id: 'label.id',
        state: 'label.state',
        guestiptype: 'label.type',
        forvpc: 'label.forvpc',
        internetprotocol: 'label.internetprotocol',
        ip4routing: 'label.ip4routing',
        services: 'label.network.offering.services',
        providers: 'label.network.offering.providers',
        sourcenat: 'label.sourcenat'
      }
      return [['label.network.offering.current', this.currentNetworkOffering], ['label.network.offering.target', this.networkOffering]].map(([key, item]) => ({
        key, facts: Object.entries(networkOfferingFacts(item)).map(([key, value]) => ({ key, label: labels[key], value: ['true', 'false'].includes(value) ? this.$t(value === 'true' ? 'label.yes' : 'label.no') : value }))
      }))
    },
    isUpdatingIsolatedNetwork () {
      return this.resource && this.resource.type === 'Isolated'
    },
    isUpdatingSharedNetwork () {
      return this.resource && this.resource.type === 'Shared'
    },
    hasNetworkDomain () {
      return this.isUpdatingIsolatedNetwork || this.isUpdatingSharedNetwork
    },
    dnsSupport () {
      return networkDnsSupport(this.resource, this.networkOffering, this.apiParams)
    }
  },
  methods: {
    resetResource () {
      this.resourceGeneration++
      this.usageRequestId++
      this.usageOriginal = null
      this.usageError = ''
      this.usageLoading = false
      this.offeringRequestId++
      this.networkOfferings = []
      this.currentOffering = null
      this.networkOfferingError = ''
      this.operationError = ''
      this.offeringReview = null
      this.selectedOffering = null
      this.resourceConflict = false
      this.pendingJobId = ''
      this.pendingUpdate = null
      this.polling = false
      this.loading = false
      this.initForm()
      this.resourceValues = {
        name: this.resource.name,
        displaytext: this.resource.displaytext ?? '',
        guestvmcidr: this.resource.cidr,
        dns1: this.resource.dns1,
        dns2: this.resource.dns2,
        ip6dns1: this.resource.ip6dns1,
        ip6dns2: this.resource.ip6dns2
      }
      if (this.hasNetworkDomain) this.resourceValues.networkdomain = this.resource.networkdomain
      for (const [field, value] of Object.entries(this.resourceValues)) {
        if (value !== undefined && value !== null) this.form[field] = value
      }
      this.fetchData()
    },
    captureScope () {
      return this.resourceScope + this.resourceGeneration
    },
    isCurrentScope (scope) {
      return !this.disposed && scope === this.captureScope()
    },
    isPendingUpdateScope (scope) {
      return !this.disposed && !!this.pendingUpdate && this.pendingUpdate.scope === scope &&
        this.pendingUpdate.generation === this.resourceGeneration &&
        this.pendingUpdate.expectedResourceScope === this.resourceScope
    },
    isUpdateScope (scope) {
      return this.isCurrentScope(scope) || this.isPendingUpdateScope(scope)
    },
    errorText (error) {
      const data = error?.response?.data
      return Object.values(data || {}).find(value => value?.errortext)?.errortext || error?.message || this.$t('message.request.failed')
    },
    initForm () {
      this.form = reactive({
        networkofferingid: this.resource.networkofferingid,
        displaynetwork: this.resource.displaynetwork,
        privatemtu: this.resource.privatemtu,
        publicmtu: this.resource.publicmtu,
        keepMacAddressOnPublicNic: this.resource.keepmacaddressonpublicnic
      })
      this.rules = reactive({
        name: [{ required: true, message: this.$t('message.error.required.input') }]
      })
    },
    fetchData () {
      this.fetchNetworkOfferingData()
      this.fetchMtuForZone()
      this.fetchUsageSetting()
    },
    async readUsageNetwork () {
      const json = await getAPI('listNetworks', { id: this.resource.id, listall: true, ...(this.resource.projectid ? { projectid: this.resource.projectid } : {}) }, { preserveOnFailure: true })
      const network = json?.listnetworksresponse?.network?.find(item => item.id === this.resource.id)
      if (!network || network.type !== 'Shared' || networkIpUsageValue(network) === null) throw new Error(this.$t('message.network.ip.usage.lookup.failed'))
      return network
    },
    async fetchUsageSetting () {
      if (!this.usageSupported) return
      const scope = this.captureScope()
      const requestId = ++this.usageRequestId
      this.usageLoading = true
      this.usageError = ''
      try {
        if (!('listNetworks' in (this.$store?.getters?.apis || {}))) throw new Error(this.$t('message.network.ip.usage.lookup.failed'))
        const network = await this.readUsageNetwork()
        if (!this.isCurrentScope(scope) || requestId !== this.usageRequestId || !this.usageSupported) return
        this.usageOriginal = networkIpUsageValue(network)
        this.form.hideipaddressusage = this.usageOriginal
      } catch (error) {
        if (this.isCurrentScope(scope) && requestId === this.usageRequestId) this.usageError = this.$t('message.network.ip.usage.lookup.failed')
      } finally {
        if (this.isCurrentScope(scope) && requestId === this.usageRequestId) this.usageLoading = false
      }
    },
    isAdmin () {
      return isAdmin()
    },
    arrayHasItems (array) {
      return array !== null && array !== undefined && Array.isArray(array) && array.length > 0
    },
    fetchMtuForZone () {
      const scope = this.captureScope()
      getAPI('listZones', {
        id: this.resource.zoneid
      }, { preserveOnFailure: true }).then(json => {
        if (!this.isCurrentScope(scope)) return
        this.setMTU = json?.listzonesresponse?.zone?.[0]?.allowuserspecifyvrmtu || false
        this.privateMtuMax = json?.listzonesresponse?.zone?.[0]?.routerprivateinterfacemaxmtu || 1500
        this.publicMtuMax = json?.listzonesresponse?.zone?.[0]?.routerpublicinterfacemaxmtu || 1500
      }).catch(error => { if (this.isCurrentScope(scope)) this.$notifyError(error) })
    },
    async fetchNetworkOfferingData () {
      const scope = this.captureScope()
      const requestId = ++this.offeringRequestId
      const resource = { ...this.resource }
      this.networkOfferingLoading = true
      this.networkOfferingError = ''
      const results = await Promise.allSettled([
        getAPI('listNetworkOfferings', { id: resource.networkofferingid }, { preserveOnFailure: true }),
        this.isUpdatingIsolatedNetwork ? readNetworkOfferingCandidates(getAPI, resource) : Promise.resolve([])
      ])
      if (!this.isCurrentScope(scope) || requestId !== this.offeringRequestId) return
      const [current, candidates] = results
      const offering = current.status === 'fulfilled' && current.value?.listnetworkofferingsresponse?.networkoffering?.find(item => item.id === resource.networkofferingid)
      if (offering) this.currentOffering = offering
      if (candidates.status === 'fulfilled') this.networkOfferings = candidates.value
      if (!offering || candidates.status === 'rejected') {
        this.networkOfferingError = this.$t('message.network.offering.lookup.failed')
      }
      this.networkOfferingLoading = false
    },
    async checkOfferingChange (selectedId, scope) {
      const resource = { ...this.resource }
      const [networkJson, candidates, currentJson] = await Promise.all([
        getAPI('listNetworks', { id: resource.id, listall: true }, { preserveOnFailure: true }),
        readNetworkOfferingCandidates(getAPI, resource),
        getAPI('listNetworkOfferings', { id: resource.networkofferingid }, { preserveOnFailure: true })
      ])
      if (!this.isCurrentScope(scope)) return null
      const network = networkJson?.listnetworksresponse?.network?.find(item => item.id === resource.id)
      const current = currentJson?.listnetworkofferingsresponse?.networkoffering?.find(item => item.id === resource.networkofferingid)
      const target = candidates.find(item => item.id === selectedId)
      this.networkOfferings = candidates
      if (current) this.currentOffering = current
      if (!network || network.networkofferingid !== resource.networkofferingid) {
        this.resourceConflict = true
        this.offeringReview = null
        throw new Error(this.$t('message.network.offering.network.changed'))
      }
      if (!current || !target) {
        this.offeringReview = null
        throw new Error(this.$t('message.network.offering.selection.expired'))
      }
      await this.$nextTick()
      const providerReady = await this.$refs.offeringDiagnostics.validate()
      if (!this.isCurrentScope(scope)) return null
      if (!providerReady) throw new Error(this.$t('message.network.diagnostics.invalid'))
      return networkOfferingFingerprint(current) + networkOfferingFingerprint(target)
    },
    selectNetworkOffering (id) {
      this.offeringReview = null
      this.selectedOffering = this.offeringOptions.find(item => item.id === id) || null
    },
    updateMtu (isPublic) {
      if (isPublic) {
        if (this.form.publicmtu > this.publicMtuMax) {
          this.errorPublicMtu = `${this.$t('message.error.mtu.public.max.exceed').replace('%x', this.publicMtuMax)}`
          this.form.publicmtu = this.publicMtuMax
        } else if (this.form.publicmtu < this.minMTU) {
          this.errorPublicMtu = `${this.$t('message.error.mtu.below.min').replace('%x', this.minMTU)}`
          this.form.publicmtu = this.minMTU
        } else {
          this.errorPublicMtu = ''
        }
      } else {
        if (this.form.privatemtu > this.privateMtuMax) {
          this.errorPrivateMtu = `${this.$t('message.error.mtu.private.max.exceed').replace('%x', this.privateMtuMax)}`
          this.form.privatemtu = this.privateMtuMax
        } else if (this.form.privatemtu < this.minMTU) {
          this.errorPrivateMtu = `${this.$t('message.error.mtu.below.min').replace('%x', this.minMTU)}`
          this.form.privatemtu = this.minMTU
        } else {
          this.errorPrivateMtu = ''
        }
      }
    },
    async handleSubmit (e, confirmed = false) {
      if (e?.preventDefault) e.preventDefault()
      if (this.submitBlocked) return
      const scope = this.captureScope()
      try {
        await this.$refs.networkForm.validate()
      } catch (error) {
        if (error?.errorFields?.length) this.$refs.networkForm.scrollToField(error.errorFields[0].name)
        return
      }
      if (!this.isCurrentScope(scope) || this.loading || this.pendingJobId) return
      const rawValues = { ...toRaw(this.form) }
      const values = this.handleRemoveFields(rawValues)
      const dnsParams = buildNetworkDnsParams(rawValues, this.resourceValues, this.dnsSupport)
      const selectedId = values.networkofferingid
      const changingOffering = this.offeringChanged
      const changingUsage = this.usageChanged
      this.loading = true
      this.operationError = ''
      try {
        if (changingUsage) {
          const network = await this.readUsageNetwork()
          if (!this.isCurrentScope(scope) || !this.usageSupported) return
          if (networkIpUsageValue(network) !== this.usageOriginal) throw new Error(this.$t('message.network.ip.usage.conflict'))
        }
        if (changingOffering) {
          const fingerprint = await this.checkOfferingChange(selectedId, scope)
          if (!fingerprint || !this.isCurrentScope(scope)) return
          if (!confirmed || this.offeringReview !== fingerprint) {
            this.offeringReview = fingerprint
            if (confirmed) this.operationError = this.$t('message.network.offering.review.again')
            return
          }
        }
        const manualFields = ['name', 'networkofferingid', 'hideipaddressusage', ...networkDnsFields]
        let params = {
          id: this.resource.id,
          name: values.name
        }
        for (const field in values) {
          if (manualFields.includes(field) || (field === 'sourcenatipaddress' && !this.sourceNatSupported)) continue
          const fieldValue = values[field]
          if (fieldValue !== undefined && fieldValue !== null && (!(field in this.resourceValues) || this.resourceValues[field] !== fieldValue)) {
            if (field === 'keepMacAddressOnPublicNic') {
              params.keepmacaddressonpublicnic = fieldValue
            } else {
              params[field] = fieldValue
            }
          }
        }
        Object.assign(params, dnsParams)
        if (changingUsage) params.hideipaddressusage = rawValues.hideipaddressusage
        if (changingOffering) params.networkofferingid = selectedId
        params = standardNetworkUpdateParams(params, this.resource.type)
        const json = await postAPI('updateNetwork', params, { preserveOnFailure: true })
        if (!this.isCurrentScope(scope)) return
        this.pendingUpdate = {
          scope,
          params,
          generation: this.resourceGeneration,
          expectedResourceScope: networkOfferingScope({ ...this.resource, networkofferingid: params.networkofferingid || this.resource.networkofferingid }, this.$store?.getters?.project?.id)
        }
        this.pendingJobId = json?.updatenetworkresponse?.jobid || 'unknown'
        this.loading = false
        await this.recheckUpdate()
      } catch (error) {
        if (this.isCurrentScope(scope)) {
          this.operationError = this.errorText(error)
          this.offeringReview = null
          if (changingOffering) this.fetchNetworkOfferingData()
        }
      } finally {
        if (this.isCurrentScope(scope)) this.loading = false
      }
    },
    async recheckUpdate () {
      if (this.polling || !this.pendingUpdate) return
      const { scope, params } = this.pendingUpdate
      if (this.pendingJobId === 'unknown') {
        this.operationError = this.$t('message.network.offering.receipt.unknown')
        return
      }
      this.polling = true
      this.operationError = ''
      try {
        const result = await this.$pollJob({ jobId: this.pendingJobId, retry: true, title: this.$t('label.update.network'), description: this.resource.name })
        if (!this.isUpdateScope(scope)) return
        if (result?.jobstatus === 1) {
          const needsZone = networkDnsNeedsZone(params)
          const [json, zoneJson] = await Promise.all([
            getAPI('listNetworks', { id: params.id, listall: true }, { preserveOnFailure: true }),
            needsZone ? getAPI('listZones', { id: this.resource.zoneid }, { preserveOnFailure: true }) : Promise.resolve(null)
          ])
          if (!this.isUpdateScope(scope)) return
          const network = json?.listnetworksresponse?.network?.find(item => item.id === params.id)
          if (!network || (params.networkofferingid && network.networkofferingid !== params.networkofferingid)) {
            this.operationError = this.$t('message.network.offering.verify.failed')
            return
          }
          if (['name', 'displaytext'].some(field => field in params && String(network[field] ?? '') !== String(params[field] ?? ''))) {
            this.operationError = this.$t('message.network.description.verify.failed')
            return
          }
          const zone = zoneJson?.listzonesresponse?.zone?.find(item => item.id === this.resource.zoneid)
          if ('hideipaddressusage' in params && networkIpUsageValue(network) !== params.hideipaddressusage) {
            this.operationError = this.$t('message.network.ip.usage.verify.failed')
            return
          }
          if ((needsZone && !zone) || !networkDnsMatches(network, params, zone)) {
            this.operationError = this.$t('message.network.dns.verify.failed')
            return
          }
          this.pendingJobId = ''
          this.$emit('refresh-data')
          this.closeAction()
        } else if (result?.jobstatus === 2) {
          this.pendingJobId = ''
          this.offeringReview = null
          this.operationError = result.jobresult?.errortext || this.$t('message.request.failed')
          this.fetchNetworkOfferingData()
        } else this.operationError = this.$t('message.network.offering.job.pending')
      } catch (error) {
        if (this.isUpdateScope(scope)) this.operationError = this.errorText(error)
      } finally {
        if (this.isUpdateScope(scope)) this.polling = false
      }
    },
    closeAction () {
      if (this.loading) return
      this.$emit('close-action')
    }
  }
}
</script>

<style scoped lang="less">
.network-update-summary { display: flex; flex-direction: column; gap: 4px; margin-bottom: 16px; overflow-wrap: anywhere; }
.network-update-summary span { color: var(--ui-text-secondary); }
.network-update-fields { width: 100%; min-width: 0; }
.network-dns-help { color: var(--ui-text-secondary); margin: 0 0 12px; overflow-wrap: anywhere; }
.network-ip-usage-control { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.network-ip-usage-value { color: var(--ui-text); font-size: 14px; }
.network-offering-picker { display: flex; align-items: center; gap: 8px; width: 100%; }
.network-offering-select { flex: 1 1 0; min-width: 0; }
.network-offering-refresh { flex: 0 0 auto; }
.network-offering-review { margin-top: 16px; }
.network-offering-comparison { display: grid; grid-template-columns: minmax(0, 1fr) minmax(0, 1fr); gap: 16px; margin-top: 16px; }
.network-offering-comparison section { min-width: 0; padding: 12px; border: 1px solid var(--ui-border); background: var(--ui-bg-surface); border-radius: 4px; }
.network-offering-comparison dl { margin: 0; }
.network-offering-comparison dt { font-weight: 600; margin-top: 8px; }
.network-offering-comparison dd { margin: 0; overflow-wrap: anywhere; white-space: pre-wrap; }
.network-update-dialog :deep(.ant-alert) { margin-bottom: 12px; }
@media (max-width: 767px) {
  .network-offering-comparison { grid-template-columns: minmax(0, 1fr); }
  .network-update-fields :deep(.ant-col) { width: 100%; }
}
</style>
