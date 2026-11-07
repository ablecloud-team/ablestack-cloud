// Licensed to the Apache Software Foundation (ASF) under one or more
// contributor license agreements. See the NOTICE file for ownership information.
// Licensed under the Apache License, Version 2.0; http://www.apache.org/licenses/LICENSE-2.0
// Distributed on an AS IS BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND.

<template>
  <section class="network-permissions" data-testid="network-permissions">
    <div class="detail-tab-toolbar">
      <a-button v-if="supports('createNetworkPermissions')" type="primary" :disabled="!ready" @click="open('grant')">
        <template #icon><plus-outlined /></template>
        {{ $t('label.add.network.permission') }}
      </a-button>
      <a-button v-if="supports('resetNetworkPermissions')" :disabled="!ready || !rows.length" @click="open('reset')">
        {{ $t('label.action.reset.network.permissions') }}
      </a-button>
      <a-button :disabled="busy" :loading="reading" @click="refresh">
        <template #icon><reload-outlined /></template>
        {{ $t('label.refresh') }}
      </a-button>
    </div>
    <a-alert type="info" show-icon class="detail-tab-notice" :message="$t('message.network.permission.effect')" />
    <a-alert v-if="readError" type="error" show-icon class="detail-tab-notice" :message="translate(readError)" />
    <a-alert v-if="writeError && !dialog" type="warning" show-icon class="detail-tab-notice" :message="translate(writeError)" />
    <a-table
      v-if="loaded"
      size="small"
      :columns="columns"
      :data-source="rows"
      :row-key="row => row.projectid || row.accountid"
      :scroll="{ x: 560 }"
      :pagination="{ pageSize: 10, hideOnSinglePage: true }">
      <template #bodyCell="{ column, record }">
        <template v-if="column.key === 'target'">{{ record.project || record.account }}</template>
        <template v-else-if="column.key === 'kind'">{{ $t(record.projectid ? 'label.project' : 'label.account') }}</template>
        <a-button v-else-if="column.key === 'actions' && supports('removeNetworkPermissions')" danger :disabled="!ready" @click="open('revoke', record)">
          {{ $t('label.action.delete.network.permission') }}
        </a-button>
      </template>
    </a-table>
    <a-spin v-else-if="reading" />
    <CreateNetworkPermission
      v-if="dialog === 'grant'"
      :resource="resource"
      :permissions="rows"
      :busy="busy"
      :ready="ready"
      :error="writeError || readError"
      @refresh="refresh"
      @submit="grant"
      @close-action="close" />
    <MoldDialog
      v-else-if="dialog"
      size="md"
      focus-guard
      :title="$t(dialog === 'reset' ? 'label.action.reset.network.permissions' : 'label.action.delete.network.permission')"
      :closable="!busy"
      :keyboard="!busy"
      @cancel="close">
      <p>{{ $t('message.network.permission.effect') }}</p>
      <p v-if="target">{{ target.project || target.account }}</p>
      <p>{{ $t(dialog === 'reset' ? 'message.confirm.reset.network.permissions' : 'message.confirm.remove.network.permission') }}</p>
      <a-alert v-if="writeError || readError" type="error" show-icon :message="translate(writeError || readError)" />
      <template #footer>
        <a-button v-if="readError" :loading="reading" :disabled="busy" @click="refresh">{{ $t('label.retry') }}</a-button>
        <a-button :disabled="busy" @click="close">{{ $t('label.cancel') }}</a-button>
        <a-button danger type="primary" :loading="busy" :disabled="!ready" @click="revoke">{{ $t('label.ok') }}</a-button>
      </template>
    </MoldDialog>
  </section>
</template>

<script>
import { getAPI, postAPI } from '@/api'
import MoldDialog from '@/components/view/MoldDialog'
import CreateNetworkPermission from './CreateNetworkPermission.vue'
import { detailTabContext, detailTabDenied } from '@/utils/detailTabData'
import { networkError } from '@/utils/networkLifecycle'
import { canReadNetworkPermissions, permissionGrant, permissionRows } from '@/utils/networkPermissions'

const commands = ['listNetworkPermissions', 'createNetworkPermissions', 'removeNetworkPermissions', 'resetNetworkPermissions']

export default {
  name: 'NetworkPermissions',
  components: { MoldDialog, CreateNetworkPermission },
  props: { resource: { type: Object, required: true } },
  data () {
    return { rows: [], loaded: false, reading: false, busy: false, readError: '', writeError: '', dialog: '', target: null, version: 0 }
  },
  computed: {
    allowed () {
      const routeId = this.$route.params?.id
      return (!routeId || routeId === this.resource.id) && canReadNetworkPermissions(this.resource, this.$store.getters)
    },
    scope () { return JSON.stringify([detailTabContext(this, commands), this.resource.domainid, this.resource.account, this.allowed]) },
    ready () { return this.allowed && this.loaded && !this.reading && !this.busy && !this.readError },
    columns () {
      return [
        { title: this.$t('label.domain'), dataIndex: 'domain', width: 150 },
        { title: this.$t('label.type'), key: 'kind', width: 95 },
        { title: this.$t('label.name'), key: 'target' },
        ...(this.supports('removeNetworkPermissions') ? [{ key: 'actions', title: this.$t('label.actions'), width: 150 }] : [])
      ]
    }
  },
  watch: {
    scope: {
      immediate: true,
      flush: 'sync',
      handler () {
        this.version++
        this.rows = []; this.loaded = false; this.reading = false; this.busy = false
        this.readError = ''; this.writeError = ''; this.dialog = ''; this.target = null
        this.refresh()
      }
    }
  },
  beforeUnmount () { this.version++ },
  methods: {
    supports (command) { return this.allowed && command in this.$store.getters.apis },
    translate (message) { return message.startsWith('message.') ? this.$t(message) : message },
    async refresh () {
      if (!this.allowed || this.reading) return false
      const version = this.version
      const network = { id: this.resource.id, domainid: this.resource.domainid }
      this.reading = true
      try {
        const json = await getAPI('listNetworkPermissions', { networkid: network.id, ignoreproject: true }, { preserveOnFailure: true, timeout: 20000 })
        if (version !== this.version) return false
        this.rows = permissionRows(json, network)
        this.loaded = true
        this.readError = ''
        return true
      } catch (error) {
        if (version === this.version) {
          this.readError = networkError(error).text || 'message.detail.tab.read.failed'
          if (detailTabDenied(error)) { this.rows = []; this.loaded = false; this.dialog = ''; this.target = null }
        }
        return false
      } finally {
        if (version === this.version) this.reading = false
      }
    },
    open (mode, target = null) {
      if (!this.ready) return
      this.writeError = ''
      this.target = target
      this.dialog = mode
    },
    close () {
      if (this.busy) return
      this.dialog = ''; this.target = null
    },
    grant (draft) {
      if (this.dialog !== 'grant') return
      const params = permissionGrant(draft, this.resource, this.rows)
      if (!Object.keys(params).length) {
        this.writeError = 'message.network.permission.target.required'
        return
      }
      return this.write('createNetworkPermissions', params, rows =>
        Object.entries(params).every(([key, ids]) => ids.split(',').every(id => rows.some(row =>
          key === 'projectids' ? row.projectid === id : key === 'accountids' ? row.accountid === id : !row.projectid && row.account === id))))
    },
    revoke () {
      if (this.dialog === 'reset') return this.write('resetNetworkPermissions', {}, rows => !rows.length)
      const target = this.target
      if (!target || !this.rows.some(row => row.accountid === target.accountid)) return
      const params = target.projectid ? { projectids: target.projectid } : { accountids: target.accountid }
      return this.write('removeNetworkPermissions', params, rows => !rows.some(row => row.accountid === target.accountid))
    },
    async write (command, params, confirmed) {
      if (!this.ready || !this.supports(command)) return
      const version = this.version
      this.busy = true
      this.writeError = ''
      try {
        const json = await postAPI(command, { ...params, networkid: this.resource.id, ignoreproject: true }, { preserveOnFailure: true, timeout: 20000 })
        if (version !== this.version) return
        const body = json?.[command.toLowerCase() + 'response']
        if (![true, 'true'].includes(body?.success)) throw new Error('message.network.permission.unverified')
        if (await this.refresh() && version === this.version) {
          if (!confirmed(this.rows)) this.writeError = 'message.network.permission.unverified'
          else { this.dialog = ''; this.target = null }
        }
      } catch (error) {
        if (version !== this.version) return
        const detail = networkError(error)
        // Invalid grant targets are validation errors. The caller can correct
        // the current draft without discarding it or reloading its candidates.
        const denied = Number(detail.code) !== 431 && detailTabDenied(error)
        this.writeError = detail.definitive ? detail.text : 'message.network.permission.unverified'
        if (!detail.definitive || denied) {
          this.readError = 'message.network.permission.unverified'
          if (denied) { this.rows = []; this.loaded = false; this.dialog = ''; this.target = null }
        }
      } finally {
        if (version === this.version) this.busy = false
      }
    }
  }
}
</script>

<style lang="scss" scoped>
@import '@/style/components/view/DetailTab.scss';
.network-permissions { min-width: 0; overflow-wrap: anywhere; }
</style>
