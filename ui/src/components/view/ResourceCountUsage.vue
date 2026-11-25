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
  <mold-list-table
    :columns="limitColumns"
    :data-source="limitRows"
    :loading="loading"
    :scope="resource.id || ''"
    row-key="key"
    :pagination="false"
    size="middle">
    <template #bodyCell="{ column, record, text }">
      <TableOverflowText v-if="column.key === 'name'" :text="record.name" />
      <span v-else-if="column.key === 'available'">{{ displayLimit(record.available) }}</span>
      <span v-else-if="column.key === 'usage'">{{ record.total }} / {{ displayLimit(record.limit) }}</span>
      <a-progress
        v-else-if="column.key === 'progress'"
        status="normal"
        :percent="Number(getPercentUsed(record.total, record.limit))"
        :format="p => record.limit !== '-1' && record.limit !== 'Unlimited' ? p.toFixed(2) + '%' : ''" />
      <span v-else>{{ text }}</span>
    </template>
  </mold-list-table>
</template>

<script>
import MoldListTable from '@/components/view/MoldListTable'
import TableOverflowText from '@/components/view/TableOverflowText'

export default {
  name: 'ResourceCountUsageTab',
  components: { MoldListTable, TableOverflowText },
  props: {
    resource: {
      type: Object,
      required: true
    },
    loading: {
      type: Boolean,
      default: false
    }
  },
  data () {
    return {
      usageList: [
        'vm', 'cpu', 'memory', 'gpu', 'primarystorage', 'volume', 'ip', 'network',
        'vpc', 'secondarystorage', 'snapshot', 'template', 'project', 'backup', 'backupstorage', 'bucket', 'objectstorage'
      ],
      taggedUsage: {}
    }
  },
  computed: {
    resourceTypeToNameMap () {
      return {
        0: 'vm',
        8: 'cpu',
        9: 'memory',
        16: 'gpu',
        2: 'volume',
        10: 'primarystorage'
      }
    },
    limitColumns () {
      return [
        { key: 'name', title: this.$t('label.name'), width: 240 },
        { key: 'available', title: this.$t('label.available'), width: 140 },
        { key: 'usage', title: this.$t('label.used') + ' / ' + this.$t('label.limit'), width: 160 },
        { key: 'progress', title: this.$t('label.usage'), width: 160 }
      ]
    },
    limitRows () {
      return this.usageList.filter(name => !(this.$route.meta.name === 'project' && name === 'project')).map(name => {
        const tagged = this.taggedUsage[name]
        return {
          key: name,
          name: this.$t('label.' + name + 'limit'),
          available: this.resource[name + 'available'],
          total: this.resource[name + 'total'],
          limit: this.resource[name + 'limit'],
          ...(tagged?.length ? { children: tagged.map(item => ({ ...item, key: name + ':' + item.tag, name: '#' + item.tag })) } : {})
        }
      })
    }
  },
  created () {
    this.updateTaggedUsage()
  },
  watch: {
    resource: {
      handler () {
        this.updateTaggedUsage()
      }
    }
  },
  methods: {
    displayLimit (value) {
      if (value === '-1' || (this.$route.meta?.name === 'domain' && value === 'Unlimited')) return this.$t('label.unlimited')
      return value
    },
    getPercentUsed (total, limit) {
      return Number(limit) > 0 ? (total / limit) * 100 : 0
    },
    addTaggedUsageToList (resource) {
      const taggedResource = { ...resource }
      var type = this.resourceTypeToNameMap['' + taggedResource.resourcetype]
      if (!type) {
        return
      }
      var typeResourceList = []
      if (this.taggedUsage[type]) {
        typeResourceList = this.taggedUsage[type]
      }
      if (taggedResource.limit === -1) {
        taggedResource.limit = '-1'
      }
      if (taggedResource.available === -1) {
        taggedResource.available = '-1'
      }
      if (['primarystorage'].includes(type)) {
        taggedResource.limit = taggedResource.limit === '-1' ? '-1' : this.$bytesToGiB(taggedResource.limit)
        taggedResource.total = this.$bytesToGiB(taggedResource.total)
        taggedResource.available = taggedResource.available === '-1' ? '-1' : this.$bytesToGiB(taggedResource.available)
      }
      typeResourceList.push(taggedResource)
      typeResourceList = typeResourceList.sort((a, b) => a.tag.localeCompare(b.tag))
      this.taggedUsage[type] = typeResourceList
    },
    updateTaggedUsage () {
      this.taggedUsage = {}
      this.tagData = {}
      if (!this.resource || !this.resource.taggedresources) {
        return
      }
      for (var taggedResource of this.resource.taggedresources) {
        this.addTaggedUsageToList(taggedResource)
      }
    }
  }
}
</script>
