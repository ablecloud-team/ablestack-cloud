<!--
Licensed to the Apache Software Foundation (ASF) under one
or more contributor license agreements. See the NOTICE file
distributed with this work for additional information
regarding copyright ownership. The ASF licenses this file
to you under the Apache License, Version 2.0 (the
"License"); you may not use this file except in compliance
with the License. You may obtain a copy of the License at
http://www.apache.org/licenses/LICENSE-2.0
Unless required by applicable law or agreed to in writing,
software distributed under the License is distributed on an
"AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
KIND, either express or implied. See the License for the
specific language governing permissions and limitations
under the License.
-->
<template>
  <div class="vm-nics-tab">
    <div class="nic-toolbar">
      <a-tooltip :title="reason('createNetwork')"><span v-if="allowed('createNetwork') && allowed('addNicToVirtualMachine')"><a-button type="primary" :disabled="busy || !!reason('createNetwork')" @click="openCreate"><template #icon><plus-outlined /></template>{{ $t('label.vmnic.create') }}</a-button></span></a-tooltip>
      <a-tooltip :title="reason('addNicToVirtualMachine')"><span v-if="allowed('addNicToVirtualMachine')"><a-button :disabled="busy || !!reason('addNicToVirtualMachine')" @click="openAttach">{{ $t('label.vmnic.attach') }}</a-button></span></a-tooltip>
      <a-button @click="fetchData"><template #icon><reload-outlined /></template>{{ $t('label.vmsnapshot.refresh') }}</a-button>
      <a-input-search v-model:value="search" :placeholder="$t('label.search')" :aria-label="$t('label.search')" />
    </div>
    <a-alert v-if="listRefreshFailed" class="nic-alert" type="warning" show-icon :message="$t('message.list.refresh.stale')" />
    <a-alert v-if="operation" class="nic-alert" :type="operation.status === 'complete' ? 'success' : operation.status === 'failed' ? 'error' : 'info'" show-icon>
      <template #message><a @click="progressVisible = true">{{ $t('label.vmnic.progress') }}: {{ $t('label.vmvolume.' + operation.status) }}</a></template>
    </a-alert>
    <a-table :columns="columns" :data-source="filteredRows" row-key="id" size="small" :loading="loading" :pagination="{ pageSize: 10, hideOnSinglePage: true }" :scroll="{ x: 760 }">
      <template #bodyCell="{ column, record }">
        <template v-if="column.key === 'network'"><router-link :to="'/guestnetwork/' + record.networkid">{{ record.networkname || record.networkid }}</router-link><br><a-tag>{{ record.type }}</a-tag><a-tag v-if="record.isdefault" color="blue">{{ $t('label.default') }}</a-tag></template>
        <template v-else-if="column.key === 'address'">{{ record.ipaddress || (record.type === 'L2' ? $t('message.vmnic.l2.short') : '—') }}<br><small>{{ record.macaddress }}</small></template>
        <template v-else-if="column.key === 'state'"><a-badge :status="nicState(record) === true ? 'success' : 'default'" :text="booleanText(nicState(record))" /></template>
        <template v-else-if="column.key === 'actions'"><div class="nic-row-actions">
          <a-tooltip :title="reason('removeNicFromVirtualMachine', record)"><span v-if="allowed('removeNicFromVirtualMachine')"><a-button size="small" :disabled="busy || !!reason('removeNicFromVirtualMachine', record)" @click="openAction('removeNicFromVirtualMachine', record)">{{ $t('label.vmnic.detach') }}</a-button></span></a-tooltip>
          <a-dropdown :trigger="['click']"><a-button size="small" :aria-label="$t('label.actions')"><template #icon><down-outlined /></template></a-button><template #overlay><a-menu>
            <a-menu-item v-for="api in actions.filter(allowed)" :key="api" :disabled="busy || !!reason(api, record)" @click="openAction(api, record)"><a-tooltip :title="reason(api, record)">{{ actionTitle(api, record) }}</a-tooltip></a-menu-item>
            <a-menu-item v-if="allowed('addIpToNic') || allowed('removeIpFromNic')" key="secondary" :disabled="record.type === 'L2'" @click="openAction('secondary', record)">{{ $t('label.edit.secondary.ips') }}</a-menu-item>
            <a-menu-divider />
            <a-menu-item key="details" @click="openAction('details', record)">{{ $t('label.details') }}</a-menu-item>
          </a-menu></template></a-dropdown>
        </div></template>
      </template>
    </a-table>
    <a-modal centered wrap-class-name="vm-nic-modal" :visible="form === 'create'" :title="$t('label.vmnic.create')" :width="760" :mask-closable="false" @cancel="closeForm">
      <dl class="nic-context"><dt>{{ $t('label.virtualmachine') }}</dt><dd>{{ vm.displayname || vm.name }}</dd><dt>{{ $t('label.zone') }}</dt><dd>{{ vm.zonename }}</dd><dt>{{ $t(vm.projectid ? 'label.project' : 'label.account') }}</dt><dd>{{ vm.project || vm.account }}</dd><template v-if="!vm.projectid"><dt>{{ $t('label.domain') }}</dt><dd>{{ vm.domain }}</dd></template></dl>
      <a-checkbox v-if="allowed('updateDefaultNicForVirtualMachine')" v-model:checked="makeDefault">{{ $t('label.make.default') }}</a-checkbox>
      <a-alert class="nic-alert" type="info" show-icon :message="$t('message.vmnic.partial')" />
      <CreateNetwork ref="networkCreator" v-if="form === 'create'" :resource="formVm" :submit-handler="createAndAttach" @close-action="closeForm" />
      <template #footer><a-button @click="closeForm">{{ $t('label.cancel') }}</a-button><a-button type="primary" :disabled="busy" @click="$refs.networkCreator?.submit()">{{ $t('label.vmnic.create') }}</a-button></template>
    </a-modal>
    <a-modal
centered
wrap-class-name="vm-nic-modal"
:visible="form === 'attach'"
:title="$t('label.vmnic.attach')"
:ok-text="$t('label.vmnic.attach')"
:ok-button-props="{ disabled: busy || candidatesLoading || !attachId }"
:mask-closable="false"
@ok="attach"
@cancel="closeForm">
      <dl class="nic-context"><dt>{{ $t('label.virtualmachine') }}</dt><dd>{{ vm.displayname || vm.name }}</dd><dt>{{ $t('label.zone') }}</dt><dd>{{ vm.zonename }}</dd><dt>{{ $t(vm.projectid ? 'label.project' : 'label.account') }}</dt><dd>{{ vm.project || vm.account }}</dd><template v-if="!vm.projectid"><dt>{{ $t('label.domain') }}</dt><dd>{{ vm.domain }}</dd></template></dl>
      <a-form layout="vertical" class="nic-fields">
        <a-form-item :label="$t('label.network')"><a-select v-model:value="attachId" show-search :filter-option="filterOption" :loading="candidatesLoading" @change="changeCandidate"><a-select-option v-for="network in candidates" :key="network.id" :value="network.id" :label="network.name">{{ network.name }} · {{ network.type || network.guestiptype }}</a-select-option></a-select></a-form-item>
        <a-alert v-if="!candidatesLoading && !candidates.length" type="info" :message="$t('message.vmnic.empty')" />
        <a-form-item :label="$t('label.ipaddress')" :extra="candidate?.type === 'L2' ? $t('message.vmnic.l2') : $t('message.vmnic.autoip')"><a-input :aria-label="$t('label.ipaddress')" v-model:value="values.ipaddress" :disabled="candidate?.type === 'L2'" /></a-form-item>
        <a-form-item :label="$t('label.macaddress')" :extra="$t('message.vmnic.automac')"><a-input :aria-label="$t('label.macaddress')" v-model:value="values.macaddress" /></a-form-item>
        <a-checkbox v-if="allowed('updateDefaultNicForVirtualMachine')" v-model:checked="makeDefault">{{ $t('label.make.default') }}</a-checkbox>
      </a-form>
      <a-alert v-if="formError" class="nic-alert" type="error" :message="formError" />
    </a-modal>
    <a-modal
centered
wrap-class-name="vm-nic-modal"
:visible="form === 'action'"
:title="actionTitle(action, selected)"
:ok-text="actionTitle(action, selected)"
:ok-button-props="{ danger: action === 'removeNicFromVirtualMachine' || action === 'removeIpFromNic', disabled: busy || !ack || !!reason(action, selected) }"
:mask-closable="false"
@ok="submitAction"
@cancel="closeForm">
      <a-descriptions v-if="selected" bordered :column="1" size="small" class="nic-description"><a-descriptions-item :label="$t('label.vm')">{{ vm.displayname || vm.name }}</a-descriptions-item><a-descriptions-item :label="$t('label.network')">{{ selected.networkname || selected.networkid }}</a-descriptions-item><a-descriptions-item :label="$t('label.id')">{{ selected.id }}</a-descriptions-item><a-descriptions-item :label="$t('label.ipaddress')">{{ action === 'removeIpFromNic' ? values.secondaryAddress : selected.ipaddress || '—' }}</a-descriptions-item><a-descriptions-item :label="$t('label.macaddress')">{{ selected.macaddress }}</a-descriptions-item></a-descriptions>
      <template v-if="action === 'updateVmNicIp'"><a-form layout="vertical" class="nic-fields"><a-form-item :label="$t('label.ipaddress')" :extra="selected?.type === 'L2' ? $t('message.vmnic.l2') : $t('message.vmnic.preserveip')"><a-input :aria-label="$t('label.ipaddress')" v-model:value="values.ipaddress" :disabled="selected?.type === 'L2'" /></a-form-item><a-form-item :label="$t('label.macaddress')" :extra="vm.state !== 'Stopped' ? $t('message.vmnic.mac.stop') : undefined"><a-input :aria-label="$t('label.macaddress')" v-model:value="values.macaddress" :disabled="vm.state !== 'Stopped'" /></a-form-item></a-form></template>
      <a-alert class="nic-alert" type="warning" show-icon :message="$t(action === 'removeNicFromVirtualMachine' ? 'message.vmnic.detach' : 'message.vmnic.impact')" />
      <a-alert v-if="reason(action, selected)" class="nic-alert" type="warning" :message="reason(action, selected)" />
      <a-alert v-if="formError" class="nic-alert" type="error" :message="formError" />
      <a-checkbox v-model:checked="ack">{{ $t('message.vmnic.ack') }}</a-checkbox>
    </a-modal>
    <a-modal centered wrap-class-name="vm-nic-modal" :visible="form === 'secondary'" :title="$t('label.edit.secondary.ips')" @cancel="closeForm">
      <p>{{ selected?.networkname }} / {{ selected?.macaddress }}</p>
      <a-form v-if="allowed('addIpToNic')" layout="vertical" class="nic-fields"><a-form-item :label="$t('label.ipaddress')" :extra="$t('message.vmnic.autoip')"><a-input :aria-label="$t('label.ipaddress')" v-model:value="values.ipaddress" /></a-form-item><a-form-item :label="$t('label.description')"><a-input v-model:value="values.description" /></a-form-item></a-form>
      <a-alert v-if="formError" class="nic-alert" type="error" :message="formError" />
      <a-list :data-source="selected?.secondaryip || []"><template #renderItem="{ item }"><a-list-item>{{ item.ipaddress }} {{ item.description }}<a-button v-if="allowed('removeIpFromNic')" danger size="small" :disabled="busy || !!reason('removeIpFromNic', selected)" @click="removeSecondary(item)">{{ $t('label.action.release.ip') }}</a-button></a-list-item></template></a-list>
      <a-alert class="nic-alert" type="info" :message="$t('message.network.secondaryip')" />
      <template #footer><a-button @click="closeForm">{{ $t('label.cancel') }}</a-button><a-button v-if="allowed('addIpToNic')" type="primary" :disabled="busy || !!reason('addIpToNic', selected)" @click="addSecondary">{{ $t('label.add.secondary.ip') }}</a-button></template>
    </a-modal>
    <a-modal centered wrap-class-name="vm-nic-modal" :visible="form === 'details'" :title="$t('label.details')" @cancel="closeForm"><a-descriptions v-if="selected" bordered :column="1" size="small" class="nic-description"><a-descriptions-item v-for="key in detailKeys" :key="key" :label="$t('label.' + key)">{{ selected[key] ?? '—' }}</a-descriptions-item></a-descriptions><p>{{ $t('label.vmnic.enabled') }}: {{ booleanText(selected?.enabled) }} / {{ $t('label.vmnic.link') }}: {{ selected?.linkstate === true ? 'UP' : selected?.linkstate === false ? 'DOWN' : $t('label.vmnic.unknown') }}</p><template #footer><a-button @click="closeForm">{{ $t('label.close') }}</a-button></template></a-modal>
    <a-modal centered wrap-class-name="vm-nic-modal" :visible="progressVisible && !!operation" :title="$t('label.vmnic.progress')" @cancel="progressVisible = false">
      <template v-if="operation"><p>{{ operation.vm.displayname || operation.vm.name }}</p><p v-if="operation.network?.id"><router-link :to="'/guestnetwork/' + operation.network.id">{{ operation.network.name || operation.network.id }}</router-link></p>
        <a-steps direction="vertical" size="small" :current="operation.stage" :status="operation.status === 'failed' ? 'error' : 'process'"><a-step v-for="step in operation.steps" :key="step" :title="actionTitle(step, operation.nic)" /></a-steps>
        <a-alert class="nic-alert" :type="operation.status === 'complete' ? 'success' : operation.status === 'failed' ? 'error' : 'info'" :message="$t('label.vmvolume.' + operation.status)" :description="operation.error ? translateError(operation.error) : ''" />
        <a-alert v-if="operation.stage > 0 && operation.status !== 'complete'" class="nic-alert" type="warning" :message="$t('message.vmnic.partial')" />
        <p v-if="operation.status === 'unknown'">{{ $t('message.vmnic.unknown') }}</p><p v-if="operation.jobId">Job ID: {{ operation.jobId }}</p>
      </template>
      <template v-if="operation" #footer><a-button v-if="['unknown', 'failed'].includes(operation.status)" :loading="operation.checking" @click="operation.resume()">{{ $t(operation.status === 'unknown' ? 'label.vmvolume.check' : 'label.vmvolume.retry') }}</a-button><a-button v-if="operation.status === 'failed'" @click="operation.abandon(); progressVisible = false">{{ $t('label.vmnic.abandon') }}</a-button><a-button @click="progressVisible = false">{{ $t('label.close') }}</a-button></template>
    </a-modal>
  </div>
</template>

<script>
import { getAPI, postAPI } from '@/api'
import CreateNetwork from '@/views/network/CreateNetwork.vue'
import { listRefreshMixin } from '@/utils/listRefreshMixin'
import { nicOperations, startNicOperation, nicActionReason, nicOwner, nicAddressParams, nicStateAction } from '@/utils/vmNicActions'

export default {
  name: 'VmNicsTab',
  components: { CreateNetwork },
  props: { resource: { type: Object, required: true } },
  mixins: [listRefreshMixin(['fetchData'], { active: vm => !!vm.resource.id })],
  data () {
    return {
      vm: { ...this.resource },
      formVm: {},
      rows: [],
      context: {},
      networks: {},
      loading: false,
      search: '',
      form: '',
      action: '',
      selected: null,
      ack: false,
      values: {},
      formError: '',
      makeDefault: false,
      candidates: [],
      candidatesLoading: false,
      attachId: undefined,
      progressVisible: false,
      detailKeys: ['id', 'networkid', 'networkname', 'deviceid', 'type', 'macaddress', 'ipaddress', 'netmask', 'gateway', 'ip6address', 'ip6gateway', 'ip6cidr']
    }
  },
  computed: {
    stateAction () { return nicStateAction(this.vm, this.$store.getters.apis) },
    actions () { return ['updateDefaultNicForVirtualMachine', ...(this.stateAction ? [this.stateAction] : []), 'updateVmNicIp'] },
    security () { return JSON.stringify([this.$store.getters.project?.id, this.$store.getters.userInfo?.id, this.$store.state.user.token]) },
    scopeKey () { return this.security + ':' + this.resource.id },
    operation () { return nicOperations[this.scopeKey] },
    busy () { return !!this.operation && this.operation.status !== 'complete' },
    candidate () { return this.candidates.find(n => n.id === this.attachId) },
    filteredRows () { const q = this.search.trim().toLowerCase(); return this.rows.filter(n => [n.networkname, n.ipaddress, n.macaddress].some(v => String(v || '').toLowerCase().includes(q))) },
    columns () { return [{ key: 'network', title: this.$t('label.network') }, { key: 'deviceid', dataIndex: 'deviceid', title: this.$t('label.deviceid') }, { key: 'address', title: this.$t('label.vmnic.address') }, { key: 'state', title: this.$t('label.state') }, { key: 'actions', title: this.$t('label.actions'), width: 150, fixed: 'right' }] }
  },
  watch: { scopeKey () { this.closeForm(); this.rows = []; this.context = {}; this.networks = {}; this.vm = { ...this.resource }; this.search = ''; this.progressVisible = false; this.fetchData() } },
  created () { this.fetchData() },
  methods: {
    allowed (api) { return api in this.$store.getters.apis },
    translateError (error) { return /^(message|label)\./.test(error) ? this.$t(error) : error },
    booleanText (v) { return this.$t(v === true ? 'state.enabled' : v === false ? 'state.disabled' : 'label.vmnic.unknown') },
    nicState (nic) { return this.stateAction === 'UpdateVmNicLinkState' ? nic.linkstate : nic.enabled },
    actionTitle (api, nic) {
      const labels = { createNetwork: 'label.vmnic.create', addNicToVirtualMachine: 'label.vmnic.attach', removeNicFromVirtualMachine: 'label.vmnic.detach', updateDefaultNicForVirtualMachine: 'label.set.default.nic', updateVmNicIp: 'label.change.ipaddress.or.macaddress', addIpToNic: 'label.add.secondary.ip', removeIpFromNic: 'label.action.release.ip', updateVmNic: nic?.enabled ? 'label.vmnic.disable' : 'label.vmnic.enable', UpdateVmNicLinkState: nic?.linkstate ? 'label.vmnic.disable' : 'label.vmnic.enable' }
      return this.$t(labels[api] || 'label.details')
    },
    reason (api, nic) { if (this.listRefreshFailed) return this.$t('message.list.refresh.stale'); const key = nicActionReason(api, nic, this.vm, { ...this.context, rows: this.rows, network: this.networks[nic?.networkid] }); return key ? this.$t(key) : '' },
    async readVm (vm) {
      const [a, b, z, s] = await Promise.all([
        getAPI('listVirtualMachines', { id: vm.id }), getAPI('listNics', { virtualmachineid: vm.id }),
        this.allowed('listZones') ? getAPI('listZones', { id: vm.zoneid }) : Promise.resolve({}),
        this.allowed('listVMSnapshot') ? getAPI('listVMSnapshot', { virtualmachineid: vm.id, page: 1, pagesize: 1, listall: true }) : Promise.resolve({})
      ])
      const fresh = a.listvirtualmachinesresponse.virtualmachine?.find(v => v.id === vm.id)
      if (!fresh) throw new Error(this.$t('message.vmnic.context'))
      // listNics does not populate linkstate on this server; VM NIC responses do.
      const rows = (b.listnicsresponse.nic || []).map(nic => ({ ...nic, linkstate: fresh.nic?.find(item => item.id === nic.id)?.linkstate }))
      return { vm: fresh, rows, zone: z.listzonesresponse?.zone?.[0], snapshots: s.listvmsnapshotresponse ? (s.listvmsnapshotresponse.count || s.listvmsnapshotresponse.vmSnapshot?.length || 0) : null }
    },
    async fetchData () {
      if (!this.resource.id) return
      const token = this.listRequestToken('fetchData'); this.loading = !token.loaded
      try {
        const data = await this.readVm(this.resource)
        const networkPairs = await Promise.all([...new Set(data.rows.map(n => n.networkid))].map(async id => {
          try { const r = await getAPI('listNetworks', { id, listall: true }); return [id, r.listnetworksresponse.network?.[0]] } catch (e) { return [id, undefined] }
        }))
        if (!this.isListRequestCurrent('fetchData', token)) return
        this.vm = data.vm; this.rows = data.rows.sort((a, b) => a.deviceid - b.deviceid); this.context = { zone: data.zone, snapshots: data.snapshots }; this.networks = Object.fromEntries(networkPairs)
        if (this.form === 'secondary' && this.selected) this.selected = this.rows.find(n => n.id === this.selected.id) || this.selected
      } catch (e) { if (this.isListRequestCurrent('fetchData', token)) { token.failed = true; this.listRefreshFailed = true } } finally { if (this.isListRequestCurrent('fetchData', token)) this.loading = false }
    },
    closeForm () { this.form = ''; this.selected = null; this.formError = ''; this.values = {}; this.ack = false },
    openCreate () { this.closeForm(); this.makeDefault = false; this.formVm = { ...this.vm }; this.form = 'create' },
    filterOption (input, option) { return option.label.toLowerCase().includes(input.toLowerCase()) },
    changeCandidate () { this.values.ipaddress = ''; this.formError = '' },
    async openAttach () {
      this.closeForm(); this.form = 'attach'; this.makeDefault = false; this.attachId = undefined; this.candidates = []; this.candidatesLoading = true
      const scope = this.scopeKey
      try {
        let page = 1; let fetched = 0
        do {
          const r = await getAPI('listNetworks', { ...nicOwner(this.vm), zoneid: this.vm.zoneid, canusefordeploy: true, listall: true, page, pagesize: 500 })
          if (this.scopeKey !== scope || this.form !== 'attach' || this.listRefreshDisposed) return
          const data = r.listnetworksresponse; const rows = data.network || []
          this.candidates.push(...rows.filter(n => !this.rows.some(nic => nic.networkid === n.id) && ['Implemented', 'Setup', 'Allocated'].includes(n.state)))
          fetched += rows.length; page++
          if (!rows.length || fetched >= (data.count || fetched)) break
        } while (true)
      } catch (e) { if (this.scopeKey === scope) this.formError = e.message || this.$t('message.list.refresh.stale') } finally { if (this.scopeKey === scope) this.candidatesLoading = false }
    },
    openAction (api, nic) { this.closeForm(); this.selected = { ...nic }; this.action = api; this.values = { ipaddress: nic.ipaddress || '', macaddress: nic.macaddress }; this.form = ['details', 'secondary'].includes(api) ? api : 'action'; if (api === 'secondary') this.values = {} },
    validAddresses () {
      const mac = this.values.macaddress?.trim()
      if (mac && !/^([0-9a-f]{2}:){5}[0-9a-f]{2}$/i.test(mac)) { this.formError = this.$t('message.vmnic.mac'); return false }
      this.formError = ''; return true
    },
    createAndAttach (params) { this.begin(['createNetwork', 'addNicToVirtualMachine', ...(this.makeDefault ? ['updateDefaultNicForVirtualMachine'] : [])], null, null, { create: params }) },
    attach () { if (!this.candidate || !this.validAddresses()) return; this.begin(['addNicToVirtualMachine', ...(this.makeDefault ? ['updateDefaultNicForVirtualMachine'] : [])], null, this.candidate, { ...this.values }) },
    submitAction () { if (!this.ack || !this.validAddresses()) return; this.begin([this.action], this.selected, this.networks[this.selected.networkid], { ...this.values, enabled: !this.selected.enabled, linkstate: !this.selected.linkstate }) },
    addSecondary () { this.begin(['addIpToNic'], this.selected, this.networks[this.selected.networkid], { ...this.values, previousSecondary: (this.selected.secondaryip || []).map(ip => ip.id) }) },
    removeSecondary (ip) { const nic = this.selected; this.openAction('removeIpFromNic', nic); this.values = { secondaryId: ip.id, secondaryAddress: ip.ipaddress } },
    begin (steps, nic, network, values) {
      if (this.busy) return
      const key = this.scopeKey; const security = this.security; const vm = { ...this.vm }; const originalPage = this.$route.path
      const current = () => this.security === security
      const refresh = () => { if (!this.listRefreshDisposed && key === this.scopeKey) this.fetchData() }
      startNicOperation(key, { steps, vm, nic: nic ? { ...nic } : null, network: network ? { ...network } : null, values }, {
        current,
        refresh,
        validate: async op => {
          const api = op.steps[op.stage]
          if (!this.allowed(api)) throw new Error(this.$t('message.vmsnapshot.permission'))
          const data = await this.readVm(vm)
          let target = op.nic && data.rows.find(n => n.id === op.nic.id)
          if (api === 'updateDefaultNicForVirtualMachine' && !target && op.network) target = data.rows.find(n => n.networkid === op.network.id)
          if (target) op.nic = { ...target }
          let latestNetwork
          if (op.network?.id || target?.networkid) {
            const r = await getAPI('listNetworks', { id: op.network?.id || target.networkid, listall: true })
            latestNetwork = r.listnetworksresponse.network?.[0]
            if (!latestNetwork) throw new Error(this.$t('message.vmnic.context'))
          }
          const reason = nicActionReason(api, target, data.vm, { ...data, network: latestNetwork })
          if (reason) throw new Error(this.$t(reason))
          if (api === 'addNicToVirtualMachine' && (!latestNetwork || latestNetwork.zoneid !== vm.zoneid || data.rows.some(n => n.networkid === latestNetwork.id))) throw new Error(this.$t('message.vmnic.candidate'))
          if (api === 'updateVmNicIp' && target.type !== 'L2' && !op.values.ipaddress) op.values.ipaddress = target.ipaddress
          if (api === 'updateVmNicIp' && data.vm.state !== 'Stopped' && op.values.macaddress && op.values.macaddress.trim().toLowerCase() !== target.macaddress?.toLowerCase()) throw new Error(this.$t('message.vmnic.mac.stop'))
          if (api === 'removeIpFromNic' && !target.secondaryip?.some(ip => ip.id === op.values.secondaryId)) throw new Error(this.$t('message.vmnic.context'))
        },
        submit: op => {
          const api = op.steps[op.stage]; let params
          if (api === 'createNetwork') {
            params = { ...op.values.create }; delete params.zoneId; delete params.account; delete params.domainid; delete params.projectid; delete params.subdomainaccess
            Object.assign(params, nicOwner(vm), { zoneid: vm.zoneid, acltype: 'account' })
          } else if (api === 'addNicToVirtualMachine') {
            params = { virtualmachineid: vm.id, networkid: op.network.id }
            if (op.network.type !== 'L2' && op.values.ipaddress) params.ipaddress = op.values.ipaddress.trim()
            if (op.values.macaddress) params.macaddress = op.values.macaddress.trim()
          } else if (api === 'updateVmNicIp') params = nicAddressParams(op.nic, op.values)
          else if (api === 'updateVmNic') params = { nicid: op.nic.id, enabled: op.values.enabled }
          else if (api === 'UpdateVmNicLinkState') params = { virtualmachineid: vm.id, nicid: op.nic.id, linkstate: op.values.linkstate }
          else if (api === 'addIpToNic') params = { nicid: op.nic.id, ...(op.values.ipaddress ? { ipaddress: op.values.ipaddress.trim() } : {}), description: op.values.description }
          else if (api === 'removeIpFromNic') params = { id: op.values.secondaryId }
          else params = { virtualmachineid: vm.id, nicid: op.nic.id }
          return postAPI(api, params)
        },
        poll: op => this.$pollJob({ jobId: op.jobId, retry: true, originalPage, title: this.actionTitle(op.steps[op.stage], op.nic), resourceId: vm.id, action: { api: op.steps[op.stage], resource: vm, isFetchData: true } }),
        reconcile: async op => {
          const api = op.steps[op.stage]
          if (api === 'createNetwork') return !!op.network?.id
          const r = await getAPI('listNics', { virtualmachineid: vm.id }); const rows = r.listnicsresponse.nic || []
          const found = rows.find(n => n.id === op.nic?.id)
          if (api === 'addNicToVirtualMachine') { const attached = rows.find(n => n.networkid === op.network?.id); if (attached) op.nic = { ...attached }; return !!attached }
          if (api === 'removeNicFromVirtualMachine') return !found
          if (!found) return false
          if (api === 'updateDefaultNicForVirtualMachine') return found.isdefault === true
          if (api === 'updateVmNic') return found.enabled === op.values.enabled
          if (api === 'UpdateVmNicLinkState') {
            const response = await getAPI('listVirtualMachines', { id: vm.id })
            return response.listvirtualmachinesresponse.virtualmachine?.[0]?.nic?.find(nic => nic.id === found.id)?.linkstate === op.values.linkstate
          }
          if (api === 'updateVmNicIp') return (!op.values.macaddress || found.macaddress?.toLowerCase() === op.values.macaddress.trim().toLowerCase()) && (found.type === 'L2' || found.ipaddress === nicAddressParams(op.nic, op.values).ipaddress)
          if (api === 'removeIpFromNic') return !(found.secondaryip || []).some(ip => ip.id === op.values.secondaryId)
          if (api === 'addIpToNic') return (found.secondaryip || []).some(ip => op.values.ipaddress ? ip.ipaddress === op.values.ipaddress.trim() : !op.values.previousSecondary.includes(ip.id)) && op.accepted
          return false
        }
      })
      this.closeForm(); this.progressVisible = true
    }
  }
}
</script>

<style scoped lang="scss">
.nic-toolbar { display: flex; flex-wrap: wrap; gap: 8px; margin-bottom: 20px; }
.nic-toolbar :deep(.ant-input-search) { width: 260px; margin-left: auto; }
.nic-row-actions { display: inline-flex; align-items: center; gap: 8px; white-space: nowrap; }
.nic-alert, .nic-fields { margin: 16px 0; }
.nic-dialog-actions { display: flex; justify-content: flex-end; flex-wrap: wrap; gap: 8px; margin-top: 20px; }
.nic-description :deep(.ant-descriptions-item-label) { background: var(--ui-bg-page); color: var(--ui-text-primary); }
.nic-description :deep(.ant-descriptions-item-content) { background: var(--ui-bg-surface); color: var(--ui-text-secondary); overflow-wrap: anywhere; }
.nic-description :deep(.ant-descriptions-view), .nic-description :deep(.ant-descriptions-row), .nic-description :deep(.ant-descriptions-item-label), .nic-description :deep(.ant-descriptions-item-content) { border-color: var(--ui-border); }
@media (max-width: 768px) { .nic-toolbar :deep(.ant-input-search) { width: 100%; } }
</style>

<style lang="scss">
.vm-nic-modal {
  .ant-modal { top: 0; padding-bottom: 0; max-width: calc(100vw - 32px); margin: 0 auto; }
  .ant-modal-content { display: flex; flex-direction: column; max-height: calc(100vh - 48px); overflow: hidden; }
  .ant-modal-header, .ant-modal-footer { flex: 0 0 auto; margin: 0; }
  .ant-modal-header { padding: 16px 24px; }
  .ant-modal-body { flex: 1 1 auto; min-height: 0; overflow-y: auto; overflow-x: hidden; padding: 20px 24px; }
  .ant-modal-footer { display: flex; justify-content: flex-end; flex-wrap: wrap; gap: 8px; padding: 12px 24px; }
  .ant-modal-footer .ant-btn + .ant-btn { margin-left: 0; }
  .form-layout, .form, .ant-form, .ant-spin-nested-loading, .ant-spin-container { width: 100% !important; max-width: 100%; min-width: 0; box-sizing: border-box; margin-left: 0; margin-right: 0; }
  .ant-form-item { width: 100%; margin-left: 0; margin-right: 0; }
  .nic-context { display: grid; grid-template-columns: 104px minmax(0, 1fr); gap: 8px 16px; margin: 0 0 20px; padding: 12px 16px; border: 1px solid var(--ui-border); border-radius: 4px; background: var(--ui-bg-page); }
  .nic-context dt { margin: 0; color: var(--ui-text-secondary); font-weight: 400; }
  .nic-context dd { margin: 0; color: var(--ui-text-primary); overflow-wrap: anywhere; }
  .ant-form-item-control { min-width: 0; }
  .ant-form-item-extra, .ant-form-item-extra *, .ant-form-item-explain { color: var(--ui-text-secondary); }
  .ant-form-item-explain-error, .ant-form-item-explain-error * { color: var(--ui-error-text); }
  .ant-form-item-control-input-content > .ant-input, .ant-form-item-control-input-content > .ant-select, .ant-input-number, .ant-input-affix-wrapper { width: 100%; }
  .ant-descriptions { width: 100%; }
  .ant-descriptions-item-content { overflow-wrap: anywhere; }
  .ant-descriptions-item-label { width: 128px; }
  .ant-modal-title { padding-right: 24px; overflow-wrap: anywhere; }
  @media (max-width: 600px) {
    .ant-modal { max-width: calc(100vw - 24px); }
    .ant-modal-content { max-height: calc(100vh - 24px); }
    .ant-modal-header, .ant-modal-footer { padding-left: 16px; padding-right: 16px; }
    .ant-modal-body { padding: 16px; }
    .ant-descriptions-item-label { width: 96px; }
  }
}
</style>
