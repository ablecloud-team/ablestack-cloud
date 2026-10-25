<!-- Licensed under the Apache License, Version 2.0. See NOTICE. -->
<template>
  <div class="sharedfs-network-policy">
    <a-space class="network-toolbar" :size="12" wrap>
      <a-button type="primary" :disabled="!canRestart" @click="openRestart"><template #icon><ReloadOutlined /></template>{{ $t('label.action.restart.sharedfs') }}</a-button>
      <a-button :loading="refreshing" @click="$emit('refresh')"><template #icon><ReloadOutlined /></template>{{ $t('label.refresh') }}</a-button>
    </a-space>
    <a-alert class="network-section" show-icon :type="state === 'READY' ? 'success' : state === 'FAILED' ? 'error' : 'warning'" :message="$t('label.sharedfs.network.state.' + state.toLowerCase())" :description="diagnostic" />
    <a-table class="network-section" :columns="columns" :data-source="rows" row-key="key" size="small" :pagination="false" :scroll="{x: 550}" />
    <div class="network-observation network-section">
      {{ $t('label.sharedfs.network.observed') }}: {{ observedAt }}
      <br>{{ $t('label.sharedfs.network.owner') }}: {{ policy?.owner || unknown }}
      <br>NIC: {{ policy?.interface || unknown }} · MAC: {{ policy?.macAddress || unknown }}
    </div>
    <div v-if="routes.length" class="network-subsection">{{ $t('label.sharedfs.network.routes') }}</div>
    <a-table
v-if="routes.length"
class="network-section"
:columns="routeColumns"
:data-source="routes"
row-key="key"
size="small"
:pagination="false"
:scroll="{ x: 500 }" />
    <a-table
v-if="aliases.length"
class="network-section"
:columns="aliasColumns"
:data-source="aliases"
row-key="key"
size="small"
:pagination="false"
:scroll="{x: 500}" />
    <a-alert class="network-section" type="info" show-icon :message="$t('message.sharedfs.network.nic.metadata')" />
    <MoldDialog v-if="dialog" :visible="true" :title="$t('label.action.restart.sharedfs')" size="md" form-actions @cancel="!submitting && (dialog = false)">
      <a-alert class="network-section" type="warning" show-icon :message="$t('message.sharedfs.network.restart.warning')" />
      <a-descriptions bordered :column="1" size="small" class="network-section">
        <a-descriptions-item :label="$t('label.name')">{{ resource.name }}</a-descriptions-item>
        <a-descriptions-item :label="$t('label.sharedfs.network.mode')">{{ resource.networkmode || unknown }}</a-descriptions-item>
        <a-descriptions-item :label="$t('label.ipaddress')">{{ resource.ipaddress || unknown }} / {{ prefix }}</a-descriptions-item>
        <a-descriptions-item :label="$t('label.gateway')">{{ resource.gateway || noGateway }}</a-descriptions-item>
        <a-descriptions-item label="VM">{{ resource.virtualmachineid || unknown }}</a-descriptions-item>
        <a-descriptions-item label="MAC">{{ policy?.macAddress || unknown }}</a-descriptions-item>
      </a-descriptions>
      <a-alert class="network-section" type="info" show-icon :message="$t('message.sharedfs.network.restart.preserve')" />
      <a-checkbox v-model:checked="ack" :disabled="submitting">{{ $t('message.sharedfs.network.restart.ack') }}</a-checkbox>
      <MoldDialogActions :busy="submitting"><a-button :disabled="submitting" @click="dialog = false">{{ $t('label.cancel') }}</a-button><a-button type="primary" :loading="submitting" :disabled="!ack || !canRestart" @click="restart">{{ $t('label.ok') }}</a-button></MoldDialogActions>
    </MoldDialog>
  </div>
</template>
<script>
import { postAPI } from '@/api'
import { ReloadOutlined } from '@ant-design/icons-vue'
import MoldDialog from '@/components/view/MoldDialog'
import MoldDialogActions from '@/components/view/MoldDialogActions'
import { networkPolicyObservation } from '@/utils/sharedfsNetwork'
export default {
  name: 'SharedFSNetworkStatus',
  components: { ReloadOutlined, MoldDialog, MoldDialogActions },
  props: {
    resource: { type: Object, required: true },
    health: { type: Array, default: () => [] },
    protocols: { type: Array, default: () => [] },
    protected: { type: Boolean, default: false },
    refreshing: { type: Boolean, default: false }
  },
  emits: ['refresh', 'restarted'],
  data () { return { dialog: false, ack: false, submitting: false, epoch: 0 } },
  computed: {
    unknown () { return this.$t('label.sharedfs.network.unknown') },
    noGateway () { return this.$t('label.sharedfs.network.no.gateway') },
    observation () { return networkPolicyObservation(this.health) },
    policy () { return this.observation.policy },
    state () { return !this.policy || this.observation.stale ? 'UNKNOWN' : this.policy.status || 'UNKNOWN' },
    prefix () { return String(this.resource.cidr || '').split('/')[1] || this.unknown },
    observedAt () { return this.policy?.observedAt || this.unknown },
    diagnostic () {
      if (!this.policy || this.observation.stale) return this.$t('message.sharedfs.network.observation.unavailable')
      if (this.policy.error) return [this.policy.phase, this.policy.error].filter(Boolean).join(' · ')
      return this.resource.networkmode === 'STATIC' && !this.resource.gateway ? this.$t('message.sharedfs.network.no.gateway') : this.$t('message.sharedfs.network.policy.preserve')
    },
    canRestart () { return !this.submitting && !this.protected && 'restartSharedFileSystem' in this.$store.getters.apis && ['Ready', 'Stopped'].includes(this.resource.state) },
    columns () { return [{ key: 'name', dataIndex: 'name', title: this.$t('label.name'), width: 115 }, { key: 'desired', dataIndex: 'desired', title: this.$t('label.sharedfs.network.desired') }, { key: 'actual', dataIndex: 'actual', title: this.$t('label.sharedfs.network.actual') }] },
    rows () {
      const p = this.policy
      const actual = !p || this.observation.stale
      const primary = p?.addresses?.filter(a => p.mode === 'DHCP' ? !a.secondary : a.local === this.resource.ipaddress).map(a => a.local + '/' + a.prefixlen).join(', ')
      const gateway = p?.routes?.filter(r => r.dst === 'default' && r.gateway && r.dev === p.interface).map(r => r.gateway).join(', ')
      return [
        { key: 'mode', name: this.$t('label.sharedfs.network.mode'), desired: this.resource.networkmode, actual: actual ? this.unknown : p.mode },
        { key: 'address', name: this.$t('label.sharedfs.ip.cidr'), desired: this.resource.networkmode === 'DHCP' ? this.$t('label.dhcp') : [this.resource.ipaddress, this.prefix].join('/'), actual: actual ? this.unknown : primary || this.unknown },
        { key: 'gateway', name: this.$t('label.gateway'), desired: this.resource.networkmode === 'DHCP' ? this.$t('label.dhcp') : this.resource.gateway || this.noGateway, actual: actual ? this.unknown : gateway || this.noGateway },
        { key: 'dns', name: 'DNS', desired: this.resource.networkmode === 'DHCP' ? this.$t('label.dhcp') : [this.resource.dns1, this.resource.dns2].filter(Boolean).join(', ') || this.$t('label.sharedfs.network.unspecified'), actual: actual ? this.unknown : p.dns?.join(', ') || this.$t('label.sharedfs.network.unspecified') }
      ]
    },
    routes () { return (this.policy?.routes || []).map((r, i) => ({ ...r, key: i, dst: r.dst || 'default', gateway: r.gateway || '—', metric: r.metric ?? '—' })) },
    routeColumns () { return [{ title: this.$t('label.sharedfs.network.destination'), dataIndex: 'dst' }, { title: this.$t('label.gateway'), dataIndex: 'gateway' }, { title: 'NIC', dataIndex: 'dev' }, { title: this.$t('label.sharedfs.network.metric'), dataIndex: 'metric' }] },
    aliasColumns () { return [{ title: this.$t('label.ipaddress'), dataIndex: 'ipaddress' }, { title: this.$t('label.protocol'), dataIndex: 'protocol' }, { title: this.$t('label.port'), dataIndex: 'port' }, { title: this.$t('label.state'), dataIndex: 'state' }] },
    aliases () {
      return (this.policy?.aliases || []).flatMap((a, i) => {
        const owners = this.protocols.filter(p => p.listenip === a.ipaddress || p.listenip === '0.0.0.0')
        return (owners.length ? owners : [{}]).map((p, j) => ({ ...a, key: i + ':' + j, protocol: ({ NVME_OF: 'NVMe-oF', ISCSI: 'iSCSI' })[p.protocol] || p.protocol || this.unknown, port: p.port || '—', state: this.$t('label.sharedfs.network.state.' + (this.observation.stale ? 'unknown' : a.active ? 'ready' : 'failed')) }))
      })
    }
  },
  watch: { 'resource.id' () { this.epoch++; this.dialog = false; this.ack = false; this.submitting = false } },
  beforeUnmount () { this.epoch++ },
  methods: {
    openRestart () { if (this.canRestart) { this.ack = false; this.dialog = true } },
    async restart () {
      if (!this.canRestart || !this.ack) return
      this.submitting = true
      const epoch = this.epoch
      const finish = () => { if (epoch === this.epoch) { this.submitting = false; this.dialog = false; this.ack = false; this.$emit('refresh'); this.$emit('restarted') } }
      try {
        const wire = await postAPI('restartSharedFileSystem', { id: this.resource.id, cleanup: false })
        if (epoch !== this.epoch) return
        const jobid = wire.restartsharedfilesystemresponse?.jobid
        if (!jobid) throw new Error(this.$t('message.sharedfs.network.restart.job.missing'))
        this.$pollJob({ jobId: jobid, title: this.$t('label.action.restart.sharedfs'), description: this.resource.name, resourceId: this.resource.id, showLoading: false, successMethod: finish, errorMethod: finish, catchMethod: finish })
      } catch (error) { if (epoch === this.epoch) { this.$notifyError(error); finish() } }
    }
  }
}
</script>
<style scoped lang="less">
.sharedfs-network-policy {
  color: var(--ui-text-primary);
  .network-toolbar { display: flex; justify-content: flex-start; margin-bottom: 16px; }
  .network-section { margin-bottom: 16px; }
  .network-observation { font-size: 12px; line-height: 1.8; color: var(--ui-text-secondary); overflow-wrap: anywhere; }
  :deep(.ant-table), :deep(.ant-table-thead > tr > th), :deep(.ant-table-tbody > tr > td) { color: var(--ui-text-primary); background: var(--ui-bg-surface); border-color: var(--ui-border); }
}
.network-section { margin-bottom: 16px; }
.network-subsection { margin-bottom: 8px; color: var(--ui-text-primary); font-weight: 600; }
</style>
