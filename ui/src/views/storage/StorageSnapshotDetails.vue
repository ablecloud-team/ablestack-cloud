<!-- Licensed to the Apache Software Foundation (ASF) under one or more
contributor license agreements. See the NOTICE file for ownership information.
Licensed under the Apache License, Version 2.0 (the "License"); you may not use
this file except in compliance with the License. http://www.apache.org/licenses/LICENSE-2.0
Unless required by applicable law or agreed to in writing, software is distributed
on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND. -->
<template>
  <div class="snapshot-detail-properties" data-testid="snapshot-detail-properties">
    <a-list class="resource-details-list" size="small" :data-source="fields">
      <template #renderItem="{ item }">
        <a-list-item><div class="snapshot-detail-field">
          <strong>{{ $t('label.snapshot.detail.' + item) }}</strong><br />
          <span v-if="item === 'type'">{{ $t(type(resource)) }}</span>
          <span v-else-if="item === 'interval'">{{ $t(interval(resource.intervaltype)) }}</span>
          <span v-else-if="item === 'volume.type'">{{ $t(['ROOT', 'DATADISK'].includes(resource.volumetype) ? 'label.snapshot.detail.volume.' + resource.volumetype.toLowerCase() : 'label.snapshot.list.unknown') }}</span>
          <Status v-else-if="item === 'volume.state' && detail.volume" :text="detail.volume.state" display-text />
          <span v-else-if="item === 'volume.state'">{{ $t('message.snapshot.detail.source.unavailable') }}</span>
          <Status v-else-if="item === 'vm.state' && detail.vm" :text="detail.vm.state" display-text />
          <span v-else-if="item === 'vm.state'">{{ $t('label.snapshot.list.unknown') }}</span>
          <template v-else-if="item === 'revert'">
            <span>{{ $t(reason ? 'message.snapshot.action.reason.' + reason : 'message.snapshot.detail.revert.ready') }}</span>
            <p class="snapshot-detail-note">{{ $t('message.snapshot.detail.preflight') }}</p>
          </template>
          <span v-else-if="item === 'chainsize'">{{ size(resource.chainsize) }}</span>
          <template v-else-if="item === 'parent'">
            <router-link v-if="detail.parent" :to="link('/snapshot/' + detail.parent.id)">{{ detail.parent.name || detail.parent.id }}</router-link>
            <span v-else>{{ resource.parentname || $t('message.snapshot.detail.chain.absent') }}</span>
            <p v-if="resource.parent && !detail.parent" class="snapshot-detail-note">{{ $t('message.snapshot.detail.parent.unavailable') }}</p>
          </template>
        </div></a-list-item>
      </template>
    </a-list>
    <a-divider />
    <strong>{{ $t('label.snapshot.detail.copies') }}</strong>
    <p class="snapshot-detail-note">{{ $t('message.snapshot.detail.copy.count', [copies.length]) }} {{ $t('message.snapshot.detail.copy.physical') }}</p>
    <a-list class="resource-details-list snapshot-detail-copies" size="small" :data-source="copies" :locale="{ emptyText: $t('message.snapshot.detail.no.copies') }">
      <template #renderItem="{ item }">
        <a-list-item :data-copy-id="item.copy.id"><div class="snapshot-detail-field">
          <strong>{{ $t('label.snapshot.copy.' + (item.copy.role === 'Primary' ? 'primary' : item.copy.role === 'Image' ? 'secondary' : 'unknown')) }}</strong>
          <div>
            <router-link v-if="storePath(item)" :to="storePath(item)">{{ item.datastorename || item.datastoreid }}</router-link>
            <span v-else>{{ item.datastorename || $t(item.copy.datastoreknown ? 'label.snapshot.copy.restricted.store' : 'label.snapshot.copy.unknown.store') }}</span>
            <span> · {{ item.zonename || $t('label.snapshot.list.unknown') }}</span>
          </div>
          <div class="snapshot-detail-copy-facts"><Status :text="item.copy.state || 'Unknown'" display-text />
            <span>{{ $t('label.snapshot.copy.' + item.copy.availability.toLowerCase()) }}</span>
            <span>{{ $t('label.virtualsize') }}: {{ size(item.copy.virtualsize) }}</span>
          </div>
        </div></a-list-item>
      </template>
    </a-list>
  </div>
</template>
<script>
import Status from '@/components/widgets/Status'
import { snapshotListSize, snapshotListInterval } from '@/utils/storageSnapshotPresentation'
import { snapshotDetailType, snapshotDetailRevertReason, snapshotDetailStorePath, snapshotDetailRouteAllowed } from '@/utils/storageSnapshotDetail'
export default {
  name: 'StorageSnapshotDetails',
  components: { Status },
  props: { resource: { type: Object, required: true }, loading: Boolean },
  computed: {
    detail () { return this.resource.snapshotdetail || {} },
    copies () { return this.detail.copies || [] },
    reason () { return snapshotDetailRevertReason(this.resource) },
    fields () { return ['type', 'interval', 'volume.type', ...('listVolumes' in this.$store.getters.apis ? ['volume.state'] : []), ...('listVirtualMachines' in this.$store.getters.apis && this.detail.volume?.virtualmachineid ? ['vm.state'] : []), 'revert', ...(this.$store.getters.features.snapshotshowchainsize === true ? ['chainsize', 'parent'] : [])] }
  },
  methods: {
    type: snapshotDetailType,
    interval: snapshotListInterval,
    size (value) { return snapshotListSize(value) ?? this.$t('label.snapshot.list.unknown') },
    storePath (row) { const path = snapshotDetailStorePath(row, this.$store.getters.apis); return path && snapshotDetailRouteAllowed(this.$router, path) ? path : null },
    link (path) { return { path, ...(this.resource.projectid ? { query: { projectid: this.resource.projectid } } : {}) } }
  }
}
</script>
<style scoped>
.snapshot-detail-properties, .snapshot-detail-field { min-width: 0; width: 100%; overflow-wrap: anywhere; }
.snapshot-detail-note { margin: 4px 0; color: var(--ui-muted, inherit); }
.snapshot-detail-copy-facts { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; margin-top: 4px; }
</style>
