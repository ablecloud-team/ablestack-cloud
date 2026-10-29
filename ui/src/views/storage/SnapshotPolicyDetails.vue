<!-- Licensed to the Apache Software Foundation (ASF) under one or more
contributor license agreements. See NOTICE for ownership information.
Licensed under the Apache License, Version 2.0 (the "License");
http://www.apache.org/licenses/LICENSE-2.0 . Distributed AS IS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND. -->
<template>
  <div class="snapshot-policy-details" data-testid="snapshot-policy-details">
    <a-list class="resource-details-list" size="small" :data-source="fields">
      <template #renderItem="{ item }">
        <a-list-item><div class="policy-detail-field">
          <strong>{{ $t(item.label || 'label.' + item.key) }}</strong><br />
          <span v-if="item.key === 'intervaltype'">{{ interval(resource.intervaltype) }}</span>
          <span v-else-if="item.key === 'schedule'">{{ schedule(resource) }}</span>
          <span v-else-if="item.key === 'schedulingenabled'">{{ scheduling(resource) }}</span>
          <span v-else-if="item.key === 'fordisplay'">{{ $t(resource.fordisplay ? 'label.yes' : 'label.no') }}</span>
          <template v-else-if="['zone', 'storage'].includes(item.key)">
            <div v-for="entry in resource[item.key]" :key="entry.id">
              <router-link v-if="allowed(item.key === 'zone' ? 'listZones' : 'listStoragePools')" :to="path((item.key === 'zone' ? '/zone/' : '/storagepool/') + entry.id)">{{ entry.name || entry.id }}</router-link>
              <span v-else>{{ entry.name || entry.id }}</span>
            </div><span v-if="!resource[item.key]?.length">{{ $t('label.snapshot.list.unknown') }}</span>
          </template>
          <template v-else-if="item.key === 'tags'"><a-tag v-for="tag in resource.tags" :key="tag.key">{{ tag.key }}={{ tag.value }}</a-tag><span v-if="!resource.tags?.length">—</span></template>
          <router-link v-else-if="item.key === 'volumename' && volumePath" :to="volumePath">{{ resource.volumename || resource.volumeid }}</router-link>
          <span v-else>{{ resource[item.key] ?? '—' }}</span>
        </div></a-list-item>
      </template>
    </a-list>
    <a-divider />
    <a-list class="resource-details-list" size="small" :data-source="operationalFields">
      <template #renderItem="{ item }"><a-list-item><div class="policy-detail-field">
        <strong>{{ $t('label.snapshot.policy.detail.' + item) }}</strong><br />
        <template v-if="item === 'next'">
          <span>{{ context.nextscheduled ? date(context.nextscheduled) : $t('message.snapshot.policy.detail.next.' + String(context.schedulestate || 'UNKNOWN').toLowerCase()) }}</span>
          <p class="policy-detail-note">{{ $t('message.snapshot.policy.detail.poll', { seconds: context.pollintervalseconds }) }}</p>
        </template>
        <template v-else-if="item === 'latest'">
          <span>{{ $t(job ? 'label.snapshot.policy.detail.job.' + job.status.toLowerCase() : 'message.snapshot.policy.detail.history.unknown') }}</span>
          <template v-if="job"><div>{{ $t('label.id') }}: {{ job.id }}</div><div>{{ $t('label.created') }}: {{ date(job.created) }}</div><div v-if="job.updated">{{ $t('label.updated') }}: {{ date(job.updated) }}</div><div v-if="job.status === 'FAILED'">{{ $t('message.snapshot.policy.detail.failed', { code: job.errorcode ?? '—' }) }}</div></template>
          <p class="policy-detail-note">{{ $t('message.snapshot.policy.detail.history', { limit: context.historylimit }) }}</p>
        </template>
        <template v-else-if="item === 'snapshot'">
          <router-link v-if="job?.snapshotavailable && allowed('listSnapshots')" :to="path('/snapshot/' + job.snapshotid)">{{ job.snapshotname || job.snapshotid }}</router-link>
          <span v-else>{{ $t('message.snapshot.policy.detail.snapshot.unavailable') }}</span>
        </template>
        <span v-else>{{ date(context.observedat) }}</span>
      </div></a-list-item></template>
    </a-list>
    <p class="policy-detail-note">{{ $t('message.snapshot.policy.detail.pause.meaning') }}</p>
  </div>
</template>
<script>
import { policyInterval, policySchedule, policyScheduling } from '@/utils/snapshotPolicyPresentation'
import { policyVolumePath } from '@/utils/snapshotPolicyDetail'
export default {
  name: 'SnapshotPolicyDetails',
  props: { resource: { type: Object, required: true }, loading: Boolean },
  computed: {
    volumePath () { return policyVolumePath(this.resource, this.$store.getters.apis) },
    context () { return this.resource.policycontext || {} },
    job () { return this.context.latestjob },
    operationalFields () { return ['next', 'latest', 'snapshot', 'observed'] },
    fields () { return ['id', 'volumename', 'intervaltype', 'schedule', 'timezone', 'maxsnaps', 'schedulingenabled', ...(typeof this.resource.fordisplay === 'boolean' ? ['fordisplay'] : []), 'zone', 'storage', 'tags', ...(this.resource.projectid ? ['project', 'projectid'] : ['account']), 'domain', 'domainid'].map(key => ({ key, ...(['schedulingenabled', 'fordisplay'].includes(key) ? { label: 'label.snapshot.policy.detail.' + key } : {}) })) }
  },
  methods: {
    interval (value) { return policyInterval(value, this.$t) },
    schedule (value) { return policySchedule(value, this.$t) },
    scheduling (value) { return policyScheduling(value, this.$t) },
    allowed (api) { return api in (this.$store.getters.apis || {}) },
    path (path) { return { path, ...(this.resource.projectid ? { query: { projectid: this.resource.projectid } } : {}) } },
    date (value) { return Number.isFinite(Date.parse(value)) ? this.$toLocaleDate(value) : this.$t('label.snapshot.list.unknown') }
  }
}
</script>
<style scoped>
.snapshot-policy-details, .policy-detail-field { min-width: 0; width: 100%; overflow-wrap: anywhere; }
.policy-detail-note { margin: 4px 0; color: var(--ui-muted, inherit); }
</style>
