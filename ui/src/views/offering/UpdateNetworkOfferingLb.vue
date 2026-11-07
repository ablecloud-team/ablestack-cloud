// Licensed to the Apache Software Foundation (ASF) under one or more
// contributor license agreements. See the NOTICE file distributed with this
// work for additional information regarding copyright ownership. The ASF
// licenses this file under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License. You may
// obtain a copy at http://www.apache.org/licenses/LICENSE-2.0 . Unless required
// by applicable law or agreed to in writing, software distributed under the
// License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS.

<template>
  <MoldDialog class="network-offering-lb-dialog" size="md" :title="$t('label.offering.lb.settings')" :closable="!saving" :keyboard="!saving" @cancel="$emit('close-action')">
    <strong>{{ resource.name }}</strong>
    <p class="identity">{{ resource.guestiptype }} · {{ resource.id }}</p>
    <a-alert v-if="error" type="error" show-icon :message="error" />
    <div class="lb-toolbar"><a-button :loading="loading" :disabled="saving" :aria-label="$t('label.refresh')" @click="load"><reload-outlined />{{ $t('label.refresh') }}</a-button></div>
    <a-spin :spinning="loading">
      <a-form layout="vertical" @submit.prevent="save">
        <NetworkOfferingLbFields v-if="current" v-model="draft" :offering="current" />
      </a-form>
    </a-spin>
    <template #footer>
      <a-button :disabled="saving" @click="$emit('close-action')">{{ $t('label.cancel') }}</a-button>
      <a-button type="primary" :loading="saving" :disabled="!canSave" @click="save">{{ $t('label.ok') }}</a-button>
    </template>
  </MoldDialog>
</template>
<script>
import MoldDialog from '@/components/view/MoldDialog'
import NetworkOfferingLbFields from './NetworkOfferingLbFields'
import { getAPI } from '@/utils/storageApi'
import { postAPI } from '@/api'
import { offeringLbSupport, lbSettingsParams } from '@/utils/networkOfferingLb'
export default {
  name: 'UpdateNetworkOfferingLb',
  components: { MoldDialog, NetworkOfferingLbFields },
  props: { resource: { type: Object, required: true } },
  emits: ['close-action', 'refresh-data'],
  data () { return { current: null, draft: {}, loading: false, saving: false, error: '', generation: 0, readReady: false } },
  computed: { canSave () { return !!this.current && this.readReady && !this.loading && !this.saving && !offeringLbSupport(this.current) } },
  watch: { 'resource.id': { immediate: true, handler () { this.load() } } },
  beforeUnmount () { this.generation++ },
  methods: {
    async load () {
      const generation = ++this.generation
      const id = this.resource.id
      this.loading = true
      this.readReady = false
      this.saving = false
      if (this.current?.id !== id) { this.current = null; this.draft = {} }
      this.error = ''
      try {
        const response = await getAPI('listNetworkOfferings', { id })
        const offering = response.listnetworkofferingsresponse?.networkoffering?.find(o => o.id === id)
        if (!offering) throw new Error(this.$t('message.offering.lb.read.failed'))
        if (generation !== this.generation) return
        this.current = offering
        this.readReady = true
        this.draft = { maxconnections: offering.maxconnections, keepaliveenabled: offering.keepaliveenabled }
      } catch (e) {
        if (generation !== this.generation) return
        this.error = this.$t('message.offering.lb.read.failed')
      } finally { if (generation === this.generation) this.loading = false }
    },
    async save () {
      if (!this.canSave) return
      let params
      try { params = lbSettingsParams(this.draft, this.current, true) } catch (e) { this.error = this.$t('message.offering.lb.range'); return }
      if (!Object.keys(params).length) { this.$emit('close-action'); return }
      const generation = this.generation
      const id = this.current.id
      this.saving = true
      this.error = ''
      try {
        await postAPI('updateNetworkOffering', { id, ...params })
        const result = await getAPI('listNetworkOfferings', { id })
        const row = result.listnetworkofferingsresponse?.networkoffering?.find(o => o.id === id)
        if (!row || Object.entries(params).some(([key, value]) => row[key] !== value)) throw new Error(this.$t('message.offering.lb.read.failed'))
        if (generation !== this.generation) return
        this.$message.success(this.$t('message.offering.lb.saved'))
        this.$emit('refresh-data')
        this.$emit('close-action')
      } catch (e) {
        if (generation === this.generation) this.error = e?.response?.data?.updatenetworkofferingresponse?.errortext || e.message || this.$t('message.offering.lb.read.failed')
      } finally { if (generation === this.generation) this.saving = false }
    }
  }
}
</script>
<style scoped>
.identity { overflow-wrap: anywhere; color: var(--ui-text-secondary); font-size: 14px; }
.lb-toolbar { display: flex; justify-content: flex-end; margin: 12px 0; }
</style>
