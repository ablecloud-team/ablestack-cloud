<!-- Licensed to the Apache Software Foundation (ASF) under one or more
contributor license agreements. See the NOTICE file for ownership information.
Licensed under the Apache License, Version 2.0 (the "License"); you may not use
this file except in compliance with the License. http://www.apache.org/licenses/LICENSE-2.0
Unless required by applicable law or agreed to in writing, software is distributed
on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND. -->
<template>
  <div class="snapshot-detail-summary" data-testid="snapshot-detail-summary">
    <div v-for="key in ['virtualsize', 'physicalsize']" :key="key" class="resource-detail-item">
      <div class="resource-detail-item__label">{{ $t(key === 'physicalsize' ? 'label.snapshot.detail.backup.size' : 'label.virtualsize') }}</div>
      <div class="resource-detail-item__details"><hdd-outlined /> {{ size(key === 'physicalsize' ? backupPhysicalSize : resource[key]) }}</div>
    </div>
    <div class="resource-detail-item">
      <div class="resource-detail-item__label">{{ $t('label.volume') }}</div>
      <div class="resource-detail-item__details"><hdd-outlined />
        <router-link v-if="volume && canLink('listVolumes', '/volume/' + volume.id)" :to="link('/volume/' + volume.id)">{{ volume.name || resource.volumename || volume.id }}</router-link>
        <span v-else>{{ resource.volumename || resource.volumeid || $t('label.snapshot.list.unknown') }}</span>
      </div>
      <p v-if="!volume" class="snapshot-detail-note">{{ $t(permitted('listVolumes') ? 'message.snapshot.detail.source.unavailable' : 'message.snapshot.detail.source.restricted') }}</p>
    </div>
    <div class="resource-detail-item">
      <div class="resource-detail-item__label">{{ $t('label.vmname') }}</div>
      <div class="resource-detail-item__details"><desktop-outlined />
        <router-link v-if="vm && canLink('listVirtualMachines', '/vm/' + vm.id)" :to="link('/vm/' + vm.id)">{{ vm.displayname || vm.name || vm.id }}</router-link>
        <span v-else>{{ $t(!permitted('listVirtualMachines') ? 'message.snapshot.detail.vm.restricted' : volume && !volume.virtualmachineid ? 'message.snapshot.detail.vm.none' : 'message.snapshot.detail.vm.unavailable') }}</span>
      </div>
    </div>
    <div class="resource-detail-item">
      <div class="resource-detail-item__label">{{ $t('label.snapshot.detail.copy.zones') }}</div>
      <div class="resource-detail-item__details snapshot-detail-zones">
        <span v-for="zone in zones" :key="zone.id || 'unknown'"><global-outlined />
          <router-link v-if="zone.id && canLink('listZones', '/zone/' + zone.id)" :to="'/zone/' + zone.id">{{ zone.name || zone.id }}</router-link>
          <span v-else>{{ zone.name || $t('label.snapshot.list.unknown') }}</span>
        </span>
        <span v-if="!zones.length">{{ $t('message.snapshot.detail.no.copies') }}</span>
      </div>
    </div>
  </div>
</template>
<script>
import { snapshotDetailRouteAllowed } from '@/utils/storageSnapshotDetail'
import { snapshotListSize } from '@/utils/storageSnapshotPresentation'
export default {
  name: 'StorageSnapshotSummary',
  props: { resource: { type: Object, required: true } },
  computed: {
    backupPhysicalSize () { return (this.resource.snapshotdetail?.copies || []).some(row => row.copy?.role === 'Image' && row.copy.state === 'Ready') ? this.resource.physicalsize : undefined },
    volume () { return this.resource.snapshotdetail?.volume },
    vm () { return this.resource.snapshotdetail?.vm },
    zones () { return [...new Map((this.resource.snapshotdetail?.copies || []).map(row => [row.zoneid || '', { id: row.zoneid, name: row.zonename }])).values()] }
  },
  methods: {
    permitted (api) { return api in this.$store.getters.apis },
    canLink (api, path) { return this.permitted(api) && snapshotDetailRouteAllowed(this.$router, path) },
    size (value) { return snapshotListSize(value) ?? this.$t('label.snapshot.list.unknown') },
    link (path) { return { path, ...(this.resource.projectid ? { query: { projectid: this.resource.projectid } } : {}) } }
  }
}
</script>
<style scoped>
.snapshot-detail-summary { min-width: 0; }
.snapshot-detail-summary .resource-detail-item__details { overflow-wrap: anywhere; flex-wrap: wrap; }
.snapshot-detail-note { margin: 4px 0 0; color: var(--ui-muted, inherit); overflow-wrap: anywhere; }
.snapshot-detail-summary .snapshot-detail-zones { display: flex; flex-direction: column; align-items: flex-start; gap: 4px; text-align: left; }
</style>

<style scoped src="@/style/components/view/resource-detail-fields.css"></style>
